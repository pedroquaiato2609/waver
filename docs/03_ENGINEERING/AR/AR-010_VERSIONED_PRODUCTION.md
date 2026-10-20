# AR-010 - Versioned Production
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Vault policies, Keycloak realm configuration, Istio policies and registered Avro schemas as versioned production configuration).

## Requirement
Editable configuration never changes production without version/deployment.

## Rationale
"Editing the agent does not change production automatically" (Scope §16). Versioning makes production reproducible, testable before release, auditable and reversible. The same applies to WAVER's own infrastructure configuration (RFC-0014).

## Rules
1. Agents follow Draft → AgentVersion (immutable) → Deployment in an agent Environment; agent-runtime executes only AgentVersions referenced by an active Deployment (RFC-0016, RFC-0011).
2. The same pattern applies to Flows (FlowVersion), Tools (ToolVersion), Policies, connectors (ConnectorVersion), Workflows (WorkflowVersion), contracts (ContractVersion) and price books (activation).
3. Editing a draft never affects running executions; a run is pinned to the version it started with.
4. Rollback activates a previous version through a new Deployment record; published versions are never edited in place.
5. Every version and deployment records author, time and change summary, and emits an audit event (AR-009).
6. Infrastructure configuration, platform flags, Vault secret references, Vault policies and roles, Keycloak realm and client configuration, Istio policies and event schemas registered in Apicurio change only through Git and the pipeline (RFC-0014 §3, §6, §12; ADR-0021, ADR-0022, ADR-0024, ADR-0026); manual edits of `production` resources (including the Keycloak admin console, the Vault CLI and the registry UI) are forbidden outside audited break-glass.
7. Where release gates are configured, a version passes evaluation before production deployment (RFC-0016).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Runtime uses published versions only | Contract/integration tests: agent-runtime rejects draft references; ArchUnit: no client for builder draft endpoints | RFC-0011, RFC-0016 |
| Immutability | Database trigger or constraint rejects updates to published version content; test in each owning service | STD-005 |
| Draft isolation | E2E: edit a draft, assert production behaviour unchanged | STD-010 |
| Infrastructure | RBAC: humans read-only in `production`; Git-vs-cluster drift job, extended to Vault policies, Keycloak realm export, mesh resources and registered schema versions; registry, Keycloak Admin API and Vault writes only from the pipeline identity | RFC-0015 §7, §13, RFC-0014 |
| Audit | Audit assertions on publish/deploy/rollback | AR-009 |

## Exceptions
Standing exceptions accepted in the v1.2 review (PROPOSED):
- **Tool versions:** ToolBindings pin the major version; minor/patch ToolVersions (backward-compatible by definition) roll forward without a new AgentVersion. An agent can opt into exact pinning (RFC-0012).
- **Model profiles:** platform-managed model profiles (ADR-0017) may change the underlying model without a new AgentVersion, only after evaluation by WAVER and with advance notice to customers. An agent can opt into an exact profile version (RFC-0019).

Emergency changes use the expedited pipeline, which still versions and audits them. Break-glass changes MUST be reconciled into Git within 24 h. Any other deviation is registered in the Exceptions register in `AR_INDEX.md` and approved by WAVER Architecture.
