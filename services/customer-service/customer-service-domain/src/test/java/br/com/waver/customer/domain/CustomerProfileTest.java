package br.com.waver.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerProfileTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final CustomerContact ADMIN = CustomerContact.of(CustomerContact.Type.ADMIN, "Carla Mendes", "carla@acme.com.br", null);

    private static CustomerProfile with(CustomerProfile.LifecycleStage stage, CustomerProfile.AccessStatus access,
                                        CustomerProfile.FinancialStatus financial) {
        return CustomerProfile.restore(Ids.newId(), new OrgId(Ids.newId()), new OrgId(Ids.newId()), "ACME Indústria Ltda", null,
                TaxId.of(TaxId.Type.CNPJ, "11.222.333/0001-81"), null, List.of(ADMIN), stage, access, financial, 0, NOW);
    }

    @Test
    void registersAsProspectInThePortfolioOfTheReseller() {
        OrgId reseller = new OrgId(Ids.newId());
        CustomerProfile.Registration r = CustomerProfile.Registration.of("  ACME   Indústria Ltda ", "ACME",
                TaxId.of(TaxId.Type.CNPJ, "11222333000181"), "Indústria", ADMIN);
        CustomerProfile c = CustomerProfile.register(new OrgId(Ids.newId()), reseller, r, NOW);
        assertThat(c.legalName()).isEqualTo("ACME Indústria Ltda");
        assertThat(c.resellerOrgId()).isEqualTo(reseller);
        assertThat(c.displayStatus()).isEqualTo(CustomerProfile.DisplayStatus.PROSPECT);
    }

    @Test
    void adminContactIsRequired() {
        assertThatThrownBy(() -> CustomerProfile.Registration.of("ACME", null, TaxId.of(TaxId.Type.CNPJ, "11222333000181"), null, null))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("CUSTOMER_ADMIN_CONTACT_REQUIRED");
    }

    @Test
    void displayStatusFollowsTheThreeStatusRule() {
        assertThat(with(CustomerProfile.LifecycleStage.ACTIVE, CustomerProfile.AccessStatus.NORMAL, CustomerProfile.FinancialStatus.PAST_DUE)
                .displayStatus()).isEqualTo(CustomerProfile.DisplayStatus.PAST_DUE);
        assertThat(with(CustomerProfile.LifecycleStage.ACTIVE, CustomerProfile.AccessStatus.RESTRICTED, CustomerProfile.FinancialStatus.PAST_DUE)
                .displayStatus()).isEqualTo(CustomerProfile.DisplayStatus.RESTRICTED);
        assertThat(with(CustomerProfile.LifecycleStage.TERMINATED, CustomerProfile.AccessStatus.SUSPENDED, CustomerProfile.FinancialStatus.CURRENT)
                .displayStatus()).isEqualTo(CustomerProfile.DisplayStatus.TERMINATED);
        assertThat(with(CustomerProfile.LifecycleStage.PROVISIONING, CustomerProfile.AccessStatus.NORMAL, CustomerProfile.FinancialStatus.PAST_DUE)
                .displayStatus()).as("past due only shows for commercially active customers").isEqualTo(CustomerProfile.DisplayStatus.PROVISIONING);
    }
}
