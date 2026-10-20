# Architecture Decision Records

| ADR | Title | Status | Closes / implements |
|---|---|---|---|
| ADR-0001 | Core Technology Stack | ACCEPTED | Angular/TS, Java/Spring Boot, PostgreSQL, Kafka, Docker, Kubernetes |
| ADR-0002 | Distributed Microservices | ACCEPTED | Bounded-context services |
| ADR-0003 | Database per Service | ACCEPTED | AR-002 |
| ADR-0004 | Synchronous and Asynchronous Communication | ACCEPTED | REST/OpenAPI + Kafka |
| ADR-0005 | Agent Strategy Model | ACCEPTED | OBJECTIVE_DRIVEN / FLOW_DRIVEN |
| ADR-0006 | Private Marketplace Visibility | ACCEPTED | Marketplace only for WAVER and resellers |
| ADR-0007 | Runtime Versions, Build Tooling and Workspace Layout | PROPOSED (frontend parts superseded by ADR-0027) | Java 25, Spring Boot 4.x, Maven modules |
| ADR-0008 | Identity Provider: Spring Authorization Server | SUPERSEDED by ADR-0021 | — |
| ADR-0009 | Edge Gateway and BFF per Experience | PROPOSED | Customer can't reach commercial data by construction; `hooks.` ingress |
| ADR-0010 | Tenant Data Isolation Strategy | PROPOSED | Logical isolation + PostgreSQL RLS |
| ADR-0011 | Vector Store: PostgreSQL with pgvector | SUPERSEDED by ADR-0025 | — |
| ADR-0012 | Event Envelope, Schema Format and Transactional Outbox | PARTIALLY SUPERSEDED by ADR-0024 | CloudEvents envelope, inbox, retry/DLQ remain |
| ADR-0013 | No Additional Datastores in v1 | SUPERSEDED by ADR-0020 | — |
| ADR-0014 | Platform Secrets and Tenant Credentials | PARTIALLY SUPERSEDED by ADR-0022 | Credential ownership and lease rules remain |
| ADR-0015 | Monorepo with Enforced Boundaries | PROPOSED | Repository layout |
| ADR-0016 | Connector Model: Declarative-first with Java SDK | PROPOSED | Connector SDK / custom connectors |
| ADR-0017 | Model Gateway for LLM Provider Abstraction | PROPOSED | Model routing, fallback, BYOK, AI cost |
| ADR-0018 | Long-running Execution without a Workflow Engine | SUPERSEDED by ADR-0023 | — |
| ADR-0019 | Objective and Flow Engines as Modules of agent-runtime | PROPOSED | Amends service map |
| ADR-0020 | Redis for Caching, Rate Limiting, Sessions and Hot Counters | PROPOSED | Supersedes ADR-0013 |
| ADR-0021 | Identity Provider: Keycloak | PROPOSED | Supersedes ADR-0008 |
| ADR-0022 | Secrets Management: HashiCorp Vault | PROPOSED | Supersedes ADR-0014 mechanism |
| ADR-0023 | Durable Execution: Temporal | PROPOSED | Supersedes ADR-0018 |
| ADR-0024 | Schema Registry, Avro and Debezium Outbox | PROPOSED | Supersedes ADR-0012 format and relay |
| ADR-0025 | Dedicated Vector Database: Qdrant | PROPOSED | Supersedes ADR-0011 |
| ADR-0026 | Service Mesh: Istio (Ambient Mode) | PROPOSED | mTLS, service authorization, egress control |
| ADR-0027 | Frontend Tooling: Nx, NgRx and Angular Material | PROPOSED | Supersedes ADR-0007 frontend parts |

PROPOSED ADRs become ACCEPTED after architecture review. Lifecycle and numbering rules: STD-015. ADR-0020 to ADR-0027 record the product owner's decision of 2026-10-19 to adopt these technologies.
