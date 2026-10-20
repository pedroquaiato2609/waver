# RFC-0010 - Security Architecture
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Istio ambient with STRICT mTLS, AuthorizationPolicy and egress gateway (ADR-0026), HashiCorp Vault as secrets and encryption authority (ADR-0022), Keycloak as identity provider (ADR-0021), Redis access control (ADR-0020), Temporal namespace security (ADR-0023), Apicurio Registry and Debezium/Kafka Connect access control (ADR-0024), Qdrant access control (ADR-0025)).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines WAVER's security architecture: a STRIDE threat model for the main assets (tenant data, credentials, LLM prompt injection through tool outputs and knowledge, SSRF through connectors, cross-tenant leakage, commercial data leakage to customers, support impersonation abuse, supply chain, and the platform components adopted on 2026-10-19 — Keycloak, Vault, Redis, Qdrant, Temporal, Apicurio Registry, Kafka Connect/Debezium, Istio), and the controls that answer it. It covers zero-trust principles, Istio ambient STRICT mTLS with AuthorizationPolicies generated from the dependency graph plus NetworkPolicies with default deny as a second layer, service identities, encryption in transit and at rest, Vault as the secrets and encryption authority (dynamic credentials, Transit, audit device), egress control through the Istio egress gateway with ServiceEntry allow-lists (only model-gateway-service, connector-service and channel-service have general internet egress; restricted exceptions for Keycloak federation and billing providers), secure SDLC (SAST, dependency and container scanning, SBOM, image signing), LGPD controls, incident response and the security testing cadence.

## Context / Problem
SECURITY_BASELINE requires strict tenant isolation, least privilege, service identities, secrets out of code and config, encryption in transit and at rest, immutable audit of sensitive admin events, LGPD retention/export/deletion, CI/CD scanning and SBOM, and threat modelling for the high-risk domains (IAM, billing, tool execution, connectors). The product adds risks specific to AI agents. Agents read untrusted content (knowledge, tool outputs, inbound messages) and can act on external systems with customer credentials. The B2B2B model adds a commercial confidentiality boundary between WAVER, resellers and customers (ADR-0006). The ADRs fix the identity provider (Keycloak, ADR-0021), BFF isolation (ADR-0009), RLS (ADR-0010), secrets and encryption (Vault, ADR-0022, keeping the ADR-0014 ownership and lease rules), the connector model (ADR-0016), the model gateway (ADR-0017), Redis (ADR-0020), Temporal (ADR-0023), the schema registry and Debezium (ADR-0024), Qdrant (ADR-0025) and the Istio service mesh (ADR-0026). This RFC ties them into one threat model and fills the gaps.

## Goals
- Every asset in the threat model has named controls, an owner and a verification.
- No single control failure leads to cross-tenant or cross-audience disclosure (defence in depth).
- Every pod-to-pod connection is mutually authenticated and encrypted, and admitted only along the declared dependency graph.
- Internet egress is limited to the three services whose purpose requires it, plus explicitly restricted exceptions, all through the egress gateway.
- A security pipeline that is the same for every service and runs before every release.
- LGPD obligations are mapped to concrete mechanisms.

## Non-goals
- Choosing a cloud provider, KMS, CNI, CI provider or signing tool. These are open (TECH_STACK). The requirements here are stated so that any of them can satisfy them.
- Fraud/KYC processes for resellers (business process, Commercial §3).
- Detailed tool approval policies (RFC-0012) and connector certification checklist (RFC-0018).
- Keycloak realm design and claim mapping details (RFC-0004); Vault engine configuration details (platform runbooks).

## Requirements / ARs
| AR / source | Control |
|---|---|
| AR-002 / AR-003 | Per-service DB roles with Vault-issued credentials, Kafka principals, Redis ACL users, Qdrant credentials, Temporal namespaces, Istio AuthorizationPolicies, NetworkPolicies; no shared credentials. |
| AR-008 | Tenant context only from validated tokens / owner-produced events; RLS (ADR-0010); mandatory `org_id` filter in Qdrant. |
| AR-009 | Hash-chained audit-service; `ce_actorid`, `ce_actortype`, `ce_supportgrantid` on events; Vault audit device and Keycloak admin events in the security log stream. |
| AR-011 | Entitlement checks in owning services and in data-plane read models. |
| AR-012 | Suspension is reversible; purge requires approval (RFC-0007). |
| AR-013 / AR-014 | Fail-closed authorization with local caches; timeouts and limits on all egress. |
| ADR-0009 / 0010 / 0016 / 0017 / 0020–0026 | Implemented and verified as described below. |

## Proposed Design

### 1. Principles
- **Zero trust inside the cluster.** Every call carries a JWT issued by Keycloak that the callee validates (ADR-0021), over a mutually authenticated Istio connection whose source workload identity must be allowed by the destination's AuthorizationPolicy (ADR-0026). Network position grants nothing. NetworkPolicies and mesh policies reduce blast radius; the JWT and the service's permission checks remain the authorization of the request.
- **Least privilege by construction.** A service only has clients, DB roles, Vault policies, Kafka ACLs, Redis key prefixes, mesh and network paths for its declared dependencies (MICROSERVICES_MAP, RFC-0002 §4).
- **Untrusted by default.** Content from users, knowledge sources, tools, connectors, webhooks and LLM output is data, never instructions or authorization.
- **Fail closed.** Missing tenant context, an unverifiable token, an unknown entitlement, an unavailable Vault decrypt or a mesh policy miss denies the action.
- **Secrets have one home.** Every secret and encryption key lives in Vault; applications receive short-lived credentials or ciphertext, never long-lived keys (ADR-0022).
- **Everything sensitive is audited.** Every sensitive action records actor, grant and reason (AR-009).

