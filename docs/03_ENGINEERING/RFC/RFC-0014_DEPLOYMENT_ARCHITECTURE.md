# RFC-0014 - Deployment Architecture
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis, Keycloak, HashiCorp Vault, Temporal, Apicurio Registry + Avro, Kafka Connect + Debezium, Qdrant, Istio ambient, Nx).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines how a merged commit reaches production: infrastructure environments, immutable images promoted by digest, configuration vs secrets per environment (secrets delivered by Vault), release strategy per plane type (canary through Istio waypoints), database migration ordering (expand/contract), event schema registration in Apicurio as a release step, Temporal workflow versioning, platform feature flags (separate from the commercial `FeatureFlag` of entitlement-service), rollback, independent deployment per service, version-compatibility windows, the frontend release train, the deployment/upgrade/backup lifecycle of the shared infrastructure components (Keycloak, Redis, Vault, Temporal, Apicurio Registry, Kafka Connect + Debezium, Qdrant, Istio) and default disaster-recovery targets. Kubernetes object standards are in RFC-0015; pipeline stages are in STD-012.

## Context / Problem
- ADR-0002 and AR-005 require independent deployment per service; ADR-0015 fixes the monorepo, path-based CI, `deploy/helm/<service>/`, `deploy/environments/<env>/` and `deploy/platform/<component>/` for shared infrastructure.
- CONVENTIONS fixes infrastructure environments `dev` → `staging` → `production` plus ephemeral previews for frontends/BFFs, and states that agent "Environments" (RFC-0016) are a product concept.
- Product baseline (Master §12.2, Scope §16): rollback, config/secrets per environment, future canary.
- v1.3 adopts shared platform components that every environment must run and upgrade: Keycloak (ADR-0021), Redis (ADR-0020), HashiCorp Vault (ADR-0022), Temporal (ADR-0023), Apicurio Registry with Avro and Debezium on Kafka Connect (ADR-0024), Qdrant (ADR-0025), Istio ambient (ADR-0026). The frontend workspace is managed by Nx (ADR-0027).
- Cloud provider, CI provider, container registry and observability backend are open (TECH_STACK). Whether each shared component runs managed or in-cluster follows the cloud decision (RFC-0015 §9). This RFC is therefore provider-neutral: it states what the pipeline and platform must do, not which product does it.
- Without explicit rules, teams drift into mutable tags, migrations that make rollback impossible, schemas registered by hand, flags that mix commercial and operational concerns, and "deploy together" plans (distributed monolith).

## Goals
1. Any service can reach production independently, at any time, without coordinating a release with other services.
2. Exactly the image tested in staging runs in production (same digest).
3. Rollback of any service to its previous version in 15 minutes or less, without data loss.
4. Explicit, testable DR targets per service tier and per shared infrastructure component.
5. No dependency on a specific cloud, CI or registry product.
6. Shared infrastructure components are deployed, upgraded and backed up through the same Git-driven pipeline as services.

## Non-goals
- Selecting cloud, CI, registry or observability products (separate ADRs), or choosing managed vs in-cluster for each shared component (RFC-0015 §9, open).
- Agent product Environments, Deployments and Releases (RFC-0016). They are data inside agent-builder-service governed by AR-010, not infrastructure.
- Kubernetes manifests, mesh policies and cluster standards (RFC-0015).
- Active-active multi-region (Open Questions).

## Requirements / ARs
| Requirement | How this RFC satisfies it |
|---|---|
| AR-005 Independent lifecycle | One image, chart, migration set, schema set and promotion record per service (§2, §8) |
| AR-010 Versioned production | Every production change (code, config, platform flag, Vault policy, Keycloak realm config, mesh policy, event schema) is a Git commit promoted by the pipeline (§2, §3, §6, §12) |
| AR-013 Plane isolation | Release strategy and DR tier per plane type (§4, §11) |
| AR-014 Failure isolation | Failed rollout stops itself; automatic revert to previous digest (§7) |
| AR-015 Compatibility | Compatibility windows, registry compatibility rule and version-skew rule (§9) |
| ADR-0010 | Migrations run with the owner role, never with the application role (§5) |
| ADR-0020 | Redis is non-authoritative; BFF sessions in Redis make edge pods interchangeable (§4); no business data needs Redis backup (§11) |
| ADR-0021 | Keycloak realm `waver` and clients per environment, configuration as code (§1, §3, §12) |
| ADR-0022 | Platform secrets and dynamic database credentials delivered by Vault, never in Git (§3, §5) |
| ADR-0023 | Temporal worker versioning aligned with service releases (§4, §9) |
| ADR-0024 | Avro schemas registered in Apicurio by the pipeline before the producer is deployed; Debezium relays the outbox (§2, §5, §9) |
| ADR-0025 | Qdrant collections are rebuildable from PostgreSQL; re-embedding uses versioned collections and alias swap (§11, §12) |
| ADR-0026 | Data-plane canary through Istio waypoints and Gateway API `HTTPRoute` weights (§4) |
| ADR-0027 | Frontend builds driven by Nx `affected` (§10) |

