# RFC-0015 - Kubernetes Platform Architecture
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Istio ambient, HashiCorp Vault with the Vault Secrets Operator, and platform namespaces for Keycloak, Redis, Temporal, Apicurio Registry, Kafka Connect + Debezium and Qdrant).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines the Kubernetes platform for WAVER: cluster topology per infrastructure environment, one namespace per plane plus platform namespaces for shared infrastructure, workload standards (probes, resources, autoscaling, disruption budgets, spreading), the Istio ambient service mesh (STRICT mTLS, `AuthorizationPolicy` per service generated from the dependency graph, waypoints, egress gateway with `ServiceEntry` allow-lists), default-deny NetworkPolicies as a second layer with an allowed matrix by plane, service accounts and RBAC, secrets delivered by Vault (ADR-0022), the managed vs in-cluster decision for PostgreSQL, Kafka and each shared component, the Helm chart standard, image policy, quotas and observability agents. Provider-neutral: only Kubernetes-native APIs, Istio and Vault are mandated; products that are not yet chosen are named as open decisions.

## Context / Problem
- ADR-0001 fixes Docker and Kubernetes; cloud, managed services, CI, registry and observability backend are open (TECH_STACK). The service mesh is decided: Istio in ambient mode (ADR-0026).
- MICROSERVICES_MAP v1.3 assigns each service to a plane and a plane type (edge, control, data, shared foundation) and lists the shared infrastructure components (Keycloak, Redis, Vault, Temporal, Apicurio Registry, Kafka Connect + Debezium, Qdrant, Istio). AR-013 and DEPENDENCY_RULES require that data-plane load cannot take down the control plane and vice versa.
- ADR-0009 terminates TLS at the ingress in front of edge-gateway. ADR-0014 (ownership rules, kept by ADR-0022) makes connector-service a high-security service with stricter policies and an optional dedicated node pool. ADR-0017 makes model-gateway-service the only egress to LLM providers.
- ADR-0022 replaces Kubernetes Secrets created by the pipeline with Vault (Kubernetes auth, Vault Secrets Operator, dynamic PostgreSQL credentials, Transit).
- RFC-0002 §4 defines the allowed synchronous dependency graph; ADR-0026 requires one `AuthorizationPolicy` per destination service generated from it.
- RFC-0014 defines environments, tiers (T0/T1/T2), promotion and the lifecycle of shared components; this RFC defines what runs inside each cluster.

## Goals
1. Plane isolation enforced by namespaces, mesh authorization, NetworkPolicies, quotas and node pools.
2. Secure-by-default workloads: non-root, read-only root filesystem, signed images, STRICT mTLS, no implicit network access, no secrets outside Vault.
3. One chart standard so every service is deployed the same way, and its mesh and Vault policies are generated from the same declared dependencies.
4. Autoscaling that matches the workload (CPU for HTTP, Kafka lag for consumers).

## Non-goals
- Choosing a cloud provider, ingress controller product, CNI, registry or observability backend.
- Choosing managed vs in-cluster for each shared component (open, §9).
- Promotion, release strategy and component upgrade procedures (RFC-0014).
- Istio features not needed by ADR-0026 (multi-cluster mesh, sidecar mode).

## Requirements / ARs
| Requirement | Mechanism in this RFC |
|---|---|
| AR-002 Database ownership | Vault database role per service (dynamic credentials only for its own database); Redis ACL per key prefix; Qdrant API key per collection set; NetworkPolicy egress only to its PostgreSQL endpoint |
| AR-003 / AR-004 Implementation isolation / cross-service communication | Istio `AuthorizationPolicy` per destination service generated from RFC-0002 §4 (runtime control); NetworkPolicy allow-list as second layer |
| AR-005 Independent lifecycle | One chart per service; per-service ServiceAccount, Vault role, AuthorizationPolicy, HPA, PDB |
| AR-013 Plane isolation | Namespace per plane, ResourceQuota, optional dedicated node pools, mesh authorization by plane matrix |
| AR-014 Failure isolation | Probes that do not cascade, PDBs, topology spread, mesh retries disabled (application retries only) |
| ADR-0022 | Vault Kubernetes auth, Vault Secrets Operator, dynamic database credentials, Transit; no raw Kubernetes Secrets authored by humans or the pipeline |
| ADR-0026 | Ambient enrollment of every `waver-*` namespace, STRICT `PeerAuthentication`, waypoints where L7 is needed, egress gateway with `ServiceEntry` allow-lists |
| ADR-0017 | Internet egress only through the egress gateway, for model-gateway-service, connector-service, channel-service (plus restricted hosts for Keycloak SSO brokering and billing-service) |

## Proposed Design

