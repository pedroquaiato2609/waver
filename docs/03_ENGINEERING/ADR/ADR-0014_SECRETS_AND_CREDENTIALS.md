# ADR-0014 - Platform Secrets and Tenant Credentials
Status: PARTIALLY SUPERSEDED by ADR-0022 (Vault) on 2026-10-19; credential ownership and lease rules remain valid
Date: 2026-10-19

## Context
Two very different kinds of secret exist: platform secrets (DB passwords, signing keys) and tenant credentials (OAuth tokens and API keys that customers give to connectors). The baseline says "vault/secret manager" without choosing.

## Decision
- **Platform secrets**: Kubernetes Secrets with encryption at rest enabled, mounted as files, rotated by the pipeline. Once the cloud provider is chosen, its managed KMS encrypts the Kubernetes secret store.
- **Tenant credentials**: owned exclusively by `connector-service` (Credential aggregate).
  - Envelope encryption: AES-256-GCM with a per-credential data key; data keys wrapped by a per-environment key-encryption key (KEK).
  - Plaintext credentials never leave `connector-service`. Calls that need a credential are executed by connector-service on behalf of tool-runtime-service (RFC-0018).
  - **Exception — credential leases:** two services own their own provider egress and cannot route calls through connector-service: `model-gateway-service` (only egress to LLM providers, ADR-0017) and `channel-service` (messaging providers). connector-service exposes an internal lease endpoint (`POST /v1/credentials/{id}/leases`) with a fixed allow-list:

    | Service identity | Credential kind |
    |---|---|
    | model-gateway-service | `LLM_PROVIDER_KEY` (BYOK) |
    | channel-service | `CHANNEL_PROVIDER_TOKEN` (e.g., the customer's own WhatsApp/e-mail provider account) |

    A lease returns the plaintext secret with a TTL of at most 15 minutes. The caller keeps it in memory only (never persisted, logged or written to disk) and every lease is audited. No other service or credential kind may be leased; extending the list requires amending this ADR.
  - Credentials are write-only through public APIs: they can be replaced or revoked, never read back.
- KEK rotation re-wraps data keys without re-encrypting payloads.

## Alternatives
- HashiCorp Vault: strong product, but a new technology to operate.

## Consequences
- Simpler v1 operations; a cloud KMS can be plugged in later behind the same port.
- connector-service is a high-security service (stricter NetworkPolicy, optional dedicated node pool).

## Compliance / Verification
- Static check: no other service schema contains credential or secret columns.
- Audit event on every credential create, rotate, revoke and lease. Routine **use** in tool calls is audited as aggregated records (per credential, per minute: count, tools, outcome) to keep audit volume proportional; the per-call detail stays in ToolExecution records.

## Supersedes / Superseded by
Implements STD-014.