## Proposed Design

### 1. Infrastructure environments
| Environment | Purpose | Cluster | Data | Deployed when |
|---|---|---|---|---|
| `dev` | Integration of `main` | `dev` cluster | Synthetic only | Automatically on every merge that changes the service |
| `preview-pr-<n>` | Review of a PR touching `frontend/` or a `*-bff` | Namespace set in the `dev` cluster | Uses `dev` backends and shared components | On PR open/update; deleted on PR close or after 72 h idle |
| `staging` | Production-like verification, E2E, load and DR drills | `staging` cluster, same topology as production at smaller size | Synthetic and test tenants only | Promotion of a digest that passed `dev` gates |
| `production` | Customer traffic | `production` cluster, multi-zone | Real tenants | Promotion of a digest that passed `staging` gates, with approval |

Rules:
1. Environment names are fixed: `dev`, `staging`, `production`, `preview-pr-<n>`. Abbreviations (`prod`, `stg`) MUST NOT appear in resource names.
2. Each environment MUST have its own Kafka cluster, Kafka Connect cluster, Apicurio Registry, PostgreSQL instances, Redis, Vault cluster (with its own seal/KMS key and Transit keys), Keycloak deployment (realm `waver` with its own signing keys and clients, ADR-0021), Temporal cluster, Qdrant cluster and Istio mesh (own root of trust). Nothing stateful is shared between environments.
3. Production personal data MUST NOT be copied to any other environment (LGPD). This includes Temporal workflow histories, Redis contents, Qdrant collections and Vault data.
4. All agent product Environments of real customers (development/staging/production of an Agent, RFC-0016) run inside the infrastructure `production` environment. Documents MUST say "infrastructure environment" or "agent environment" whenever ambiguity is possible.

### 2. Artifacts and promotion by immutable digest
1. Build once: a merge that changes `services/<service>/` (or its `platform/*` inputs) builds exactly one image `<registry>/waver/<service>`. Frontends build `<registry>/waver/frontend-<app>`.
2. Tags (`<semver>`, `git-<sha>`) are informative only. Every deployment MUST reference the image by digest (`@sha256:...`); `latest` and environment-named tags are forbidden.
3. Images are signed and carry an SBOM before leaving the build stage (STD-012). Clusters admit only signed images (RFC-0015).
4. Promotion copies the same digest, together with the chart version and the event-schema set built from the same commit, into `deploy/environments/<env>/<service>.yaml` through an automated commit. `dev` and `staging` commits are auto-merged by the pipeline; the `production` commit requires approval (STD-012). Git history is the authoritative record of what runs where.
5. The path is always `dev` → `staging` → `production`. Hotfixes use the same path with an expedited gate set; no environment is skipped.
6. **Schema registration is a release step (ADR-0024).** The Avro schemas (`contracts/events/<context>/<EventName>.v<N>.avsc`) that the service produces are registered in the target environment's Apicurio Registry by the pipeline, before the migrate Job and the rollout, in every environment. Services run with `auto.register.schemas=false`; the registry enforces `BACKWARD_TRANSITIVE` per subject and the step fails the deployment if the schema is incompatible. Registration is idempotent (an identical schema returns the existing version). Only the pipeline identity has write access to the registry.
7. **Outbox connector configuration.** The Debezium connector of a producing service (one per producing database, Outbox Event Router SMT) is declared in the service chart values and applied by the pipeline to the environment's Kafka Connect cluster through its REST API after registration and before the rollout. Connector configuration changes follow the same review and promotion path as the service.

```yaml
# deploy/environments/production/contract-service.yaml (written by the pipeline)
image:
  repository: <registry>/waver/contract-service
  digest: sha256:4f1c...e9a2          # promoted from staging, never rebuilt
chart:
  version: 1.14.0                     # chart built from the same commit
schemas:                              # registered in this environment's Apicurio before rollout
  - subject: waver.contract.contract.v1-value
    file: contracts/events/contract/ContractActivated.v1.avsc
    gitSha: 8c41d0b
release:
  version: 1.14.0
  gitSha: 8c41d0b
  temporalBuildId: contract-service@1.14.0
  promotedFrom: staging
```

