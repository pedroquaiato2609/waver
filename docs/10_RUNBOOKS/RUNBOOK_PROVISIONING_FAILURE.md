# Runbook - Provisioning Failure
Service: provisioning-service (dependencies: tenant-service, identity-access-service, entitlement-service, Temporal; upstream: contract-service)
Owner: commercial platform team (CODEOWNERS of `services/provisioning-service/`); platform team for the Temporal cluster
SLO: 99% of ProvisioningJobs reach `SUCCEEDED` within 5 min of `ContractActivated`; 0 jobs in `FAILED` or `WAITING_MANUAL_REVIEW` older than 1 h without an owner
Alerts: `ProvisioningJobFailed`, `ProvisioningJobStuck`, `ProvisioningSuccessRateLow`, `ProvisioningManualReviewPending`, `TemporalTaskQueueNoPollers` (task queue `provisioning-service.provisioning`)
Tier: T2 (RFC-0014 §11); Temporal itself is T0
Last reviewed: 2026-10-19
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal for the provisioning saga, Debezium outbox relay, Keycloak behind identity-access-service); investigation via Temporal Web and the Temporal CLI, manual review through workflow signals sent by the provisioning-service admin API.

> Worked example of RUNBOOK_TEMPLATE. Job states, step names, workflow/signal names and admin endpoints are illustrative until fixed by RFC-0017/RFC-0013. The provisioning saga is a Temporal workflow owned by provisioning-service (ADR-0023): namespace `waver-commercial`, task queue `provisioning-service.provisioning`, workflow type `ProvisioningWorkflow`, workflow ID `ProvisioningJob:<jobId>`, search attribute `OrgId`. Every activity is idempotent (AR-006) and the `ProvisioningJob` row in provisioning-service's database mirrors the workflow state for APIs and the UI. Commands use the `temporal` CLI (the older `tctl` has equivalent `workflow describe/show/signal` commands); `$TEMPORAL_ADDRESS` and the operator identity (Keycloak SSO for Temporal Web) come from the on-call access procedure.

Illustrative process: `ContractActivated` → entitlement-service grants entitlements (`EntitlementGranted`) → provisioning-service creates `ProvisioningJob` and starts `ProvisioningWorkflow` → activities `ENSURE_WORKSPACE` (tenant-service) → `INVITE_CUSTOMER_ADMIN` (identity-access-service) → `CONFIRM_ENTITLEMENTS` (entitlement-service) → `ProvisioningSucceeded` or `ProvisioningFailed` (written to the outbox by the final activity). States: `PENDING`, `RUNNING`, `WAITING_RETRY` (activity retrying with backoff), `WAITING_MANUAL_REVIEW` (non-retryable error; the workflow waits for a `manualReviewDecision` signal), `SUCCEEDED`, `FAILED`.

## Symptoms
- `ProvisioningJobFailed`: a job entered `FAILED` (manual review decided to abort, or the workflow failed).
- `ProvisioningManualReviewPending`: a job waits in `WAITING_MANUAL_REVIEW` for more than 15 min.
- `ProvisioningJobStuck`: job in `RUNNING`/`WAITING_RETRY` for > 10 min without activity progress, or no job created 5 min after `ContractActivated`.
- `TemporalTaskQueueNoPollers`: no worker polls `provisioning-service.provisioning` (workers down or wrong namespace/build ID).
- `ProvisioningSuccessRateLow`: success rate < 95% over 30 min.
- Reports: reseller sees contract "Active" in WAVER Partner but the customer Workspace is missing, the admin invitation never arrived, or contracted capabilities do not appear.

## Impact
- New customers or upgrades are not usable; existing workloads are unaffected (provisioning is control plane; AR-013).
- Revenue and reseller trust impact if prolonged; billing starts only on provisioning success (D-29), so a stuck job delays invoicing too (check with billing owners).
- Single job failing = one customer; many failing = dependency, Temporal or code regression (SEV2). Temporal cluster unavailable affects every workflow-owning service (escalate as a platform incident).

