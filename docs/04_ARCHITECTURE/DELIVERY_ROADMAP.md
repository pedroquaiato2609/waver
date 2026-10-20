# Delivery Roadmap - Vertical Slices

Status: PROPOSED
Date: 2026-10-19

The v1.2 service map has 33 backend services plus 3 frontend images. Building them all at once would produce a distributed monolith with nothing usable for months. Services are introduced **by vertical slice**. Every phase ends with something a real user can do in production-like conditions, and every new service is born with the full chassis (tenancy, outbox, observability, tests). Phases depend on the business decisions in `00_START_HERE/OPEN_DECISIONS.md`.

## Phase 0 - Foundations (platform chassis)
Goal: an empty but production-grade platform where a WAVER admin can log in.
- Monorepo, Maven BOM, `waver-platform-starter`, `waver-platform-test` (ADR-0007, ADR-0015).
- CI pipeline with all gates (STD-012), Kubernetes environments dev/staging (RFC-0014, RFC-0015).
- Shared infrastructure (v1.3), as code in `deploy/platform/`: Istio ambient, Vault, Keycloak (realm `waver`, WAVER theme), Redis, Kafka + Kafka Connect/Debezium + Apicurio Registry, Temporal. Qdrant can wait until Phase 2 (first consumer: knowledge-service).
- Services: `edge-gateway`, `control-bff`, `identity-access-service`, `tenant-service`, `audit-service`.
- Frontend: Nx workspace, NgRx, Angular Material themed with tokens v1.2 behind the `wv-*` wrapper libraries, `control` app shell (ADR-0027).
- Exit criteria: login through Keycloak with MFA; create Organization of type RESELLER (Keycloak organization created by identity-access); audit trail visible; tenant-isolation test kit running in CI; mTLS STRICT and AuthorizationPolicies enforced; secrets delivered only by Vault; one event flowing outbox → Debezium → Kafka with a registered Avro schema.
- Decisions needed: D-23, D-24, D-25, D-26 to D-28.

## Phase 1 - Commercial core (WAVER Control + WAVER Partner)
Goal: WAVER onboards a reseller; the reseller registers a customer, closes a contract and the customer's Workspace is provisioned.
- Services: `reseller-service`, `customer-service`, `catalog-service`, `marketplace-service`, `contract-service`, `entitlement-service`, `provisioning-service`, `approval-service`, `notification-service` (e-mail only), `partner-bff`, `workspace-bff` (shell only).
- `billing-service` minimal: invoices and payment status registered manually; dunning states set manually (RFC-0020).
- Frontend: Control (resellers, catalog, marketplace, contracts), Partner (customers, quotes, contracts, entitlements), Workspace shell (login, users).
- Exit criteria: full traceable chain Listing → Quote → Contract → Entitlement → Provisioning → Workspace; manual suspension/reactivation with audit.
- Decisions needed: D-01 to D-08, D-12, D-13, D-22.

## Phase 2 - Agent MVP (Objective-driven, Web Chat)
Goal: a customer builds, tests and publishes an Objective-driven agent that talks on Web Chat and uses one integration.
- Services: `agent-builder-service`, `agent-runtime` (objective-engine module first), `model-gateway-service`, `knowledge-service`, `tool-registry-service`, `tool-runtime-service`, `connector-service` (declarative connectors + first 1–2 certified connectors), `channel-service` (Web Chat + API), `usage-metering-service`.
- Frontend: Workspace Builder (creation wizard, Objective Builder, Playground), chat inbox (read-only).
- Exit criteria: AgentVersion published to production environment; entitlement gating works in Builder and Runtime; usage and AI cost per tenant visible in Control.
- Decisions needed: D-09, D-11, D-14, D-16 to D-18, D-20.

## Phase 3 - Agent platform completeness
- Flow-driven agents (flow-engine module, Flow Builder canvas).
- `evaluation-service` (test suites, release gates), `handoff-service`, `memory-service` (long-term memory with policies).
- WhatsApp channel; notification-service over WhatsApp.
- Coded connectors via `waver-connector-sdk`; Custom API connector for customers.

## Phase 4 - Automation and financial automation
- `trigger-service`, `workflow-service`, `scheduler-service`.
- Billing automation: usage rating, automatic invoices, automated dunning driving suspension, payment-gateway and fiscal adapters (D-08, D-03).
- `support-service` with L1/L2/L3 escalation and audited support access.

## Phase 5 - Enterprise
- SSO federation per organization, dedicated tenant databases for data-plane services (ADR-0010), BYOK (D-21), third-party publishers and revenue share (D-10), voice channel, multi-region (D-23).

## Rules for every phase
1. No service is created before the phase that needs it.
2. Each new service starts from the service template (chassis + ArchUnit + tenancy test kit + Helm chart + runbook).
3. Event contracts and OpenAPI contracts are merged before implementation starts (contract-first, AR-004/AR-015).
4. A phase is "done" only when its exit criteria pass in staging with observability dashboards and runbooks in place.