### 3. Configuration vs secrets per environment
| Kind | Examples | Lives in | Change path |
|---|---|---|---|
| Code defaults | Timeouts, pool sizes, flag defaults | `application.yaml` inside the image | Code PR + build |
| Environment configuration (non-secret) | Kafka bootstrap address, DB host, Redis/Temporal/Apicurio/Qdrant endpoints, Vault role name, replica counts, platform flags | `deploy/environments/<env>/<service>.yaml` → ConfigMap | Config PR + pipeline (a deployment) |
| Static platform secrets | Kafka credentials, Redis ACL password, Qdrant API key, Keycloak client secret, Apicurio read credentials | Vault KV v2 at `kv/<env>/<service>/<purpose>` (ADR-0022), synced by the Vault Secrets Operator into a short-lived Kubernetes Secret mounted as files | Written to Vault by the pipeline secret stage or generated by Vault; rotated per STD-014 |
| Dynamic database credentials | Application role and migration owner role | Vault database secrets engine, roles `<service>-app` and `<service>-migrate`, TTL 1 h, renewed | Issued at runtime by Spring Cloud Vault (application) or at Job start (migrate); never stored |
| Signing and encryption keys | Outbound webhook signing, internal HMACs, tenant-credential encryption | Vault Transit keys; never leave Vault | Key creation and rotation by pipeline-applied Vault configuration |
| Tenant credentials | Customer OAuth tokens, API keys, BYOK keys | connector-service `Credential` (ciphertext from Vault Transit key `tenant-credentials`) | Product APIs only, never deployment config |
| Identity configuration | Keycloak realm `waver`, clients, protocol mappers, identity-provider templates, themes | `deploy/platform/keycloak/` applied through the Keycloak Admin API | Config PR + pipeline; no manual console changes in `staging`/`production` (ADR-0021) |
| Mesh and Vault policies | `AuthorizationPolicy`, `ServiceEntry`, waypoints, Vault policies and roles | Rendered from chart values and `deploy/platform/istio/`, `deploy/platform/vault/` | Config PR + pipeline (RFC-0015 §7, §13) |
| Agent product configuration | AgentVersion, Deployment of an agent | agent-builder-service data | Builder publish flow (RFC-0016) |

Rules:
1. The same configuration keys exist in every environment; only values differ. Code MUST NOT branch on the environment name.
2. Spring profiles are limited to `local` (developer machines) and `test`. Environment-specific `application-<env>.yaml` files inside images are forbidden.
3. Values files reference Vault paths and roles by name only. Static secrets synced by the Vault Secrets Operator are mounted as files under `/var/run/secrets/waver/` and imported with `spring.config.import=optional:configtree:/var/run/secrets/waver/`; dynamic database credentials are obtained and renewed by Spring Cloud Vault using Kubernetes auth (STD-014). No secret value is ever written to Git, Helm values or images.
4. A configuration change in `production` is a deployment: it is reviewed, promoted and recorded like an image change (AR-010). This includes Vault policies, Keycloak realm configuration and mesh policies.

### 4. Release strategy per plane type
| Plane type (MICROSERVICES_MAP) | Services | v1 strategy | Settings |
|---|---|---|---|
| Edge (stateless) | edge-gateway, control/partner/workspace-bff, `frontend-<app>` | Rolling | `maxSurge: 25%`, `maxUnavailable: 0`; BFF sessions are in Redis (Spring Session Data Redis, ADR-0020), so pods are interchangeable |
| Control plane | Commercial services, agent-builder, evaluation, tool-registry | Rolling | `maxSurge: 1`, `maxUnavailable: 0` |
| Data plane | agent-runtime, memory, model-gateway, handoff, tool-runtime, connector, knowledge, channel, trigger, workflow, scheduler | Rolling now; canary through Istio waypoints in phase 3 | `maxSurge: 25%`, `maxUnavailable: 0`, long grace period for in-flight runs and streams (RFC-0015), Kafka cooperative rebalancing |
| Shared foundation | tenant, identity-access, audit, notification, usage-metering, approval | Rolling | `maxSurge: 1`, `maxUnavailable: 0` |

Canary for the data plane (phase 3, proposal, ADR-0026):
1. Two Deployments of the same service, `<service>` (stable) and `<service>-canary`, each labelled with its version; two auxiliary Services `<service>-stable` and `<service>-canary` select them. The service is enrolled in an Istio **waypoint** (`istio.io/use-waypoint`), and a Gateway API `HTTPRoute` attached to the main `<service>` Service splits HTTP traffic by weight (start 5%, then 25%, 50%, 100%). Edge-routed traffic may also use weighted routes in edge-gateway.
2. Kafka share follows partition assignment within the shared consumer group (not weight-controlled). Temporal workers in canary pods register with the new build ID (worker versioning, ADR-0023); workflows already running stay on their build ID, and the new build ID becomes the default for new executions only on promotion.
3. Metrics, logs and traces carry the version label (RFC-0009), and waypoint L7 metrics split by destination version, so the pipeline compares canary vs stable error rate and p95 latency over a 30-minute window.
4. Abort = set the canary weight to 0 and scale the canary to zero. Promote = roll the stable Deployment to the canary digest, set the stable weight to 100% and remove the canary.
5. Mesh retries stay disabled during canary (ADR-0026); the application's Resilience4j policies are the only retry layer.
6. No progressive-delivery controller product is adopted; the pipeline adjusts the `HTTPRoute` weights. A controller would require an ADR.

