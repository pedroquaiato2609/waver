package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PriceBook (RFC-0017 §4): wholesale and retail prices of SKUs for one scope and currency, valid in a window.
 * DRAFT → ACTIVE → RETIRED. Prices change only in a draft; activating a book retires the active book of the
 * same scope and currency, which stays readable (quotes and contracts snapshot prices, RFC-0013).
 *
 * Activation needs approval BASE_PRICE_CHANGE; until approval-service exists WAVER Commercial activates
 * directly and the activation is audited.
 */
public final class PriceBook {

    public enum Status { DRAFT, ACTIVE, RETIRED }

    private final UUID id;
    private final String name;
    private final Currency currency;
    private final PriceScope scope;
    private final String scopeRef;
    private final Instant validFrom;
    private final Instant validTo;
    private final Map<UUID, PriceEntry> entries;
    private Status status;
    private Instant activatedAt;
    private String activatedBy;
    private long version;
    private final Instant createdAt;

    private PriceBook(UUID id, String name, Currency currency, PriceScope scope, String scopeRef, Instant validFrom, Instant validTo,
                      List<PriceEntry> entries, Status status, Instant activatedAt, String activatedBy, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.currency = Objects.requireNonNull(currency);
        this.scope = Objects.requireNonNull(scope);
        this.scopeRef = scopeRef;
        this.validFrom = Objects.requireNonNull(validFrom);
        this.validTo = validTo;
        this.entries = new LinkedHashMap<>();
        entries.forEach(e -> this.entries.put(e.skuId(), e));
        this.status = Objects.requireNonNull(status);
        this.activatedAt = activatedAt;
        this.activatedBy = activatedBy;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** A new draft. validFrom defaults to now; validTo is optional and must be after validFrom. */
    public static PriceBook draft(String name, String currencyCode, PriceScope scope, String scopeRef, Instant validFrom,
                                  Instant validTo, Instant now) {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 120) {
            throw invalid("CATALOG_PRICE_BOOK_NAME_INVALID", "Price book name must have between 2 and 120 characters.");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode == null ? "" : currencyCode.strip());
        } catch (IllegalArgumentException e) {
            throw invalid("CATALOG_CURRENCY_INVALID", "Inform an ISO 4217 currency, e.g. BRL.");
        }
        if (scope == null) {
            throw invalid("CATALOG_SCOPE_INVALID", "Inform the scope.");
        }
        String ref = scope.normalizeRef(scopeRef);
        Instant from = validFrom == null ? now : validFrom;
        if (validTo != null && !validTo.isAfter(from)) {
            throw invalid("CATALOG_VALIDITY_INVALID", "The end of validity must be after its start.");
        }
        return new PriceBook(Ids.newId(), n, currency, scope, ref, from, validTo, List.of(), Status.DRAFT, null, null, 0, now);
    }

    public static PriceBook restore(UUID id, String name, Currency currency, PriceScope scope, String scopeRef, Instant validFrom,
                                    Instant validTo, List<PriceEntry> entries, Status status, Instant activatedAt, String activatedBy,
                                    long version, Instant createdAt) {
        return new PriceBook(id, name, currency, scope, scopeRef, validFrom, validTo, entries, status, activatedAt, activatedBy,
                version, createdAt);
    }

    /** Adds or replaces the price of a SKU. Draft only. */
    public void putEntry(PriceEntry entry) {
        requireDraft();
        entries.put(entry.skuId(), entry);
        version++;
    }

    public void removeEntry(UUID skuId) {
        requireDraft();
        if (entries.remove(skuId) == null) {
            throw new DomainException(DomainException.Kind.NOT_FOUND, "CATALOG_PRICE_ENTRY_NOT_FOUND", "This SKU has no price in the book.");
        }
        version++;
    }

    /**
     * DRAFT → ACTIVE. {@code current} is the book now active for the same scope, reference and currency, if any:
     * it is retired by this activation. Returns its ID so the event can say what was superseded.
     */
    public Optional<UUID> activate(String actor, Optional<PriceBook> current, Instant now) {
        requireDraft();
        if (entries.isEmpty()) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "CATALOG_PRICE_BOOK_EMPTY",
                    "Add at least one SKU price before activating the book.");
        }
        if (validTo != null && !validTo.isAfter(now)) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "CATALOG_PRICE_BOOK_EXPIRED",
                    "This book's validity has already ended.");
        }
        Optional<UUID> superseded = current.filter(c -> !c.id.equals(id)).map(c -> {
            if (c.scope != scope || !Objects.equals(c.scopeRef, scopeRef) || !c.currency.equals(currency) || c.status != Status.ACTIVE) {
                throw new IllegalArgumentException("not the active book of the same scope and currency");
            }
            c.status = Status.RETIRED;
            c.version++;
            return c.id;
        });
        status = Status.ACTIVE;
        activatedAt = now;
        activatedBy = actor;
        version++;
        return superseded;
    }

    public boolean validAt(Instant at) {
        return !validFrom.isAfter(at) && (validTo == null || at.isBefore(validTo));
    }

    /** Usable for this reseller now: active, valid, in scope. */
    public boolean appliesTo(ResellerRef reseller, Instant at) {
        return status == Status.ACTIVE && validAt(at) && scope.matches(scopeRef, reseller);
    }

    public Optional<PriceEntry> entry(UUID skuId) {
        return Optional.ofNullable(entries.get(skuId));
    }

    private void requireDraft() {
        if (status != Status.DRAFT) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "CATALOG_PRICE_BOOK_NOT_DRAFT",
                    "Prices of an active or retired book cannot change. Create a new draft book.");
        }
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public Currency currency() { return currency; }
    public PriceScope scope() { return scope; }
    public Optional<String> scopeRef() { return Optional.ofNullable(scopeRef); }
    public Instant validFrom() { return validFrom; }
    public Optional<Instant> validTo() { return Optional.ofNullable(validTo); }
    public List<PriceEntry> entries() { return List.copyOf(entries.values()); }
    public Status status() { return status; }
    public Optional<Instant> activatedAt() { return Optional.ofNullable(activatedAt); }
    public Optional<String> activatedBy() { return Optional.ofNullable(activatedBy); }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
