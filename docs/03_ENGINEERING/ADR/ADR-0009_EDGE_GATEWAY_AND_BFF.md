# ADR-0009 - Edge Gateway and Backend-for-Frontend per Experience
Status: PROPOSED
Date: 2026-10-19

## Context
There are three audiences with very different visibility rules: WAVER (Control), Resellers (Partner) and Customers (Workspace). ADR-0006 forbids customers from seeing the marketplace, wholesale prices or reseller margins. If this relies only on per-endpoint authorization, one mistake is enough to leak commercial data.

## Decision
- **edge-gateway** (Spring Cloud Gateway): routing by host, coarse rate limiting, request size limits, correlation ID injection, header hygiene. TLS terminates at the Kubernetes ingress in front of it.
- Three **BFF** services (Spring Boot): `control-bff`, `partner-bff`, `workspace-bff`.
  - Each BFF is the only backend its Angular app talks to.
  - Each BFF only has clients for the services its audience may use. `workspace-bff` has **no client** for marketplace, catalog pricing, reseller or wholesale endpoints, so the leak is impossible by construction, not by configuration.
  - BFFs compose data for screens; they hold no business rules and own no business data (HTTP sessions live in Redis via Spring Session Data Redis — ADR-0020).
  - The Angular apps are served by small `frontend-<app>` images (a minimal Spring Boot static-resource server, no business code, no outbound calls). edge-gateway routes `/api/**` and the login paths to the BFF and everything else on the same host to `frontend-<app>`. App and API therefore share one origin (no CORS, cookie-only auth), and frontend releases stay independent from BFF releases (RFC-0014, RFC-0021). A CDN in front is optional once the cloud provider is chosen.
- The public API (programmatic customer access, API channel) is exposed by edge-gateway on a separate host with API keys / client credentials, routed to `workspace-bff` public routes.
- **Machine and anonymous ingress** (amendment 2026-10-19): traffic that has no user session goes through a dedicated `hooks.` host on edge-gateway, routed **directly** to the owning service and never to a BFF:
  - provider webhooks (WhatsApp/e-mail providers → `channel-service`; connector webhooks → `connector-service`; generic inbound webhooks → `trigger-service`). Each owning service verifies the provider signature.
  - Web Chat widget traffic (anonymous end users, public widget key plus origin allow-list) → `channel-service`.
  - Strict per-route rate limits, body size limits and no access to any commercial or administrative route.
  - OAuth **callbacks** of connector connections are user-session traffic: they go through `workspace-bff` (`app.` host), not through `hooks.`.

## Alternatives
- Single gateway with role filters: simpler, but visibility becomes configuration and is easy to break.
- GraphQL federation: new technology; not needed.

## Consequences
- Three more deployables, but small and stateless.
- Screen-specific aggregation does not leak into domain services.

## Compliance / Verification
- CI check: `workspace-bff` must not depend on marketplace, catalog-pricing or reseller API clients.
- Per-audience access tests in the E2E suite.

## Supersedes / Superseded by
Closes "API gateway implementation" in TECH_STACK.
