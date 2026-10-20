# RFC-0009 - Observability Architecture
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted monitoring, SLOs and alerts for Redis (ADR-0020), Keycloak (ADR-0021), Vault (ADR-0022), Temporal (ADR-0023), Apicurio Registry and Kafka Connect/Debezium (ADR-0024), Qdrant (ADR-0025) and Istio telemetry (ADR-0026); tracing through Temporal workflows and CloudEvents Kafka headers).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines logs, metrics, traces, correlation and tenant context, cost telemetry, the SLO framework with default SLOs per service tier, alerting, dashboards per Organization/Workspace/Domain/Agent, what customers and resellers see versus WAVER operations, the distinction between audit and logs, and retention. It also covers the platform components adopted on 2026-10-19 (Redis, Keycloak, Vault, Temporal, Apicurio Registry, Kafka Connect/Debezium, Qdrant, Istio ambient mesh): their telemetry, SLOs and alerts, and trace propagation through Temporal workflows and CloudEvents `ce_traceparent` Kafka headers. All telemetry is emitted in open formats (structured JSON logs, OpenTelemetry/OTLP metrics and traces via Micrometer) so the observability backend, still open in TECH_STACK, can be chosen without code changes.

## Context / Problem
Scope §17 requires structured logs, distributed traces per AgentRun/ToolExecution, token/model usage and cost, latency/error metrics, audit events, quotas, dashboards per Organization, Workspace, Domain, Agent and version, alerting and incident diagnostics. Commercial §14 and the launch checklist require observability per tenant with event correlation; Master §12.3 lists product metrics (task/objective completion, handoff, latency, cost, tool errors, quality). ADR-0017 makes `ModelCallCompleted` the source of truth for AI cost. ADR-0023 moves long-running processes to Temporal workflows, whose execution spans minutes to months and must stay traceable. ADR-0020..0026 add tier-0/1 platform components (Redis, Keycloak, Vault, Temporal, Apicurio Registry, Kafka Connect/Debezium, Qdrant, Istio) that need the same SLO and alerting discipline as services. Two tensions must be resolved: per-tenant visibility versus metric cardinality, and diagnostic depth versus PII/LGPD.

## Goals
- Any request, event or AgentRun can be followed end to end with one `traceId` or `correlationId`.
- Every service emits the same log fields, metric names and span attributes from `waver-platform-starter`, with no per-team setup.
- Tenant-facing analytics (customers, resellers) come from product data, not from the operations backend.
- SLOs per tier with error-budget alerting; noisy threshold alerts are the exception.
- Zero PII in logs, metrics labels and span attributes.

## Non-goals
- Choosing the backend (storage, query, visualization, alert routing/on-call tool) — open; a later ADR.
- Audit trail design (owned by audit-service; this RFC only separates it from logs).
- Customer-facing SLAs/contractual credits (business decision).
- Agent quality evaluation (evaluation-service, RFC-0016).

## Requirements / ARs
| AR / source | Mechanism |
|---|---|
| AR-005 | Each service ships its dashboards, alerts and SLO definitions as code next to its Helm chart. |
| AR-008 | `orgId`/`workspaceId`/`domainId` in MDC, span attributes and event envelopes. |
| AR-009 | Audit events are a separate, append-only data path (audit-service), not logs. |
| AR-013 / AR-014 | Freshness SLOs on data-plane read models; dependency failure metrics. |
| ADR-0017 | Cost telemetry from `ModelCallCompleted`; per-tenant budgets via usage-metering. |
| ADR-0020..0026 | Platform component SLOs (§7), alerts (§8), Vault audit and Keycloak events in the security log stream (§2, §9), mesh telemetry (§1). |

## Proposed Design

### 1. Pipeline and formats
```text
service (Spring Boot)
 ├─ logs    → stdout, structured JSON (Spring Boot structured logging) → node-level collection → log backend (open)
 ├─ metrics → Micrometer → OTLP registry → collection endpoint (open)
 └─ traces  → Micrometer Tracing (OpenTelemetry bridge) → OTLP exporter → collection endpoint (open)
```
- The OTLP endpoint, sampling ratio and resource attributes are configuration (`deploy/environments/<env>`). Swapping backend = config change.
- Resource attributes on all signals: `service.name`, `service.version`, `deployment.environment` (`dev|staging|production`), `k8s.namespace.name`, `k8s.pod.name`, `waver.plane` (`experience|control|data|platform`), `waver.tier` (`0|1|2`).

