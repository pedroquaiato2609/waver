# ADR-0010 - Tenant Data Isolation Strategy
Status: PROPOSED
Date: 2026-10-19

## Context
Open decision (Master §15 "Data isolation", Scope §24): physical vs logical tenant isolation per service. Database-per-service (ADR-0003) isolates services, not tenants.

## Decision
- **Default: logical isolation** inside each service database. Every tenant-owned table carries `org_id` (owning Organization) and, where relevant, `workspace_id` and `domain_id`.
- **PostgreSQL Row-Level Security (RLS)** is enabled on every tenant-owned table. At transaction start the application runs `SET LOCAL waver.org_id = '<id>'` (and workspace/domain when scoped) from the validated token; RLS policies filter by it. A missing setting returns zero rows.
- The application database role is not the table owner and does not have `BYPASSRLS`. Migrations run with a separate owner role.
- Cross-tenant reads (WAVER oversight, reseller portfolio views) use explicit, audited query paths with a dedicated role, available only in the services that own those views.
- **Reseller portfolio column (standard):** tables whose rows a reseller may read for its customers carry `reseller_org_id` (the owning reseller, maintained from `CustomerOwnershipTransferred` events). The RLS policy allows `org_id = current_setting('waver.org_id')` **or** `reseller_org_id = current_setting('waver.org_id')` when the session also sets `waver.portfolio_access = 'on'`, which the application only sets for reseller roles on portfolio endpoints. Records issued by a reseller (quotes, contracts, invoices) also keep an immutable `seller_org_id` so a former reseller keeps read access to its own historical documents after a transfer.
- **Dedicated database per tenant** may be offered later as an enterprise option for data-plane services (agent-runtime, knowledge, memory) through a routing datasource with the same code. Not in v1.

## Alternatives
- Schema-per-tenant: migration cost grows with tenant count; poor fit for resellers with many small customers.
- Database-per-tenant everywhere: operationally heavy for v1.

## Consequences
- Tenant isolation is enforced twice (application and database).
- Every repository adapter runs inside a transaction that sets tenant context (provided by `waver-platform-starter`).

## Compliance / Verification
- Shared test kit: for each repository, "tenant A cannot read tenant B" runs automatically.
- Migration linter rejects tenant-owned tables without `org_id` and an RLS policy.

## Supersedes / Superseded by
Closes the "Data isolation" open decision.
