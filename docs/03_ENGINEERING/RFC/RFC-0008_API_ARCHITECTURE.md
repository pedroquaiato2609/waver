# RFC-0008 - API Architecture
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis for edge rate limiting, quota counters and idempotency keys (ADR-0020), Keycloak as token issuer (ADR-0021), Vault Transit for outbound webhook signing (ADR-0022), Temporal for webhook delivery retries (ADR-0023), Istio mTLS and AuthorizationPolicy between services (ADR-0026)).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines WAVER's HTTP API architecture: the three API layers (experience/BFF APIs for the Angular apps, internal service APIs, public API for customers), contract-first OpenAPI in `contracts/openapi`, versioning and deprecation (Deprecation/Sunset headers), the HTTP conventions of CONVENTIONS (RFC 9457 errors with codes, cursor pagination, idempotency keys backed by Redis with a PostgreSQL guard, ETags), rate limits (Spring Cloud Gateway `RedisRateLimiter`) and quotas (Redis real-time counters), authentication and authorization per layer (tokens issued by Keycloak), outbound webhooks to customers (signatures by Vault Transit, retries), documentation generation, CI compatibility checks and Java/TypeScript client generation.

## Context / Problem
ADR-0004 makes REST + OpenAPI the synchronous integration style; ADR-0009 introduces edge-gateway and one BFF per audience so that customers can never reach marketplace/wholesale APIs (ADR-0006); ADR-0021 makes Keycloak the token issuer (identity-access-service owns memberships, roles, permissions and API keys); ADR-0020 adds Redis for exact global edge rate limiting, real-time quota counters and idempotency keys (superseding ADR-0013's approximate in-memory limits); ADR-0022 puts signing keys in Vault Transit; CONVENTIONS fixes paths, JSON style, errors, pagination, idempotency and concurrency. API_GUIDELINES_BASELINE still asks for a compatibility policy, filter/sort conventions and service-to-service auth. Customers also need programmatic access (API channel, Scope §13) and notifications about their resources without polling.

## Goals
- One consistent contract style across ~30 services, enforced by generation and CI, not by memory.
- Audience isolation by construction (ADR-0009) and tenant context only from tokens (CONVENTIONS).
- Safe retries everywhere (AR-006) and safe concurrent edits (optimistic locking).
- Predictable evolution: no breaking change reaches a consumer without a new major version (AR-015).
- A small, stable, documented public surface for customers.

## Non-goals
- GraphQL, gRPC or public SDKs in v1.
- Inter-service resilience defaults (timeouts, retries, circuit breakers): RFC-0005.
- Business rate plans and quota values: commercial decisions expressed as Entitlements (RFC-0013, RFC-0020).
- Frontend state management and design system: RFC-0021.

## Requirements / ARs
| AR | Mechanism |
|---|---|
| AR-004 / AR-015 | Contract-first OpenAPI; major version in path; CI breaking-change gate. |
| AR-006 | `Idempotency-Key` handling in the platform starter; event-derived keys for service calls. |
| AR-008 | Tenant context from the validated token only; never from body or client headers. |
| AR-009 | Sensitive endpoints flagged `x-waver-audit: true`; starter emits audit events. |
| AR-011 | Entitlement checks in owning services; customer-facing errors never reveal commercial data. |
| AR-013 | Data-plane APIs depend only on local read models for authorization decisions. |

## Proposed Design

### 1. API layers
| Layer | Consumers | Exposure | Path style | AuthN | Versioning |
|---|---|---|---|---|---|
| Experience (BFF) | Angular apps `control`, `partner`, `workspace` (RFC-0021) | `app.<domain>` (the three apps by path/host, CONVENTIONS) via ingress → edge-gateway → `*-bff` | `/api/<screen-or-resource>` | OIDC session cookie held by the BFF; Keycloak login (ADR-0021), session in Redis (ADR-0020) | Not versioned; must stay compatible with the previous frontend release (N-1) |
| Internal service | BFFs and other services | Cluster-internal only (`<service>.<namespace>.svc`) | `/v{major}/{resource}` (CONVENTIONS) | JWT bearer issued by Keycloak (client credentials or token exchange) over Istio mTLS | Major in path |
| Public | Customer systems, API channel | `api.<domain>` → edge-gateway → `workspace-bff` public routes (ADR-0009) | `/v{major}/{resource}` | API key (validated at edge, exchanged for a Keycloak-issued JWT) or OAuth client credentials of a customer service account | Major in path; formal deprecation policy (§4) |
| Machine / anonymous (hooks) | Channel providers, connector webhooks, generic inbound webhooks, Web Chat widget (anonymous end users) | `hooks.<domain>` → edge-gateway → **directly** to channel-service, connector-service or trigger-service, never to a BFF (ADR-0009 amendment) | Service-prefixed: `/connectors/v1/webhooks/{pathToken}` (RFC-0018), `/channels/v1/...` (provider callbacks, widget), `/triggers/v1/...` | Provider signature verification in the owning service; widget: public widget key + origin allow-list | Major in path; provider-facing paths change only with provider migration |

Domain names are open (branding/cloud). Internal APIs are never routed through the ingress. Routes on `hooks.` expose only the explicitly listed machine/anonymous endpoints, with strict per-route rate and body-size limits, and never any commercial or administrative route. BFFs expose only screen-oriented compositions; they contain no business rules (ADR-0009).

Indicative public API v1 surface (details owned by RFC-0011, RFC-0018, RFC-0020):

| Method / path | Purpose | Notes |
|---|---|---|
| `POST /v1/agents/{agentId}/conversations` | Start a conversation on the API channel | `Idempotency-Key` required |
| `POST /v1/conversations/{conversationId}/messages` | Send a message; `Accept: text/event-stream` streams the reply | `Idempotency-Key` required |
| `GET /v1/conversations/{conversationId}/messages` | List messages | Cursor pagination |
| `GET /v1/runs/{runId}` | AgentRun status and outcome | ETag |
| `GET /v1/usage/summary?period=YYYY-MM` | Usage vs limits for the caller's org | No prices unless the reseller exposes billing |
| `POST/GET/DELETE /v1/webhook-subscriptions[/{id}]` | Manage outbound webhooks (§9) | Public verification keys at `GET /v1/webhook-subscriptions/{id}/signing-keys` |
| `POST /v1/knowledge-bases/{kbId}/documents` | Upload a document (multipart, ≤ 20 MB) | 202 + ingestion status resource |

### 2. Contract-first OpenAPI
- One OpenAPI 3.1 document per service and major: `contracts/openapi/<service>/v<N>.yaml` (ADR-0015); BFFs use `contracts/openapi/<bff>/v1.yaml` with `/api` paths; the public API is `contracts/openapi/public/v1.yaml` (implemented by `workspace-bff`).
- Shared components in `contracts/openapi/common/v1.yaml`: `Problem`, `Money`, `CursorPage`, standard headers and parameters, tenant-free ID formats.
- Server side: OpenAPI Generator `spring` with `interfaceOnly=true` generates controller interfaces and DTOs in the `adapters` module; DTOs are mapped to application commands (MapStruct). Entities are never exposed (API_GUIDELINES_BASELINE).
- Required vendor extensions: `x-waver-audience` (`internal`, `control`, `partner`, `workspace`, `public`), `x-waver-idempotency` (`required|optional|none`), `x-waver-audit`, `x-waver-classification` per schema property (RFC-0007), `x-waver-error-codes` per operation.

### 3. HTTP conventions (from CONVENTIONS, made concrete)
| Concern | Rule |
|---|---|
| Naming | Plural kebab-case resources; `camelCase` JSON; `UPPER_SNAKE_CASE` enums; IDs UUIDv7 strings; timestamps ISO-8601 UTC `Z`; money `{amount: "decimal string", currency}`. |
| Non-CRUD actions | Modeled as creating a noun sub-resource: `POST /v1/customers/{id}/suspensions`, `POST /v1/contracts/{id}/activations`. No verbs in paths. |
| Partial update | `PATCH` with `application/merge-patch+json` (RFC 7396). |
| Long-running work | `202 Accepted` + `Location` of the process resource (e.g., `/v1/provisioning-jobs/{id}`) + `Retry-After`. |
| Pagination | `?limit=50&cursor=...` (max 200); response `{ "items": [...], "nextCursor": "..." }`; cursor is opaque (base64url of last sort key + id + filter hash); no total counts by default (`?includeTotal=true` only where cheap). |
| Filtering / sorting | Exact-match filters as field-named query params (`?status=ACTIVE&workspaceId=...`); ranges `createdAtFrom` / `createdAtTo`; `sort=createdAt:desc,name:asc` limited to documented indexed fields; free text `q=` only where a search index exists. |
| Concurrency | Strong `ETag: "<version>"` on single resources; `If-Match` required on `PUT/PATCH/DELETE` → `428 PRECONDITION_REQUIRED` if missing, `412 PRECONDITION_FAILED` on mismatch; `If-None-Match` → `304`. |
| Correlation | `traceparent` propagated; `X-Correlation-Id` accepted from trusted callers, generated at edge otherwise, echoed in responses and in every problem body. |
| Deadlines | `X-Request-Deadline` (absolute UTC timestamp, CONVENTIONS) is propagated on every internal call. Edge-gateway sets it per route (RFC-0005 defaults: UI 10 s, public API 30 s, streaming 120 s) and strips any client-supplied value. Services do not start work after the deadline (`504 DEADLINE_EXCEEDED`) and pass the remaining budget downstream (RFC-0005 §6). |
| Language | `Accept-Language` (`pt-BR` default, `en-US`) affects `title`/`detail` only; `code` is stable. |
| Limits | JSON body ≤ 1 MiB, uploads ≤ 20 MiB, headers ≤ 16 KiB, URL ≤ 4 KiB; route deadlines per RFC-0005 (UI 10 s, public API 30 s, streaming 120 s per request; long SSE conversations reconnect with `Last-Event-ID`). |

**Idempotency** (platform starter; Redis fast path per ADR-0020 + PostgreSQL guard in the service DB):
- Scope `(org_id, principal_id, key)`; stored: method + route template, SHA-256 of the canonical body, status (`IN_PROGRESS`, `COMPLETED`), response status/headers/body; TTL 24 h by default (CONVENTIONS), longer where the endpoint documents it (e.g., 7 days for tool executions, RFC-0012).
- **Redis (fast path)**: key `waver:idem:<service>:<sha256(orgId|principalId|Idempotency-Key)>` (no raw tenant values or PII in the key). On arrival the starter does `SET ... NX` with `{status: IN_PROGRESS, requestHash}` and a TTL equal to the request deadline; on completion it stores `{COMPLETED, requestHash, response}` with the endpoint TTL (responses > 64 KiB are not cached; the replay re-reads the created resource). Used for replay of completed responses and detection of concurrent duplicates across pods.
- **PostgreSQL guard** (table `idempotency_record`, RFC-0007): for operations whose duplicate would create a business side effect (endpoints with `x-waver-idempotency: required`), the use case inserts `(org_id, principal_id, idem_key, request_hash, resource_ref, created_at)` **in the same transaction** as the business change; the unique key makes a duplicate impossible even if Redis lost the key. The row is also the durable record when the response is needed for audit (ADR-0020). Rows are purged after the endpoint TTL.
- Same key + same hash → replay stored response with `Idempotent-Replayed: true`; same key + different hash → `422 IDEMPOTENCY_KEY_REUSED`; concurrent duplicate while in progress → `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`; missing on `x-waver-idempotency: required` → `400 IDEMPOTENCY_KEY_REQUIRED`.
- Redis unavailable: the PostgreSQL guard alone applies (a concurrent duplicate gets `409` from the unique violation; a completed duplicate is answered from `resource_ref`); no duplicate side effect is possible, only the replay is slower.
- Service-to-service calls triggered by events use the triggering event ID (`ce_id`) as key (RFC-0006 §9). 5xx responses are not stored (client may retry).

**Errors** (RFC 9457 `application/problem+json`):
```json
{
  "type": "urn:waver:problem:CONTRACT_ITEM_NOT_AVAILABLE",
  "title": "Contract item not available",
  "status": 422,
  "detail": "The SKU is not available to this reseller's catalog.",
  "instance": "/v1/contracts/019a0c41-22d0-7a9e-8c11-0d6b9e2f4a77/items",
  "code": "CONTRACT_ITEM_NOT_AVAILABLE",
  "correlationId": "019a0c5e-8e01-7d20-b3c4-1a2b3c4d5e6f",
  "errors": [ { "field": "items[0].skuId", "code": "NOT_AVAILABLE", "message": "SKU not assigned to reseller" } ]
}
```
| Status | Generic code | Use |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_REQUIRED` | Syntax/schema errors (`errors[]` filled) |
| 401 | `UNAUTHENTICATED` | Missing/invalid token or key |
| 403 | `FORBIDDEN`, `ENTITLEMENT_REQUIRED`, `ORGANIZATION_SUSPENDED` | Permission, entitlement (AR-011), suspension (AR-012) |
| 404 | `NOT_FOUND` | Also returned for resources of other tenants (no existence leak) |
| 409 | `CONFLICT`, `IDEMPOTENCY_REQUEST_IN_PROGRESS`, `CONCURRENT_MODIFICATION` | State conflicts |
| 412 / 428 | `PRECONDITION_FAILED` / `PRECONDITION_REQUIRED` | ETag handling |
| 422 | Domain codes (`CONTRACT_ITEM_NOT_AVAILABLE`), `IDEMPOTENCY_KEY_REUSED` | Business rule violations |
| 429 | `RATE_LIMITED`, `QUOTA_EXCEEDED` | With `Retry-After` when known |
| 500 / 503 / 504 | `INTERNAL_ERROR`, `DEPENDENCY_UNAVAILABLE`, `DEPENDENCY_TIMEOUT`, `DEADLINE_EXCEEDED` | No stack traces or internal names |

Domain codes are `UPPER_SNAKE_CASE`, prefixed by the business concept, declared in the service's OpenAPI (`x-waver-error-codes`) and never reused with a different meaning. Customer-audience problems (workspace-bff, public API) never contain SKU, price, reseller margin or marketplace information: `ENTITLEMENT_REQUIRED` says only that the capability is not in the plan and to contact the reseller (Commercial §18).

### 4. Versioning and deprecation
- Non-breaking within a major: new endpoints, new optional request fields, new response fields, new values in enums documented as extensible (`x-extensible-enum`), relaxed validation. Clients must ignore unknown fields and tolerate unknown extensible enum values.
- Breaking (new major): removing/renaming fields or endpoints, type/format changes, new required inputs, stricter validation, changed status codes or semantics, changed auth requirements, changed pagination/sort defaults.
- Internal APIs: a new major runs side by side with the old one; the old one is removed when the monorepo dependency graph shows no generated-client consumer (CI report), minimum one release of overlap.
- Public API (engineering default, business to confirm): old major supported ≥ 12 months after the successor is GA. From announcement, responses carry `Deprecation: @<epoch-seconds>` (RFC 9745), `Sunset: <HTTP-date>` (RFC 8594) and `Link: <docs-url>; rel="deprecation"`, `<successor>; rel="successor-version"`. Customer admins and resellers are notified (notification-service); usage of deprecated versions per org is tracked (RFC-0009) to drive outreach. After sunset: `410 Gone` with code `API_VERSION_SUNSET`.
- BFF APIs: expand/contract across two releases so the previous Angular build keeps working during rollout.

### 5. Rate limits and quotas
| Layer | Mechanism | Scope / key | Default (engineering) | Response |
|---|---|---|---|---|
| Edge (exact, global, ADR-0020) | Spring Cloud Gateway `RedisRateLimiter` (token bucket evaluated atomically in Redis); fallback: local in-memory limiter per replica (limits divided by replica count) when Redis is unavailable (fail-open to the local limiter, never to "unlimited") | Unauthenticated: client IP; app hosts: session; public: API key / client ID; plus per org and per route | Public 20 req/s burst 40 per key; login endpoints 10 req/min per IP (in addition to Keycloak brute-force detection); app hosts 50 req/s per session | `429 RATE_LIMITED` + `Retry-After`, `RateLimit-Policy`/`RateLimit` headers (IETF draft) |
| Service protection | Resilience4j bulkheads and concurrency limits per caller | Per dependent service / per org on hot paths | Sized per service in its runbook | `503 DEPENDENCY_UNAVAILABLE` or `429` |
| Commercial quotas (authoritative for billing in PostgreSQL) | Entitlement limits + usage-metering real-time counters in Redis (`waver:quota:<quotaCounterId>`, `INCRBY` + Lua check, ADR-0020) checked by the data plane; PostgreSQL `quota_counter` reconciled every minute (RFC-0020); fallback: PostgreSQL counter and the local `quota_state` read model | Org / workspace / agent per meter | From contract (business) | `429 QUOTA_EXCEEDED` (no retry until period reset; `Retry-After` set when reset time is known) |

Edge limits protect the platform; they are not a billing control. Rate-limit keys contain only IDs or hashes (API key ID, session ID hash, client IP), never PII. HARD quotas are near-exact: overshoot is limited to concurrent in-flight calls (ADR-0020); during a Redis outage enforcement falls back to the PostgreSQL counter and local snapshot with the tolerance defined in RFC-0020.

### 6. Authentication and authorization per layer
| Hop | AuthN | AuthZ | Tenant context |
|---|---|---|---|
| Browser → BFF | OIDC Authorization Code + PKCE executed by the BFF against Keycloak (realm `waver`, ADR-0021); tokens kept server-side in the BFF session (Spring Session Data Redis, ADR-0020); HttpOnly, Secure, SameSite cookie; CSRF token on state-changing calls; MFA (TOTP/WebAuthn) and step-up enforced by Keycloak for the roles identity-access-service marks as requiring them | BFF: audience gate (only users of that audience's org types), screen-level role checks for UX only | From the session's access token |
| BFF → service | OAuth 2.0 Token Exchange (RFC 8693, Keycloak standard token exchange): user token exchanged for a service-audience token with `act` = BFF; exchanged tokens cached in the session until expiry | **Authoritative** in the service: permissions, `ws_ids`, resource ownership, entitlements | `org_id`/`org_type`/`ws_ids` claims (Keycloak protocol mapper, values owned by identity-access-service) |
| Service → service | `client_credentials` of the service's Keycloak client (`private_key_jwt`, key in Vault) or exchanged token when acting for a user; audience-restricted, 5-min tokens. Transport: Istio ambient STRICT mTLS; the mesh AuthorizationPolicy admits only callers declared in the RFC-0002 §4 dependency graph (ADR-0026) | Callee checks caller client ID against an allow-list per endpoint plus the user permissions when `act` is present; the mesh check is an additional layer, never a replacement | Claims of the presented token; system calls carry an explicit `org_id` in an exchanged token issued for that org |
| Public API | API key (identity-access-service ApiKey) validated by edge-gateway against identity-access-service and exchanged for a 5-min access token issued by Keycloak for the key's ServiceAccount (cached per replica until expiry; mechanism in RFC-0004), or OAuth `client_credentials` of a customer service account (Keycloak client in the customer's Keycloak organization) | Same as BFF → service; keys carry roles and optional workspace restriction | Organization of the key/service account |
| `hooks.` → channel / connector / trigger services | Provider signature (HMAC or provider scheme) + replay window, verified by the owning service; Web Chat widget: public widget key + origin allow-list, anonymous end-user session issued by channel-service | Endpoint/route → Connection or Channel → Organization; no access to any other route | Resolved from the WebhookEndpoint / Channel configuration, never from payload |

Edge-gateway strips any client-supplied `X-Org-*`, `X-Tenant-*`, `X-User-*` headers, and `X-Correlation-Id` on `api.`/`hooks.` hosts. API keys are stored hashed (SHA-256 of a 256-bit secret), shown once, revocable; revocation takes effect within token lifetime (≤ 5 min).

### 7. Documentation
- Every operation has summary, description, examples for request/response/problem, error codes and required scopes/permissions. Lint rules in `tooling/api-lint` (tool selection via STD-013) enforce CONVENTIONS (casing, pagination shape, problem responses, idempotency on POST, ETag on GET-by-ID).
- HTML reference generated with OpenAPI Generator (`html2`) for every spec at build time; internal docs published to the internal developer portal; public docs (public spec only, including `webhooks`) published to the customer developer site. Hosting is open (cloud decision).
- Changelog per spec generated from the CI diff (§8) and attached to the release notes.

### 8. Compatibility checks in CI
- For each changed `contracts/openapi/**/v<N>.yaml`: diff against `main` (OpenAPI diff tool admitted via STD-013); any breaking change without a new major file fails the build. Exceptions require an Architecture-approved label and are impossible on the public spec.
- Monorepo consumer check: all generated clients are rebuilt and every consumer service/app compiles against them (path-based CI includes consumers of changed contracts).
- Provider tests validate real responses against the spec; consumers use WireMock stubs generated from spec examples.
- ADR-0009 check: `workspace-bff` and the public spec must not reference marketplace, catalog-pricing or reseller clients.

### 9. Outbound webhooks to customers
- Ownership (MICROSERVICES_MAP): `notification-service` owns `WebhookSubscription` (org, URL, event types, status) and `DeliveryAttempt`; `connector-service` owns the subscription's **signing key**, held in Vault Transit (ADR-0022; the key never leaves Vault), and performs the HTTP delivery through its SSRF-guarded egress (RFC-0010, RFC-0018) via the Istio egress gateway (ADR-0026). Only connector-service, model-gateway-service and channel-service have general internet egress.
- Payload: CloudEvents JSON (structured mode over HTTP) with curated public types (`waver.public.<EventName>.v<N>`), declared in the `webhooks` section of `contracts/openapi/public/v1.yaml`. Internal events (Avro on Kafka, RFC-0006) are never forwarded as-is.
- Signing key: the Vault Transit **Ed25519** key `outbound-webhooks` (ADR-0022); connector-service's Vault policy allows only `sign` on it (rotation is a platform pipeline operation). Customers retrieve the public verification keys (current and previous key version) at `GET /v1/webhook-subscriptions/{id}/signing-keys` (the same keys for every subscription; also published with the public docs); there is no shared secret to show or leak. Each signature covers the `Webhook-Id`, timestamp and body, so a signature cannot be replayed for another delivery.
- Headers: `Webhook-Id` (= event id, dedup key), `Webhook-Timestamp` (epoch seconds), `Webhook-Signature: v1a,<base64(Ed25519 signature over id + "." + timestamp + "." + body)>` (asymmetric scheme of the Standard Webhooks convention). Receivers should reject timestamps older than 5 minutes. On key rotation (Transit key version) both signatures (new and previous version) are sent for 24 h.
- Delivery: at-least-once, no ordering guarantee (payload carries `time` and resource `version`). HTTPS only, no redirects followed, connect timeout 5 s, response timeout 10 s, any 2xx = success, `410` disables the subscription. Retry schedule (default): 1 min, 5 min, 30 min, 2 h, 6 h, 12 h, 24 h, 24 h (≈ 3 days); then `FAILED`; a subscription with all deliveries failing for 72 h is set `DISABLED` and customer admins are notified.
- Retries are a **Temporal workflow** per DeliveryAttempt owned by notification-service (namespace `waver-platform`, task queue `notification-service.webhook-delivery`, ADR-0023): durable timers implement the schedule; the delivery activity calls connector-service with `Idempotency-Key = deliveryAttemptId + attempt`; the result activity updates `DeliveryAttempt` and writes outbox rows. The workflow ID is `WebhookDelivery:<deliveryAttemptId>` (CONVENTIONS), so a redelivered Kafka event cannot start a second delivery.

```mermaid
sequenceDiagram
  participant P as Producing service
  participant K as Kafka
  participant N as notification-service (webhook delivery workflow, Temporal)
  participant C as connector-service
  participant V as Vault Transit
  participant X as Customer endpoint
  P->>K: domain event (e.g., AgentRunCompleted)
  K->>N: consume; match subscriptions of ce_orgid
  N->>N: build public CloudEvent; DeliveryAttempt PENDING; start workflow (id = WebhookDelivery:<deliveryAttemptId>)
  N->>C: activity: POST /v1/outbound-deliveries (Idempotency-Key, subscriptionId)
  C->>C: SSRF checks
  C->>V: transit/sign/outbound-webhooks (Ed25519)
  V-->>C: signature (key never leaves Vault)
  C->>X: HTTPS POST via egress gateway (Webhook-Id, -Timestamp, -Signature)
  X-->>C: 2xx / error / timeout
  C-->>N: result
  N->>N: SUCCEEDED, or durable timer until next retry (workflow)
```

### 10. Client generation
- Java: OpenAPI Generator `java` (library `restclient`, Spring `RestClient`) per internal service spec and major → Maven artifact `br.com.waver.contracts:<service>-client-v<N>`. Clients are wrapped by the platform starter (token propagation/exchange, `traceparent`, timeouts, Resilience4j policies from RFC-0005). Services depend only on these artifacts, never on another service's modules (ADR-0015). Where an owner also ships read-only logic for its published language (e.g., the entitlement snapshot evaluator), it is a published client library under `contracts/clients/<service>-client/` (ADR-0015), owned and versioned by that team.
- TypeScript: OpenAPI Generator `typescript-angular` for BFF specs only → `frontend/libs/api-clients/<bff>`; lint rules ensure each app imports only its own BFF client (RFC-0021). Angular never calls internal services or the public API.
- Public SDKs: not in v1; the public spec is published so customers can generate clients.

## APIs / Events / Data Ownership
- Specs: `contracts/openapi/<service>/v<N>.yaml`, `contracts/openapi/common/v1.yaml`, `contracts/openapi/public/v1.yaml`.
- Platform table per service: `idempotency_record` (RFC-0007 ownership rules); Redis keys `waver:idem:<service>:*` owned by the same service (ADR-0020).
- Webhooks: `WebhookSubscription`, `DeliveryAttempt` and the delivery workflow (notification-service); signing with the Vault Transit key `outbound-webhooks`, used only by connector-service. Events consumed for webhooks: curated subset of EVENT_CATALOG.

## Security & Privacy
- TLS 1.2+ at ingress (HSTS on all hosts); security headers (CSP, frame-ancestors, `X-Content-Type-Options`) on app hosts set at edge (RFC-0010).
- No PII or secrets in URLs or query strings; sensitive identifiers go in bodies. Request/response bodies are not logged (RFC-0009).
- `404` instead of `403` for cross-tenant resource IDs. Uniform error timing for authentication failures.
- Internal APIs are reachable only from callers admitted by the destination's Istio AuthorizationPolicy (generated from the RFC-0002 §4 graph) over STRICT mTLS; NetworkPolicies remain a second layer (ADR-0026, RFC-0010). JWT validation stays mandatory in every service.
- Webhook signing keys never leave Vault (Transit); idempotency and rate-limit keys in Redis contain no PII (ADR-0020).
- Classification-aware DTOs: fields marked `RESTRICTED` are excluded from list endpoints unless explicitly requested and permitted.

## Reliability & Failure Modes
| Failure | Handling |
|---|---|
| Client retries after timeout | Idempotency replay; no duplicate side effects. |
| Lost update | ETag/If-Match → `412`. |
| Downstream service down | BFF returns partial screen data where designed, otherwise `503 DEPENDENCY_UNAVAILABLE` with correlationId; circuit breakers per RFC-0005. |
| Redis unavailable | Edge falls back to the local per-replica limiter; idempotency falls back to the PostgreSQL guard; BFF users re-login once Redis is back; quotas fall back to PostgreSQL counters and local snapshots (ADR-0020). |
| Keycloak unavailable | Existing sessions and cached exchanged tokens keep working until expiry; new logins and token exchanges fail with `503 DEPENDENCY_UNAVAILABLE` (tier-0 SLO, RFC-0009). |
| Vault unavailable | Webhook deliveries cannot be signed; the delivery workflow retries on its schedule (no unsigned delivery is ever sent). |
| Webhook endpoint down | Retry schedule (Temporal durable timers), then disable + notify; no impact on producers (async). |
| Breaking change merged by mistake | Blocked by CI diff; if released, roll back the provider (expand/contract guarantees safe rollback). |

## Observability
RED metrics per route template and caller (`http.server.requests` with `uri`, `status`, `code`, `client_id` tags; no org tags on technical metrics, RFC-0009); idempotency replays/conflicts (Redis hit, PostgreSQL guard hit); rate-limit rejections by key class and `RedisRateLimiter` fallback activations; deprecated-version calls by org (business metric); webhook delivery success rate, latency, retries and signing latency (Vault Transit) per subscription (aggregated, no org tags on technical metrics).

## Testing Strategy
- Spec lint and compatibility diff on every PR; generated server interfaces make missing endpoints a compile error.
- Provider response validation, consumer WireMock tests, BFF audience E2E tests (Playwright) including "customer cannot reach marketplace data" (ADR-0009).
- Conformance suite in the platform starter: problem format, idempotency semantics (including Redis loss: the PostgreSQL guard still prevents duplicates), ETag rules, pagination, header stripping.
- Edge rate-limit tests against a Redis Testcontainer (global limit across replicas; fallback when Redis is stopped).
- Webhook signature test vectors (Ed25519 public key, payload, expected signature) published with the public docs.

## Deployment / Rollout / Rollback
- New majors deploy alongside old ones; routes for public majors are added at edge per version.
- Edge and BFF configs are versioned in `deploy/helm`; limit changes are config releases with rollback.
- Public API GA requires: docs published, rate limits configured (Redis-backed), webhook signing vectors, deprecation policy approved.

## Alternatives Considered
- Single gateway with role filters instead of BFFs: rejected (ADR-0009).
- Offset pagination: rejected by CONVENTIONS (unstable under inserts, slow at depth).
- Header-based versioning: harder to route at edge and to see in logs; path versioning chosen (CONVENTIONS).
- Webhook delivery by notification-service directly: would need internet egress and signing-key rights outside connector-service (conflicts with ADR-0022 ownership and the RFC-0010 egress rule).
- HMAC shared-secret signatures (v1.2 design, `v1` scheme): familiar to receivers, but the secret must leave Vault to be shown to the customer, which ADR-0022 forbids for signing keys; Ed25519 via Transit chosen (see Open Questions).
- In-memory per-replica edge limits (ADR-0013, v1.2 design): no extra component, but approximate; superseded by `RedisRateLimiter` (ADR-0020), kept only as the fallback.
- Idempotency in Redis only: fast, but a Redis loss would allow duplicate side effects; the PostgreSQL guard keeps correctness (ADR-0020 rule).

## Risks
- BFFs accumulating business logic; mitigated by review rules and ArchUnit (BFFs have no persistence besides session).
- API-key exchange at edge adds a dependency on identity-access-service and Keycloak for cold keys (tier-0 SLO, RFC-0009).
- Redis becomes part of the edge request path; its latency and availability directly affect every public request (fallback limits it to degraded limits, not outages).
- Webhook delivery through two services adds latency and a cross-service dependency.

## Open Questions
1. ~~Confirm ownership of outbound webhooks~~ — resolved: MICROSERVICES_MAP gives subscriptions to notification-service and delivery to connector-service; the signing key is now a Vault Transit key managed by connector-service (ADR-0022). MICROSERVICES_MAP should list notification-service → connector-service as a sync dependency.
2. ~~Owner of generic inbound webhook URLs~~ — resolved in EVENT_CATALOG §7 (5): connector-service owns connector/provider webhooks, trigger-service owns customer-defined generic webhook triggers.
3. Public API deprecation window (≥ 12 months proposed) and whether public API access is itself an entitlement (business).
4. Are customers allowed to see monetary usage/billing through the public API, or only units (reseller choice per Commercial §18)?
5. Public developer portal hosting and domain names (cloud/branding decisions).
6. Ed25519 (`v1a`) webhook signatures: confirm that target customers' receivers can verify asymmetric signatures, or offer an opt-in HMAC scheme per subscription using an exportable Transit HMAC key shown once (would need an ADR-0022 exception). A per-subscription key (instead of the shared `outbound-webhooks` key) would also allow revoking one customer's verification key independently.
7. ADR-0023's table does not list webhook delivery; confirm the notification-service delivery workflow as a Temporal use (this RFC proposes it) or an alternative retry mechanism.

## Decision
Pending review. Proposed: adopt the three-layer model, conventions, versioning/deprecation policy, rate-limit layering, webhook design and generation pipeline described above.
