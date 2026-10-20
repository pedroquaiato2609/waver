package br.com.waver.reseller.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.platform.tenancy.TenantRlsDataSource;
import br.com.waver.reseller.adapters.out.persistence.JdbcResellerProfiles;
import br.com.waver.reseller.domain.ChecklistItem;
import br.com.waver.reseller.domain.Contact;
import br.com.waver.reseller.domain.ResellerProfile;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tenancy kit for reseller-service (ADR-0010): a reseller reads only its own profile; only WAVER writes. */
@Testcontainers
class ResellerIsolationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("waver_reseller")
            .withInitScript("rls-roles.sql");

    static final OrgId PLATFORM = new OrgId(Ids.newId());
    static final TenantContext STAFF = new TenantContext("staff", TenantContext.PrincipalType.USER, PLATFORM, OrgType.PLATFORM,
            Set.of("WAVER_COMMERCIAL"));
    static JdbcResellerProfiles profiles;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "reseller_owner", "test").load().migrate();
        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser("reseller_app");
        app.setPassword("test");
        profiles = new JdbcResellerProfiles(new JdbcTemplate(new TenantRlsDataSource(app)), PLATFORM.toString());
    }

    private static ResellerProfile activeLinked(String name, String cnpj, OrgId org) {
        ResellerProfile p = ResellerProfile.registerLead(name, null, TaxId.of(TaxId.Type.CNPJ, cnpj),
                Contact.of(Contact.Type.OWNER, "Ana Souza", "ana@" + name.toLowerCase().replace(' ', '-') + ".com.br", null),
                List.of(Contact.of(Contact.Type.COMMERCIAL, "Bruno Lima", "bruno@exemplo.com.br", null)), Instant.now());
        TenantContextHolder.runAs(STAFF, () -> {
            profiles.insert(p);
            long v = p.version();
            p.startReview(Instant.now());
            p.completeChecklistItem(ChecklistItem.DUE_DILIGENCE, "staff", null, Instant.now());
            p.approve("staff", null, Instant.now());
            p.linkOrganization(org, Instant.now());
            assertThat(profiles.update(p, v)).isTrue();
        });
        return p;
    }

    @Test
    void resellerSeesOnlyItsOwnProfileWithContactsAndChecklist() {
        OrgId alphaOrg = new OrgId(Ids.newId());
        ResellerProfile alpha = activeLinked("Alpha TI", "11.222.333/0001-81", alphaOrg);
        ResellerProfile norte = activeLinked("Norte Digital", "12.abc.345/01de-35", new OrgId(Ids.newId()));

        TenantContext alphaOwner = new TenantContext("ana", TenantContext.PrincipalType.USER, alphaOrg, OrgType.RESELLER,
                Set.of("RESELLER_OWNER"));
        TenantContextHolder.runAs(alphaOwner, () -> {
            ResellerProfile own = profiles.findByResellerOrg(alphaOrg).orElseThrow();
            assertThat(own.id()).isEqualTo(alpha.id());
            assertThat(own.contacts()).hasSize(2);
            assertThat(own.checklist()).containsKeys(ChecklistItem.DUE_DILIGENCE, ChecklistItem.ORGANIZATION_CREATED);
            assertThat(profiles.findById(norte.id())).as("another reseller must be invisible").isEmpty();
            assertThat(profiles.list(null, null, 50)).extracting(ResellerProfile::id).containsExactly(alpha.id());
        });
    }

    @Test
    void resellerCannotRegisterOrChangeProfiles() {
        OrgId betaOrg = new OrgId(Ids.newId());
        ResellerProfile beta = activeLinked("Beta Sistemas", "04.252.011/0001-10", betaOrg);
        TenantContext betaOwner = new TenantContext("bia", TenantContext.PrincipalType.USER, betaOrg, OrgType.RESELLER,
                Set.of("RESELLER_OWNER"));

        ResellerProfile sneaky = ResellerProfile.registerLead("Sneaky", null, TaxId.of(TaxId.Type.CNPJ, "33.000.167/0001-01"),
                Contact.of(Contact.Type.OWNER, "Eve Silva", "eve@sneaky.com", null), List.of(), Instant.now());
        assertThatThrownBy(() -> TenantContextHolder.runAs(betaOwner, () -> profiles.insert(sneaky)))
                .rootCause().hasMessageContaining("row-level security");

        TenantContextHolder.runAs(betaOwner, () -> {
            ResellerProfile own = profiles.findById(beta.id()).orElseThrow();
            long v = own.version();
            own.completeChecklistItem(ChecklistItem.TRAINING_DONE, "bia", null, Instant.now());
            assertThat(profiles.update(own, v)).as("update is filtered out by RLS").isFalse();
        });
    }

    @Test
    void noTenantContextMeansNoRows() {
        activeLinked("Gamma TI", "60.701.190/0001-04", new OrgId(Ids.newId()));
        assertThat(profiles.list(null, null, 50)).isEmpty();
    }
}
