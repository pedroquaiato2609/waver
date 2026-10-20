# ADR-0021 - Identity Provider: Keycloak
Status: PROPOSED
Date: 2026-10-19
Supersedes: ADR-0008

## Context
ADR-0008 embedded Spring Authorization Server in identity-access-service, which meant building login UX, MFA, federation, account recovery and admin tooling ourselves. The product owner decided (2026-10-19) to adopt Keycloak, a mature open-source IdP (Apache 2.0) that provides these out of the box.

## Decision
**Keycloak** (current major, Quarkus distribution) is the OAuth 2.1 / OIDC authorization server for all human and machine identities. It runs as its own deployment with its own PostgreSQL database.

Responsibilities split:
| Concern | Keycloak | identity-access-service (still a WAVER service) |
|---|---|---|
| Login, password policy, account recovery, e-mail verification | ✔ (themed with WAVER brand) | — |
| MFA (TOTP, WebAuthn/passkeys), step-up (ACR levels) | ✔ | Defines which roles require MFA/step-up |
| Enterprise SSO federation (OIDC/SAML per customer) | ✔ Identity Brokering, one IdP per Organization | Stores the configuration request, approves and pushes it via Admin API |
| Token issuance, JWKS, key rotation | ✔ | — |
| Token exchange between services (RFC 8693) | ✔ (standard token exchange) | — |
| Service accounts (client credentials) | ✔ one client per service | Requests creation through Admin API during provisioning |
| Organization model in IdP | ✔ Keycloak **Organizations** feature (one Keycloak organization per WAVER Organization) | Source of truth for WAVER Organization ↔ Keycloak org mapping |
| Memberships, roles, Domain-scoped grants, permissions (RBAC + ABAC) | Only coarse role keys in the token | ✔ Owner; fine-grained permissions evaluated in services (RFC-0004) |
| API keys for the public API, SupportAccessGrant | — | ✔ Owner. Each API key belongs to a ServiceAccount that has a Keycloak client; edge-gateway validates the key with identity-access, which returns a short-lived Keycloak access token for that ServiceAccount (client credentials, cached until expiry), so downstream services only ever see Keycloak tokens |

Realm design: a single realm `waver`; Keycloak organizations isolate members and federation per tenant; token claims (`org_id`, `org_type`, `ws_ids`, `roles`, `act`, `support_grant`) produced by protocol mappers (a small custom mapper SPI in Java reads claims from identity-access-service through a cached internal endpoint).

BFF login flow is unchanged in shape: Authorization Code + PKCE performed by the BFF; tokens stay server-side (sessions in Redis, ADR-0020).

## Alternatives
- Spring Authorization Server (ADR-0008): full control, much more to build and secure.
- Commercial IdP (Auth0, Entra, Okta): faster, but per-user cost at B2B2B scale and data residency constraints.

## Consequences
- Keycloak becomes a tier-0 component: HA (≥ 2 replicas, Infinispan clustering), backups of its database, upgrade runbook.
- Custom code is limited to the theme (WAVER login pages) and the claims mapper SPI.
- identity-access-service keeps the business model of access; it no longer issues tokens.

## Compliance / Verification
- Pen test of the login and federation flows before production.
- Configuration as code: realm and clients exported/imported via the Keycloak Admin API in the pipeline (no manual console changes in production).

## Supersedes / Superseded by
Supersedes ADR-0008.
