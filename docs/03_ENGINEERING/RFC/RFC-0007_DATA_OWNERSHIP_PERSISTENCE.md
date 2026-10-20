# RFC-0007 - Data Ownership & Persistence
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis as a non-authoritative store (ADR-0020), Keycloak database (ADR-0021), Vault dynamic database credentials and Transit (ADR-0022), Temporal persistence (ADR-0023), `wal_level=logical` for Debezium and the Apicurio database (ADR-0024), Qdrant for vectors (ADR-0025)).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines how services own and persist data. PostgreSQL is the **source of truth** for every business fact: database-per-service, physical topology per environment (including `wal_level=logical` for the Debezium outbox), database roles with **Vault-issued dynamic credentials**, Row-Level Security for tenant isolation (ADR-0010), Flyway migrations with zero-downtime expand/contract, JPA vs plain JDBC, transactions and optimistic locking, event-built read models, data classification, retention/soft delete/purge and LGPD export/deletion, backups/PITR/restore testing, and a storage port for large objects whose implementation stays open until the cloud decision. It also fixes the rules for the derived and non-authoritative stores adopted on 2026-10-19 — **Qdrant** for vectors (rebuildable from PostgreSQL) and **Redis** (never authoritative) — and the databases and backups of the platform components (Keycloak, Temporal, Apicurio Registry, Vault).

