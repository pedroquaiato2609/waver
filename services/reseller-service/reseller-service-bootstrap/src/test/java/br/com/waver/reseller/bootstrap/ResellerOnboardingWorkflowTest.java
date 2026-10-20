package br.com.waver.reseller.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.waver.reseller.adapters.workflow.OnboardingActivities;
import br.com.waver.reseller.adapters.workflow.ResellerOnboardingWorkflow;
import br.com.waver.reseller.adapters.workflow.ResellerOnboardingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ResellerOnboardingWorkflow with the Temporal test server (time skipping) and in-memory activities (ADR-0023). */
class ResellerOnboardingWorkflowTest {

    /** Stand-in for the business record: what the activities would read from and write to PostgreSQL. */
    static final class FakeOnboarding implements OnboardingActivities {
        volatile String status = "LEAD";
        volatile boolean organization;
        volatile boolean invited;
        final AtomicInteger organizationFailures = new AtomicInteger();
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Snapshot snapshot(String resellerId) {
            boolean approved = status.equals("APPROVED");
            return new Snapshot(status.equals("ACTIVE") || status.equals("REJECTED"), approved && !organization,
                    approved && organization && !invited);
        }

        @Override
        public void createOrganization(String resellerId) {
            calls.add("createOrganization");
            if (organizationFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("tenant-service unavailable");
            }
            organization = true;
        }

        @Override
        public void inviteOwner(String resellerId) {
            calls.add("inviteOwner");
            invited = true;
        }
    }

    private TestWorkflowEnvironment env;
    private FakeOnboarding onboarding;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        onboarding = new FakeOnboarding();
        Worker worker = env.newWorker(ResellerOnboardingWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ResellerOnboardingWorkflowImpl.class);
        worker.registerActivitiesImplementations(onboarding);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private WorkflowStub start(UUID id) {
        WorkflowStub stub = client.newUntypedWorkflowStub("ResellerOnboardingWorkflow", WorkflowOptions.newBuilder()
                .setWorkflowId(ResellerOnboardingWorkflow.workflowId(id))
                .setTaskQueue(ResellerOnboardingWorkflow.TASK_QUEUE)
                .build());
        stub.signalWithStart("changed", new Object[0], new Object[] {id.toString()});
        return stub;
    }

    @Test
    void waitsDuringReviewThenCreatesOrganizationAndInvitesOwnerAfterApproval() throws Exception {
        UUID id = UUID.randomUUID();
        WorkflowStub stub = start(id);

        env.sleep(Duration.ofDays(30)); // a long review: the workflow just waits, no timeout
        assertThat(onboarding.calls).isEmpty();

        onboarding.status = "APPROVED";
        stub.signal("changed");
        env.sleep(Duration.ofSeconds(5));
        assertThat(onboarding.calls).containsExactly("createOrganization", "inviteOwner");

        onboarding.status = "ACTIVE";
        stub.signal("changed");
        stub.getResult(10, TimeUnit.SECONDS, Void.class);
    }

    @Test
    void retriesAFailingStepUntilTheDependencyIsBack() throws Exception {
        UUID id = UUID.randomUUID();
        onboarding.status = "APPROVED";
        onboarding.organizationFailures.set(3);
        WorkflowStub stub = start(id);

        env.sleep(Duration.ofMinutes(5));

        assertThat(onboarding.organization).isTrue();
        assertThat(onboarding.invited).isTrue();
        assertThat(onboarding.calls.stream().filter("createOrganization"::equals).count()).isEqualTo(4);
        onboarding.status = "ACTIVE";
        stub.signal("changed");
        stub.getResult(10, TimeUnit.SECONDS, Void.class);
    }

    @Test
    void endsWhenTheLeadIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        WorkflowStub stub = start(id);
        onboarding.status = "REJECTED";
        stub.signal("changed");
        stub.getResult(10, TimeUnit.SECONDS, Void.class);
        assertThat(onboarding.calls).isEmpty();
    }
}