**Platform components** (owned by the platform team, telemetry shipped to the same backend; collection mechanism per component depends on the backend decision):

| Component | Metrics | Logs / events |
|---|---|---|
| Redis (ADR-0020) | Server metrics (memory, evictions, keyspace, replication, latency) via the collection layer; client metrics from Lettuce/Micrometer in every service | Server logs; ACL denials → security stream |
| Keycloak (ADR-0021) | Keycloak metrics endpoint (logins, token requests, errors, sessions, JVM) | Login and admin **events** → security stream (§2) |
| Vault (ADR-0022) | Vault telemetry (requests, leases, Transit operations, Raft health, seal status) | **Audit device** → security stream, correlated with audit-service events (§9) |
| Temporal (ADR-0023) | Server metrics (persistence latency, task queue backlog, schedule-to-start) + Java SDK worker metrics via Micrometer in each owning service | Server logs; Temporal Web for workflow histories (operators only) |
| Apicurio Registry (ADR-0024) | Micrometer metrics (requests, latency, errors) | Registry audit log (schema writes) → security stream |
| Kafka Connect / Debezium (ADR-0024) | Connect worker and task metrics, Debezium streaming metrics (`MilliSecondsBehindSource`, `Connected`, queue sizes) exposed as JMX | Worker logs; connector state changes |
| PostgreSQL replication slots | Slot lag in bytes and WAL retained (`pg_replication_slots`), per slot (RFC-0007) | — |
| Qdrant (ADR-0025) | Qdrant metrics endpoint (search/upsert latency, collection sizes, cluster health) | Server logs |
| Istio ambient (ADR-0026) | ztunnel L4 metrics (TCP connections, bytes, mTLS status, policy denials), waypoint L7 metrics where deployed, istiod health and certificate issuance | Mesh access logs (ztunnel/waypoints/egress gateway) → log backend; AuthorizationPolicy denials and egress gateway blocks → security stream |

Mesh telemetry uses the same resource attributes (`k8s.*`, workload identity) so dashboards join application and mesh signals. Mesh access logs must not contain query strings or path secrets (e.g., `hooks.` path tokens, RFC-0008): the access log format drops query strings and masks the configured secret path segments.

### 2. Logs
Mandatory JSON fields (MDC filled by the starter from token, event envelope or job context):

| Field | Source | Notes |
|---|---|---|
| `timestamp`, `level`, `logger`, `thread`, `message` | logging framework | UTC, ISO-8601 |
| `service`, `version`, `env` | resource | |
| `traceId`, `spanId` | Micrometer Tracing | Always present, also when the trace is not sampled |
| `correlationId` | `X-Correlation-Id` / CloudEvents `ce_correlationid` header | |
| `orgId`, `orgType`, `workspaceId`, `domainId` | token / `ce_*` headers | Empty for platform-scoped work |
| `principalId`, `actorType`, `supportGrantId` | token `sub`, `act`, `support_grant` | Pseudonymous IDs only |
| `eventId`, `eventType` | when handling an event (`ce_id`, `ce_type`) | |
| `workflowId`, `workflowType`, `activityType` | when running in a Temporal worker (ADR-0023) | Workflow ID `<workflowType>:<aggregateId>` (CONVENTIONS); never a metric tag |
| `agentRunId`, `toolExecutionId`, `modelCallId` | when applicable | |
| `errorCode`, `exception` | on errors | Stack traces only at `ERROR`, sanitized messages |

- **No PII and no restricted content**: no names, e-mails, phone numbers, CPF/CNPJ, addresses, message text, prompts, completions, retrieved chunks, tool inputs/outputs, HTTP bodies, tokens, API keys, credentials, cookies, SQL bind values. The starter ships a `SanitizingMessageConverter` that masks known patterns (e-mail, phone, CPF/CNPJ, JWT, bearer tokens, key prefixes) as a safety net; the rule is still "don't log it".
- Diagnostic content of an AgentRun (messages, tool payloads, model output) is **product data** stored by the owning services under RLS and retention (RFC-0007), viewable through the product with permissions — never shipped to the log backend.
- Levels: `ERROR` actionable failure; `WARN` degraded but handled; `INFO` lifecycle and state transitions (one line per request summary at most); `DEBUG` disabled in production except via time-boxed dynamic level change (audited).
- Log volume budget per service (default 50 GB/month production) with an alert at 80%.
- **Security log stream** (separate index/stream with the longer retention of §10): edge and BFF authentication logs, Keycloak login and admin events (login failures, brute-force lockouts, MFA changes, realm/client/IdP/mapper changes), Vault audit device logs (HMAC-ed request/response values, as Vault writes them), Apicurio schema writes, Kafka Connect configuration changes, Istio AuthorizationPolicy denials and egress gateway blocks, Redis ACL denials, break-glass sessions. These are diagnostic security evidence, not the audit trail (§9).

