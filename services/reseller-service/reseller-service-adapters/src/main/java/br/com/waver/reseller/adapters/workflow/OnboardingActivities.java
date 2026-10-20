package br.com.waver.reseller.adapters.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Activities of the onboarding workflow. Each is idempotent: a retry or a replay never repeats a side effect. */
@ActivityInterface
public interface OnboardingActivities {

    /** Flags only (no personal data in workflow history, RFC-0017 Security). */
    record Snapshot(boolean finished, boolean awaitsOrganization, boolean awaitsOwnerInvitation) {
    }

    @ActivityMethod
    Snapshot snapshot(String resellerId);

    /** ORGANIZATION_CREATED: tenant-service POST /v1/organizations with Idempotency-Key, then link it. */
    @ActivityMethod
    void createOrganization(String resellerId);

    /** ADMIN_INVITED: identity-access-service POST /v1/invitations for the Reseller Owner, then record it. */
    @ActivityMethod
    void inviteOwner(String resellerId);
}
