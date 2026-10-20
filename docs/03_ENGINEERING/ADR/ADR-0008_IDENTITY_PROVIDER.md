# ADR-0008 - Identity Provider: Spring Authorization Server
Status: SUPERSEDED by ADR-0021 (Keycloak) on 2026-10-19
Date: 2026-10-19

## Context
TECH_STACK lists "IAM product" as not frozen and SECURITY_BASELINE requires OAuth2/OIDC. The constraint is to stay on the approved stack (Java + Spring Boot + PostgreSQL) without adding a third-party IAM product.

## Decision
`identity-access-service` embeds **Spring Authorization Server** (OAuth 2.1 / OIDC) backed by its own PostgreSQL database.
- Human users: Authorization Code + PKCE, performed by the BFFs (ADR-0009). Tokens are never stored in browser storage; the BFF keeps them server-side and gives the browser an HttpOnly, Secure, SameSite session cookie.
- Services: `client_credentials`, one client per service.
- User context across services: **OAuth 2.0 Token Exchange (RFC 8693)** to obtain down-scoped tokens on behalf of the caller.
- MFA (TOTP first, WebAuthn later) mandatory for WAVER and Reseller administrative roles.
- Enterprise SSO: federation to customer IdPs (OIDC/SAML via Spring Security) configured per Organization.
- Every service is a Spring Security **resource server** validating JWTs (cached JWKS, 5-minute access tokens, rotating signing keys).

## Alternatives
- Keycloak or a commercial IdP: mature, but a new technology outside the approved stack.
- Hand-written JWT issuance: rejected; security-critical code with no standard guarantees.

## Consequences
- WAVER operates the IdP (key rotation, availability). It is a tier-0 service with its own SLO.
- Claims model is defined in RFC-0004.

## Compliance / Verification
- Token validation tests in the service template.
- External penetration test of the authorization server before production.

## Supersedes / Superseded by
Closes "IAM product" in TECH_STACK.