### 3. Metrics
- Naming: Micrometer dotted names `waver.<area>.<name>` (exported as OTLP), base units (seconds, bytes), counters for events, timers for latency (histograms with SLO buckets), gauges for state.
- **RED** for every inbound interface: HTTP (`http.server.requests`), Kafka handlers (`waver.event.handle.duration`), scheduled jobs (`waver.job.duration`), with `outcome` and `code` tags.
- **USE** for resources: JVM (heap, GC, threads, virtual threads), HikariCP pools, Kafka clients, CPU/memory/throttling from Kubernetes, PostgreSQL (RFC-0007), and the platform components of §1.
- Dependency metrics: client calls per target service with Resilience4j circuit-breaker state, retries, timeouts. Platform clients in the starter add: Redis command latency and errors (`lettuce.command.*`), cache gets by level (`waver.cache.gets{cache,level=l1|l2,result}`), rate-limiter fallback activations (`waver.ratelimit.fallback`), quota reconciliation drift (`waver.quota.reconciliation.drift`, usage-metering), Vault lease renewals/failures (`waver.vault.lease.renewal{outcome}`) and Transit latency (`waver.vault.transit.duration{operation}`), registry lookups (`waver.schema.registry.lookup{outcome}`), Qdrant search/upsert latency (`waver.vector.request.duration{collection,operation}`), Temporal SDK worker metrics (workflow/activity task schedule-to-start latency, failures, non-determinism errors) tagged by namespace, task queue and workflow/activity type.
- **Cardinality rule**: technical metrics never carry `orgId`, `workspaceId`, `domainId`, `agentId`, user or resource IDs, nor Temporal workflow IDs, Redis keys, Qdrant point IDs or Kafka Connect record keys. A short allow-list of business metrics may carry `org_id` (not deeper), capped by the starter at 2 000 series per metric per pod (overflow bucket `org_id="other"`). Per-tenant analytics come from product data (§6).
- Business/operational metrics (examples): `waver.agent.run.duration{strategy,outcome}`, `waver.agent.run.handoff`, `waver.tool.execution{tool_kind,outcome}`, `waver.model.call.duration{provider,model_profile,outcome}`, `waver.model.call.tokens{direction}`, `waver.channel.message{channel_type,direction,outcome}`, `waver.provisioning.job{outcome}`, `waver.entitlement.propagation.lag`, `waver.outbox.publish.lag` (Debezium CDC lag per producing service), `waver.event.end_to_end.latency{type}`, `waver.api.deprecated.calls{api_version}`.

### 4. Distributed tracing
- W3C Trace Context (`traceparent`, `tracestate`) over HTTP (automatic via Spring observation) and over Kafka. Kafka uses CloudEvents binary mode (RFC-0006 §6): the use case's `outbox.insert` span writes its context into the `ce_traceparent`/`ce_tracestate` outbox columns; the Debezium Outbox Event Router copies them to the `ce_traceparent`/`ce_tracestate` headers (the CloudEvents distributed-tracing extension). Kafka Connect creates no WAVER spans, so the producer context crosses CDC unchanged. The starter's consumer instrumentation extracts the context from `ce_traceparent` (falling back to a plain `traceparent` header); consumer spans are children of the producer context (single-consumer flows) or **span links** (fan-out and batch handling). The consumer span records `waver.event.publish_lag` (consume time − `ce_time`).
- Long-running processes run as **Temporal workflows** (ADR-0023). The Temporal Java SDK tracing interceptor (configured by the platform starter) propagates the context in workflow and activity headers: starting a workflow creates a `temporal.workflow.start` span in the caller; each activity execution is a span (`temporal.activity`) whose parent is the workflow's start context. Signals and updates (approval granted, user reply, purge confirmation) carry the sender's context and appear as **span links**. Each resume after a durable timer or signal therefore starts a new trace linked to the previous one; the stable correlation is the workflow ID (`<workflowType>:<aggregateId>`, CONVENTIONS, e.g. `ProvisioningJob:<provisioningJobId>`) and the business process ID (`agentRunId`, `provisioningJobId`, `deliveryAttemptId`), recorded as `waver.workflow.id` (plus `temporal.workflow_id`, `temporal.run_id`, `temporal.workflow_type`) on spans and as a log field. Temporal Web shows the full history for operators; the AgentRun record stores the list of trace IDs for retrieval in the product.
- Mesh: ztunnel works at L4 and emits no spans; waypoint span generation is disabled by default so that applications remain the single source of traces (tracing headers are propagated by applications, ADR-0026). Mesh L4/L7 metrics and access logs are correlated with traces through workload identity and time.
- Span model for the agent hot path (attribute names `waver.*` plus OpenTelemetry GenAI conventions `gen_ai.*`):