Blue/green and `Recreate` are not used for services. Singleton behaviour never relies on running one replica: timers and long-running processes are Temporal workflows (ADR-0023), the outbox relay is a Debezium connector task (ADR-0024), and short in-process coordination uses Redis locks (ADR-0020).

### 5. Database migration ordering and expand/contract
1. Migrations are Flyway, forward-only, `V<yyyyMMddHHmm>__<description>.sql` (CONVENTIONS), stored inside the service.
2. Migrations run as a Kubernetes Job `<service>-migrate` that uses the same image digest in migrate-only mode, with its own ServiceAccount `<service>-migrate` and the migration owner role obtained from Vault as a dynamic credential (Vault role `<service>-migrate`, ADR-0010, ADR-0022). Application pods can never obtain owner credentials: their Vault policy only allows `<service>-app`.
3. Order per deployment: (a) schema registration in Apicurio (§2) → (b) migrate Job (expand only) MUST succeed → (c) Debezium connector configuration applied when it changed (§2) → (d) rolling update of the application. If any step fails, the application is not updated and the pipeline fails.
4. Every migration shipped with version N+1 MUST be compatible with version N, because rollback returns to N on the new schema.
5. Destructive changes (DROP, RENAME, type narrowing, NOT NULL on existing columns) are only allowed in a contract migration carrying the header `-- waver:contract expands=V<id>`, deployed at least one release and seven days after the switch release reached production. The migration linter (STD-012) enforces this.
6. Large-table operations use `CREATE INDEX CONCURRENTLY` (non-transactional script), `lock_timeout = '5s'`, and batched, idempotent, resumable backfills run as application jobs (or Temporal workflows when they must survive restarts), not inside the migration.
7. Every new tenant-owned table carries `org_id` and its RLS policy in the same migration (ADR-0010).
8. The `outbox_event` table is read by Debezium through logical replication. Changes to its columns follow expand/contract and MUST be released together with a compatible Outbox Event Router configuration; renaming or dropping a column that the router reads is a contract migration. Producing databases run with `wal_level = logical`; the replication slot and publication are created by platform provisioning, not by service migrations.

| Phase | Release | Schema | Code |
|---|---|---|---|
| Expand | R1 | Add nullable column / new table / index concurrently | Writes old and new; reads old |
| Backfill | R1 | — (batched job) | Same as above |
| Switch | R2 | Add constraint `NOT VALID`, then `VALIDATE` | Reads and writes new only |
| Contract | R3 (≥ 1 release and ≥ 7 days after R2) | Drop old column/table | — |

```sql
-- R1: V202610191200__add_contract_item_quantity.sql (expand)
ALTER TABLE contract_item ADD COLUMN quantity numeric(19,4);
-- R3: V202611101200__drop_contract_item_qty.sql
-- waver:contract expands=V202610191200
ALTER TABLE contract_item DROP COLUMN qty;
```

### 6. Feature flags: platform flags vs commercial FeatureFlag
| | Platform flag (release/operations) | Commercial `FeatureFlag` |
|---|---|---|
| Owner | The service team | entitlement-service (MICROSERVICES_MAP) |
| Purpose | Hide unfinished code, kill switch, operational tuning | Capability that is sold or contracted (AR-011) |
| Scope | Infrastructure environment | Organization / workspace / domain per entitlement |
| Storage | `waver.flags.<service>.<name>` in `deploy/environments/<env>/<service>.yaml` | entitlement-service database, propagated by events |
| Change | PR + pipeline (AR-010) | Contract → Entitlement flow, audited |
| Lifetime | Temporary: removed within 90 days of reaching 100% | Permanent product concept |

Rules:
1. A platform flag MUST NOT gate a commercially controlled capability, and a commercial `FeatureFlag` MUST NOT be used as a release toggle. Pilot access that is sold or promised to a customer is an entitlement.
2. Flag defaults in code are the safe value (off). Removing a flag is part of the definition of done of the feature.
3. Kill switches (e.g., disable one channel provider adapter) are platform flags; flipping one in production uses the expedited pipeline and is audited.
4. No feature-flag server or SaaS is introduced in v1; platform flags are not stored in Redis (Redis holds no configuration of record, ADR-0020). Adopting a flag server requires an ADR.

