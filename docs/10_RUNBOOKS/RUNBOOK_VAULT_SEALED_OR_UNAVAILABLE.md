# Runbook - Vault Sealed or Unavailable
Service: HashiCorp Vault and the Vault Secrets Operator (ADR-0022); affects every WAVER service and shared component that obtains credentials or uses Transit
Owner: platform team (Vault cluster, namespace `waver-infra-vault`); Security (policies, key holders); connector-service team for tenant-credential impact
SLO: Vault available (unsealed, leader elected) 99.95%; secret and Transit request p99 < 100 ms; 0 failed lease renewals in steady state
Alerts: `VaultSealed`, `VaultUnavailable`, `VaultLeaderLost`, `VaultRaftPeerUnhealthy`, `VaultAuditDeviceFailing`, `VaultLeaseRenewalFailures`, `VaultSecretsOperatorSyncErrors`, `VaultRequestLatencyHigh`
Tier: T0 (RFC-0014 §11)
Last reviewed: 2026-10-19
Revision 1.3 (2026-10-19): new runbook for HashiCorp Vault adopted by ADR-0022 (Kubernetes auth, Vault Secrets Operator, dynamic PostgreSQL credentials, Transit, audit device, integrated Raft storage with auto-unseal).

> Commands use kubectl and the Vault CLI inside the Vault pods (`kubectl -n waver-infra-vault exec vault-0 -- vault ...`) with an operator token obtained through the break-glass procedure. Never paste tokens, unseal or recovery key shares, or secret values into the incident channel, tickets or this runbook. With auto-unseal (cloud KMS, once chosen) nodes unseal themselves; recovery keys are used only for operations such as generating a root token. Until a KMS exists (e.g., in `dev`), Shamir unseal by the named key holders applies.

## Symptoms
- `VaultSealed`: one or more nodes report `Sealed: true`.
- `VaultUnavailable` / `VaultLeaderLost`: health endpoint failing, no active node, Raft without quorum.
- `VaultAuditDeviceFailing`: the audit device cannot write. Vault refuses requests when no enabled audit device can record them, so this looks like a full outage.
- Services: new pods crash at start (cannot authenticate or fetch database credentials); `VaultLeaseRenewalFailures` rising; `VaultSecretsOperatorSyncErrors`.
- connector-service, model-gateway-service and channel-service: tool executions, BYOK model calls and channel deliveries that need tenant credentials fail with `DEPENDENCY_UNAVAILABLE`; outbound webhook signing fails.

## Impact
- **Running pods keep working at first**: they hold database connections and synced files. New pods (deployments, autoscaling, node drains, crash restarts) cannot start.
- **Dynamic database credentials expire**: credentials are issued with a 1 h TTL and renewed by Vault; while Vault is down they cannot be renewed. With the standard creation statement (`VALID UNTIL` the lease expiry), new connections fail after expiry while existing connections stay open until recycled. Plan for every service losing the ability to open new database connections within about 1 h of the outage start (SEV1 territory).
- **Transit fails closed**: no encryption/decryption of tenant credentials; in-memory leases already held by model-gateway-service and channel-service last at most 15 min (ADR-0014).
- Keycloak, Temporal, Apicurio, Kafka Connect and Qdrant keep running, but their restarts depend on Vault too.
- Severity: one node sealed with quorum intact = SEV3; Vault unavailable = SEV1 (all tenants, all planes).

## Dashboards / Alerts
- Vault: seal status per node, active node, Raft peers and Autopilot health, request rate/latency/errors, lease counts and renewals, audit device write errors, KMS (auto-unseal) errors, storage disk usage.
- Vault Secrets Operator: sync success/error per `VaultStaticSecret`, rollout restarts triggered.
- Services: lease-renewal failures and database connection errors by service; pods in `CrashLoopBackOff` across namespaces.

## Immediate mitigation
1. **Freeze change.** Pause the pipelines that deploy to the affected environment (no deployments, no shared-component upgrades). Avoid node drains and cluster-autoscaler scale-downs, and do not restart healthy pods: every new pod needs Vault. Scale-out by HPA will produce pods that cannot start; that is acceptable (existing pods keep serving).
2. If only one node is sealed or unhealthy and quorum is intact: Vault keeps serving; restart that pod and let auto-unseal and Raft rejoin it (Diagnosis 2).
3. If the audit device is failing: restore its storage (free disk, fix the log path or socket target). Do not disable the audit device without Security approval; if Security approves enabling a second audit device to restore service, record it in the incident.
4. Tell connector-service, model-gateway-service and channel-service owners to expect credential failures; they must not introduce any fallback secret source (STD-014).

## Diagnosis
1. Cluster state:
   ```bash
   kubectl -n waver-infra-vault get pods -o wide
   kubectl -n waver-infra-vault exec vault-0 -- vault status          # Sealed, HA Mode, Raft index
   kubectl -n waver-infra-vault exec vault-0 -- vault operator raft list-peers
   kubectl -n waver-infra-vault exec vault-0 -- vault operator raft autopilot state
   ```
