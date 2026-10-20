# AR-011 - Entitlement Authority
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis real-time quota counters, Avro event contracts).

## Requirement
Entitlements are technical authority for commercially controlled capabilities.

## Rationale
Catalog ≠ Contract ≠ Entitlement ≠ Usage (Master §6). Catalog says what can be sold, Contract what was agreed, Entitlement what is technically enabled, Usage what was consumed. If services decided access from catalog or contract data, every commercial change would need code in many places and suspensions would leak.

## Rules
1. entitlement-service is the only source of truth for `Entitlement`, `EntitlementLimit` and the commercial `FeatureFlag` (MICROSERVICES_MAP).
2. No service grants a commercially controlled capability based on catalog, listing or contract data.
3. Enforcing services (agent-builder when offering capabilities, agent-runtime, tool-runtime, channel, knowledge, model-gateway budgets) check entitlements through a local snapshot built from `EntitlementGranted/Changed/Suspended/Revoked` events, with periodic reconciliation (default every 15 min).
4. Revocation and suspension take effect within a propagation target (default ≤ 60 s p99, to be confirmed in RFC-0013).
5. Platform release flags never gate commercial capabilities, and the commercial `FeatureFlag` is never a release toggle (RFC-0014 §6).
6. Entitlements change only from contract/customer events or through audited manual overrides approved in approval-service.
7. Quota enforcement uses the counters owned by usage-metering-service: data-plane services check the real-time Redis counters (`waver:quota:<quotaCounterId>`), and the PostgreSQL `quota_counter`, reconciled every minute, stays authoritative for billing. If Redis is unavailable, enforcement falls back to the PostgreSQL counter and the local snapshot (ADR-0020).
8. When the snapshot is missing, the service denies new commercial capabilities (fail closed) while already-authorized workloads continue on the last known snapshot (AR-013).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Single check path | ArchUnit: commercial capability checks only via the platform `EntitlementPort`; no catalog/contract clients in enforcing services' authorization code | `waver-platform-test`, RFC-0013 |
| Event contracts | Consumer contract tests for Entitlement events (Avro schemas, registry compatibility) | ADR-0024, STD-004 |
| Quota counters | Reconciliation job compares Redis counters with PostgreSQL `quota_counter` and alerts on drift; chaos test with Redis unavailable asserts the documented fallback | RFC-0020, ADR-0020 |
| Propagation | E2E in `staging`: revoke an entitlement and assert the capability is blocked within the target | STD-010, RFC-0013 |
| Snapshot health | Metric `entitlement snapshot age` per service with alert | RFC-0009 |
| Overrides | Audit assertions and approval reference required on manual overrides | AR-009, RFC-0017 |

## Exceptions
Internal WAVER test tenants may receive entitlements through a documented seeding job, not through bypasses. Any bypass of entitlement checks requires an ADR; time-boxed exceptions are registered in the Exceptions register in `AR_INDEX.md` and approved by WAVER Architecture and the commercial owner.
