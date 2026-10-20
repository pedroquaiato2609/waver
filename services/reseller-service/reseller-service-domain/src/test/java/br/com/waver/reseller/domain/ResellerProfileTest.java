package br.com.waver.reseller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResellerProfileTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final Set<ChecklistItem> REQUIRED = EnumSet.of(ChecklistItem.DUE_DILIGENCE, ChecklistItem.COMMERCIAL_APPROVAL,
            ChecklistItem.AGREEMENT_SIGNED, ChecklistItem.ORGANIZATION_CREATED, ChecklistItem.ADMIN_INVITED, ChecklistItem.TRAINING_DONE);

    private static ResellerProfile lead(boolean withCommercialContact) {
        Contact owner = Contact.of(Contact.Type.OWNER, "Ana Souza", "ana@alpha.com.br", null);
        List<Contact> others = withCommercialContact
                ? List.of(Contact.of(Contact.Type.COMMERCIAL, "Bruno Lima", "bruno@alpha.com.br", "+55 11 4000-1234"))
                : List.of();
        return ResellerProfile.registerLead("Alpha Soluções em Tecnologia Ltda", "Alpha",
                TaxId.of(TaxId.Type.CNPJ, "11.222.333/0001-81"), owner, others, NOW);
    }

    private static ResellerProfile approved() {
        ResellerProfile p = lead(true);
        p.startReview(NOW);
        p.completeChecklistItem(ChecklistItem.DUE_DILIGENCE, "u1", "DD-2026-001", NOW);
        p.approve("u1", null, NOW);
        return p;
    }

    @Test
    void fullOnboardingReachesActive() {
        ResellerProfile p = approved();
        OrgId org = new OrgId(Ids.newId());
        assertThat(p.awaitsOrganization()).isTrue();
        assertThat(p.linkOrganization(org, NOW)).isPresent();
        assertThat(p.awaitsOwnerInvitation()).isTrue();
        assertThat(p.recordOwnerInvited("inv-1", NOW)).isPresent();
        p.completeChecklistItem(ChecklistItem.AGREEMENT_SIGNED, "u1", null, NOW);
        p.completeChecklistItem(ChecklistItem.TRAINING_DONE, "u1", null, NOW);

        ResellerEvent event = p.activate(REQUIRED, NOW);

        assertThat(p.status()).isEqualTo(OnboardingStatus.ACTIVE);
        assertThat(event).isEqualTo(new ResellerEvent.Activated(org, NOW));
    }

    @Test
    void reviewNeedsTheCommercialContact() {
        assertThatThrownBy(() -> lead(false).startReview(NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_CONTACTS_INCOMPLETE");
    }

    @Test
    void approvalNeedsDueDiligence() {
        ResellerProfile p = lead(true);
        p.startReview(NOW);
        assertThatThrownBy(() -> p.approve("u1", null, NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_DUE_DILIGENCE_PENDING");
    }

    @Test
    void activationListsTheOpenItems() {
        ResellerProfile p = approved();
        p.linkOrganization(new OrgId(Ids.newId()), NOW);
        assertThatThrownBy(() -> p.activate(REQUIRED, NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AGREEMENT_SIGNED")
                .hasMessageContaining("ADMIN_INVITED")
                .hasMessageContaining("TRAINING_DONE")
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_CHECKLIST_INCOMPLETE");
    }

    @Test
    void automaticItemsCannotBeMarkedByHand() {
        ResellerProfile p = approved();
        assertThatThrownBy(() -> p.completeChecklistItem(ChecklistItem.ORGANIZATION_CREATED, "u1", null, NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_CHECKLIST_ITEM_AUTOMATIC");
    }

    @Test
    void workflowStepsAreIdempotent() {
        ResellerProfile p = approved();
        OrgId org = new OrgId(Ids.newId());
        p.linkOrganization(org, NOW);
        long version = p.version();
        assertThat(p.linkOrganization(org, NOW)).isEmpty();
        assertThat(p.version()).isEqualTo(version);
        assertThatThrownBy(() -> p.linkOrganization(new OrgId(Ids.newId()), NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_ORGANIZATION_MISMATCH");
    }

    @Test
    void rejectionNeedsAReasonAndEndsTheOnboarding() {
        ResellerProfile p = lead(true);
        p.startReview(NOW);
        assertThatThrownBy(() -> p.reject("u1", null, "no", NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_REJECTION_REASON_REQUIRED");
        p.reject("u1", null, "Pendências fiscais na due diligence", NOW);
        assertThat(p.status().onboardingFinished()).isTrue();
        assertThatThrownBy(() -> p.approve("u1", null, NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_INVALID_TRANSITION");
    }

    @Test
    void dueDiligenceBelongsToTheReview() {
        ResellerProfile p = lead(true);
        assertThatThrownBy(() -> p.completeChecklistItem(ChecklistItem.DUE_DILIGENCE, "u1", null, NOW))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESELLER_CHECKLIST_ITEM_OUT_OF_PHASE");
    }
}
