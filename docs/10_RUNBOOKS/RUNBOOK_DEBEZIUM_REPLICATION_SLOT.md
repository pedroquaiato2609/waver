# Runbook - Debezium Connector Failure and Replication Slot Lag
Service: Kafka Connect + Debezium outbox relay (ADR-0024); affects every producing service (worked example uses contract-service, database `waver_contract`, connector `outbox-contract-service`)
Owner: platform team (Kafka Connect, Debezium, PostgreSQL instances); owning team of the producing service for its outbox table and connector configuration
SLO: commit-to-Kafka latency of outbox events < 5 s at p99; replication-slot retained WAL below 20% of the instance's free WAL disk; 0 connectors in `FAILED` for more than 5 min
Alerts: `DebeziumConnectorFailed`, `DebeziumTaskFailed`, `DebeziumLagHigh` (MilliSecondsBehindSource > 60 s for 5 min), `ReplicationSlotRetainedWalHigh`, `ReplicationSlotInactive`, `PostgresWalDiskUsageHigh`, `KafkaConnectWorkersDown`
Tier: T1 (RFC-0014 §11); Kafka Connect + Debezium shared component
Last reviewed: 2026-10-19
Revision 1.3 (2026-10-19): new runbook for the Debezium outbox relay adopted by ADR-0024 (Kafka Connect, Debezium Outbox Event Router, PostgreSQL logical replication slots).

> Each producing database has one Debezium PostgreSQL connector with the Outbox Event Router SMT, one logical replication slot and one publication covering `outbox_event`. While the connector does not consume, PostgreSQL retains WAL for the slot: events are delayed, not lost, until disk runs out or the slot is invalidated. Commands use the Kafka Connect REST API (`$CONNECT`, inside `waver-infra-streaming`), `psql` with a read-only monitoring role, and kubectl. Credentials come from Vault through the on-call access procedure; never paste them into the incident channel. Names are illustrative until fixed by RFC-0006.

## Symptoms
- `DebeziumConnectorFailed` / `DebeziumTaskFailed`: connector or task state `FAILED` in Kafka Connect.
- `DebeziumLagHigh`: the connector streams but is far behind the source.
- `ReplicationSlotRetainedWalHigh` / `PostgresWalDiskUsageHigh`: WAL accumulating on the PostgreSQL instance for a slot.
- `ReplicationSlotInactive`: the slot has no active consumer (`active = false`).
- Business symptoms: state changed in a service but downstream effects never happen (e.g., contract activated, no entitlements or provisioning); consumers show no lag because nothing arrives.

## Impact
- All events of the affected producing service are delayed; downstream processes that depend on them stall (activation, provisioning, billing, data-plane read models). Data plane keeps serving already-authorized workloads from local read models (AR-013).
- WAL growth threatens the **whole PostgreSQL instance**, including other service databases on it: a full disk stops writes for every database on the instance (SEV1).
- If the slot is invalidated or dropped (`wal_status = lost`), events committed since the last confirmed position are no longer readable from WAL and must be republished from the outbox table (data-integrity risk).
- Severity: one connector failed < 30 min = SEV3; data-plane producer or > 30 min = SEV2; WAL disk > 80% or slot lost = SEV1.

## Dashboards / Alerts
- Kafka Connect: connectors and tasks by state, worker count, rebalances, task error rate.
- Debezium streaming metrics per connector: `MilliSecondsBehindSource`, `TotalNumberOfEventsSeen`, `Connected`, last event timestamp; heartbeat topic freshness.
- PostgreSQL: retained WAL per slot, `wal_status`, `safe_wal_size`, WAL disk usage per instance, replication connections.
- Producer outbox: rows inserted per minute vs events published per minute per topic.

## Immediate mitigation
1. Protect the database instance first. If WAL disk usage > 80%, increase the volume (managed: provider storage autoscaling or resize; in-cluster: expand the PVC) and page the platform team. Do not drop the slot to free disk without incident-commander approval: it causes event loss (Recovery, "Slot lost").
2. Restart failed tasks (safe, idempotent; consumers deduplicate by `ce_id`):
   ```bash
   curl -s -X POST "$CONNECT/connectors/outbox-contract-service/restart?includeTasks=true&onlyFailed=true"
   ```
3. If Kafka Connect workers are down, restore them (scale the Deployment in `waver-infra-streaming`, check node capacity and quotas):
   ```bash
   kubectl -n waver-infra-streaming get pods -l app.kubernetes.io/name=kafka-connect
   kubectl -n waver-infra-streaming scale deployment/kafka-connect --replicas=3
   ```
4. If a recent producer deployment changed the `outbox_event` table or the connector configuration and the task fails on it, roll back that service through the pipeline (RFC-0014 §7); the connector resumes from its stored offset.

