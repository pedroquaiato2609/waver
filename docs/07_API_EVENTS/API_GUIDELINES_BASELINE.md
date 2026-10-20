# API Guidelines Baseline (v1.3)

Short summary. The authoritative rules are in:
- `04_ARCHITECTURE/CONVENTIONS.md` (HTTP section)
- RFC-0008 API Architecture
- STD-003 REST API Design

Summary:
- Contract-first OpenAPI in `contracts/openapi/<service>/v<N>.yaml`. Clients for Java and TypeScript are generated from it.
- Three API layers: BFF APIs (one per Angular app, ADR-0009), internal service APIs, and the public customer API.
- Versioning in the path (`/v1/...`). Breaking changes require a new major version and a deprecation window with `Sunset` headers.
- Errors use RFC 9457 `application/problem+json` with a stable `code` and a `correlationId`.
- Cursor-based pagination, `Idempotency-Key` on side-effecting POSTs (keys stored in Redis, ADR-0020), `ETag`/`If-Match` for updates.
- Tenant context is taken only from the validated token, never from the payload (AR-008).
- Service-to-service auth is mandatory (Keycloak client credentials + token exchange, ADR-0021) and Istio mTLS with per-service AuthorizationPolicy (ADR-0026).
- APIs never expose persistence entities directly, and the customer audience never receives wholesale, margin or marketplace data (ADR-0006, ADR-0009).