### 1. Cluster topology
| Environment | Clusters | Zones | Node pools |
|---|---|---|---|
| `dev` (+ `preview-pr-<n>`) | 1 | 1 allowed | `system`, `general` |
| `staging` | 1 | 3 | Same pools as production, smaller |
| `production` | 1 per region (1 in v1) | 3 | `system`, `general`, optional `dataplane`, optional `restricted`, optional `stateful` |

- `system`: ingress controller, DNS, observability agents, admission components, istiod, Vault and the Vault Secrets Operator. The ztunnel and istio-cni DaemonSets run on every node of every pool.
- `general`: edge, commercial, platform and control-plane workloads; Keycloak, Temporal, Apicurio Registry and Kafka Connect.
- `dataplane` (optional, recommended once chat volume is material): agent-runtime and the hot-path data-plane services. Taint `waver.io/pool=dataplane:NoSchedule`.
- `restricted` (optional, recommended before the first coded connector, ADR-0014/ADR-0016): connector-service and `connector-runner-<group>` only. Taint `waver.io/pool=restricted:NoSchedule`; no other workloads tolerate it.
- `stateful` (optional, only for in-cluster data services): Redis, Qdrant, and PostgreSQL/Kafka if run in-cluster (§9). Taint `waver.io/pool=stateful:NoSchedule`; local or zonal persistent volumes.

### 2. Namespaces
| Namespace | Workloads | Pod Security | Ambient mesh |
|---|---|---|---|
| `waver-edge` | edge-gateway, control-bff, partner-bff, workspace-bff, `frontend-<app>` | restricted | enrolled |
| `waver-commercial` | reseller, customer, catalog, marketplace, contract, entitlement, provisioning, billing, support | restricted | enrolled |
| `waver-agent` | agent-builder, agent-runtime, memory, evaluation, model-gateway, handoff | restricted | enrolled |
| `waver-capability` | tool-registry, tool-runtime, connector, knowledge, channel (+ future `connector-runner-<group>`) | restricted | enrolled |
| `waver-automation` | trigger, workflow, scheduler | restricted | enrolled |
| `waver-platform` | tenant, identity-access, audit, notification, usage-metering, approval | restricted | enrolled |
| `waver-infra-keycloak` | Keycloak (ADR-0021) | restricted | enrolled |
| `waver-infra-vault` | Vault server, Vault Secrets Operator (ADR-0022) | restricted | enrolled (Vault keeps its own TLS listener end to end) |
| `waver-infra-temporal` | Temporal frontend, history, matching, internal worker, Temporal Web (ADR-0023) | restricted | enrolled |
| `waver-infra-streaming` | Apicurio Registry, Kafka Connect workers with the Debezium connectors (ADR-0024) | restricted | enrolled |
| `waver-data-redis` | Redis, only if in-cluster (§9, ADR-0020) | baseline | enrolled |
| `waver-data-qdrant` | Qdrant, only if in-cluster (§9, ADR-0025) | baseline | enrolled |
| `waver-data-postgres`, `waver-data-kafka` | Only if in-cluster data services are chosen (§9) | baseline | enrolled |
| `istio-system` | istiod, ztunnel, istio-cni (ADR-0026) | privileged (node networking) | not enrolled (mesh infrastructure) |
| `istio-egress` | Istio egress gateway and the egress `ServiceEntry` resources | restricted | enrolled |
| `preview-pr-<n>` (dev only) | Changed frontends/BFFs of one PR | restricted | enrolled |

Rules:
1. Every application namespace carries `waver.io/plane=<plane>`; platform namespaces carry `waver.io/plane=infra` (or `data` for `waver-data-*`). All carry `pod-security.kubernetes.io/enforce=<level>` as above.
2. Every `waver-*`, `istio-egress` and `preview-pr-<n>` namespace carries `istio.io/dataplane-mode=ambient`. Removing the label or opting a pod out (`istio.io/dataplane-mode=none`) is rejected by admission (§8).
3. Shared components are platform components, not bounded contexts: they own no business data, and each WAVER service gets its own isolated slice in them (MICROSERVICES_MAP).
4. Components that run managed (§9) have no namespace. Managed endpoints in the private network are reached directly with NetworkPolicy `ipBlock` rules and TLS (§6); managed endpoints on public hosts are reached through the egress gateway like any external host (§13).

### 3. Labels (mandatory, set by the library chart)
`app.kubernetes.io/name: <service>`, `app.kubernetes.io/version: <version>`, `app.kubernetes.io/part-of: waver`, `waver.io/plane: <edge|commercial|agent|capability|automation|platform>`, `waver.io/plane-type: <edge|control|data|foundation>`, `waver.io/tier: <t0|t1|t2>`. `waver.io/egress-profile: internet` is only admitted on model-gateway-service, connector-service, channel-service and `connector-runner-*`; `waver.io/egress-profile: restricted` only on Keycloak (customer IdP brokering) and billing-service (enforced by admission, §8). Services enrolled in a waypoint additionally carry `istio.io/use-waypoint: <service>-waypoint` on their Service.