### 7. Rollback procedures
| Situation | Action | Target |
|---|---|---|
| New pods fail readiness / rollout exceeds `progressDeadlineSeconds` (600 s) | Pipeline reverts automatically to the previous digest | ≤ 15 min |
| Error rate or latency regression after a successful rollout | Owner triggers the pipeline `rollback` job: previous digest from `deploy/environments/production/<service>.yaml` history; skips build and staging gates, never signature verification | ≤ 15 min |
| Canary regression (phase 3) | Pipeline sets canary weight to 0 and scales the canary to zero | ≤ 5 min |
| Temporal non-determinism after a deployment (workflow tasks failing with a non-determinism error) | Roll back to the previous digest; the previous build ID's workers resume the affected executions; fix with `Workflow.getVersion` patching and replay tests before redeploying | ≤ 15 min |
| Bad configuration, platform flag, Vault policy, mesh policy or Keycloak realm change | Revert the config commit and promote | ≤ 15 min |
| Bad migration | Never roll back the schema; fix forward with a new migration (§5 makes N compatible) | — |
| Bad event schema registered | Registered schema versions are never deleted in `staging`/`production`; fix forward with a new compatible version (or a new major topic); consumers park undecodable records in `<topic>.dlq` | — |
| Malformed events published | Consumers park them in `<topic>.dlq`; fix forward; replay with the registry-aware replay tool (RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ) | — |
| Data corruption | Point-in-time restore into a new database instance, compare, repair through an audited job; restoring over a live database requires incident-commander approval | Tier RTO |
| Pipeline unavailable | Break-glass `helm rollback` by an on-call engineer with audited elevated access, then reconcile Git within 24 h | ≤ 30 min |

