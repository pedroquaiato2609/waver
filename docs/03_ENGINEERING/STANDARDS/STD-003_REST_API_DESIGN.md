# STD-003 - REST API Design Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis for the idempotency fast path, Keycloak tokens, Temporal for long-running operations).
Version: 1.3
Date: 2026-10-19
Applies to: every HTTP API: internal service APIs, BFF APIs (ADR-0009) and the public API exposed by edge-gateway
Related: CONVENTIONS (HTTP), API_GUIDELINES_BASELINE, ADR-0004, ADR-0006, ADR-0009, ADR-0023, AR-004, AR-006, AR-015, RFC-0005, RFC-0008, STD-008, STD-009

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. This standard turns CONVENTIONS and RFC-0008 into checkable rules; if they disagree, CONVENTIONS wins, then RFC-0008.

## 1. API layers (RFC-0008 §1)
| Layer | Path | Consumer | Versioning |
|---|---|---|---|
| Internal service API | `/v{major}/{resource}`, cluster-internal only | BFFs, other services | Major in path |
| BFF API | `/api/<screen-or-resource>` on the app host | only its own Angular app | Not versioned; MUST stay compatible with the current and previous frontend release (N-1) |
| Public API | `api.<domain>/v{major}/{resource}` → `workspace-bff` public routes | customer systems, API channel | Major in path; formal deprecation |

## 2. Resource modeling and URLs
- **R-01** Resources are plural kebab-case nouns, nested at most two levels: `/v1/contracts/{contractId}/items/{itemId}`. Path parameters are named `<resource>Id`.
- **R-02** No verbs in paths. Non-CRUD actions create a noun sub-resource that records the action: `POST /v1/customers/{customerId}/suspensions`, `POST /v1/contracts/{contractId}/activations`. The sub-resource carries reason, actor and effective date and can be listed.
- **R-03** JSON fields `camelCase`; enums `UPPER_SNAKE_CASE`; timestamps ISO-8601 UTC `Z`; dates `YYYY-MM-DD`; IDs lowercase UUIDv7 strings; money `{ "amount": "1234.50", "currency": "BRL" }` with amount as a decimal string (no exponent, never a JSON number).
- **R-04** Tenant context (`orgId`, `workspaceId`, `domainId`) comes from the token. It MUST NOT be a request-body field that selects the tenant; filters such as `?workspaceId=` only narrow results inside what the token allows.
- **R-05** Partial updates use `PATCH` with `application/merge-patch+json` (RFC 7396); `PUT` only for full replacement of small documents.
- **R-06** No PII or secrets in URLs or query strings (they end up in logs and browser history); send them in bodies.

## 3. Status codes and generic error codes (RFC-0008 §3)
| Status | When | Generic `code` |
|---|---|---|
| 200 / 201 / 204 | Success; 201 sets `Location` | — |
| 202 | Accepted long-running work (section 7) | — |
| 304 | `If-None-Match` matches current `ETag` | — |
| 400 | Malformed body, schema validation, bad cursor, missing required idempotency key | `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_REQUIRED` |
| 401 | Missing/invalid token or key; step-up needed | `UNAUTHENTICATED`, `STEP_UP_REQUIRED` |
| 403 | Permission, entitlement, suspended organization | `FORBIDDEN`, `ENTITLEMENT_REQUIRED`, `ORGANIZATION_SUSPENDED` |
| 404 | Not found **or belongs to another tenant** (never reveal existence) | `NOT_FOUND` |
| 409 | State conflict, concurrent idempotent request | `CONFLICT`, `CONCURRENT_MODIFICATION`, `IDEMPOTENCY_REQUEST_IN_PROGRESS` |
| 410 | Public API major past its sunset | `API_VERSION_SUNSET` |
| 412 / 428 | `If-Match` mismatch / missing | `PRECONDITION_FAILED` / `PRECONDITION_REQUIRED` |
| 422 | Business rule violation; key reused with another body | domain codes (`CONTRACT_ITEM_NOT_AVAILABLE`), `IDEMPOTENCY_KEY_REUSED` |
| 429 | Edge rate limit or commercial quota | `RATE_LIMITED`, `QUOTA_EXCEEDED` (+ `Retry-After`) |
| 500 / 503 / 504 | Unexpected, dependency down/saturated, timeout/deadline | `INTERNAL_ERROR`, `DEPENDENCY_UNAVAILABLE`, `DOWNSTREAM_SATURATED`, `DEPENDENCY_TIMEOUT`, `DEADLINE_EXCEEDED` |