### 2. Assets and trust boundaries
| Asset | Owner(s) | Class (RFC-0007) |
|---|---|---|
| Tenant operational data (agents, conversations, documents, embeddings in PostgreSQL and Qdrant, memory, runs, agent-output streams in Redis) | agent-builder, agent-runtime, knowledge, memory, channel | Confidential / Restricted |
| Tenant credentials | connector-service only (ciphertext under Vault Transit; ADR-0014 ownership kept by ADR-0022) | Restricted |
| Outbound webhook signing key | Vault Transit `outbound-webhooks` (used by connector-service only) | Restricted |
| Platform secrets (Vault unseal/recovery keys and root token, Transit keys, dynamic DB credentials, Keycloak realm signing keys and client credentials, Redis/Qdrant/Kafka/registry/Temporal credentials) | Platform (Vault) / Keycloak | Restricted |
| Commercial data (wholesale prices, margins, reseller agreements, marketplace) | catalog, marketplace, reseller, contract, billing | Confidential (commercial) |
| Identities, sessions, API keys, support grants | Keycloak (authentication, tokens), identity-access-service (memberships, roles, API keys, grants), BFFs (sessions in Redis) | Confidential / Restricted |
| Audit trail | audit-service | Confidential |
| Durable workflow state (Temporal histories: IDs and control values only) | owning services; Temporal cluster (platform) | Confidential |
| Event schemas and their IDs (Apicurio Registry) | producing services (content), platform (registry) | Internal (integrity-critical) |
| Mesh and network policy (AuthorizationPolicy, PeerAuthentication, ServiceEntry, NetworkPolicy), Vault policies, Keycloak realm configuration | Platform | Internal (integrity-critical) |
| Build pipeline, images, dependencies, connector manifests | Platform, marketplace pipeline | Internal (integrity-critical) |

Trust boundaries: Internet ↔ ingress/edge on three hosts (`app.` user sessions, `api.` customer programs, `hooks.` provider webhooks and anonymous Web Chat; CONVENTIONS, ADR-0009); Internet ↔ Keycloak login pages (through the `app.` host routes); edge ↔ BFF; BFF ↔ services; services ↔ PostgreSQL/Kafka/Redis/Qdrant/Vault/Keycloak/Temporal/registry; Kafka Connect ↔ service databases (replication) and Kafka; egress gateway ↔ Internet (LLM providers, connectors, channel providers, customer IdPs, payment/fiscal providers); agent context ↔ untrusted content; WAVER ↔ Reseller ↔ Customer audiences.

