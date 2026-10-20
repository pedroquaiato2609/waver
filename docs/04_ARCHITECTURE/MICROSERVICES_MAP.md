# Microservices Map - v1.3

Status: PROPOSED (supersedes v1.1 baseline list; v1.1 decisions preserved). Revision 1.3 (2026-10-19): shared infrastructure components added (Keycloak, Redis, Vault, Temporal, Apicurio, Kafka Connect/Debezium, Qdrant, Istio).
Related: RFC-0001, RFC-0002, ADR-0009, ADR-0017, ADR-0019

## Changes from v1.1
| Change | Reason |
|---|---|
| Added **Experience plane** (`edge-gateway`, `control-bff`, `partner-bff`, `workspace-bff`) | Audience isolation by construction; customers can never reach marketplace/wholesale APIs (ADR-0009). |
| `objective-engine` and `flow-engine` became **modules of `agent-runtime`** | No network hop per conversational turn; engines own no independent data (ADR-0019). |
| Added `model-gateway-service` | No owner existed for LLM provider abstraction, fallback, BYOK and AI cost (ADR-0017). |
| Added `handoff-service` | Human handoff (Scope §12, §24) had no owner. |
| Added `approval-service` | Approvals are required by commercial (discounts, overrides, purge) and tool execution; one generic owner avoids duplication. |
| Added `support-service` | SupportCase, L1→L2/L3 escalation and impersonation grants had no owner. |
| Clarified owners of Organization, Quote, PricingRule, Suspension, Deployment | Ambiguous between several services in v1.1. |
| "observability platform" and "event-orchestration components" removed from the service list | They are infrastructure/patterns, not bounded contexts. |

## Plane overview
| Plane | Services | Plane type |
|---|---|---|
| Experience | edge-gateway, control-bff, partner-bff, workspace-bff | Edge (stateless) |
| Commercial | reseller-service, customer-service, catalog-service, marketplace-service, contract-service, entitlement-service, provisioning-service, billing-service, support-service | Control plane |
| Agent | agent-builder-service, agent-runtime, memory-service, evaluation-service, model-gateway-service, handoff-service | Builder/evaluation = control plane; runtime/memory/model-gateway/handoff = data plane |
| Capability | tool-registry-service, tool-runtime-service, connector-service, knowledge-service, channel-service | Registry = control plane; runtime/connector execution/channel/knowledge retrieval = data plane |
| Automation | trigger-service, workflow-service, scheduler-service | Data plane |
| Platform | tenant-service, identity-access-service, audit-service, notification-service, usage-metering-service, approval-service | Shared foundation |

Data-plane services must keep serving already-authorized workloads when control-plane services are down (AR-013). They do so with **local read models** built from events (e.g., agent-runtime keeps active Deployments and Entitlement snapshots locally).

