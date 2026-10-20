package br.com.waver.reseller.domain;

/** Reseller onboarding lifecycle (RFC-0017 §2). SUSPENDED and TERMINATED come with the restriction flows. */
public enum OnboardingStatus {
    LEAD, UNDER_REVIEW, APPROVED, ACTIVE, REJECTED, SUSPENDED, TERMINATED;

    /** The onboarding workflow ends in these states. */
    public boolean onboardingFinished() {
        return this != LEAD && this != UNDER_REVIEW && this != APPROVED;
    }
}
