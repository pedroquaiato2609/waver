package br.com.waver.reseller.domain;

/**
 * Onboarding checklist (RFC-0017 §2, ResellerOnboarding). Automatic items are completed by the onboarding itself
 * (approval and the workflow steps); people mark the manual ones in WAVER Control.
 */
public enum ChecklistItem {
    DUE_DILIGENCE(false),
    COMMERCIAL_APPROVAL(true),
    AGREEMENT_SIGNED(false),
    ORGANIZATION_CREATED(true),
    CATALOG_ASSIGNED(false),
    PRICING_RULES_SET(false),
    ADMIN_INVITED(true),
    TRAINING_DONE(false);

    private final boolean automatic;

    ChecklistItem(boolean automatic) {
        this.automatic = automatic;
    }

    public boolean automatic() {
        return automatic;
    }
}
