# AR-009 - Auditability
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Keycloak as authorization server, Vault audit device for secret and credential access).

## Requirement
Sensitive actions require authorization and audit trail.

## Rationale
Resellers act on their customers, WAVER support can impersonate, agents execute actions in customer systems, and financial states block service. Enterprise customers and LGPD require proof of who did what, when, why and with which authority (Master §7.1, §11; SECURITY_BASELINE).

## Rules
1. Each service RFC lists its sensitive actions. The minimum set: contract and price/discount overrides, suspension/reactivation/termination, customer ownership transfer, role and membership changes, MFA reset, SSO configuration, support access grants and impersonated actions, credential create/rotate/revoke/lease/use (ADR-0014 ownership, ADR-0022 Vault Transit), agent publish/deploy/rollback, tool executions requiring approval, data export and purge, billing adjustments and credits, production break-glass.
2. Each sensitive action is authorized by an explicit permission check in the owning service (fine-grained permissions are not packed in tokens, CONVENTIONS); token roles alone are not sufficient.
3. Each produces an AuditEvent with: actor (`sub`, `act` chain, `support_grant` if present), `orgId`, action, target resource, outcome, timestamp, `correlationId`, reason (mandatory for suspension, Master §7.1) and a before/after summary without secrets and with minimized PII.
4. Audit events are written to the outbox in the same transaction as the change; audit-service stores them append-only and hash-chained.
5. Denied attempts on sensitive actions are audited too.
6. No API modifies or deletes audit records; retention follows the compliance policy (open).
7. Sessions under a support grant mark every action with `support_grant`.
8. Secret and credential access at the infrastructure level is also recorded: the Vault audit device is always enabled, and its logs are shipped to the observability backend and correlated with audit-service events by `correlationId` where available (ADR-0022). Keycloak admin and login events (MFA reset, IdP federation changes) are exported and correlated the same way (ADR-0021).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Authorization present | ArchUnit: every controller method has a permission check annotation or `@PublicEndpoint` | `waver-platform-test`, STD-009 |
| Audit emitted | Sensitive use cases annotated `@Audited(action)`; test kit `assertAudited(action)` checks the outbox | `waver-platform-starter`, `waver-platform-test` |
| Integrity | Daily hash-chain verification job in audit-service with alert | audit-service, RFC-0010 |
| Coverage | Review of each RFC's sensitive-action list; E2E checks audit entries for the minimum set | RFC-0004, RFC-0010, STD-010 |
| Authorization strength | External penetration test of the Keycloak login and federation flows, the claims mapper and the admin APIs before production | ADR-0021 |
| Infrastructure audit | Vault audit device enabled in every environment (alert if disabled or failing); Keycloak event export enabled | ADR-0022, ADR-0021, RFC-0015 §12 |

## Exceptions
None for the minimum set in rule 1. Adding or removing actions from a service's sensitive list is done in its RFC. Temporary gaps before `production` are registered in the Exceptions register in `AR_INDEX.md` and approved by WAVER Architecture and Security.