## Dashboards / Alerts
- Jobs by state and step; job age histogram; failures by error `code`.
- Temporal: workflow executions of type `ProvisioningWorkflow` by status, activity failures and retries, schedule-to-start latency and pollers of the task queue, workflow task failures (non-determinism).
- Golden signals of provisioning-service, tenant-service, identity-access-service, entitlement-service (by version label).
- Consumer lag of provisioning-service and entitlement-service groups (RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ).
- Debezium connector status and replication-slot lag of contract-service and entitlement-service databases (RUNBOOK_DEBEZIUM_REPLICATION_SLOT).

## Immediate mitigation
1. If failures started after a deployment of provisioning-service or a dependency: run the pipeline `rollback` job for that service (RFC-0014 §7). If workflow tasks fail with a non-determinism error after a provisioning-service deployment, rolling back restores the previous build ID's workers and the stuck executions resume.
2. If a dependency is down: do nothing destructive; activities retry with backoff (`WAITING_RETRY`) and resume automatically when it recovers. Inform resellers through commercial operations if delay exceeds 30 min.
3. If the task queue has no pollers: check provisioning-service pods (they host the workers) before anything else; workflows wait durably and resume when workers return.
4. Never create workspaces, users or entitlements by hand in other services' consoles or databases (AR-002, AR-010), and never terminate or reset a provisioning workflow from the Temporal CLI as a first response; recovery goes through the job.

## Diagnosis
1. Find the job (control-bff operations screen, or the admin API on the cluster network):
   ```bash
   # illustrative, read-only
   GET /v1/provisioning-jobs?contractId=<contractId>
   GET /v1/provisioning-jobs/<jobId>        # state, current step, attempts, lastError.code, correlationId, workflowId
   ```
2. Inspect the workflow (read-only) in Temporal Web (namespace `waver-commercial`, search `WorkflowType="ProvisioningWorkflow" AND OrgId="<orgId>"`) or with the CLI:
   ```bash
   temporal workflow list --namespace waver-commercial \
     --query 'WorkflowType="ProvisioningWorkflow" AND OrgId="<orgId>"'
   temporal workflow describe --namespace waver-commercial --workflow-id ProvisioningJob:<jobId>
   temporal workflow show --namespace waver-commercial --workflow-id ProvisioningJob:<jobId>   # event history
   temporal task-queue describe --namespace waver-commercial --task-queue provisioning-service.provisioning
   ```
   - `describe` → pending activities with attempt count, last failure and next retry time; pending signal waits (manual review).
   - `show` → which activity completed last; `WorkflowTaskFailed` events with a non-determinism cause point to a code change (go to Mitigation 1).
   - `task-queue describe` → no pollers = workers down; pollers only on an old build ID = versioning mismatch after a deployment.
   Workflow inputs and activity results may contain customer data: do not copy them into the incident channel.
3. No job exists:
   - Was `ContractActivated` published? Check the contract-service Debezium connector and replication-slot lag, then the topic `waver.contract.contract.v1` (headers only, or `tooling/event-inspect`).
   - Did entitlement-service grant entitlements? Check its consumer lag/DLQ, then `waver.entitlement.entitlement.v1` for `EntitlementGranted` with the same `ce_orgid`.
   - Is provisioning-service consuming? Lag/DLQ of its group → RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ.
   - Job row exists but no workflow: the start activity failed (Temporal frontend unreachable from provisioning-service; check mesh denials and the Temporal frontend health).
4. Job failing or stuck at a step: follow `correlationId` in logs and traces to the failing call.
   | Step | Typical causes |
   |---|---|
   | `ENSURE_WORKSPACE` | tenant-service unavailable; validation error (org not in expected status); duplicate name conflict |
   | `INVITE_CUSTOMER_ADMIN` | identity-access-service unavailable; Keycloak Admin API unavailable behind it; invalid admin e-mail in contract data; notification delivery failure (does not fail the job, check notification-service) |
   | `CONFIRM_ENTITLEMENTS` | entitlement not yet granted (lag), entitlement suspended/revoked meanwhile, mismatch between contract items and entitlements |
5. Non-retryable data errors (e.g., invalid admin e-mail) put the job in `WAITING_MANUAL_REVIEW`; they need a correction at the source (contract or customer data) by the reseller or WAVER operations, not a code fix.

