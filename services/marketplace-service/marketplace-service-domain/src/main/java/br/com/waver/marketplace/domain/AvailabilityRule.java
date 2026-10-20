package br.com.waver.marketplace.domain;

import br.com.waver.kernel.error.DomainException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AvailabilityRule (RFC-0017 §5): which resellers may sell a listing. ALL_RESELLERS, the resellers of some tiers
 * (include = tier keys) or named resellers (include = organization IDs). {@code exclude} lists resellers that never
 * see it, in any mode. Optional validity window. The same rule is enforced by Row-Level Security
 * (listing_available_to_caller) for reseller reads.
 */
public record AvailabilityRule(Mode mode, List<String> include, List<String> exclude, Instant validFrom, Instant validTo) {

    public enum Mode { ALL_RESELLERS, TIERS, RESELLERS }

    private static final Pattern TIER_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    public AvailabilityRule {
        Objects.requireNonNull(mode);
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
    }

    public static AvailabilityRule of(Mode mode, List<String> include, List<String> exclude, Instant validFrom, Instant validTo) {
        if (mode == null) {
            throw invalid("MARKETPLACE_AVAILABILITY_MODE_REQUIRED", "Inform who may sell the listing.");
        }
        List<String> in = clean(include);
        List<String> out = clean(exclude).stream().map(AvailabilityRule::orgId).toList();
        switch (mode) {
            case ALL_RESELLERS -> {
                if (!in.isEmpty()) {
                    throw invalid("MARKETPLACE_AVAILABILITY_INCLUDE_UNEXPECTED", "Available to all resellers takes no include list.");
                }
            }
            case TIERS -> {
                if (in.isEmpty() || in.stream().anyMatch(t -> !TIER_KEY.matcher(t).matches())) {
                    throw invalid("MARKETPLACE_AVAILABILITY_TIERS_INVALID", "Inform at least one tier key, e.g. GOLD.");
                }
            }
            case RESELLERS -> {
                if (in.isEmpty()) {
                    throw invalid("MARKETPLACE_AVAILABILITY_RESELLERS_REQUIRED", "Inform at least one reseller.");
                }
                in = in.stream().map(AvailabilityRule::orgId).toList();
            }
        }
        if (validFrom != null && validTo != null && !validTo.isAfter(validFrom)) {
            throw invalid("MARKETPLACE_VALIDITY_INVALID", "The end of availability must be after its start.");
        }
        return new AvailabilityRule(mode, in, out, validFrom, validTo);
    }

    public boolean allows(ResellerRef reseller, Instant at) {
        String org = reseller.orgId().value().toString();
        if (exclude.contains(org) || (validFrom != null && validFrom.isAfter(at)) || (validTo != null && !at.isBefore(validTo))) {
            return false;
        }
        return switch (mode) {
            case ALL_RESELLERS -> true;
            case TIERS -> reseller.tierKey().map(include::contains).orElse(false);
            case RESELLERS -> include.contains(org);
        };
    }

    public Optional<Instant> from() { return Optional.ofNullable(validFrom); }
    public Optional<Instant> until() { return Optional.ofNullable(validTo); }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values.stream().filter(Objects::nonNull).map(String::strip).filter(v -> !v.isEmpty()).toList()));
    }

    private static String orgId(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw invalid("MARKETPLACE_AVAILABILITY_RESELLER_INVALID", "Resellers are identified by their organization ID.");
        }
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message);
    }
}
