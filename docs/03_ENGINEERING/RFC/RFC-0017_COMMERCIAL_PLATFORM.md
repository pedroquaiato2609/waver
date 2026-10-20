# RFC-0017 - Commercial & Reseller Platform
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal, Keycloak, Apicurio Registry + Avro + Debezium outbox, Redis, Istio).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3
Related: ADR-0006, ADR-0009, ADR-0010, ADR-0012, ADR-0020, ADR-0021, ADR-0023, ADR-0024, ADR-0026, AR-008, AR-009, AR-012, RFC-0004, RFC-0013, RFC-0020, Commercial §1–§20, THREE_SYSTEMS

## Summary
Define WAVER Control, WAVER Partner, customer ownership, private marketplace, contracts, billing, dunning and support boundaries. This RFC specifies the backends behind Control and Partner — `reseller-service`, `customer-service`, `catalog-service`, `marketplace-service`, `support-service` — the audience rules enforced by the BFF split (ADR-0009), roles and approvals, notifications, KPI data sources and exception handling. Contracts/entitlements/provisioning are in RFC-0013; usage, billing and dunning in RFC-0020.

## Context / Problem
WAVER is B2B2B: WAVER governs the network and catalog, resellers sell and operate their portfolio, customers only use what was contracted (Commercial §1). The Commercial document describes the daily operation but not the bounded contexts, state machines, permissions or how "the customer never sees the marketplace, wholesale or margins" is guaranteed. MICROSERVICES_MAP v1.2 assigns owners; this RFC makes them concrete. Many business rules remain open (Commercial §20); they are modelled as configurable policies.

## Goals
- Explicit ownership and state machines for resellers, customers, catalog, listings and support cases.
- Customer-facing leakage of marketplace, wholesale, margin, commission or other customers impossible by construction (ADR-0006, ADR-0009).
- Commercial, financial and access status kept as separate fields (Commercial §13).
- Every sensitive action authorized, approved where required (Commercial §11.1) and audited (AR-009).
- Resellers operate their portfolio without WAVER involvement for routine tasks (Commercial §4).

## Non-goals
- Screen design of Control and Partner (separate UX artifacts).
- Pricing values, margins, discount limits, grace periods, SLA targets (business decisions; see Open Questions).
- Third-party publishers and revenue share activation (prepared only).

## Requirements / ARs
| AR | Application |
|---|---|
| AR-001/002/003 | Five services with own DBs (`waver_reseller`, `waver_customer`, `waver_catalog`, `waver_marketplace`, `waver_support`). |
| AR-004/015 | REST `/v1` + events on `waver.<context>.<aggregate>.v1` (Avro payloads registered in Apicurio Registry, CloudEvents binary mode, Debezium outbox relay — ADR-0024). Long-running flows are Temporal workflows owned by the service that owns the process (ADR-0023); other services interact only through that service's API or events. |
| AR-008 | Tenant context only from token; resellers reach customers only through the audited portfolio path (ADR-0010). |
| AR-009 | All transitions emitted as events, consumed by audit-service; approvals via approval-service. |
| AR-012 | Suspension of reseller or customer is reversible and never deletes data; termination/purge are separate, approval-gated processes. |

## Proposed Design

### 1. Experiences, BFFs and the "customer never sees" rules
| Backend service | control-bff (WAVER) | partner-bff (Reseller) | workspace-bff (Customer) |
|---|---|---|---|
| reseller-service | full | own profile, contacts, tier (read) | **no client** |
| customer-service | oversight (audited cross-tenant) | own portfolio | own profile; reseller support contact projection |
| catalog-service | full | price books/SKUs allowed to the reseller (wholesale + retail) | **no client** |
| marketplace-service | full | available listings only | **no client** |
| contract-service (RFC-0013) | full | own portfolio | optional `customer-contract-summaries` only (no prices) |
| entitlement-service | read, manual holds | own portfolio | decision API + own "enabled capabilities" |
| billing-service (RFC-0020) | full | own invoices (both sides) | customer invoices only if the reseller enables `exposeBillingToCustomer` (Commercial §18), retail only |
| usage-metering-service | full incl. cost | own portfolio, quantities, no WAVER cost | own quantities and limits, no cost |
| support-service | full (L2/L3) | own portfolio (L1) | own cases |
| provisioning-service | full | own portfolio | **no client** |

Enforcement: (1) CI allow-list of generated API clients per BFF (ADR-0009); (2) customer-audience endpoints are separate projection resources that do not contain wholesale/margin/cost fields at all (not filtered); (3) E2E audience tests. When a customer touches a non-contracted capability, workspace-bff returns `CAPABILITY_NOT_CONTRACTED` with the reseller contact (Commercial §18); it never links to a marketplace.

### 2. reseller-service
| Entity | Key fields |
|---|---|
| ResellerProfile | `id`, `org_id` (PLATFORM org, owner), `resellerOrgId` (null until approved), `legalName`, `taxIdType`, `taxId`, `onboardingStatus`, `salesRestriction` (`NONE`,`NEW_SALES_BLOCKED`,`PROVISIONING_BLOCKED`), `tierId`, `territories[]`, `version` |
| ResellerContact | `resellerProfileId`, `contactType` (`COMMERCIAL`,`FINANCIAL`,`TECHNICAL`,`LEGAL`), name, email, phone (PII) |
| ResellerTier | `id`, `tierKey`, `name`, `commercialLimits` (policy refs: max open quotes, credit limit ref, discount policy ref) |
| ResellerAgreement | `id`, `resellerProfileId`, `agreementNumber`, `validFrom`, `validTo`, `renewalRule`, `documentRefs[]`, `acceptedAt`, `acceptedBy`, `status` (`DRAFT`,`SIGNED`,`EXPIRED`,`TERMINATED`) |
| ResellerOnboarding | `resellerProfileId`, checklist items (`DUE_DILIGENCE`, `COMMERCIAL_APPROVAL`, `AGREEMENT_SIGNED`, `ORGANIZATION_CREATED`, `CATALOG_ASSIGNED`, `PRICING_RULES_SET`, `ADMIN_INVITED`, `TRAINING_DONE`), each with `doneAt`, `doneBy`, `evidenceRef` |
| ResellerScore | `resellerOrgId`, `period`, components (revenue, growth, churn, default, tickets, consumption), `weightsPolicyKey`, `score` (computed from events) |
| ResellerRestriction | `id`, `resellerOrgId`, `level`, `source` (`FINANCIAL_DEFAULT`,`COMPLIANCE`,`MANUAL`), `sourceRef` (dunningCaseId), `placedBy`, `reason`, `placedAt`, `liftedAt` |

