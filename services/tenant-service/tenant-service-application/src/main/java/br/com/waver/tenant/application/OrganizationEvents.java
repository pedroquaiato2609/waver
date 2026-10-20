package br.com.waver.tenant.application;

import br.com.waver.tenant.domain.Organization;
import java.util.Map;

/** Outbound port: facts published through the outbox (events + audit transport), inside the use case transaction. */
public interface OrganizationEvents {

    void organizationCreated(Organization organization);

    void statusChanged(Organization organization, Organization.Status fromStatus);

    void audit(String action, Organization organization, Map<String, String> details);
}
