# AR-001 - Service and Layer Isolation
Status: ACCEPTED

## Requirement
Every business capability belongs to an explicit bounded context.

## Rationale
A capability without a single owning context ends up implemented in several services. That produces a distributed monolith: shared data, lockstep releases and unclear accountability (Master §9, ADR-0002). Inside each service, Clean/Hexagonal layering keeps business rules testable and keeps infrastructure replaceable (Master §10).

## Rules
1. Every aggregate in the "Owns" column of `04_ARCHITECTURE/MICROSERVICES_MAP.md` has exactly one owning service. A new aggregate MUST be added to the map in the same PR that introduces it (RFC-0002).
2. A new service MUST be approved through an RFC (or an RFC-0002 amendment) and added to MICROSERVICES_MAP before its code is merged. Names follow CONVENTIONS (`<context>-service`, `agent-runtime`, `edge-gateway`, `*-bff`).
3. One directory `services/<service>/` = one bounded context; the Java base package is `br.com.waver.<context>`.
4. Each service is a Maven multi-module project `<service>-domain`, `<service>-application`, `<service>-adapters`, `<service>-bootstrap` (ADR-0007); dependencies point inward (DEPENDENCY_RULES).
5. BFFs and edge-gateway MUST NOT contain business rules or own business data (ADR-0009).
6. `platform/*` MUST NOT contain domain code (ADR-0015).
7. Separate engines inside one deployable (objective-engine, flow-engine) MUST NOT depend on each other (ADR-0019).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Service list matches the map | CI doc check: every `services/*` directory appears in MICROSERVICES_MAP and in CODEOWNERS | `tooling/`, STD-012, STD-015 |
| Module structure | Maven Enforcer: the four modules exist; `-domain` depends on none of the other three | Parent POM / `waver-platform-bom`, ADR-0007 |
| Layering | ArchUnit `layeredArchitecture()` rule: domain ← application ← adapters ← bootstrap | `waver-platform-test`, STD-001 |
| Package ownership | ArchUnit: classes live under `br.com.waver.<context>` and never reference another context's packages | `waver-platform-test` |
| Engine separation | ArchUnit/Maven rule objective-engine ↔ flow-engine | ADR-0019 |
| New aggregates and services | Architecture review of the RFC | RFC-0001, RFC-0002 |

## Exceptions
Open a PR that adds an entry to the Exceptions register in `AR_INDEX.md` (AR ID, service, scope, reason, compensating control, owner, expiry ≤ 6 months). Approval: WAVER Architecture plus the CODEOWNERS of the affected services. A permanent deviation (e.g., merging two contexts) requires an ADR that amends MICROSERVICES_MAP. CI fails when a referenced exception has expired.