2. Sealed nodes: read the logs for auto-unseal errors (KMS key disabled or deleted, KMS permission or network failure, KMS endpoint not in the egress allow-list, clock skew):
   ```bash
   kubectl -n waver-infra-vault logs vault-0 --since=30m | grep -iE "seal|kms|unseal|error"
   ```
   Repeated sealing after restarts → KMS access, not Vault, is the cause.
3. No leader: Raft quorum lost (more than half the peers down, a zone outage, disk full on peers). Check PVC usage and node status of each peer.
4. Requests rejected while unsealed: audit device failure (logs contain "audit" errors), or a policy/auth change (403 for one service only → Kubernetes auth role or policy change in the last deployment; compare with Git).
5. Service-side only: if Vault is healthy but one service fails, check its ServiceAccount, projected token audience `vault`, Kubernetes auth role binding, and mesh/NetworkPolicy path to `waver-infra-vault` (RFC-0015 §6, §13).

## Recovery
- **Auto-unseal broken (KMS):** restore KMS access (key enabled, permissions, network/egress path) through the platform team and the cloud provider; then restart sealed pods one at a time; they unseal automatically.
- **Shamir unseal (environments without KMS):** the required quorum of named key holders each submit their share directly in their own session (`vault operator unseal`), never through chat or a shared terminal.
- **Raft quorum lost:** bring failed peers back (node, PVC, zone). If peers cannot return, follow the Raft `peers.json` manual recovery procedure on the surviving node with the platform owner and Security, then rejoin new peers.
- **Data loss / unrecoverable cluster (last resort, incident-commander and Security approval):** restore the latest encrypted Raft snapshot into a fresh cluster (`vault operator raft snapshot restore`). Consequences to handle afterwards: dynamic database credentials issued after the snapshot are unknown to Vault (revoke orphaned PostgreSQL roles created by Vault after the snapshot time); any Transit key version created after the snapshot is lost, so ciphertexts written with it cannot be decrypted (this is why a snapshot is taken right after every Transit rotation, STD-014); KV versions written after the snapshot must be re-written by the pipeline secret stage.
- **After Vault is healthy:**
  1. Confirm Vault Secrets Operator syncs succeed and lease renewals recover.
  2. Restart pods stuck in `CrashLoopBackOff` (they fetch new credentials at start); services whose database credentials expired recycle their pools automatically or are restarted in a controlled rollout.
  3. Run the Transit smoke test (connector-service health endpoint that encrypts/decrypts a synthetic value) and an outbound webhook signature check.
  4. Unfreeze pipelines.
- Recovery is confirmed when all nodes are unsealed with a healthy Raft for 30 min, no lease-renewal failures for 15 min, and no pods failing on Vault authentication.

## Rollback
- Policy or role change that broke authentication: revert the commit under `deploy/platform/vault/` (or the service chart's Vault fragment) and let the pipeline re-apply it (RFC-0014 §7).
- Vault upgrade that caused the outage: follow the upgrade rollback in RFC-0014 §12 (restore the pre-upgrade snapshot onto the previous version only if the new version cannot start; Vault does not support in-place downgrades of migrated storage).
- Never roll back by switching services to Kubernetes Secrets or environment variables.

## Data integrity checks
- Vault audit log is continuous for the incident window (gaps mean requests were refused, not unaudited); record any period where Security approved a second audit device.
- Transit: a sample of tenant credentials (one per Transit key version in use) decrypts in a connector-service integrity job; no ciphertext references a key version newer than the restored state.
- PostgreSQL: no orphaned Vault-created roles remain (compare `pg_roles` of each instance with Vault's active leases); no static or human-known password was introduced during the incident.
- Credential-lease audit events in audit-service match the Vault audit log for the window (AR-009).

## Escalation
- Vault unavailable or sealed without quorum → page platform on-call and incident commander immediately (SEV1); Security joins for any unseal, recovery-key, root-token or snapshot-restore action.
- KMS failures → cloud provider support through the platform team.
- Any suspicion of compromise (unexpected root token, policy change outside the pipeline, unknown auth role) → Security immediately; treat as a security incident and rotate affected secrets after recovery (STD-014 rule 21).
- Tenant-facing failures longer than 30 min (tool executions, channels) → commercial operations for reseller communication.

## Post-incident actions
- Incident record; blameless review within 5 working days (SEV1/SEV2).
- Revoke any root token generated during the incident; rotate any secret exposed during break-glass.
- Review TTLs and max TTLs of database roles against the observed outage duration; review snapshot frequency and the last restore drill date.
- Add or tune alerts that would have fired earlier (KMS errors, audit device disk, Raft health).
- Update this runbook and RFC-0014 §12 if the procedure changed.
