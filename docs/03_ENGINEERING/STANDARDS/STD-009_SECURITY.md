# STD-009 - Security Standard (Secure Coding)
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Keycloak, HashiCorp Vault, Istio ambient, Redis, Qdrant, Temporal).
Version: 1.3
Date: 2026-10-19
Applies to: all WAVER code (services, BFFs, edge-gateway, platform libraries, Angular apps, connector manifests)
Related: SECURITY_BASELINE, ADR-0006, ADR-0009, ADR-0010, ADR-0014, ADR-0016, ADR-0017, ADR-0020, ADR-0021, ADR-0022, ADR-0023, ADR-0025, ADR-0026, AR-008, AR-009, AR-011, RFC-0004, RFC-0010, STD-002, STD-006, STD-008, STD-013, STD-014

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. This standard covers secure coding; platform-level controls (network, IdP, mesh, secrets platform, cluster) are in RFC-0010/RFC-0015; this standard states what code and configuration-as-code must do with them.

## 1. Identity and tenant context
- **S-01** Every service is an OAuth2 resource server validating JWTs issued by **Keycloak** (realm `waver`, ADR-0021) through `waver-platform-starter` (issuer `https://auth.<domain>/realms/waver`, audience = the service's client ID added by a Keycloak audience mapper, signature via cached JWKS, expiry). Mesh identity (Istio mTLS) is defense in depth and never replaces token validation. Endpoints without authentication are limited to health on the management port and explicitly listed public webhooks with signature verification.
- **S-02** Tenant context (`orgId`, `orgType`, `workspaceId` set, roles) is taken **only** from the validated token (`org_id`, `org_type`, `ws_ids`, `roles`) or, for events, from the envelope of a trusted producer. Request bodies, query parameters and client headers (`X-Org-Id` and similar) MUST NOT set or override it.
- **S-03** Calls on behalf of a user use OAuth 2.0 Token Exchange (RFC 8693) to obtain a down-scoped token with the `act` chain; forwarding the user's original token to another service is forbidden. Token exchange is Keycloak's standard token exchange with per-client exchange permissions. Service-only calls use `client_credentials`, one Keycloak client per service; client secrets/keys are delivered by Vault (STD-014) and never appear in configuration repositories.
- **S-04** Support/impersonation sessions (`support_grant`) are read-only unless the grant says otherwise, are time-boxed, and every request produces an audit event with the real operator. Permissions marked `stepUp: true` in the role catalog (purge, suspension override, credential write, support grant approval) require recent, stronger authentication: the service checks the token's `acr` (Keycloak ACR level) and `auth_time` and answers 401 `STEP_UP_REQUIRED` (RFC-0004 §3).
- **S-31** Keycloak customization is limited to the WAVER login theme and the protocol-mapper SPI that adds `org_id`, `org_type`, `ws_ids`, `roles`, `act`, `support_grant` (ADR-0021). The mapper follows STD-001, reads claims only from identity-access-service's internal endpoint (cached, mTLS + client credentials) and **fails closed**: if claims cannot be resolved, token issuance fails rather than issuing a token without tenant claims. Realm, clients, mappers and IdP brokering configuration are code applied through the Admin API by the pipeline; manual console changes in staging/production are forbidden. Admin API credentials exist only for the pipeline and identity-access-service, scoped to the operations they need.

## 2. Authorization in the application layer
- **S-05** The authoritative authorization check is in the application service, before loading or mutating data: `access.require(permission, resourceRef)`. Controller annotations or BFF checks are additional, never the only check (BFF/UI checks are UX).
- **S-06** Deny by default. Decisions combine RBAC (roles) with ABAC attributes: organization relationship (WAVER → reseller → customer portfolio), workspace and domain membership, resource ownership, and **entitlements** for commercially controlled capabilities (AR-011, local entitlement snapshot).
- **S-07** Resources of other tenants return 404, not 403 (STD-003). Denials on sensitive operations and every allow on high-impact actions (suspension, price override, purge, credential use, role grants, support access) emit audit events (AR-009).
- **S-08** A reseller may act only on customers in its own portfolio; WAVER oversight uses audited cross-tenant paths (ADR-0010). The customer audience never receives marketplace, wholesale or margin data (ADR-0006, ADR-0009).

```java
@Transactional
public void suspend(SuspendCustomer cmd) {
  var principal = tenantContext.principal();
  access.require(Permission.CUSTOMER_SUSPEND, ResourceRef.customer(cmd.customerId()));   // RBAC + portfolio ABAC
  var customer = customers.get(cmd.customerId());
  customer.suspend(cmd.reason(), principal.actor(), clock);    // reason mandatory (domain invariant)
  customers.save(customer);
  events.publish(customer.pullEvents());                       // CustomerSuspended -> audit-service
}
```

## 3. Input validation
- **S-09** All external input is validated at the adapter against the contract (Bean Validation generated from OpenAPI constraints: lengths, patterns, ranges, `maxItems`) and again by domain invariants. Validation is allow-list based; unknown request fields are rejected.
- **S-10** Size limits: edge-gateway rejects bodies over 1 MB by default (upload routes have their own limits); JSON parser limits (nesting depth, string length, number length) are set by the platform starter.
- **S-11** No Java native serialization (including `JdkSerializationRedisSerializer` and Java serialization of Temporal payloads), no polymorphic deserialization of untrusted JSON (no default typing, no `@JsonTypeInfo` with class names), XML parsers with DTDs and external entities disabled. Avro is deserialized only into generated specific records with the reader schema of the consumer.
- **S-12** SQL only through JPA/JDBC bind parameters. Dynamic sort/filter fields are mapped from an allow-list, never concatenated.
- **S-13** Regular expressions applied to user input are bounded (input length check first) and reviewed for catastrophic backtracking.

## 4. Output encoding and untrusted content
- **S-14** HTTP responses are JSON produced by the framework serializer; HTML is never built by string concatenation. Templated e-mails/notifications use contextual auto-escaping. CSV/XLSX exports neutralize formula injection (cells starting with `=`, `+`, `-`, `@` are prefixed with `'`).
- **S-15** Security headers on app hosts are set at edge-gateway (RFC-0008): strict CSP (STD-002 A-31), `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `frame-ancestors 'none'` (except the web chat widget route, scoped by customer-configured origins), `Referrer-Policy: strict-origin-when-cross-origin`. The BFF session cookie is `__Host-wv-session` (`HttpOnly; Secure; SameSite=Lax`) with `XSRF-TOKEN`/`X-XSRF-TOKEN` on state-changing requests (RFC-0004 §11).
- **S-16** LLM output, retrieved documents, tool results and inbound channel messages are **untrusted data**: never executed, never used as SQL, URLs, file paths or tool names without validation, and rendered as text in UIs.
- **S-17** Model output MUST NOT expand privileges: every tool execution is authorized against ToolBinding/ToolPermission, policies, approvals and entitlements independent of what the model requests. Secrets are never placed in prompts.

## 5. Outbound HTTP and SSRF
- **S-18** Only connector-service, model-gateway-service and channel-service have general internet egress (RFC-0008, RFC-0012); restricted egress exists for Keycloak/identity-access → customer IdPs (identity brokering) and billing-service → payment/fiscal providers. All egress leaves through the **Istio egress gateway**, and `ServiceEntry` allow-lists per destination host plus `AuthorizationPolicy` on the gateway decide which service identity may reach which host (ADR-0026). Outbound calls to tenant-supplied destinations (declarative/custom API connectors, outbound webhooks, knowledge source URLs) are executed by connector-service through the platform `SafeHttpClient`; other services (tool-runtime, knowledge, notification) ask connector-service and MUST NOT call tenant-supplied URLs themselves.
- **S-19** `SafeHttpClient` rules: `https` only; resolve DNS and reject loopback, private, link-local, CGNAT, multicast, unspecified and cloud metadata addresses (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, `169.254.0.0/16`, `::1`, `fc00::/7`, `fe80::/10`, `0.0.0.0`); connect to the validated IP (no re-resolution, prevents DNS rebinding); at most 3 redirects, each re-validated; connect timeout 5 s, total 30 s; response size cap 10 MB; no internal headers forwarded.
- **S-20** Internal service URLs come only from configuration, never from request data. Egress is additionally restricted by NetworkPolicy (only model-gateway reaches LLM providers, ADR-0017). Tenant-supplied destinations cannot be enumerated in `ServiceEntry` allow-lists, so connector-service's tenant-destination traffic uses a dedicated egress route that only its identity may use, and `SafeHttpClient` (S-19) remains the authoritative control for those destinations (see Open Questions).
- **S-32** Service-to-service traffic runs over Istio STRICT mTLS (`PeerAuthentication` STRICT in every `waver-*` namespace; `PERMISSIVE` and `DISABLE` are forbidden). Every service chart ships an `AuthorizationPolicy` generated from its declared synchronous dependencies (RFC-0002 §4); hand-written `ALLOW` rules that widen the graph and `AuthorizationPolicy` objects with empty `from` are forbidden. Waypoints add request-level rules on connector-service, identity-access-service and model-gateway-service. Mesh retries stay disabled (ADR-0026).

## 6. File uploads
- **S-21** Uploads (knowledge documents, attachments, imports) go through the BFF to the owning service with the RFC-0008 limit (≤ 20 MiB unless a route declares less) and an allow-list of types verified by magic bytes, not by extension or `Content-Type`.
- **S-22** Files are stored only through the `ObjectStoragePort` of the owning service (RFC-0007 §11), addressed by generated IDs under `org/<orgId>/...`; user file names are metadata only, never paths. Downloads use `Content-Disposition: attachment` and `nosniff`.
- **S-23** Parsing (PDF, Office, archives) runs with resource limits (time, memory, decompression ratio, entry count) in the owning service's worker deployment, never in BFFs or request threads.

## 7. Cryptography
- **S-24** Encryption of secrets at rest (tenant credentials) and signing of outbound webhooks/internal HMACs use **Vault Transit** (ADR-0022): keys never leave Vault and are rotated there. Locally, use only JCA/JCE standard algorithms through platform helpers: AES-256-GCM (random 96-bit IV per message, 128-bit tag, AAD binding `orgId` + record ID) where an approved design needs local data encryption; HMAC-SHA256 for webhook signatures with constant-time comparison (`MessageDigest.isEqual`); `SecureRandom` for tokens/keys; SHA-256 for hashing high-entropy API keys; no WAVER service stores or verifies user passwords (Keycloak does, ADR-0021). TLS 1.2+ for every connection.
- **S-25** Forbidden: custom algorithms or protocols, ECB/CBC without authentication, MD5/SHA-1 for security purposes, static or reused IVs, hardcoded keys, `java.util.Random` for security values.

```java
// connector-service, adapters.out.vault (STD-001 J-49): ciphertext only is persisted
final class VaultTransitCredentialCipher implements CredentialCipherPort {
  private static final String KEY = "tenant-credentials";
  private final VaultTransitOperations transit;            // Spring Vault, Kubernetes auth configured in bootstrap

  @Override public StoredCiphertext encrypt(OrgId org, Sensitive<byte[]> plaintext) {
    var ctx = VaultTransitContext.builder().context(org.toBytes()).build();   // derived key per org
    return new StoredCiphertext(transit.encrypt(KEY, Plaintext.of(plaintext.value()).with(ctx)).getCiphertext());
  }
  @Override public Sensitive<byte[]> decrypt(OrgId org, StoredCiphertext ciphertext) {
    var ctx = VaultTransitContext.builder().context(org.toBytes()).build();
    return Sensitive.of(transit.decrypt(KEY, Ciphertext.of(ciphertext.value()).with(ctx)).getPlaintext());
  }
}
```
- **S-26** Tenant credentials follow the ownership rules of ADR-0014 with the mechanism of ADR-0022: write-only through public APIs; connector-service stores only Vault Transit ciphertext (key `tenant-credentials`, derived per `orgId`); plaintext exists only in memory for the call (`Sensitive<T>`), is never cached and never leaves connector-service except the audited **lease** to model-gateway-service (`LLM_PROVIDER_KEY`) and channel-service (`CHANNEL_PROVIDER_TOKEN`), implemented as Transit decrypt permissions in those services' Vault policies for those credential kinds only. Platform secrets follow STD-014 (Vault KV/dynamic secrets, Vault Secrets Operator) and are never in code, images, config repositories, Helm values, logs or error messages.
- **S-33** Every workload authenticates to Vault with the Kubernetes auth method (one Vault role per service account); policies grant least privilege per service and are reviewed as code. Applications never hold Vault root or long-lived tokens; the Vault audit device stays enabled (STD-006 L-19).
- **S-34** Data stores added by ADR-0020..0025 follow the same tenant and secrecy rules: Redis — TLS, one ACL user per service restricted to its key prefixes, no PII in keys (STD-005 §9); Qdrant — one API key per service restricted to its collections, mandatory `org_id` filter built from the validated token, final PostgreSQL permission check (STD-005 §10); Temporal — a service uses only its own namespace and task queues, workflow payloads hold no secrets/PII (STD-001 J-36), Temporal Web is reachable only by WAVER operators through Keycloak SSO and Istio authorization; Apicurio Registry — write access only for the CI pipeline.

## 8. Dependency and build hygiene
- **S-27** Only TECH_STACK libraries or those approved through STD-013. Versions come from `waver-platform-bom` / the frontend lockfile; SNAPSHOT/`latest`/unpinned versions are forbidden; `npm ci` with committed `package-lock.json`.
- **S-28** CI runs dependency vulnerability scanning, secret scanning and SBOM generation for every build (SECURITY_BASELINE). Fix SLA from disclosure: critical 7 days, high 30 days, medium next release; exceptions recorded with expiry.
- **S-29** Container images: minimal JRE base pinned by digest, non-root user, read-only root filesystem, no shell tools in production images where feasible.

## 9. Security review triggers
- **S-30** A security reviewer (security CODEOWNER) MUST approve PRs that: change authentication, authorization, tenant context, RLS policies or oversight paths; add public API or webhook endpoints; touch crypto, secrets or credentials; add outbound integrations or change `SafeHttpClient`/manifest engine; handle file uploads; add PII fields or `x-waver-pii` event properties; change prompt compilation, tool exposure or policy enforcement in the agent plane; change billing/payment flows; add a dependency; change Keycloak realm/client/mapper/theme configuration or the mapper SPI; change Vault policies, roles or Transit keys; change Istio `AuthorizationPolicy`, `PeerAuthentication`, `ServiceEntry` or egress gateway rules; change Redis ACLs or Qdrant filter code. New high-risk features (IAM, billing, tool execution, connectors) require a threat model in their RFC.

## Enforcement
| Rule | Enforced by |
|---|---|
| S-01..S-03 token handling | Platform starter security auto-config; ArchUnit bans reading tenant IDs from `@RequestHeader`/request DTOs; token-validation tests in service template against a Keycloak test realm (ADR-0021, STD-010) |
| S-31 Keycloak | Realm configuration as code applied by the pipeline (drift check); mapper SPI tests in a Keycloak container (STD-010); pen test of login and federation before production (ADR-0021) |
| S-32 mesh | CI check that every chart ships its generated `AuthorizationPolicy` and that namespaces are STRICT; staging test that a call outside the dependency graph is denied (ADR-0026) |
| S-33, S-34 Vault and data stores | Vault policy lint and review; Vault Transit policy tests (lease exception); Redis ACL and Qdrant key configuration review; tenancy test kit extended to Qdrant |
| S-05..S-08 authorization | ArchUnit: every public use case method calls `AccessPolicy` (or is annotated `@PublicUseCase` with review); tenancy test kit; per-audience E2E (STD-010); ADR-0009 CI check |
| S-09..S-13 input | OpenAPI-generated validation; platform parser limits; ArchUnit bans `ObjectInputStream`, `enableDefaultTyping`, string-built queries |
| S-14, S-15 output/headers | BFF security header tests; CSP violation reports monitored |
| S-16, S-17 LLM content | Evaluation red-team suites (prompt injection, tool escalation) in evaluation-service (STD-010) |
| S-18..S-20 SSRF/egress | ArchUnit: HTTP clients with dynamic URLs only via `SafeHttpClient`; SSRF test corpus in platform tests; `ServiceEntry` allow-lists and egress-gateway `AuthorizationPolicy` reviewed as code; NetworkPolicy |
| S-21..S-23 uploads | Upload adapter tests with malicious fixtures (polyglots, zip bombs) |
| S-24..S-26 crypto/secrets | ArchUnit bans weak algorithms (`"MD5"`, `"SHA1"`, `"ECB"`), `java.util.Random` in security packages, Vault types outside `adapters.out.vault`/`bootstrap`; secret scanning in CI |
| S-27..S-29 dependencies | Maven Enforcer + BOM; npm allow-list; CI scanners (STD-012/STD-013); image policy at admission |
| S-30 review | CODEOWNERS paths + PR template checklist (STD-011) |

## Open Questions
1. RFC-0010 (security architecture) must reflect ADR-0021, ADR-0022 and ADR-0026; until then S-15 headers, egress rules and break-glass references point to RFC-0008/RFC-0012/RFC-0007 and the ADRs.
2. Malware scanning of uploads requires a scanner not in the approved stack.
3. CI security tools (SCA, secret scanning, SBOM generator, container scanner) are not yet selected (CI provider is open in TECH_STACK).
4. Tenant-destination egress for connectors: the dedicated egress route of S-20 (wildcard TLS destinations reachable only by connector-service's identity) needs a platform decision in RFC-0010/RFC-0015, since `ServiceEntry` allow-lists cannot list tenant-supplied hosts.
5. Identity brokering egress: ADR-0026 lists "identity-access → customer IdPs", but with Keycloak brokering (ADR-0021) the calls to customer IdPs originate from Keycloak; the egress allow-list should name Keycloak's identity.
6. ADR-0022 describes the Transit context for tenant credentials as "convergent"; convergent encryption makes equal plaintexts produce equal ciphertexts. This standard assumes key derivation with context = `orgId` **without** convergent mode; confirm in the ADR.
