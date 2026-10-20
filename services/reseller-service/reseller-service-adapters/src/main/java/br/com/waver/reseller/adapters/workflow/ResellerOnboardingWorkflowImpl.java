package br.com.waver.reseller.adapters.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Deterministic loop: read the business record, run the next automatic step, otherwise wait for a change.
 * No business timeout (RFC-0017): a lead may stay under review as long as WAVER Commercial needs.
 * Activities retry without a limit, backing off to one minute; a failing step is visible in Temporal Web.
 */
public class ResellerOnboardingWorkflowImpl implements ResellerOnboardingWorkflow {

    private final OnboardingActivities activities = Workflow.newActivityStub(OnboardingActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    private boolean changed;

    @Override
    public void run(String resellerId) {
        while (true) {
            changed = false;
            OnboardingActivities.Snapshot state = activities.snapshot(resellerId);
            if (state.finished()) {
                return;
            }
            if (state.awaitsOrganization()) {
                activities.createOrganization(resellerId);
                continue;
            }
            if (state.awaitsOwnerInvitation()) {
                activities.inviteOwner(resellerId);
                continue;
            }
            Workflow.await(() -> changed);
        }
    }

    @Override
    public void changed() {
        changed = true;
    }
}