Onboarding lifecycle (Commercial §3.1, §9.1):
| From | To | Trigger / who | Guard | Event |
|---|---|---|---|---|
| — | LEAD | `WAVER_COMMERCIAL` creates, or application form | — | `ResellerLeadRegistered` |
| LEAD | UNDER_REVIEW | `WAVER_COMMERCIAL` starts due diligence | contacts complete | `ResellerReviewStarted` |
| UNDER_REVIEW | APPROVED | `ApprovalGranted` (`RESELLER_APPROVAL` policy) | due diligence done | `ResellerApproved` |
| UNDER_REVIEW | REJECTED | `ApprovalRejected` / `WAVER_COMMERCIAL` | — | `ResellerRejected` |
| APPROVED | ACTIVE | `WAVER_COMMERCIAL` activates | all checklist items done; reseller Organization created in tenant-service; ≥1 AvailabilityRule | `ResellerActivated` |
| ACTIVE | SUSPENDED | `WAVER_COMMERCIAL`/`WAVER_FINANCE` with reason, approval `RESELLER_SUSPENSION` | — | `ResellerSuspended` |
| SUSPENDED | ACTIVE | same roles, approval | cause resolved | `ResellerReinstated` |
| ACTIVE, SUSPENDED | TERMINATED | `WAVER_SUPER_ADMIN`, approval `TERMINATION` | customer portfolio transferred or a run-off plan recorded | `ResellerTerminated` |

On `APPROVED`, reseller-service calls tenant-service `POST /v1/organizations` (`orgType = RESELLER`, idempotent) and identity-access-service to invite the Reseller Owner (identity-access-service creates the matching Keycloak organization and user invitation through the Keycloak Admin API, ADR-0021; reseller-service never calls Keycloak).

**Onboarding as a Temporal workflow** (ADR-0023): each reseller onboarding runs as `ResellerOnboardingWorkflow` in reseller-service (namespace `waver-commercial`, task queue `reseller-service.reseller-onboarding`, `workflowId = reseller-onboarding:<resellerProfileId>`), started at `LEAD` and completed at `ACTIVE` or `REJECTED`. The lifecycle table above and the checklist items are unchanged; the workflow only orchestrates them:
- It waits for the review start (`POST /v1/resellers/{id}/review` → `startReview` signal), checklist completions (`PUT /v1/resellers/{id}/onboarding-checklist` → `checklistItemDone` signal with `doneBy` and `evidenceRef`) and the activation command (`POST /v1/resellers/{id}/activation` → `activate` signal). Each signal comes from reseller-service's own API handler, after authorization, and is mirrored in the `ResellerOnboarding` rows, which stay the business record shown in Control.
- The approval request (`RESELLER_APPROVAL`) is created by an activity in approval-service. The decision returns as `ApprovalGranted`/`ApprovalRejected`/`ApprovalExpired` events; reseller-service's own consumer (inbox-deduplicated) signals its own workflow. approval-service never signals reseller-service workflows.
- On `APPROVED`, the `ORGANIZATION_CREATED` and `ADMIN_INVITED` items run as idempotent activities (tenant-service, identity-access-service, `Idempotency-Key` derived from the workflow ID and item key). The activation guard (all items done, organization created, ≥1 AvailabilityRule) is checked by the activity that performs `APPROVED → ACTIVE`.
- Every transition is written with its event to the outbox in one local transaction. No business timeout is added: a reseller can stay in `LEAD`/`UNDER_REVIEW` as long as today; the workflow waits durably.

Suspension, reinstatement and termination of an active reseller stay simple approval-driven transitions: reseller-service records the `approvalRequestId` and its approval-event consumer applies the transition; no workflow is needed because there is no timer or multi-step side effect.

**Partial suspension** (Commercial §3.1) is a `ResellerRestriction` (no lifecycle change): `NEW_SALES_BLOCKED` makes contract-service reject new quotes/contracts/expansions; `PROVISIONING_BLOCKED` additionally makes provisioning-service reject new `ACTIVATE`/`AMEND` jobs that increase scope (renewals, reactivations and in-flight jobs continue). Events: `ResellerRestricted`, `ResellerRestrictionLifted`. A full `SUSPENDED` reseller keeps read access to Partner for its portfolio, billing and support; its customers are **not** affected.

**Reseller in default with WAVER** (Commercial §17): billing-service runs dunning on the `PLATFORM_TO_RESELLER` billing relationship (RFC-0020). reseller-service consumes `DunningStageChanged` for reseller debtors and maps stages to restriction levels through `ResellerDefaultPolicy` (configurable map stage → level; recommendation, needs business approval: `RESTRICTED → NEW_SALES_BLOCKED`, `SUSPENDED → PROVISIONING_BLOCKED`). End customers are only affected by an explicit per-customer WAVER action with approval `RESELLER_DEFAULT_CUSTOMER_ACTION`, never automatically.

