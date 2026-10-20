# ADR-0020 - Redis for Caching, Rate Limiting, Sessions and Hot Counters
Status: PROPOSED
Date: 2026-10-19
Supersedes: ADR-0013

## Context
ADR-0013 kept v1 on PostgreSQL + Kafka only, accepting approximate edge rate limiting and quota enforcement that can overshoot by one batch. The product owner decided (2026-10-19) to adopt Redis to get exact, low-latency enforcement and shared caches from the start.

## Decision
Adopt **Redis** (Redis 8.x; Valkey is an API-compatible fallback if licensing requires it) as a shared, **non-authoritative** in-memory store. PostgreSQL remains the source of truth for every business fact.

| Use | Owner | Key pattern / structure | Notes |
|---|---|---|---|
| BFF HTTP sessions | control/partner/workspace-bff | Spring Session Data Redis, `waver:sess:<bff>:*` | Replaces Spring Session JDBC |
| Rate limiting | edge-gateway; also tool-runtime, connector-service, model-gateway (provider/connection throttles) | Spring Cloud Gateway `RedisRateLimiter` at the edge; token buckets under each service's own prefix elsewhere | Exact global limits per API key, org, route, connection and provider |
| Real-time quota counters | usage-metering-service | `waver:quota:<quotaCounterId>` (INCRBY + Lua check) | Data plane checks counters directly; PostgreSQL `quota_counter` is reconciled every minute and is authoritative for billing |
| Idempotency keys | services exposing `Idempotency-Key` | `waver:idem:<service>:<key>` with TTL | Response stored in PostgreSQL when needed for audit |
| Shared read-through caches | any service, opt-in | `waver:cache:<service>:<name>:<id>` | TTL + event-driven eviction; local Caffeine stays as L1 |
| Distributed locks (short) | any service | Redisson or Spring Integration `RedisLockRegistry` | Only for in-process coordination; long processes use Temporal (ADR-0023) |
| Streaming fan-out of agent output | agent-runtime (private keys) | Redis Streams `waver:run-stream:<agentRunId>` (TTL 15 min) | Any agent-runtime pod can serve the SSE endpoint (with `Last-Event-ID` reconnect) regardless of which pod produced the tokens; channel-service and workspace-bff subscribe through that endpoint, never to the keys |

Rules:
- Redis is **per environment**, deployed as a highly available cluster (managed service once the cloud is chosen; Redis Operator / Sentinel otherwise).
- Logical isolation per service: ACL user per service restricted to its key prefix (`~waver:<prefix>:*`). No service reads another service's keys (AR-002 spirit).
- **Single exception — quota counters:** usage-metering-service owns `waver:quota:*` and the Lua scripts that check/increment them. The data-plane services that admit work (agent-runtime, tool-runtime-service, channel-service, model-gateway-service) may only run those scripts (`EVALSHA` restricted by ACL to the registered script SHAs); they never read or write the keys directly. The scripts are published by usage-metering as part of its client library (`contracts/clients/usage-metering-client`, ADR-0015).
- **No business data that only lives in Redis.** Losing Redis may degrade latency or rate limiting, never correctness: every use above has a defined fallback (sessions → re-login; rate limiting → fail-open with local limiter; quota → fall back to the PostgreSQL counter and local snapshot; cache → miss).
- No PII in keys; values containing PII are encrypted or avoided; TLS in transit and AUTH/ACL mandatory.

## Alternatives
- Keep ADR-0013 (PostgreSQL only): simpler operations, approximate limits.
- Hazelcast or another in-memory grid: more operational complexity, smaller ecosystem in Spring.

## Consequences
- One more stateful system to operate, secure and monitor (memory, evictions, latency).
- HARD quotas become near-exact (overshoot limited to concurrent in-flight calls).
- Licensing: Redis 8 is tri-licensed (RSALv2 / SSPLv1 / AGPLv3). Using it unmodified as an internal backing service is compatible with WAVER's use; legal must confirm (OPEN_DECISIONS D-37). Valkey (BSD) is the drop-in alternative.

## Compliance / Verification
- Chaos test: Redis unavailable in staging → no data loss, services degrade as documented.
- Redis ACL configuration reviewed per service in the Helm chart.

## Supersedes / Superseded by
Supersedes ADR-0013.
