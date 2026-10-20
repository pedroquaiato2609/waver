# Event Catalog (v1.3)

Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro payloads with Apicurio Registry subjects, CloudEvents binary mode with `ce_*` Kafka headers and the Debezium outbox (ADR-0024); credential leases per ADR-0022).
Owners: WAVER Architecture (named owner TBD)
Date: 2026-10-19
Version: 1.3
Related: ADR-0012, ADR-0024, RFC-0006 (rules), MICROSERVICES_MAP v1.2 (ownership), RFC-0003, RFC-0004, RFC-0011, RFC-0012, RFC-0013, RFC-0016, RFC-0017, RFC-0018, RFC-0020

This catalog is the registry of record for Kafka topics and CloudEvents types (ADR-0012, ADR-0024). It lists the **main** events derived from MICROSERVICES_MAP v1.2, plus additions agreed in the v1.2 review. Owning RFCs may define more event types on the same topics; each topic row names the RFC that holds the full list. A change to this file is reviewed by the producing service and by Architecture. Schemas live in `contracts/events/<context>/<EventName>.v<N>.avsc` (Avro) and are registered in Apicurio Registry by the CI release pipeline (never by services at runtime).

## 1. Conventions used here (RFC-0006)
- Topic: `waver.<context>.<aggregate>.v1` with a kebab-case aggregate. CloudEvents type: `waver.<context>.<EventName>.v1`. Kafka key: aggregate ID (plain UTF-8 string) unless stated otherwise. DLQ: `<topic>.dlq`. Retry: `<topic>-retry-<n>`.
- **Format (ADR-0024):** the Kafka value is the Avro-encoded `data` only, in the Confluent-compatible wire format (magic byte, 4-byte schema ID, Avro binary). Events are published by the producing service's outbox through the Debezium Outbox Event Router on Kafka Connect (RFC-0006 §8).
- **Envelope: CloudEvents 1.0 in binary mode** — every attribute is a Kafka header (RFC-0006 §6):
  - always: `ce_specversion` (`1.0`), `ce_id` (UUIDv7, inbox dedup key), `ce_source` (`/waver/<service>`), `ce_type`, `ce_subject` (aggregate ID), `ce_time`, `ce_dataschema` (`urn:waver:events:<context>:<EventName>:v<N>`), `content-type` (`application/avro`);
  - required WAVER extensions: `ce_orgid`, `ce_correlationid`, `ce_causationid`, `ce_actorid`, `ce_actortype`, `ce_traceparent`;
  - conditional: `ce_workspaceid`, `ce_domainid`, `ce_supportgrantid` (under a support grant), `ce_aggregateversion` (state-bearing aggregates), `ce_tracestate` (optional).
  - Headers never carry PII.
- **Schema location:** `contracts/events/<context>/<EventName>.v<N>.avsc`; Avro namespace `br.com.waver.events.<contextpkg>.v<N>` (`<contextpkg>` = context token without hyphens, e.g. `usagemetering`), record name = event name; shared types inlined from `contracts/events/common/`.
- **Registry subject naming** (Apicurio, Confluent-compatible API, `TopicRecordNameStrategy`): `<topic>-<namespace>.<EventName>`, e.g. `waver.contract.contract.v1-br.com.waver.events.contract.v1.ContractActivated`. Compatibility rule `BACKWARD_TRANSITIVE` per subject; a new major has a new namespace and therefore a new subject.
- Payloads carry IDs and minimal state; the payload summaries below list the main Avro fields. **PII** marks the topics that carry personal data in fields flagged `x-waver-pii` (Avro field property), consumed only by the listed consumers. **R** marks Restricted content (RFC-0007 classification). **A** marks events that audit-service ingests as AuditEvents (RFC-0006 §13).
- Partition class (RFC-0006 §2): **CP** = control plane (6 partitions, 14 days); **DP** = data-plane high volume (24 partitions, 7 days); **RC** = restricted content (24 partitions, 3 days); **ST** = compacted state (12 partitions).

**Context tokens** (one per producing service):

