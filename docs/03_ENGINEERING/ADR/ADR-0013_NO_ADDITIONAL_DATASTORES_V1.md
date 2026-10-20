# ADR-0013 - No Additional Datastores in v1 (Caching, Rate Limiting, Locks)
Status: SUPERSEDED by ADR-0020 (Redis) on 2026-10-19
Date: 2026-10-19

## Context
TECH_STACK lists Redis as not frozen. Caching, rate limiting and distributed locks are where an extra datastore usually creeps in.

## Decision
v1 uses only PostgreSQL and Kafka as stateful infrastructure.
- **Local caches**: in-process (Caffeine via Spring Cache) with short TTLs and event-driven invalidation (e.g., on entitlement changes).
- **Rate limits / quotas**: token buckets at edge-gateway (in memory per replica, approximate) plus authoritative quota counters in `usage-metering-service` (PostgreSQL, aggregated per minute) and entitlement checks.
- **Distributed locks / schedulers**: PostgreSQL advisory locks or `FOR UPDATE SKIP LOCKED`.
- **BFF session store**: Spring Session JDBC on the BFF's own small PostgreSQL database.

## Alternatives
- Redis now: faster, but another stateful system to secure, back up and operate.

## Consequences
- Edge rate limiting is approximate; authoritative enforcement happens at metering/entitlement level.
- Revisit only when a measured bottleneck appears (new ADR required).

## Compliance / Verification
- Architecture review rejects new stateful dependencies without an ADR.

## Supersedes / Superseded by
Closes "Redis" in TECH_STACK for v1.
