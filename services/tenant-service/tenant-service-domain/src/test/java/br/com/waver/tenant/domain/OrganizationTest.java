package br.com.waver.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrganizationTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private final Organization platform = Organization.restore(new OrgId(Ids.newId()), OrgType.PLATFORM, null,
            "Grupo WAVER", Organization.Status.ACTIVE, 0, NOW);

    @Test
    void registersPendingResellerUnderPlatform() {
        Organization reseller = Organization.register(OrgType.RESELLER, platform, "  Alpha   Soluções  ", NOW);
        assertThat(reseller.status()).isEqualTo(Organization.Status.PENDING);
        assertThat(reseller.parentId()).contains(platform.id());
        assertThat(reseller.legalName()).isEqualTo("Alpha Soluções");
    }

    @Test
    void rejectsResellerUnderAnotherReseller() {
        Organization reseller = Organization.restore(new OrgId(Ids.newId()), OrgType.RESELLER, platform.id(),
                "Alpha", Organization.Status.ACTIVE, 0, NOW);
        assertThatThrownBy(() -> Organization.register(OrgType.RESELLER, reseller, "Beta", NOW))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TENANT_INVALID_HIERARCHY");
    }

    @Test
    void rejectsSecondPlatformOrganization() {
        assertThatThrownBy(() -> Organization.register(OrgType.PLATFORM, platform, "Outra", NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TENANT_PLATFORM_ORG_EXISTS");
    }

    @Test
    void rejectsChildOfInactiveParent() {
        Organization pendingReseller = Organization.register(OrgType.RESELLER, platform, "Alpha", NOW);
        assertThatThrownBy(() -> Organization.register(OrgType.CUSTOMER, pendingReseller, "ACME", NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TENANT_PARENT_NOT_ACTIVE");
    }

    @Test
    void validatesLegalNameLength() {
        assertThatThrownBy(() -> Organization.register(OrgType.RESELLER, platform, "A", NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TENANT_LEGAL_NAME_INVALID");
    }

    @Test
    void activatesPendingOrganizationOnceAndIgnoresRepeats() {
        Organization pending = Organization.register(OrgType.RESELLER, platform, "Alpha", NOW);
        Organization active = pending.activate();
        assertThat(active.status()).isEqualTo(Organization.Status.ACTIVE);
        assertThat(active.version()).isEqualTo(pending.version() + 1);
        assertThat(active.activate()).isSameAs(active);
    }

    @Test
    void doesNotActivateTerminatedOrganization() {
        Organization terminated = Organization.restore(new OrgId(Ids.newId()), OrgType.RESELLER, platform.id(),
                "Alpha", Organization.Status.TERMINATED, 3, NOW);
        assertThatThrownBy(terminated::activate)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TENANT_INVALID_STATUS_TRANSITION");
    }
}