| Service | `<context>` | Service | `<context>` |
|---|---|---|---|
| tenant-service | `tenant` | agent-builder-service | `agent-builder` |
| identity-access-service | `identity` | agent-runtime | `agent-runtime` |
| reseller-service | `reseller` | memory-service | `memory` |
| customer-service | `customer` | evaluation-service | `evaluation` |
| catalog-service | `catalog` | model-gateway-service | `model-gateway` |
| marketplace-service | `marketplace` | handoff-service | `handoff` |
| contract-service | `contract` | tool-registry-service | `tool-registry` |
| entitlement-service | `entitlement` | tool-runtime-service | `tool-runtime` |
| provisioning-service | `provisioning` | connector-service | `connector` |
| billing-service | `billing` | knowledge-service | `knowledge` |
| support-service | `support` | channel-service | `channel` |
| trigger-service | `trigger` | notification-service | `notification` |
| workflow-service | `workflow` | usage-metering-service | `usage-metering` |
| scheduler-service | `scheduler` | approval-service | `approval` |
| audit-service (schema owner of audit transport) | `audit` | | |

## 2. Platform plane

**`waver.tenant.organization.v1`**: producer tenant-service · CP · key `organizationId` · full list in RFC-0003
| CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|
| `waver.tenant.OrganizationCreated.v1` | identity-access, customer-service, reseller-service | `organizationId`, `orgType`, `status` (PENDING), `parentOrgId` | A |
| `waver.tenant.OrganizationStatusChanged.v1` | agent-runtime, channel, tool-runtime, trigger, workflow, scheduler, identity-access, all data-owning services (on `PURGING`) | `organizationId`, `fromStatus`, `toStatus`, `reasonCode`, `causeEventId` | A |
| `waver.tenant.OrganizationRelationshipChanged.v1` | identity-access, services with `reseller_org_id` columns (ADR-0010) | `relationshipId`, `parentOrgId`, `childOrgId`, `relationType`, `validFrom`, `validTo`, `status` | A |

**`waver.tenant.workspace.v1`** / **`waver.tenant.domain.v1`**: producer tenant-service · CP · key `workspaceId` / `domainId`
| CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|
| `waver.tenant.WorkspaceCreated.v1` | identity-access, agent-builder, provisioning (saga confirmation), usage-metering | `workspaceId`, `orgId` | A |
| `waver.tenant.DomainCreated.v1` | identity-access, agent-builder, knowledge, memory | `domainId`, `workspaceId`, `domainKey` | A |

**`waver.tenant.resource-share.v1`**: producer tenant-service (per MICROSERVICES_MAP; see Open Questions) · CP · key `resourceShareId`
| CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|
| `waver.tenant.ResourceShareGranted.v1` | agent-builder, knowledge, connector, agent-runtime | `resourceShareId`, `resourceType`, `resourceId`, `sourceDomainId`, `targetDomainId`, `access` | A |
| `waver.tenant.ResourceShareRevoked.v1` | same | `resourceShareId`, `revokedAt`, `reason` | A |

**`waver.tenant.purge-job.v1`**: producer tenant-service · CP · key `purgeJobId`
| CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|
| `waver.tenant.TenantDataPurged.v1` | audit, notification, billing (close relationship) | `purgeJobId`, `organizationId`, `servicesConfirmed[]` (`service`, `rowsDeleted`, `confirmedAt`), `completedAt` | A |

**`waver.identity.*`**: producer identity-access-service · CP · full list in RFC-0004
| Topic (key) | CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|---|
| `waver.identity.user.v1` (`userId`) | `waver.identity.UserInvited.v1` | notification-service | `userId`, `orgId`, `invitationId`, `email`, `locale`, `expiresAt` | A, PII |
| | `waver.identity.UserSessionsRevoked.v1` | control/partner/workspace-bff (session store), audit | `userId`, `reasonCode`, `revokedAt` | A |
| `waver.identity.membership.v1` (`membershipId`) | `waver.identity.MembershipChanged.v1` | handoff, approval, support, agent-builder | `membershipId`, `userId`, `orgId`, `workspaceIds[]`, `status` | A |
| | `waver.identity.RoleAssigned.v1` | handoff, approval, support | `membershipId`, `roleKey`, `scope` | A |
| `waver.identity.service-account.v1` (`serviceAccountId`) | `waver.identity.ApiKeyCreated.v1` | audit, notification | `apiKeyId`, `serviceAccountId`, `keyPrefix`, `roles[]`, `expiresAt` (never the secret) | A |
| | `waver.identity.ApiKeyRevoked.v1` | edge-gateway (token cache eviction), audit, notification | `apiKeyId`, `revokedAt`, `reasonCode` | A |
| `waver.identity.support-access-grant.v1` (`grantId`) | `waver.identity.SupportAccessGranted.v1` | every service holding a grant view (RFC-0004), support, notification | `grantId`, `supportCaseId`, `grantee`, `scope`, `accessLevel`, `expiresAt` | A |
| | `waver.identity.SupportAccessRevoked.v1` | same | `grantId`, `revokedAt`, `reasonCode` | A |
| `waver.identity.idp-configuration.v1` (`idpConfigurationId`) | `waver.identity.IdpConfigurationChanged.v1` | audit, notification (org admins) | `idpConfigurationId`, `orgId`, `protocol`, `status`, `changedFields[]` | A |

