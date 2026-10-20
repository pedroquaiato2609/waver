# AR-004 - Cross-Service Communication
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro + Apicurio Registry with CloudEvents binary mode, Keycloak tokens, Istio mTLS and AuthorizationPolicy, Redis and Temporal boundaries).

## Requirement
Only versioned APIs or versioned asynchronous events cross service boundaries.

## Rationale
Explicit, versioned contracts are what make independent deployment (AR-005) and compatibility management (AR-015) possible. REST serves immediate decisions; Kafka events serve change propagation and decoupled processes (ADR-0004).

## Rules
1. Synchronous calls use REST with a contract in `contracts/openapi/<service>/v<N>.yaml` and paths `/v{major}/{resource}` (CONVENTIONS, RFC-0008).
2. Asynchronous integration uses Kafka events with the CloudEvents envelope in binary mode (`ce_*` Kafka headers) and an Avro schema in `contracts/events/<context>/<EventName>.v<N>.avsc`, registered in Apicurio Registry by the pipeline, on topics `waver.<context>.<aggregate>.v<major>` (ADR-0024, RFC-0006).
3. Contracts are written and reviewed before the implementation (contract-first).
4. Forbidden channels: shared databases or tables, shared file systems, another service's Redis keys or Qdrant collections (shared infrastructure is partitioned per service, ADR-0020, ADR-0025), Kafka topics without a registered schema, starting or signalling another service's Temporal workflows (ADR-0023), calls to another service's actuator or non-contract endpoints.
5. Events are past-tense facts; commands between services are REST calls, not events (CONVENTIONS).
6. Every internal call travels over the mesh's mutual TLS with the caller's workload identity (ADR-0026) and carries a Keycloak-issued service token or an exchanged user token (ADR-0021); there are no anonymous internal endpoints.
7. Only the owning service publishes to its topics, through the Debezium outbox connector of its own database (ADR-0024).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Clients only from contracts | ArchUnit: outbound HTTP only through generated clients built by the platform HTTP client factory | `waver-platform-test`, RFC-0005 |
| Contracts present and valid | CI lint of OpenAPI and Avro (`.avsc`) files | STD-003, STD-004, STD-012 |
| No unregistered events | Serializers run with `auto.register.schemas=false`, so writing an outbox row for a schema not registered in Apicurio fails; only the pipeline can register schemas; Kafka ACLs per service principal and per Debezium connector principal | `waver-platform-starter`, ADR-0024, RFC-0006, RFC-0015 §7 |
| Network paths (runtime) | Istio `AuthorizationPolicy` per destination service generated from the RFC-0002 §4 dependency graph (STRICT mTLS identities); NetworkPolicy allow-list as second layer | RFC-0015 §6, §13, ADR-0026 |
| Compatibility | CI compatibility checks (AR-015) | STD-012 |

## Exceptions
Register in the Exceptions register in `AR_INDEX.md` (scope, reason, compensating control, owner, expiry ≤ 6 months), approved by WAVER Architecture and the CODEOWNERS of both services. A new integration style (e.g., gRPC, WebSocket between services) requires an ADR.