## Service ownership (authoritative)
| Service | Owns (aggregates / data) | Publishes (main events) | Main sync dependencies |
|---|---|---|---|
| edge-gateway | — (routing config) | — | BFFs, Keycloak (JWKS), identity-access (API key validation), Redis (rate limiting) |
| control-bff / partner-bff / workspace-bff | HTTP sessions (in Redis, ADR-0020) | — | Only the services allowed for its audience (ADR-0009) |
| tenant-service | Organization, OrganizationRelationship, Workspace, Domain | OrganizationCreated, OrganizationRelationshipChanged, WorkspaceCreated, DomainCreated, OrganizationStatusChanged, ResourceShareGranted, ResourceShareRevoked, TenantDataPurged | — |
| identity-access-service | User, Membership, Role, Permission, ServiceAccount, ApiKey, SupportAccessGrant, IdP federation config | UserInvited, MembershipChanged, RoleAssigned, UserSessionsRevoked, ApiKeyCreated, ApiKeyRevoked, IdpConfigurationChanged, SupportAccessGranted, SupportAccessRevoked | tenant-service |
| reseller-service | ResellerProfile, ResellerTier, ResellerAgreement, ResellerOnboarding, ResellerScore | ResellerApproved, ResellerActivated, ResellerSuspended | tenant-service |
| customer-service | CustomerProfile, BillingProfile, CustomerOwnership, CustomerLifecycle, SuspensionCase | CustomerRegistered, CustomerOwnershipTransferred, CustomerRestricted, CustomerSuspended, CustomerReactivated, CustomerTerminated | tenant-service |
| catalog-service | Product, SKU, Bundle, SolutionPack, AddOn, PriceBook, WholesalePrice, RetailPrice, PricingRule, DiscountPolicy | ProductPublished, PriceBookActivated | — |
| marketplace-service | Publisher, Listing, ListingVersion, AvailabilityRule, Certification | ListingPublished, ListingDeprecated, ListingRetired, AvailabilityChanged | catalog-service |
| contract-service | Quote, Contract, ContractVersion, ContractItem, Amendment, Renewal, Cancellation | QuoteAccepted, ContractActivated, ContractAmended, ContractRenewed, ContractCancelled, ContractExpired | catalog-service, marketplace-service, approval-service |
| entitlement-service | Entitlement, EntitlementLimit, FeatureFlag | EntitlementGranted, EntitlementChanged, EntitlementSuspended, EntitlementRevoked | — (event-driven from contract/customer) |
| provisioning-service | ProvisioningJob (saga run as a Temporal workflow, ADR-0023) | ProvisioningSucceeded, ProvisioningFailed | tenant, identity-access, entitlement |
| billing-service | Invoice, InvoiceLine, Payment, Credit, Adjustment, LedgerEntry, DunningCase; later Commission, RevenueShare, Settlement | InvoiceIssued, PaymentReceived, InvoicePastDue, DunningStageChanged | usage-metering (aggregates), contract-service |
| support-service | SupportCase, Escalation, SLA policy | SupportCaseOpened, SupportCaseEscalated, SupportCaseResolved | identity-access (support grants) |
| agent-builder-service | Agent, AgentDraft, AgentVersion, ObjectiveDefinition, Flow/FlowVersion/FlowNode/FlowTransition, PolicyDefinition, Tool/Knowledge/Channel bindings, Environment, Deployment, Release | AgentVersionCreated, DeploymentActivated, DeploymentRolledBack | tool-registry, knowledge, entitlement, evaluation |
| agent-runtime (+ objective-engine, flow-engine modules) | AgentRun, Session, Conversation, Message, ExecutionContext, ObjectiveState, FlowState, local read models | AgentRunStarted, AgentRunCompleted, AgentRunFailed, HandoffRequested, ObjectiveCompleted | model-gateway, tool-runtime, knowledge, memory |
| memory-service | MemoryEntry, MemoryScope, RetentionPolicy | MemoryEntryWritten, MemoryPurged | model-gateway (embeddings) |
| evaluation-service | TestSuite, TestCase, Dataset, EvaluationRun, EvaluationResult, ReleaseGate | EvaluationCompleted | agent-runtime (sandbox mode), model-gateway |
| model-gateway-service | ModelCatalog, ModelProfile, RoutingRule, ProviderAccount, ModelCall log | ModelCallCompleted | connector-service (BYOK credentials) |
| handoff-service | HandoffTicket, Queue, Assignment, HandoffSLA | HandoffAssigned, HandoffResolved | identity-access |
| tool-registry-service | Tool, ToolVersion (input/output schema), ToolPermission, ExecutionPolicy template | ToolVersionPublished, ToolDeprecated | — |
| tool-runtime-service | ToolExecution, ExecutionApprovalLink | ToolExecutionCompleted, ToolExecutionFailed | tool-registry (cached), connector-service, approval-service, entitlement (cached) |
| connector-service | Connector, ConnectorVersion (manifest/coded), Connection, Credential (encrypted), WebhookEndpoint | ConnectorVersionPublished, ConnectionCreated, ConnectionHealthChanged, CredentialRotated, CredentialLeased, WebhookReceived | Vault Transit (credentials, outbound webhook signing; publishes the signing public keys) |
| knowledge-service | KnowledgeBase, KnowledgeSource, Document, Chunk (text + metadata in PostgreSQL; vectors in Qdrant, ADR-0025), RetrievalConfig | DocumentIngested, KnowledgeSourceSynced | model-gateway (embeddings), connector-service (sources) |
| channel-service | Channel, ChannelConnection, ChannelSession, provider adapters, OutboundDelivery | MessageReceived, MessageDelivered, MessageDeliveryFailed | connector-service (provider credentials) |
| trigger-service | Trigger, inbound webhook routing | TriggerFired | — |
| workflow-service | Workflow, WorkflowVersion, WorkflowStep, WorkflowRun | WorkflowRunCompleted, WorkflowRunFailed | tool-runtime, agent-runtime |
| scheduler-service | Schedule (user-defined cron) | ScheduleFired | — |
| audit-service | AuditEvent (append-only, hash-chained) | — (consumer only) | — |
| notification-service | NotificationTemplate, NotificationPreference, Notification, DeliveryAttempt | NotificationSent | channel-service (e-mail/whatsapp delivery) |
| usage-metering-service | Meter, UsageRecord, UsageAggregation, QuotaCounter | UsageAggregated, QuotaThresholdReached, QuotaExceeded | — |
| approval-service | ApprovalPolicy, ApprovalRequest, ApprovalDecision | ApprovalRequested, ApprovalGranted, ApprovalRejected, ApprovalExpired | identity-access |