### 4. Workload standards
Rules:
1. Every service is a `Deployment` (no bare Pods, no StatefulSets for application services). Migrations are a `Job` (RFC-0014 §5). Timers and long-running processes are Temporal workflows run by the owning service's workers (ADR-0023), not CronJobs. Shared data components (Vault, Redis, Qdrant, in-cluster PostgreSQL/Kafka) may use StatefulSets.
2. Application port `8080`, management port `8081`. Probes use Spring Boot Actuator health groups on the management port:
   - `startupProbe`: `/actuator/health/liveness`, `failureThreshold: 30`, `periodSeconds: 5` (JVM start).
   - `livenessProbe`: `/actuator/health/liveness`. MUST NOT include databases, Kafka or other services.
   - `readinessProbe`: `/actuator/health/readiness`. Includes the service's own PostgreSQL; MUST NOT include other services, Kafka, Redis, Temporal, Qdrant, Keycloak or Vault, so one dependency outage does not remove whole fleets from load balancing. A pod that cannot obtain its initial Vault credentials fails to start (fail closed) instead of becoming ready.
3. Graceful shutdown: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`, `preStop` sleep 10 s, `terminationGracePeriodSeconds: 45` (agent-runtime and channel-service: 120 to drain streams and in-flight turns). Temporal workers stop polling first and let running activities finish or heartbeat-time-out within the grace period.
4. JVM sizing from container limits: `-XX:MaxRAMPercentage=75`. Memory limit = memory request. No CPU limit by default (avoids throttling during JIT/GC), except in the `restricted` pool where CPU limit = 2 × request.
5. Production minimum replicas: T0 = 3, T1 = 2 (agent-runtime 3), T2 = 2. `dev` and previews: 1.
6. PodDisruptionBudget for every Deployment with ≥ 2 replicas: `maxUnavailable: 1`. Shared components define their own PDBs (Vault and Qdrant: never more than one node down).
7. Topology spread: zones with `whenUnsatisfiable: DoNotSchedule` (staging/production) and hostnames with `ScheduleAnyway`.

| Default resources (production) | CPU request | Memory request = limit |
|---|---|---|
| T0 | 500m | 1Gi |
| T1 | 500m | 1Gi (agent-runtime: 1000m / 2Gi) |
| T2 | 250m | 768Mi |
| `frontend-<app>` | 50m | 128Mi |
| `<service>-migrate` Job | 250m | 512Mi |
| Waypoint proxy (per enrolled service) | 100m | 256Mi |
| `dev` / preview (all) | 100m | 512Mi |
Shared component sizing is defined in their values under `deploy/platform/<component>/` and reviewed at each capacity review.

### 5. Autoscaling
1. HTTP-serving services: HorizontalPodAutoscaler on CPU, target 70% of request.
2. Kafka-consuming data-plane services (agent-runtime, tool-runtime, channel, knowledge ingestion, workflow, notification): HPA on consumer-group lag through the Kubernetes External Metrics API, target lag per replica set per service (default 1000 records). The metrics adapter that serves this API is an open product choice tied to the observability backend; until it exists, these services scale on CPU.
3. Temporal worker pools may scale on task-queue backlog (schedule-to-start latency) through the same External Metrics API once the adapter exists.
4. `maxReplicas` of a Kafka consumer MUST NOT exceed the partition count of its main topic (extra replicas would sit idle).
5. Scale-down stabilization window 300 s to avoid rebalance storms.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: agent-runtime, namespace: waver-agent }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: agent-runtime }
  minReplicas: 3
  maxReplicas: 24            # <= partitions of the main input topic
  behavior: { scaleDown: { stabilizationWindowSeconds: 300 } }
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
    - type: External
      external:
        metric: { name: kafka_consumergroup_lag, selector: { matchLabels: { group: agent-runtime } } }
        target: { type: AverageValue, averageValue: "1000" }
```

### 6. NetworkPolicies (second layer)
Istio `AuthorizationPolicy` (§13) is the primary, identity-based control; NetworkPolicies remain as an independent L3/L4 layer, so a mesh misconfiguration alone never opens a path.

