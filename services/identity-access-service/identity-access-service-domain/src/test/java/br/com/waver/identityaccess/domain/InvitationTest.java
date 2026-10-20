package br.com.waver.identityaccess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void normalizesEmailAndExpiresInSevenDays() {
        Invitation.Invitee invitee = Invitation.Invitee.of(OrgType.RESELLER, "  Ana.Souza@Alpha.com.br ", "Ana", "Souza", "RESELLER_OWNER");
        Invitation invitation = Invitation.create(new OrgId(Ids.newId()), OrgType.RESELLER, invitee, "kc-user", NOW);
        assertThat(invitation.email()).isEqualTo("ana.souza@alpha.com.br");
        assertThat(invitation.expiresAt()).isEqualTo(Instant.parse("2026-10-26T12:00:00Z"));
        assertThat(invitation.isOpen(NOW)).isTrue();
        assertThat(invitation.isOpen(Instant.parse("2026-10-27T00:00:00Z"))).isFalse();
    }

    @Test
    void roleMustBelongToTheOrganizationType() {
        assertThatThrownBy(() -> Invitation.Invitee.of(OrgType.RESELLER, "a@b.com", "A", "B", "CUSTOMER_ADMIN"))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("IDENTITY_ROLE_NOT_ALLOWED");
        assertThatThrownBy(() -> Invitation.Invitee.of(OrgType.PLATFORM, "a@b.com", "A", "B", "PLATFORM_SUPER_ADMIN"))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("IDENTITY_ROLE_NOT_ALLOWED");
    }

    @Test
    void rejectsInvalidEmail() {
        assertThatThrownBy(() -> Invitation.Invitee.of(OrgType.CUSTOMER, "not-an-email", "A", "B", "CUSTOMER_ADMIN"))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("IDENTITY_EMAIL_INVALID");
    }
}
