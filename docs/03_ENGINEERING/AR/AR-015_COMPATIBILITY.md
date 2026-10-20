# AR-015 - Compatibility
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro schemas with Apicurio Registry compatibility rules, Temporal workflow versioning).

## Requirement
Public contracts and schemas follow compatibility/versioning policy.

## Rationale
Independent deployment (AR-005) only works if a provider can ship without breaking consumers that have not changed. Customers integrating through the public API and connector authors need stable contracts (Scope §22: version compatibility and migrations).

## Rules
1. Major versions are visible in names: `/v{major}` paths, `.v<N>` topic and event type suffixes, `<EventName>.v<N>.avsc` and `v<N>.yaml` files (CONVENTIONS, ADR-0024). Minor/patch versions are tracked in the contract (`info.version` for OpenAPI; registry versions of the subject for Avro).
2. Inside a major version only backward-compatible changes are allowed: add optional fields, add endpoints, add event types. Removing or renaming fields, changing types or semantics, or making optional fields required is breaking. For Avro, every subject in Apicurio Registry uses `BACKWARD_TRANSITIVE` within a major: new fields need defaults, and fields are never removed or renamed (aliases do not make a rename compatible for older readers).
3. Consumers are tolerant readers: they ignore unknown fields and handle unknown enum values with a safe default (Avro enums declare a `default` symbol).
4. A breaking change creates a new major. The old major is supported for the window in RFC-0014 §9 (internal ≥ 90 days, public API ≥ 12 months, event majors dual-published ≥ 90 days).
5. Deprecation is announced with `Deprecation` and `Sunset` response headers, `deprecated: true` in the contract and an entry in the event catalog.
6. ToolVersion input/output schemas and connector manifests follow the same rules (ADR-0016); any change to a published ToolVersion creates a new ToolVersion.
7. BFFs remain compatible with the current and previous frontend release.
8. Database schemas follow expand/contract so version N works on schema N+1 (RFC-0014 §5).
9. Temporal workflow definitions are contracts with their own in-flight executions: a change must replay existing histories deterministically (`Workflow.getVersion` patches) or run under a new worker build ID while old executions drain (ADR-0023, RFC-0014 §9). Signal and query names and payloads of a workflow follow rules 2–3.

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| OpenAPI breaking changes | CI OpenAPI diff check against the latest released contract of the same major | STD-012, STD-003 |
| Event schema breaking changes | CI Avro compatibility check of `contracts/events/**/*.avsc` against the Apicurio Registry (`BACKWARD_TRANSITIVE`) before merge; registration by the pipeline at release, where the registry enforces the rule again | ADR-0024, STD-004, STD-012 |
| Consumers | Consumer contract tests generated from schemas (Avro specific records) | ADR-0024, STD-010 |
| Workflow changes | Temporal workflow replay tests against recorded histories for every workflow change | ADR-0023, STD-012 |
| Database | Previous-version compatibility test (N's suite on N+1 schema) | RFC-0014 Testing Strategy |
| Retirement | Runtime per-client usage metrics of deprecated majors | RFC-0009 |

## Exceptions
A breaking change inside a major version is only acceptable for a security fix. It requires approval by WAVER Architecture and Security, notification of all known consumers, and an entry in the Exceptions register in `AR_INDEX.md`. Shortening a compatibility window requires an ADR.