### 3. customer-service
| Entity | Key fields |
|---|---|
| CustomerProfile | `id`, `org_id` (= customer org), `resellerOrgId` (current owner), `legalName`, `tradeName`, `taxIdType`, `taxId` (e.g., CNPJ), `segment`, `lifecycleStage`, `accessStatus`, `financialStatus` (projection), `resellerSupportContact` (projection), `version` |
| CustomerContact | `customerId`, `contactType` (`ADMIN`,`FINANCIAL`,`TECHNICAL`,`LEGAL`), name, email, phone |
| BillingProfile | `id`, `customerId`, `billingName`, `taxId`, `billingAddress`, `billingEmails[]`, `taxMetadata` (country-neutral key/values), `paymentMethodRef` (opaque gateway token reference, never card data) |
| CustomerOwnership | `id`, `customerOrgId`, `resellerOrgId`, `validFrom`, `validTo`, `transferId` (history; one open row) |
| OwnershipTransfer | `id`, `customerOrgId`, `fromResellerOrgId`, `toResellerOrgId`, `requestedBy`, `reason`, `effectiveDate`, `contractHandlingPolicy`, `approvalRequestId`, `status` |
| SuspensionCase | `id`, `customerOrgId`, `resellerOrgId`, `level` (`RESTRICTED`,`SUSPENDED`), `scope` (`FULL` or capability keys), `reasonCode` (`NON_PAYMENT`,`FRAUD`,`ABUSE`,`COMPLIANCE`,`CUSTOMER_REQUEST`,`RESELLER_DEFAULT`,`OTHER`), `reasonText`, `origin` (`AUTOMATIC`,`MANUAL`), `openedBy`, `openedAt`, `sourceRef` (dunningCaseId), `reactivationMode` (`AUTOMATIC`,`APPROVAL_REQUIRED`), `state`, `closedBy`, `closedAt`, `closureReason` (`RESOLVED`,`WRONGFUL`,`ESCALATED_TO_TERMINATION`) |

Registration: Partner `POST /v1/customers` → customer-service calls tenant-service `POST /v1/organizations` (`orgType = CUSTOMER`, relationship to the reseller org; `Idempotency-Key` propagated) → stores profile and the first `CustomerOwnership` row → `CustomerRegistered`.

**Three status fields, one derived display lifecycle.** customer-service owns `lifecycleStage` (commercial) and `accessStatus` (from its SuspensionCases); `financialStatus` is a projection of billing's `DunningStageChanged`. The Commercial §13.1 lifecycle shown in UIs is derived: `TERMINATED` if stage is TERMINATED; else `SUSPENDED`/`RESTRICTED` if accessStatus says so; else `PAST_DUE` if stage is ACTIVE and financialStatus ∈ {PAST_DUE, GRACE_PERIOD}; else the stage. A customer can be commercially ACTIVE and financially PAST_DUE at the same time.

| lifecycleStage from | To | Trigger | Event |
|---|---|---|---|
| — | PROSPECT | registration (`RESELLER_SALES`, `RESELLER_OWNER`) | `CustomerRegistered` |
| PROSPECT | CONTRACTING | first `QuoteSent` for the customer | `CustomerLifecycleChanged` |
| CONTRACTING | PROSPECT | all open quotes/contracts rejected/expired | `CustomerLifecycleChanged` |
| CONTRACTING | PROVISIONING | `ContractActivated` | `CustomerLifecycleChanged` |
| PROVISIONING | ACTIVE | `ProvisioningSucceeded` with all critical items | `CustomerActivated` |
| ACTIVE, PROVISIONING | TERMINATED | no in-force contract left after `ContractCancelled/Terminated/Expired` + `postContractPolicy`, or approved termination | `CustomerTerminated` |

SuspensionCase lifecycle (Commercial §7.1, §17 "suspensão equivocada"):
| From | To | Trigger / who | Event |
|---|---|---|---|
| — | PENDING_CONFIRMATION | `DunningStageChanged` to RESTRICTED/SUSPENDED when `DunningPolicy.requireManualConfirmation` | `SuspensionProposed` |
| —, PENDING_CONFIRMATION | ACTIVE | automatic (policy allows) or confirmed by `RESELLER_FINANCE`/`RESELLER_OWNER` (own portfolio only) or `WAVER_FINANCE`; manual cases need `reasonCode` + `reasonText` | `CustomerRestricted` / `CustomerSuspended` |
| ACTIVE | ACTIVE (level up) | dunning escalation or manual | `CustomerSuspended` |
| ACTIVE | REACTIVATION_PENDING | `DunningStageChanged` to REACTIVATING and `reactivationMode = APPROVAL_REQUIRED` | `ReactivationRequested` |
| ACTIVE, REACTIVATION_PENDING | CLOSED | payment regularized (`AUTOMATIC`), approval granted, or override (`SUSPENSION_OVERRIDE` approval) | `CustomerReactivated` |
| PENDING_CONFIRMATION | CANCELLED | `RESELLER_FINANCE` declines with reason | `SuspensionProposalDeclined` |
| ACTIVE | CLOSED (`ESCALATED_TO_TERMINATION`) | contract terminated (RFC-0013) | `CustomerTerminated` |

entitlement-service turns `CustomerRestricted/Suspended/Reactivated` into holds (RFC-0013 §6); after `CustomerReactivated`, provisioning-service runs a `REACTIVATION_VERIFY` job to validate channels and executions (Commercial §8.2). A reseller can only act on customers whose open `CustomerOwnership.resellerOrgId` equals the token's `org_id`. Wrongful suspension: override closes with `closureReason = WRONGFUL` and creates an impact report task (affected period, usage-metering counts of blocked runs/messages) for WAVER Support.

