# Architecture Requirements

Revision 1.3 (2026-10-19): verification mechanisms aligned with ADR-0020..0027 (adopted Vault dynamic credentials, Istio AuthorizationPolicy, Temporal replay tests, Avro compatibility against Apicurio Registry, Nx module boundaries, Redis quota counters). The requirements themselves are unchanged.

All ARs are ACCEPTED and non-negotiable (Master Appendix A). Each AR file contains Rationale, Rules, Verification and Exceptions.

| ID | Title | Requirement | Main verification mechanism | Implemented by |
|---|---|---|---|---|
| AR-001 | Service and Layer Isolation | Every business capability belongs to an explicit bounded context. | ArchUnit layering/package rules + CI check that `services/*` match MICROSERVICES_MAP and CODEOWNERS | RFC-0001, RFC-0002, ADR-0007 |
| AR-002 | Database Ownership | No microservice accesses another service database. | Per-service Vault database roles (dynamic credentials) + migration linter (no `dblink`/`postgres_fdw`) + chart values schema + Redis ACL prefixes / Qdrant API keys per service | RFC-0007, RFC-0015, ADR-0003, ADR-0022 |
| AR-003 | Implementation Isolation | No service imports another service internal implementation. | Maven Enforcer ban on cross-`services/*` dependencies + ArchUnit + Nx module boundaries + Istio `AuthorizationPolicy` (runtime) | ADR-0015, ADR-0027, STD-013, RFC-0015 |
| AR-004 | Cross-Service Communication | Only versioned APIs or versioned asynchronous events cross service boundaries. | Generated clients only (ArchUnit) + contract lint + registry-only schemas + Istio `AuthorizationPolicy` from the dependency graph (runtime) + NetworkPolicy allow-list + Kafka ACLs | RFC-0005, RFC-0006, RFC-0008, RFC-0015, ADR-0024, ADR-0026 |
| AR-005 | Independent Lifecycle | Each service owns migrations, tests, deployment and observability. | CI service-manifest check + path-based pipeline + per-service promotion by digest | RFC-0014, STD-012, ADR-0015 |
| AR-006 | Idempotency | Events and commands with side effects must be idempotent. | Platform inbox/`Idempotency-Key` + test kit duplicate-delivery assertions + Temporal workflow replay and activity re-execution tests | ADR-0023, ADR-0024, RFC-0006 |
| AR-007 | Domain Purity | Domain layer is independent from infrastructure/frameworks. | Maven Enforcer on `-domain` modules + ArchUnit import rules | ADR-0007, STD-001 |
| AR-008 | Tenant Context | Tenant context is propagated and validated on calls and events. | Tenancy test kit (A cannot read B, extended to Qdrant) + RLS + migration linter + `ce_orgid` header check | ADR-0010, RFC-0003, RFC-0004, ADR-0025 |
| AR-009 | Auditability | Sensitive actions require authorization and audit trail. | ArchUnit permission-annotation rule + `assertAudited` test kit + audit hash-chain verification + Vault audit device | RFC-0004, RFC-0010, ADR-0022 |
| AR-010 | Versioned Production | Editable configuration never changes production without version/deployment. | Runtime rejects drafts (contract tests) + immutable version tables + production RBAC/Git drift check (incl. Vault, Keycloak, mesh, registry) | RFC-0011, RFC-0014, RFC-0016 |
| AR-011 | Entitlement Authority | Entitlements are technical authority for commercially controlled capabilities. | ArchUnit single `EntitlementPort` + E2E revocation propagation test + Redis/PostgreSQL quota reconciliation | RFC-0013, RFC-0017, ADR-0020 |
| AR-012 | Safe Suspension | Suspension is reversible and distinct from termination/purge. | Exhaustive lifecycle-state tests + Temporal workflow/replay tests + suspend/reactivate round-trip test + purge approval guard | RFC-0017, ADR-0023 |
| AR-013 | Plane Isolation | Data Plane does not depend on admin UI availability for already-authorized workloads. | Quarterly game day (control plane scaled to zero) + ArchUnit client allow-list | RFC-0011, RFC-0015 |
| AR-014 | Failure Isolation | Cross-service failures use timeout/retry/circuit breaking/outbox where applicable. | Platform HTTP client factory (ArchUnit) + WireMock fault-injection tests + outbox-only publishing (Debezium) + mesh retries disabled | RFC-0005, RFC-0006, STD-008, ADR-0024, ADR-0026 |
| AR-015 | Compatibility | Public contracts and schemas follow compatibility/versioning policy. | CI OpenAPI diff + Avro compatibility check against Apicurio Registry (`BACKWARD_TRANSITIVE`) + Temporal replay tests + consumer contract tests | ADR-0024, ADR-0023, RFC-0014, STD-003, STD-004 |

## Exceptions process
1. Open a PR adding a row to the register below: AR, service, scope, reason, compensating control, owner, expiry (≤ 6 months unless the AR states a shorter limit).
2. Approval: WAVER Architecture plus the CODEOWNERS of the affected services (and Security for AR-008, AR-009, AR-015 security fixes).
3. Code covered by an exception references its ID (e.g., `// AR-EXC-0003`) so automated checks can allow-list it.
4. CI fails when an allow-listed exception is past its expiry date.
5. A permanent deviation requires an ADR; some ARs (AR-002 in production, AR-008, AR-012) admit no time-boxed exception.

## Exceptions register
| ID | AR | Service | Scope | Reason | Compensating control | Owner | Expiry |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — |
