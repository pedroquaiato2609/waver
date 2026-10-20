# AR-002 - Database Ownership
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Vault dynamic database credentials, Istio AuthorizationPolicy, Redis key prefixes and Qdrant collections per service).

## Requirement
No microservice accesses another service database.

## Rationale
A shared database is the strongest form of coupling: schema changes become cross-team releases, and one service's queries can slow down or corrupt another's data (ADR-0003). Database per service also limits the blast radius of a leaked credential.

## Rules
1. Each service owns exactly one logical database named `waver_<service>` (CONVENTIONS) with its own roles: an application role and a migration owner role (ADR-0010).
2. Database credentials are issued per service as short-lived dynamic credentials by Vault's database secrets engine (roles `<service>-app` and `<service>-migrate`), obtainable only by that service's ServiceAccounts through Vault Kubernetes auth (ADR-0022, RFC-0015 §7). Sharing a credential or a Vault role between services is forbidden.
3. PostgreSQL grants: a service's roles have `CONNECT` only on its own database; `CONNECT` for `PUBLIC` is revoked on every database.
4. The extensions `dblink` and `postgres_fdw`, foreign servers and any cross-database reference are forbidden.
5. Another service's data is obtained only through its versioned API or events (AR-004) and, when needed, kept as a local read model (MICROSERVICES_MAP, AR-013).
6. Reporting and analytics read from events or exports, never from service databases.
7. One physical instance may host several service databases, but data-plane and control-plane databases are on separate instances (RFC-0015 §9).
8. The same ownership applies to the non-authoritative stores adopted in v1.3: a service reads and writes only its own Redis key prefix (`waver:<prefix>:*`, ACL user per service, ADR-0020) and its own Qdrant collections (API key per service, ADR-0025). PostgreSQL remains the source of truth; another service's keys or collections are never accessed directly.

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| JDBC target is the own database | Chart values schema: `datasource.database` must equal `waver_<service>` | Library chart, RFC-0015 §10 |
| Grants | Database provisioning script asserts `CONNECT` only for the service's roles; daily drift check | RFC-0007, RFC-0015 §9 |
| No cross-database features | Migration linter rejects `dblink`, `postgres_fdw`, `CREATE SERVER` and references to other databases | `tooling/`, STD-005, STD-012 |
| Credential isolation | Vault policy per service allows only `database/creds/<service>-app` (and `<service>-migrate` for the migrate Job's ServiceAccount); Vault policies reviewed as code; Vault audit log alerts on denied credential requests | RFC-0015 §7, ADR-0022 |
| Non-authoritative stores | Redis ACL user restricted to the service's key prefix; Qdrant API key restricted to the service's collections; both rendered from chart values | RFC-0015 §7, ADR-0020, ADR-0025 |
| Network | NetworkPolicy egress only to the service's own PostgreSQL endpoint (where instances are separate); Istio `AuthorizationPolicy` on in-cluster data endpoints (Qdrant, in-cluster PostgreSQL) admits only the declared service identities | RFC-0015 §6, §13 |

## Exceptions
There is no exception path for runtime access in `production`. One-off data moves between services (e.g., during a service split) run as audited jobs that read through APIs or exports and are described in an RFC. Any temporary deviation in `dev`/`staging` is registered in the Exceptions register in `AR_INDEX.md` with expiry ≤ 3 months and WAVER Architecture approval.