### 3. Threat model (STRIDE)
| # | Asset / area | STRIDE | Threat | Controls | Verification |
|---|---|---|---|---|---|
| T1 | Tenant data | I | Query or job misses tenant filter; tenant A reads B | App-level checks + RLS with `FORCE` (RFC-0007); `404` for foreign IDs; tenant context set by the starter only | Tenancy test kit per repository; migration linter |
| T2 | Tenant data | S/T | Client forges tenant via header/body; event with forged `ce_orgid` | Context only from token claims; edge strips `X-Org-*`/`X-Tenant-*`; Kafka ACLs make the owner the only writer (its Debezium connector produces with the owner's principal, RFC-0006); consumers reject `ce_orgid` mismatches | Edge header tests; DLQ security alert (RFC-0006) |
| T3 | Tenant data | I | Export bundle, backup, snapshot or support dump leaks | Encrypted backups and snapshots (PostgreSQL, Qdrant, Vault Raft); exports through `ObjectStoragePort` with short-lived presigned access; export is an audited, permissioned action | Restore drills; audit review |
| T4 | Tenant credentials | I | Plaintext credential exfiltrated via DB dump, logs, API read-back, LLM context | Vault Transit encryption: connector-service stores only ciphertext; general credentials under key `tenant-credentials` (decrypt allowed only to connector-service); write-only APIs. The ADR-0022 **lease** exception has three lessees, each with its own Transit key: model-gateway-service → `tenant-credentials-llm` (`LLM_PROVIDER_KEY`), channel-service → `tenant-credentials-channel` (`CHANNEL_PROVIDER_TOKEN`), billing-service → `tenant-credentials-payment` (only when a reseller brings its own payment gateway account). The lease returns **ciphertext**; the lessee decrypts it under a decrypt-only Vault policy for its key, keeps the plaintext in memory only (TTL ≤ 15 min), and every lease is audited (`CredentialLeased`) and visible in the Vault audit log. Credentials are never put into prompts; log sanitizer | Static check: no secret columns outside connector-service; Vault policy tests (decrypt denied for any other identity or key); log PII/secret tests |
| T5 | Tenant credentials | E | Caller uses another tenant's Connection/credential | connector-service resolves Connection only within the caller token's `org_id` (+ workspace/domain); Transit encryption context = `orgId`, so a ciphertext cannot be decrypted under another org's context; `connectionId` is bound in the ToolBinding, never chosen by the model | Cross-tenant tests in connector-service |
| T6 | Platform signing keys | S | Stolen Keycloak realm signing key → forged tokens | Keys managed by Keycloak key providers in its tier-0 database (encrypted at rest, Vault-issued DB credentials); rotation every 30 days with JWKS overlap; 5-min access tokens; Keycloak admin access restricted (T25); key-compromise runbook (rotate, set realm not-before, revoke sessions) | Rotation drill quarterly |
| T7 | Agent (prompt injection via tool output / knowledge / messages) | T/E | Untrusted content instructs the agent to call tools, change goals or exfiltrate data | Prompt compiler places untrusted content in delimited data sections and never in the instruction section; tools callable only if bound to the deployed AgentVersion and entitled; arguments validated against ToolVersion input schema; tenant, connection and recipient scoping fixed by runtime, not by model; approval for sensitive tools (approval-service); outbound-message tools with recipient allow-lists/policies; per-run limits | Evaluation suites with injection cases per release (evaluation-service); platform red-team corpus |
| T8 | Agent | I | Agent discloses another domain's knowledge, memory or internal instructions | Retrieval filtered by org/workspace/domain/knowledge base/ACL tags **inside** the Qdrant search, before ranking, with filters built from the token by the platform library, and a final PostgreSQL permission check (ADR-0025); memory scoped (memory-service); no secrets or commercial data in prompts; output policies (Scope §8) | Retrieval tests assert no cross-tenant or cross-domain chunk (tenancy kit extended to Qdrant) |
| T9 | Agent / model spend | D | Loops or adversarial inputs exhaust tokens/tool calls | Per-run max steps, tokens, tool calls and wall-clock; per-org budgets (model-gateway + usage-metering); near-exact quotas with Redis counters (ADR-0020) | Load and abuse tests; cost anomaly alerts (RFC-0009) |
| T10 | Connectors (SSRF) | I/E | Custom API connector or redirect targets cluster services, cloud metadata or private networks | SSRF guard in the generic HTTP engine (§6); egress only through the egress gateway, whose own NetworkPolicy blocks private ranges; ServiceEntry allow-lists; manifest-declared hosts; HTTPS only | SSRF test corpus (DNS rebinding, redirects, IPv6, decimal IPs) in CI; staging test that an undeclared host is blocked at the gateway |
| T11 | Connectors | D | Slow or huge responses exhaust workers | Connect 5 s / total 30 s timeouts (defaults), 10 MB response cap, per-org and per-connection concurrency limits | Fault-injection tests |
| T12 | Cross-tenant leakage (caches, batching) | I | Cache key without org (Caffeine L1 or Redis L2); batched embedding/LLM calls mixing tenants; provider-side caching | ArchUnit rule: `@Cacheable` keys must include `orgId` (applies to the Redis cache tier too); Redis key builders from the starter only; no multi-tenant request batching to providers; provider prompt caching only per org-scoped prefix | ArchUnit; model-gateway tests |
| T13 | Commercial data → customers | I | Customer sees wholesale price, margin, marketplace or other offers | `workspace-bff` has no clients for marketplace, catalog pricing, reseller (ADR-0009), and the mesh AuthorizationPolicies of those services do not admit `workspace-bff`; public API and webhooks curated; problem details sanitized (RFC-0008); classification-aware DTOs | CI dependency check; mesh deny test; Playwright audience tests |
| T14 | Commercial data → other resellers | I | Reseller sees another reseller's customers or pricing | RLS portfolio policies (`rm_org_relationship`); partner-bff scoping | Tenancy kit with reseller fixtures |
| T15 | Support impersonation | E | Support staff accesses data without need, beyond scope or time | SupportAccessGrant (§7): approval, scope, time-box, read-only default, `support_grant` claim (Keycloak mapper, values from identity-access), customer-visible record, forbidden actions list | Grant tests; monthly grant review |
| T16 | Support / admin actions | R | Action cannot be attributed or is denied later | `act` chain (RFC 8693), `ce_actorid`/`ce_actortype`/`ce_supportgrantid` on events, hash-chained audit, no delete API; Keycloak admin events and Vault audit log in the security stream | Audit chain verification job (daily) |
| T17 | Supply chain | T | Malicious or vulnerable dependency or base image (including Keycloak extensions, Debezium plugins, platform component images) | BOM-pinned versions (ADR-0007), STD-013 approval, SCA, container scan, SBOM, signed images, admission verification, private registry; platform component images mirrored and scanned like service images | Pipeline gates (§9) |
| T18 | Marketplace connectors | T/E | Malicious manifest or coded connector | Certification checklist (ADR-0016); manifest schema validation; only WAVER publishes in v1 (Commercial §5.3); coded connectors reviewed and optionally isolated in `connector-runner-<group>` | Certification records |
| T19 | Identity | S | Credential stuffing, phishing of admin users, account-recovery abuse | Keycloak: MFA (TOTP, WebAuthn/passkeys) mandatory for WAVER/Reseller admin roles and step-up (ACR) for sensitive actions as defined by identity-access-service; brute-force detection and lockout; password policy; verified e-mail for recovery; login rate limits at edge; enterprise SSO; new-device and impossible-travel alerts | Pen test of the Keycloak login, recovery and federation flows before production (ADR-0021) |
| T20 | API keys | S | Leaked customer API key | Hashed storage in identity-access-service; recognizable prefix for secret scanners; scoping (roles, workspace); revocation ≤ 5 min; optional IP allow-list | Key lifecycle tests |
| T21 | Audit trail | T | Insider modifies or deletes audit records | Append-only table, hash chain, separate DB roles, no update/delete grants, periodic anchoring of chain head in a second store (backup) | Daily chain verification |
| T21b | Audit transport | S/R | Compromised service forges audit records for another service or org on the shared topic `waver.audit.audit-record.v1` (RFC-0006 §13) | WRITE ACL only for service principals; `ce_source` stored with each record; domain-event audit path is single-producer per topic; anomaly rule on records whose `ce_source` does not own the `action` context | Residual risk; per-service signing with Vault Transit is an open question (RFC-0006) |
| T22 | Data plane availability | D | DDoS, noisy tenant | Exact global edge limits (`RedisRateLimiter`, ADR-0020), quotas, per-org concurrency in runtime, plane isolation (AR-013), HPA | Load tests |
| T23 | Webhooks | S/T | Spoofed inbound provider webhook; tampered outbound webhook | Provider signature verification + replay window in channel/connector/trigger services; unguessable 128-bit path tokens (RFC-0018); outbound Ed25519 signatures with the Vault Transit key `outbound-webhooks` (RFC-0008) | Signature test vectors |
| T24 | `hooks.` ingress (machine/anonymous) | S/D/E | Unauthenticated traffic reaches services directly (no BFF in between): endpoint enumeration, payload floods, oversized bodies, Web Chat abuse (spam, cost exhaustion through anonymous conversations), attempts to reach admin routes | Edge route allow-list per host (only the listed webhook/widget paths; no commercial or admin route, ADR-0009); strict per-route and per-path-token rate limits and body limits; widget public key + origin allow-list + per-session and per-org message caps; anonymous sessions bound to one Channel/Organization; agent quotas and budgets (T9); signature check before any parsing beyond headers | Route-table tests; abuse load tests on staging |
| T25 | Keycloak administration | E/T | Attacker or insider with realm-admin rights creates users or clients, alters protocol mappers, disables MFA or adds an IdP | No human realm-admin in production: realm configuration as code applied by the pipeline through the Admin API (ADR-0021); identity-access-service's admin client limited by fine-grained admin permissions to organizations, IdPs and service clients it provisions; `master` realm admin only for break-glass (MFA, two-person); admin console not routed on public hosts and reachable only by operators (Istio AuthorizationPolicy + Keycloak SSO); admin events enabled and shipped to the security stream | Alert on admin events from any identity other than the pipeline/identity-access clients; quarterly realm diff against Git |
| T26 | Keycloak claim mapper SPI | T/I | Bug or compromise of the custom protocol mapper (reads claims from identity-access-service) injects wrong `org_id`, `roles` or `support_grant` | Mapper code minimal, CODEOWNERS security review, signed build; the internal claims endpoint of identity-access-service is admitted only for Keycloak's workload identity (AuthorizationPolicy) and returns claims only for the authenticated subject; short claim cache (≤ 60 s); token issuance fails closed when claims are unavailable; services still evaluate fine-grained permissions themselves (RFC-0004) | Contract tests mapper ↔ identity-access; mesh deny test |
| T27 | Federation / identity brokering | S | Malicious or misconfigured customer IdP asserts identities of other organizations; account takeover through automatic account linking | One IdP per Keycloak Organization, bound to the organization's verified domains; brokered users become members of that organization only; automatic linking by e-mail disabled (first-broker-login requires verification); IdP configuration requested and approved in identity-access-service and pushed through the Admin API (`IdpConfigurationChanged` audited); Keycloak egress to customer IdPs only through the egress gateway with a ServiceEntry per approved IdP host | Federation tests per protocol (OIDC/SAML) with hostile IdP fixtures; pen test |
| T28 | Sessions and tokens | S | Session hijack or token replay | Tokens only server-side in the BFF session (Redis, encrypted transport, TTL); HttpOnly/SameSite cookies; refresh-token rotation; 5-min access tokens; `UserSessionsRevoked` makes identity-access-service log out the user's Keycloak sessions and BFFs delete their Redis sessions | Session revocation tests |
| T29 | Redis | I/T | A service reads another's keys, poisons caches, rate-limit buckets or quota counters; agent output streams leak | ACL user per service restricted to its prefix (`~waver:<prefix>:*`), administrative and keyspace-wide commands not granted; TLS and AUTH; credentials from Vault; AuthorizationPolicy admits only declared clients; no PII in keys; run-output streams `waver:run-stream:*` private to agent-runtime (channel-service and workspace-bff subscribe through agent-runtime's SSE endpoint) and encrypted (RFC-0007); quota counters reconciled against authoritative PostgreSQL (tampering bounded) | ACL configuration tests in the Helm chart CI; chaos test (ADR-0020) |
| T30 | Qdrant | I/T | Cross-tenant retrieval through a missing filter, another service's collection, or direct API access; poisoned vectors | Per-service credential restricted to its collections; mandatory `org_id` (+ workspace/domain/ACL) filter built by the platform library from the token; final PostgreSQL check before use; only knowledge-service and memory-service admitted by AuthorizationPolicy; TLS; writes only from the owning ingestion workflow | Tenancy kit on Qdrant; rebuild drill (ADR-0025) |
| T31 | Schema registry | T/D | Unauthorized schema registration, deletion or rule change breaks or subverts consumers | Services `sr-readonly`; only the CI release identity `sr-developer`; `sr-admin` break-glass; authentication through Keycloak; deletion disabled in staging/production; `BACKWARD_TRANSITIVE` rule; schemas reviewed in Git; registry DB backed up (IDs preserved) | Alert on registry writes outside CI; compatibility checks in CI |
| T32 | CDC pipeline (Kafka Connect / Debezium) | I/T/E | Connect workers hold replication credentials that bypass RLS and produce as owner principals; a rogue connector or slot exfiltrates a database's WAL | `<svc>_cdc` limited to the publication's tables (RFC-0007); Connect REST API admitted only for the pipeline identity (AuthorizationPolicy); connector configs as code, secrets referenced from Vault-delivered files (never in the API); per-connector producer principal; any replication slot not declared in `databases.yaml` raises a security page | Slot inventory check; connector config diff against Git |
| T33 | Temporal | T/E/I | A service signals, terminates or reads another service's workflows; Temporal Web exposes payloads | Namespaces per plane with server-side authorization from Keycloak-issued tokens (workers and clients authenticate with their service client); workflows interacted with only by the owning service (ADR-0023); payloads carry IDs only (RFC-0007 §8); Temporal Web only for WAVER operators via Keycloak SSO + AuthorizationPolicy; mTLS by the mesh | Authorization tests per namespace; payload lint test |
| T34 | Vault | E/I/D | Over-broad policy, stolen Vault token or Vault compromise exposes secrets or decrypt capability; Vault outage | Kubernetes auth roles bound to service account + namespace; one policy per service, as code, reviewed; Transit keys non-exportable; root token revoked after bootstrap and regenerated only with recovery-key quorum; KMS auto-unseal; audit device mandatory (Vault refuses requests if no audit device can write); operator access through Keycloak OIDC with MFA; only declared workloads admitted (AuthorizationPolicy); HA Raft cluster | Policy tests; quarterly policy review; Vault audit review |
| T35 | Service mesh | T/E | Misconfiguration (PERMISSIVE mode, over-broad AuthorizationPolicy, wildcard ServiceEntry) or istiod compromise (mints workload identities) | PeerAuthentication STRICT in all `waver-*` namespaces; AuthorizationPolicies generated from the RFC-0002 §4 dependency graph; Kubernetes `ValidatingAdmissionPolicy` rejects PERMISSIVE/DISABLE mTLS, ALLOW-all policies and wildcard ServiceEntries in `waver-*` namespaces; istiod in a restricted namespace with minimal RBAC; mesh retries disabled (ADR-0026) | CI check on generated policies; staging test that calls outside the graph are denied; plaintext-traffic alert |