**Other platform topics**
| Topic (producer, class, key) | CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|---|
| `waver.approval.approval-request.v1` (approval-service, CP, `approvalRequestId`) | `waver.approval.ApprovalRequested.v1` | notification (approvers) | `approvalRequestId`, `policyKey`, `subjectType`, `subjectId`, `requesterId`, `expiresAt` | A |
| | `waver.approval.ApprovalGranted.v1` / `ApprovalRejected.v1` / `ApprovalExpired.v1` | requesting service (contract, customer, billing, support, identity-access, tool-runtime, tenant, marketplace), notification | `approvalRequestId`, `subjectType`, `subjectId`, `decision`, `decidedBy`, `reason` | A |
| `waver.notification.notification.v1` (notification-service, CP, `notificationId`) | `waver.notification.NotificationSent.v1` | billing (dunning notice evidence), customer-service (suspension notices), audit | `notificationId`, `templateKey`, `recipientRef` (ID, not address), `channel`, `sentAt` | A |
| `waver.usage-metering.usage-aggregation.v1` (usage-metering, CP, `usageAggregationId`) | `waver.usage-metering.UsageAggregated.v1` | billing | `orgId`, `meterKey`, `granularity` (HOUR/DAY/BILLING_PERIOD), `periodKey`, `quantity`, `unit`, `entitlementId` | |
| | `waver.usage-metering.UsagePeriodClosed.v1` | billing | `orgId`, `periodKey`, `closedAt` | |
| `waver.usage-metering.quota-counter.v1` (usage-metering, CP, `quotaCounterId`) | `waver.usage-metering.QuotaThresholdReached.v1` | notification | `quotaCounterId`, `orgId`, `meterKey`, `threshold`, `used`, `limit` | |
| | `waver.usage-metering.QuotaExceeded.v1` / `QuotaPeriodReset.v1` | agent-runtime, tool-runtime, channel, notification | same + `enforcement` | A (Exceeded) |
| `waver.usage-metering.quota-state.v1` (usage-metering, ST, `quotaCounterId`) | `waver.usage-metering.QuotaStateUpdated.v1` | agent-runtime, tool-runtime, channel | Full counter state (RFC-0020) | |
| `waver.audit.audit-record.v1` (**every service** via outbox, `auditRecordId`) | `waver.audit.AuditRecorded.v1` | audit-service | `action`, `resourceType`, `resourceId`, `outcome`, `reason`, `changes[]`, `requestId` (RFC-0006 §13) | A |

## 3. Commercial plane

