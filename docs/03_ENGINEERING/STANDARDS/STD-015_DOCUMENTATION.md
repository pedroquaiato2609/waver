# STD-015 - Documentation Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro event schemas in `contracts/events` as the source of the event catalog; runbooks for shared infrastructure components).
Version: 1.3
Date: 2026-10-19
Related: ADR-0024, ADR-0015, STD-011, 11_TEMPLATES, 10_RUNBOOKS/RUNBOOK_TEMPLATE.md

## Purpose
Keep WAVER's architecture and operational knowledge versioned, reviewed and close to the code, with clear rules for which document type records which kind of decision.

## Doc pack structure
Documentation lives in the monorepo under `waver/docs/` (ADR-0015) and is reviewed like code (STD-011).

| Folder | Content | Language |
|---|---|---|
| `00_START_HERE` | Index, manifest, reading order | Portuguese |
| `01_PRODUCT_BASELINE` | Master product architecture, product scope | Portuguese |
| `02_BRAND` | Brand guidelines, tokens, assets | Portuguese |
| `03_ENGINEERING/ADR`, `/RFC`, `/AR`, `/STANDARDS` | Decisions, designs, architecture requirements, standards | English |
| `04_ARCHITECTURE` | Conventions, microservices map, tech stack, dependency rules | English |
| `05_PRODUCT_UX` | Product/UX descriptions of the three systems | Portuguese |
| `06_COMMERCIAL` | Commercial and reseller architecture | Portuguese |
| `07_API_EVENTS` | API guidelines, Kafka baseline, generated event catalog | English |
| `08_SECURITY` | Security baseline, threat models | English |
| `09_DEVOPS` | Short summaries pointing to RFC-0014/RFC-0015 | English |
| `10_RUNBOOKS` | Runbook template and runbooks | English |
| `11_TEMPLATES` | ADR and RFC templates | English |

## Rules

### Language
1. Engineering documents (ADR, RFC, AR, STD, architecture, API/events, security, DevOps, runbooks, code comments, commit messages) are written in English.
2. Product, brand and commercial documents are written in Portuguese (pt-BR). Engineering documents may quote Portuguese product terms but keep English entity names as in MICROSERVICES_MAP.

### Document types and when to use them
| Need | Document |
|---|---|
| One architecturally significant decision with alternatives (technology, pattern, boundary change) | ADR |
| Design of a capability, service group or cross-cutting concern, with APIs, events, failure modes and rollout | RFC |
| A non-negotiable architectural rule with verification | AR (new ARs need an ADR) |
| How engineers must work (coding, testing, CI, secrets…) | STD |
| How to operate a service or handle an alert | Runbook |
| Changing CONVENTIONS, adding stateful technology, deviating from an AR permanently | ADR (mandatory) |

3. An RFC that makes a significant technology or boundary decision MUST extract it into an ADR; an ADR MUST NOT carry detailed design.

### Numbering and naming
4. ADR: `ADR-NNNN_<UPPER_SNAKE_TITLE>.md` (4 digits); RFC: `RFC-NNNN_<UPPER_SNAKE_TITLE>.md`; AR: `AR-NNN_<TITLE>.md`; standards: `STD-NNN_<TITLE>.md`; runbooks: `RUNBOOK_<TOPIC>.md` (cross-cutting) or `10_RUNBOOKS/<service>/RUNBOOK_<TOPIC>.md`.
5. Numbers are assigned in merge order, never reused, including for REJECTED documents.

### Lifecycle and statuses
| Status | Meaning |
|---|---|
| PROPOSED | Merged for review; may guide work but can still change |
| ACCEPTED | Approved; binding |
| SUPERSEDED | Replaced; header names the successor (`Superseded by ADR-NNNN`) |
| REJECTED | Considered and declined; kept for history |

6. Every ADR/RFC/STD header contains `Status`, `Version`, `Date`; RFCs also `Owners` and `Reviewers` and use the exact headings of `11_TEMPLATES/RFC_TEMPLATE.md`; ADRs use `11_TEMPLATES/ADR_TEMPLATE.md`.
7. `DRAFT` is only allowed on unmerged branches; documents on `main` are PROPOSED or later.
8. PROPOSED → ACCEPTED requires approval by WAVER Architecture plus owners of affected services, recorded in the PR; the `Decision` section states the date and approvers.
9. ACCEPTED ADRs are immutable except for status and "Superseded by"; changes are made by a new ADR. RFCs may be revised with a version bump and a change log line.
10. When a document changes a baseline (CONVENTIONS, MICROSERVICES_MAP, TECH_STACK), the baseline is updated in the same PR.

### Docs as code
11. Docs change in the same PR as the code they describe (contract, behaviour, alert, runbook).
12. CI doc lint: Markdown lint, broken internal links, required headers, required RFC headings, references to non-existent ADR/RFC/AR IDs.
13. Diagrams are text-based (Mermaid or PlantUML source in Markdown) so they can be reviewed in diffs; binary diagrams need their source alongside.

### API and event catalog
14. OpenAPI documents in `contracts/openapi/<service>/v<N>.yaml` are the API reference; human docs link to them, never copy them.
15. Avro event schemas in `contracts/events/<context>/<EventName>.v<N>.avsc` (ADR-0024) carry catalog metadata as additional schema attributes (`x-waver-topic`, `x-waver-producer`, `x-waver-consumers`, `x-waver-pii`, `deprecated`), which Avro ignores for serialization. CI generates `07_API_EVENTS/EVENT_CATALOG.md` (an AsyncAPI-like view: topic, key, producer, consumers, schema link, registered version status in Apicurio Registry) from them; manual edits to the generated file are rejected.

### Runbooks
16. Every service and every shared infrastructure component (Keycloak, Vault, Redis, Temporal, Apicurio Registry, Kafka Connect + Debezium, Qdrant, Istio) has at least one runbook per alert that can page, using `10_RUNBOOKS/RUNBOOK_TEMPLATE.md`; every alert carries a runbook link.
17. Runbooks are reviewed after each incident that used them and at least every 6 months; stale runbooks are flagged by CI from their `Last reviewed` date.

## Verification
- CI doc lint (rules 4, 6, 12, 15) and service-manifest check (runbook link, AR-005).
- Quarterly review of PROPOSED documents older than 90 days: accept, revise or reject.

## Exceptions
Approved by WAVER Architecture in the PR; language exceptions (e.g., a customer-facing runbook extract in Portuguese) are allowed as additional files, never as replacements.
