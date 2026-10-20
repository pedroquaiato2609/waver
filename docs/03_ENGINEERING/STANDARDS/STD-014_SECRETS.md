# STD-014 - Secrets Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted HashiCorp Vault as secrets and encryption authority with Kubernetes auth, the Vault Secrets Operator, dynamic PostgreSQL credentials, Transit and the audit device; Keycloak as token issuer; Istio for workload mTLS). Rewritten around Vault; ADR-0014 ownership and lease rules unchanged.
Version: 1.3
Date: 2026-10-19
Related: ADR-0022, ADR-0014 (ownership and lease rules), ADR-0021, ADR-0017, ADR-0026, RFC-0010, RFC-0014 §3, RFC-0015 §7, STD-006, STD-012

## Purpose
Define how WAVER creates, stores, delivers, rotates and detects secrets, implementing ADR-0022 (HashiCorp Vault as the secrets and encryption authority) while keeping the ownership and lease rules of ADR-0014. A secret is any value that grants access or proves identity: passwords, private keys, signing keys, OAuth client secrets, API keys, tokens, encryption keys, Vault tokens and unseal/recovery material, webhook signing secrets.

## Principles
1. **Vault is the only store of platform secrets** in `dev`, `staging` and `production`. Git, Helm values, images, ConfigMaps and pipeline variables never hold secret values.
2. **Prefer no static secret at all:** dynamic, short-lived credentials (database), keys that never leave Vault (Transit), and workload identity (Kubernetes auth to Vault; Istio mTLS between services) come before any long-lived secret.
3. **Least privilege per workload:** one Vault role and policy per ServiceAccount; a service can read only its own paths.
4. **Fail closed:** when Vault cannot be reached, services do not fall back to a weaker secret source.

## Secret classes
| Class | Examples | Owner / store | Delivery |
|---|---|---|---|
| Database credentials | Application role and migration owner role per service | Vault database secrets engine, roles `<service>-app` and `<service>-migrate`, TTL 1 h, renewed while the pod lives | Fetched and renewed by Spring Cloud Vault at runtime; the migrate Job fetches its own at start. Never stored anywhere |
| Static platform secrets | Kafka credentials, Redis ACL password, Qdrant API key, Keycloak client secret of the service, Apicurio read credentials, provider credentials owned by WAVER | Vault KV v2 at `kv/<env>/<service>/<purpose>` | Vault Secrets Operator (`VaultStaticSecret`) syncs a short-lived Kubernetes Secret owned by the operator, mounted read-only as files under `/var/run/secrets/waver/` |
| Keys that never leave Vault | Outbound webhook signing keys, internal HMAC keys, the `tenant-credentials` encryption key | Vault Transit | Used through Transit API calls (encrypt/decrypt, sign/verify, hmac); key material is never exported |
| Tenant credentials | Customer OAuth tokens, API keys, BYOK LLM keys, channel provider tokens | connector-service `Credential` aggregate, stored only as Vault Transit ciphertext (key `tenant-credentials`, context = `orgId`) in its own database | Never delivered, except the audited leases of ADR-0014 (rule 7) |
| Identity-provider keys | Token signing keys, Keycloak realm keys | Keycloak (ADR-0021); Keycloak's own database and admin credentials come from Vault | Published only as public keys in JWKS |
| Workload certificates | mTLS between services | Istio (istiod CA, ADR-0026) | Issued and rotated automatically per workload identity; no application handling |
| Vault operational material | Recovery keys (auto-unseal) or unseal key shares (until a KMS exists), root token | Named key holders in escrow; root token generated only for break-glass and revoked immediately | Never stored in Git, tickets, chat or password managers shared by a team |
| Pipeline secrets | Registry push, image signing key, deployer identity | CI provider's protected store or Vault (JWT/OIDC auth of the CI identity); short-lived federated identities preferred | Injected only into the job that needs them |
| Developer secrets | Local DB/Kafka/Redis passwords | Generated locally (Testcontainers/compose, Vault dev mode if needed), never shared | Local files ignored by Git |

## Rules