| Topic (producer, class, key) | CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|---|
| `waver.reseller.reseller.v1` (reseller-service, CP, `resellerId`; full list RFC-0017) | `waver.reseller.ResellerApproved.v1` | tenant (org/relationship), marketplace, notification | `resellerId`, `resellerOrgId`, `tier` | A |
| | `waver.reseller.ResellerActivated.v1` | tenant (org ACTIVE), billing (wholesale relationship), marketplace | `resellerId`, `resellerOrgId` | A |
| | `waver.reseller.ResellerRestricted.v1` / `ResellerSuspended.v1` | contract (block new sales), provisioning, marketplace, tenant (on suspension), notification | `resellerId`, `resellerOrgId`, `reasonCode`, `scope` | A |
| `waver.customer.customer.v1` (customer-service, CP, `customerId`; RFC-0017) | `waver.customer.CustomerRegistered.v1` | billing (billing profile ref), notification | `customerId`, `customerOrgId`, `resellerOrgId` | A |
| | `waver.customer.CustomerOwnershipTransferred.v1` | tenant (OrganizationRelationship), contract, entitlement, billing, usage-metering, support (`reseller_org_id`) | `customerId`, `customerOrgId`, `fromResellerOrgId`, `toResellerOrgId`, `effectiveAt` | A |
| | `waver.customer.CustomerLifecycleChanged.v1` | billing, notification | `customerId`, `fromState`, `toState` | A |
| | `waver.customer.CustomerTerminated.v1` | tenant (TERMINATED), entitlement (revoke), billing (close), contract, notification | `customerId`, `customerOrgId`, `effectiveAt`, `reasonCode` | A |
| `waver.customer.suspension-case.v1` (customer-service, CP, `suspensionCaseId`; RFC-0017) | `waver.customer.CustomerRestricted.v1` | entitlement (→ EntitlementRestricted), notification | `suspensionCaseId`, `customerOrgId`, `mode` (MANUAL/AUTOMATIC), `reasonCode`, `policyRef` | A |
| | `waver.customer.CustomerSuspended.v1` | entitlement (→ EntitlementSuspended), tenant (→ SUSPENDED), notification | same | A |
| | `waver.customer.CustomerReactivated.v1` | entitlement (→ EntitlementReinstated), tenant (→ ACTIVE), provisioning (`REACTIVATION_VERIFY`), notification | `suspensionCaseId`, `customerOrgId`, `approvalRequestId` | A |
| `waver.catalog.product.v1` (catalog, CP, `productId`) | `waver.catalog.ProductPublished.v1` | marketplace, contract (cache) | `productId`, `skuIds[]`, `version` | A |
| `waver.catalog.price-book.v1` (catalog, CP, `priceBookId`) | `waver.catalog.PriceBookActivated.v1` | contract (pricing cache), marketplace | `priceBookId`, `currency`, `validFrom` (no prices) | A |
| `waver.marketplace.listing.v1` (marketplace, CP, `listingId`; RFC-0017) | `waver.marketplace.ListingPublished.v1` / `ListingDeprecated.v1` / `ListingRetired.v1` | contract (quote validation), notification (impacted resellers) | `listingId`, `listingVersionId`, `status`, `replacementListingId` | A |
| | `waver.marketplace.AvailabilityChanged.v1` | contract, partner-facing caches | `listingId`, `resellerGroupIds[]`, `resellerOrgIds[]` | A |
| `waver.contract.quote.v1` (contract, CP, `quoteId`; RFC-0013) | `waver.contract.QuoteAccepted.v1` | notification | `quoteId`, `customerOrgId`, `contractId` | A |
| `waver.contract.contract.v1` (contract, CP, `contractId`; RFC-0013) | `waver.contract.ContractActivated.v1` | entitlement, provisioning, billing, notification | `contractId`, `contractVersionId`, `customerOrgId`, `resellerOrgId`, `effectiveFrom/To`, `items[]` (IDs, quantity; no prices) | A |
| | `waver.contract.ContractAmended.v1` / `ContractRenewed.v1` | entitlement, provisioning, billing | `contractId`, `contractVersionId`, `changedItemLineageIds[]` | A |
| | `waver.contract.ContractCancelled.v1` / `ContractExpired.v1` / `ContractTerminated.v1` | entitlement, provisioning (deprovision), billing, notification | `contractId`, `effectiveAt`, `reasonCode` | A |
| | `waver.contract.ContractRenewalDue.v1` | notification (90/60/30 days) | `contractId`, `endDate`, `noticeDays` | |
| `waver.entitlement.entitlement.v1` (entitlement, CP, `entitlementId`; RFC-0013) | `waver.entitlement.EntitlementGranted.v1` / `EntitlementChanged.v1` | agent-builder, provisioning, notification | `entitlementId`, `capabilityKey`, `scope`, `limits[]`, `contractItemId` | A |
| | `waver.entitlement.EntitlementRestricted.v1` / `EntitlementSuspended.v1` / `EntitlementReinstated.v1` / `EntitlementRevoked.v1` | agent-builder, provisioning, notification | `entitlementId`, `technicalStatus`, `causeEventId` | A |
| `waver.entitlement.entitlement-snapshot.v1` (entitlement, ST, **key customer `orgId`**) | `waver.entitlement.EntitlementSnapshotUpdated.v1` | agent-runtime, tool-runtime, channel, knowledge, usage-metering | Full EntitlementSet, `snapshotVersion` (RFC-0013 §8) | |
| `waver.provisioning.provisioning-job.v1` (provisioning, CP, `provisioningJobId`; RFC-0013) | `waver.provisioning.ProvisioningSucceeded.v1` | contract (deliveryStatus), tenant (org ACTIVE), customer (lifecycle ACTIVE), billing (start trigger policy), notification | `provisioningJobId`, `contractVersionId`, `customerOrgId`, `workspaceIds[]` | A |
| | `waver.provisioning.ProvisioningFailed.v1` | contract, notification (reseller ops + WAVER ops) | `provisioningJobId`, `failedStep`, `errorCode`, `attempt` | A |
| `waver.billing.invoice.v1` (billing, CP, `invoiceId`; RFC-0020) | `waver.billing.InvoiceIssued.v1` | notification | `invoiceId`, `issuerOrgId`, `payerOrgId`, `total` (Money), `dueDate` | A |
| | `waver.billing.InvoicePastDue.v1` | customer-service, notification | `invoiceId`, `payerOrgId`, `daysOverdue` | A |
| `waver.billing.payment.v1` (billing, CP, `paymentId`) | `waver.billing.PaymentReceived.v1` | customer-service (reactivation evaluation), notification | `paymentId`, `payerOrgId`, `amount`, `allocatedInvoiceIds[]` | A |
| `waver.billing.dunning-case.v1` (billing, CP, `dunningCaseId`) | `waver.billing.DunningStageChanged.v1` | customer-service (suspension proposal), notification | `dunningCaseId`, `payerOrgId`, `fromStage`, `toStage` | A |
| | `waver.billing.DunningNoticeDue.v1` | notification | `dunningCaseId`, `noticeKey` | |
| `waver.support.support-case.v1` (support, CP, `supportCaseId`) | `waver.support.SupportCaseOpened.v1` / `SupportCaseEscalated.v1` / `SupportCaseResolved.v1` | notification | `supportCaseId`, `orgId`, `level` (L1/L2/L3), `status` | A |

