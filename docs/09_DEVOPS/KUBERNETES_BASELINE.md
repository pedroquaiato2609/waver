# Kubernetes Baseline
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Istio ambient, HashiCorp Vault with the Vault Secrets Operator, and platform namespaces for Keycloak, Redis, Temporal, Apicurio Registry, Kafka Connect + Debezium and Qdrant).
Version: 1.3
Date: 2026-10-19

Summary only. The authoritative design is **RFC-0015 Kubernetes Platform Architecture**; environments, promotion, rollback, shared-component upgrades and DR are in **RFC-0014 Deployment Architecture**.

| Concern | Baseline (details in) |
|---|---|
| Clusters | One cluster per infrastructure environment (`dev` + previews, `staging`, `production`); multi-zone in staging/production (RFC-0015 §1) |
| Namespaces | One per plane: `waver-edge`, `waver-commercial`, `waver-agent`, `waver-capability`, `waver-automation`, `waver-platform`; platform namespaces `waver-infra-keycloak`, `waver-infra-vault`, `waver-infra-temporal`, `waver-infra-streaming` (Apicurio Registry, Kafka Connect + Debezium), `waver-data-redis` / `waver-data-qdrant` (only if in-cluster), `istio-system`, `istio-egress` (RFC-0015 §2) |
| Node pools | `system`, `general`; optional `dataplane` (agent-runtime), `restricted` (connector-service) and `stateful` (in-cluster data services) pools (RFC-0015 §1) |
| Workloads | Deployment per service, Actuator liveness/readiness/startup probes (readiness never includes other services or shared components), graceful shutdown, defaults per tier T0/T1/T2 (RFC-0015 §4) |
| Autoscaling | HPA on CPU; Kafka-lag (and optionally Temporal task-queue) scaling via the External Metrics API (adapter product open) (RFC-0015 §5) |
| Availability | PDB `maxUnavailable: 1`, zone/host topology spread (RFC-0015 §4) |
| Service mesh | Istio ambient on every `waver-*` namespace; STRICT mTLS; default-deny plus one `AuthorizationPolicy` per service generated from the RFC-0002 §4 dependency graph; waypoints only for L7 needs (connector, identity-access, model-gateway, data-plane canary); mesh retries disabled (RFC-0015 §13, ADR-0026) |
| Network | NetworkPolicies as second layer: default-deny, plane allow-matrix, HBONE port 15008 allowed on permitted paths; no direct internet egress from application pods (RFC-0015 §6) |
| Egress | Istio egress gateway in `istio-egress` with one `ServiceEntry` per allowed host; internet profile for model-gateway-service, connector-service, channel-service; restricted host lists for Keycloak (customer IdPs, SSO brokering) and billing-service (payment/fiscal providers) (RFC-0015 §6, §13) |
| Identity | One ServiceAccount per service (plus `<service>-migrate`), no Kubernetes API access for apps, projected token only for Vault auth; pipeline-only writes in staging/production (RFC-0015 §7) |
| Secrets | HashiCorp Vault: Kubernetes auth, dynamic PostgreSQL credentials (Spring Cloud Vault), static secrets synced by the Vault Secrets Operator and mounted as files, Transit for tenant credentials and signing; no human- or pipeline-authored Kubernetes Secrets (ADR-0022, STD-014) |
| Images | Digest-only, signed, non-root, read-only root filesystem, Pod Security `restricted` (RFC-0015 §8) |
| Admission | `ValidatingAdmissionPolicy`: digests, registry prefix, labels, egress-profile allow-list, ambient enrollment, STRICT `PeerAuthentication`, mesh resources and Secrets only from the pipeline / Vault Secrets Operator (RFC-0015 §8) |
| Packaging | One Helm chart per service on a shared library chart (renders NetworkPolicy, `AuthorizationPolicy`, Vault role/policy fragment, Debezium connector config); values per environment; shared components in `deploy/platform/<component>/` (RFC-0015 §10, ADR-0015) |
| Quotas | ResourceQuota + LimitRange per namespace, including `waver-infra-*` (RFC-0015 §11) |
| Data services and shared components | Managed vs in-cluster is open per component (PostgreSQL, Kafka, Kafka Connect, Apicurio, Redis, Vault, Keycloak, Temporal, Qdrant, Istio); recommendations pending the cloud decision (RFC-0015 §9) |
| Rollout / rollback / DR | Rolling by default, canary via Istio waypoints for the data plane (phase 3), promotion by digest, schema registration before rollout, expand/contract migrations, RPO/RTO defaults per tier and per shared component (RFC-0014) |

Open: cloud provider and managed vs in-cluster per component, ingress controller (or Istio ingress gateway), metrics adapter, admission signature verification, CNI, automation of runtime-approved egress hosts (TECH_STACK, RFC-0015 Open Questions).