## 4. Errors (RFC 9457)
- **R-07** All errors are `application/problem+json` with `type` (`urn:waver:problem:<CODE>`), `title`, `status`, `detail`, `instance`, plus `code`, `correlationId` and, for validation, `errors[]` (`field`, `code`, `message`). Domain codes follow STD-008.

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
- **R-08** `Accept-Language` (`pt-BR` default, `en-US`) affects only `title`/`detail`/`message`; `code` is stable. UIs localize from `code` (STD-008). Stack traces, SQL, class names, internal hostnames and downstream bodies MUST NOT appear.
- **R-09** Customer-audience problems (workspace-bff, public API) MUST NOT mention SKU, price, margin, listing or reseller-internal data; `ENTITLEMENT_REQUIRED` only says the capability is not in the plan and to contact the reseller.

## 5. Pagination, filtering, sorting
- **R-10** Collections are cursor-paginated: `?limit=50&cursor=<opaque>`, default 50, max 200. Response `{ "items": [...], "nextCursor": "..." }`; `nextCursor` absent on the last page. No totals by default; `?includeTotal=true` only where cheap. Offset pagination is forbidden.
- **R-11** The cursor is opaque (base64url of last sort key + id + filter hash). A cursor reused with different filters/sort returns 400 `VALIDATION_FAILED`.
- **R-12** Filters are field-named query parameters: equality `?status=ACTIVE&workspaceId=...`; ranges with `From`/`To` suffixes `?createdAtFrom=2026-10-01T00:00:00Z&createdAtTo=2026-11-01T00:00:00Z`; free text `?q=` only where a search index exists. Unknown parameters → 400.
- **R-13** Sorting: `?sort=createdAt:desc,name:asc`, limited to documented indexed fields; the server appends `id` as the final tiebreaker.

```http
GET /v1/contracts?status=ACTIVE&createdAtFrom=2026-10-01T00:00:00Z&sort=createdAt:desc&limit=50 HTTP/1.1

200 OK
{ "items": [ { "id": "019a0c41-22d0-7a9e-8c11-0d6b9e2f4a77", "number": "CT-2026-000123", "status": "ACTIVE" } ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xOVQxNDowMzowMFoiLCJpZCI6IjAxOWEwYzQxIn0" }
```

## 6. Idempotency and concurrency
- **R-14** Every POST that creates or triggers side effects accepts `Idempotency-Key` (UUID) and declares `x-waver-idempotency: required|optional`. Callers MUST send one; endpoints marked `required` (money, provisioning, suspension, tool execution, public API messages) reject requests without it (400 `IDEMPOTENCY_KEY_REQUIRED`). Calls triggered by an event use the event `id` as key.
- **R-15** The platform starter stores `(org_id, principal_id, key)` → route, body SHA-256, status, response for 24 h: Redis (`waver:idem:<service>:*`, ADR-0020) is the fast path and the PostgreSQL `idempotency_record` guard stays authoritative, so losing Redis never causes duplicate side effects (RFC-0008). Replay → stored response + `Idempotent-Replayed: true`; same key, different body → 422 `IDEMPOTENCY_KEY_REUSED`; in progress → 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`. 5xx responses are not stored.
- **R-16** Single resources return a strong `ETag: "<version>"` from the aggregate `version` column. `PUT`/`PATCH`/`DELETE` and action sub-resources that change the parent require `If-Match`: missing → 428, mismatch → 412. `If-None-Match` → 304.

```http
PATCH /v1/agents/019a0d10-1d2e-7a3b-8c4d-5e6f7a8b9c0d HTTP/1.1
If-Match: "7"
Content-Type: application/merge-patch+json

{ "displayName": "SDR Comercial" }
```

## 7. Long-running operations (ADR-0023)
- **R-17** Work that may exceed the call-class timeout of RFC-0005 or depends on asynchronous steps returns `202 Accepted` with `Location` of the **process resource owned by the service** (backed by a Temporal workflow in that service, ADR-0023) (e.g., `/v1/provisioning-jobs/{jobId}`, `/v1/knowledge-sources/{id}/ingestions/{ingestionId}`) and `Retry-After`.
- **R-18** Process resources expose a common shape: `id`, `status` (`PENDING | RUNNING | SUCCEEDED | FAILED | CANCELLED`), `createdAt`, `updatedAt`, `resource` (reference to the affected resource), `error` (problem object when `FAILED`). Completion is also published as an event (STD-004). Cancellation is a sub-resource: `POST .../{id}/cancellations`.

```json
{ "id": "019a0e01-5a6b-7c8d-9e0f-112233445566", "status": "RUNNING",
  "createdAt": "2026-10-19T14:03:00Z", "updatedAt": "2026-10-19T14:03:05Z",
  "resource": { "type": "knowledge-source", "id": "019a0dff-0000-7000-8000-000000000001" }, "error": null }
