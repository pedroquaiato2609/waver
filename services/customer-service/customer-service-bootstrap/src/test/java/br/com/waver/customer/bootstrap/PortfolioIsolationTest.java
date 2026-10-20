package br.com.waver.customer.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.customer.adapters.out.persistence.JdbcCustomers;
import br.com.waver.customer.domain.CustomerContact;
import br.com.waver.customer.domain.CustomerProfile;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.platform.tenancy.TenantRlsDataSource;
import java.time.Instant;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tenancy kit for customer-service (ADR-0010): reseller A never sees nor writes reseller B's portfolio. */
@Testcontainers
class PortfolioIsolationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("waver_customer")
            .withInitScript("rls-roles.sql");

    static JdbcCustomers customers;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "customer_owner", "test").load().migrate();
        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser("customer_app");
        app.setPassword("test");
        customers = new JdbcCustomers(new JdbcTemplate(new TenantRlsDataSource(app)));
    }

    private static TenantContext seller(OrgId reseller) {
        return new TenantContext("seller-" + reseller, TenantContext.PrincipalType.USER, reseller, OrgType.RESELLER,
                Set.of("RESELLER_SALES"));
    }

    private static CustomerProfile customer(OrgId reseller, String name, String cnpj) {
        return CustomerProfile.register(new OrgId(Ids.newId()), reseller, CustomerProfile.Registration.of(name, null,
                TaxId.of(TaxId.Type.CNPJ, cnpj), null,
                CustomerContact.of(CustomerContact.Type.ADMIN, "Carla Mendes", "carla@exemplo.com.br", null)), Instant.now());
    }

    @Test
    void eachResellerSeesOnlyItsPortfolio() {
        OrgId alpha = new OrgId(Ids.newId());
        OrgId norte = new OrgId(Ids.newId());
        CustomerProfile acme = customer(alpha, "ACME Indústria", "11.222.333/0001-81");
        CustomerProfile zeta = customer(norte, "Zeta Varejo", "60.701.190/0001-04");
        TenantContextHolder.runAs(seller(alpha), () -> customers.register(acme, "k-acme-0001"));
        TenantContextHolder.runAs(seller(norte), () -> customers.register(zeta, "k-zeta-0001"));

        TenantContextHolder.runAs(seller(alpha), () -> {
            assertThat(customers.list(null, 50)).extracting(CustomerProfile::id).containsExactly(acme.id());
            assertThat(customers.findById(zeta.id())).as("other portfolio must be invisible").isEmpty();
            assertThat(customers.findById(acme.id()).orElseThrow().contacts()).hasSize(1);
            assertThat(customers.existsInPortfolio(alpha, zeta.taxId())).isFalse();
        });
    }

    @Test
    void sameCompanyCanBeInTwoPortfoliosWithoutRevealingIt() {
        OrgId alpha = new OrgId(Ids.newId());
        OrgId norte = new OrgId(Ids.newId());
        TenantContextHolder.runAs(seller(alpha), () -> customers.register(customer(alpha, "Delta Logística", "33.000.167/0001-01"), "k-delta-a01"));
        TenantContextHolder.runAs(seller(norte), () -> {
            assertThat(customers.existsInPortfolio(norte, TaxId.of(TaxId.Type.CNPJ, "33.000.167/0001-01"))).isFalse();
            customers.register(customer(norte, "Delta Logística", "33.000.167/0001-01"), "k-delta-n01");
        });
    }

    @Test
    void resellerCannotRegisterIntoAnotherPortfolio() {
        OrgId alpha = new OrgId(Ids.newId());
        OrgId norte = new OrgId(Ids.newId());
        CustomerProfile intoNorte = customer(norte, "Omega Serviços", "04.252.011/0001-10");
        assertThatThrownBy(() -> TenantContextHolder.runAs(seller(alpha), () -> customers.register(intoNorte, "k-omega-001")))
                .rootCause().hasMessageContaining("row-level security");
    }
}
