# STD-007 - Observability Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal, Debezium/Kafka Connect, Redis, HashiCorp Vault, Qdrant, Istio ambient).
Version: 1.3
Date: 2026-10-19
Applies to: every Java service, BFF, edge-gateway, Temporal worker and frontend image; the telemetry of the platform components (Kafka Connect/Debezium, Temporal, Redis, Vault, Qdrant, Keycloak, Istio); Angular apps for client-side performance signals
Related: AR-005, AR-013, AR-014, ADR-0017, ADR-0020, ADR-0022, ADR-0023, ADR-0024, ADR-0025, ADR-0026, RFC-0005, RFC-0006, RFC-0007, RFC-0009, STD-004, STD-005, STD-006, STD-008

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. Signals are emitted with **Micrometer** (metrics, and Micrometer Tracing bridged to OpenTelemetry format) and exported over OTLP; logs follow STD-006. The backend is still open (TECH_STACK) and nothing here depends on it. Design and defaults: RFC-0009.

## 1. Metric naming and tags
- **O-01** WAVER metrics are lowercase dotted names `waver.<area>.<name>` (`waver.agent.run.duration`, `waver.outbox.slot.lag`). Spring/Micrometer/Resilience4j/Lettuce built-in names (`http.server.requests`, `hikaricp.*`, `jvm.*`, `resilience4j.*`, `lettuce.*`), Temporal SDK metrics (`temporal_*`), Debezium/Kafka Connect metrics and Istio metrics (`istio_*`) are kept unchanged.
- **O-02** Base units (seconds, bytes); counters for occurrences, timers (histograms with SLO buckets) for latency, gauges for state, distribution summaries with an explicit `baseUnit` (`tokens`, `bytes`).
- **O-03** Tag keys are `snake_case` and low cardinality: `outcome`, `code`, `uri` (template), `method`, `status`, `topic`, `group`, `type`, `strategy`, `provider`, `model_profile`, `tool_kind`, `channel_type`, `api_version`, `workflow_type`, `activity_type`, `task_queue`, `namespace`, `cache`, `collection_alias`. Resource attributes (`service.name`, `service.version`, `deployment.environment`, `waver.plane`, `waver.tier`) are added by the starter, not by code.
- **O-04** Technical metrics MUST NOT carry `org_id`, `workspace_id`, `domain_id`, `agent_id`, user or resource IDs, raw paths or error messages. A short, reviewed allow-list of business metrics may carry `org_id` only (never deeper), capped by the starter at 2 000 series per metric per pod (overflow `org_id="other"`). Per-tenant analytics come from product data (usage-metering, agent-runtime run records), not from metrics.

## 2. Mandatory metrics per service
| Area | Metrics | Provided by |
|---|---|---|
| HTTP server (RED) | `http.server.requests` with `uri`, `method`, `status`, `outcome`, `code`; SLO buckets | Spring Boot + starter |
| HTTP clients | client requests per downstream, retries, timeouts; `resilience4j.circuitbreaker.state`, `resilience4j.bulkhead.*` | starter `client` module (RFC-0005) |
| Kafka handlers (RED) | `waver.event.handle.duration{type,group,outcome}`, consumer `records-lag-max` per group/partition, `waver.event.dlq.published{topic,group}`, `waver.inbox.duplicates`, unknown event types | starter `messaging` module (RFC-0006) |
| Outbox (Debezium, ADR-0024) | `waver.outbox.slot.lag` (bytes behind `pg_current_wal_lsn()`), `waver.outbox.slot.active`, `waver.outbox.retained.rows`; Debezium `MilliSecondsBehindSource`, connector/task state; `waver.event.publish.latency{topic}` (Kafka record timestamp minus `ce_time`, measured by consumers) | starter `outbox` module (slot gauges), Kafka Connect metrics, starter `messaging` module |
| Temporal workflows and activities (ADR-0023) | SDK metrics: `temporal_workflow_completed/failed/canceled`, `temporal_workflow_task_schedule_to_start_latency`, `temporal_activity_schedule_to_start_latency`, `temporal_activity_execution_failed`, `temporal_activity_execution_latency`, `temporal_sticky_cache_*`, worker slots; tags `namespace`, `task_queue`, `workflow_type`, `activity_type` | Temporal Java SDK Micrometer reporter, registered by the starter `temporal` module |
| Jobs | `waver.job.duration{job,outcome}` for batch jobs that are not Temporal workflows | starter `process` module |
| Redis (ADR-0020) | Lettuce command latency/errors; `waver.redis.fallback{use,outcome}` when a documented fallback is taken; quota reconciliation drift `waver.quota.reconciliation.drift` (usage-metering) | Lettuce binder + starter `redis` module |
| Vault (ADR-0022) | `waver.vault.lease.renewal{outcome}`, `waver.vault.credential.rotation{outcome}` (DB credentials), `waver.vault.transit.duration{operation,outcome}` (services using Transit) | starter `vault` module |
| Vector store (ADR-0025) | `waver.vector.search.duration{collection_alias,outcome}`, `waver.vector.final_check.dropped` (hits rejected by the PostgreSQL re-check) | knowledge-service, memory-service |
| Database | `hikaricp.connections.active/pending/timeout/usage`, slow queries, `TENANT_CONTEXT_MISSING` count | HikariCP binder + starter |
| Read models | staleness and rebuild counts of `rm_*` projections | starter `readmodel` module |
| Runtime (USE) | `jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*` (incl. virtual threads), `process.cpu.usage`; caches `cache.gets{cache,result}` for L1 (Caffeine) and L2 (Redis) | Micrometer binders |
| Logs | events by level (volume budget, STD-006 L-17) | Logback binder |