| Span | Owner | Key attributes |
|---|---|---|
| `agent.run` (root per turn) | agent-runtime | `waver.agent_run.id`, `waver.agent.id`, `waver.agent_version.id`, `waver.deployment.id`, `waver.strategy`, org/workspace/domain IDs |
| `agent.strategy.step` | objective/flow engine modules | `waver.objective.id` or `waver.flow_node.id`, step outcome |
| `knowledge.retrieve` | knowledge-service | `waver.knowledge_base.id`, result count, latency (no content); child spans `vector.search` (Qdrant: collection alias, filter fields used, hit count) and `fulltext.search` (PostgreSQL) |
| `memory.read` / `memory.write` | memory-service | scope, entry count |
| `model.call` | model-gateway-service | `gen_ai.system`, `gen_ai.request.model`, `waver.model_profile`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `waver.cost.amount`, `waver.cost.currency`, fallback used, time to first token |
| `tool.execution` | tool-runtime-service | `waver.tool.id`, `waver.tool_version.id`, `waver.tool_execution.id`, approval required, outcome |
| `connector.action` | connector-service | connector/version, action, target host (no path params/query), HTTP status, retries; child span `credential.decrypt` (Vault Transit operation, key name, outcome; never the value) |
| `approval.wait` | tool-runtime-service (Temporal workflow, ADR-0023) | `temporal.workflow_id`; span link from the approval signal to the resumed trace |
| `temporal.workflow.start` / `temporal.activity` | owning service of the workflow | `temporal.namespace`, `temporal.task_queue`, `temporal.workflow_type`, `temporal.activity_type`, attempt, outcome |
| `outbox.insert` / `event.handle` | every producer / consumer (starter) | `messaging.destination.name` (topic), `ce_type`, `ce_id`, `waver.event.publish_lag` (consumer) |
| `channel.deliver` | channel-service | channel type, provider, outcome |

- Sampling (defaults): parent-based ratio — production 10%, staging 100%, evaluation/sandbox runs 100%; errors and slow requests are always logged with `traceId` even when unsampled. Tail-based sampling is a backend capability (open) and would be added in the collection layer without code change. Support can raise sampling to 100% for one Organization for ≤ 24 h (audited configuration change read by the starter).

```mermaid
sequenceDiagram
  participant CH as channel-service
  participant K as Kafka
  participant RT as agent-runtime
  participant MG as model-gateway-service
  participant TR as tool-runtime-service
  participant CN as connector-service
  CH->>K: MessageReceived (outbox → Debezium; header ce_traceparent T, span A)
  K->>RT: consume → span agent.run (child of A, context from ce_traceparent)
  RT->>MG: POST chat (traceparent) → span model.call
  RT->>TR: POST tool-executions → span tool.execution
  TR->>CN: POST action → span connector.action
  RT->>K: AgentRunCompleted (ce_traceparent from agent.run)
  K->>CH: consume reply → span channel.deliver (link)
```

### 5. Cost telemetry
- `ModelCallCompleted` (model-gateway-service) is the source of truth for AI cost (ADR-0017): tokens in/out, provider, model, profile, latency, `cost` as Money (provider list price × tokens, BYOK flagged with cost borne by the customer), with `orgId`, `workspaceId`, `domainId`, `agentId`, `agentRunId`.
- Tool/connector cost units from `ToolExecutionCompleted` (duration, billable units, connector) and channel cost units from `MessageDelivered` (provider, segments).
- usage-metering-service aggregates these into per-tenant usage (org → workspace → domain → agent → version, per meter and hour/day) and maintains budgets/quotas (RFC-0020). Billing uses priced aggregates; COGS and margin analytics are WAVER-internal.
- Internal metrics expose aggregate cost rates per provider/model profile (no tenant tags) for operations; per-tenant cost lives in usage-metering data.
- Alerts: per-org spend anomaly (> 3× trailing 7-day hourly average) via usage-metering; provider-level cost spike via metrics.

