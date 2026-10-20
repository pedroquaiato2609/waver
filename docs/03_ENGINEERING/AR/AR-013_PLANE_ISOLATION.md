# AR-013 - Plane Isolation
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis rate limiting and quota counters, Istio AuthorizationPolicy; shared infrastructure components treated as tier-0 platform, not control plane).

## Requirement
Data Plane does not depend on admin UI availability for already-authorized workloads.

## Rationale
Customer conversations, tool executions and channel deliveries are the product in production. A control-plane outage (Builder, commercial services, portals) or a burst of admin activity must not stop them, and chat load must not make contracts and administration unavailable (DEPENDENCY_RULES, Master §10.1).

## Rules
1. Data-plane services (plane type "data" in MICROSERVICES_MAP) keep serving already-authorized workloads when control-plane services, BFFs and admin UIs are down.
2. They do so with local read models built from events: active Deployments and AgentVersions, ToolVersions, entitlement snapshots, connection metadata.
3. No synchronous call from a data-plane hot path to a control-plane service, except calls served from a local cache with a documented fallback (e.g., tool-runtime → tool-registry).
4. A data-plane service starts from its own database without any control-plane service being available.
5. New authorizations (a new Deployment, a new entitlement) may wait for the control plane; existing ones continue.
6. Planes run in separate namespaces with separate quotas, optional dedicated node pools and separate PostgreSQL instances (RFC-0015).
7. Edge rate limits (`RedisRateLimiter` in edge-gateway) and quotas (Redis counters of usage-metering-service) protect control-plane operations from data-plane surges and vice versa; if Redis is unavailable they fall back to local limiters and the PostgreSQL counters (ADR-0020).
8. Shared infrastructure components used by the data plane (Keycloak, Vault, Redis, Temporal, Apicurio Registry, Kafka Connect, Qdrant, Istio) are platform components, not control-plane services: they are operated with tier-0/tier-1 targets (RFC-0014 §11), and their failure behaviour for the data plane is defined in ADR-0020..0026. Control-plane outages never reach them.

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Static dependencies | ArchUnit: data-plane hot-path modules have no generated clients of control-plane services except the allow-listed cached ones | `waver-platform-test`, RFC-0005 |
| Degraded dependencies | Integration tests with WireMock returning 503/timeouts for every control-plane dependency | STD-010 |
| Game day | Quarterly in `staging`: scale control-plane namespaces to zero; synthetic conversation suite must meet data-plane SLOs | RFC-0015 Testing Strategy |
| Resource isolation | Namespaces, ResourceQuotas, node pools, Istio `AuthorizationPolicy` and NetworkPolicy matrix | RFC-0015 §1, §6, §11, §13 |
| Load | Load test: data-plane surge does not breach control-plane SLOs | RFC-0011, STD-010 |

## Exceptions
A new synchronous data-plane → control-plane dependency must be proposed in the service RFC with cache, staleness bound and fallback, and registered in the Exceptions register in `AR_INDEX.md` until the allow-list in the ArchUnit rule is updated. Approval: WAVER Architecture.