## 4. Agent plane

| Topic (producer, class, key) | CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|---|
| `waver.agent-builder.agent-version.v1` (agent-builder, CP, `agentVersionId`; RFC-0016) | `waver.agent-builder.AgentVersionCreated.v1` | evaluation (release gate), tool-runtime (binding view) | `agentVersionId`, `agentId`, `number`, `artifactHash`, `strategy`, `toolBindings[]` | A |
| `waver.agent-builder.deployment.v1` (agent-builder, CP, **key `agentId`**, per RFC-0016 to order activations per agent) | `waver.agent-builder.DeploymentActivated.v1` / `DeploymentRolledBack.v1` | agent-runtime, channel (routes), tool-runtime, trigger, notification | `deploymentId`, `agentVersionId`, `environmentId`, `channelConnectionIds[]`, `previousDeploymentId` | A |
| `waver.agent-builder.deployment-state.v1` (agent-builder, ST, `deploymentId`; proposed) | `waver.agent-builder.DeploymentStateChanged.v1` | agent-runtime, channel | Full active-deployment view | |
| `waver.agent-runtime.agent-run.v1` (agent-runtime, DP, `agentRunId`; RFC-0011) | `waver.agent-runtime.AgentRunStarted.v1` | usage-metering (if the meter counts starts) | `agentRunId`, `deploymentId`, `agentVersionId`, `mode`, `trigger` | |
| | `waver.agent-runtime.AgentRunCompleted.v1` / `AgentRunFailed.v1` | usage-metering, workflow, evaluation (sandbox), notification (public webhooks) | `agentRunId`, `outcomeCode`, `durationMs`, `steps`, `tokenTotals`, `toolCalls` | |
| | `waver.agent-runtime.ObjectiveCompleted.v1` | workflow, notification (public webhooks) | `agentRunId`, `objectiveId`, `status` (value not included) | |
| `waver.agent-runtime.conversation.v1` (agent-runtime, RC, `conversationId`; RFC-0011) | `waver.agent-runtime.AgentMessageCreated.v1` | channel-service (outbound delivery) | `conversationId`, `channelSessionId`, `messageId`, `content` | R, PII |
| | `waver.agent-runtime.HandoffRequested.v1` | handoff, channel | `conversationId`, `agentRunId`, `reasonCode`, `queueHint` | A |
| `waver.memory.memory-scope.v1` (memory, CP, `memoryScopeId`) | `waver.memory.MemoryEntryWritten.v1` | — (analytics) | `memoryScopeId`, `memoryEntryId`, `kind` (no content) | |
| | `waver.memory.MemoryPurged.v1` | audit | `memoryScopeId`, `entriesPurged`, `reasonCode` | A |
| | `waver.memory.StorageUsageReported.v1` | usage-metering | `orgId`, `storeId`, `bytes`, `measuredAt` | |
| `waver.evaluation.evaluation-run.v1` (evaluation, CP, `evaluationRunId`) | `waver.evaluation.EvaluationCompleted.v1` | agent-builder (release gate), notification | `evaluationRunId`, `agentVersionId`, `passed`, `scores` | A |
| `waver.model-gateway.model-call.v1` (model-gateway, DP, `modelCallId`) | `waver.model-gateway.ModelCallCompleted.v1` | usage-metering (AI cost source of truth, ADR-0017) | `modelCallId`, `agentRunId`, `provider`, `model`, `modelProfile`, `inputTokens`, `outputTokens`, `cost` (Money), `byok`, `latencyMs` | |
| `waver.handoff.handoff-ticket.v1` (handoff, CP, `handoffTicketId`) | `waver.handoff.HandoffAssigned.v1` | agent-runtime, channel, notification | `handoffTicketId`, `conversationId`, `assigneeId`, `queueId` | A |
| | `waver.handoff.HandoffResolved.v1` | agent-runtime (resume), channel, usage-metering | `handoffTicketId`, `resolution`, `resumeAgent` | A |