### 6. Dashboards and tenant-facing observability
Two data paths:
1. **Operations telemetry** (logs/metrics/traces in the backend): WAVER engineering and support only.
2. **Product analytics** (run records, usage aggregates, health states owned by agent-runtime, usage-metering, connector-service, channel-service): served through BFFs with RLS and permissions to all audiences.

| View | WAVER Control | Reseller (Partner) | Customer (Workspace) |
|---|---|---|---|
| Platform/service health, SLOs, infra, Kafka, DB, Redis, Keycloak, Vault, Temporal, registry, Kafka Connect, Qdrant, mesh | Yes (ops backend) | Status page only | Status page only |
| Usage per org/workspace/domain/agent vs limits | All orgs | Own customers | Own org |
| AI COGS, provider cost, margin per product/reseller | Yes | No | No |
| Reseller margin estimate per customer | Yes | Yes (own) | No |
| Monetary price of usage | Yes | Yes (own customers) | Only if the reseller exposes billing (Commercial §18) |
| Agent KPIs: runs, completion/objective rate, handoff rate, latency, tool errors, cost units, per version | Aggregates; drill-down under support grant | Aggregates for own customers | Full, own org |
| AgentRun execution timeline (steps, tool calls, model calls, durations, errors) | Under support grant | Under support grant | Yes, with permission; content per role |
| Integration/connector health, channel delivery status | All | Own customers | Own org |
| Raw logs and traces | Engineering/support | No | No |

- Internal dashboards (as code per service): RED overview, dependencies, Kafka (lag, DLQ, CDC publish lag), DB, JVM, Temporal workers (task queues, schedule-to-start, failures); platform dashboards for SLOs per tier, provisioning success, entitlement propagation, cost per provider, and one dashboard per platform component (Redis, Keycloak, Vault, Temporal cluster, Apicurio Registry, Kafka Connect/Debezium with replication slots, Qdrant, Istio mesh and egress gateway), owned by the platform team in `deploy/platform/<component>/observability/`.
- Temporal Web (ADR-0023) is the operator view of workflow histories; access is restricted to WAVER operators through Keycloak SSO and Istio authorization. Workflow payloads hold IDs only (RFC-0007 §8), so Temporal Web exposes no restricted content.
- Product dashboards per Organization/Workspace/Domain/Agent/version are screens in the three apps (RFC-0021) backed by product data; the same data feeds Master §12.3 / Commercial §16 KPIs.

### 7. SLO framework
Tiering (default):

| Tier | Services | Rationale |
|---|---|---|
| 0 | identity-access-service, edge-gateway, control-bff, partner-bff, workspace-bff, agent-runtime, channel-service, model-gateway-service | On the path of every login or conversational turn |
| 1 | tool-runtime-service, connector-service, knowledge-service, memory-service, handoff-service, trigger-service, workflow-service, scheduler-service, entitlement-service, usage-metering-service, tenant-service, provisioning-service, approval-service, notification-service, audit-service | Data-plane dependencies and authorization/provisioning foundations |
| 2 | reseller-, customer-, catalog-, marketplace-, contract-, billing-, support-service, agent-builder-service, evaluation-service, tool-registry-service | Back-office and authoring; async tolerant |

Default internal SLOs (engineering defaults, 28-day rolling windows; customer-facing SLAs are business decisions):

| SLI | Tier 0 | Tier 1 | Tier 2 |
|---|---|---|---|
| Availability (non-5xx / valid requests) | 99.95% | 99.9% | 99.5% |
| Latency p95 of service-owned time (excl. LLM and external systems) | 300 ms | 500 ms | 1 s |
| Event handling freshness (publish → handled) p99 | 5 s | 30 s | 5 min |

Platform components are tiered by the most critical path they sit on (default):

| Tier | Components | Rationale |
|---|---|---|
| 0 | Keycloak, Vault, Redis, Istio (istiod, ztunnel, egress gateway), Kafka Connect + Debezium, Temporal (`waver-agent` namespace and cluster) | Login/token exchange (Keycloak), pod start, DB credentials and credential decrypt (Vault), sessions and edge limits (Redis), every pod-to-pod call (mesh), data-plane events such as `MessageReceived` (CDC), data-plane waits (Temporal) |
| 1 | Apicurio Registry (running pods cache schemas), Qdrant (with knowledge-service/memory-service), Temporal namespaces of the commercial/capability/automation/platform planes | Needed for new pods, retrieval and back-office processes |

