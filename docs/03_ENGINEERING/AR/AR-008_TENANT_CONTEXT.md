# AR-008 - Tenant Context
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Keycloak token exchange, CloudEvents binary-mode headers, Temporal context propagation, Qdrant tenant filters, Redis key scoping). RLS is unchanged.

## Requirement
Tenant context is propagated and validated on calls and events.

## Rationale
WAVER is B2B2B: WAVER, resellers and customers share services and databases with logical isolation (ADR-0010). One query, job or event processed without the correct tenant context can leak data between organizations or domains, which is the most damaging failure the platform can have (Scope §22).

## Rules
1. Tenant context (`orgId`, `orgType`, `workspaceId`, `domainId`) is derived only from the validated access token. Values from request bodies, query parameters or client-supplied headers MUST NOT establish tenant context (CONVENTIONS).
2. A service calling another on behalf of a user uses Keycloak token exchange (RFC 8693) so the downstream token keeps the organization and the `act` chain (ADR-0021, RFC-0004).
3. Pure service calls (`client_credentials`) that act on a tenant name it explicitly in the path and are authorized by a platform role; they are audited (AR-009).
4. Every event carries the CloudEvents extensions `orgid` and, when applicable, `workspaceid` as binary-mode Kafka headers (`ce_orgid`, `ce_workspaceid`, ADR-0024), written from the outbox row's header columns. Consumers set tenant context from the headers before touching data. Events without `ce_orgid` go to the DLQ unless their type is on the platform-scoped allow-list.
5. Every transaction runs `SET LOCAL waver.org_id` (and workspace/domain when scoped); RLS is enabled on every tenant-owned table; the application role has no `BYPASSRLS` (ADR-0010).
6. Background jobs, outbox rows and Temporal workflows store the tenant context with the row or the workflow (workflow input plus a platform context propagator; `OrgId` search attribute) and restore it in every activity before touching data (ADR-0023).
7. Cross-tenant reads (WAVER oversight, reseller portfolio) use dedicated, audited query paths (ADR-0010).
8. Data in one Domain is never shared with another Domain implicitly (Master §2); domain scoping is enforced by the same mechanism.
9. Logs and traces carry `orgId` (RFC-0009); metrics use tenant labels only as RFC-0009 allows (cardinality).
10. Non-authoritative stores apply the same rule: Qdrant queries always carry a mandatory `org_id` (and workspace/domain/ACL) payload filter built by the platform library from the validated context, never from the request body (ADR-0025); tenant-scoped Redis keys include the `orgId` inside the service's prefix and never contain PII (ADR-0020).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Cross-tenant isolation | Tenancy test kit: "tenant A cannot read or modify tenant B" generated for every repository and endpoint | `waver-platform-test`, ADR-0010, RFC-0003 |
| Schema | Migration linter: tenant-owned tables need `org_id` and an RLS policy | `tooling/`, STD-005 |
| No client-supplied tenant | ArchUnit: controllers do not bind `orgId`/`workspaceId` from `@RequestBody` or `@RequestHeader` | `waver-platform-test`, STD-001 |
| Events | Outbox writer rejects tenant-owned events without the `orgid` header column; consumer test with missing `ce_orgid` header expects DLQ | STD-004, RFC-0006, ADR-0024 |
| Vector retrieval | Tenancy test kit extended to Qdrant: no cross-tenant or cross-domain result under any filter combination | `waver-platform-test`, ADR-0025 |
| Workflows | Test kit asserts that every activity runs with the tenant context of the workflow that scheduled it | `waver-platform-test`, ADR-0023 |
| Runtime | Missing tenant context returns zero rows (RLS) and increments an alerting metric | `waver-platform-starter`, RFC-0009 |

## Exceptions
Platform-scoped data (e.g., catalog products, model catalog) is declared non-tenant-owned in the owning RFC and listed in the migration linter configuration; this is not an exception. Any other deviation requires an ADR; there are no time-boxed exceptions to AR-008 in `production`.