## 5. Capability and automation planes

| Topic (producer, class, key) | CloudEvents type | Main consumers | Payload summary | Flags |
|---|---|---|---|---|
| `waver.tool-registry.tool.v1` (tool-registry, CP, `toolId`; RFC-0012) | `waver.tool-registry.ToolVersionPublished.v1` / `ToolDeprecated.v1` | tool-runtime, agent-builder, notification | `toolId`, `toolVersionId`, `major`, `sideEffectClass`, `status` | A |
| `waver.tool-runtime.tool-execution.v1` (tool-runtime, DP, `toolExecutionId`; RFC-0012) | `waver.tool-runtime.ToolExecutionCompleted.v1` / `ToolExecutionFailed.v1` | agent-runtime (async resume), workflow, usage-metering, audit (side-effecting tools) | `toolExecutionId`, `toolVersionId`, `agentRunId`, `finalState`, `errorCode`, `latencyMs`, `meteredUnits` (no input/output) | A |
| `waver.connector.connector.v1` (connector, CP, `connectorId`; RFC-0018) | `waver.connector.ConnectorVersionPublished.v1` | tool-registry (derives one Tool per action), marketplace | `connectorId`, `connectorVersionId`, `kind`, `actions[]` | A |
| `waver.connector.connection.v1` (connector, CP, `connectionId`) | `waver.connector.ConnectionCreated.v1` / `ConnectionHealthChanged.v1` | agent-builder (connection view), tool-runtime, provisioning, notification | `connectionId`, `connectorVersionId`, `scope`, `health` | A |
| | `waver.connector.StorageUsageReported.v1` | usage-metering | `orgId`, `storeId`, `bytes`, `measuredAt` | |
| `waver.connector.credential.v1` (connector, CP, `credentialId`) | `waver.connector.CredentialRotated.v1` | model-gateway, channel, billing (lease cache eviction), audit | `credentialId`, `kind`, `rotatedAt` (never secret material) | A |
| | `waver.connector.CredentialLeased.v1` | audit | `credentialId`, `kind` (`LLM_PROVIDER_KEY` → model-gateway, `CHANNEL_PROVIDER_TOKEN` → channel, payment gateway credential → billing), `lessee`, `leaseId`, `expiresAt` (ADR-0014 rules, ADR-0022 Transit leases) | A |
| `waver.connector.webhook-endpoint.v1` (connector, RC, `webhookEndpointId`) | `waver.connector.WebhookReceived.v1` | trigger-service | `webhookEndpointId`, `connectionId`, `triggerKey`, `body` (≤ 256 KiB, else reference) | R, PII |
| `waver.knowledge.document.v1` (knowledge, CP, `documentId`) | `waver.knowledge.DocumentIngested.v1` | notification, agent-builder (KB status) | `documentId`, `knowledgeBaseId`, `chunks`, `status` | |
| `waver.knowledge.knowledge-source.v1` (knowledge, CP, `knowledgeSourceId`) | `waver.knowledge.KnowledgeSourceSynced.v1` | notification | `knowledgeSourceId`, `added`, `updated`, `removed`, `status` | |
| `waver.knowledge.knowledge-base.v1` (knowledge, CP, `knowledgeBaseId`) | `waver.knowledge.StorageUsageReported.v1` | usage-metering | `orgId`, `storeId`, `bytes`, `measuredAt` | |
| `waver.channel.channel-session.v1` (channel, RC, `channelSessionId`) | `waver.channel.MessageReceived.v1` | agent-runtime, handoff (active ticket) | `channelSessionId`, `messageId`, `channelType`, `senderRef`, `content` | R, PII |
| | `waver.channel.MessageDelivered.v1` / `MessageDeliveryFailed.v1` | usage-metering, agent-runtime, notification, handoff | `channelSessionId`, `messageId`, `provider`, `segments`, `errorCode` | |
| `waver.channel.channel-connection.v1` (channel, CP, `channelConnectionId`) | `waver.channel.ChannelNumberActivated.v1` / `ChannelNumberReleased.v1` | usage-metering, provisioning, agent-builder | `channelConnectionId`, `channelType`, `numberRef` | A |
| `waver.trigger.trigger.v1` (trigger, DP, `triggerId`) | `waver.trigger.TriggerFired.v1` | workflow, agent-runtime | `triggerId`, `firingId`, `targetRef`, `sourceEventId` | |
| `waver.workflow.workflow-run.v1` (workflow, DP, `workflowRunId`) | `waver.workflow.WorkflowRunCompleted.v1` / `WorkflowRunFailed.v1` | usage-metering, notification | `workflowRunId`, `workflowVersionId`, `outcome`, `steps` | |
| `waver.scheduler.schedule.v1` (scheduler, DP, `scheduleId`) | `waver.scheduler.ScheduleFired.v1` | trigger, workflow, agent-runtime | `scheduleId`, `firingId`, `scheduledFor`, `targetRef` | |

