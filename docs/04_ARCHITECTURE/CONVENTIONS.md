# Cross-cutting Conventions

Status: PROPOSED (revision 1.3, 2026-10-19: Avro/registry events, shared infrastructure naming, Nx library naming)
Every RFC, standard, contract and service must follow these conventions. Changing one requires an ADR.

## Identifiers
- All IDs are **UUIDv7** (time-ordered), stored as PostgreSQL `uuid`, serialized as lowercase strings.
- IDs are opaque to clients; never encode tenant or type in them.
- Human-readable numbers (invoice number, contract number) are separate fields, generated per issuing organization.

## Tenancy hierarchy and context
- Hierarchy: `Organization (PLATFORM | RESELLER | CUSTOMER) → Workspace → Domain → resources`.
- Tenant context fields (names identical everywhere): `orgId`, `orgType`, `workspaceId`, `domainId`.
- Database columns: `org_id`, `workspace_id`, `domain_id` (ADR-0010).
- Tenant context **always comes from the validated token**, never from a request body or a client-supplied header.

## Access token claims (RFC-0004)
| Claim | Meaning |
|---|---|
| `sub` | User ID or service account ID |
| `org_id`, `org_type` | Organization the token acts in |
| `ws_ids` | Workspaces the principal may access in that org (may be omitted for org-wide roles) |
| `roles` | Role keys (e.g., `RESELLER_FINANCE`) |
| `act` | Actor chain for token exchange (RFC 8693), e.g., service acting on behalf of user |
| `support_grant` | Present only for audited support/impersonation sessions |
Fine-grained permissions are evaluated in services, not packed into tokens.

## HTTP
- Internal service APIs: `/v{major}/{resource}` (e.g., `/v1/contracts/{contractId}/items`). Resource names are plural, kebab-case.
- JSON fields: `camelCase`. Enum values: `UPPER_SNAKE_CASE`.
- Timestamps: ISO-8601 UTC with `Z` (`2026-10-19T14:03:00Z`). Dates without time: `YYYY-MM-DD`.
- Money: `{ "amount": "1234.50", "currency": "BRL" }` — amount as **decimal string**, never float. DB type `numeric(19,4)` plus `char(3)` currency.
- Errors: RFC 9457 `application/problem+json` with extra members `code` (stable machine code, e.g., `CONTRACT_ITEM_NOT_AVAILABLE`), `correlationId`, and `errors[]` for validation. Codes are `<CONTEXT>_<REASON>`, except a small **reserved platform set without prefix** emitted by `waver-platform-starter` (`VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`, `FORBIDDEN`, `UNAUTHENTICATED`, `RATE_LIMITED`, `DEPENDENCY_UNAVAILABLE`, `DOWNSTREAM_SATURATED`, `PRECONDITION_FAILED`, `INTERNAL_ERROR`; full list in RFC-0008/STD-008). Services must not reuse these names for their own codes.
- Pagination: cursor-based. Request `?limit=50&cursor=...`; response `{ "items": [...], "nextCursor": "..." }`. Max limit 200.
- Idempotency: every POST that creates or triggers side effects accepts `Idempotency-Key` (UUID). The server stores key + request hash + response (in Redis, ADR-0020) for 24h by default; an endpoint may document a longer retention when retries can legitimately arrive later (e.g., tool executions waiting on approvals keep keys for 7 days, RFC-0012).
- Concurrency: resources return `ETag`; updates require `If-Match` (optimistic locking).
- Correlation: `traceparent` (W3C Trace Context) is propagated; `X-Correlation-Id` is echoed for humans/support.
- Deadlines: the caller's remaining time budget is propagated as `X-Request-Deadline` (absolute UTC timestamp); services must not start work after it has passed (RFC-0005).
- Ingress hosts: `app.` (BFFs of the three apps, by path/host), `api.` (public customer API), `hooks.` (provider webhooks and Web Chat widget, routed directly to channel/connector/trigger services — ADR-0009 amendment).

