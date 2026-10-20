package br.com.waver.reseller.adapters.workflow;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.reseller.application.ResellerOnboardingService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runs each step as the reseller-service principal of the PLATFORM organization (AR-008: never without context). */
@Component
public class OnboardingActivitiesImpl implements OnboardingActivities {

    private final ResellerOnboardingService onboarding;
    private final TenantContext serviceContext;

    public OnboardingActivitiesImpl(ResellerOnboardingService onboarding, @Value("${waver.platform-org-id}") String platformOrgId) {
        this.onboarding = onboarding;
        this.serviceContext = TenantContext.platformService("svc-reseller-service", OrgId.of(platformOrgId));
    }

    @Override
    public Snapshot snapshot(String resellerId) {
        return TenantContextHolder.callAs(serviceContext, () -> {
            ResellerOnboardingService.OnboardingSnapshot s = onboarding.snapshot(UUID.fromString(resellerId));
            return new Snapshot(s.finished(), s.awaitsOrganization(), s.awaitsOwnerInvitation());
        });
    }

    @Override
    public void createOrganization(String resellerId) {
        TenantContextHolder.runAs(serviceContext, () -> onboarding.createOrganization(UUID.fromString(resellerId)));
    }

    @Override
    public void inviteOwner(String resellerId) {
        TenantContextHolder.runAs(serviceContext, () -> onboarding.inviteOwner(UUID.fromString(resellerId)));
    }
}