## 6. Canonical cross-service flows
Notation: `service: Event`. `→` means the next step consumes the event. `[sync]` marks a REST call (RFC-0005).

### 6.1 Contract activation → entitlements → provisioning → billing / notification / audit (RFC-0013)
```text
contract-service: ContractActivated
 ├→ entitlement-service: EntitlementGranted (×items) + EntitlementSnapshotUpdated
 │    └→ agent-runtime / tool-runtime / channel / knowledge / usage-metering: update entitlement_snapshot
 ├→ provisioning-service: ProvisioningJobCreated
 │    [sync] tenant-service POST /v1/workspaces → tenant-service: WorkspaceCreated
 │    [sync] entitlement-service GET (AWAIT_ENTITLEMENTS)
 │    [sync] identity-access invite admins → identity: UserInvited → notification-service: NotificationSent
 │    [sync] channel / connector setup → channel: ChannelNumberActivated
 │    └→ provisioning-service: ProvisioningSucceeded
 │         ├→ tenant-service: OrganizationStatusChanged(ACTIVE)
 │         ├→ contract-service: ContractDeliveryStatusChanged(DELIVERED)
 │         ├→ customer-service: CustomerLifecycleChanged(ACTIVE)
 │         └→ billing-service: charge schedule starts (billing.startTrigger, RFC-0013 Q4)
 ├→ billing-service: ChargeSchedule created → (billing run) InvoiceIssued → notification
 └→ audit-service: AuditEvents for every A-flagged event above
```

### 6.2 Customer suspension and reactivation (AR-012, RFC-0017, RFC-0020)
```text
billing-service: InvoicePastDue → DunningStageChanged(GRACE_PERIOD → SUSPENSION_ELIGIBLE)
 └→ customer-service: SuspensionCase (proposed / approved by reseller or automatic policy)
      ├ customer: CustomerRestricted → entitlement: EntitlementRestricted + EntitlementSnapshotUpdated
      └ customer: CustomerSuspended
           ├→ entitlement: EntitlementSuspended + EntitlementSnapshotUpdated
           │    └→ agent-runtime / channel / tool-runtime / trigger / workflow: refuse new runs per policy (data kept)
           ├→ tenant: OrganizationStatusChanged(SUSPENDED) → identity-access: UserSessionsRevoked (per policy)
           └→ notification: NotificationSent (reseller + customer admins); audit
billing: PaymentReceived → customer: CustomerReactivated (auto or ApprovalGranted)
 ├→ entitlement: EntitlementReinstated + snapshot → data plane resumes
 ├→ tenant: OrganizationStatusChanged(ACTIVE)
 └→ provisioning: REACTIVATION_VERIFY job → ProvisioningSucceeded (channels verified)
```