**Suspension and reactivation as Temporal workflows** (ADR-0023): each SuspensionCase runs as `SuspensionCaseWorkflow` in customer-service (namespace `waver-commercial`, task queue `customer-service.suspension-case`, `workflowId = suspension-case:<suspensionCaseId>`) from creation to `CLOSED`/`CANCELLED`. The states, triggers, roles and events in the table above are unchanged; the workflow is the durable coordinator:
- Inputs arrive as signals sent **only by customer-service itself**: confirmation, decline, reactivation and override commands from its API (`POST /v1/suspension-cases/{id}/confirmation`, `/reactivation`, `/override`) after the portfolio and role checks; `DunningStageChanged` (escalation, REACTIVATING, reversal) and `ContractTerminated` translated by customer-service's own consumers; `ApprovalGranted`/`ApprovalRejected`/`ApprovalExpired` for `SUSPENSION_OVERRIDE` and `APPROVAL_REQUIRED` reactivations, consumed by customer-service and forwarded to the case's workflow. billing-service and approval-service never signal customer-service workflows.
- Each transition (SuspensionCase row + `accessStatus` + outbox event) is one idempotent activity; entitlement holds, the `REACTIVATION_VERIFY` provisioning job and dunning closure still follow from the published events, never from cross-service workflow calls.
- No timeout is introduced for `PENDING_CONFIRMATION` or `REACTIVATION_PENDING` (the business rules define none); the workflow waits durably until a signal arrives. Manual cases (`POST /v1/customers/{id}/suspension-cases`) and automatic ones (from `DunningStageChanged`) start the same workflow; the deterministic workflow ID makes a redelivered event a no-op.

**Ownership transfer** (Commercial §17, §11.1): `REQUESTED → PENDING_APPROVAL → APPROVED → SCHEDULED → APPLYING → COMPLETED`, or `REJECTED`/`CANCELLED`; `APPLYING → FAILED` (manual review) when the tenant switch keeps failing after activity retries (aligned with RFC-0003 §6 in the v1.3 review). It runs as `OwnershipTransferWorkflow` in customer-service (task queue `customer-service.ownership-transfer`, `workflowId = ownership-transfer:<transferId>`): an activity creates the `CUSTOMER_OWNERSHIP_TRANSFER` approval request; the decision arrives as an approval event consumed by customer-service, which signals its own workflow; `SCHEDULED` is a durable Temporal timer at `effectiveDate`; the relationship update, the ownership rows and `CustomerOwnershipTransferred` are idempotent activities (the outbox row is written in the same transaction as the ownership rows). A cancellation through the customer-service API signals `cancel` while the workflow is before `COMPLETED`.
```mermaid
sequenceDiagram
  autonumber
  participant CB as control-bff (WAVER Commercial)
  participant CS as customer-service
  participant AP as approval-service
  participant TS as tenant-service
  participant K as Kafka
  CB->>CS: POST /v1/ownership-transfers (customerOrgId, toResellerOrgId, effectiveDate, contractHandlingPolicy)
  Note over CS: OwnershipTransferWorkflow (Temporal) starts, calls below are activities
  CS->>AP: POST /v1/approval-requests (CUSTOMER_OWNERSHIP_TRANSFER)
  AP-->>CS: ApprovalGranted (event, consumed by customer-service, signals its own workflow)
  CS->>CS: SCHEDULED (Temporal durable timer at effectiveDate)
  CS->>TS: PUT /v1/organizations/{customerOrgId}/reseller-relationship (toResellerOrgId; tenant-service API, RFC-0003)
  CS->>CS: close old CustomerOwnership, open new (+ outbox row, same tx)
  CS-->>K: CustomerOwnershipTransferred (Debezium outbox relay)
  K-->>K: contract-service applies contractHandlingPolicy; billing switches relationship; identity-access revokes old reseller grants; entitlement-service updates resellerOrgId
```
Open debts stay on the old billing relationship. Entitlements continue without interruption. `contractHandlingPolicy` (`NOVATE` to the new reseller vs `TERMINATE_AND_RECONTRACT`) is an open business decision.

### 4. catalog-service
| Entity | Key fields |
|---|---|
| Product | `id`, `productKey`, `name`, `itemType` (`PRODUCT`,`TOOL`,`CONNECTOR`,`SOLUTION_PACK`,`CHANNEL`,`CAPACITY`,`SERVICE` — Commercial §5.1), `status`, `technicalRef` (toolId / connectorId / channel type) |
| SKU | `id`, `skuCode`, `productId`, `unit` (workspace, connection, number, seat, run, token, hour…), `chargeType` (`RECURRING`,`USAGE`,`TIERED`,`ONE_TIME`), `billingPeriod`, `meterKey` (usage SKUs) |
| Bundle / SolutionPack | `id`, `components[]` (`skuId`, `quantity`, `optional`) |
| AddOn | `id`, `skuId`, `requiresSkuIds[]` |
| PriceBook | `id`, `name`, `currency`, `validFrom`, `validTo`, `scope` (`GLOBAL`,`TIER`,`RESELLER`), `scopeRef`, `status` (`DRAFT`,`PENDING_APPROVAL`,`ACTIVE`,`RETIRED`) |
| WholesalePrice | `priceBookId`, `skuId`, `chargeModel` (`FLAT`,`PER_UNIT`,`TIERED`,`VOLUME`), `amount` / `tiers[]` |
| RetailPrice | `priceBookId`, `skuId`, `pricingModel` (`WHOLESALE_PLUS_MARGIN`,`SUGGESTED_RETAIL`,`FIXED_RETAIL`), `suggestedAmount`, `fixedAmount`, `floorAmount`, `ceilingAmount` |
| PricingRule | `id`, `scope` (global/tier/reseller), `minMarginPct`, `maxMarginPct`, `validFrom/To` |
| DiscountPolicy | `id`, `scope`, `maxDiscountWithoutApprovalPct`, `approvalPolicyKey`, `promotion` window |

Pricing models (Commercial §10) map to RetailPrice.pricingModel (who sets the final price) × SKU.chargeType (how it is charged). Activation of a PriceBook or any change to WholesalePrice requires approval `BASE_PRICE_CHANGE`, emits `PriceBookActivated`; the old book stays readable (quotes and contracts snapshot prices, RFC-0013). Evaluation endpoints: `POST /v1/price-quotes` (resolve wholesale + retail for reseller/SKU/qty/date) and `POST /v1/discount-policy-evaluations` (returns `WITHIN_POLICY | APPROVAL_REQUIRED | FORBIDDEN` + policy key). No numeric limits are hard-coded; all come from these entities. Tax data is metadata only (CONVENTIONS).

