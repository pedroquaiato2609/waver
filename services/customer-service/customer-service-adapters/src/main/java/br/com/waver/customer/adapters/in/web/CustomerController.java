package br.com.waver.customer.adapters.in.web;

import br.com.waver.customer.application.CustomerService;
import br.com.waver.customer.domain.CustomerProfile;
import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.platform.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal service API (RFC-0017 §3): /v1/customers. */
@RestController
@RequestMapping("/v1/customers")
public class CustomerController {

    public record ContactInput(String name, String email, String phone) {
    }

    public record RegisterRequest(String legalName, String tradeName, @NotNull TaxId.Type taxIdType, String taxId, String segment,
                                  @NotNull @Valid ContactInput adminContact) {
    }

    public record ContactView(String type, String name, String email, String phone) {
    }

    public record CustomerView(UUID id, UUID customerOrgId, UUID resellerOrgId, String legalName, String tradeName,
                               String taxIdType, String taxId, String segment, String lifecycleStage, String accessStatus,
                               String financialStatus, String displayStatus, List<ContactView> contacts, long version,
                               Instant createdAt) {
        static CustomerView from(CustomerProfile c) {
            return new CustomerView(c.id(), c.customerOrgId().value(), c.resellerOrgId().value(), c.legalName(),
                    c.tradeName().orElse(null), c.taxId().type().name(), c.taxId().formatted(), c.segment().orElse(null),
                    c.lifecycleStage().name(), c.accessStatus().name(), c.financialStatus().name(), c.displayStatus().name(),
                    c.contacts().stream().map(x -> new ContactView(x.type().name(), x.name(), x.email(), x.phone())).toList(),
                    c.version(), c.createdAt());
        }
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    /** Idempotency-Key (STD-003): the same key returns the customer created the first time (200 instead of 201). */
    @PostMapping
    public ResponseEntity<CustomerView> register(@Valid @RequestBody RegisterRequest r,
                                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey != null && (idempotencyKey.length() < 8 || idempotencyKey.length() > 200)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must have between 8 and 200 characters.");
        }
        CustomerService.Result result = customers.register(TenantContextHolder.require(), new CustomerService.Register(
                r.legalName(), r.tradeName(), r.taxIdType(), r.taxId(), r.segment(),
                r.adminContact().name(), r.adminContact().email(), r.adminContact().phone()), idempotencyKey);
        CustomerView body = CustomerView.from(result.customer());
        return result.replayed() ? ResponseEntity.ok(body)
                : ResponseEntity.created(URI.create("/v1/customers/" + body.id())).body(body);
    }

    @GetMapping
    public Page<CustomerView> list(@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) UUID cursor) {
        List<CustomerProfile> items = customers.list(TenantContextHolder.require(), cursor, limit);
        String next = items.size() == Math.clamp(limit, 1, 200) ? items.getLast().id().toString() : null;
        return new Page<>(items.stream().map(CustomerView::from).toList(), next);
    }

    @GetMapping("/{id}")
    public CustomerView get(@PathVariable UUID id) {
        return CustomerView.from(customers.get(TenantContextHolder.require(), id));
    }
}
