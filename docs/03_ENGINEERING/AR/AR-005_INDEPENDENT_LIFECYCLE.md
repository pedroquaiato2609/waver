# AR-005 - Independent Lifecycle
Status: ACCEPTED

## Requirement
Each service owns migrations, tests, deployment and observability.

## Rationale
If a service cannot be changed, tested and shipped alone, microservices bring their cost without their benefit. Owning migrations, tests, deployment and telemetry makes one team accountable end to end (Master §9.2).

## Rules
1. Each service contains its own Flyway migrations, test suites, Helm chart (`deploy/helm/<service>/`), environment values, dashboards, alerts and runbooks.
2. A service is built, tested, versioned and deployed alone; path-based CI builds only changed services (ADR-0015, STD-012).
3. There is no deployment ordering between services; cross-service changes are made backward compatible so each side ships alone (RFC-0014 §8).
4. Each service has its own version and release history; its image is promoted by digest (RFC-0014 §2).
5. Each service has an owning team in CODEOWNERS and an on-call rota before `production`.
6. Each service emits logs, metrics and traces through `waver-platform-starter`, tagged with service name and version (RFC-0009).
7. A service reaches `production` only with SLOs defined, alerts linked to runbooks and a passing rollback drill (RFC-0014).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Service completeness | CI "service manifest" check: chart, migrations folder, contracts, runbook link, CODEOWNERS entry | `tooling/`, STD-012, STD-015 |
| Independent build | Path-based pipeline; a service build MUST NOT require building another service | STD-012 |
| Independent deploy | Promotion per service; the pipeline rejects multi-service bundles | RFC-0014 §2, §8 |
| Telemetry | Post-deploy smoke test asserts health, metrics and trace export | RFC-0009, STD-007 |
| Production readiness | Readiness checklist reviewed before the first `production` promotion | RFC-0014 |

## Exceptions
Register in the Exceptions register in `AR_INDEX.md` (expiry ≤ 6 months), approved by WAVER Architecture. Acceptable example: a new service running in `dev`/`staging` without complete runbooks. No exception allows `production` without SLOs and alerts.