### 8. Independent deployment per service (AR-005)
1. No deployment ordering between services. When consumer and provider change together, the provider ships first in a backward-compatible form and the consumer adopts later; "deploy together" plans are rejected in review.
2. Each service has its own chart, migrations, schemas, Debezium connector configuration, Temporal workers, pipeline run, version and promotion record. Path-based CI builds only what changed (ADR-0015).
3. A change in `platform/*` or `waver-platform-bom` does not force a fleet-wide release; services adopt it within the cadence of STD-013, except security fixes with an SLA.
4. A change in `contracts/*` runs compatibility checks (OpenAPI diff; Avro compatibility against the environment's Apicurio Registry) and regenerates clients and Avro specific records; each consumer adopts the new artifact on its own schedule. Schemas are registered by the producer's release (§2), never by consumers.
5. Shared infrastructure components (§12) have their own pipelines under `deploy/platform/<component>/` and never ship together with a service release.

### 9. Version compatibility windows (AR-015)
Defaults; changing them requires an ADR.
| Contract | Window | Mechanism |
|---|---|---|
| Additive change (new optional field with default, new endpoint, new event type) | None | Tolerant readers ignore unknown fields; CI compatibility check; Apicurio `BACKWARD_TRANSITIVE` at registration |
| Internal REST major (`/v1` → `/v2`) | Provider serves both majors ≥ 90 days after `/v2` is in production and until all consumers migrated | `Deprecation` and `Sunset` headers; per-client call metrics |
| Public API (customer programmatic access) | ≥ 12 months after successor GA, with customer notice | Same, plus reseller/customer communication |
| Event major (`...v1` → `...v2` topic) | Producer dual-publishes both topics ≥ 90 days and until no consumer group reads `v1` | Outbox writes both events in one transaction; Debezium routes each row to its topic |
| Temporal workflow definitions | In-flight executions complete on the code they started with, or replay deterministically on the new code | Worker versioning (build ID per release); `Workflow.getVersion` patches; replay tests in CI (STD-012); old build-ID workers kept until their executions drain |
| Frontend ↔ BFF | BFF supports the current and previous frontend release | BFF contract tests against N and N-1 clients |
| Rollout skew | Every service works with neighbours at N-1 and N+1 | Previous-version compatibility test (Testing Strategy) |

### 10. Frontend release train
1. The Nx workspace (ADR-0027) produces three independently deployable images: `frontend-control`, `frontend-partner`, `frontend-workspace`, deployed in `waver-edge` and routed by edge-gateway on the same host as the matching BFF.
2. Default train: weekly cut from `main` (Tuesday), promoted dev → staging → production per app; unfinished features are behind platform flags. Hotfixes may leave the train through the same promotion path.
3. Nx `affected` decides which apps rebuild: a change in a `scope:shared` library (`ui-*`, `util-*`, shared `data-access-*`, auth, i18n) rebuilds all three apps; a change in a `scope:<app>` library rebuilds only that app. Each app still ships in its own train slot.
4. Assets use content-hashed filenames; `index.html` is served with `Cache-Control: no-cache`; the app detects a new version and asks the user to reload.
5. BFFs follow the service path (§8) and MUST stay compatible with the frontend release in production and the previous one (§9).

### 11. Disaster recovery targets (defaults, pending business validation)
| Tier | Services | RPO | RTO |
|---|---|---|---|
| T0 | edge-gateway, workspace-bff, identity-access-service | 5 min | 30 min |
| T1 | agent-runtime, model-gateway, tool-runtime, connector, channel, knowledge, memory, handoff, trigger, workflow, scheduler, entitlement, billing, usage-metering, audit, approval, tenant, control-bff, partner-bff | 5 min | 1 h |
| T2 | reseller, customer, catalog, marketplace, contract, provisioning, support, agent-builder, evaluation, tool-registry, notification | 15 min | 4 h |

| Shared component | Tier | RPO | RTO | Why |
|---|---|---|---|---|
| Vault | T0 | 5 min (Raft snapshots + replicated Raft) | 30 min | New pods cannot start and tenant credentials cannot be decrypted without it (ADR-0022) |
| Keycloak | T0 | 5 min (its PostgreSQL) | 30 min | No login or token issuance without it (ADR-0021) |
| Istio control plane (istiod, ztunnel, egress gateway) | T0 | n/a (state in Git) | 30 min | Mesh identity and authorization for every call (ADR-0026) |
| Temporal | T0 | 5 min (its PostgreSQL) | 30 min | Tier-0 for data-plane waits, tier-1 for commercial processes (ADR-0023) |
| Apicurio Registry | T1 | 5 min (its PostgreSQL) | 1 h | Producers and consumers cache schemas; new pods and new schema IDs need it |
| Kafka Connect + Debezium | T1 | 0 (WAL retained by the replication slot) | 1 h | Events are delayed, not lost, while it is down; WAL growth bounds the outage |
| Redis | T1 | n/a (non-authoritative) | 1 h | Loss degrades latency, sessions and rate limiting, never correctness (ADR-0020) |
| Qdrant | T1 | 24 h (snapshot) + reconciliation from PostgreSQL | 4 h (rebuild) | Rebuildable from PostgreSQL (ADR-0025) |

1. Targets apply to the loss of a zone or of a database instance. Region loss is covered only by restore from backups until multi-region is decided (Open Questions).
2. PostgreSQL (service databases and the Keycloak, Temporal and Apicurio databases): continuous WAL archiving with point-in-time recovery, daily full backup, 35-day retention, backups stored outside the cluster and outside the primary failure domain. Restore tests: monthly for T0/T1 databases, quarterly for T2.
3. Kafka: replication factor 3 and `min.insync.replicas=2` in staging and production, including the Kafka Connect internal topics (configs, offsets, status). Kafka is not a backup: PostgreSQL (state + outbox) is the system of record; read models are rebuilt by replay or snapshot APIs.
4. Cluster state is reproducible from Git (charts, environment values, `deploy/platform/*`). Secrets are not re-created from Git: Vault is restored from its encrypted Raft snapshot, after which dynamic credentials are re-issued automatically and the Vault Secrets Operator re-syncs static secrets.
5. The Apicurio Registry is restored from its database backup, never rebuilt by re-registering `.avsc` files: Avro records in Kafka reference schema IDs, which must be preserved.
6. Restore order: Istio control plane → Vault → Keycloak → identity-access, tenant → Apicurio Registry, Kafka Connect (connectors resume from their stored offsets and replication slots) → Temporal → entitlement, connector → Redis (empty start is acceptable), Qdrant (snapshot restore, then reconciliation or rebuild) → data plane → control plane → edge. audit-service verifies its hash chain after restore.

### 12. Shared infrastructure components: deployment, upgrade and backup
Each component lives under `deploy/platform/<component>/` (ADR-0015), is deployed by its own pipeline with images pinned by digest and charts pinned by version, follows `dev` → `staging` → `production`, and is owned by WAVER platform engineering. Whether it runs managed or in-cluster is open per component (RFC-0015 §9); with a managed offering, the rows below describe what WAVER still verifies.

| Component | Deployment (in-cluster) | Upgrade procedure | Backup / recovery |
|---|---|---|---|
| Keycloak (ADR-0021) | ≥ 2 replicas (production 3), Infinispan clustering, own PostgreSQL database; realm `waver`, clients, mappers, themes and the claims-mapper SPI applied by the pipeline through the Admin API | Patch releases rolling. Minor/major: rehearse in `staging`, run Keycloak's update compatibility check; if rolling is not supported, a planned short maintenance window (logins unavailable; issued tokens stay valid until expiry) after a database backup. Realm configuration re-applied and verified after every upgrade | PostgreSQL PITR; realm configuration in Git; signing keys rotate inside Keycloak (old keys kept in JWKS until tokens expire) |
| Redis (ADR-0020) | HA (Sentinel or cluster mode), TLS, ACL user per service, `maxmemory` with an eviction policy per use (`noeviction` for sessions/quota/idempotency, `allkeys-lru` only for cache-only instances) | Replicas first, then failover of the primary; clients tolerate failover. Major versions rehearsed in `staging`; Valkey is the drop-in fallback (D-37) | No backup required for correctness; optional RDB/AOF for faster warm-up. Quota counters are reconciled from PostgreSQL `quota_counter`; sessions lost → re-login |
| Vault (ADR-0022) | 3 (dev 1) or 5 nodes, integrated Raft, auto-unseal with the cloud KMS once chosen, audit device enabled, Vault Secrets Operator in the same namespace; policies, roles, engines and Transit keys as code in `deploy/platform/vault/` | One version at a time: snapshot first, upgrade standbys, then step down the active node; Autopilot health must be green between steps. OpenBao is the fallback (D-38) | Encrypted Raft snapshots every hour and before every upgrade or Transit key rotation, stored outside the cluster; recovery keys held in escrow by named holders (Open Questions); quarterly restore drill in `staging` |
| Temporal (ADR-0023) | Frontend, history, matching and internal worker services as separate Deployments; own PostgreSQL databases (persistence and visibility); namespaces `waver-commercial`, `waver-agent`, `waver-capability`, `waver-automation`, `waver-platform`; Temporal Web behind Keycloak SSO and Istio authorization | Minor versions sequentially (no skipping); database schema upgrade with Temporal's SQL tool as a Job before the server rollout; history shards count fixed at creation and never changed | PostgreSQL PITR for both databases; namespace and search-attribute definitions in Git |
| Apicurio Registry (ADR-0024) | ≥ 2 replicas, own PostgreSQL storage, Confluent-compatible API enabled, global rule `BACKWARD_TRANSITIVE`; write access only for the pipeline identity | Rolling; storage migrations run on start after a database backup | PostgreSQL PITR; schema IDs preserved (§11 rule 5) |
| Kafka Connect + Debezium (ADR-0024) | Distributed mode, ≥ 2 workers; one Debezium PostgreSQL connector per producing database with the Outbox Event Router SMT, `snapshot.mode=no_data` for outbox connectors, heartbeat enabled | Rolling restart of workers (tasks rebalance). Debezium upgrades rehearsed in `staging` against every producing database; connectors keep their stored offsets and replication slots | Connector configuration in Git; offsets in replicated Kafka topics; replication slots monitored (RUNBOOK_DEBEZIUM_REPLICATION_SLOT) |
| Qdrant (ADR-0025) | ≥ 3 nodes, replication factor ≥ 2 per shard; collections per owning service, API key per service | Rolling, one node at a time with replicas healthy; snapshot before upgrade. Re-embedding never upgrades a collection in place: a new versioned collection is filled by a Temporal ingestion workflow and swapped by alias | Daily collection snapshots stored outside the cluster; rebuild from PostgreSQL is the authoritative recovery (quarterly drill, ADR-0025) |
| Istio ambient (ADR-0026) | istiod (≥ 2 replicas), ztunnel DaemonSet, istio-cni, waypoints where needed, egress gateway; configuration rendered from Git | Revision-based canary for istiod (`istio.io/rev` tags), namespaces switched to the new revision after verification; ztunnel and CNI upgraded node by node with node drains (a ztunnel restart interrupts connections on that node); `istioctl` pre-check before and analysis after every upgrade | No data to back up; all configuration in Git |

Rules:
1. A shared component upgrade is a deployment: PR, `dev` → `staging` → `production`, with the component's runbook updated in the same PR.
2. Component upgrades and service releases are never combined; T0 component upgrades run with on-call presence and outside change-freeze windows.
3. Every component exports metrics to the observability backend and has alerts linked to runbooks (STD-015); Vault, Debezium and Kafka consumers have dedicated runbooks (RUNBOOK_VAULT_SEALED_OR_UNAVAILABLE, RUNBOOK_DEBEZIUM_REPLICATION_SLOT, RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ).
4. Supported versions: each component stays within the vendor's supported release lines; security patches follow STD-013 SLAs.

## APIs / Events / Data Ownership
No business APIs or events are added. Deployment metadata is owned by the pipeline and stored in Git (`deploy/environments/<env>/`, `deploy/platform/`). Pods carry `app.kubernetes.io/version`, and telemetry carries the version label. Agent `DeploymentActivated`/`DeploymentRolledBack` events belong to agent-builder-service and are unrelated to infrastructure deployments. The registered schema versions in each environment's Apicurio Registry are derived from Git and recorded in the promotion commit.

## Security & Privacy
- Only the pipeline identity can change workloads, Vault configuration, Keycloak realm configuration, mesh policies and registry schemas in `staging` and `production`; humans have read access, plus audited break-glass (RFC-0015).
- The author of a change MUST NOT be the only approver of its production promotion.
- Signed images and SBOMs for every production digest, including shared component images; admission rejects unsigned images.
- Vault clusters, Transit keys, Keycloak signing keys and mesh roots of trust are per environment; production personal data never leaves production.
- Promotion commits, approvals, break-glass sessions and Vault audit logs are audit evidence (AR-009).

## Reliability & Failure Modes
| Failure | Behaviour |
|---|---|
| Migration Job fails | Application not rolled; alert; fix forward |
| Schema registration rejected (incompatible) | Deployment stops before the migrate Job; fix the schema or create a new major |
| New version never becomes ready | `maxUnavailable: 0` keeps old pods serving; automatic revert |
| Registry (container) unavailable | Running pods unaffected; new deploys blocked; scale-out uses images cached on nodes |
| Vault unavailable during a deployment | New pods cannot obtain credentials and never become ready; `maxUnavailable: 0` keeps old pods serving; pipeline aborts (RUNBOOK_VAULT_SEALED_OR_UNAVAILABLE) |
| Kafka Connect down | Events delayed, WAL retained by the slot; alert on slot lag (RUNBOOK_DEBEZIUM_REPLICATION_SLOT) |
| Pipeline unavailable | Break-glass rollback (§7) |
| Bad event schema in production | DLQ, fix forward, replay; CI compatibility check against the registry is the primary prevention |

## Observability
- The pipeline emits a deployment marker (service or component, environment, version, digest, actor) to the observability backend (RFC-0009).
- Delivery metrics per service: deployment frequency, lead time, change-failure rate, time to restore.
- Dashboards compare error rate and latency before/after each deployment, split by version label; during canary, waypoint L7 metrics split by destination version.

## Testing Strategy
- Gates per STD-012 (unit, ArchUnit, Nx module boundaries, contract compatibility including Avro against Apicurio, Temporal workflow replay, Testcontainers integration, mesh and Vault policy validation, scans, E2E in `dev`/preview, smoke in `staging` and `production`).
- Previous-version compatibility test: apply migrations of N+1, then run N's integration suite against that schema.
- Rollback drill per T0/T1 service quarterly in `staging`; DR restore tests per §11, including a Vault snapshot restore, a Qdrant collection rebuild and a Kafka Connect restart with replication-slot catch-up each quarter.
- Upgrade rehearsal of every shared component in `staging` before `production`.

## Deployment / Rollout / Rollback
- Phase 0: library chart, `deploy/environments/*`, `deploy/platform/*` for Istio, Vault, Keycloak, Redis, Temporal, Apicurio, Kafka Connect and Qdrant in `dev`; pipeline promotion, schema registration and rollback jobs.
- Phase 1: `dev` and `staging` for the first vertical slice.
- Phase 2: `production` after a rollback drill, a restore test and a Vault restore drill pass.
- Phase 3: data-plane canary through waypoints.
Rollback of this RFC: none needed; it only constrains how services and shared components ship.

## Alternatives Considered
- Pull-based GitOps controller: good drift detection, but a new product; may be proposed by ADR later.
- Mutable environment tags: rejected; cannot prove staging = production.
- Fleet-wide release trains for services: rejected; recreates a distributed monolith.
- Flyway undo migrations: rejected; CONVENTIONS mandates forward-only.
- Feature-flag server: not adopted in v1; would require an ADR.
- Blue/green for everything: doubles capacity and does not help Kafka consumers.
- Canary by replica ratio behind one Service (v1.2 proposal): no precise traffic control; replaced by waypoint `HTTPRoute` weights (ADR-0026).
- Schemas registered by services at runtime (`auto.register.schemas=true`): rejected; schemas are reviewed code and registration is a pipeline step (ADR-0024).
- Platform secrets as pipeline-created Kubernetes Secrets (v1.2, ADR-0014): replaced by Vault (ADR-0022).

## Risks
- Expand/contract discipline erodes → migration linter and review checklist.
- Long compatibility windows mean dual maintenance → per-client usage metrics to retire old majors early.
- Push-based deployment can drift from Git → periodic diff job between Git values and cluster state (including Vault policies, Keycloak realm export and mesh resources).
- Loss of Vault data together with its seal/recovery material makes all tenant credentials unrecoverable → hourly encrypted Raft snapshots, protected KMS key, recovery-key escrow (Open Questions).
- Temporal non-determinism after a code change blocks in-flight executions → mandatory replay tests and worker versioning.
- Many tier-0 shared components increase the operational surface → upgrade rehearsals, runbooks and quarterly DR drills per component.

## Open Questions
1. Multi-region and data residency (Scope §24): is Brazil-only processing or regional isolation required? Decides cluster count, Kafka topology and region-level DR.
2. Cloud provider ADR: managed PostgreSQL PITR, managed KMS for Vault auto-unseal, backup storage location, and which shared components run managed vs in-cluster (RFC-0015 §9).
3. Can anonymized production-derived datasets be used in `staging`? Needs DPO approval.
4. How frontend static assets are served (small Spring Boot static server vs a generic web-server image, which would need an ADR).
5. Named approvers for production promotion and break-glass.
6. Automatic rollback on SLO burn after a successful rollout (depends on the observability backend).
7. Business validation of RPO/RTO defaults and of SLAs promised to resellers.
8. Change-freeze windows (e.g., month-end invoicing in billing-service).
9. Vault recovery-key escrow procedure and named key holders (replaces the v1.2 KEK escrow question).
10. Licensing confirmation for Redis (D-37) and Vault (D-38); the fallbacks Valkey and OpenBao keep the same deployment design.

## Decision
Pending review. Proposed for ACCEPTED when the first vertical slice has completed a `dev` → `staging` → `production` promotion by digest (including schema registration), one rollback drill, one point-in-time restore test and one Vault restore drill.