Rules:
1. Every `waver-*` namespace has a default-deny policy for ingress and egress.
2. Every pod may egress to cluster DNS, to the Keycloak token/JWKS endpoints (`waver-infra-keycloak`), to Vault (`waver-infra-vault`), to the observability collector, to Kafka (9092–9094), to its own PostgreSQL endpoint (5432) and to the shared components it declares in its chart values (Redis, Temporal frontend, Apicurio Registry, Qdrant). Managed endpoints outside the cluster are expressed as `ipBlock` CIDRs; in-cluster ones via namespace selectors.
3. Ambient mode carries mesh traffic between ztunnels and waypoints over HBONE (port 15008): policies that allow a path also allow port 15008 for it, and kubelet health probes (which ambient rewrites to a link-local source address) are allowed. The library chart renders these rules; services never write them by hand.
4. Service-to-service ingress is allowed per destination service from an explicit list of client services (`dependencies.allowedClients` in chart values), derived from the allowed synchronous dependency graph (RFC-0002 §4, the "Main sync dependencies" of MICROSERVICES_MAP) and from ADR-0009 audience rules (workspace-bff never reaches marketplace, catalog pricing or reseller-service). The same list renders the service's `AuthorizationPolicy` (§13).
5. No application pod has direct internet egress. Only the egress gateway pods in `istio-egress` may egress to `0.0.0.0/0` minus private and cluster CIDRs on port 443; pods labelled `waver.io/egress-profile` may reach the egress gateway. Host-level allow-lists are `ServiceEntry` resources plus `AuthorizationPolicy` on the egress gateway (§13). notification-service reaches e-mail/WhatsApp only through channel-service. Application-level SSRF protection stays in connector-service (ADR-0016), and model-gateway keeps its provider endpoint configuration (ADR-0017).
6. Two narrow egress exceptions (decided in the v1.2 review, see SECURITY_BASELINE, and adjusted in v1.3): **Keycloak** may reach the customer IdP hosts configured per Organization, because SSO federation is performed by Keycloak identity brokering (ADR-0021); identity-access-service only approves the configuration and pushes it through the Keycloak Admin API, so it has no internet egress. **billing-service** may reach the chosen payment-gateway and fiscal-provider hosts. Both carry `waver.io/egress-profile: restricted`, port 443 only, through the egress gateway with one Istio `ServiceEntry` per approved host; they never get the `internet` profile.

Allowed matrix by plane (source → destination; everything else denied). The same matrix bounds the generated `AuthorizationPolicy` resources:
| Source \ Destination | edge | commercial | agent | capability | automation | platform |
|---|---|---|---|---|---|---|
| ingress controller | edge-gateway only | — | — | — | — | — |
| edge | gateway → BFFs, frontends | BFFs per audience | BFFs per audience | BFFs per audience; gateway → connector, channel (inbound webhooks) | gateway → trigger (inbound webhooks) | BFFs per audience; identity-access |
| commercial | — | per deps | — | — | — | tenant, identity-access, approval |
| agent | — | entitlement (builder only) | per deps | tool-runtime, tool-registry, knowledge (builder, runtime); connector (model-gateway, BYOK) | — | identity-access, approval |
| capability | — | entitlement (tool-runtime, cached) | model-gateway (knowledge) | per deps | — | identity-access, approval |
| automation | — | — | agent-runtime | tool-runtime | per deps | identity-access |
| platform | — | — | — | channel (notification) | — | per deps |
| evaluation (agent) | — | — | agent-runtime (sandbox) | — | — | — |