## 3. Business metrics
- **O-05** Each service's RFC lists 3-10 business metrics; they are implemented with Micrometer and the tag rules above.

```java
Timer.builder("waver.agent.run.duration")
    .tag("strategy", run.strategy().name()).tag("outcome", outcome.name())
    .publishPercentileHistogram().register(registry).record(elapsed);
Counter.builder("waver.provisioning.job").tag("outcome", "SUCCEEDED").register(registry).increment();
DistributionSummary.builder("waver.model.call.tokens").baseUnit("tokens").tag("direction", "output")
    .register(registry).record(usage.outputTokens());
```
| Plane | Examples (RFC-0009 §3) |
|---|---|
| Commercial | `waver.provisioning.job{outcome}`, `waver.entitlement.propagation.lag`, `waver.api.deprecated.calls{api_version}` |
| Agent | `waver.agent.run.duration{strategy,outcome}`, `waver.agent.run.handoff`, `waver.model.call.duration{provider,model_profile,outcome}`, `waver.model.call.tokens{direction}` |
| Capability | `waver.tool.execution{tool_kind,outcome}`, `waver.channel.message{channel_type,direction,outcome}` |

## 4. Tracing
- **O-06** W3C `traceparent`/`tracestate` are propagated on every HTTP call automatically; only clients built by the starter (generated HTTP interface clients on Spring `RestClient`) are allowed, so propagation, deadline and auth headers are always set.
- **O-07** Kafka: the outbox row stores `traceparent`/`tracestate` at insert; Debezium's Outbox Event Router copies them into the W3C `traceparent` header and the CloudEvents `ce_traceparent` header (STD-004 §2); consumer spans are children of the producer context for single-consumer flows and **span links** for fan-out and batch handling. The connector itself creates no spans.
- **O-08** Long-running processes run as Temporal workflows (ADR-0023). The starter's Temporal context propagator carries `traceparent`, correlation ID and tenant context in Temporal headers, so the workflow start and its first activities join the caller's trace; each resume after a timer, signal or update starts a new trace **linked** to the start. The stable correlation is the workflow ID (the business key, e.g., `provisioning-job-<id>`) plus the process ID (`agentRunId`, `provisioningJobId`) as span attribute and log field (STD-006).
- **O-09** Span names are low cardinality (`agent.run`, `model.call`, `tool.execution`, `knowledge.retrieve`, `connector.action`, route templates). Attributes use `waver.*` (`waver.agent_run.id`, `waver.org_id`) and OpenTelemetry `gen_ai.*` conventions for model calls. Span attributes MAY carry tenant and resource IDs but MUST NOT carry message content, prompts, completions, tool arguments/results, PII, secrets or query strings.
- **O-10** Sampling is parent-based: production 10%, staging 100%, evaluation/sandbox runs 100%; `traceId` is always logged even when not sampled. A temporary 100% override for one Organization (≤ 24 h) is an audited configuration change. Baggage is not used for tenant context or PII.

## 5. Health endpoints
- **O-11** Actuator runs on a separate management port that edge-gateway never routes. Exposed endpoints: `health` (liveness/readiness groups) and `info`; metrics are pushed over OTLP, not scraped through public routes.
- **O-12** Liveness checks only the process itself. Readiness includes the service's own database and essential local state (e.g., data-plane read models loaded, schema IDs of produced event types resolved, STD-004 K-30); it MUST NOT include other WAVER services (AR-013, AR-014), nor Redis, Qdrant, Temporal, Kafka or Vault for pods already running: those have documented degradation paths (ADR-0020, ADR-0022, ADR-0025) and are watched by metrics and alerts, so an outage never cascades into pod restarts or removal from load balancing. Obtaining the first Vault credentials is a startup precondition, not a readiness check.

```yaml
management:
  server.port: 8081
  endpoints.web.exposure.include: health,info
  endpoint.health.probes.enabled: true
  endpoint.health.group.readiness.include: readinessState,db
  tracing.sampling.probability: ${WAVER_TRACING_SAMPLING:0.1}
  metrics.distribution.slo.http.server.requests: 100ms,300ms,500ms,1s,3s
  otlp.metrics.export.url: ${WAVER_OTLP_METRICS_URL}
```