## Diagnosis
1. Connector and task state, with the error trace:
   ```bash
   curl -s "$CONNECT/connectors/outbox-contract-service/status"
   curl -s "$CONNECT/connectors/outbox-contract-service/config"
   ```
   Typical traces: authentication failure to PostgreSQL (expired or revoked Vault-issued credential; check RUNBOOK_VAULT_SEALED_OR_UNAVAILABLE), publication or slot missing, column missing in `outbox_event` (incompatible migration), Kafka authorization error (connector principal ACL), record too large, router cannot read the topic or aggregate ID column.
2. Slot state on the producing instance (read-only role):
   ```sql
   SELECT slot_name, database, active, active_pid, wal_status, safe_wal_size,
          pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS retained_wal
   FROM pg_replication_slots WHERE slot_type = 'logical';
   ```
   - `active = false` and the connector is `RUNNING` → the connector points at a different slot name or database; compare with its config.
   - `active = false` and no connector exists → orphan slot (a removed connector); confirm with the owning team before removal (Recovery).
   - `wal_status = 'unreserved'` → about to be invalidated (`max_slot_wal_keep_size` reached); treat as SEV1. `wal_status = 'lost'` → go to Recovery, "Slot lost".
3. Retained WAL grows while the connector is `RUNNING` and lag is low: the producing database is quiet but other databases on the same instance write WAL, so the slot's confirmed position does not advance. Check that the connector heartbeat (`heartbeat.interval.ms` with the heartbeat action query) is configured and that the heartbeat topic receives records.
4. Lag high while `RUNNING`: Kafka produce errors or throttling (check connector logs), a burst of outbox writes (bulk job, backfill), or slow Kafka Connect workers (CPU, GC). Compare outbox insert rate with publish rate.
5. Kafka side: the target topics exist and the connector principal has write ACLs only for the service's topics (RFC-0015 §7).

## Recovery
- Task failure fixed (credential, ACL, configuration): restart the connector (Mitigation 2); confirm the slot becomes `active` and retained WAL decreases.
- Incompatible `outbox_event` change: roll back the producer or ship the fix forward with a compatible migration and router configuration (RFC-0014 §5 rule 8); restart the connector.
- Orphan slot (no connector, confirmed by the owning team and platform owner): drop it to release WAL. Record the action in the incident.
  ```sql
  SELECT pg_drop_replication_slot('<slot_name>');   -- platform owner role, audited
  ```
- **Slot lost or dropped while events were pending** (SEV1, incident-commander approval):
  1. Determine the last event delivered to Kafka for the service (last `ce_id`/`ce_time` per topic, via `tooling/event-inspect` on the service's topics).
  2. Recreate the slot and restart the connector with the same configuration (`snapshot.mode=no_data`, so existing rows are not re-snapshotted); new events flow again.
  3. Republish outbox rows committed after the last delivered event with the platform tool (proposed in `tooling/`, reviewed script until it exists). It publishes each row with its original `ce_id`, key, headers and Avro payload; consumers skip duplicates through their inbox (AR-006). This is only possible if outbox rows are still retained in the table (retention per RFC-0006).
     ```bash
     tooling/outbox-republish --service contract-service --since <ce_time of last delivered event> --dry-run   # then without --dry-run
     ```
- Recovery is confirmed when the connector and all tasks are `RUNNING` for 30 min, `MilliSecondsBehindSource` is under 5 s, retained WAL is back under threshold, and downstream consumers process the backlog.

## Rollback
- Producer service: pipeline `rollback` job (previous digest). Connector offsets and the slot stay; the previous version must still write compatible outbox rows (expand/contract).
- Connector configuration: revert the configuration commit and let the pipeline re-apply it (RFC-0014 §2 rule 7). Never edit connector configuration by hand through the REST API in `staging`/`production` except as audited break-glass, reconciled into Git within 24 h.
- Never reset connector offsets or drop a slot as a "rollback": both risk event loss or mass duplicates.

## Data integrity checks
- For a sample window, every outbox row has a matching record on its topic (same `ce_id`): compare counts per aggregate type between `outbox_event` (read-only role) and the topic.
- Consumers' inbox duplicate counters rose only by the number of republished or re-delivered events (AR-006).
- No event was published without the `ce_orgid` header for tenant-owned types (AR-008).
- PostgreSQL instance health: WAL disk back to normal; no other database on the instance was affected.

## Escalation
- WAL disk > 80% or `wal_status` `unreserved`/`lost` → page platform team and incident commander immediately (SEV1).
- Connector failed > 30 min for a data-plane producer or for contract/entitlement/billing producers → SEV2, owning team of the producer.
- Credential failures from Vault → RUNBOOK_VAULT_SEALED_OR_UNAVAILABLE and the platform team.
- Managed PostgreSQL or managed Kafka issues → provider support through the platform team (once chosen).

## Post-incident actions
- Incident record; blameless review for SEV1/SEV2 within 5 working days.
- Tune alert thresholds (`max_slot_wal_keep_size`, retained-WAL alert, heartbeat interval) per instance with a ticket and owner.
- If a migration broke the connector, add a CI check that renders the router configuration against the migrated `outbox_event` schema.
- If republishing was needed, verify that outbox retention is long enough to cover the time to detect and repair a lost slot.
- Update this runbook with any new diagnosis branch.