### Never in the wrong place
1. Secrets MUST NOT be stored in Git (including `deploy/environments/*`, `deploy/platform/*`, test fixtures and docs), container images, ConfigMaps, Helm values, environment-variable definitions in charts, build logs, application logs, traces, metrics labels, Kafka events and headers, outbox rows, Temporal workflow inputs/results/history, Redis keys or values, Qdrant payloads, audit payloads, error responses or tickets.
2. Values files reference Vault roles and paths by name only (`vault.staticSecrets: [kafka, redis]`, `vault.database.role`). Neither humans nor the pipeline create Kubernetes Secret objects in application namespaces; only the Vault Secrets Operator does (admission-enforced, RFC-0015 §8).
3. Secrets delivered as files are imported with `spring.config.import=optional:configtree:/var/run/secrets/waver/`; dynamic database credentials and Transit operations go through Spring Cloud Vault. Passing secrets as environment variables or command-line arguments is forbidden (visible in process listings and crash dumps).
4. Vault Kubernetes auth roles are bound to exactly one ServiceAccount and namespace. The application ServiceAccount cannot read the migration owner role; only `<service>-migrate` can (RFC-0015 §7).
5. No service schema other than connector-service contains credential or secret columns (ADR-0014), and connector-service stores only Transit ciphertext. Tenant credentials are write-only through APIs: replace or revoke, never read back.
6. Plaintext tenant credentials never leave connector-service. Other services request an operation, not a credential.
7. **Lease exception (ADR-0014, unchanged in scope):** model-gateway-service (`LLM_PROVIDER_KEY`) and channel-service (`CHANNEL_PROVIDER_TOKEN`) may obtain a credential through connector-service's audited lease endpoint; decryption is authorized by a Vault policy that grants Transit decrypt only to those two service identities and only for those credential kinds (ADR-0022). The plaintext is kept in memory only, for at most 15 minutes, never persisted, logged or written to disk. No other service or kind may be leased; extending the list requires amending ADR-0014/ADR-0022.
8. Event schemas and API responses MUST NOT contain fields carrying secrets; schema lint rejects field names matching `password|secret|token|apiKey|privateKey|credential` in `.avsc` and OpenAPI files unless explicitly allow-listed as references (IDs).
9. Redis and Qdrant credentials are per service and never shared (ADR-0020, ADR-0025); a Redis ACL password or Qdrant API key is a static platform secret.

### Logging and telemetry
10. The platform logging configuration masks known secret patterns (Authorization headers, bearer tokens, `password=`, Vault tokens `hvs.`), and HTTP client/server logging excludes headers and bodies by default (STD-006).
11. Exceptions that may include connection strings or Vault responses are sanitized before logging.
12. The Vault audit device is always enabled in every environment and logs are shipped to the observability backend; Vault hashes secret values in audit entries (HMAC), which must not be disabled.

### Generation and rotation
| Secret | Rotation | Method |
|---|---|---|
| Database credentials | Automatic: TTL 1 h, renewed up to the role's max TTL; new credentials per pod start | Vault database secrets engine; Vault revokes expired credentials. The root credential Vault uses per PostgreSQL instance is rotated by Vault (`rotate-root`) right after setup and every 90 days, so no human knows it |
| Static platform secrets (KV) | 90 days, and on staff change or suspicion | Pipeline secret stage writes a new version in KV v2 (or Vault generates it); the Vault Secrets Operator syncs it and restarts the Deployment; the old value is revoked at the source after the rollout (overlap pattern) |
| Service OAuth client secrets (Keycloak) | 90 days | New secret generated in Keycloak through the Admin API, written to Vault KV, synced and rolled out, then the old one invalidated |
| Token signing keys (Keycloak, ADR-0021) | 90 days | New key active in the realm and published in JWKS before use; old key kept until all tokens it signed expire |
| Kafka credentials | 90 days | Same overlap pattern as client secrets |
| Transit keys (`tenant-credentials`, signing keys) | Yearly and on compromise | `transit/keys/<name>/rotate`; old versions stay available for decryption/verification until data is rewrapped (`rewrap`) by a connector-service job; minimum decryption version raised only after rewrap; a Raft snapshot is taken right after each rotation (RFC-0014 §12) |
| Tenant credentials | Driven by the tenant/provider; OAuth refresh handled by connector-service | `CredentialRotated` event, audited |
| Vault recovery keys / unseal shares | On holder change and yearly | `vault operator rekey` (or `-target=recovery`) ceremony with the required quorum of holders |
| Pipeline signing key | Yearly | Key ceremony with two approvers |