## 6. SLOs
- **O-13** Every deployable declares SLOs as code in `deploy/helm/<service>/slo.yaml`: event-based good/total SLIs, 28-day rolling window, objective per RFC-0009 tier. Defaults: availability 99.95% / 99.9% / 99.5% and latency p95 (service-owned time, excluding LLM and external systems) 300 ms / 500 ms / 1 s for tiers 0 / 1 / 2; event-handling freshness p99 5 s / 30 s / 5 min.

```yaml
service: contract-service
tier: 2
slos:
  - name: api-availability
    objective: 99.5
    window: 28d
    sli: { good: 'http.server.requests{outcome!="SERVER_ERROR"}', total: 'http.server.requests', exclude: { uri: '/actuator/**' } }
  - name: api-latency
    objective: 95.0
    window: 28d
    sli: { good: 'http.server.requests{le="1.0"}', total: 'http.server.requests' }
  - name: event-freshness
    objective: 99.0
    window: 28d
    sli: { good: 'waver.event.handle.lag{le="300"}', total: 'waver.event.handle.lag' }
alerts:
  fastBurn: { factor: 14.4, long: 1h, short: 5m }
  slowBurn: { factor: 6, long: 6h, short: 30m }
  budgetTrend: { factor: 1, long: 3d }
runbook: 10_RUNBOOKS/contract-service.md
```

## 7. Dashboards and alerts
- **O-14** Each service has, as code in `deploy/helm/<service>/observability/`: a **Service Overview** dashboard (RED per route and handler, SLO burn, dependencies and circuit breakers, Kafka lag/DLQ, outbox slot lag, Temporal task-queue latency and failures, Redis fallback rate, DB pool and Vault credential rotation, JVM and virtual threads, log volume, mesh L4/L7 traffic and denials) generated from the platform template, and a **Business** dashboard from its business metrics. Tenant-facing product dashboards are app screens backed by product data (RFC-0021), not telemetry.
- **O-15** Mandatory alerts (RFC-0009 §8 defaults): fast burn (page tier 0/1), slow burn (page tier 0, ticket tier 1/2), budget trend (ticket); outbox: Debezium `MilliSecondsBehindSource` > 60 s (warn) / > 5 min (page tier 0/1), replication slot inactive > 5 min or slot lag > 50% of `max_slot_wal_keep_size` (page), connector task `FAILED` (page); Temporal: activity or workflow-task schedule-to-start latency > 30 s for 10 min (ticket tier 2, page tier 0/1), non-determinism or repeated workflow-task failures (page), workflow failures above baseline (ticket); Vault: lease renewal or credential rotation failures (page); Redis: fallback active > 5 min (ticket; page for edge rate limiting and quota); mesh: `AuthorizationPolicy` denials between WAVER services (security ticket, page on spikes); data-plane consumer lag > 2 min for 10 min (page); any DLQ write (ticket), > 10 in 5 min (page); tenant isolation signals (`TENANT_CONTEXT_MISSING`, orgid mismatch) page security on-call. Every paging alert links to a runbook and a dashboard; alerts without a runbook are rejected in review.
- **O-16** Frontends report Core Web Vitals (LCP, INP, CLS) per route template and client error counts through their BFF, which records `waver.frontend.web_vitals{app,route,metric}` (RFC-0021).
- **O-17** Mesh telemetry (ADR-0026): ztunnel L4 metrics and waypoint L7 metrics (`istio_requests_total`, `istio_request_duration_milliseconds`) are exported to the same backend and labelled with source/destination workload; they complement, never replace, application RED metrics (mesh retries are disabled, so application metrics remain the source for SLOs).

## Enforcement
| Rule | Enforced by |
|---|---|
| O-01..O-04 naming/cardinality | Starter `MeterFilter`: denies forbidden tag keys, caps `org_id` series; CI check on tag keys in code |
| Section 2 mandatory metrics | Starter auto-configuration; per-service contract test asserting the meters exist (RFC-0009 testing) |
| O-05 business metrics | Service RFC review; Business dashboard required in production readiness review |
| O-06..O-08 propagation | ArchUnit bans hand-built HTTP clients; starter tests for `traceparent` through outbox → Debezium → Kafka → consumer and through Temporal start → activity |
| O-09, O-10 span content, sampling | Starter span-attribute filter; CI test feeding PII samples and asserting none reach captured spans/logs |
| O-11, O-12 health | Starter defaults; Helm chart probes (RFC-0015); review of custom health indicators |
| O-13..O-15 SLOs/alerts | CI validates `slo.yaml` schema, tier and runbook link for every deployable; platform alert rules for Debezium, Temporal, Vault, Redis and mesh in `deploy/platform/*` |
| O-17 mesh telemetry | Istio telemetry configuration as code (RFC-0015) |

## Open Questions
1. ~~Outbox age metric name~~ — superseded in v1.3: with Debezium there is no relay queue; outbox health is `waver.outbox.slot.lag`, Debezium `MilliSecondsBehindSource` and `waver.event.publish.latency` (RFC-0006/RFC-0009 should use the same names).
2. The event-freshness SLI needs a lag timer (`waver.event.handle.lag`, publish → handled) not yet listed in RFC-0006/0009.
3. Observability backend, alert routing and on-call tooling remain open (TECH_STACK).
