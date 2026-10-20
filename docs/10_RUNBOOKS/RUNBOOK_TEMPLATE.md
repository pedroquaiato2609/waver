# Runbook Template
Service: TBD
Owner: TBD
SLO: TBD

<!--
Guidance: copy this file to 10_RUNBOOKS/RUNBOOK_<TOPIC>.md (cross-cutting) or 10_RUNBOOKS/<service>/RUNBOOK_<TOPIC>.md (STD-015).
Header: Service = service name(s) from MICROSERVICES_MAP; Owner = team from CODEOWNERS; SLO = the SLO this runbook protects (e.g., "p99 consumer lag < 60 s").
Add "Alerts:", "Tier:" (RFC-0014 §11) and "Last reviewed: YYYY-MM-DD" lines. Write in English, imperative voice, commands copy-pasteable,
provider-neutral (kubectl, Kafka CLI, psql, platform admin APIs). Never paste secrets or customer personal data into a runbook or incident channel.
-->

## Symptoms
What the on-call engineer sees: alert names and thresholds, dashboard patterns, error codes (`code` in problem+json), typical customer/reseller reports. One bullet per observable signal.

## Impact
Who is affected and how badly: planes, tenants (all vs some orgs), user journeys, data at risk, SLO/error-budget burn. State the severity mapping (e.g., "all tenants + data plane = SEV1").

## Dashboards / Alerts
Links (or dashboard names, since the observability backend is open) and the alert rules that page for this runbook, with thresholds. Every listed alert must link back to this runbook.

## Immediate mitigation
Safe, reversible actions that reduce impact within 15 minutes, in order: e.g., scale out, pause a consumer, flip a platform kill switch (RFC-0014 §6), roll back the last deployment. State what each action costs and how to undo it.

## Diagnosis
Step-by-step questions with commands and expected outputs to find the cause: recent deployments (version label), dependency health, logs filtered by `correlationId`/`orgId`, traces, database and Kafka checks. Branch to the matching Recovery path.

## Recovery
Procedures to return to normal for each cause found in Diagnosis. Use platform/admin APIs with `Idempotency-Key` and audit; never edit production databases by hand (AR-009, AR-010). Include how to confirm recovery (metric back under threshold for N minutes).

## Rollback
How to revert the change that caused the incident: pipeline `rollback` job (previous digest), config/flag revert, event replay. Remember schemas are never rolled back (RFC-0014 §5, §7).

## Data integrity checks
Queries or reports that prove no data was lost, duplicated or leaked across tenants: outbox backlog, DLQ counts, inbox duplicates, reconciliation jobs, audit hash-chain verification. Read-only, run with read-only roles.

## Escalation
Who to call and when: service owner, platform/SRE, Security (any suspected cross-tenant exposure or secret leak = immediate), WAVER Architecture, reseller communication owner. Include time thresholds (e.g., "not mitigated in 30 min → escalate").

## Post-incident actions
Mandatory follow-ups: incident record, blameless review within 5 working days for SEV1/SEV2, runbook update, tests/alerts that would have caught it, tickets with owners and dates, customer/reseller communication if SLAs were affected.
