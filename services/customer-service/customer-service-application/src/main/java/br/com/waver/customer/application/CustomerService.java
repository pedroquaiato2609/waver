package br.com.waver.customer.application;

import br.com.waver.customer.domain.CustomerContact;
import br.com.waver.customer.domain.CustomerProfile;
import br.com.waver.customer.domain.Customers;
import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Customer registration and portfolio (RFC-0017 §3). Registration: validate, create the CUSTOMER organization in
 * tenant-service under the reseller (same Idempotency-Key), then store the profile, the first ownership row and
 * CustomerRegistered in one transaction. A retry with the same key returns the same customer.
 */
public class CustomerService {

    public record Register(String legalName, String tradeName, TaxId.Type taxIdType, String taxId, String segment,
                           String adminName, String adminEmail, String adminPhone) {
    }

    public record Result(CustomerProfile customer, boolean replayed) {
    }

    public interface Events {
        void registered(CustomerProfile customer);
    }

    /** tenant-service, called with the reseller user's own token (the organization is created under its org). */
    public interface TenantDirectory {
        OrgId createCustomerOrganization(String legalName, String idempotencyKey);
    }

    private final Customers customers;
    private final Events events;
    private final TenantDirectory tenants;
    private final TransactionOperations tx;
    private final Clock clock;

    public CustomerService(Customers customers, Events events, TenantDirectory tenants, TransactionOperations tx, Clock clock) {
        this.customers = customers;
        this.events = events;
        this.tenants = tenants;
        this.tx = tx;
        this.clock = clock;
    }

    public Result register(TenantContext actor, Register c, String idempotencyKey) {
        requireSeller(actor);
        CustomerProfile.Registration registration = CustomerProfile.Registration.of(c.legalName(), c.tradeName(),
                TaxId.of(c.taxIdType(), c.taxId()), c.segment(),
                CustomerContact.of(CustomerContact.Type.ADMIN, c.adminName(), c.adminEmail(), c.adminPhone()));
        String key = idempotencyKey != null ? idempotencyKey : "customer-registration:" + Ids.newId();

        Optional<CustomerProfile> earlier = tx.execute(s -> customers.findByIdempotencyKey(actor.orgId(), key));
        if (earlier != null && earlier.isPresent()) {
            return new Result(earlier.get(), true);
        }
        Boolean duplicated = tx.execute(s -> customers.existsInPortfolio(actor.orgId(), registration.taxId()));
        if (Boolean.TRUE.equals(duplicated)) {
            throw new DomainException(DomainException.Kind.CONFLICT, "CUSTOMER_TAX_ID_EXISTS",
                    "This customer is already in your portfolio.");
        }

        OrgId customerOrg = tenants.createCustomerOrganization(registration.legalName(), key);
        CustomerProfile customer = CustomerProfile.register(customerOrg, actor.orgId(), registration, clock.instant());
        tx.executeWithoutResult(s -> {
            customers.register(customer, key);
            events.registered(customer);
        });
        return new Result(customer, false);
    }

    public CustomerProfile get(TenantContext actor, UUID id) {
        requireReader(actor);
        return tx.execute(s -> customers.findById(id)).orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found."));
    }

    public List<CustomerProfile> list(TenantContext actor, UUID afterId, int limit) {
        requireReader(actor);
        return tx.execute(s -> customers.list(afterId, Math.clamp(limit, 1, 200)));
    }

    /** RFC-0017 §3: registration by RESELLER_OWNER or RESELLER_SALES, always into the caller's own portfolio. */
    private static void requireSeller(TenantContext actor) {
        if (actor.orgType() != OrgType.RESELLER || !(actor.hasRole("RESELLER_OWNER") || actor.hasRole("RESELLER_SALES"))) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "CUSTOMER_REGISTER_FORBIDDEN",
                    "Only reseller owners and sales can register customers.");
        }
    }

    private static void requireReader(TenantContext actor) {
        if (actor.orgType() != OrgType.RESELLER && !actor.isPlatformStaff()) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "CUSTOMER_READ_FORBIDDEN",
                    "You cannot see this portfolio.");
        }
    }
}