## Ownership clarifications (v1.2 review)
- **Customer ownership:** `customer-service` owns the business fact (`CustomerOwnership`, transfers and approvals). `tenant-service` maintains the technical `OrganizationRelationship` only by consuming `CustomerOwnershipTransferred` / `ResellerApproved`. No other service writes relationships.
- **Organization creation:** `reseller-service` and `customer-service` request creation of RESELLER / CUSTOMER organizations (status PENDING) from tenant-service. `provisioning-service` creates the Workspace and activates the organization.
- **Reseller groups** used by marketplace AvailabilityRules are owned by `marketplace-service`.
- **More events** from RFC-0011/0012/0016/0018/0019: AgentRunCancelled, AgentRunTimedOut, AgentMessageCreated, HandoffInputReceived, ConversationClosed (agent-runtime); AgentCreated, AgentArchived, DeploymentDeactivated, PolicySetPublished (agent-builder, plus the compacted deployment-state topic); PollingItemDetected (connector); ToolRetired (tool-registry); ModelProfilePublished, ModelProfileDeprecated, BudgetThresholdReached, BudgetExceeded (model-gateway, which also owns the BudgetPolicy aggregate).
- **Additional sync dependencies:** agent-builder-service → approval-service (production deployment approvals); provisioning-service → channel-service and connector-service (capability-specific setup); support-service, billing-service → approval-service; billing-service → payment-gateway and fiscal adapters (ports, provider open); notification-service → connector-service (signed delivery of outbound customer webhooks).
- **Additional events** introduced by RFC-0013/0017/0020: ContractTerminated, ContractRenewalDue, EntitlementRestricted, EntitlementReinstated, ResellerRestricted, CustomerLifecycleChanged, DunningNoticeDue, UsagePeriodClosed, QuotaPeriodReset, ChannelNumberActivated/Released (channel-service), StorageUsageReported (knowledge, memory, connector). The full list lives in `07_API_EVENTS/EVENT_CATALOG.md`.
- **Compacted state topics** for data-plane read models: `waver.entitlement.entitlement-snapshot.v1` (key: customer orgId), `waver.usage-metering.quota-state.v1` (Redis-down fallback since v1.3) and `waver.agent-builder.deployment-state.v1` (RFC-0006, RFC-0011).
- **Webhooks:** inbound connector/provider webhooks → connector-service (`WebhookEndpoint`); inbound customer-defined webhook triggers → trigger-service (`WebhookRoute`). **Outbound** webhooks to customers: notification-service owns the subscriptions (`WebhookSubscription`), and connector-service holds the signing secret and performs delivery (it already has egress and the SSRF controls).
- **ResourceShare** (cross-Domain sharing) is owned by tenant-service; resource-owning services enforce it from a read model.
- **Reseller contact shown to customers** ("feature not contracted" screen, ADR-0006): customer-service keeps a read model of the owning reseller's public support contact (from `ResellerContactChanged`, reseller-service) and serves it to workspace-bff. Customer "expansion requests" are SupportCases of type `EXPANSION_REQUEST` in support-service, routed to the reseller. workspace-bff never calls reseller-service.
- **Reporting:** v1 has no reporting service; KPIs are composed by BFFs from owner read models. A dedicated analytics service would need an ADR.

## Shared infrastructure components (v1.3)
These are platform components, not bounded contexts. They own no business data; each WAVER service keeps its own isolated slice (prefix, collection, namespace, role).

| Component | Purpose | Used by | Isolation per service | ADR |
|---|---|---|---|---|
| Keycloak | Identity provider: login, MFA, SSO brokering, tokens, token exchange | edge-gateway, all BFFs and services (JWKS); identity-access-service drives it via Admin API | One client per service/BFF; Keycloak Organization per WAVER Organization | ADR-0021 |
| Redis | Sessions, rate limiting, real-time quota counters, idempotency keys, caches, short locks, run-output streams | BFFs, edge-gateway, usage-metering, agent-runtime, channel, any service (cache) | ACL user + key prefix per service | ADR-0020 |
| HashiCorp Vault | Secrets, dynamic DB credentials, Transit encryption/signing | all services (K8s auth); connector-service (tenant credentials) | Vault role and policy per service account | ADR-0022 |
| Temporal | Durable workflows and timers | provisioning, billing, customer, reseller, contract, agent-runtime, tool-runtime, workflow, tenant, knowledge | Namespace per plane, task queues per service, `OrgId` search attribute | ADR-0023 |
| Apicurio Registry | Avro schema registry for all topics | all producers/consumers; CI registers schemas | Subjects per topic; write access only for CI | ADR-0024 |
| Kafka Connect + Debezium | Outbox relay from each producing database to Kafka | every service with an outbox | One connector per producing database | ADR-0024 |
| Qdrant | Vector search | knowledge-service, memory-service | Collections and API key per service; `org_id` payload filter | ADR-0025 |
| Istio (ambient) | mTLS, service authorization, egress gateway, canary | all `waver-*` namespaces | AuthorizationPolicy per service from the dependency graph | ADR-0026 |

## Future (not in v1)
- Native apps (`native-crm-service`, `native-scheduling-service`, ...): each a normal service that exposes its actions as Tools in tool-registry, exactly like an external connector (Scope §19).
- `connector-runner-<group>`: separate deployments for heavy or high-risk coded connectors (ADR-0016).

## Mandatory boundaries
Each service owns its domain model, persistence, migrations, API/event contracts, tests, deployment and observability. No cross-database queries and no internal implementation imports across services (AR-002, AR-003). The only shared code is `platform/*` (no domain code) and clients generated from `contracts/*` (ADR-0015).

## Delivery phasing
Services are introduced by vertical slice, not all at once. See `04_ARCHITECTURE/DELIVERY_ROADMAP.md`.
