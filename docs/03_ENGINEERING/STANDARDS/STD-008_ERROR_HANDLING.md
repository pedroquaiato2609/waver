# STD-008 - Error Handling Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal, Avro/Apicurio Registry with Debezium, Redis, HashiCorp Vault, Qdrant, Istio ambient, Keycloak step-up).
Version: 1.3
Date: 2026-10-19
Applies to: Java services, BFFs, Kafka consumers, Temporal workflows and activities, tool results returned to LLMs, Angular apps
Related: CONVENTIONS (Errors), AR-006, AR-014, ADR-0020, ADR-0021, ADR-0022, ADR-0023, ADR-0024, ADR-0025, ADR-0026, RFC-0005, RFC-0006, RFC-0008, RFC-0011, RFC-0012, STD-001, STD-002, STD-003, STD-004, STD-006, STD-009

Keywords MUST, MUST NOT, SHOULD follow RFC 2119.

## 1. Taxonomy
Every failure belongs to exactly one category. The category decides HTTP status, retryability, log level and Kafka handling.

| Category | Platform exception | HTTP | Retryable | Log level | Kafka consumer |
|---|---|---|---|---|---|
| Validation | `ValidationFailed` | 400 | No | DEBUG | DLQ immediately |
| Business rule | `BusinessRuleViolation` | 422 | No | DEBUG | Expected outcome → ack (or failure event); unexpected → DLQ |
| Not found | `ResourceNotFound` | 404 | No | DEBUG | Ack if obsolete, else DLQ |
| Conflict | `ConflictDetected` (state, duplicate, version) | 409 / 412 | Only after re-read | INFO | Retry (optimistic lock), then DLQ |
| Unauthenticated | Spring Security | 401 | No (re-auth / step-up) | INFO | n/a |
| Forbidden | `AccessDenied` (permission, entitlement, suspension) | 403 (404 for other tenants' resources) | No | INFO + audit when sensitive | DLQ + security alert |
| Rate / quota | `QuotaExceeded` (Redis quota counters, ADR-0020), edge limiter (Spring Cloud Gateway `RedisRateLimiter`) | 429 + `Retry-After` | Yes, after delay | INFO | Retry with backoff |
| Dependency failure | `DependencyFailure` | 502 / 503 / 504 | Yes if idempotent | WARN | Retry, then DLQ |
| Transient | `TransientFailure` (DB timeout, deadlock, pool exhausted, Redis unavailable for idempotency/locks, schema registry unreachable) | 503 | Yes | WARN | Retry, then DLQ |
| Internal | any other exception | 500 | No | ERROR | DLQ |

## 2. Error codes
- **E-01** Domain error codes are `CONTEXT_REASON` in `UPPER_SNAKE_CASE`: `<CONTEXT>` is the bounded context or business concept (`CONTRACT`, `CUSTOMER`, `ENTITLEMENT`, `AGENT_BUILDER`, `TOOL`), `<REASON>` describes the violated rule: `CONTRACT_ITEM_NOT_AVAILABLE`, `CUSTOMER_SUSPENSION_REASON_REQUIRED`, `AGENT_BUILDER_OBJECTIVE_CYCLE`.
- **E-02** The generic codes of RFC-0008 are a **reserved platform set** without prefix, raised only by `waver-platform-starter`: `VALIDATION_FAILED`, `UNAUTHENTICATED`, `STEP_UP_REQUIRED`, `FORBIDDEN`, `ENTITLEMENT_REQUIRED`, `ORGANIZATION_SUSPENDED`, `NOT_FOUND`, `CONFLICT`, `CONCURRENT_MODIFICATION`, `PRECONDITION_FAILED`, `PRECONDITION_REQUIRED`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_REQUEST_IN_PROGRESS`, `RATE_LIMITED`, `QUOTA_EXCEEDED`, `INTERNAL_ERROR`, `DEPENDENCY_UNAVAILABLE`, `DEPENDENCY_TIMEOUT`, `DOWNSTREAM_SATURATED`, `DEADLINE_EXCEEDED`, `TENANT_CONTEXT_MISSING`, `API_VERSION_SUNSET`. Services MUST NOT define codes that collide with this set.
- **E-03** Codes are public API (AR-015): never renamed or reused with another meaning. Each operation lists its codes in OpenAPI `x-waver-error-codes`; the service keeps the catalogue (code, category, status, description) next to its spec.

## 3. Java: raising and mapping
- **E-04** Domain and application code throw only the platform exception types of section 1 with an `ErrorCode`. The domain knows categories, never HTTP statuses.

```java
public enum ContractErrors implements ErrorCode {
  CONTRACT_NOT_SIGNED(Category.BUSINESS_RULE),
  CONTRACT_ITEM_NOT_AVAILABLE(Category.BUSINESS_RULE);
  private final Category category;
  ContractErrors(Category c) { this.category = c; }
  @Override public Category category() { return category; }
}
throw new BusinessRuleViolation(ContractErrors.CONTRACT_ITEM_NOT_AVAILABLE, "skuId", skuId);
```
- **E-05** The platform starter's `web` module maps exceptions to RFC 9457 problems (`ProblemDetail`) using section 1, adds `code`, `correlationId`, `errors[]`, localizes `title`/`detail` by `Accept-Language`, and logs per STD-006. Services MUST NOT declare their own `@RestControllerAdvice`; library exceptions are mapped through the starter's extension point.

```java
// inside waver-platform-starter (illustrative)
ProblemDetail toProblem(WaverException ex) {
  HttpStatus status = StatusMapping.of(ex.code().category());
  ProblemDetail p = ProblemDetail.forStatusAndDetail(status, messages.detail(ex.code(), ex.args(), locale()));
  p.setType(URI.create("urn:waver:problem:" + ex.code().name()));
  p.setTitle(messages.title(ex.code(), locale()));
  p.setProperty("code", ex.code().name());
  p.setProperty("correlationId", correlation.current());
  if (ex instanceof ValidationFailed v) p.setProperty("errors", v.fieldErrors());   // field, code, message
  return p;
}
```
- **E-06** Bean Validation failures and malformed JSON become `VALIDATION_FAILED` with `errors[]`; unknown exceptions become `INTERNAL_ERROR` with a generic `detail`, and the stack trace is logged once at `ERROR`.
- **E-07** Exceptions are not used for normal control flow. Catch-and-ignore is forbidden; catch-and-wrap MUST keep the cause. Checked library exceptions are wrapped at the adapter (STD-001 J-24).

## 4. Retryable vs non-retryable
- **E-08** Retryable statuses: 429, 503, 504, and 502 for idempotent calls; `Retry-After` is honored within the remaining deadline. 4xx other than 408/429 are never retried, except 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` (retry same key later).
- **E-09** Only idempotent operations are retried: GET, `PUT`/`PATCH`/`DELETE` with `If-Match`, POST with `Idempotency-Key`. Retry, backoff, circuit-breaker, bulkhead and deadline values are the RFC-0005 defaults (exponential from 100 ms, max 1 s, full jitter, max 3 attempts, **single retry layer** per call chain, `X-Request-Deadline`). Istio mesh retries are disabled (ADR-0026), so the application is that layer; inside a Temporal activity the activity `RetryOptions` are the retry layer and Resilience4j retries are off (STD-001 J-41). A non-idempotent call that timed out is reconciled (GET or event), never blindly retried.
- **E-10** Callers translate downstream failures: a downstream 4xx that reflects the caller's own rule becomes the caller's own code; downstream 5xx/timeouts become `DEPENDENCY_UNAVAILABLE`/`DEPENDENCY_TIMEOUT`. Downstream `detail` is never forwarded. BFFs forward `code`, `status`, `errors[]` and `correlationId` unchanged so the UI can localize.
- **E-21** Mesh denials are configuration defects, not business errors: an L4 reset or an L7 `403` without `application/problem+json` from a waypoint (`RBAC: access denied`) on a call inside the declared dependency graph is mapped by the starter `client` module to `DEPENDENCY_UNAVAILABLE` **without retry**, logged at `ERROR` with `mesh_denied=true`, and alerted (STD-007). Services never rely on the mesh to produce WAVER error bodies.
- **E-22** Keycloak: an expired or invalid token yields 401 `UNAUTHENTICATED`; a token whose `acr`/`auth_time` does not satisfy a `stepUp` permission yields 401 `STEP_UP_REQUIRED` (RFC-0004); Keycloak or JWKS unavailability at a BFF login yields 503 `DEPENDENCY_UNAVAILABLE`, while resource servers keep validating with cached JWKS.

## 5. Kafka consumers (STD-004, RFC-0006 §10)
- **E-11** Deserialization/schema errors (corrupt bytes, unknown magic byte, a writer schema the reader cannot resolve), `ValidationFailed` and `NonRetryableEventException` go straight to `<topic>.dlq`; schema-registry unavailability or timeouts while resolving a schema ID are **transient** (never DLQ) and retried per profile; transient and dependency failures follow the consumer profile (ORDERED: blocking retries; UNORDERED: retry topics), then DLQ; optimistic-lock `ConflictDetected` is retried.
- **E-12** Expected business outcomes are not errors: an obsolete event (`aggregateversion` not newer than local state) or an already-applied transition is acknowledged and counted. Process steps that fail for business reasons publish a failure event with the `code` (e.g., `ProvisioningFailed`) from the activity's use case through the outbox instead of using the DLQ (ADR-0023, E-20).
- **E-13** DLQ records keep the original bytes (Avro wire format) and headers (`ce_*`) plus `waver-consumer-group`, exception class, sanitized message (no payload values), attempt count and failure times.

## 5a. Temporal workflows and activities (ADR-0023, STD-001 §9)
- **E-20** Activities map platform exceptions by category: Validation, Business rule, Not found, Forbidden and Conflict-after-re-read are raised as **non-retryable** `ApplicationFailure` whose `type` is the `ErrorCode` name (listed in the activity's `doNotRetry`); Dependency failure and Transient are retryable under the activity `RetryOptions`; Internal is retryable with the same bounded policy and alerts. Workflow code catches `ActivityFailure`, reads the code, compensates and ends the process with a business outcome (failure event through an activity, E-12). Any other exception thrown by workflow code (a bug, a non-determinism error) MUST NOT be caught: it fails the **workflow task**, which Temporal retries until a fixed release is deployed, and pages on-call (STD-007). Workflows are never failed or terminated to "clear" errors without an approved runbook step.
- **E-23** Long waits time out as business outcomes (e.g., approval not received within 7 days → `PROVISIONING_APPROVAL_EXPIRED`), never as Temporal workflow execution timeouts; workflow execution/run timeouts are not set except as an outer safety net documented per workflow.

## 5b. Degradation of shared platform components
- **E-24** Redis (ADR-0020): failures never surface as 500. Caches → miss and load from the owner; edge rate limiting → local limiter (fail-open) and `WARN`; quota checks → PostgreSQL counter/local snapshot; idempotency and distributed locks → `TransientFailure` (503 `DEPENDENCY_UNAVAILABLE`, retryable), because executing without protection could duplicate side effects; BFF session loss → 401 `UNAUTHENTICATED` (re-login).
- **E-25** Vault (ADR-0022): Transit decrypt/sign failures **fail closed** with `DependencyFailure` (503 `DEPENDENCY_UNAVAILABLE`, retryable); there is no fallback to cached plaintext. A tool execution that cannot obtain its credential returns `status: ERROR, errorCode: DEPENDENCY_UNAVAILABLE, retryable: true` to the runtime (section 6). A denied Transit operation (policy) is a security event: `AccessDenied` internally, `INTERNAL_ERROR` externally, alert.
- **E-26** Qdrant (ADR-0025): retrieval failures degrade to PostgreSQL full-text results where the retrieval config allows, otherwise `DependencyFailure`; hits that fail the PostgreSQL re-check are dropped silently (counted), never returned with an error.

## 6. Errors returned to LLM tool calls (RFC-0012)
- **E-14** tool-runtime-service returns every outcome to agent-runtime as the structured result contract of RFC-0012; agent-runtime passes only this object to the model, never exception text or downstream bodies.

```json
{ "status": "ERROR", "errorCode": "INPUT_INVALID", "retryable": false,
  "message": "The field 'date' must be a future date in format YYYY-MM-DD.",
  "fieldErrors": [ { "path": "/date", "code": "VALUE_IN_PAST" } ] }
```
- **E-15** `status` ∈ `SUCCESS`, `ERROR`, `PENDING_APPROVAL`, `OUTCOME_UNKNOWN`. `message` comes from a fixed, localized template per `errorCode` with no interpolation of downstream text; stack traces, hostnames, SQL, HTTP headers, credential identifiers, other tenants' data and policy internals MUST NOT appear. Downstream detail is stored in the ToolExecution record for operators only.
- **E-16** Downstream response bodies and tool outputs are untrusted (prompt-injection risk): delivered to the model only as validated, truncated `data` marked `trust=UNTRUSTED`, never inside error messages. `retryable=false` results MUST NOT be retried automatically; the runtime enforces a per-turn limit on model-initiated retries.
- **E-17** End users never see tool error codes verbatim in agent replies; the agent uses handoff or a safe fallback when it cannot recover (Scope §12).

## 7. User-facing messages (STD-002)
- **E-18** UIs map `code` to a localized message in `libs/i18n` (ID `error.<CODE>`, pt-BR source, en-US second) and `errors[].code` to field messages. Unknown codes fall back to a message per status class. Server `detail` is not shown verbatim to end users.
- **E-19** Every error state shows the `correlationId` as a copyable support reference and offers "try again" only when retryable (E-08). `ENTITLEMENT_REQUIRED` uses the "feature not contracted" pattern of RFC-0021; `STEP_UP_REQUIRED` triggers re-authentication through the BFF.

```typescript
export const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  CONTRACT_ITEM_NOT_AVAILABLE: $localize`:@@error.CONTRACT_ITEM_NOT_AVAILABLE:Este item não está disponível para sua revenda.`,
  CONCURRENT_MODIFICATION: $localize`:@@error.CONCURRENT_MODIFICATION:Este registro foi alterado por outra pessoa. Recarregue e tente novamente.`,
  ENTITLEMENT_REQUIRED: $localize`:@@error.ENTITLEMENT_REQUIRED:Este recurso não está no seu plano. Fale com sua revenda.`,
};
```

## Enforcement
| Rule | Enforced by |
|---|---|
| E-01..E-03 codes | CI check: codes match `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$`, are unique per spec, do not collide with the reserved set, and every thrown `ErrorCode` appears in `x-waver-error-codes` (unit test from `waver-platform-test`) |
| E-04, E-05 mapping | ArchUnit: no `@RestControllerAdvice` outside the platform; domain may not reference `HttpStatus`; conformance suite asserts problem shape |
| E-08..E-10 retries | Platform `client` module defaults (RFC-0005); WireMock fault-injection tests |
| E-11..E-13 consumers | Platform `messaging` error handler (registry errors classified transient); DLQ integration tests (STD-010) |
| E-20, E-23 Temporal | Platform activity interceptor maps `ErrorCode` categories to `ApplicationFailure`; workflow tests with `TestWorkflowEnvironment` and replay tests (STD-010) |
| E-21, E-22, E-24..E-26 platform components | Starter `client`, `redis`, `vault` modules; chaos tests in staging (Redis down, Vault sealed, Qdrant down, mesh policy removed) |
| E-14..E-17 tool results | tool-runtime contract tests; security tests asserting error messages never contain downstream bodies; evaluation red-team suites |
| E-18, E-19 UI | CI check that every code in BFF specs has pt-BR and en-US messages in `libs/i18n` |

## Open Questions
1. Reserved generic codes (E-02) do not follow `CONTEXT_REASON`; confirm the exception or rename them `PLATFORM_*` before contracts are published (see STD-003).
2. RFC-0012 tool error codes (`PERMISSION_DENIED`, `INPUT_INVALID`, `OUTPUT_INVALID`, ...) are a separate namespace for the model-facing contract; confirm they need no context prefix.