```

## 8. Versioning and deprecation (AR-015, RFC-0008 §4)
- **R-19** Allowed within a major: new endpoints, new optional request fields, new response fields, new values of enums documented as extensible (`x-extensible-enum`), relaxed validation. Clients MUST ignore unknown fields and tolerate unknown extensible enum values.
- **R-20** Breaking (new major): removing/renaming fields or endpoints, type/format change, new required input, stricter validation, changed status codes, semantics, auth requirements or pagination/sort defaults. Old and new majors run side by side.
- **R-21** Internal majors are removed only when CI shows no generated-client consumer, after at least one release of overlap. Public majors: ≥ 12 months after the successor is GA, with `Deprecation: @<epoch-seconds>` (RFC 9745), `Sunset: <HTTP-date>` (RFC 8594), `Link: <docs>; rel="deprecation"`; after sunset, 410 `API_VERSION_SUNSET`.
- **R-22** BFF APIs change by expand/contract across two frontend releases.

## 9. OpenAPI style
- **R-23** Contract-first, OpenAPI 3.1: `contracts/openapi/<service>/v<N>.yaml`, `contracts/openapi/<bff>/v1.yaml`, `contracts/openapi/public/v1.yaml`; shared `Problem`, `Money`, `CursorPage`, headers and parameters in `contracts/openapi/common/v1.yaml`, referenced with `$ref`, never copied.
- **R-24** `operationId` is lowerCamelCase `<verb><Resource>`, unique per spec: `listContracts`, `getContract`, `createContract`, `updateContract`, `deleteContract`, `createContractActivation`. Tags = resource name.
- **R-25** Schema names: `<Resource>`, `<Resource>CreateRequest`, `<Resource>UpdateRequest`, `<Resource>Page`. Request schemas set `additionalProperties: false`; strings have `maxLength`, arrays `maxItems`, IDs `format: uuid`; every property has a `description` and `x-waver-classification`.
- **R-26** Every operation declares `x-waver-audience`, `x-waver-idempotency` (POST), `x-waver-audit` when sensitive, `x-waver-error-codes`, its security requirement, all 4xx/5xx responses (problem) and examples for success and the main error.
- **R-27** Size limits (RFC-0008): JSON body ≤ 1 MiB, uploads ≤ 20 MiB, URL ≤ 4 KiB; edge timeout 30 s for non-streaming calls.

## 10. Never expose
- **R-28** JPA entities or persistence-only columns (`version` travels only as `ETag`; `deleted_at`, internal flags and `rm_*` data are not fields).
- **R-29** IDs or data of tenants the caller may not access, including their existence (404).
- **R-30** Wholesale prices, reseller margins, marketplace listings, availability rules or reseller data to the customer audience (ADR-0006): such fields MUST NOT exist in `workspace-bff` or public schemas.
- **R-31** Secrets/credentials (write-only, `writeOnly: true`, ADR-0014), message/prompt content in errors, and RESTRICTED fields in list endpoints unless explicitly requested and permitted.

## Enforcement
| Rule | Enforced by |
|---|---|
| R-01..R-03, R-23..R-27 style | API lint in `tooling/api-lint` on `contracts/openapi/**` (RFC-0008 §7) |
| R-19..R-21 compatibility | CI diff against `main`; breaking change without a new major fails; consumer rebuild check |
| R-07..R-09 problem format | Platform starter `web` module; conformance suite; contract tests (STD-010) |
| R-14..R-16 idempotency / ETag | Platform starter filters; conformance suite |
| R-29 tenant isolation | RLS (ADR-0010) + tenancy test kit (STD-010) |
| R-30 audience leakage | ADR-0009 CI check (no forbidden clients in `workspace-bff`); banned field names (`wholesale*`, `margin*`, `listing*`) in workspace-bff/public specs; per-audience E2E |
| R-22 BFF compatibility | BFF contract tests against N and N-1 frontend clients (RFC-0014) |

## Open Questions
1. RFC-0008's generic codes (`VALIDATION_FAILED`, `NOT_FOUND`, ...) have no context prefix, while the target naming is `CONTEXT_REASON`; STD-008 treats them as a reserved platform set. Confirm or rename to `PLATFORM_*` before the first contract is published.
2. The API lint tool and OpenAPI diff tool are still to be admitted through STD-013.