Platform SLIs (engineering defaults, 28-day windows):

| Component | SLI | Objective |
|---|---|---|
| Keycloak | Token endpoint availability / p99 latency (incl. token exchange) | 99.95% / < 300 ms |
| Keycloak | Login page availability (synthetic probe) | 99.95% |
| Redis | Command availability seen by clients / p99 command latency | 99.95% / < 5 ms |
| Redis | Rate-limiter fallback time (local limiter active) | < 0.05% of the window |
| Redis | Quota reconciliation drift (Redis vs PostgreSQL after reconciliation) | 0 unresolved drift after 5 min |
| Vault | Availability (unsealed leader, API success) / Transit p99 latency | 99.95% / < 50 ms |
| Vault | Lease renewal success | ≥ 99.99% |
| Temporal | Frontend availability / workflow task schedule-to-start p95 (`waver-agent`) | 99.95% / < 1 s (other namespaces < 5 s) |
| Temporal | Timer firing delay p99 | < 5 s |
| Kafka Connect / Debezium | Connector task availability / CDC publish lag p99 (commit → produced) | 99.95% / < 1 s (data-plane producers), < 5 s (others) |
| Apicurio Registry | Availability / lookup p99 | 99.9% / < 100 ms |
| Qdrant | Filtered search p95 / availability | < 100 ms / 99.9% |
| Istio | mTLS coverage of `waver-*` traffic / istiod availability | 100% / 99.95% |

Specific SLIs: token endpoint p99 < 300 ms (Keycloak, above); platform overhead per agent turn (excluding model and tool time) p95 < 300 ms; model-gateway time-to-first-token overhead p95 < 100 ms over provider; inbound channel acknowledgement p99 < 1 s; entitlement/suspension propagation to data-plane read models p99 < 60 s; provisioning job success ≥ 99% without manual review; webhook delivery first-attempt success ≥ 99% (excluding customer endpoint errors).

SLOs are declared as code (`deploy/helm/<service>/slo.yaml`, and `deploy/platform/<component>/slo.yaml` for platform components: SLI query reference, objective, window) and translated to the chosen backend.

### 8. Alerting
| Alert | Condition (default) | Severity |
|---|---|---|
| Fast burn | Error budget burn rate > 14.4× over 1 h and 5 min | Page (tier 0/1) |
| Slow burn | > 6× over 6 h and 30 min | Page tier 0, ticket tier 1/2 |
| Budget trend | > 1× over 3 days | Ticket |
| Outbox / CDC stuck | CDC publish lag > 60 s for a tier-0/1 producer, or connector/task not RUNNING > 2 min (RFC-0006) | Page |
| Replication slot | WAL retained > 50% (ticket) / > 80% (page) of `max_slot_wal_keep_size`; slot inactive > 5 min; undeclared slot (security) | Ticket/Page |
| Redis | Memory > 85%; evictions of keys without TTL; replication broken; rate-limiter fallback active > 1 min; quota reconciliation drift unresolved > 5 min | Page (fallback/drift, memory), Ticket (other) |
| Keycloak | Token/login SLO burn (fast/slow burn rules above); login failure rate > 5× baseline or brute-force lockouts spike (security); admin event outside the pipeline identity (security); custom mapper errors > 1% | Page / security on-call |
| Vault | Sealed or no leader; audit device failure (Vault blocks requests when no audit device can write); lease renewal failures > 1% for 5 min; root token use (security); Raft snapshot failed | Page |
| Temporal | Schedule-to-start p95 > 5 s for 10 min (`waver-agent`); workflow task failures or non-determinism errors > 0 after a deploy; persistence latency p99 > 1 s; visibility lag | Page (tier 0) / Ticket |
| Apicurio Registry | Unavailable > 5 min; schema write by an identity other than the CI release identity (security) | Page |
| Qdrant | Search p95 > 300 ms for 10 min; cluster not green; snapshot failed; PostgreSQL/Qdrant reconciliation drift | Page / Ticket |
| Istio | istiod unavailable; ztunnel not ready on a node; workload certificate rotation failures; plaintext traffic detected in a `waver-*` namespace or `PeerAuthentication` not STRICT (security); AuthorizationPolicy denial spike (security); egress gateway blocks to a host not in any ServiceEntry (security ticket) | Page / security on-call |
| Consumer lag (data plane) | > 2 min for 10 min | Page |
| DLQ | Any write → ticket; > 10 in 5 min → page | Ticket/Page |
| Tenant isolation signal | `TENANT_CONTEXT_MISSING`, orgid mismatch in events, cross-tenant query outside audited path | Page security on-call |
| Model provider degradation | Fallback rate > 20% for 10 min or provider error rate > 5% | Page |
| Cost anomaly | Provider cost rate > 2× weekly baseline | Ticket (page if > 5×) |
| Certificate/secret expiry | External certificates, Vault-issued static secrets and Keycloak signing keys < 14 days (mesh workload certificates rotate automatically and are covered by the Istio row) | Ticket |
| Backup/restore | Backup or snapshot failed (PostgreSQL incl. Keycloak/Temporal/Apicurio databases, Vault Raft, Qdrant, registry export); restore drill > 35 days old | Page / Ticket |

