# Security Baseline (v1.3)

Summary of mandatory controls. Details are in RFC-0010 (Security Architecture), RFC-0004 (Identity and Access), ADR-0021 (Keycloak), ADR-0022 (Vault), ADR-0026 (Istio), ADR-0010, ADR-0014 and STD-009 / STD-014.

| Control | v1.3 decision |
|---|---|
| Tenant isolation | Application checks + PostgreSQL Row-Level Security in every tenant-owned table (ADR-0010); Redis ACL key prefixes per service (ADR-0020); mandatory `org_id` filters in Qdrant (ADR-0025); `OrgId` search attribute in Temporal (ADR-0023) |
| Identity | Keycloak (OAuth 2.1/OIDC), MFA/WebAuthn for admin roles, SSO brokering per organization, Keycloak Organizations (ADR-0021) |
| Authorization | RBAC with ABAC conditions on org/workspace/domain/resource; least privilege (RFC-0004) |
| Service identities | One Keycloak client per service; token exchange for user context (ADR-0021); workload identity + STRICT mTLS from Istio (ADR-0026) |
| Commercial data exposure | Customers can never reach marketplace/wholesale/margin APIs: separate BFF per audience (ADR-0009) |
| Secrets | HashiCorp Vault: Kubernetes auth, dynamic PostgreSQL credentials, Transit encryption for tenant credentials (still owned only by connector-service, write-only, audited leases), audit device (ADR-0022, ADR-0014 rules) |
| Network | Istio ambient: STRICT mTLS and AuthorizationPolicy per dependency edge; egress gateway with ServiceEntry host allow-lists (ADR-0026). Default-deny NetworkPolicies as a second layer. Open internet egress only for model-gateway, connector and channel services. Two narrow, host-allow-listed exceptions: Keycloak → configured customer IdPs (SSO brokering) and billing-service → the chosen payment-gateway and fiscal-provider hosts (RFC-0015) |
| Encryption | TLS in transit; encryption at rest for databases, backups and Kafka |
| Audit | Immutable, hash-chained audit events for administrative, commercial and sensitive technical actions (audit-service) |
| Support access | Explicit, time-boxed, approved, audited and visible to the customer (RFC-0004) |
| LLM-specific | Tool outputs and knowledge treated as data (prompt-injection defenses), policy guardrails, egress via model-gateway only (RFC-0011, RFC-0019) |
| LGPD | Retention, export and deletion pipeline per service (RFC-0007); open legal items in OPEN_DECISIONS D-12, D-22 |
| Supply chain | SAST, dependency and container scanning, SBOM and image signing in CI (STD-012) |
| Threat modeling | Mandatory for IAM, billing, tool execution, connectors and model gateway (RFC-0010) |