## Recovery
- Transient failure resolved: nothing to do; the pending activity retries on its schedule. To retry immediately, use the admin API, which signals the workflow (`retryNow`); the activity is idempotent and will not duplicate workspaces or invitations.
  ```bash
  # illustrative; requires WAVER operations role, audited (AR-009)
  POST /v1/provisioning-jobs/<jobId>/retry
  Idempotency-Key: <new UUID>
  ```
- Manual review (`WAITING_MANUAL_REVIEW`): correct the source data through its owning service's API (e.g., customer admin contact in customer-service), then send the decision through the admin API; provisioning-service authorizes it, audits it and signals the workflow (`manualReviewDecision` with `RETRY_STEP` or `ABORT` and a mandatory reason):
  ```bash
  POST /v1/provisioning-jobs/<jobId>/manual-review
  Idempotency-Key: <new UUID>
  { "decision": "RETRY_STEP", "reason": "Admin e-mail corrected in customer-service (ticket WAV-1234)" }
  ```
  Sending the signal directly with `temporal workflow signal` bypasses authorization and audit (ADR-0023: other parties never signal another service's workflows directly); it is break-glass only, with incident-commander approval, recorded in the incident and reconciled in audit-service afterwards.
- Code bug: fix forward, deploy, then retry affected jobs in batches of ≤ 50 through the admin API, checking success between batches. For a non-deterministic change, ship a `Workflow.getVersion` patch (or keep the old build ID) and confirm the replay tests pass (STD-012) before redeploying.
- Missing upstream event: fix the upstream (Debezium connector or consumer), then let the event flow; replay from DLQ if parked. Do not create jobs or start workflows manually.
- Confirm: job `SUCCEEDED`, workflow `Completed` in Temporal, `ProvisioningSucceeded` published, customer Workspace visible in WAVER Partner, admin invitation recorded in identity-access-service.

## Rollback
- Service rollback via the pipeline (previous digest). Running workflows continue on the build ID that started them when workers for it are available; job rows stay in place, and the previous version must understand any new state values (expand/contract, RFC-0014 §5).
- Provisioning is never "rolled back" by deleting a Workspace or entitlements, or by resetting/terminating workflows. If a contract was activated by mistake, the correction is a commercial action (cancellation/amendment in contract-service) that flows through events; data is retained (AR-012).

## Data integrity checks
- Exactly one Workspace per contracted customer scope: query tenant-service API by `orgId` (no duplicates created by activity retries).
- Exactly one workflow execution per job: `temporal workflow list --query 'WorkflowId="ProvisioningJob:<jobId>"'` shows a single run (plus continue-as-new runs only if the workflow uses them).
- Entitlements of the organization match the active contract items (entitlement-service API vs contract-service API).
- The admin user has exactly one pending invitation/membership.
- Audit events exist for each retry, each manual-review decision and each manual data correction (audit-service query by `correlationId`).
- No job of another organization was touched: retries and signals are per job ID; verify `orgId` of each retried job.

## Escalation
- More than 10 jobs failed in 30 min, or any job failed or waited for review > 1 h with a reseller waiting → service owner and incident commander (SEV2).
- Temporal cluster unhealthy (frontend errors, history service down, persistence errors) → platform team (T0 component; affects every workflow-owning service).
- Dependency outage → owning team of tenant-service, identity-access-service (T0; Keycloak behind it) or entitlement-service.
- Billing started for unprovisioned customers → billing-service owner and commercial operations.
- Any sign of a Workspace or user created under the wrong organization → Security immediately (AR-008).

## Post-incident actions
- Incident record; blameless review for SEV1/SEV2 within 5 working days.
- Add a workflow test for the failure path (Temporal test framework), add the incident's history to the replay test set if it exposed a determinism issue (ADR-0023), and an integration test for the dependency error.
- Classify the error codes that should be non-retryable vs retryable; update the activity retry policy (AR-014 rule 9).
- If source data was invalid, add validation at contract/customer capture time.
- Update this runbook and the reseller-facing status message templates.