13. Production secrets are generated by Vault or the pipeline secret stage and are never known to humans. Humans never copy production secrets to other environments.
14. Every environment has its own Vault cluster and distinct secrets and keys (RFC-0014 §1).
15. Rotation of every platform secret class and a Transit key rotation with rewrap are rehearsed in `staging` at least quarterly.
16. Vault policies and roles are code (`deploy/platform/vault/` and the per-service fragment from chart values), reviewed by platform owners and Security, and applied only by the pipeline (STD-012 rule 12e).

### Local development
17. Local runs use the `local` Spring profile with Testcontainers or a local compose file that generates random passwords at start; no shared "team password". A Vault dev-mode container may be used to exercise Vault integration; production-like Vault policies are tested in `dev`.
18. Local override files (e.g., `application-local.yaml`, `.env.local`) are listed in `.gitignore`; examples are committed as `*.example` with placeholder values only.
19. Developers MUST NOT use real tenant credentials or production data locally; connector development uses provider sandboxes with personal test accounts.

### Detection and response
20. Secret scanning runs as a pre-commit hook (recommended), on every PR (blocking) and nightly over the full history (STD-012).
21. A secret that reached Git, a log, an event, a workflow history, a ticket or a chat is treated as compromised: rotate it in Vault immediately (or revoke the lease/credential), then remove it; history rewriting is not a substitute for rotation.
22. Every access to tenant credentials (create, rotate, revoke, lease, use) emits an audit event (ADR-0014, AR-009) and is visible in the Vault audit log; unusual use patterns alert Security.
23. Vault alerts: sealed or unavailable, audit device failure, denied requests above baseline, lease-renewal failures, root token creation (RUNBOOK_VAULT_SEALED_OR_UNAVAILABLE).
24. Leaks are handled as security incidents with a post-incident review.

## Example
```yaml
# deploy/helm/contract-service/values.yaml (excerpt) - names only, never values
vault:
  role: contract-service                     # Kubernetes auth role bound to SA contract-service
  database: { role: contract-service-app }   # dynamic credentials via Spring Cloud Vault
  staticSecrets: [kafka, redis, keycloak-client]   # kv/<env>/contract-service/<name>, synced by VSO
```
```yaml
# application.yaml (inside the image)
spring:
  config:
    import: optional:configtree:/var/run/secrets/waver/
  cloud:
    vault:
      authentication: KUBERNETES
      kubernetes: { role: ${WAVER_VAULT_ROLE}, service-account-token-file: /var/run/secrets/vault/token }
      database: { enabled: true, role: ${WAVER_VAULT_DB_ROLE} }
```
```hcl
# deploy/platform/vault/policies/contract-service.hcl (rendered, reviewed)
path "database/creds/contract-service-app"   { capabilities = ["read"] }
path "kv/data/production/contract-service/*" { capabilities = ["read"] }
```

## Verification
- CI: secret scanning, chart lint rejecting `env` entries sourced from Secrets and any `Secret` manifest in service charts, schema lint (rule 8), static check for credential columns outside connector-service (ADR-0014), Vault policy lint and least-privilege check (STD-012 rule 12e).
- Runtime: Vault audit log monitoring (denied requests, lease operations, root token use); Kubernetes audit logs for Secret reads by humans; rotation age metrics for KV secrets and Transit keys with alerts at 80% of the period; lease-renewal failure metrics per service.

## Exceptions
None for rules 1, 5, 6, 7 and 21. Other deviations require Security approval with expiry, recorded in the PR.