### 4. Network security
Two independent layers: **Istio ambient** (identity-based, L4 everywhere, L7 where a waypoint is deployed) and **Kubernetes NetworkPolicies** (IP/port-based). Both are generated from the dependency graph (RFC-0002 §4) and kept with each Helm chart; a call must pass both.

**Mesh (ADR-0026)**
- `PeerAuthentication` STRICT in every `waver-*` namespace; workload identities derived from Kubernetes service accounts (`cluster.local/ns/<ns>/sa/<service>`).
- Each namespace has a deny-by-default AuthorizationPolicy; each destination service ships an ALLOW policy listing the source identities of its declared callers. L4 policies are enforced by ztunnel; L7 rules (paths, methods) require a waypoint, deployed for connector-service, identity-access-service and model-gateway-service (and for canary traffic splitting of data-plane services, RFC-0014).
- Mesh retries are disabled; mesh timeouts are only an outer safety net (resilience stays in Resilience4j, RFC-0005).

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata: { name: default, namespace: waver-commercial }
spec: { mtls: { mode: STRICT } }
---
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata: { name: deny-by-default, namespace: waver-commercial }
spec: {}
---
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata: { name: entitlement-service-allow, namespace: waver-commercial }   # CONVENTIONS: <service>-allow
spec:
  selector: { matchLabels: { app.kubernetes.io/name: entitlement-service } }
  action: ALLOW
  rules:
    - from:
        - source:
            principals:                       # generated from RFC-0002 §4
              - cluster.local/ns/waver-edge/sa/partner-bff
              - cluster.local/ns/waver-edge/sa/control-bff
              - cluster.local/ns/waver-commercial/sa/provisioning-service
              - cluster.local/ns/waver-agent/sa/agent-builder-service
      to:
        - operation: { ports: ["8080"] }