Every paging alert links to a runbook (`10_RUNBOOKS`, template RUNBOOK_TEMPLATE) and a dashboard; alerts without a runbook are rejected in review. Alert routing/on-call tooling is open.

### 9. Audit vs logs
| Aspect | Audit events | Logs |
|---|---|---|
| Purpose | Accountability: who did what to which resource, when, under which grant | Diagnostics of system behaviour |
| Producer | Domain events (audit-relevant topics) + explicit `AuditRecorded` records written by every service through its outbox to `waver.audit.audit-record.v1` (RFC-0006 §13), consumed by audit-service | Every component, via stdout |
| Storage | audit-service PostgreSQL, append-only, hash-chained (MICROSERVICES_MAP) | Log backend (open) |
| Integrity | Tamper-evident, no update/delete API | Best effort |
| Content | Actor, action, resource IDs, before/after of controlled fields (no restricted content), reason, support grant | Technical context, no PII |
| Access | Audit Center (Control), filtered views for resellers/customers about their own org | Engineering/support |
| Retention | Legal/business policy (open; default: no automatic deletion until decided) | 30 days (below) |
| Completeness | Must not be sampled or dropped | Sampled/rate-limited allowed |

Vault's audit device and Keycloak's admin/login events are **security logs** of those components (security log stream, §2), not entries of the WAVER audit trail. They are correlated with audit-service events through IDs and time (e.g., a `CredentialLeased` audit event and the matching Vault Transit decrypt by model-gateway-service). Business actions performed through Keycloak on behalf of WAVER (IdP configuration pushed by identity-access-service, organization creation) are audited by the WAVER service that requested them.

### 10. Retention (engineering defaults)
| Signal | Hot | Archive |
|---|---|---|
| Application logs | 30 days | none |
| Security-relevant logs (auth, edge, admin access, break-glass, Keycloak events, Vault audit device, mesh denials and egress gateway logs, registry/Connect configuration changes) | 90 days | 1 year |
| Mesh access logs (non-security) | 7 days | none |
| Metrics | 15 days raw | 13 months downsampled (5 min) |
| Traces | 7 days | none |
| Product run records / usage aggregates | per RFC-0007 and RFC-0020 | — |
| Audit events | per audit policy (open) | — |

## APIs / Events / Data Ownership
- No new service. Telemetry libraries and conventions live in `waver-platform-starter` (including the Temporal tracing interceptor and the `ce_traceparent` Kafka propagation); dashboards/alerts/SLOs live in each service's `deploy/helm/<service>/observability/` and, for platform components, in `deploy/platform/<component>/observability/`.
- Inputs used for tenant-facing views: `AgentRunCompleted`, `AgentRunFailed`, `ToolExecutionCompleted/Failed`, `ModelCallCompleted`, `MessageDelivered/DeliveryFailed`, `UsageAggregated`, `QuotaThresholdReached`, `ConnectionHealthChanged` (EVENT_CATALOG).
- Per-org sampling overrides and dynamic log levels are versioned configuration changes applied through the deployment pipeline (audited), not a runtime admin API, in v1.

## Security & Privacy
- No PII/restricted content in any signal (§2); CI test feeds known PII samples through representative code paths and asserts that captured logs/spans contain none.
- Telemetry backends hold Internal-class data only; access restricted to WAVER engineering/support with SSO (Keycloak) and audit.
- Platform component telemetry follows the same rule: Vault audit entries keep Vault's HMAC-ed values (never plaintext secrets); Keycloak events exclude credentials and are configured without representation details that contain PII; Redis, Qdrant and Temporal metrics never carry keys, payloads or workflow inputs; mesh access logs drop query strings and mask secret path segments.
- Tenant IDs in telemetry are pseudonymous UUIDs; mapping to customer names only in product systems.
- Dynamic log-level and sampling overrides are audited actions.

