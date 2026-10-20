# RFC-0019 - Model Gateway
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis for real-time budget and quota counters, throttle state and shared caches, HashiCorp Vault for platform provider keys and the BYOK lease as a Transit decrypt policy, Istio egress gateway + ServiceEntry allow-lists and mTLS/authorization, Avro + Apicurio Registry + CloudEvents binary mode + Debezium outbox for events, Qdrant collection versioning for embedding changes, Keycloak service identities).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Define `model-gateway-service` (ADR-0017): the provider adapter ports, ModelCatalog, ModelProfile, RoutingRule, fallback chains and circuit breaking per provider, streaming passthrough, token counting and cost calculation, budgets per organization/workspace/domain/agent together with usage-metering (soft/hard limits, real-time Redis counters), BYOK through the ADR-0014 credential lease implemented with Vault Transit (ADR-0022), data handling (no-training, redaction hook, logging rules), the embeddings API used by knowledge and memory, the `ModelCallCompleted` event, model evaluation with evaluation-service, SLO defaults and failure modes. LLM vendors stay behind adapters; no vendor or model is a decision of this RFC.

## Context / Problem
Scope §22 requires provider abstraction across LLM vendors, cost control and fallback; Scope §24 leaves model routing, cost optimization and customer-provided keys open. ADR-0017 creates the single egress point for LLM and embedding providers and makes it the source of truth for AI cost of goods sold. Every turn of every agent passes through it at least once, so it must add little latency, never mix tenants, never become a place where prompts leak, and degrade gracefully when a provider fails.

## Goals
- Agents and services reference model **profiles**, never vendor model IDs (Scope §6.2, ADR-0017).
- One normalized request/response/stream contract over all providers.
- Automatic fallback and circuit breaking without the caller knowing the provider.
- Exact per-call token and cost accounting, published as events for metering and COGS.
- Budget and quota enforcement before money is spent.
- BYOK without persisting customer keys outside connector-service.
- Prompts and completions are not logged by default.

## Non-goals
- Agent prompt composition (RFC-0016) and turn orchestration (RFC-0011).
- Knowledge chunking/indexing (knowledge RFC); this RFC only provides embeddings.
- Billing and pricing to customers (RFC-0020); `model.cost` is internal COGS.
- Choosing vendors, models or regions (business/contract decisions).
- Self-hosted model serving (would need its own ADR).

## Requirements / ARs
| AR / ADR | Application |
|---|---|
| AR-002/004 | Own DB `waver_model_gateway`; callers use REST; facts published on Kafka. |
| AR-006 | `callId` = caller `Idempotency-Key`; a repeated `callId` within 24h returns the stored result (non-streaming) or `409 CALL_IN_PROGRESS`. Events deduplicated by `callId` downstream. |
| AR-008 | Tenant context from the caller's token (RFC-0005 service-to-service rules); every call row and event carries `org_id`/`workspace_id`/`domain_id`. |
| AR-009 | Admin changes (catalog, profiles, routing, provider accounts, budgets), BYOK leases and debug-capture access are audited. |
| AR-011/013 | Entitlement snapshot and quota state consumed from compacted topics; no synchronous control-plane calls on the hot path. |
| AR-014 | Timeouts from `X-Request-Deadline`, Resilience4j circuit breakers and bulkheads per provider account, fallback chain. |
| ADR-0020 | Budget reservations, commercial quota checks, provider throttle state and the per-org concurrency cap use Redis in real time; PostgreSQL (`budget_counter`, ModelCall rows) stays authoritative and is reconciled every minute; documented fallbacks when Redis is unavailable. |
| ADR-0014 / ADR-0022 | BYOK keys leased from connector-service (`LLM_PROVIDER_KEY` only, TTL ≤ 15 min, memory only, audited); the lease returns Transit ciphertext that model-gateway decrypts under a Vault policy limited to `tenant-credentials-llm`. Platform provider keys come from Vault (KV v2, delivered by Vault Secrets Operator/Agent). |
| ADR-0017 | Single egress to LLM/embedding providers; Spring AI allowed only inside adapters; the Istio egress gateway allow-list enforces it (ADR-0026), NetworkPolicy as second layer. |
| ADR-0024 | Events in Avro with CloudEvents binary-mode headers, published by Debezium from the outbox. |
| ADR-0025 | Embedding profile versions map to versioned Qdrant collections in knowledge/memory (alias swap on re-embedding). |

## Proposed Design