### 6.3 Agent deployment (RFC-0016, RFC-0011)
```text
agent-builder: AgentVersionCreated
 └→ evaluation-service: EvaluationRun (sandbox via agent-runtime [sync]) → EvaluationCompleted
      └→ agent-builder: release gate passed → [user deploys] [sync] entitlement decision API
           → DeploymentActivated + DeploymentStateChanged
              ├→ agent-runtime: deployment_view updated; new runs pinned to agentVersionId
              ├→ channel-service: route channelConnection → deployment
              ├→ tool-runtime / trigger-service: binding and trigger views
              └→ audit, notification
Rollback: DeploymentRolledBack (+ DeploymentStateChanged) → same consumers; in-flight runs keep their version.
```

### 6.4 Conversation turn with tool execution (RFC-0011, RFC-0012, RFC-0018)
```text
channel: MessageReceived → agent-runtime (gate: org_status_view, entitlement_snapshot, quota_state)
 [sync] knowledge / memory retrieval; [sync] model-gateway → model-gateway: ModelCallCompleted → usage-metering
 [sync] tool-runtime POST tool-executions
    ├ needs approval → [sync] approval-service → approval: ApprovalRequested → notification
    │                    approval: ApprovalGranted → tool-runtime resumes
    └ [sync] connector-service executes action (credential stays inside, ADR-0014)
 → tool-runtime: ToolExecutionCompleted | ToolExecutionFailed
      ├→ agent-runtime (async resume when not answered inline)
      ├→ usage-metering (tool units)
      └→ audit (side-effecting tools)
 → agent-runtime: AgentMessageCreated → channel-service delivers → channel: MessageDelivered → usage-metering
 → agent-runtime: AgentRunCompleted → usage-metering, notification (public webhooks)
```

### 6.5 Usage → quotas → billing (RFC-0020)
```text
ModelCallCompleted, ToolExecutionCompleted, AgentRunCompleted, MessageDelivered, WorkflowRunCompleted,
StorageUsageReported, ChannelNumberActivated/Released
 └→ usage-metering-service: UsageRecord (attributed via entitlement_snapshot)
      ├→ QuotaCounter update → QuotaStateUpdated (compacted) → agent-runtime / tool-runtime / channel
      │     ├ QuotaThresholdReached → notification (reseller + customer admin)
      │     └ QuotaExceeded → data plane refuses new billable work per enforcement; notification
      └→ UsageAggregated (HOUR/DAY/BILLING_PERIOD) → UsagePeriodClosed
           └→ billing-service: rating → billing run → InvoiceIssued → notification; audit
```

### 6.6 Purge after termination (RFC-0003, RFC-0007)
```text
customer: CustomerTerminated → tenant: OrganizationStatusChanged(TERMINATED) → retention window
 → [retention elapsed] approval: ApprovalGranted (purge) → tenant: OrganizationStatusChanged(PURGING)
 → every data-owning service purges, then [sync] POST /v1/purge-jobs/{id}/confirmations
 → tenant: TenantDataPurged + OrganizationStatusChanged(PURGED) + entitlement snapshot tombstone
```

## 7. Open Questions
1. ~~Context tokens~~ — resolved in the v1.2 review: this catalog's table is the standard (CONVENTIONS updated) and the RFCs were aligned.
2. ~~ResourceShare ownership~~ — resolved: tenant-service owns ResourceShare; resource-owning services enforce it from a read model (RFC-0003 aligned).
3. ~~`TenantDataPurged` producer~~ — resolved: tenant-service is the single producer after synchronous confirmations from each service (RFC-0003 aligned).
4. ~~`deployment-state` topic~~ — resolved: listed in MICROSERVICES_MAP and CONVENTIONS.
5. ~~Inbound webhook entry path~~ — resolved: two distinct owners. **connector-service** owns `WebhookEndpoint`/`WebhookReceived` for connector and provider webhooks. **trigger-service** owns customer-defined generic webhook triggers (`WebhookRoute`) that start workflows or agent runs. Both arrive on the `hooks.` host (ADR-0009).
6. Outbound customer webhooks (RFC-0008 §9) consume A-flagged and run events. The curated public event list (`waver.public.*`) still needs product definition.
