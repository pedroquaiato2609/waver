# ADR-0015 - Monorepo with Enforced Boundaries
Status: PROPOSED
Revision 1.3 (2026-10-19): `contracts/events` now holds Avro schemas (ADR-0024); `frontend/` is an Nx workspace (ADR-0027); `deploy/platform/` holds the shared infrastructure (Keycloak, Vault, Redis, Temporal, Apicurio, Kafka Connect, Qdrant, Istio).
Date: 2026-10-19

## Context
REPOSITORY_LAYOUT left monorepo vs multi-repo open. Contracts (OpenAPI, event schemas) must be shared; implementations must not (AR-003).

## Decision
A single Git monorepo:

```text
waver/
  docs/                                 # this pack
  contracts/
    openapi/<service>/v<N>.yaml
    events/<context>/<Event>.v<N>.avsc   # Avro schemas, registered in Apicurio by CI (ADR-0024)
    connectors/connector-manifest.schema.json
    connectors/<connector-key>/manifest.v<N>.yaml   # declarative connectors (ADR-0016, RFC-0018)
    clients/<service>-client/           # published client libraries owned by a service (see below)
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

- A service may depend on `platform/*` and on clients generated from `contracts/*`, never on another `services/*` module.
- Path-based CI: only changed services build, test and deploy; each service keeps its own version and release.
- CODEOWNERS per service directory.

## Alternatives
- Multi-repo: stronger physical isolation, but contract changes need multi-repo coordination and versions drift.

- **Published client libraries:** a service may publish a versioned client artifact under `contracts/clients/<service>-client/` that contains its generated API client **and** read-only logic that interprets its own published language (e.g., `entitlement-client` evaluates entitlement snapshots locally in data-plane services). The owning team is the only writer; consumers depend on it like on any contract. It must not contain the owner's domain model or persistence.

## Consequences
- `waver-platform-starter` must contain **no domain code**; platform owners review every change.

## Compliance / Verification
- Maven Enforcer rule bans dependencies between `services/*`.

## Supersedes / Superseded by
Closes the open point in REPOSITORY_LAYOUT.
