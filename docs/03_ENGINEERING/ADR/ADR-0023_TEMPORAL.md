# ADR-0023 - Durable Execution: Temporal
Status: PROPOSED
Date: 2026-10-19
Supersedes: ADR-0018

## Context
ADR-0018 modeled long-running processes as hand-written PostgreSQL state machines with timer polling. The product owner decided (2026-10-19) to adopt Temporal (MIT license), which provides durable workflows, timers, retries and visibility out of the box.

## Decision
**Temporal** (self-hosted cluster per environment, persistence on its own PostgreSQL database, Elasticsearch-free visibility on PostgreSQL) runs long-running and multi-step processes. Services use the **Temporal Java SDK**; each owning service runs its own workers.

| Process | Owner service (workflow + workers) | Temporal namespace |
|---|---|---|
| Provisioning saga (ProvisioningJob) | provisioning-service | `waver-commercial` |
| Dunning cases | billing-service | `waver-commercial` |
| Billing runs and month-end close | billing-service | `waver-commercial` |
| Customer ownership transfer, suspension/reactivation flows | customer-service | `waver-commercial` |
| Reseller onboarding checklist | reseller-service | `waver-commercial` |
| Contract effective dates, renewals (90/60/30-day reminders), quote expiry | contract-service | `waver-commercial` |
| Entitlement validity windows (scheduled start/end, policy windows) | entitlement-service | `waver-commercial` |
| Listing deprecation → retirement timer | marketplace-service | `waver-commercial` |
| Contract ↔ entitlement ↔ provisioning reconciliation (Temporal Schedules) | provisioning-service | `waver-commercial` |
| Agent runs that wait (user reply timeouts, Flow `Delay` nodes, approvals, handoff) | agent-runtime | `waver-agent` |
| Tool executions awaiting approval | tool-runtime-service | `waver-capability` |
| Customer workflows (WorkflowVersion runs) | workflow-service | `waver-automation` |
| Tenant purge job | tenant-service | `waver-platform` |
| Approval request expiry and reminders | approval-service | `waver-platform` |
| Outbound customer webhook delivery and retries | notification-service | `waver-platform` |
| Support access grant expiry | identity-access-service | `waver-platform` |
| Handoff SLA timers and escalation | handoff-service | `waver-agent` |
| Knowledge ingestion pipelines | knowledge-service | `waver-capability` |
| Agent deployment release (evaluation gates, approvals) | agent-builder-service | `waver-agent` |
| Connector polling triggers, health checks, OAuth token refresh and expiry | connector-service | `waver-capability` |

Rules:
- **Ownership is unchanged**: a workflow belongs to the service that owns the process; other services interact through that service's API or events, never by signalling another service's workflows directly (AR-003/AR-004).
- Workflow code is deterministic; side effects only in activities; every activity is idempotent (AR-006) and uses the outbox for events.
- **Not on the hot path**: an ordinary conversational turn does not start a workflow; agent-runtime uses Temporal only when a run must wait (`WAITING_USER` beyond a short window, `WAITING_APPROVAL`, `WAITING_HANDOFF`, Flow `Delay`).
- Versioning: Temporal worker versioning (build IDs) aligned with service releases; workflow changes follow AR-015.
- Task queues are named `<service>.<workflow>`; namespaces per plane isolate retention and permissions.

## Alternatives
- Keep ADR-0018: no new system, more custom code.
- Camunda 8: BPMN modeling is attractive for business processes, but heavier stack (Zeebe, Elasticsearch) and licensing constraints for production.

## Consequences
- Less custom state-machine code; built-in retries, timers and visibility UI (Temporal Web) for operations.
- Temporal is tier-1 (commercial) / tier-0 for data-plane waits; HA deployment, database backups, upgrade runbook.
- Temporal Web access restricted to WAVER operators (Istio authorization + Keycloak SSO).

## Compliance / Verification
- Workflow replay tests in CI for every workflow change (Temporal test framework).
- ArchUnit rule: workflow classes do not call infrastructure directly (only activities).

## Supersedes / Superseded by
Supersedes ADR-0018.
