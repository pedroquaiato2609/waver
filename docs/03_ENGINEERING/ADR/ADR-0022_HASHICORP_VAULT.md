# ADR-0022 - Secrets Management: HashiCorp Vault
Status: PROPOSED
Date: 2026-10-19
Supersedes: ADR-0014 (mechanism); ADR-0014's ownership rules are kept

## Context
ADR-0014 used Kubernetes Secrets plus application-level envelope encryption. The product owner decided (2026-10-19) to adopt HashiCorp Vault for centralized secrets, encryption as a service and dynamic credentials.

## Decision
**HashiCorp Vault** (HA, integrated Raft storage, auto-unseal with the cloud KMS once chosen) is the secrets and encryption authority.

| Need | Vault engine / feature |
|---|---|
| Service authentication to Vault | Kubernetes auth method (one Vault role per service account) |
| Delivery of platform secrets to pods | Vault Secrets Operator (synced to short-lived K8s Secrets) or Vault Agent injector; no secrets in Git or Helm values |
| PostgreSQL credentials | Database secrets engine: **dynamic, short-lived** credentials per service (TTL 1 h, renewed) |
| Tenant credentials (connector OAuth tokens, API keys, BYOK LLM keys, channel provider tokens) | **Transit** engine with derived keys (context = orgId, non-convergent: equal plaintexts never produce equal ciphertexts): connector-service stores only ciphertext. One key per leasable type so Vault policy can scope each lessee: `tenant-credentials` (general, connector-service only), `tenant-credentials-llm`, `tenant-credentials-channel`, `tenant-credentials-payment`. Rotation via Transit rewrap. Per-organization crypto-shredding on purge is an open question (RFC-0018) |
| Signing keys for outbound webhooks and internal HMACs | Transit (sign/verify; key `outbound-webhooks`, Ed25519, public keys published by connector-service) — private keys never leave Vault |
| Keycloak, Kafka, Redis, Qdrant, Temporal, registry credentials | KV v2 + dynamic secrets where the engine exists |
| Certificates | Istio handles workload mTLS (ADR-0026); Vault PKI only for external-facing internal CAs if needed |

Ownership rules from ADR-0014 are unchanged:
- connector-service is the only owner of tenant credentials; plaintext never leaves it, except the audited **leases**, implemented as Transit decrypt permissions scoped by Vault policy to these service identities and credential kinds:

  | Service | Credential kind |
  |---|---|
  | model-gateway-service | `LLM_PROVIDER_KEY` (BYOK) — decrypt-only on `tenant-credentials-llm` |
  | channel-service | `CHANNEL_PROVIDER_TOKEN` — decrypt-only on `tenant-credentials-channel` |
  | billing-service | `PAYMENT_GATEWAY_CREDENTIAL` — decrypt-only on `tenant-credentials-payment` (only if resellers bring their own payment-gateway account, D-02/D-08; WAVER's own gateway keys are platform secrets in Vault KV) |
- Credentials are write-only through public APIs.

## Alternatives
- Keep ADR-0014 (K8s Secrets + envelope): fewer moving parts, no dynamic credentials.
- Cloud secret manager only: depends on the still-open cloud choice.
- OpenBao (MPL 2.0 fork of Vault): API-compatible; the fallback if Vault's license is not acceptable.

## Consequences
- Vault is tier-0: if it is unavailable, new pods cannot start and credential decryption fails closed. Mitigations: HA cluster, short local cache of decrypted *data keys is not used* (Transit only), runbook for unseal/recovery.
- Licensing: Vault is under the Business Source License 1.1. Internal use as WAVER's secret manager is permitted; legal must confirm (OPEN_DECISIONS D-38). OpenBao is the fallback.
- Audit device enabled; Vault audit logs shipped to the observability backend and correlated with audit-service events.

## Compliance / Verification
- CI check: no secret material in Git, Helm values or images (secret scanning, STD-014).
- Vault policies as code, reviewed per service.

## Supersedes / Superseded by
Supersedes the mechanism of ADR-0014 (ownership and lease rules remain).