## Events (ADR-0012, ADR-0024, RFC-0006)
- Format (v1.3): **Avro** payload registered in **Apicurio Registry**; CloudEvents attributes and WAVER extensions travel as Kafka headers `ce_*` (binary mode). Schemas: `contracts/events/<context>/<EventName>.v<major>.avsc`, Avro namespace `br.com.waver.events.<contextpkg>.v<major>` (context with hyphens dropped, e.g. `usagemetering`; the major is part of the namespace so a v2 schema never lands in the v1 subject). Registry coordinates: Apicurio `TopicRecordIdStrategy`, i.e. group = Avro namespace and artifact = `<topic>-<EventName>` (through the Confluent-compatible API the same schema appears as subject `<topic>-<namespace>.<EventName>`, RFC-0006). Schemas are registered by CI, never by services. Headers: `ce_id`, `ce_source`, `ce_type`, `ce_specversion`, `ce_time`, `ce_subject` plus extensions `ce_orgid`, `ce_correlationid`, `ce_causationid`, `ce_actorid`, `ce_actortype`, conditional `ce_workspaceid`, `ce_domainid`, `ce_supportgrantid`, and `ce_aggregateversion`, `ce_traceparent`.
- Topic name: `waver.<context>.<aggregate>.v<major>` — e.g., `waver.contract.contract.v1`, `waver.billing.invoice.v1`. Multi-word aggregates in kebab-case (`waver.identity.support-access-grant.v1`).
- `<context>` is the **producing service name without `-service`, in kebab-case**, with one exception: identity-access-service uses `identity`. Full table in `07_API_EVENTS/EVENT_CATALOG.md` §1 (registry of record). Examples: `agent-runtime`, `agent-builder`, `tool-runtime`, `tool-registry`, `model-gateway`, `usage-metering`, `audit` (audit transport topic `waver.audit.audit-record.v1`).
- Compacted state topics (read models for the data plane) use the aggregate name with a `-snapshot` or `-state` suffix and are keyed by the entity the consumer looks up: `waver.entitlement.entitlement-snapshot.v1`, `waver.usage-metering.quota-state.v1`, `waver.agent-builder.deployment-state.v1`.
- Java packages drop the hyphen: `br.com.waver.usagemetering`, `br.com.waver.agentruntime`. Database names use underscores: `waver_usage_metering`.
- CloudEvents `type`: `waver.<context>.<EventName>.v<major>` — e.g., `waver.contract.ContractActivated.v1`.
- Event names are **past tense** facts (`ContractActivated`), never commands.
- Kafka key: aggregate ID. One topic per aggregate type; multiple event types per topic.
- Events carry IDs and the minimal state consumers need (no PII unless the consumer is entitled to it; see STD-009).
- DLQ: `<topic>.dlq`. Retry topics: managed by Spring Kafka (`<topic>-retry-<n>`).

## Database (RFC-0007, STD-005)
- One database per service, named `waver_<service>` (e.g., `waver_contract`).
- Tables/columns: `snake_case`, tables singular (`contract_item`).
- Mandatory columns on aggregates: `id`, `org_id` (if tenant-owned), `created_at`, `created_by`, `updated_at`, `updated_by`, `version`.
- Soft delete via `deleted_at` only where retention policy requires it; purge is a separate, audited job.
- Migrations: Flyway, forward-only, `V<yyyyMMddHHmm>__<description>.sql`.

## Shared infrastructure naming (v1.3)
| Component | Convention | Example |
|---|---|---|
| Redis keys (ADR-0020) | `waver:<area>:<service>:<name>:<id>`; one Redis ACL user per service limited to its prefix; TTL mandatory except sessions managed by Spring Session | `waver:cache:catalog-service:price-quote:<hash>` |
| Qdrant collections (ADR-0025) | `<service_underscored>__<purpose>_v<N>` behind an alias without version; payload always has `org_id` | `knowledge_service__chunks_v2` (alias `knowledge_service__chunks`) |
| Temporal (ADR-0023) | Namespace per plane `waver-<plane>`; task queue `<service>.<workflow>`; workflow ID `<workflowType>:<aggregateId>` (idempotent start); search attribute `OrgId` mandatory | `provisioning-service.provisioning-job`, `ProvisioningJob:0192…` |
| Vault paths (ADR-0022) | KV `secret/waver/<env>/<service>/*`; DB role `waver-<env>-<service>`; Transit keys `tenant-credentials`, `tenant-credentials-llm`, `tenant-credentials-channel`, `tenant-credentials-payment`, `outbound-webhooks` (ADR-0022); K8s auth role = service account name | `secret/waver/prod/billing-service/gateway` |
| Keycloak (ADR-0021) | Realm `waver`; client per BFF `waver-<app>-bff` and per service `svc-<service>`; one Keycloak organization per WAVER Organization (alias = orgId) | `svc-contract-service` |
| Istio (ADR-0026) | `AuthorizationPolicy` named `<service>-allow`; `ServiceEntry` named `egress-<service>-<provider>` | `egress-model-gateway-service-provider-a` |

## Naming of services and modules
- Services: `<context>-service` (except `agent-runtime`, `edge-gateway`, `*-bff`).
- Java base package: `br.com.waver.<context>` with sub-packages `domain`, `application`, `adapters`, `bootstrap`.
- Angular (Nx, ADR-0027): apps `control`, `partner`, `workspace`; libraries `libs/<scope>/<type>-<name>` with type `feature`, `data-access`, `ui`, `util` and tags `scope:<control|partner|workspace|shared>`, `type:<…>`; component selector prefix `wv-`; NgRx stores named `<Name>Store` (SignalStore) in `data-access-*` libraries.

## Time, locale and currency
- Server-side everything is UTC. UI renders in the user's time zone.
- Default locale `pt-BR`; all UI strings externalized (i18n library) from day one. `en-US` second.
- Currency per PriceBook; BRL first. Tax data is metadata, never hard-coded to one country (Commercial §10.1).

## Environments
`dev` → `staging` → `production` (plus ephemeral preview environments per pull request for frontends/BFFs). Agent "Environments" (RFC-0016) are a product concept and are not the same as infrastructure environments.
