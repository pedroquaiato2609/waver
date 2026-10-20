# AR-003 - Implementation Isolation
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro event schemas, Temporal, Nx module boundaries, Istio AuthorizationPolicy).

## Requirement
No service imports another service internal implementation.

## Rationale
Importing another service's classes couples release cycles and leaks internal models across boundaries. Contracts are shared; implementations are not (Master §9.2, ADR-0015).

## Rules
1. A module under `services/<a>/` MUST NOT depend on any artifact built from `services/<b>/`.
2. Allowed shared dependencies: `platform/*` (no domain code) and artifacts generated from `contracts/*` (OpenAPI clients, Avro specific records generated from `.avsc` schemas, ADR-0024).
3. Clients and event classes are generated from contracts; hand-copied DTOs or Avro classes of another service are forbidden.
4. In the Nx frontend workspace (ADR-0027), applications (`control`, `partner`, `workspace`) MUST NOT import each other; libraries carry scope tags (`scope:control`, `scope:partner`, `scope:workspace`, `scope:shared`) and an application imports only libraries of its own scope and `scope:shared`.
5. A BFF uses only the generated clients of services allowed for its audience (ADR-0009).
6. Copying non-trivial code between services is a review finding: technical code moves to `platform/*`; business code means the boundary must be re-examined (RFC-0002).
7. Temporal workflow and activity implementations are internal to the owning service: another service never starts, signals, queries or polls them and never shares their task queues (`<service>.<workflow>`); it uses the owner's API or events (ADR-0023).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| No cross-service Maven dependency | Maven Enforcer `bannedDependencies` on other services' artifacts | ADR-0015, STD-013 |
| No foreign packages | ArchUnit: no dependency on classes outside `br.com.waver.<own-context>`, `br.com.waver.platform` and generated clients | `waver-platform-test` |
| BFF audience | CI check: `workspace-bff` has no marketplace, catalog-pricing or reseller client | ADR-0009, STD-012 |
| Frontend boundaries | Nx `enforce-module-boundaries` lint with scope and type tags | STD-002, ADR-0027 |
| Workflow isolation | ArchUnit: no Temporal client stubs for workflow types outside the service's own package; task queue names start with the service name | `waver-platform-test`, ADR-0023 |
| Runtime | Istio `AuthorizationPolicy` per destination service, generated from declared dependencies: a service cannot reach any endpoint of a service it does not declare | RFC-0015 §13, ADR-0026 |
| Review | CODEOWNERS per service and per `contracts/*` directory | STD-011 |

## Exceptions
No exception exists for cross-service imports. If two services need the same non-domain code, propose it for `platform/*` (platform owners review). Temporary deviations during a service split are registered in the Exceptions register in `AR_INDEX.md` (expiry ≤ 3 months, WAVER Architecture approval).