## Context / Problem
ADR-0003 mandates database per service; ADR-0010 chooses logical tenant isolation with RLS; CONVENTIONS fixes DB naming, mandatory columns and the Flyway file pattern. On 2026-10-19 ADR-0025 moved embeddings from pgvector to Qdrant (PostgreSQL remains the source of truth), ADR-0020 added Redis as a shared non-authoritative store (superseding ADR-0013's "no additional datastores"), ADR-0022 replaced Kubernetes-Secret database passwords and application envelope encryption with Vault, ADR-0023 introduced Temporal with its own database, ADR-0024 requires logical replication for Debezium plus a database for Apicurio Registry, and ADR-0021 gives Keycloak its own database. What is missing: how many PostgreSQL clusters exist, which roles each service has and how their credentials are issued, how RLS is implemented concretely, how schemas change without downtime, how cross-service data is copied, how the derived stores stay consistent with PostgreSQL, how data is retained and purged under LGPD across all stores, how every stateful component is backed up and restored, and how documents for knowledge are stored without choosing a cloud object store.

## Goals
- Enforce AR-002 (no cross-service DB access) physically, through roles, Vault policies and credentials, not only by convention.
- Enforce tenant isolation twice (application + RLS) with a mechanism every service gets for free from `waver-platform-starter`.
- Zero-downtime schema changes with independent service lifecycles (AR-005).
- PostgreSQL is the only system of record; every other store (Redis, Qdrant, Kafka) can be lost and rebuilt without losing a business fact.
- Defined, auditable data lifecycle: active → soft-deleted/terminated → retained → purged; exportable; applied to every store.
- Tested restores, not just backups, for every stateful component.

## Non-goals
- Choosing a managed PostgreSQL, Redis or object-store product (cloud provider open, TECH_STACK).
- Database-per-tenant (ADR-0010: later enterprise option for data-plane services).
- Business retention periods (legal/business decisions; see Open Questions).
- Analytics warehouse / BI platform.
- Retrieval algorithms and ingestion pipelines (knowledge-service, RFC-0018/ADR-0025); quota semantics (RFC-0020).

## Requirements / ARs
| AR | Mechanism |
|---|---|
| AR-002 | One database + roles per service; Vault issues credentials only to that service's Kubernetes identity; no cross-DB links (`dblink`, `postgres_fdw` forbidden). Qdrant collections, Redis key prefixes and Temporal namespaces/task queues are likewise owned per service. |
| AR-005 | Each service owns its Flyway migrations, run by its own pipeline Job. |
| AR-006 | Inbox, idempotency records, unique business keys (RFC-0006, RFC-0008); idempotent Qdrant upserts (point ID = chunk ID); idempotent Temporal activities. |
| AR-008 | `SET LOCAL` tenant context + RLS on every tenant-owned table; mandatory `org_id` filter on every Qdrant query; no tenant data in Redis keys. |
| AR-009 | Audited cross-tenant query paths; purge audited and approved. |
| AR-012 | Suspension never deletes; purge is a separate approved process. |
| AR-013 | Data-plane read models stored locally in PostgreSQL (`*_view`, `*_snapshot`), rebuilt from Kafka; Redis is an accelerator, never the only copy. |

## Proposed Design

### 1. Physical topology
| Environment | Clusters | Notes |
|---|---|---|
| dev / preview | 1 shared cluster | Per-service DBs; preview environments get ephemeral DBs (Testcontainers locally). |
| staging | 1 shared cluster (same major version, extensions, parameters as production) | Restore drills target staging. |
| production v1 | 1 shared cluster per environment (HA: primary + synchronous standby in another zone) + dedicated clusters when a promotion trigger fires | One database `waver_<service>` and one role set per service. |

Promotion to a **dedicated cluster** (no code change; only the Vault database connection configuration changes) when any trigger holds:
- Tier-0 service whose availability would otherwise depend on unrelated workloads (RFC-0009 tiers). Default recommendation: `agent-runtime` and `identity-access-service` get dedicated clusters before GA; the Keycloak and Temporal databases (tier-0 platform components) are placed on a dedicated platform cluster before GA, shared by no WAVER service database.
- Sustained > 30% of shared-cluster CPU/IO, or > 500 GB, or WAL volume that dominates the cluster's replication-slot retention (§1 parameters).
- Distinct retention/backup/residency requirements (e.g., enterprise data residency, open).

BFFs have no PostgreSQL database: their HTTP sessions live in Redis (ADR-0020, §12). Connection pooling is HikariCP per pod (Spring Boot default); pool size default 10 per pod, cluster `max_connections` budgeted per service in `deploy/environments/<env>`.

**Mandatory cluster parameters** (all environments, ADR-0024):
| Parameter | Value | Reason |
|---|---|---|
| `wal_level` | `logical` | Debezium reads the outbox through logical replication (RFC-0006 §8). |
| `max_replication_slots` / `max_wal_senders` | ≥ number of producing databases on the cluster + 25% (plus physical standbys) | One slot `waver_<svc>_outbox` per producing database. |
| `max_slot_wal_keep_size` | Sized per cluster (default 20% of the WAL volume) | Caps WAL retained by a stalled slot so the primary never fills its disk; a slot exceeding it is invalidated and recovered by the RFC-0006 runbook. |
| Failover slots | Enabled where the PostgreSQL version / managed offering supports it | Keeps outbox slots across primary failover (open, RFC-0006). |
| `ssl` | on, server certificate verifiable | `sslmode=verify-full` for all clients, including inside the mesh. |

**Platform component databases** (each is its own database with its own role set, created by the platform Job like a service database):
| Database | Owner | Notes |
|---|---|---|
| `waver_keycloak` | Keycloak (ADR-0021) | Tier 0. Realm configuration is also kept as code (Admin API export/import). |
| `waver_temporal`, `waver_temporal_visibility` | Temporal cluster (ADR-0023) | Tier 0 for data-plane waits; schema managed by Temporal's own schema tool in the platform Job; no WAVER service reads them. |
| `waver_apicurio_registry` | Apicurio Registry (ADR-0024) | Holds schemas and their **global IDs**; IDs referenced by every Kafka record must survive restores (§10). |

Vault uses its integrated Raft storage (ADR-0022), not PostgreSQL. Kafka Connect keeps its state in Kafka topics (RFC-0006).

### 2. Roles per service database
Group roles are `NOLOGIN` and carry the privileges; the **login users are issued by the Vault database secrets engine** (ADR-0022) and are members of exactly one group role. No static password exists for application, migration or cross-tenant access.

| Group role | Login users | Privileges | Used by |
|---|---|---|---|
| `<svc>_owner` | none | Owns schema objects | Assumed (`SET ROLE`) by the migrator login, so objects are never owned by an ephemeral user |
| `<svc>_migrator` | Vault dynamic, TTL 15 min, issued to the Flyway Job's service account | Member of `<svc>_owner` | Flyway Job only |
| `<svc>_app` | Vault dynamic, TTL 1 h (renewed, max TTL 24 h), issued to the service's pods | DML on tables, `USAGE` on sequences; **no BYPASSRLS**, not owner | Service pods (including Temporal activity workers of the service) |
| `<svc>_cdc` | Vault **static role** (fixed name `<svc>_cdc`, password rotated by Vault every 30 days) | `LOGIN REPLICATION`; `SELECT` on `outbox_event` and `debezium_heartbeat` only | Debezium connector of the service (RFC-0006 §8); exists only for producing services |
| `<svc>_xtenant` | Vault dynamic, TTL 1 h | Read-only, policies for audited cross-tenant views | Only services that own such views (ADR-0010) |
| `<svc>_breakglass_ro` | Vault dynamic, TTL 1 h, issued only after the two-person break-glass approval (RFC-0010) | Read-only, RLS applies | Humans, audited |

Rules:
- `REVOKE ALL ON DATABASE waver_<svc> FROM PUBLIC`; no role of service A is granted anything in `waver_<svc B>`; Vault's policy for service A can only read its own database roles (`waver-<env>-<svc A>*`, CONVENTIONS). Superuser credentials are break-glass only (RFC-0010), held in Vault and never used by applications.
- Vault connects to each cluster with a management user that has `CREATEROLE` and admin option on the group roles only (not superuser).
- PostgreSQL does not inherit role-level settings from group roles, so `statement_timeout` (default 5 s app, 60 s xtenant), `idle_in_transaction_session_timeout` (30 s) and `search_path` are set in the Vault role's **creation statements** (`ALTER ROLE "{{name}}" SET ...`), and the platform starter also sets them per connection.
- Credential delivery: the Vault Secrets Operator syncs the dynamic secret to a short-lived Kubernetes Secret mounted as a file; the platform starter watches the file and updates HikariCP credentials at runtime (existing connections are soft-evicted and re-opened with the new user), so lease rotation never restarts pods. Vault revokes expired users and terminates their sessions.

### 3. Row-Level Security (ADR-0010)
Tenant context is set per transaction by the platform starter from the validated token or the event headers (RFC-0006 §12), using `set_config(..., true)` (transaction-local, bind-parameter safe, pool safe):

```sql
SELECT set_config('waver.org_id',       :orgId,       true),
       set_config('waver.org_type',     :orgType,     true),  -- PLATFORM | RESELLER | CUSTOMER
       set_config('waver.workspace_id', :workspaceId, true),  -- '' when org-wide
       set_config('waver.domain_id',    :domainId,    true);  -- '' when not domain-scoped
```

Standard policy, generated by a migration template for each tenant-owned table:

```sql
ALTER TABLE agent ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON agent
  USING (
    org_id = NULLIF(current_setting('waver.org_id', true), '')::uuid
    AND (NULLIF(current_setting('waver.workspace_id', true), '') IS NULL
         OR workspace_id = NULLIF(current_setting('waver.workspace_id', true), '')::uuid)
    AND (NULLIF(current_setting('waver.domain_id', true), '') IS NULL
         OR domain_id = NULLIF(current_setting('waver.domain_id', true), '')::uuid))
  WITH CHECK (org_id = NULLIF(current_setting('waver.org_id', true), '')::uuid);
```
- `NULLIF(..., '')` matters: after a `SET LOCAL`/`set_config(..., true)` transaction ends the placeholder can remain defined as `''`; a missing org therefore yields `NULL` and zero rows, never an error path that is caught and ignored.
- The application additionally checks `ws_ids` and permissions (RFC-0004); RLS is the second line, scoped at least to the Organization.
- **Reseller portfolio access** uses the standard column from ADR-0010. Rows a reseller may read for its customers carry `reseller_org_id`, maintained from `CustomerOwnershipTransferred`. Documents issued by a reseller (quotes, contracts, invoices) also carry an immutable `seller_org_id`. The application sets `waver.portfolio_access = 'on'` only for reseller roles on portfolio endpoints, and doing so emits an audit record (RFC-0006 §13):

```sql
CREATE POLICY portfolio_read ON entitlement FOR SELECT
  USING (
    org_id = NULLIF(current_setting('waver.org_id', true), '')::uuid
    OR (NULLIF(current_setting('waver.portfolio_access', true), '') = 'on'
        AND reseller_org_id = NULLIF(current_setting('waver.org_id', true), '')::uuid));
```
- **WAVER oversight and other cross-tenant reads** use the dedicated `<svc>_xtenant` role (ADR-0010), with a policy keyed on `current_setting('waver.org_type') = 'PLATFORM'`. The `xtenant` datasource is a separate Spring bean, usable only from query handlers annotated `@CrossTenantQuery`. Each such query emits an audit record (AR-009).
- Platform tables: `outbox_event` (insert allowed to `_app` with `WITH CHECK org_id = current org OR org_id IS NULL`), `debezium_heartbeat`, `processed_event`, `idempotency_record`, `flyway_schema_history`.
- Logical decoding is **not** subject to RLS: the `<svc>_cdc` role sees all outbox rows of the database. This is why it is restricted to the publication's tables, used only by the connector and monitored (RFC-0006 Security, RFC-0010).
- The migration linter (ADR-0010) rejects a table with `org_id` but without `ENABLE`+`FORCE ROW LEVEL SECURITY` and a policy, and rejects a table without `org_id` unless listed in `db/platform-tables.txt` with justification.

### 4. Migrations (Flyway) and zero-downtime changes
- Location `services/<svc>/<svc>-adapters/src/main/resources/db/migration`, files `V<yyyyMMddHHmm>__<description>.sql`, forward-only (CONVENTIONS). No `undo` migrations; a bad migration is fixed by a new one.
- Run as a Kubernetes Job (Helm pre-upgrade hook) with a Vault-issued `<svc>_migrator` login that runs `SET ROLE <svc>_owner` first; application pods start with `spring.flyway.enabled=false` in staging/production. Each migration sets `lock_timeout = '3s'` and `statement_timeout`; the Job retries on lock timeout.
- Every change must be compatible with the running version N and the new version N+1 (rolling deploys, RFC-0014). Expand/contract:

| Change | Release N (expand) | Release N+1 | Release N+2 (contract) |
|---|---|---|---|
| Add column | Add nullable / with constant default | Code writes and reads it | Add `NOT NULL` via `CHECK ... NOT VALID` then `VALIDATE` |
| Rename column | Add new column, dual-write, backfill in batches | Read new column | Drop old column |
| Change type | New column + dual-write + backfill | Switch reads | Drop old |
| Drop column | Code stops using it | — | Drop column |
| Add index | `CREATE INDEX CONCURRENTLY` (migration with `executeInTransaction=false`) | — | — |
| Split table | New table + dual-write + backfill job | Switch reads | Drop old |

- Backfills > 10 000 rows run as idempotent batch jobs, not inside a migration: a Temporal workflow owned by the service when the backfill is long-running or must survive restarts (ADR-0023), otherwise a simple keyset loop.
- `outbox_event`, `debezium_heartbeat` and the `waver_outbox` publication are changed only by platform-starter migrations; a service migration never alters them.
- Qdrant has no schema migrations: an embedding or index change creates a new versioned collection, filled by the service's ingestion workflow, then swapped atomically through the collection alias (§12, ADR-0025).

### 5. JPA vs plain JDBC
| Use | Technology |
|---|---|
| Aggregates with invariants (control plane: contract, catalog, agent-builder, identity) | Spring Data JPA; entities live in `adapters` and are mapped to domain objects (MapStruct); domain stays framework-free (AR-007). |
| Hot paths, bulk writes, keyset pagination, projections, read models, full-text queries, outbox inserts, reporting | `JdbcClient` / `JdbcTemplate` with explicit SQL. |
| Vector search and upserts (knowledge-service, memory-service) | Qdrant Java client behind a repository port in `adapters`; filters built by the platform library (§12). |
| Redis access | Spring Data Redis (Lettuce) through starter components (cache, rate limit, idempotency, locks, streams), never raw clients in domain code. |

Rules: `spring.jpa.open-in-view=false`; no lazy loading across aggregate boundaries; no entity is ever serialized in an API or event; repository ports are defined in `domain`/`application`, implemented in `adapters`.

### 6. Transactions and concurrency
- One aggregate per transaction; transaction boundary in the application layer (use case). Outbox insert in the same transaction (RFC-0006).
- Optimistic locking: `version bigint` mandatory column (`@Version` or `UPDATE ... WHERE id = ? AND version = ?`). Exposed as HTTP `ETag`; `If-Match` required on updates (RFC-0008). Conflict → `409`/`412` with code `CONCURRENT_MODIFICATION`.
- Pessimistic row locks only for queue-like work (`FOR UPDATE SKIP LOCKED`).
- Short cross-pod coordination (e.g., "only one pod refreshes this cache") may use Redis locks (`RedisLockRegistry`/Redisson, ADR-0020). A Redis lock is never the correctness guarantee for PostgreSQL data: invariants are protected by optimistic versions, unique constraints or row locks, because a Redis lock can be lost.
- Long-running and multi-step processes (sagas, timers, singleton schedules) are Temporal workflows owned by the service (ADR-0023). Each activity is idempotent and runs its own short transaction (with outbox rows for events); a workflow never holds a database transaction or lock across activities.
- Default isolation `READ COMMITTED`; `REPEATABLE READ` only for documented invariants (e.g., ledger posting in billing-service).
- Money columns `numeric(19,4)` + `char(3)`; timestamps `timestamptz` (UTC); IDs `uuid` (UUIDv7 generated in the application).

### 7. Read models and projections
- A service that needs another service's data either calls its API (for immediate decisions) or keeps a **read model** built from events.
- Read-model tables are named `<thing>_view`, or `<thing>_snapshot` for state-topic copies, e.g., `deployment_view`, `entitlement_snapshot`, `org_status_view`. Each is marked with `COMMENT ON TABLE ... 'kind=READ_MODEL;source=<topic>'` so the linter and purge tooling can find it.
- Each row stores `source_aggregate_id`, `source_version` (`ce_aggregateversion`, or `snapshotVersion` for state topics) and `updated_at`. The projector applies an event only if its version is higher than `source_version` (RFC-0006 §4).
- Read models are copies:
  - never re-published as the consumer's own facts;
  - never edited by use cases;
  - always rebuildable: from the state topic, or through snapshot-API bootstrap plus catch-up (RFC-0005 §7).
- Mandatory data-plane read models (AR-013):
  - agent-runtime: `deployment_view`, `entitlement_snapshot`, `quota_state`, `org_status_view` (RFC-0011);
  - tool-runtime-service: `entitlement_snapshot`, tool-version and binding views (RFC-0012);
  - channel-service: `entitlement_snapshot`, channel route views.
- Redis caches (L2 behind Caffeine L1) and Redis quota counters are **not** read models: they may accelerate a read model or replace a per-request API call, but the PostgreSQL read model above stays mandatory so the data plane survives a Redis outage (ADR-0020 fallbacks).

### 8. Data classification
| Class | Definition | Examples | Controls |
|---|---|---|---|
| Public | Intended for publication | Public API docs, status page text | Integrity only |
| Internal | No customer data, low harm | Service configs (non-secret), technical metrics, logs without PII | Access by WAVER staff |
| Confidential | Customer/business data or personal data of platform users | Organizations, users (name, e-mail), contracts, invoices, usage, audit events, agent definitions, wholesale prices/margins (commercial-confidential: never exposed to customers, ADR-0006/0009) | RLS, encryption at rest, least privilege, export/erasure support |
| Restricted | Highest harm if disclosed | Tenant credentials and signing keys (connector-service ciphertext under Vault Transit, ADR-0022; signing keys never leave Vault), conversation messages and transcripts, documents/chunks/embeddings (PostgreSQL and Qdrant), memory entries, prompts/completions, agent output streams in Redis, tool inputs/outputs, support-grant sessions | Confidential controls + no logging, short Kafka retention, field-level encryption where specified, access only through owning service APIs, purge verification |

Schemas (OpenAPI, Avro events, DB columns via `COMMENT ON COLUMN ... 'class=RESTRICTED;pii=true'`) carry the classification; CI lints for unclassified fields in new tables and contracts.

Per-store rules:
- **Redis**: no PII or Restricted content in keys; Confidential values only when the owning service needs them for a cache and with a TTL; Restricted values only for the agent-output streams (`waver:run-stream:<agentRunId>`, TTL 15 min). These keys are **private to agent-runtime** (its ACL user is the only one with access): channel-service and workspace-bff subscribe through agent-runtime's SSE endpoint and never read the keys directly. The stream values must be encrypted (ADR-0020; mechanism in Open Questions).
- **Qdrant**: vectors plus the minimal filter payload (`org_id`, `workspace_id`, `domain_id`, `knowledge_base_id`, `chunk_id`, `acl_tags`, `embedding_model`); no chunk text or document metadata (those stay in PostgreSQL). Embeddings are still Restricted (they can leak content).
- **Temporal**: workflow inputs, results, signals and activity payloads carry IDs and small control values only, never Restricted content; content is loaded by activities from the owning service's database.

### 9. Retention, soft delete and purge (LGPD)
- Soft delete (`deleted_at`, `deleted_by`) only where retention requires it (CONVENTIONS). Suspension never deletes or soft-deletes (AR-012).
- The Organization lifecycle in tenant-service (RFC-0003 §7) drives the data lifecycle:

| Status | Data state |
|---|---|
| `ACTIVE` | Normal operation |
| `SUSPENDED` | Data intact (AR-012) |
| `TERMINATED` | Access closed except export; retention window starts |
| `PURGING` | Retention elapsed and purge `ApprovalGranted`; services are deleting |
| `PURGED` | Tombstone only |

- Each service implements a `DataLifecyclePort` from the platform starter, with two operations, both idempotent and resumable:
  - `export(orgId | subjectRef)`: JSON lines per aggregate type.
  - `purge(orgId | subjectRef)`: hard delete of the org's rows, including read models, outbox rows, idempotency records and `blob_object`; **plus** the service's derived data in other stores: Qdrant points (delete by filter `org_id`), Redis keys that have no TTL (e.g., quota counters, deleted by the IDs read from PostgreSQL; keys with TTL simply expire), and cancellation of the service's open Temporal workflows for that org.
- **Purge orchestration** is owned by tenant-service as the tenant purge **Temporal workflow** (namespace `waver-platform`, ADR-0023, RFC-0003), with one confirmation per data-owning service. Purge requires an ApprovalRequest (approval-service; Commercial §11.1). Each service:
  1. reacts to `OrganizationStatusChanged(status=PURGING)`;
  2. purges in batches, with the tenant context of the org being purged (RLS still applies, no BYPASSRLS), then its Qdrant points and Redis keys;
  3. verifies zero remaining rows with a count query (and zero Qdrant points with a filtered count);
  4. confirms to tenant-service through `POST /v1/purge-jobs/{jobId}/confirmations` (Idempotency-Key = event id); tenant-service signals its own workflow.
- tenant-service then publishes `TenantDataPurged`. It is the single producer, consistent with MICROSERVICES_MAP. See Open Questions on RFC-0003's per-service wording.
- identity-access-service's purge also removes the org's users, organization and IdP configuration from Keycloak through the Admin API (ADR-0021).
- Closed Temporal workflow histories expire with the namespace retention (default 30 days after close); because they hold IDs only (§8), no content outlives the purge.
- Audit events are retained under the audit policy and are not purged with the tenant. They hold IDs, not content.
- Data-subject requests come from end users of a customer, e.g., a WhatsApp contact. The Customer is the controller, and requests are raised by the customer admin. Services locate the data through a `subject_ref` index (hashed, normalized phone or e-mail) maintained by channel-service, agent-runtime and memory-service.

```mermaid
sequenceDiagram
  participant Admin as WAVER/Reseller admin
  participant TS as tenant-service (purge workflow, Temporal)
  participant AP as approval-service
  participant S as Each data-owning service
  participant AU as audit-service
  Admin->>TS: request purge(orgId) (TERMINATED, retention elapsed)
  TS->>AP: create ApprovalRequest
  AP-->>TS: ApprovalGranted (event → signal)
  TS-->>S: OrganizationStatusChanged(PURGING) (event)
  S->>S: purge PostgreSQL in batches, then Qdrant points / Redis keys; verify zero
  S->>TS: POST /v1/purge-jobs/{id}/confirmations (→ workflow signal)
  TS-->>AU: TenantDataPurged + OrganizationStatusChanged(PURGED)
```
- Backups: purged data disappears from backups and snapshots (PostgreSQL, Qdrant) when they expire (≤ 35 days, §10). This is documented in the DPA. A restore must never re-introduce a purged tenant, so the restore runbook replays tenant-service's completed purge jobs after any restore of PostgreSQL or Qdrant (RFC-0014 restore order).

### 10. Backups, PITR, restore testing (engineering defaults)
**PostgreSQL** (service databases and the Keycloak, Temporal and Apicurio databases):

| Item | Default |
|---|---|
| Continuous WAL archiving + PITR window | 14 days (production), 3 days (staging) |
| Daily snapshot retention | 35 days |
| RPO / RTO tier-0 DB (incl. `waver_keycloak`, `waver_temporal*`, `waver_apicurio_registry`) | ≤ 5 min / ≤ 1 h |
| RPO / RTO tier-1/2 DB | ≤ 15 min / ≤ 4 h |
| Cross-region backup copy | Required for production; region choice open (data residency) |
| Restore drill | Monthly full-cluster restore to staging; quarterly per-service PITR drill with checksum comparison; results recorded in the runbook |

Single-tenant recovery (e.g., accidental deletion in one org): PITR to a side instance, extract rows `WHERE org_id = ?`, re-apply through the owning service's admin restore use case (never by direct copy into production), audited. After any PITR of a producing database, its replication slot is recreated and the reconciliation of RFC-0006 (Reliability) applies.

**Other stateful components:**

| Component | Backup | RPO / RTO | Restore approach and caveats |
|---|---|---|---|
| Redis (ADR-0020) | None required for correctness (non-authoritative). Optional RDB snapshots only to shorten warm-up | n/a / ≤ 15 min (empty cluster) | Start empty: sessions → re-login; rate limits restart; caches cold; quota counters re-seeded from PostgreSQL `quota_counter` by usage-metering-service's reconciliation before enforcement switches back from the fallback (RFC-0020); run streams lost (clients reconnect and read the final message from agent-runtime). |
| Qdrant (ADR-0025) | Collection snapshots daily, retention 7 days (35 days in production when the collection is large), stored with the database backups | ≤ 24 h + replay / ≤ 4 h | Restore the snapshot, then re-embed and upsert chunks changed in PostgreSQL after the snapshot time (ingestion workflow with `since`), then re-apply completed purges. Full rebuild from PostgreSQL (re-embedding everything) is always possible but slow and costly; quarterly rebuild drill in staging (ADR-0025). |
| Vault (ADR-0022) | Raft snapshots hourly, encrypted, cross-region copy, retention 35 days; additional snapshot after every Transit key rotation | ≤ 1 h / ≤ 1 h | Restoring an older snapshot must not lose Transit key versions used by existing ciphertexts: key rotation is rare and always followed by a snapshot; the restore runbook checks the latest key versions against connector-service ciphertexts. Dynamic credentials issued after the snapshot become orphaned and are revoked by a cleanup job. Unseal uses KMS auto-unseal; recovery keys are split among named custodians. |
| Keycloak (ADR-0021) | `waver_keycloak` PostgreSQL backups (tier-0 defaults above); realm configuration as code in Git | ≤ 5 min / ≤ 1 h | Restore DB, re-apply realm configuration from Git; user sessions lost (re-login). |
| Temporal (ADR-0023) | `waver_temporal` and `waver_temporal_visibility` PostgreSQL backups (tier-0 defaults) | ≤ 5 min / ≤ 1 h | After a restore to an earlier point, workflows resume from their last persisted state and may re-run activities; activities are idempotent (AR-006) and starts are deduplicated by workflow ID. |
| Apicurio Registry (ADR-0024) | `waver_apicurio_registry` PostgreSQL backups (tier-0 defaults) plus a daily registry export (admin export, preserves global IDs) | ≤ 5 min / ≤ 1 h | Schema IDs embedded in Kafka records must keep resolving: restore from backup or import the export; never re-register from Git into an empty registry in an environment with retained records (IDs would change). |
| Kafka Connect (ADR-0024) | Stateless workers; state in `_waver-connect-*` topics; connector declarations in Git | — / ≤ 30 min | Recreate connectors from Git; if offsets are lost, the replication slot's confirmed LSN is authoritative; republish if a slot was lost (RFC-0006). |
| Kafka | Not backed up (not a system of record) | — | Topics recreated from code; republish and state-topic re-seed (RFC-0006). |

Recommended restore dependency order (to be reflected in RFC-0014): Vault → PostgreSQL clusters (Keycloak, Temporal, Apicurio, services) → Kafka and Apicurio Registry → Kafka Connect connectors (slots) → Qdrant → Redis (empty) → purge-ledger replay.

### 11. Large objects
- `ObjectStoragePort` (platform starter): `put(tenantKey, stream, contentType, sha256) → objectRef`, `get(objectRef)`, `delete(objectRef)`, `presignGet(objectRef, ttl)`; keys prefixed `org/<orgId>/<service>/...`; server-side encryption mandatory; content-addressed dedup per org.
- v1 adapter: PostgreSQL large-object table (`blob_object(org_id, id, sha256, content_type, size, bytes bytea)`) inside the owning service DB, RLS-protected, size limit 20 MB per object (knowledge documents, exports). The cloud object-store adapter is added when the cloud ADR is approved; migration is a background copy job behind the same port.
- Owners: knowledge-service (source documents), channel-service (media attachments), billing-service (invoice PDFs), tenant-service (organization export bundles; who assembles them is open).

### 12. Derived and non-authoritative stores
**Qdrant (ADR-0025)**
- Collections per owning service, named per CONVENTIONS: `knowledge_service__chunks_v<N>` (knowledge-service), `memory_service__entries_v<N>` (memory-service), accessed through aliases without version (`knowledge_service__chunks`, `memory_service__entries`). Each service has its own Qdrant credential (Vault KV, delivered by the Vault Secrets Operator) restricted to its collections; no service queries another's collections (AR-002).
- Tenant isolation: payload index on `org_id` with tenant-optimized indexing (`is_tenant`); every query carries a mandatory filter (org, workspace, domain, knowledge base, ACL tags) built by the platform library from the validated token, never from the request body; filters apply inside the search, before ranking. Large tenants can be moved to a dedicated shard or collection.
- Write path: PostgreSQL first (document, chunk text, metadata, permissions), then Qdrant upsert with point ID = chunk ID, driven by the ingestion Temporal workflow. Deletes follow the same order. A reconciliation activity compares chunk IDs and `embedding_model` per knowledge base and repairs drift.
- Read path: Qdrant results are re-checked against PostgreSQL (chunk exists, not deleted, still permitted) before use; hybrid retrieval combines them with PostgreSQL full-text search.
- Re-embedding: a new versioned collection is filled and then swapped atomically through the alias; the old collection is deleted after a grace period.

**Redis (ADR-0020)**
- One HA Redis per environment; each service has an ACL user restricted to its key prefix (`~waver:<prefix>:*`), with TLS and AUTH; administrative commands are not granted to service users.
- Uses and owners are those of ADR-0020 (BFF sessions, edge rate limiting, quota counters, idempotency keys, shared caches, short locks, agent-output streams). Every use has a documented fallback, and none holds a business fact that exists only in Redis: quota counters are reconciled every minute into PostgreSQL `quota_counter` (authoritative for billing, RFC-0020); idempotency responses needed for audit are stored in PostgreSQL (RFC-0008).
- Every key has a TTL except quota counters (period-scoped, deleted at period close or purge).
- Eviction policy `volatile-lru` on the cache tier; keys without TTL are therefore never evicted silently. Memory and eviction metrics are alerted (RFC-0009).

## APIs / Events / Data Ownership
- Aggregates per service: MICROSERVICES_MAP (authoritative). Platform tables owned by each service: `outbox_event`, `debezium_heartbeat`, `processed_event`, `idempotency_record`, read-model tables, `blob_object`.
- Derived stores owned by services: Qdrant collections (knowledge-service, memory-service), Redis key prefixes (per ADR-0020 table), Temporal workflows and task queues (per ADR-0023 table).
- Platform component databases: `waver_keycloak`, `waver_temporal`, `waver_temporal_visibility`, `waver_apicurio_registry` (owned by the platform team, no service access).
- Events used: `OrganizationStatusChanged`, `CustomerTerminated` (start retention), `ApprovalGranted` (purge), state topics for read models (RFC-0006 §11). New purge/export events are proposed in Open Questions.

## Security & Privacy
- Encryption at rest by the managed service (KMS, open) for PostgreSQL; encrypted volumes for Qdrant and for Redis persistence (if enabled); Vault's barrier encryption with KMS auto-unseal. TLS with `sslmode=verify-full` for all DB connections; TLS for Redis, Qdrant and Temporal clients; Istio ambient mTLS between all pods in addition (ADR-0026).
- Restricted columns needing field-level encryption (e.g., memory entries flagged sensitive) use Vault Transit through the platform crypto helper (key per data kind, encryption context = `orgId`, ADR-0022); plaintext keys never leave Vault. Tenant credentials remain exclusive to connector-service (ciphertext only in its database, Transit keys `tenant-credentials` and, for the leasable types, `tenant-credentials-llm`, `tenant-credentials-channel`, `tenant-credentials-payment`); the audited leases return ciphertext that the lessee decrypts under a decrypt-only Vault policy (RFC-0010 §6). Lessees never persist the plaintext.
- DB credentials are Vault dynamic credentials (§2): short-lived, per pod lease, revoked automatically; no static passwords in Kubernetes Secrets, Git or Helm values. The CDC role is a Vault static role rotated every 30 days.
- Human DB access in production: none by default; break-glass read-only Vault-issued credentials (`<svc>_breakglass_ro`, TTL 1 h) after two-person approval, with session audit (RFC-0010). Vault's audit device records every issuance.
- Redis ACL users and Qdrant credentials are per service (§12); their secrets come from Vault.

## Reliability & Failure Modes
| Failure | Effect | Handling |
|---|---|---|
| Shared cluster primary failure | All services on it degrade | Automatic failover to standby (< 60 s target); tier-0 services on dedicated clusters unaffected; outbox slots follow failover where supported, otherwise slot-recreation runbook (RFC-0006). |
| Tenant context not set | Zero rows / insert rejected | Fails closed; starter logs `TENANT_CONTEXT_MISSING` and returns 500; alert. |
| Long migration lock | Writes blocked | `lock_timeout` 3 s; Job retries; expand/contract avoids rewrites. |
| Pool exhaustion | Latency spikes | Bulkhead per datasource; `statement_timeout`; RED alerts (RFC-0009). |
| Projection drift | Stale/incorrect read model | Rebuild from state topic; daily reconciliation against the owner snapshot API (RFC-0005 §7). |
| Restore to earlier point | Events already published for lost writes | Reconciliation job (RFC-0006); purge ledger replay. |
| Stalled replication slot | WAL accumulates on the primary | `max_slot_wal_keep_size` cap, slot-lag alerts, RFC-0006 runbook. |
| Vault unavailable | New pods cannot obtain DB credentials; leases cannot be renewed | HA Vault; page immediately (tier 0). Running pods can open new connections until their current lease expires (≤ 1 h after the last renewal; the creation statements set `VALID UNTIL` to the lease expiry) and keep already-open connections beyond it; the pool's `maxLifetime` is raised automatically while renewals fail. |
| Dynamic credential rotation | Connections opened with the old user are terminated at revocation | Starter rotates pool credentials before expiry (at 2/3 of TTL) and soft-evicts connections. |
| Redis unavailable | Degraded latency and limits, never data loss | ADR-0020 fallbacks: sessions → re-login, rate limiting → local limiter, quota → PostgreSQL counter and local `quota_state`, cache → miss, locks → operation skipped or retried. |
| Qdrant unavailable | Vector retrieval fails | knowledge/memory return full-text-only results from PostgreSQL where the retrieval config allows, otherwise `503 DEPENDENCY_UNAVAILABLE`; ingestion workflows retry. |
| PostgreSQL / Qdrant drift | Retrieval misses or returns stale chunks | Final PostgreSQL check on read; reconciliation activity; rebuild drill. |

## Observability
Per DB: connections by role, pool wait time, slow queries (> 500 ms, logged without bind values), replication lag, **replication slot lag and WAL retained per slot**, transaction ID age, table bloat, RLS denials (`TENANT_CONTEXT_MISSING`), Vault lease renewals/failures per service, backup success and last restore drill age (alert when > 35 days). Per derived store: Qdrant search latency, collection size and reconciliation drift; Redis memory, evictions, keyspace per prefix and command latency; snapshot/backup success for Qdrant, Vault, Apicurio and the platform databases. Dashboards in RFC-0009.

## Testing Strategy
- Tenancy test kit (ADR-0010): for every repository, tenant A cannot read/write tenant B, and missing context returns nothing. Extended to Qdrant (no cross-tenant or cross-domain point under any filter combination, ADR-0025) and to Redis key builders (keys contain no tenant-supplied raw values, prefixes per service).
- Migration tests: apply all migrations on an empty DB and on a **synthetic** large-volume dataset (generator in `waver-platform-test`; production data and anonymized copies are never used, STD-010 T-25) in CI with Testcontainers (PostgreSQL with `wal_level=logical`); N/N+1 compatibility test runs the previous release's integration tests against the new schema.
- Linter: RLS, `org_id`, classification comments, `CONCURRENTLY` for indexes on existing tables, forbidden statements (`DROP` of columns still referenced, changes to platform outbox objects).
- Purge/export tests per service: seed org, export, purge, assert zero rows across all tables, zero Qdrant points and no remaining non-TTL Redis keys.
- Credential tests: dynamic-credential rotation under load (no failed requests); Redis-unavailable chaos test (no data loss, documented degradation, ADR-0020); quarterly Qdrant rebuild drill.

## Deployment / Rollout / Rollback
- New service: platform Job creates the DB, group roles, grants, the Vault database roles and policies, and (for producers) the publication and the `<svc>_cdc` static role, from `deploy/environments/<env>/databases.yaml`; then the service's first migration.
- Rollback of application code is always possible because schemas are expand-first; a schema is never rolled back, only rolled forward. Qdrant alias swaps can be reverted to the previous collection during its grace period.
- Cluster promotion: logical replication of the service DB to the dedicated cluster, cutover in a maintenance window of the service only (minutes): stop writes, wait until the outbox slot lag is zero, switch the Vault connection configuration, create the slot and connector on the new cluster (`snapshot.mode=no_data`) before resuming writes, drop the old slot.

## Alternatives Considered
- Schema-per-tenant / DB-per-tenant: rejected for v1 (ADR-0010).
- One cluster per service from day one: strongest isolation but high cost and operational load in v1.
- Flyway at application startup: simpler, but races across replicas and requires owner privileges in app pods.
- JPA everywhere: poor fit for bulk work and projections.
- pgvector inside the service database (ADR-0011, v1.2 design): one technology, transactional with the chunks; superseded by ADR-0025 for retrieval performance and scale.
- Static database passwords in Kubernetes Secrets with pipeline rotation (ADR-0014, v1.2 design): fewer moving parts; superseded by Vault dynamic credentials (ADR-0022).
- Redis as the system of record for counters or sessions-with-state: rejected (ADR-0020 rule: no business data only in Redis).

## Risks
- Shared cluster creates noisy-neighbour coupling between planes until promotions happen (AR-013 tension); replication slots add a WAL-retention coupling.
- RLS performance on large tables: mitigated with `org_id` leading composite indexes and policy expressions that stay sargable.
- PostgreSQL bytea storage for documents increases DB size and backup time until object storage exists.
- Purge across ~30 services and three stores (PostgreSQL, Qdrant, Redis) is complex; incomplete purge is an LGPD exposure.
- Dual write PostgreSQL + Qdrant can drift; mitigated by the final PostgreSQL check and reconciliation.
- Vault becomes a tier-0 dependency for database connectivity (bounded by lease max TTL).
- Re-embedding a full collection after a Qdrant loss is slow and costs provider tokens; snapshots reduce but do not remove this.

## Open Questions
1. Purge confirmation path: RFC-0003 says each service "reports `TenantDataPurged`", while MICROSERVICES_MAP lists `TenantDataPurged` as a tenant-service event. This RFC uses a synchronous confirmation API to tenant-service, keeping a single producer. RFC-0003 should be aligned. Also undecided: who assembles organization-level export bundles (`DataExportCompleted` event not yet defined).
2. Retention periods after termination, for conversations, memory, audit events and backups (Commercial §20 "Data retention", Scope §24 "Compliance"): business/legal decision. Includes the Temporal namespace retention (default 30 days proposed here).
3. Data residency/multi-region requirements (Scope §24) affect cluster, Qdrant, Redis and backup placement.
4. Approve dedicated clusters for agent-runtime and identity-access, and the dedicated platform cluster for Keycloak and Temporal, before GA (cost decision).
5. Whether customers can request single-tenant point-in-time restores as a support service.
6. Encryption mechanism for Restricted agent-output streams in Redis (ADR-0020 requires encryption or avoidance): since only agent-runtime reads them, proposed a per-run data key from Vault Transit (`datakey`) held in agent-runtime memory for the run, with the wrapped key stored next to the stream so another agent-runtime pod can serve the SSE reconnect; to be confirmed with RFC-0011.

## Decision
Pending review. Proposed: adopt the topology, roles and Vault credential model, RLS template, migration rules, classification, store rules and lifecycle port above; the platform starter provides tenant transactions, RLS helpers, idempotency, outbox/inbox, `DataLifecyclePort` and `ObjectStoragePort`.
