# ADR-0016 - Connector Model: Declarative-first with Java SDK Escape Hatch
Status: PROPOSED
Date: 2026-10-19

## Context
"Connector SDK and custom connector deployment model" is an open decision (Scope §24). How a connector is built decides whether a new integration takes hours or weeks.

## Decision
Three connector kinds, one runtime contract (RFC-0018):
1. **Declarative connector** (default): a versioned `connector-manifest` (YAML) referencing an OpenAPI document, an auth scheme and an action mapping, executed by a generic HTTP engine inside `connector-service`. Most REST integrations need **no new code and no deploy**, only a manifest published through the marketplace pipeline.
2. **Coded connector**: a Java module implementing `waver-connector-sdk` interfaces (`ConnectorDefinition`, `ActionHandler`, `TriggerHandler`, `AuthStrategy`), packaged into `connector-service` or into a separate `connector-runner-<group>` deployment for heavy or high-risk connectors. Used for pagination quirks, request signing, SOAP, files, etc.
3. **Custom API connector**: a declarative connector created by a customer admin in Workspace for their own system, scoped to their organization and never listed in the marketplace.

Each connector action is exposed as one or more **Tools** in `tool-registry-service`.

## Alternatives
- Code-only connectors: every integration needs engineering work and a deploy.
- Arbitrary customer code (sandboxed scripts): high security risk; out of scope for v1.

## Consequences
- The generic HTTP engine is critical code: SSRF protection, egress allow-lists, timeouts, response size limits.
- A connector certification checklist is required before marketplace publication.

## Compliance / Verification
- Manifest JSON Schema validated in CI and at publish time.
- Contract tests per connector against recorded fixtures.

## Supersedes / Superseded by
Closes "Integrations: connector SDK and custom connector deployment model".
