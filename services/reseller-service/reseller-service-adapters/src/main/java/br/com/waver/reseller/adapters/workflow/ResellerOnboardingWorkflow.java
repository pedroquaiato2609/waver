package br.com.waver.reseller.adapters.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.UUID;

/**
 * One workflow per reseller onboarding (RFC-0017 §2, ADR-0023): namespace waver-commercial, task queue
 * {@value #TASK_QUEUE}, workflow ID {@code reseller-onboarding:<resellerProfileId>}. Started at LEAD, completed
 * at ACTIVE or REJECTED. The business record stays in PostgreSQL; the workflow only drives the automatic steps.
 */
@WorkflowInterface
public interface ResellerOnboardingWorkflow {

    String TASK_QUEUE = "reseller-service.reseller-onboarding";

    static String workflowId(UUID resellerId) {
        return "reseller-onboarding:" + resellerId;
    }

    @WorkflowMethod
    void run(String resellerId);

    /** Sent by reseller-service itself after each committed change (review, checklist, decision, activation). */
    @SignalMethod
    void changed();
}
