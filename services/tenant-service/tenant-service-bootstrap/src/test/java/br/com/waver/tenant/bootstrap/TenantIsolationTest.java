package br.com.waver.tenant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.platform.tenancy.TenantRlsDataSource;
import br.com.waver.tenant.adapters.out.persistence.JdbcOrganizationRepository;
import br.com.waver.tenant.domain.Organization;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Tenancy test kit (ADR-0010, AR-008): "tenant A cannot read tenant B", proven against real PostgreSQL RLS
 * with the same roles and migrations as production (owner runs Flyway, the app role is subject to RLS).
 */
@Testcontainers
class TenantIsolationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("waver_tenant")
            .withInitScript("rls-roles.sql");

    static final OrgId PLATFORM = new OrgId(Ids.newId());
    static JdbcOrganizationRepository repository;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "tenant_owner", "test")
                .placeholders(Map.of("platformOrgId", PLATFORM.toString()))
                .load()
                .migrate();
        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser("tenant_app");
        app.setPassword("test");
        repository = new JdbcOrganizationRepository(new JdbcTemplate(new TenantRlsDataSource(app)));
    }

    private static TenantContext ctx(OrgId org, OrgType type, String... roles) {
        return new TenantContext("user-" + org, TenantContext.PrincipalType.USER, org, type, Set.of(roles));
    }

    private static Organization platformOrg() {
        return Organization.restore(PLATFORM, OrgType.PLATFORM, null, "Grupo WAVER", Organization.Status.ACTIVE, 0, Instant.now());
    }

    @Test
    void resellerSeesItselfButNotOtherResellers() {
        TenantContext staff = ctx(PLATFORM, OrgType.PLATFORM, "PLATFORM_SUPER_ADMIN");
        Organization alpha = Organization.register(OrgType.RESELLER, platformOrg(), "Alpha Soluções", Instant.now());
        Organization norte = Organization.register(OrgType.RESELLER, platformOrg(), "Norte Digital", Instant.now());
        TenantContextHolder.runAs(staff, () -> {
            repository.insert(alpha, null);
            repository.insert(norte, null);
        });

        TenantContext alphaUser = ctx(alpha.id(), OrgType.RESELLER, "RESELLER_OWNER");
        TenantContextHolder.runAs(alphaUser, () -> {
            assertThat(repository.findById(alpha.id())).isPresent();
            assertThat(repository.findById(norte.id())).as("other reseller must be invisible").isEmpty();
            assertThat(repository.findVisible(null, 50)).extracting(Organization::id).containsExactly(alpha.id());
        });
    }

    @Test
    void platformStaffHasAuditedOversightOfAllOrganizations() {
        TenantContext staff = ctx(PLATFORM, OrgType.PLATFORM, "PLATFORM_SUPER_ADMIN");
        Organization beta = Organization.register(OrgType.RESELLER, platformOrg(), "Beta Sistemas", Instant.now());
        TenantContextHolder.runAs(staff, () -> {
            repository.insert(beta, null);
            assertThat(repository.findById(beta.id())).isPresent();
            assertThat(repository.findById(PLATFORM)).isPresent();
        });
    }

    @Test
    void noTenantContextMeansNoRows() {
        assertThat(repository.findById(PLATFORM)).as("missing tenant context must fail closed").isEmpty();
        assertThat(repository.findVisible(null, 50)).isEmpty();
    }

    @Test
    void resellerCannotCreateOrganizationsUnderAnotherOrganization() {
        TenantContext staff = ctx(PLATFORM, OrgType.PLATFORM, "PLATFORM_SUPER_ADMIN");
        Organization gamma = Organization.register(OrgType.RESELLER, platformOrg(), "Gamma TI", Instant.now());
        TenantContextHolder.runAs(staff, () -> repository.insert(gamma, null));

        TenantContext gammaUser = ctx(gamma.id(), OrgType.RESELLER, "RESELLER_OWNER");
        Organization sneaky = Organization.register(OrgType.RESELLER, platformOrg(), "Sneaky", Instant.now());
        assertThatThrownBy(() -> TenantContextHolder.runAs(gammaUser, () -> repository.insert(sneaky, null)))
                .rootCause().hasMessageContaining("row-level security");
    }
}