```

**NetworkPolicies (second layer)**
- Namespaces per plane (RFC-0015). Every namespace has **default deny** for ingress and egress; allow rules are generated from the dependency matrix in MICROSERVICES_MAP.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-all, namespace: waver-commercial }
spec: { podSelector: {}, policyTypes: [Ingress, Egress] }
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: entitlement-service-ingress, namespace: waver-commercial }
spec:
  podSelector: { matchLabels: { app.kubernetes.io/name: entitlement-service } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector: { matchLabels: { waver.io/plane: edge } }
          podSelector: { matchLabels: { app.kubernetes.io/name: partner-bff } }
      ports: [ { port: 8080, protocol: TCP }, { port: 15008, protocol: TCP } ]   # 15008 = HBONE (ambient mesh tunnel)
```
- Allowed egress for every pod: cluster DNS, its PostgreSQL endpoint, Kafka brokers, the telemetry endpoint (RFC-0009), Keycloak (token/JWKS), Vault, its declared in-cluster dependencies and, where declared, Redis, Qdrant, the Temporal frontend and the registry; plus the ambient mesh ports.
- Ingress from edge-gateway is allowed only to the three BFFs, to the Keycloak login routes and to the `hooks.` targets (channel-service, connector-service, trigger-service) on the specific ports. Domain services are never reachable from the edge.

**Internet egress (egress gateway + ServiceEntry, ADR-0026):** application pods never reach the internet directly. Egress-allowed services route through the Istio egress gateway; the gateway forwards only to hosts listed in a `ServiceEntry` and only for source identities allowed by its AuthorizationPolicy. TLS is end to end (the application originates TLS; the gateway routes by SNI). Only the egress gateway pods have an internet NetworkPolicy (`0.0.0.0/0`, `::/0` **except** RFC 1918, CGNAT, link-local including cloud metadata `169.254.169.254`, loopback, ULA `fc00::/7` and cluster CIDRs).

| Service | Internet egress | Destinations (ServiceEntry allow-list) | Additional controls |
|---|---|---|---|
| model-gateway-service | Yes | LLM/embedding provider endpoints from ProviderAccount / ModelCatalog | BYOK via the `tenant-credentials-llm` lease (ADR-0017, ADR-0022) |
| connector-service (+ `connector-runner-*`) | Yes | Hosts declared by certified connector manifests and approved custom API connectors (ServiceEntries `egress-connector-service-<provider>`, CONVENTIONS) | SSRF guard (§6), manifest-declared hosts, per-connection limits; outbound webhook delivery |
| channel-service | Yes | Channel provider endpoints (messaging, e-mail) | `tenant-credentials-channel` lease |
| Keycloak | Restricted | Customer IdP endpoints (OIDC discovery/token/JWKS, SAML metadata), one ServiceEntry per approved IdP host | Created when identity-access-service approves the IdP configuration (T27) |
| billing-service | Restricted | Payment gateway and fiscal provider endpoints (providers open) | `tenant-credentials-payment` lease when a reseller brings its own gateway account |
| All other services | **No** | In-cluster only | Needs from the outside go through connector-service (e.g., knowledge web sources, outbound webhooks) |

### 5. Identities, transport and encryption
- **Workload identity**: one Kubernetes ServiceAccount per service (`automountServiceAccountToken: false` unless the service calls the Kubernetes API or uses Vault Kubernetes auth via projected token). The same service account is the service's mesh identity (ADR-0026), its Vault Kubernetes auth role (ADR-0022) and is mapped to one Keycloak client per service (`client_credentials`; `private_key_jwt` client authentication with the key in Vault preferred over shared secrets; clients created through the Admin API at provisioning, ADR-0021). One Kafka principal, one DB role set (Vault-issued logins), one Redis ACL user and, where used, one Qdrant credential and one Temporal namespace client per service (RFC-0006, RFC-0007).
- **Human identity**: Keycloak (realm `waver`, one Keycloak Organization per WAVER Organization) via the BFFs; MFA and step-up for the roles defined by identity-access-service; enterprise SSO per Organization through identity brokering; 5-min access tokens; refresh tokens held only server-side in the BFF session store in Redis (ADR-0021, ADR-0020).
- **In transit:**

| Hop | Protection |
|---|---|
| Client → ingress | TLS 1.2+ (1.3 preferred), HSTS, modern cipher suites; certificates managed by the platform (tooling open) |
| Ingress → edge → BFF → services | JWT on every call + Istio ambient STRICT mTLS (ztunnel) between all pods |
| Services → PostgreSQL | TLS `verify-full` (+ mesh mTLS for in-cluster endpoints) |
| Services → Kafka | TLS + SASL/SCRAM or mTLS (RFC-0006) |
| Services → Redis, Qdrant, Vault, Keycloak, Temporal, registry | TLS with authentication of the client (ACL user, API credential, Vault token, client credentials) + mesh mTLS |
| Egress services → Internet | Through the egress gateway; TLS with certificate validation originated by the application; no TLS downgrade; HTTPS only for connectors |

- Workload certificates are issued and rotated automatically by istiod (short-lived); Vault PKI is used only for external-facing internal CAs if needed (ADR-0022).
- **At rest:** managed PostgreSQL storage encryption, Kafka broker storage encryption, encrypted volumes for Qdrant and for Redis persistence (if enabled), Vault barrier encryption with KMS auto-unseal, encrypted backups and snapshots; Kubernetes Secrets (only the short-lived ones synced by the Vault Secrets Operator) encrypted with KMS. Field-level encryption through Vault Transit for tenant credentials (connector-service) and for Restricted fields designated in RFC-0007.

### 6. Secrets and SSRF guard
- **Platform secrets** (ADR-0022): stored in Vault; delivered to pods by the Vault Secrets Operator (synced to short-lived Kubernetes Secrets mounted as files) or the Vault Agent injector; never in env vars for long-lived keys, images, Git or Helm values. Each service authenticates with the Kubernetes auth method and reads only its own paths.
  - PostgreSQL: dynamic credentials, TTL 1 h (RFC-0007); the CDC role is a static role rotated every 30 days.
  - Redis: dynamic ACL users where the Vault database engine supports them, otherwise KV with 90-day rotation; Qdrant, Kafka, registry and Temporal client credentials: KV v2, 90-day rotation.
  - Keycloak: realm signing keys rotated every 30 days (2 keys in JWKS during overlap); service client keys (`private_key_jwt`) 90 days, stored in Vault.
  - Transit keys: rotated yearly or on suspicion; old versions kept for decryption until a background rewrap completes, then `min_decryption_version` raised.
  - Secret scanning runs pre-commit (optional) and in CI (mandatory).
