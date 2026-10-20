package br.com.waver.reseller.adapters.workflow;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.reseller.application.ResellerOnboardingService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Temporal client and worker of reseller-service (ADR-0023). The worker polls only this service's task queue
 * and hosts only this service's workflow; no other service starts or signals it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TemporalWorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalService(@Value("${waver.temporal.target:localhost:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    WorkflowClient temporalClient(WorkflowServiceStubs service, @Value("${waver.temporal.namespace:waver-commercial}") String namespace) {
        return WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory temporalWorkerFactory(WorkflowClient client, OnboardingActivitiesImpl activities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(ResellerOnboardingWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ResellerOnboardingWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return factory;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startWorker(ApplicationReadyEvent ready) {
        ready.getApplicationContext().getBean(WorkerFactory.class).start();
        log.info("Temporal worker started on task queue {}", ResellerOnboardingWorkflow.TASK_QUEUE);
    }

    /** Reconciliation: approved resellers with automatic steps left get their workflow woken every minute. */
    @Bean
    OnboardingReconciler onboardingReconciler(ResellerOnboardingService onboarding, @Value("${waver.platform-org-id}") String platformOrgId) {
        return new OnboardingReconciler(onboarding, TenantContext.platformService("svc-reseller-service", OrgId.of(platformOrgId)));
    }

    public static class OnboardingReconciler {

        private final ResellerOnboardingService onboarding;
        private final TenantContext serviceContext;

        OnboardingReconciler(ResellerOnboardingService onboarding, TenantContext serviceContext) {
            this.onboarding = onboarding;
            this.serviceContext = serviceContext;
        }

        @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${waver.reseller.reconcile-every:PT1M}")
        public void wakePending() {
            try {
                TenantContextHolder.runAs(serviceContext, () -> onboarding.wakePendingOnboardings(100));
            } catch (RuntimeException e) {
                log.warn("Onboarding reconciliation failed; next run retries", e);
            }
        }
    }
}