### Module layout
| Module | Content |
|---|---|
| `model-gateway-domain` | CatalogEntry, ModelProfile, RoutingRule, ProviderAccount, BudgetPolicy, ModelCall, routing and budget logic; ports `ChatModelPort`, `EmbeddingPort`, `CredentialLeasePort`, `RedactionPort`. No Spring, no vendor SDK. |
| `model-gateway-application` | Invocation use case (route → budget → redact → call → account), embedding use case, profile rollout, reconciliation jobs. |
| `model-gateway-adapters` | REST/SSE inbound; one adapter module per provider family (`adapter-<provider>`, may use Spring AI); connector-service lease client and Vault Transit decrypt client; Redis adapters (budget/quota scripts, throttle keys, concurrency semaphore, shared caches); Kafka (Avro) and outbox; JPA. |
| `model-gateway-bootstrap` | Wiring, virtual threads. |

### Provider adapter port
```java
public interface ChatModelPort {
    ProviderFamily family();
    ChatResult invoke(ChatRequest request, ProviderBinding binding, Deadline deadline) throws ProviderException;
    Flow.Publisher<ChatChunk> stream(ChatRequest request, ProviderBinding binding, Deadline deadline);
    TokenEstimate estimate(ChatRequest request, ModelRef model);      // exact tokenizer when available, else conservative heuristic
}

public interface EmbeddingPort {
    EmbeddingResult embed(EmbeddingRequest request, ProviderBinding binding, Deadline deadline) throws ProviderException;
}

// ProviderBinding = endpoint + model key + secret supplier (platform key delivered from Vault as a file by the
// Vault Secrets Operator/Agent, or in-memory BYOK lease decrypted through Vault Transit)
// ProviderException carries a normalized ErrorClass:
// RATE_LIMITED, UNAVAILABLE, TIMEOUT, CONTEXT_TOO_LONG, CONTENT_FILTERED, INVALID_REQUEST, AUTH_FAILED
```
Normalized request: `messages[]` with roles `SYSTEM, USER, ASSISTANT, TOOL` and content parts `TEXT` / `DATA_BLOCK` (with provenance and `trust`, rendered by the adapter in the provider's safest equivalent), `tools[]` (name, description, JSON Schema), `responseSchema` (structured output), `params` (temperature, topP, maxOutputTokens). Normalized `finishReason`: `STOP, LENGTH, TOOL_CALLS, CONTENT_FILTERED, ERROR, CANCELLED`. Adapters must pass a shared conformance test kit (tool calls, structured output, streaming, usage reporting, error mapping).

### Entities (`waver_model_gateway`)
| Entity | Key fields | Notes |
|---|---|---|
| CatalogEntry | `id, provider_family, model_key, capabilities (TOOLS, STRUCTURED_OUTPUT, STREAMING, VISION, EMBEDDING), context_window, max_output_tokens, embedding_dimension, status (ACTIVE, DEPRECATED, RETIRED)` | WAVER-managed. |
| PricePoint | `id, catalog_entry_id, input_per_1m, output_per_1m, cached_input_per_1m, currency, valid_from` | Money as `numeric(19,4)`; `priceVersionId` recorded on every call. |
| ProviderAccount | `id, owner_type (PLATFORM, ORGANIZATION), org_id (BYOK), provider_family, endpoint, regions[], training_opt_out (CONFIRMED, UNKNOWN), provider_retention_days, secret_ref (Vault KV v2 path of the platform key) or credential_id (BYOK), rpm/tpm limits, status` | Platform traffic only routes to `CONFIRMED` accounts. `endpoint` must match a host pattern declared in the provider family's `ServiceEntry` (validated at registration). |
| ModelProfile / ModelProfileVersion | `key (fast, balanced, high-quality, embedding-default, ...)`; `number, defaults (temperature, maxOutputTokens), allowed_overrides (ranges), routing_rule_set_id, state (CANDIDATE, ROLLING_OUT, ACTIVE, SUPERSEDED), rollout_percent` | What agents reference. |
| RoutingRule | `id, rule_set_id, priority, match (profileKey, orgId?, resellerOrgId?, purpose?, requiredCapabilities, region?), candidates [{catalogEntryId, providerAccountId?, weight}], strategy (PRIORITY, WEIGHTED, CHEAPEST_HEALTHY), version` | Org/reseller rules (incl. BYOK) are evaluated before platform rules. |
| BudgetPolicy | `id, org_id, scope_type (ORGANIZATION, WORKSPACE, DOMAIN, AGENT), scope_id, period (DAY, MONTH), unit (TOKENS), soft_limit, hard_limit, on_hard (BLOCK, DOWNGRADE_PROFILE), downgrade_to` | Customer-defined caps below commercial limits. |
| BudgetCounter | `budget_policy_id, period_start, consumed, reconciled_at` | Authoritative consumed total per period, recomputed every minute from ModelCall rows. The live counter (consumed + in-flight reservations) is in Redis (see Budgets and quotas). |
| ModelCall | `id (callId), org_id, workspace_id, domain_id, caller, purpose, attribution (agentId, agentVersionId, runId, turnId, mode), profile_key, profile_version, provider_account_id, catalog_entry_id, attempts, fallback_used, outcome, finish_reason, input_tokens, output_tokens, cached_input_tokens, usage_estimated, cost, price_version_id, latency_ms, ttft_ms, created_at` | Metadata only, no content. Partitioned daily, retention 90 days (default). |
| DebugCapture | `call_id, org_id, encrypted_payload, expires_at (≤ 7 days)` | Only when explicitly enabled (see Data handling). |

Read models: `entitlement_snapshot` (`waver.entitlement.entitlement-snapshot.v1`), `quota_state` (`waver.usage-metering.quota-state.v1`), `credential_status` (`waver.connector.credential.v1`).

### Invocation pipeline
1. **Admit**: validate request; check `X-Request-Deadline` not passed; `purpose` ∈ `AGENT_TURN, OBJECTIVE_EXTRACTION, POLICY_CLASSIFIER, EVALUATION_JUDGE, SUMMARIZATION, EMBEDDING_*`; attribution present for tenant traffic.
2. **Entitle**: the profile is entitled for the org (snapshot evaluator from `contracts/`, RFC-0013); commercial consumption limits on the model meters are checked in real time against the usage-metering Redis counters `waver:quota:<quotaCounterId>` (counter IDs and limits from `quota_state`, RFC-0020) — `HARD/BLOCK` → `QUOTA_EXCEEDED`.
3. **Resolve profile version**: ACTIVE version, or the ROLLING_OUT candidate when `hash(orgId) % 100 < rollout_percent`, or an org pin.
4. **Route**: collect matching RoutingRules by priority; filter candidates by capability (tools/structured output/context window ≥ estimate + maxOutputTokens), data handling (training opt-out, allowed regions), circuit state, provider throttle and budget mode (DOWNGRADE); order → fallback chain (max 3 candidates).
5. **Budget reserve**: estimate = input estimate + `maxOutputTokens`; one atomic Lua script reserves the estimate on every applicable BudgetPolicy counter (agent, domain, workspace, org — keys share the `{<orgId>}` hash tag); if `consumed + reserved > hard_limit` for any scope, nothing is reserved and the call is `BLOCK`ed (`MODEL_BUDGET_EXCEEDED`) or switched to the `downgrade_to` profile. The commercial quota counter is reserved by a second call through the usage-metering quota client; if that one is denied, the budget reservation is released and the call ends with `QUOTA_EXCEEDED`.
6. **Redact** (`RedactionPort`) per effective DATA_HANDLING policy.
7. **Call** the first candidate; on `RATE_LIMITED`, `UNAVAILABLE`, `TIMEOUT` (before the first streamed token) → next candidate; `CONTEXT_TOO_LONG` → next candidate only if it has a larger window; `INVALID_REQUEST`, `CONTENT_FILTERED` → no fallback; `AUTH_FAILED` on BYOK → no silent fallback to platform keys unless the org enabled it.
8. **Account**: provider-reported usage (or estimate with `usage_estimated=true` if the stream was cut); cost = tokens × PricePoint; settle reservations in Redis with the same script family (`reserved -= est, consumed += actual`, idempotent by `callId`).
9. **Persist & publish**: ModelCall row + outbox `ModelCallCompleted` in one transaction (published by Debezium, ADR-0024); return normalized result.

```mermaid
sequenceDiagram
  participant RT as agent-runtime
  participant MG as model-gateway
  participant CS as connector-service
  participant V as Vault (Transit)
  participant PA as Provider A (primary)
  participant PB as Provider B (fallback)
  RT->>MG: POST /v1/model-invocations {profile: balanced, purpose: AGENT_TURN, stream: true} Idempotency-Key, X-Request-Deadline
  MG->>MG: entitlement (local snapshot), resolve profile version, route, reserve budget + quota (Redis Lua)
  opt BYOK account selected and no valid lease in memory
    MG->>CS: POST /v1/credentials/{id}/leases
    CS-->>MG: {leaseId, ciphertext, expiresAt ≤ 15 min} (audited)
    MG->>V: transit/decrypt/tenant-credentials-llm (context=orgId)
    V-->>MG: plaintext (memory only; Vault audit log)
  end
  MG->>PA: stream request (via Istio egress gateway)
  PA-->>MG: 503 before first token
  MG->>MG: record failure (circuit A), next candidate
  MG->>PB: stream request (via Istio egress gateway)
  PB-->>MG: deltas ... usage
  MG-->>RT: SSE start, delta*, tool_call_delta*, usage, done
  MG->>MG: settle budget + quota (Redis), ModelCall row + outbox
  MG-)RT: (Kafka) ModelCallCompleted → usage-metering, audit
```

### Streaming passthrough
- SSE events to the caller: `start {callId, servedBy}`, `delta {text}`, `tool_call_delta {index, name, argumentsDelta}`, `usage {inputTokens, outputTokens}`, `done {finishReason}`, `error {code, retryable}`.
- Chunks are forwarded as they arrive (no buffering beyond adapter framing); one virtual thread per stream.
- Fallback only before the first `delta`; after that an upstream error ends the stream with `error` and the runtime applies its fallback (RFC-0011).
- Caller disconnect or cancellation aborts the provider stream; the partial usage is still accounted (`outcome=CANCELLED`).

### Token counting and cost
- Pre-call: `estimate()` from the adapter (exact tokenizer when the adapter has one; otherwise a conservative heuristic, default 1 token per 3 characters for pt-BR text) — used for context fitting (`POST /v1/token-counts`, also used by runtime to trim history) and budget reservation.
- Post-call: provider-reported usage is authoritative. Missing usage → estimate, flagged.
- Cost uses the PricePoint valid at call time in its own currency (decimal string); currency conversion and margins are billing concerns (RFC-0020: `model.cost` is internal, never billable). BYOK calls carry `byok=true` and their cost is informative (the customer pays the provider).

### Budgets and quotas
| Layer | Owner of the limit | Counter | Enforcement |
|---|---|---|---|
| Commercial consumption limits (tokens per period, from contracts) | entitlement-service (EntitlementLimit) | usage-metering QuotaCounter: real-time Redis counter `waver:quota:<quotaCounterId>` (owned by usage-metering, operated through its quota client), PostgreSQL `quota_counter` authoritative for billing | HARD/BLOCK or SOFT per RFC-0020; near-exact (overshoot limited to concurrent in-flight reservations, ADR-0020); `quota-state` snapshot as fallback. |
| Customer budgets (org/workspace/domain/agent) | model-gateway BudgetPolicy | model-gateway Redis counters `waver:model-gateway:budget:{<orgId>}:<budgetPolicyId>:<periodStart>` (reservation-based) + PostgreSQL BudgetCounter (authoritative) | soft → `BudgetThresholdReached` (notification); hard → BLOCK or DOWNGRADE_PROFILE. |
| Platform protection | model-gateway config | Redis expirable semaphore `waver:model-gateway:inflight:<orgId>`; per-replica token buckets for provider rpm/tpm | per-org concurrency cap (default 100 in-flight cluster-wide), per-call `maxOutputTokens` ceiling per profile. |
| Run limits | AgentVersion (RFC-0016) | agent-runtime | tokens per run, iterations per turn (RFC-0011). |

**Reconciliation and fallback (ADR-0020).** Redis holds the live value (consumed + in-flight reservations, with per-reservation expiry so a crashed pod's reservations lapse after deadline + 60 s). Every minute a reconciler recomputes `consumed` per BudgetPolicy and period from ModelCall rows into `budget_counter` and corrects the Redis value when it drifts by more than one in-flight estimate. If Redis is unavailable (or a check exceeds 10 ms), model-gateway falls back to the last reconciled `budget_counter` value plus a per-replica local reservation ledger (approximate, the call is flagged `budgetCheck=FALLBACK`) and to the `quota_state` snapshot for commercial quotas; it never blocks traffic on Redis loss alone. A nightly reconciliation compares BudgetCounter totals with usage-metering aggregates for the same scopes and alerts on drift > 1%.

### BYOK
1. Customer admin stores the key in Workspace → workspace-bff → connector-service (`LLM_PROVIDER_KEY` credential under an `llm-provider-<family>` connection; write-only; RFC-0018).
2. workspace-bff → model-gateway `POST /v1/provider-accounts {providerFamily, credentialId, regions}` (owner_type ORGANIZATION). model-gateway leases the key and performs a test call; the account becomes ACTIVE.
3. The org's routing rule (created with the account) places the BYOK account first for selected profiles; platform accounts are fallback only if `byokFallbackToPlatform=true` (default false).
4. At call time the key comes from an in-memory lease (`POST /v1/credentials/{id}/leases`, ADR-0014/ADR-0022): connector-service authorizes and audits the lease and returns the Transit ciphertext; model-gateway decrypts it with `transit/decrypt/tenant-credentials-llm` and `context = orgId` under its Vault role (Kubernetes auth), whose policy allows `decrypt` on that key only — no encrypt, no other Transit key, so it can never read other credential types even with a stolen ciphertext. TTL ≤ 15 min, renewed at 80% of TTL by requesting a new lease, plaintext and ciphertext never persisted, logged, written to disk or placed in Redis/any shared cache; heap dumps disabled in production pods. `CredentialRotated` / `CredentialRevoked` events drop the cached lease immediately.
5. `AUTH_FAILED` from the provider → ProviderAccount `INVALID_CREDENTIAL`, notification to the org admin, no retries with that key.

### Data handling
- **No training**: platform ProviderAccounts must have `training_opt_out = CONFIRMED` (contractual, recorded by WAVER); BYOK accounts record what the customer declares; the Workspace shows the data-handling attributes of the account in use.
- **No tenant mixing**: one request = one tenant; no cross-tenant batching; provider-side prompt caching only with org-scoped prefixes; in-process cache keys include `orgId` (RFC-0010 T12).
- **Caching**: configuration data (catalog, profiles, routing rules, provider accounts, budget policies) is cached in Caffeine (L1) with an optional shared Redis read-through cache `waver:cache:model-gateway:<name>:<id>` (TTL + event-driven eviction, ADR-0020). Model responses, prompts, completions, embeddings and credentials are **never** cached in Redis; there is no response cache in v1.
- **Redaction hook**: `RedactionPort` applies DATA_HANDLING policies (RFC-0016) per org/domain: `NONE`, `MASK`, `TOKENIZE` (reversible within the call; the map lives only in memory and is applied back to the output). Engineering default: payment card numbers are always masked; other identifiers (CPF, CNPJ, e-mail) are not, because objectives legitimately collect them.
- **Logging**: prompts and completions are never logged. ModelCall rows and logs hold metadata only. **Debug capture** can be enabled per agent by an org admin (audited) for ≤ 7 days; payloads are encrypted, visible only to that org's admins and audited support grants, and purged at expiry. Conversation content of record is the runtime's Message table, not the gateway.
- **Residency**: ProviderAccount `regions[]` and RoutingRule region filters allow regional routing once data residency is decided (RFC-0010 open question).

### Embeddings API
- `POST /v1/embeddings {embeddingProfile, purpose (KNOWLEDGE_INGEST, KNOWLEDGE_QUERY, MEMORY_WRITE, MEMORY_QUERY), inputs[] (max 256 items, each ≤ 8k tokens)}` → `{vectors[], modelKey, dimension, profileVersion, usage}`.
- An embedding profile version is pinned to one model and dimension (knowledge/memory record `embedding_model` in each Qdrant point payload and use one versioned collection per model, ADR-0025). **No fallback to a different model**; fallback only to the same model on another ProviderAccount/region. A new embedding model = new profile version + re-embedding into a new versioned Qdrant collection by the owning service's Temporal ingestion workflow, then an atomic alias swap; query-time embeddings use the profile version pinned by the collection behind the alias.
- Ingest purposes run in a separate bulkhead with lower concurrency so bulk ingestion cannot starve query-time embeddings on the hot path (agent-runtime → knowledge → model-gateway = depth 2, RFC-0005).

### Model evaluation and profile changes
1. WAVER Platform Ops creates a CANDIDATE profile version (new routing rule set).
2. evaluation-service runs the WAVER benchmark suites (tool-call accuracy, structured extraction on pt-BR datasets, policy adherence, latency, cost) through model-gateway with header `X-Waver-Profile-Version` (accepted only from the evaluation-service identity).
3. Results attached; approval through approval-service (`MODEL_PROFILE_CHANGE`).
4. Rollout ROLLING_OUT 5% → 25% → 100% of orgs (by hash), default 24h per step, automatic halt on error rate, fallback rate or quality alarms; `ModelProfilePublished` at 100%.
5. Orgs may pin the previous version for up to 90 days (default) and run their own release-gate suites against the candidate.

## APIs / Events / Data Ownership
| Endpoint | Caller | Purpose |
|---|---|---|
| `POST /v1/model-invocations` (JSON, or SSE with `Accept: text/event-stream`) | agent-runtime, evaluation-service, knowledge/memory (summarization) | Chat/completion with tools and structured output. |
| `POST /v1/embeddings` | knowledge-service, memory-service | Embeddings. |
| `POST /v1/token-counts` | agent-runtime, knowledge-service | Estimates. |
| `GET /v1/model-profiles` | agent-builder (also via read model), BFFs | Profiles available to the org (no prices for customers, ADR-0009). |
| `GET /v1/model-calls?runId=` | workspace-bff (run inspector), support | Metadata only. |
| `GET/POST /v1/provider-accounts`, `GET/PUT /v1/budget-policies` | workspace-bff (org-scoped BYOK and budgets) | Customer configuration. |
| `/v1/catalog-entries`, `/v1/price-points`, `/v1/model-profiles/{key}/versions`, `/v1/routing-rules`, platform `/v1/provider-accounts` | control-bff (WAVER Platform Ops) | Platform configuration (audited). |

| Topic | Events | Key |
|---|---|---|
| `waver.model-gateway.model-call.v1` | ModelCallCompleted | callId |
| `waver.model-gateway.model-profile.v1` | ModelProfilePublished, ModelProfileDeprecated | profileKey |
| `waver.model-gateway.budget-policy.v1` | BudgetThresholdReached, BudgetExceeded | budgetPolicyId |

`ModelCallCompleted` payload: `callId, caller, purpose, mode (LIVE, SANDBOX), agentId, agentVersionId, runId, turnId, profileKey, profileVersion, providerFamily, modelKey, providerAccountId, byok, outcome (SUCCESS, ERROR, CANCELLED, DENIED), errorCode, attempts, fallbackUsed, inputTokens, outputTokens, cachedInputTokens, usageEstimated, cost {amount, currency}, priceVersionId, latencyMs, ttftMs, finishReason, occurredAt`; extensions `orgid`, `workspaceid`, `domainid` as CloudEvents binary-mode `ce_*` Kafka headers; payload Avro (`contracts/events/model-gateway/ModelCallCompleted.v1.avsc`, registered in Apicurio Registry by CI; `cost.amount` as a decimal string per CONVENTIONS) (ADR-0024). No content. Consumers: usage-metering (`model.tokens.input`, `model.tokens.output`, `model.cost`, RFC-0020), audit-service, evaluation-service. Profile events use the PLATFORM organization as `orgid` (RFC-0006 §12).

Consumed: `waver.entitlement.entitlement-snapshot.v1`, `waver.usage-metering.quota-state.v1`, `waver.connector.credential.v1` (`CredentialRotated`, `CredentialRevoked`), `waver.connector.connection.v1` (`ConnectionHealthChanged` for BYOK connections).

Sync dependency: connector-service (lease) only. model-gateway is otherwise a leaf on the hot path (RFC-0005 §7); Redis, Vault and the egress gateway are infrastructure calls, not service hops.

## Security & Privacy
- Threat model required (RFC-0010): key exfiltration (BYOK lease in memory only, no heap dumps, no secret in logs), prompt leakage (no content logging), cross-tenant leakage (no batching, org-scoped caches), provider compromise (no-training accounts, data minimisation via redaction).
- Egress (ADR-0026): only model-gateway reaches LLM/embedding provider endpoints, through the Istio egress gateway. Each provider family adapter ships a `ServiceEntry` (exact hosts, or host patterns for providers with per-customer resource subdomains) applied by the pipeline; the egress gateway `AuthorizationPolicy` admits only the model-gateway identity for those hosts. NetworkPolicy denies direct internet egress from the pods as a second layer. ProviderAccount endpoints (platform and BYOK) are validated at registration against the declared patterns, so the host allow-list is no longer maintained in application code.
- Mesh retries are disabled for provider routes; retries and fallback stay in the application (Resilience4j, fallback chain) so budget accounting and "no fallback after first token" remain correct.
- Inbound: Istio STRICT mTLS with a waypoint and request-level `AuthorizationPolicy` (ADR-0026): `/v1/model-invocations` and `/v1/token-counts` only from agent-runtime, evaluation-service, knowledge-service and memory-service; `/v1/embeddings` only from knowledge-service and memory-service; admin endpoints only from control-bff and workspace-bff. Tokens are Keycloak-issued (ADR-0021) and validated by the service as well.
- `X-Waver-Profile-Version` and admin endpoints restricted by service identity (Keycloak client + Istio peer identity) / WAVER roles.
- Vault (ADR-0022): model-gateway's Vault role reads only its own KV paths (platform provider keys) and may only `decrypt` on `tenant-credentials-llm`; every decrypt is recorded by the Vault audit device.
- Customer-facing APIs never expose prices, wholesale costs or other tenants' accounts.

## Reliability & Failure Modes
| Failure | Behaviour |
|---|---|
| Provider outage | Circuit breaker per (ProviderAccount, model): sliding window 50 calls, failure rate ≥ 50% or slow calls ≥ 50% (slow = 2× profile TTFT target) → OPEN 30 s, HALF_OPEN 5 probes; traffic moves to the next candidate. |
| Provider 429 | Respect `Retry-After`; write `waver:model-gateway:throttle:<providerAccountId>:<modelKey>` with TTL = `Retry-After` (read through a 1 s Caffeine L1) so all replicas back off; next candidate. Without Redis, each replica backs off on what it observed. |
| All candidates down | `NO_ROUTE_AVAILABLE` (retryable); runtime sends its fallback message (RFC-0011). |
| Mid-stream failure | `error` event, partial usage accounted; no fallback after first token. |
| connector-service down (BYOK) | Existing leases used until expiry; then `BYOK_CREDENTIAL_UNAVAILABLE` (no silent platform fallback unless enabled). |
| Vault unavailable | BYOK: existing in-memory leases used until expiry, then `BYOK_CREDENTIAL_UNAVAILABLE` (decrypt fails closed). Platform keys already delivered to running pods keep working; new pods cannot start until Vault recovers (tier-0, ADR-0022). |
| Redis unavailable | Budgets fall back to the last reconciled `budget_counter` + per-replica ledger, commercial quotas to the `quota_state` snapshot, concurrency cap and throttle state to per-replica values; calls flagged for reconciliation; no data loss (ADR-0020). |
| Budget counter contention | Reservations are single Redis Lua calls per org hash slot (no database row contention); PostgreSQL is written only by the per-minute reconciler. |
| usage-metering lagging | Real-time commercial checks are unaffected (Redis counters); only billing aggregates and the `quota_state` fallback lag (RFC-0020). |
| Egress gateway unavailable | All candidates fail with `UNAVAILABLE` → `NO_ROUTE_AVAILABLE`; HA gateway replicas and PodDisruptionBudget (platform team). |
| Pod crash during call | Redis reservation lapses at deadline + 60 s (per-reservation expiry inside the script); caller retries with the same `callId` → idempotent replay if the call had completed. |

SLO defaults: availability 99.9% (excluding outages of every candidate of a profile); gateway overhead p95 ≤ 25 ms non-streaming, added time-to-first-token p95 ≤ 50 ms; embeddings overhead p95 ≤ 20 ms; `ModelCallCompleted` publish lag p95 ≤ 2 s.

## Observability
- Span `model.invoke` with child spans per attempt; attributes `profile_key`, `profile_version`, `provider_family`, `model_key`, `attempt`, `fallback_used`, `finish_reason`, `org_id` (no content).
- Metrics: calls by outcome/error class, TTFT and total latency per provider/model, fallback rate, circuit state, tokens and cost per org/profile/provider (WAVER-only dashboards), budget denials and downgrades, lease count and failures, Vault decrypt latency/errors, reservation drift (Redis vs reconciled PostgreSQL), budget/quota checks served by fallback, egress gateway errors per provider host.
- Alerts: fallback rate > 10% for 5 min, circuit OPEN, cost anomaly per org (3× rolling baseline), `usage_estimated` ratio > 2%.

## Testing Strategy
- Adapter conformance kit (WireMock-recorded provider fixtures): tool calls, structured output, streaming framing, usage, error mapping, cancellation.
- Routing property tests: candidate filtering by capability, data handling, circuit and budget mode.
- Budget concurrency tests (Testcontainers Redis): parallel reservations never exceed the hard limit by more than one in-flight estimate; reconciliation corrects injected drift; Redis outage switches to the fallback path.
- Security tests: no content in logs (log capture assertions), lease never persisted (DB and Redis key scan in tests), cross-tenant cache key ArchUnit rule; Vault dev server (Testcontainers) policy tests: model-gateway cannot decrypt `tenant-credentials` or `tenant-credentials-channel`, cross-org context fails.
- Chaos: provider 429/503/slow streams, connector-service down for BYOK, Vault unavailable, Redis unavailable; staging test that a provider host outside the `ServiceEntry` set is blocked by the egress gateway.
- Benchmark suites (evaluation-service) for every profile version change.

## Deployment / Rollout / Rollback
- Service canary 5% with SLO-burn auto-rollback. Adapters ship with the service; a new provider family is a code change plus catalog/routing data plus its `ServiceEntry` (applied before the first ProviderAccount of that family is registered).
- The Helm chart ships model-gateway's Istio `AuthorizationPolicy`, Redis ACL user (`~waver:model-gateway:*`, `~waver:cache:model-gateway:*`, usage-metering quota scripts) and Vault role/policies (policy-as-code, reviewed per release).
- Profile changes are data and follow the evaluated, staged rollout above; rollback = set the previous version ACTIVE (immediate, audited).
- Routing rule changes are versioned; the previous rule set can be restored in one action (emergency "disable provider", RFC-0010).

## Alternatives Considered
- Each service calling providers directly: rejected by ADR-0017.
- Vendor-specific SDKs in domain code: violates DEPENDENCY_RULES.
- Using usage-metering counters for customer budgets: although they are now real-time in Redis (ADR-0020), they model commercial limits owned by entitlement/usage-metering; customer budgets are model-gateway's BudgetPolicy (different owner, scopes and downgrade behaviour), so they keep their own keys and reconciliation.
- Sharded PostgreSQL budget counters updated per call (v1.2, ADR-0013): superseded by Redis reservations; PostgreSQL keeps the reconciled authoritative totals.
- Persisting BYOK keys (encrypted) in model-gateway: violates ADR-0014; model-gateway holds no Transit ciphertext beyond the lease lifetime either.
- Application-level host allow-list as the only egress control (v1.2): replaced by the Istio egress gateway allow-list (ADR-0026).

## Risks
- Profile version changes alter agent behaviour without a new AgentVersion (tension with AR-010); mitigated by evaluation, staged rollout and pinning.
- Token estimates for some providers are imprecise; reservation uses `maxOutputTokens` so hard limits are conservative.
- Fallback to a different model family changes answer style; profiles should list candidates with comparable benchmark scores.

## Open Questions
1. Model routing rules for cost optimization (Scope §24): recommendation — `CHEAPEST_HEALTHY` only within a profile's evaluated candidate set; never cross profiles automatically except via budget `DOWNGRADE_PROFILE`.
2. BYOK fallback to platform keys when the customer key fails: recommendation — off by default; when enabled, usage is billed as platform usage (business decision).
3. Customer budgets unit: tokens (proposed) vs currency; customers do not see WAVER cost (ADR-0006/0009). Recommendation — tokens per profile, with profile weights if needed.
4. MICROSERVICES_MAP lists ModelCatalog/ModelProfile/RoutingRule/ProviderAccount/ModelCall log but not BudgetPolicy/BudgetCounter, nor the events ModelProfilePublished/Deprecated and BudgetThresholdReached/Exceeded; the map should be updated (ADR-0017 already assigns budgets to this service).
5. AR-010 vs. platform-managed profile versions: recommendation — accept, documented as "platform contract changes" with pinning; alternatively record the profile version in the AgentVersion and require redeploy (heavier).
6. Card-number masking as a default redaction is an engineering safety default; product/legal to confirm the default redaction set under LGPD.
7. Data residency / international transfer for provider regions (RFC-0010): routing supports it once decided.
8. Quota client contract for `waver:quota:*` (reserve/settle scripts and which meters model-gateway increments) must be specified by usage-metering in RFC-0020; this RFC assumes model-gateway reserves and settles the `model.tokens.*` counters per call.
9. BYOK providers with customer-specific endpoints need `ServiceEntry` host patterns per provider family; a customer endpoint outside the declared patterns cannot be registered. Confirm with Platform Ops that pattern-level allow-listing is acceptable for each supported family.

## Decision
Proposed: adopt the adapter ports, profile/routing/fallback model, real-time Redis reservation-based budgets alongside usage-metering quotas with PostgreSQL reconciliation, BYOK via the ADR-0014 lease decrypted under a Vault Transit policy, egress through the Istio egress gateway allow-list, metadata-only logging, embeddings without cross-model fallback, and evaluated staged profile rollouts. To be confirmed in architecture review.