### 5. marketplace-service
| Entity | Key fields |
|---|---|
| Publisher | `id`, `publisherType` (`PLATFORM`; `PARTNER` defined but disabled by `marketplace.thirdPartyPublishers.enabled = false`), `orgId`, `revenueShareRef` (unused in v1) |
| Listing | `id`, `listingKey`, `publisherId`, `productId`, `category`, `status`, `currentVersionId` |
| ListingVersion | `id`, `listingId`, `versionNo`, `skuIds[]`, `compatibility`, `dependencies[]` (listing refs), `requirements`, `entitlementTemplate` (capability keys, limits, `critical`, `suspensionBehavior`), `docsRefs`, `supportPolicy`, `slaRef`, `owner`, `retirementPolicy`, `status` (immutable once published) |
| AvailabilityRule | `id`, `listingId`, `mode` (`ALL_RESELLERS`,`TIERS`,`RESELLERS`), `include[]`, `exclude[]`, `validFrom`, `validTo` |
| Certification | `id`, `listingVersionId`, `checklist[]` (security review, connector certification per ADR-0016, docs, support readiness), `status` (`PENDING`,`PASSED`,`FAILED`), `certifiedBy` |

| From | To | Trigger / who | Guard | Event |
|---|---|---|---|---|
| — | DRAFT | `WAVER_PRODUCT_OPS` | — | `ListingDrafted` |
| DRAFT | REVIEW | `WAVER_PRODUCT_OPS` submits | version complete; referenced ToolVersion/Connector published | `ListingSubmitted` |
| REVIEW | DRAFT | reviewer / `ApprovalRejected` | — | `ListingReturned` |
| REVIEW | PUBLISHED | `ApprovalGranted` (`LISTING_PUBLICATION`) | Certification PASSED | `ListingPublished` |
| PUBLISHED | DEPRECATED | `WAVER_PRODUCT_OPS` with `retirementPolicy` and retirement date | — | `ListingDeprecated` |
| DEPRECATED | RETIRED | timer at retirement date (Temporal durable timer in marketplace-service, `ListingRetirementWorkflow`, re-planned by a signal from marketplace-service's own API when the date changes or the deprecation is undone; Q13), or `WAVER_PRODUCT_OPS` | — | `ListingRetired` |
| DEPRECATED | PUBLISHED | `WAVER_PRODUCT_OPS` (undo) with approval | — | `ListingPublished` |

New versions of a published listing go through the same REVIEW/certification on the version; the listing stays PUBLISHED. `AvailabilityChanged` is emitted on any rule change. Partner sees only listings where an AvailabilityRule matches its org and tier (`GET /v1/listings?availableTo=me`). Grandfathering on retirement: RFC-0013 §11.

### 6. support-service
| Entity | Key fields |
|---|---|
| SupportCase | `id`, `org_id` (customer or reseller org that opened it), `customerOrgId`, `resellerOrgId`, `openedBy`, `category`, `severity` (`S1`–`S4`), `level` (`L1`,`L2`,`L3`), `status`, `slaPolicyId`, `firstResponseDueAt`, `resolutionDueAt`, `relatedRefs[]` (provisioningJobId, contractId, agentRunId) |
| Escalation | `caseId`, `fromLevel`, `toLevel`, `reason`, `escalatedBy`, `at` |
| SlaPolicy | `id`, `scope` (tier/contract), targets per severity (values open) |

Lifecycle: `NEW → TRIAGED → IN_PROGRESS → (WAITING_CUSTOMER ↔ IN_PROGRESS) → ESCALATED → RESOLVED → CLOSED`, `RESOLVED → REOPENED → IN_PROGRESS`. L1 = reseller (`RESELLER_SUPPORT`), L2/L3 = WAVER (`WAVER_SUPPORT`). Customers open cases to their reseller from Workspace; resellers escalate to WAVER (`SupportCaseEscalated`).

Support access (impersonation): support-service requests approval `SUPPORT_ACCESS` (approver per policy, recommended Customer Admin, or Reseller Owner for L1) → identity-access-service creates a time-bound, scoped `SupportAccessGrant` (read-only by default) → tokens issued by Keycloak carry `support_grant` (added by the WAVER claims protocol mapper, which reads active grants from identity-access-service; ADR-0021) → every action is audited; grants auto-expire and are revoked when the case closes. A break-glass grant by `WAVER_SUPER_ADMIN` without prior approval is allowed only with mandatory post-hoc review (policy flag).

### 7. Roles, permissions and approvals
| Role key (Commercial §11) | Main permissions | Explicitly denied |
|---|---|---|
| `WAVER_SUPER_ADMIN` | all; break-glass | — (MFA, every action audited) |
| `WAVER_COMMERCIAL` | reseller.*, customer.oversight, catalog.price.*, contract.read, ownership.transfer.request | billing.payment.write |
| `WAVER_FINANCE` | billing.*, dunning.*, reseller.restriction.*, suspension.override.request | catalog.price.write |
| `WAVER_PRODUCT_OPS` | marketplace.*, catalog.product.*, availability.* | billing.* |
| `WAVER_SUPPORT` | support.l2l3.*, provisioning.retry, reconciliation.read, support.access.request | price/contract write |
| `RESELLER_OWNER` | portfolio-wide: customer.*, quote.*, contract.*, pricing within policy, billing.*, reseller.users.* | catalog.base.*, other portfolios |
| `RESELLER_SALES` | customer.write, quote.*, contract.draft/send/accept | secrets, billing.payment.write, suspension |
| `RESELLER_FINANCE` | billing.*, dunning.*, customer.suspension.*, reports.finance | price/contract write |
| `RESELLER_SUPPORT` | customer.read, support.l1.*, provisioning.read/retry | price/contract write |
| `CUSTOMER_ADMIN` | workspace.*, users.*, enabled capabilities, own usage, own support | marketplace, wholesale, margin |
| `CUSTOMER_OPERATOR` | operational use per domain/role | admin settings |

Permissions are evaluated in services (CONVENTIONS); roles are owned by identity-access-service and reach services as coarse role keys in the Keycloak-issued token (`roles` claim via the protocol mapper, ADR-0021). Keycloak never holds the fine-grained permissions.

Approval policies (approval-service, Commercial §11.1): `DISCOUNT_ABOVE_POLICY`, `MARGIN_BELOW_POLICY` (contract-service), `SUSPENSION_OVERRIDE` (customer-service/entitlement-service), `CUSTOMER_OWNERSHIP_TRANSFER` (customer-service), `LISTING_PUBLICATION` (marketplace-service), `BASE_PRICE_CHANGE` (catalog-service), `SUPPORT_ACCESS` (support-service), `TERMINATION` (contract/reseller), `PURGE` (retention jobs), plus `RESELLER_APPROVAL`, `RESELLER_SUSPENSION`, `RESELLER_DEFAULT_CUSTOMER_ACTION`. Approver roles, quorum and expiry are configuration; requester ≠ approver is enforced.

**Approvals and workflows.** approval-service remains the single owner of ApprovalRequest/ApprovalDecision. When the requesting service runs a Temporal workflow that waits for the decision (reseller onboarding, ownership transfer, suspension override/reactivation, provisioning in RFC-0013), the decision travels as an `ApprovalGranted`/`ApprovalRejected`/`ApprovalExpired` event to the requesting service, whose own event handler signals its own workflow (correlated by `approvalRequestId`). No service signals, queries or starts another service's workflows (ADR-0023, AR-003/AR-004).

### 8. Notifications (Commercial §15)
notification-service consumes the events below and resolves recipients by role in the relevant org.
| Situation | Source event | Recipients | Channels |
|---|---|---|---|
| Contract near expiry | `ContractRenewalDue` (contract-service Temporal timer, RFC-0013 §12; notice schedule configurable) | Reseller Owner/Sales | in-app, e-mail |
| Customer past due | `DunningStageChanged` (PAST_DUE) | Reseller Finance | in-app, e-mail, webhook |
| Grace period ending | `DunningNoticeDue` (GRACE_ENDING) | Reseller Finance + customer financial contact per policy | e-mail, in-app |
| Suspension executed | `CustomerSuspended`/`CustomerRestricted` | Reseller + authorized customer admins | in-app, e-mail |
| Reactivation | `CustomerReactivated` | Reseller + customer admins | in-app, e-mail |
| Provisioning failed | `ProvisioningFailed`, `ProvisioningManualReviewRequired` | Reseller Support + WAVER ops | in-app, alerting |
| Quota near limit | `QuotaThresholdReached` | Reseller + customer admin | in-app, e-mail |
| Product deprecated | `ListingDeprecated` (recipients from contract-service `GET /v1/contract-items/impact?listingId=`) | impacted resellers | in-app, e-mail |
| Critical incident | manual broadcast by WAVER ops | affected resellers/customers | status/notification policy |

### 9. KPIs and dashboards (Commercial §16)
No service reads another's database; dashboards are composed by control-bff/partner-bff from owner read models.
| KPI | Owner read model | Built from |
|---|---|---|
| MRR/ARR, NRR, churn, expansion, revenue per reseller | billing-service RevenueSchedule (RFC-0020) | contract versions, `InvoiceIssued` |
| Default, DSO, aging, recovery rate, suspended revenue | billing-service | invoices, ledger, `DunningStageChanged` |
| Active resellers, customers per reseller, activation rate, time-to-live | reseller-service / customer-service | lifecycle timestamps (`CustomerRegistered` → `CustomerActivated`) |
| Attach rate per Tool/Product/Connector | contract-service | in-force items by listing |
| Usage, AI COGS, margin per product/reseller | usage-metering + billing (RFC-0020) | `ModelCallCompleted`, usage aggregates |
| Provisioning success rate | provisioning-service | job outcomes |
| Support volume, SLA, incident rate | support-service | case events |
| Reseller score | reseller-service ResellerScore | the events above, weights by policy |

Reseller dashboards use the same read models filtered by `resellerOrgId`; WAVER COGS never appears in Partner.

### 10. Exception handling (Commercial §17)
| Situation | Mechanism |
|---|---|
| Reseller in default with WAVER | §2 ResellerRestriction via `ResellerDefaultPolicy`; customers untouched unless approved per customer. |
| Customer changes reseller | §3 OwnershipTransfer with approval and audit. |
| Tool retired | RFC-0013 §11 retirement policy and grandfathering. |
| Provisioning error | RFC-0013 §5 `deliveryStatus`; lifecycle stays PROVISIONING. |
| Duplicate payment | RFC-0020 credit/adjustment in the append-only ledger. |
| Wrongful suspension | §3 `SUSPENSION_OVERRIDE` + reactivation + audit + impact report. |
| Contract expired with active data | RFC-0013 `CONTRACT_EXPIRED` hold + retention window; no purge. |

## APIs / Events / Data Ownership
| Service | Main endpoints (`/v1`) |
|---|---|
| reseller | `POST/GET /resellers`, `POST /resellers/{id}/review`, `/approval-requests`, `/activation`, `/suspension`, `/reinstatement`, `/termination`; `GET/PUT /resellers/{id}/contacts`; `POST /resellers/{id}/agreements`; `GET/PUT /resellers/{id}/onboarding-checklist`; `POST/DELETE /resellers/{id}/restrictions`; `GET /reseller-tiers`; `GET /resellers/{id}/score` |
| customer | `POST/GET /customers`, `GET/PUT /customers/{id}`, `/contacts`, `/billing-profile`; `POST /customers/{id}/suspension-cases`, `POST /suspension-cases/{id}/confirmation`, `/reactivation`, `/override`; `POST /ownership-transfers`, `GET /ownership-transfers/{id}`; `GET /customers/{id}/reseller-contact` (workspace) |
| catalog | `GET/POST /products`, `/skus`, `/bundles`, `/add-ons`; `POST /price-books`, `POST /price-books/{id}/activation`; `GET /price-books/{id}/entries`; `POST /price-quotes`; `POST /discount-policy-evaluations`; `GET/POST /pricing-rules`, `/discount-policies` |
| marketplace | `POST/GET /listings`, `POST /listings/{id}/versions`, `/submission`, `/deprecation`, `/retirement`; `PUT /listings/{id}/availability-rules`; `GET /listings/{id}/availability?resellerOrgId=`; `POST /listing-versions/{id}/certifications` |
| support | `POST/GET /support-cases`, `POST /support-cases/{id}/escalations`, `/resolution`, `/reopening`; `POST /support-cases/{id}/access-requests` |

| Topic | Event types |
|---|---|
| `waver.reseller.reseller.v1` | `ResellerLeadRegistered`, `ResellerReviewStarted`, `ResellerApproved`, `ResellerRejected`, `ResellerActivated`, `ResellerSuspended`, `ResellerReinstated`, `ResellerTerminated`, `ResellerRestricted`, `ResellerRestrictionLifted`, `ResellerTierChanged` |
| `waver.customer.customer.v1` | `CustomerRegistered`, `CustomerLifecycleChanged`, `CustomerActivated`, `CustomerOwnershipTransferred`, `CustomerTerminated` |
| `waver.customer.suspension-case.v1` | `SuspensionProposed`, `SuspensionProposalDeclined`, `CustomerRestricted`, `CustomerSuspended`, `ReactivationRequested`, `CustomerReactivated` |
| `waver.catalog.product.v1` / `waver.catalog.price-book.v1` | `ProductPublished`, `ProductRetired` / `PriceBookActivated`, `PriceBookRetired` |
| `waver.marketplace.listing.v1` | `ListingDrafted`, `ListingSubmitted`, `ListingReturned`, `ListingPublished`, `ListingDeprecated`, `ListingRetired`, `AvailabilityChanged` |
| `waver.support.support-case.v1` | `SupportCaseOpened`, `SupportCaseEscalated`, `SupportCaseResolved`, `SupportCaseReopened` |

Customer events carry IDs and statuses only; contact PII is fetched by authorized consumers via API (STD-009). Every event type has an Avro schema in `contracts/events/<context>/<EventName>.v1.avsc` (contexts `reseller`, `customer`, `catalog`, `marketplace`, `support`), registered by CI in Apicurio Registry (`BACKWARD_TRANSITIVE`); CloudEvents attributes travel as `ce_*` Kafka headers and each service's `outbox_event` rows are published by Debezium's Outbox Event Router (ADR-0024). Mandatory columns and RLS per CONVENTIONS/ADR-0010; customer tables carry `reseller_org_id` for the portfolio policy. Workflow state (onboarding, ownership transfer, suspension case, listing retirement) lives in Temporal (namespace `waver-commercial`); the business rows above remain the source of truth and the only thing APIs and dashboards read.

## Security & Privacy
- Reseller isolation: every Partner query is constrained by `reseller_org_id = token.org_id` at RLS level; cross-portfolio reads are impossible from partner-bff.
- WAVER oversight uses the audited cross-tenant role; each oversight read of customer data produces an audit event.
- MFA mandatory for WAVER and Reseller admin roles: identity-access-service declares which roles require MFA/step-up and Keycloak enforces it at login (TOTP/WebAuthn, ACR levels; ADR-0021). Segregation of duties: sales cannot change payments; finance cannot change prices; support cannot change contracts.
- Service-to-service calls (reseller/customer → tenant-service and identity-access-service, contract/customer/support → approval-service, …) run over Istio ambient STRICT mTLS with `AuthorizationPolicy` generated from the declared dependency graph; undeclared calls are denied by the mesh (ADR-0026, see Q10). NetworkPolicies remain as a second layer.
- Temporal Web is restricted to WAVER operators (Keycloak SSO + Istio authorization); business actions on workflows (checklist items, transfer cancellation, suspension confirmation/override) are only possible through the owning service's API, so they are authorized and audited. Workflow inputs and signals carry IDs only, never contact PII.
- PII (contacts, tax IDs) minimised in events, encrypted at rest by the platform DB, exportable/deletable under LGPD after contractual retention.
- Customer-facing projections exclude wholesale, margin, commission and cost by schema, not by filter.

## Reliability & Failure Modes
| Failure | Behavior |
|---|---|
| tenant-service down at registration/approval | request fails `503`, idempotent retry by the client; no half-created profile (org created first, profile second with same key). |
| approval-service down | transition stays pending; the workflow activity that creates the approval request retries with backoff (or, outside workflows, the outbox retries request creation). |
| Missed `DunningStageChanged` | inbox dedup + DLQ; nightly consistency check of `financialStatus` projection against billing API. |
| Conflicting manual and automatic suspension | separate SuspensionCases, each with its own workflow; entitlement holds per case (RFC-0013) so closing one never lifts another. |
| Ownership transfer partially applied | `OwnershipTransferWorkflow` resumes from its history after any crash; every step is an idempotent activity; `COMPLETED` only after tenant relationship and ownership rows are consistent. |
| Temporal unavailable | Onboarding, transfers and suspension cases pause (no new steps, timers fire late, never lost); API commands that need to signal a workflow return `503 DEPENDENCY_UNAVAILABLE` and are retried by the client with the same `Idempotency-Key`; event consumers do not ack until the signal is delivered, so Kafka redelivers. Running customer workloads are unaffected. |
| Debezium / Kafka Connect stopped | events wait in the outbox; alert on replication slot lag (ADR-0024). |

Commercial services are control plane: their outage blocks sales and administration but never running customer workloads (AR-013).

## Observability
Metrics per service: transitions per state machine, approval wait time, open SuspensionCases by origin/level, ownership transfers by status, listings by status, support SLA breaches; Temporal SDK metrics per task queue (workflow/activity failures, schedule-to-start latency). Operators can inspect each onboarding, transfer or suspension workflow in Temporal Web by its `workflowId`, shown next to the business record in Control. Business events are traceable via `correlationid` across Partner request → events → notifications. Dashboards for WAVER ops: onboarding pipeline, suspensions executed vs proposed, SLA breaches.

## Testing Strategy
Exhaustive transition tests for every state machine above; Temporal workflow tests (Java SDK test framework, time skipping) for `ResellerOnboardingWorkflow`, `OwnershipTransferWorkflow`, `SuspensionCaseWorkflow` and `ListingRetirementWorkflow`, covering every signal (approval outcomes, confirmations, overrides, cancellation) and timer, plus replay tests in CI for each workflow change (ADR-0023); tenancy kit (reseller A vs reseller B, customer vs reseller data); BFF audience tests (workspace-bff never returns wholesale/margin/cost/marketplace fields — schema assertions); approval-path tests (requester ≠ approver); event contract tests against the Avro schemas (CI compatibility check against Apicurio Registry); Playwright E2E for the critical journeys of Commercial §9.

## Deployment / Rollout / Rollback
Phase 1: tenant/identity + reseller-service + customer-service (registration, ownership). Phase 2: catalog + marketplace (WAVER-only publisher). Phase 3: contract/entitlement/provisioning (RFC-0013). Phase 4: billing/dunning/suspension (RFC-0020) with automatic suspension disabled (`dunning.autoSuspend = false`) until business approves policies. Phase 5: support-service and support grants. Each service deploys independently; feature flags guard automatic actions; rollback by redeploying the previous image (forward-only migrations). Workflow changes ship with Temporal worker versioning (build IDs), so in-flight onboardings, transfers and suspension cases finish on the code that started them. Prerequisites per phase: Temporal namespace `waver-commercial`, Avro schemas registered by CI, Debezium outbox connectors for each new database, Keycloak realm/client configuration as code for the roles above.

## Alternatives Considered
- Single "commercial-service": faster start, but violates the map and creates a distributed monolith around the most change-prone rules.
- Customer lifecycle as one status field: rejected (Commercial §13 consistency rule).
- Role-filtered single BFF: rejected by ADR-0009.
- Onboarding, ownership transfer and suspension cases as hand-written PostgreSQL state machines with timer polling (v1.2, ADR-0018): no extra component, more custom timer/retry code; superseded by Temporal workflows in the owning services (ADR-0023).
- Letting approval-service signal the requesting service's workflow directly: one hop less, but couples approval-service to every requester's workflow API and violates ownership (ADR-0023); approvals stay events consumed by the requester.

## Risks
- Many business decisions open; services may ship with conservative defaults that resellers find restrictive.
- Ownership transfer touches tenant, contract, billing and identity; needs strong runbooks.
- KPI composition in BFFs may become slow; short-TTL shared caches in Redis (`waver:cache:<bff>:…`, Caffeine as L1, ADR-0020) mitigate it, but a reporting read model may still be needed.
- Temporal is a new tier-1 dependency for commercial flows; operators must learn to correlate business records with workflow histories.

## Open Questions
| # | Question | Recommended default (recommendation, needs business approval) |
|---|---|---|
| Q1 | Commercial ownership of the customer (reseller or WAVER) and transfer rules (Commercial §20) | Reseller owns the relationship; transfer initiated by WAVER Commercial with approval; `contractHandlingPolicy = NOVATE`. |
| Q2 | Pricing autonomy: margin bands and discount limits per tier | PricingRule/DiscountPolicy per tier; no values defaulted in code. |
| Q3 | Reseller default policy and when customers may be affected | Stage map as in §2; customer impact only via per-customer approval. |
| Q4 | Who approves support access; SLA targets per severity | Customer Admin approves L2/L3 access; SLA values per tier TBD. |
| Q5 | When third-party publishers and revenue share start | Disabled in v1; schema ready. |
| Q6 | Branding customisation for resellers (Commercial §20) | Out of scope of this RFC; no reseller branding in v1. |
| Q7 | Reseller groups for AvailabilityRule (Commercial §5.2 "grupos específicos") have no owner in MICROSERVICES_MAP | Use tiers + explicit lists in v1; add `ResellerGroup` to reseller-service if needed. |
| Q8 | `CustomerOwnership` (customer-service) vs `OrganizationRelationship` (tenant-service) overlap | tenant-service = technical access link; customer-service = commercial history and transfer process; customer-service is the only writer of the relationship. |
| Q9 | Commercial §12 places PricingRule/CatalogAssignment in Resellers; the map puts PricingRule in catalog and availability in marketplace | Follow the map (authoritative); update the Commercial doc. |
| Q10 | support-service → approval-service and notification recipient lookup (contract-service) are not listed as sync dependencies in the map | Add to the map. Since ADR-0026 the declared graph also generates the Istio `AuthorizationPolicy`, so missing entries block the call at runtime. |
| Q11 | No reporting/analytics service exists for cross-domain KPIs | BFF composition in v1; propose a reporting read-model service by ADR when needed. |
| Q12 | New event types beyond the map (e.g., `ResellerRestricted`, `CustomerLifecycleChanged`, `ContractRenewalDue`) | Add to the event catalog (`07_API_EVENTS/EVENT_CATALOG.md`, registry of record per CONVENTIONS), with their Avro schemas under `contracts/events/` (ADR-0024). |
| Q13 | ADR-0023's process table does not list marketplace-service, which needs a durable timer for the listing retirement date (`ListingRetirementWorkflow`, §5) | Add "Listing retirement — marketplace-service — `waver-commercial`" to the ADR-0023 table. |

## Decision
PROPOSED. Accept the service responsibilities, state machines, three-status rule, BFF audience matrix and approval policy keys as the v1 baseline, with reseller onboarding, ownership transfer and suspension/reactivation implemented as Temporal workflows in their owning services and approvals delivered to them as events from approval-service; Open Questions Q1–Q6 require business decisions and Q7–Q13 architecture/map updates before the corresponding phases start.