- **Tenant credentials** (ADR-0014 ownership, ADR-0022 mechanism): owned by connector-service, write-only, Transit-encrypted with the org as encryption context, audited on create/rotate/revoke/**use**. Plaintext leaves connector-service only through the three audited leases: model-gateway-service (`LLM_PROVIDER_KEY`, key `tenant-credentials-llm`), channel-service (`CHANNEL_PROVIDER_TOKEN`, key `tenant-credentials-channel`) and billing-service (reseller-owned payment gateway credentials, key `tenant-credentials-payment`). The lease API returns the **ciphertext**; the lessee decrypts it under a **decrypt-only** Vault policy for its single key and must never persist, log or cache the plaintext on disk. All other credentials use `tenant-credentials`, decryptable only by connector-service. Optional dedicated node pool and stricter policies for connector-service.
- **Outbound webhook signing**: Transit Ed25519 key `outbound-webhooks`, `sign` allowed only to connector-service (RFC-0008).
- **SSRF guard** in connector-service's generic HTTP engine and SDK HTTP client (RFC-0018); the egress gateway's ServiceEntry allow-list is the second layer:
  1. Allow only `https` (and `http` only for explicitly certified connectors) on ports 443 and manifest-declared ports.
  2. Resolve DNS in-process. Reject the request if **any** resolved address is in a blocked range. Connect to the validated IP (pinning), with SNI/Host set to the original name.
  3. Follow at most 3 redirects, re-validating each hop. No cross-scheme downgrade.
  4. Reject literal IPs in decimal, octal or hex notation and IPv4-mapped IPv6 that map to blocked ranges.
  5. Strip hop-by-hop and internal headers. Never forward platform tokens. Response size cap, timeouts, no proxy settings inherited from the environment (the egress gateway is reached through mesh routing, not an application proxy).
  6. Custom API connectors (ADR-0016) are bound to the hosts declared at creation. A host change is an audited new connector version and updates the ServiceEntry allow-list.

### 7. Support access and impersonation
Rules (defaults, business to confirm approvers):
- Support access requires an open SupportCase (support-service) and a **SupportAccessGrant** (identity-access-service) with reason, scope (org, optional workspace/domain), access level (`READ_ONLY` default, `READ_WRITE` requires elevated approval), maximum duration of 4 h, and approver.
- Tokens obtained under a grant are issued by Keycloak through token exchange and carry `support_grant` (set by the claims mapper from identity-access-service) and the `act` chain. Every request and event records `supportgrantid`.
- Forbidden under impersonation: reading or replacing credentials, changing contracts, prices, billing or entitlements, exporting or purging data, creating API keys, changing MFA/SSO settings.
- Customer admins (and the owning reseller) see active and past grants and the actions taken (audit view), and are notified on grant creation.
- A separate break-glass procedure (production infrastructure, Vault or DB access) requires two-person approval, time-boxed Vault-issued credentials (e.g., `<svc>_breakglass_ro`, RFC-0007), session recording where the platform supports it, and a post-incident review.

```mermaid
sequenceDiagram
  participant SA as WAVER/Reseller support agent
  participant SS as support-service
  participant IA as identity-access-service
  participant KC as Keycloak
  participant AP as approval-service
  participant AU as audit-service
  participant NS as notification-service
  SA->>SS: work on SupportCase
  SS->>IA: request SupportAccessGrant(case, scope, level, duration, reason)
  IA->>AP: create ApprovalRequest
  AP-->>IA: ApprovalGranted (event)
  IA->>IA: activate grant (expires_at)
  IA-->>AU: SupportAccessGranted
  IA-->>NS: SupportAccessGranted → notify customer admins
  SA->>KC: token exchange via control-bff/partner-bff
  KC->>IA: claims mapper reads active grant
  KC-->>SA: token with support_grant (held by the BFF)
  SA->>SS: diagnose (all calls audited with supportgrantid)
  IA->>IA: expiry → revoke grant; logout Keycloak sessions; BFF sessions deleted (audited)
```

### 8. Commercial and cross-tenant isolation summary
| Boundary | Construction | Configuration | Data | Verification |
|---|---|---|---|---|
| Customer ↔ commercial data | workspace-bff has no commercial clients (ADR-0009); mesh policies do not admit it to commercial services | Audience gates in BFF | Curated public API/webhooks, sanitized errors | CI + mesh deny test + E2E |
| Reseller ↔ reseller | partner-bff only calls portfolio-scoped endpoints | Permissions | RLS portfolio policy | Tenancy kit |
| Tenant ↔ tenant | Tenant context from token | RLS `FORCE`; mandatory Qdrant `org_id` filter | Org-keyed caches (Caffeine and Redis), scoped retrieval, Transit context per org | Tenancy kit, ArchUnit |
| Domain ↔ domain (inside a workspace) | Domain-scoped bindings | `waver.domain_id` RLS narrowing; Qdrant domain filter | Retrieval/memory filters | Retrieval tests |

### 9. Secure SDLC and supply chain
| Stage | Control | Gate |
|---|---|---|
| Design | Threat model section required in RFCs for high-risk domains (IAM, billing, tool execution, connectors, model gateway) | RFC review |
| Code | CODEOWNERS security review for `platform/*`, identity-access, the Keycloak theme and claims mapper SPI, connector-service, tool-runtime, model-gateway, edge, Vault policies, mesh/network policies, `contracts/events`; STD-009 rules | PR approval |
| Every PR | Secret scan; SAST; dependency scan (SCA) with license check; IaC/Helm policy lint (Pod Security `restricted`, no privileged, resource limits, generated AuthorizationPolicy present and matching declared dependencies, no PERMISSIVE mTLS, Vault policy least privilege, Redis ACL prefix); ArchUnit/tenancy tests | Block on critical/high without approved exception |
| Build | Reproducible Maven build against the BOM; minimal non-root JRE base image (ADR-0007); SBOM (CycloneDX) per image; container scan; image signing and build provenance (tool open, CI-dependent) | Block on critical vulnerabilities with fix available |
| Deploy | Images only from the private registry, by digest (platform component images mirrored); admission-time signature verification (mechanism open, tied to cloud/CI decision); Pod Security Standards `restricted`; read-only root filesystem; drop all capabilities; seccomp `RuntimeDefault`; `ValidatingAdmissionPolicy` for mesh settings (T35) | Admission reject |
| Run | Daily re-scan of running images against new CVEs; SBOM inventory query for incident response | Ticket SLA: critical 7 days, high 30 days (defaults) |

New libraries follow STD-013. Base images are rebuilt at least weekly. Platform components (Keycloak, Vault, Redis, Qdrant, Temporal, Apicurio, Kafka Connect/Debezium, Istio) follow the same scan/SBOM rules and have upgrade runbooks.

### 10. LGPD controls
| Requirement | Control |
|---|---|
| Roles | WAVER is controller for platform users and commercial data; processor (operator) for the customers' end-user data processed by agents. DPA templates are a legal deliverable. |
| Minimization (Art. 6) | IDs-only events (RFC-0006); no PII in telemetry (RFC-0009), Redis keys, Qdrant payloads or Temporal payloads (RFC-0007); prompt compilation includes only bound knowledge/memory; memory retention policies (memory-service). |
| Data subject rights (Art. 18) | `DataLifecyclePort` export/purge per service, covering PostgreSQL, Qdrant and Redis, and `subject_ref` index (RFC-0007), requested through the customer admin (controller). |
| Retention and deletion | Per data kind, purge with approval and audit (RFC-0007), including Keycloak users of a purged organization. Never deletion on non-payment (AR-012). |
| Security (Art. 46) | This RFC. |
| Incident communication (Art. 48) | §11. Notify ANPD and affected data subjects within the ANPD regulatory deadline (currently 3 business days; legal to confirm). Notify controllers (customers) and resellers without undue delay. |
| International transfer (Art. 33) | LLM, channel and cloud providers may process data outside Brazil. Provider/region selection in model-gateway, BYOK, and contractual safeguards. Open question. |
| Records of processing (Art. 37) | Generated from data classification annotations (OpenAPI, Avro `.avsc`, DB comments) and EVENT_CATALOG. |
| Sub-processors | Maintained list (cloud, LLM providers, channel providers) disclosed to customers and resellers. |

### 11. Incident response
- Severity: SEV1 (confirmed cross-tenant exposure, credential/key compromise, active exploitation), SEV2 (exploitable vulnerability in production, single-tenant breach), SEV3 (contained issue, no data exposure), SEV4 (hardening).
- Phases: detect → triage (security on-call, 15 min for SEV1) → contain → eradicate → recover → notify → post-incident review within 5 business days. Evidence comes from security logs (1-year archive, RFC-0009, including the Vault audit log and Keycloak events), audit-service and the SBOM inventory.
- Containment levers (each is an audited action with a runbook):

| Lever | Mechanism |
|---|---|
| Revoke all sessions/tokens of an org | identity-access-service logs out the org's users in Keycloak (Admin API), disables its clients and API keys, BFFs delete Redis sessions (`UserSessionsRevoked`, `ApiKeyRevoked`); services also reject revoked support grants from their local grant view (RFC-0004) |
| Global token invalidation | Emergency Keycloak realm key rotation (old key removed from JWKS) and realm not-before revocation; forced re-login |
| Revoke a service identity | Disable its Vault Kubernetes auth role and revoke its leases by prefix; disable its Keycloak client; deny it in AuthorizationPolicies |
| Block a Tool or Connector everywhere | Emergency block status in tool-registry / connector-service, propagated to data-plane read models (proposed, see Open Questions) |
| Disable an LLM provider | model-gateway routing rule change (ADR-0017) |
| Stop an org's workloads | Emergency entitlement suspension by WAVER (reversible, AR-012) |
| Rotate tenant credentials | connector-service revoke, plus customer re-authorization; on suspected key compromise rotate the Transit key and rewrap |
| Cut a lessee's access to credentials | Remove its decrypt policy on `tenant-credentials-llm` / `-channel` / `-payment` (immediate) |
| Cut internet egress | Remove ServiceEntries or deny at the egress gateway AuthorizationPolicy (immediate); NetworkPolicy change on the egress gateway as backstop |

### 12. Security testing cadence
| Activity | Cadence |
|---|---|
| SAST, SCA, secret scan, IaC/mesh/Vault-policy lint | Every PR |
| Container scan | Every build and daily on running images |
| Tenancy isolation suite (PostgreSQL, Qdrant, Redis keys), SSRF corpus, audience E2E, mesh deny tests | Every build of affected services |
| Prompt-injection evaluation suite | Every AgentVersion release gate (evaluation-service) and weekly platform corpus run |
| DAST against staging | Weekly and before each public API release |
| Key/credential rotation drills (Keycloak keys, Transit keys, dynamic credentials) | Quarterly |
| Vault policy, Keycloak realm and AuthorizationPolicy review against Git | Quarterly |
| External penetration test | Before GA (including Keycloak login, recovery and federation flows, the claims mapper, and the egress gateway), annually, and after major architecture changes |
| Threat model review | Per RFC for high-risk domains; annual full review |
| Incident response tabletop | Semi-annually |

## APIs / Events / Data Ownership
- Uses: `SupportAccessGranted`, `RoleAssigned`, `MembershipChanged` (identity-access); `CredentialRotated` (connector-service); `ApprovalGranted/Rejected/Expired` (approval-service). audit-service consumes them (EVENT_CATALOG).
- Also used: `SupportAccessRevoked`, `ApiKeyCreated`/`ApiKeyRevoked`, `UserSessionsRevoked`, `IdpConfigurationChanged` (identity), `CredentialLeased` (connector) and explicit audit records on `waver.audit.audit-record.v1`.
- Proposed additions (not in MICROSERVICES_MAP v1.2): `ToolBlocked` / `ConnectorBlocked` (emergency kill switch); `SupportAccessExpired` (RFC-0004).
- No new service. Security libraries (token validation, SSRF-safe HTTP client, Vault Transit crypto helper, log sanitizer) live in `waver-platform-starter` and `waver-connector-sdk`. Platform-owned security configuration: Vault policies and roles, Keycloak realm configuration, Istio PeerAuthentication/AuthorizationPolicy/ServiceEntry, NetworkPolicies, Redis ACLs, registry roles.

## Security & Privacy
This RFC is the security reference. Privacy-specific controls are in §10. Data classification and lifecycle are in RFC-0007.

## Reliability & Failure Modes
| Failure | Behaviour |
|---|---|
| Keycloak unavailable | Existing tokens and cached exchanged tokens remain valid until expiry (JWKS cached). New logins and token exchanges fail. Data plane continues for authorized workloads (AR-013). |
| identity-access-service unavailable | Keycloak's claims mapper serves claims from its short cache (≤ 60 s), then token issuance fails closed; existing tokens keep working until expiry. |
| JWKS fetch fails | Cached keys used up to 24 h. Unknown `kid` → reject (fail closed). |
| Vault unavailable or sealed | Transit decrypt fails closed: credential-dependent tool calls, leases and webhook signing fail with `DEPENDENCY_UNAVAILABLE`; no plaintext data keys are cached (ADR-0022). New pods cannot start; DB credentials are not renewed (RFC-0007 bounds). Tier-0 page. |
| Redis unavailable | Edge rate limiting falls back to the local limiter (Keycloak brute-force detection still protects logins); BFF sessions lost (re-login when Redis returns); no security control fails open beyond these documented degradations (ADR-0020). |
| istiod unavailable | Existing mesh configuration and workload certificates keep working until certificate expiry; no new workloads join; page. |
| ztunnel unavailable on a node | Pods on that node lose mesh connectivity (fail closed); node cordoned and drained. |
| Entitlement read model stale | Last known state applies. Suspension propagation SLO 60 s (RFC-0009). |
| NetworkPolicy or AuthorizationPolicy misconfiguration | Detected by connectivity and deny tests in the deploy pipeline. Default deny means failures surface as outages, not exposure. |
| Signing key compromise | Emergency rotation, forced re-login, incident SEV1. |
| Scanner/CI outage | Releases blocked (no bypass) except a documented emergency path with retroactive scan. |

## Observability
Security signals go to the security log stream and alerts (RFC-0009). They include authentication failures by client, Keycloak login failures, brute-force lockouts and admin events, MFA failures, grant creation/use, break-glass use, Vault audit events (denied requests, root token use, policy changes, lease activity per lessee), RLS context-missing errors, event `ce_orgid` mismatches, SSRF guard blocks per connection, Istio AuthorizationPolicy denials and egress gateway blocks, non-STRICT mTLS or plaintext traffic, undeclared replication slots, registry writes outside CI, Redis ACL denials, unusual credential use, API key creation and use from new networks, and audit chain verification failures. Each paging security alert routes to security on-call with a runbook.

## Testing Strategy
- Automated: the controls listed in §3 "Verification" run in CI. Tenancy, audience and mesh-deny suites block releases.
- Adversarial: SSRF corpus, prompt-injection corpus, authorization fuzzing of internal APIs (role × tenant matrix generated from OpenAPI `x-waver-audience`), hostile IdP fixtures for federation, Vault policy negative tests (every service × every Transit key).
- External: penetration tests and DAST per §12.

## Deployment / Rollout / Rollback
- Before the first vertical slice: Istio ambient with STRICT mTLS and generated AuthorizationPolicies, default-deny NetworkPolicies, Pod Security `restricted`, CI gates, Vault (Kubernetes auth, Secrets Operator, dynamic DB credentials, Transit) and Keycloak realm as code.
- Before the first production tenant: egress gateway with ServiceEntry allow-lists for all egress paths, SupportAccessGrant flow, audit chain verification, incident runbooks, external pen test (including Keycloak flows), LGPD DPA and sub-processor list, Vault audit device and Keycloak events in the security stream.
- Before GA: prompt-injection release gates mandatory, image signing with admission verification, full tabletop exercise.
- Security configuration (mesh and network policies, ServiceEntries, Vault policies, Keycloak realm, allow-lists) is versioned and rolled back like code. Rollbacks never re-enable a revoked key, credential or policy grant.

## Alternatives Considered
- No service mesh, encryption from the CNI or the cloud network (v1.2 position): encrypts traffic but provides no workload identity or identity-based authorization; superseded by ADR-0026.
- Istio sidecar mode or Linkerd: see ADR-0026 (ambient chosen for overhead; Linkerd stable releases need a commercial license).
- Application-level host allow-lists only (v1.2 egress design): depended on every service's code being correct; replaced by the egress gateway with ServiceEntries, keeping the connector SSRF guard.
- Egress proxy for all services: centralizes control but every service would gain an internet path; the egress gateway is used only by the allowed services.
- Kubernetes Secrets + application envelope encryption (ADR-0014 mechanism): fewer components, no dynamic credentials or central audit; superseded by Vault (ADR-0022).
- Spring Authorization Server in identity-access-service (ADR-0008): full control, but login, MFA, federation and admin tooling to build and secure; superseded by Keycloak (ADR-0021).
- Allowing the model to choose connections or recipients freely: rejected (T5, T7).

## Risks
- Concentration of tier-0 security components (Keycloak, Vault, istiod/ztunnel, Redis): an outage of any of them degrades login, decryption or connectivity platform-wide. Mitigated by HA deployments, runbooks and the documented fail-closed behaviour.
- Prompt injection has no complete technical fix. Controls limit impact (least-privilege tools, approvals, egress), not the occurrence.
- Keycloak's custom claims mapper couples token issuance to identity-access-service availability (short cache only).
- Mesh, Vault and Keycloak policies are powerful configuration; a wrong change can expose or block everything. Mitigated by policy as code, generation from the dependency graph, admission checks and review.
- The Debezium CDC role bypasses RLS on the outbox; mitigated by table scope and slot monitoring (T32).
- Licensing: Vault is BSL 1.1 (OPEN_DECISIONS D-38, OpenBao fallback) and Redis 8 is tri-licensed (D-37, Valkey fallback).
- Support grant friction may push teams toward break-glass. Monitor usage and review monthly.

## Open Questions
1. ~~identity-access-service needs internet egress for federated customer IdPs~~ — resolved by ADR-0021/ADR-0026: brokering is performed by Keycloak, whose egress to customer IdPs is a restricted exception through the egress gateway with one ServiceEntry per approved IdP host.
2. ~~Payment gateway and fiscal provider integrations: through connector-service or a restricted exception?~~ — resolved by ADR-0026: billing-service has restricted egress through the egress gateway; reseller-owned gateway credentials are leased under `tenant-credentials-payment` (ADR-0022).
3. Who approves support grants: customer admin, owning reseller, or both? Is pre-consent per contract allowed (business, Commercial §20 "Support model")?
4. International data transfer to LLM providers and data residency requirements (Scope §24).
5. ~~Pod-to-pod encryption option (mesh vs CNI)~~ — resolved by ADR-0026 (Istio ambient STRICT mTLS). Image signing/admission verification tooling still depends on cloud/CI decisions.
6. Add emergency block events (`ToolBlocked`, `ConnectorBlocked`) to MICROSERVICES_MAP.
7. connector-service egress for custom API connectors and generic sources: keeping a ServiceEntry per declared host requires the pipeline (or connector-service) to update mesh configuration when a connector version is published; confirm the mechanism and whether a broader, SSRF-guarded allow rule is acceptable in the interim.
8. Per-service signing of audit records with Vault Transit (RFC-0006 Open Question 6) would close the residual risk of T21b.

## Decision
Pending review. Proposed: adopt the threat model, mesh and egress rules, Vault secret and lease model, Keycloak hardening, support-grant model, SDLC gates, LGPD mapping and testing cadence above as the security baseline for all services and RFCs.
