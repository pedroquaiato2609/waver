# AR-012 - Safe Suspension
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal for suspension/reactivation flows and the tenant purge job).

## Requirement
Suspension is reversible and distinct from termination/purge.

## Rationale
Non-payment can restrict or suspend a customer but must never erase data (Master §7.1, §14). Suspension, termination, retention and purge are different processes with different legal and commercial consequences; collapsing them risks irreversible data loss and LGPD violations.

## Rules
1. Lifecycle states follow Master §7.1: `CURRENT`, `PAST_DUE`, `GRACE_PERIOD`, `RESTRICTED`, `SUSPENDED`, `TERMINATED`, `REACTIVATING`. Suspension, termination, retention and purge are separate processes with separate commands and events.
2. Suspension blocks executions and channels according to policy (via `EntitlementSuspended`); it never deletes, anonymizes or rewrites business data.
3. Suspension requires reason, actor, timestamp and an AuditEvent; a reseller may only suspend customers in its own portfolio.
4. Reactivation restores the entitlements that were active before suspension (from entitlement history), automatically or by approval.
5. Termination starts retention; data export remains available during retention (LGPD).
6. Purge is a separate, audited job that runs only after retention ends and requires an approval in approval-service; it is never triggered by a billing state.
7. Suspension and reactivation commands are idempotent (AR-006).
8. Effects of a reseller's own default on its customers are defined in RFC-0017; until then there is no automatic cascade.

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| State machine | Exhaustive transition-table tests of the lifecycle states in customer-service (and reseller-service); Temporal workflow tests and replay tests for the suspension/reactivation flows and the tenant purge workflow | ADR-0023, RFC-0017 |
| No deletion | ArchUnit: suspension use cases cannot call repository delete methods or purge ports | `waver-platform-test` |
| Round trip | Integration test: suspend → reactivate restores identical entitlements; record counts unchanged | STD-010, RFC-0013 |
| Purge guarded | Integration test: purge job refuses to run without approval ID and expired retention | RFC-0007 |
| Audit | `assertAudited` on suspend/reactivate/terminate/purge | AR-009 |

## Exceptions
None. Legal deletion requests (LGPD) follow the purge process with its own approval, not suspension. Changes to the state model require an RFC-0017 update.
