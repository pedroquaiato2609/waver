package br.com.waver.reseller.adapters.workflow;

import br.com.waver.reseller.application.ResellerOnboardingService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signal-with-start: wakes the reseller's workflow, starting it if it is not running. Called after commit; if
 * Temporal is down the change is already saved and the reconciliation job wakes the workflow later.
 */
@Component
public class TemporalOnboardingOrchestrator implements ResellerOnboardingService.OnboardingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TemporalOnboardingOrchestrator.class);

    private final WorkflowClient client;

    public TemporalOnboardingOrchestrator(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public void changed(UUID resellerId) {
        try {
            WorkflowStub stub = client.newUntypedWorkflowStub("ResellerOnboardingWorkflow", WorkflowOptions.newBuilder()
                    .setWorkflowId(ResellerOnboardingWorkflow.workflowId(resellerId))
                    .setTaskQueue(ResellerOnboardingWorkflow.TASK_QUEUE)
                    .build());
            stub.signalWithStart("changed", new Object[0], new Object[] {resellerId.toString()});
        } catch (RuntimeException e) {
            log.warn("Could not wake onboarding workflow for reseller {}; the reconciliation job will retry", resellerId, e);
        }
    }
}