## Reliability & Failure Modes
| Failure | Effect | Handling |
|---|---|---|
| Telemetry endpoint down | Lost/delayed telemetry | Exporters buffer in memory with bounded queues and drop (never block requests); stdout logs survive in node collection. |
| Cardinality explosion | Backend cost/instability | Starter caps tagged series; CI check on tag keys. |
| Clock skew | Misordered traces | NTP on nodes; spans use monotonic durations. |
| Trace not sampled during incident | Missing detail | `traceId` in logs; per-org temporary 100% sampling. |
| Alert storm | On-call overload | Burn-rate alerts, grouping by service and dependency, inhibition of downstream alerts (e.g., a Vault, Keycloak, Redis or mesh outage inhibits the dependent service alerts it causes). |
| Trace context lost across CDC or a workflow resume | Broken traces | `ce_traceparent` stored in the outbox row; Temporal interceptor headers; span links on signals; correlation by `correlationId` and workflow ID as fallback. |

## Observability
This RFC defines observability itself. Meta-monitoring: exporter queue drops, log ingestion lag, alert pipeline heartbeat (synthetic alert every 5 minutes, page when missing), synthetic probes per public host (login through Keycloak, API health, webchat round trip) every minute, a synthetic event per producing service through the outbox → Debezium → consumer path (end-to-end CDC probe), and scrape health of every platform component of §1.

## Testing Strategy
- Starter tests: MDC propagation across threads, virtual threads, `@Async`, Kafka listeners, Temporal workflows and activities; trace context through outbox → Debezium (`ce_traceparent` header) → consumer and through workflow start → activity → signal; PII sanitizer.
- Contract test per service: required log fields present; RED metrics registered; SLO file valid.
- Staging game days: kill dependencies (including Redis, a Vault node, a Keycloak replica, a Kafka Connect worker, the registry, a Qdrant node, istiod) and verify alerts fire within expected time, inhibit correctly and link runbooks.

## Deployment / Rollout / Rollback
- Phase 1 (first vertical slice): logs + RED metrics + tracing (including Kafka headers and Temporal) + interim backend in dev/staging (backend ADR pending); platform component dashboards and the CDC/replication-slot alerts.
- Phase 2 (before first production tenant): SLOs and burn-rate alerts for tier 0/1 services and platform components, security log stream (Keycloak events, Vault audit, mesh denials), product dashboards for usage and agent KPIs, status page.
- Phase 3: cost anomaly detection, tenant-facing execution timelines, tail sampling if the chosen backend supports it.
- All configuration changes (sampling, endpoints, alert rules) are versioned releases and can be rolled back.

## Alternatives Considered
- Vendor agents/SDKs in services: lock-in; rejected in favour of Micrometer + OTLP.
- Per-tenant labels on all metrics: unbounded cardinality and cost; rejected.
- Serving customer dashboards from the observability backend: mixes Internal telemetry with tenant data and breaks RLS guarantees; rejected.
- Logging prompts/completions for debugging: violates LGPD minimization; replaced by permissioned run timelines.

## Risks
- Without a chosen backend, alert/SLO tooling cannot be validated end to end; interim setup needed for staging.
- 10% head sampling may miss rare failures; mitigated by logs with `traceId` and targeted overrides.
- Product-side run timelines duplicate some trace data; storage cost is governed by RFC-0007 retention.
- Eight additional platform components multiply dashboards, alerts and on-call knowledge; mitigated by platform-owned dashboards/runbooks and alert inhibition.
- Mesh access logs can be high volume; non-security access logs are kept 7 days and may be sampled.

## Open Questions
1. Observability backend, alert routing/on-call tooling and status page product (TECH_STACK open).
2. Customer-facing SLAs and whether internal tier-0 SLOs become contractual (business).
3. Audit event retention period and legal hold rules (Scope §24 Compliance).
4. Should resellers see per-agent KPIs of their customers by default, or only with the customer's consent (privacy/commercial decision)?
5. Are model-gateway-service and channel-service tier 0 in agreement with RFC-0011/RFC-0019 owners (proposed here)?
6. Confirm the platform component tiers of §7 (in particular Kafka Connect and Redis as tier 0) with the platform team and RFC-0014/RFC-0015 capacity plans.

## Decision
Pending review. Proposed: adopt the signal conventions, tiering, default SLOs, alert set, data-path separation (ops telemetry vs product analytics) and retention defaults above.