Shared infrastructure paths (all planes, only for services that declare them): → Keycloak (token/JWKS; identity-access-service also the Admin API), → Vault, → Redis, → Temporal frontend (services owning workflows, ADR-0023), → Apicurio Registry (read), → Qdrant (knowledge-service, memory-service only). Kafka Connect → producing PostgreSQL databases (replication) and Kafka; the pipeline → Apicurio (write), Kafka Connect REST, Keycloak Admin API, Vault.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: contract-service-ingress, namespace: waver-commercial }
spec:
  podSelector: { matchLabels: { app.kubernetes.io/name: contract-service } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector: { matchLabels: { waver.io/plane: edge } }
          podSelector: { matchExpressions: [{ key: app.kubernetes.io/name, operator: In, values: [control-bff, partner-bff] }] }
      ports: [{ port: 8080 }, { port: 15008 }]   # 15008 = ambient HBONE
```

### 7. Service accounts, RBAC and secrets
1. One ServiceAccount per service, `automountServiceAccountToken: false`. Application workloads have no Kubernetes API permissions. The only token mounted is a projected ServiceAccount token with audience `vault` and a short expiry, used for Vault Kubernetes auth. The migrate Job has its own ServiceAccount `<service>-migrate`.
2. The pipeline deployer has one identity per environment with a namespaced Role limited to the `waver-*` namespaces and the resources the charts create. Humans: read-only in `staging`/`production`; break-glass elevation is time-boxed and audited.
3. Platform secrets come from Vault (ADR-0022, STD-014); neither humans nor the pipeline author Kubernetes Secret objects in `waver-*` namespaces.
   - Each ServiceAccount maps to one Vault Kubernetes-auth role (`<service>`, `<service>-migrate`) bound to that ServiceAccount and namespace, with a Vault policy generated from chart values and reviewed as code (`deploy/platform/vault/policies/`).
   - Static secrets (KV v2: Kafka credentials, Redis ACL password, Qdrant API key, Keycloak client secret) are synced by the **Vault Secrets Operator** (`VaultStaticSecret`) into short-lived Kubernetes Secrets owned by the operator, mounted read-only as files under `/var/run/secrets/waver/`, never as environment variables; the operator restarts the Deployment on change (`rolloutRestartTargets`). Encryption at rest of the Kubernetes secret store remains mandatory (managed KMS once the cloud is chosen).
   - Dynamic PostgreSQL credentials (TTL 1 h, renewed) are obtained by the application through Spring Cloud Vault from role `database/creds/<service>-app`; the migrate Job alone can read `database/creds/<service>-migrate`.
   - Transit: connector-service may encrypt/decrypt with the `tenant-credentials` key; model-gateway-service and channel-service may only decrypt the leased credential kinds (`LLM_PROVIDER_KEY`, `CHANNEL_PROVIDER_TOKEN`) as defined in ADR-0022; connector-service signs outbound webhooks with a Transit signing key.
4. Tenant credentials never appear as Kubernetes Secrets; connector-service stores only Transit ciphertext in its own database (ADR-0014 ownership rules, ADR-0022 mechanism).
5. Kafka: one principal per service with ACLs limited to the topics it produces and the consumer groups it owns (details in RFC-0006). Producing services do not write to Kafka directly for domain events: the Debezium connector of their database does, with its own principal limited to that service's topics.
6. Redis: one ACL user per service restricted to `~waver:<prefix>:*` (ADR-0020). Qdrant: one API key per owning service restricted to its collections (ADR-0025). Temporal: workers reach only the Temporal frontend, and namespace-level authorization restricts each service to its plane's Temporal namespace (Open Questions).

### 8. Image and admission policy
1. Images are referenced by digest from the WAVER registry prefix only (shared component images are mirrored into it); `latest` is rejected.
2. Images are built multi-stage on a minimal JRE base (ADR-0007), run as non-root UID 10001, `readOnlyRootFilesystem: true` with an `emptyDir` for `/tmp`, `allowPrivilegeEscalation: false`, capabilities `drop: [ALL]`, `seccompProfile: RuntimeDefault`.
3. Pod Security Admission `restricted` is enforced on every application and `waver-infra-*` namespace. Kubernetes `ValidatingAdmissionPolicy` (CEL) enforces: digest-only images; the registry prefix; mandatory labels; the `waver.io/egress-profile` allow-list; the ambient enrollment label on namespaces and the absence of pod-level opt-outs; `PeerAuthentication` only in mode `STRICT`; mesh resources (`AuthorizationPolicy`, `ServiceEntry`, waypoints, `HTTPRoute`) only from the pipeline identity; and no `Secret` objects in application namespaces other than those owned by the Vault Secrets Operator.
4. Image signature verification at admission requires an admission webhook product (open). Until chosen, the pipeline verifies the signature immediately before deploying (STD-012).

### 9. Data services and shared components: managed vs in-cluster (open decision)
| Option | Pros | Cons |
|---|---|---|
| Managed (cloud) | HA, patching and backups operated by the provider | Depends on the cloud ADR (D-24); cost; feature parity per component varies |
| In-cluster (Helm chart or operator) | Portable, available before the cloud decision | WAVER operates storage, failover, upgrades and backups (RFC-0014 §12); operator products other than those allowed by ADR-0020..0027 need an ADR |

| Component | Managed option | In-cluster option | Recommendation (pending D-24) |
|---|---|---|---|
| PostgreSQL | Managed PostgreSQL with PITR and logical replication | Operator (needs an ADR) | Managed |
| Kafka | Managed Kafka | Operator (needs an ADR) | Managed |
| Kafka Connect + Debezium | Managed Kafka Connect, if it accepts the Debezium PostgreSQL connector and SMTs | Distributed workers in `waver-infra-streaming` | In-cluster unless the managed offering supports the Outbox Event Router |
| Apicurio Registry | — (a managed Confluent-compatible registry would need an ADR) | `waver-infra-streaming` on managed PostgreSQL | In-cluster |
| Redis | Managed Redis/Valkey with TLS and ACLs | Redis Operator or Sentinel in `waver-data-redis` | Managed |
| Vault | Managed Vault (vendor-hosted) | Raft cluster in `waver-infra-vault`, auto-unseal with cloud KMS | Open: in-cluster keeps secrets inside the WAVER boundary; managed reduces tier-0 operations |
| Keycloak | — | `waver-infra-keycloak` on managed PostgreSQL | In-cluster |
| Temporal | Temporal Cloud (would need an ADR: SaaS, data residency) | `waver-infra-temporal` on managed PostgreSQL (ADR-0023) | In-cluster |
| Qdrant | Managed Qdrant | StatefulSet in `waver-data-qdrant` | Open: depends on data-residency requirements |
| Istio | Provider-managed Istio add-on, if it supports ambient mode | `istio-system` | In-cluster unless the managed add-on supports ambient and revision upgrades |

Either way: database per service (`waver_<service>`) with its own roles managed by Vault's database secrets engine; separate PostgreSQL instances at least per plane type, a dedicated instance for agent-runtime, and separate instances for the shared components' databases (Keycloak, Temporal, Apicurio Registry), so data-plane load cannot starve control-plane databases (AR-013). knowledge-service and memory-service no longer need dedicated instances for vector load (vectors moved to Qdrant, ADR-0025). Every instance hosting a producing database runs with `wal_level = logical` and has slot monitoring (ADR-0024).

### 10. Helm chart standard (proposal)
1. One chart per service in `deploy/helm/<service>/` (ADR-0015), depending on one shared library chart that renders Deployment, Service, HPA, PDB, NetworkPolicies, `AuthorizationPolicy`, optional waypoint enrollment, ServiceAccount, Vault Secrets Operator resources, the Vault role/policy fragment, ConfigMap, migrate Job, the Debezium connector configuration and probes. Services only provide values; templates in a service chart need review by platform owners.
2. Each chart has `values.schema.json`; CI fails on unknown or missing values, and on `dependencies` entries that are not edges of RFC-0002 §4. Per-environment values live in `deploy/environments/<env>/<service>.yaml`.
3. Shared components live in `deploy/platform/<component>/` (ADR-0015) and are owned by platform engineering.

```yaml
# deploy/helm/contract-service/values.yaml
service: { name: contract-service, plane: commercial, planeType: control, tier: t2 }
resources: { cpu: 250m, memory: 768Mi }
autoscaling: { minReplicas: 2, maxReplicas: 6, cpuTarget: 70 }
migrations: { enabled: true }
dependencies:                         # renders NetworkPolicy + Istio AuthorizationPolicy (must be ⊆ RFC-0002 §4)
  allowedClients: [control-bff, partner-bff, provisioning-service, billing-service]
  dependsOn: [catalog-service, marketplace-service, approval-service]
platform:                             # shared components this service uses
  redis: { prefix: contract }         # ACL user restricted to waver:contract:*
  temporal: { namespace: waver-commercial, taskQueues: [contract-service.renewal] }
  registry: { read: true }
vault:
  role: contract-service              # Kubernetes auth role bound to this ServiceAccount
  database: { role: contract-service-app }
  staticSecrets: [kafka, redis, keycloak-client]   # synced by the Vault Secrets Operator, mounted as files
outbox:
  debezium: { enabled: true }         # Outbox Event Router connector for waver_contract
mesh:
  waypoint: false                     # true only where L7 is needed (§13)
```

### 11. Resource quotas (initial production defaults)
| Namespace | requests.cpu | requests.memory | limits.memory | pods |
|---|---|---|---|---|
| `waver-edge` | 8 | 16Gi | 16Gi | 60 |
| `waver-commercial` | 8 | 24Gi | 24Gi | 80 |
| `waver-agent` | 24 | 64Gi | 64Gi | 150 |
| `waver-capability` | 16 | 40Gi | 40Gi | 120 |
| `waver-automation` | 6 | 12Gi | 12Gi | 50 |
| `waver-platform` | 8 | 20Gi | 20Gi | 70 |
| `waver-infra-*` (each) | 4 | 8Gi | 8Gi | 30 |
| `waver-data-*` (each, in-cluster only) | Sized per component at capacity review | | | |
Each namespace also has a `LimitRange` applying the T2 defaults to containers without explicit values. Quotas include waypoint proxies of the namespace. Quotas are reviewed at each capacity review; `dev` and previews use 25% of these values.

### 12. Ingress and observability agents
- Ingress: TLS terminates at the ingress controller (product open) in front of edge-gateway (ADR-0009); Gateway API resources are preferred if the chosen controller supports them, otherwise `Ingress`. Hosts: one per experience, one for the public API, one for inbound webhooks. Traffic from the ingress controller into the mesh is authorized to reach edge-gateway, channel/connector webhook endpoints and trigger-service only.
- Observability: a node-level log collector (DaemonSet) reading JSON logs from stdout (STD-006), a cluster telemetry collector receiving traces and metrics in open formats (RFC-0009), cluster-state and Kafka consumer-lag metrics, Istio telemetry (ztunnel L4 metrics, waypoint and egress-gateway L7 metrics and access logs), Vault audit logs, Debezium/Kafka Connect metrics and Temporal server metrics. Products are chosen with the observability backend ADR; services need no change.

### 13. Service mesh: Istio ambient (ADR-0026)
1. **Enrollment and mTLS.** All namespaces listed as enrolled in §2 run in ambient mode. A mesh-wide `PeerAuthentication` in `istio-system` sets `mtls.mode: STRICT`; no namespace may lower it. Workload identities are SPIFFE IDs derived from ServiceAccounts (`cluster.local/ns/<namespace>/sa/<service>`), which is why every service has its own ServiceAccount (§7).
2. **Default deny.** Each enrolled namespace has an empty `AuthorizationPolicy` (`allow-nothing`); traffic is allowed only by explicit policies.
3. **Per-service authorization.** The library chart renders one `AuthorizationPolicy` per destination service from `dependencies.allowedClients` (§10), which CI validates against RFC-0002 §4 and the plane matrix (§6). The observability collector is allowed on the management port 8081 only. A call outside the graph is denied by the mesh even if a NetworkPolicy were misconfigured, and vice versa.
4. **Shared components** carry policies generated from the services that declare them in `platform:` values (e.g., only workflow-owning services reach the Temporal frontend; only knowledge-service and memory-service reach Qdrant; only the pipeline identity may write to Apicurio, Kafka Connect REST and the Keycloak Admin API, plus identity-access-service on the Admin API).
5. **Waypoints (L7)** are deployed per service only where L7 is needed: request-level authorization on connector-service, identity-access-service and model-gateway-service (allowed paths/methods per client), and traffic splitting for data-plane canaries (RFC-0014 §4). Services without a waypoint are authorized at L4 by ztunnel.
6. **Resilience.** Mesh retries are disabled (`retries.attempts: 0` on every generated route); timeouts at the mesh level are only an outer safety net, longer than the application deadline (RFC-0005). Applications keep Resilience4j retries, timeouts and circuit breakers (AR-014).
7. **Egress gateway.** The Istio egress gateway runs in `istio-egress`. Each allowed external host is a `ServiceEntry` (port 443, TLS origination by the application) in `istio-egress`, rendered from `deploy/platform/istio/egress/` (owned by platform engineering and Security in CODEOWNERS), and an `AuthorizationPolicy` on the gateway lists which ServiceAccounts may reach which hosts: model-gateway-service → LLM provider hosts; connector-service and `connector-runner-*` → hosts declared by approved connector manifests; channel-service → messaging/e-mail provider hosts; Keycloak → approved customer IdP hosts (SSO brokering); billing-service → payment-gateway and fiscal-provider hosts. A host without a `ServiceEntry` is not routed through the gateway, and NetworkPolicy (§6 rule 5) blocks any direct attempt from an application pod, so it is unreachable.
8. **Hosts approved at runtime** (a customer's SSO IdP, a tenant-specific connector endpoint) cannot wait for a manual edit. In v1 they are added through the expedited configuration path (RFC-0014 §3) triggered by the approval in the owning service; the long-term mechanism is an Open Question.

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata: { name: contract-service, namespace: waver-commercial }   # generated by the library chart
spec:
  selector: { matchLabels: { app.kubernetes.io/name: contract-service } }
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - cluster.local/ns/waver-edge/sa/control-bff
              - cluster.local/ns/waver-edge/sa/partner-bff
              - cluster.local/ns/waver-commercial/sa/provisioning-service
              - cluster.local/ns/waver-commercial/sa/billing-service
      to:
        - operation: { ports: ["8080"] }
```

## APIs / Events / Data Ownership
No business APIs or events. Chart values are owned by each service team; the library chart, admission policies, quotas, namespace definitions, mesh-wide policies, egress `ServiceEntry` resources, Vault engines/policies and the shared components under `deploy/platform/` are owned by WAVER platform engineering (Security co-owns Vault policies and egress allow-lists).

## Security & Privacy
STRICT mTLS for every in-mesh connection, identity-based authorization generated from the dependency graph, default-deny networking as a second layer, Pod Security `restricted`, non-root read-only containers, per-service identities, Vault-issued short-lived credentials (no long-lived database passwords), tenant credentials only as Transit ciphertext, internet egress only through the egress gateway with host allow-lists, connector code isolated in the `restricted` pool, no Kubernetes API access for applications, audited break-glass, Vault audit device enabled.

## Reliability & Failure Modes
| Failure | Behaviour |
|---|---|
| Zone loss | Topology spread + PDB keep ≥ 1 replica per zone; managed DB fails over; Vault/Qdrant/Redis keep quorum across zones |
| Data-plane surge | Data-plane quota/node pool exhausts first; control plane unaffected (AR-013) |
| Downstream service down | Readiness unaffected (rule 4.2); callers use timeouts/circuit breakers (AR-014) |
| Kafka lag metrics unavailable | HPA keeps last scale and CPU metric still applies |
| Node drain | PDB `maxUnavailable: 1` and graceful shutdown; ztunnel on the drained node stops with it |
| istiod unavailable | Existing ztunnel/waypoint configuration and certificates keep working until certificate expiry; no policy changes or new workloads until it returns (T0, RFC-0014 §11) |
| ztunnel crash on a node | Pods on that node lose mesh connectivity until it restarts (DaemonSet); other nodes unaffected |
| Vault unavailable | Running pods keep their current credentials until expiry; new pods fail to start; Transit operations fail closed (RUNBOOK_VAULT_SEALED_OR_UNAVAILABLE) |
| Mesh policy error | Calls denied (fail closed); revert the policy commit (RFC-0014 §7) |

## Observability
Cluster dashboards per namespace (quota usage, restarts, throttling, OOM kills), per-service golden signals by version, HPA decisions and consumer lag, mesh dashboards (mTLS coverage, denied requests per policy, egress-gateway traffic per host), Vault health and lease metrics, Debezium replication-slot lag. Alerts link to runbooks (STD-015).

## Testing Strategy
- Chart CI: schema validation, template rendering, policy tests (rendered manifests checked against the admission rules), `dependencies` ⊆ RFC-0002 §4, `istioctl analyze` on rendered mesh resources, Vault policy lint.
- NetworkPolicy and mesh tests in `staging`: a probe pod per plane (with its own ServiceAccount) asserts allowed and denied paths of the matrix; a call outside the dependency graph must be denied by the mesh with NetworkPolicies temporarily permissive in a test namespace, and by NetworkPolicy with the mesh policy removed (each layer proven independently).
- Egress test: each egress profile reaches only its allow-listed hosts; any other host is blocked.
- Game day per quarter in `staging`: drain a zone; stop control-plane namespaces and verify data-plane SLOs (AR-013); restart Vault and istiod and verify documented degradation.

## Deployment / Rollout / Rollback
Platform components (namespaces, quotas, admission policies, library chart, Istio, Vault and the shared components) are versioned in `deploy/` and promoted like services (RFC-0014 §12). New admission policies run in audit mode for one week in `staging` before enforcement. New or changed generated `AuthorizationPolicy` resources are validated by the matrix tests in `staging` before promotion. Ambient enrollment and STRICT mTLS are in place from the first environment (no PERMISSIVE phase is needed for a greenfield platform).

## Alternatives Considered
- Namespace per service: finer RBAC, but plane-level quotas and policies get harder; labels already give per-service policies.
- Cluster per plane: strongest isolation, high cost; revisit if AR-013 cannot be met.
- No service mesh, NetworkPolicy-only with CIDR-based internet egress (v1.2): fewer components, but no workload identity, no mTLS and no host-level egress control; replaced by Istio ambient (ADR-0026). NetworkPolicies are kept as a second layer.
- Istio sidecar mode or Linkerd: see ADR-0026.
- Pipeline-created Kubernetes Secrets (v1.2, ADR-0014): replaced by Vault and the Vault Secrets Operator (ADR-0022).
- One `waver-infra` namespace for every shared component: simpler, but mixes Vault (tier-0 secrets) with other workloads in one RBAC and quota scope; rejected in favor of one namespace per component.
- Kustomize instead of Helm: workable; Helm chosen for the shared library chart and packaging (proposal).

## Risks
- Runtime-approved external hosts (customer IdPs, tenant connector endpoints) need fast `ServiceEntry` changes → expedited path in v1, automated mechanism open.
- ztunnel upgrades interrupt connections per node → node-by-node upgrades with drains (RFC-0014 §12).
- Vault is tier-0 for pod start and credential use → HA, snapshots, runbook, credential TTLs long enough to ride out short outages.
- No admission-time signature check until a product is chosen.
- Quotas set too low throttle legitimate growth → alerts at 80% quota.

## Open Questions
1. Cloud provider, managed Kubernetes, managed PostgreSQL/Kafka/Redis, KMS for Vault auto-unseal, and the managed vs in-cluster choice per shared component (§9, D-24).
2. ~~Egress for SSO federation and billing-service (payment/fiscal)~~ — resolved: restricted, host-allow-listed egress through the egress gateway; SSO egress now belongs to Keycloak, not identity-access-service (§6 rule 6, §13).
3. ~~Location of the shared library chart~~ — resolved: `deploy/helm/waver-service-lib/` added to ADR-0015.
5. Products for the ingress controller, external-metrics adapter, admission signature verification and CNI. Whether an Istio ingress gateway (Gateway API) should replace a separate ingress controller is evaluated with the controller choice.
6. Whether `connector-runner-<group>` runs in `waver-capability` or in its own namespace.
7. Automated management of runtime-approved egress hosts (§13 rule 8): a platform-owned reconciler of approved hosts into `ServiceEntry` resources vs wildcard entries per provider domain.
8. Temporal authorization: namespace-level authorization with Keycloak-issued service tokens vs mesh authorization only; Temporal does not enforce task-queue ownership natively (ownership is enforced by ArchUnit and review, ADR-0023).
9. Exact Vault Transit key layout that restricts model-gateway-service and channel-service to their leased credential kinds (ADR-0022).

## Decision
Pending review. Proposed for ACCEPTED after the cloud ADR, once `staging` passes the NetworkPolicy and mesh matrix tests, the egress tests and one AR-013 game day.
