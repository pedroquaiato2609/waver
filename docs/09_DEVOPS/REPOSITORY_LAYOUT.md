# Repository Layout
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro schemas in `contracts/events`, the Nx frontend workspace and `deploy/platform/` for Keycloak, Vault, Redis, Temporal, Apicurio Registry, Kafka Connect + Debezium, Qdrant and Istio).
Version: 1.3
Date: 2026-10-19

Summary only. The decision is **ADR-0015 Monorepo with Enforced Boundaries** (single Git monorepo). The layout below is reproduced exactly from ADR-0015:

```text
waver/
  docs/                                 # this pack
  contracts/
    openapi/<service>/v<N>.yaml
    events/<context>/<Event>.v<N>.avsc   # Avro schemas, registered in Apicurio by CI (ADR-0024)
    connectors/connector-manifest.schema.json
    connectors/<connector-key>/manifest.v<N>.yaml   # declarative connectors (ADR-0016, RFC-0018)
    clients/<service>-client/           # published client libraries owned by a service (ADR-0015)
  platform/
    java/waver-platform-bom/            # managed versions
    java/waver-domain-kernel/           # framework-free shared value types: UUIDv7 ids, Money, tenancy ids, ErrorCode (allowed in domain modules)
    java/waver-platform-starter/        # tenancy, outbox/inbox, problem+json, security, observability
    java/waver-platform-test/           # Testcontainers, tenancy test kit, ArchUnit rules
    java/waver-connector-sdk/           # SDK for coded connectors (ADR-0016)
  services/<service>/                   # one Maven multi-module project per service
  frontend/                             # Nx workspace (ADR-0027)
    apps/control/ apps/partner/ apps/workspace/
    libs/<scope>/<type>-<name>/         # scope: control|partner|workspace|shared; type: feature|data-access|ui|util
  deploy/
    helm/waver-service-lib/             # shared Helm library chart (probes, security context, HPA, PDB, NetworkPolicy, Istio AuthorizationPolicy, Vault and Debezium fragments)
    helm/<service>/                     # one chart per service
    environments/<env>/                 # values per environment
    platform/                           # shared infrastructure as code (v1.3)
      keycloak/  vault/  redis/  temporal/  apicurio/  kafka-connect/  qdrant/  istio/
  tooling/
```

Boundary rules (ADR-0015):
- A service may depend on `platform/*` and on artifacts generated from `contracts/*` (OpenAPI clients, Avro specific records), never on another `services/*` module (Maven Enforcer; AR-003).
- Path-based CI: only changed services build, test and deploy; each service keeps its own version and release (STD-012, RFC-0014). In `frontend/`, Nx `affected` selects the apps and libraries to build and test (ADR-0027).
- Frontend libraries carry scope tags (`scope:control|partner|workspace|shared`) and type tags (`feature|data-access|ui|util`); Nx `enforce-module-boundaries` forbids cross-app imports, and Angular Material is used only inside `ui-*` wrapper libraries (ADR-0027, AR-003).
- Avro schemas in `contracts/events/` are the source of truth for event payloads; the pipeline checks them against Apicurio Registry and registers them at release (ADR-0024, STD-012).
- `deploy/platform/<component>/` holds the charts, values, policies and configuration-as-code of the shared infrastructure components (e.g., Vault policies, Keycloak realm export, Istio mesh-wide and egress resources); each component has its own pipeline and is never released together with a service (RFC-0014 §12). The per-service `AuthorizationPolicy`, Vault role/policy fragment and Debezium connector configuration are rendered from each service's chart values by the library chart (RFC-0015 §10).
- CODEOWNERS per service directory, per `contracts/*` directory and per `deploy/platform/<component>/` (Security co-owns Vault, Istio and Keycloak configuration) (STD-011).
- `waver-platform-starter` contains no domain code; platform owners review every change.

Related: ADR-0007 (Maven modules per service), ADR-0027 (Nx workspace), ADR-0024 (Avro contracts), STD-015 (doc pack structure), RFC-0015 §10 (Helm chart standard), RFC-0014 §12 (shared components).
