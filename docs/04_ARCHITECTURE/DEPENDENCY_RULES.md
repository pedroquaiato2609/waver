# Dependency Rules (v1.2)

## Inside a service (Clean/Hexagonal)
Infrastructure → Adapters → Application → Domain.

| Layer | Maven module (ADR-0007) | May depend on | Must not depend on |
|---|---|---|---|
| Domain | `<service>-domain` | Java standard library only | Spring, JPA, Kafka, HTTP, Jackson, cloud or vendor SDKs, other layers |
| Application | `<service>-application` | Domain | Adapters, infrastructure frameworks (except annotations explicitly allowed in STD-001) |
| Adapters | `<service>-adapters` | Application, Domain, Spring, JPA, Kafka, HTTP clients | Other services' modules |
| Bootstrap / Infrastructure | `<service>-bootstrap` | Everything in the service | Other services' modules |

Domain MUST NOT depend on Spring, JPA, Kafka, HTTP, cloud SDKs or vendor SDKs. Application orchestrates use cases through ports. Adapters implement inbound and outbound contracts. Infrastructure wires concrete technology.

Enforcement: the Maven module graph, plus ArchUnit rules from `waver-platform-test` running in every build (AR-007).

## Between services
- A service may depend only on `platform/*` libraries and on clients generated from `contracts/*` (ADR-0015, AR-003).
- Synchronous dependencies must form a directed acyclic graph. Allowed edges and forbidden edges are listed in RFC-0002.
- Data-plane services must not call control-plane services synchronously on the hot path. They use local read models built from events (AR-013).

## Control Plane vs Data Plane
Control Plane and Data Plane are separated (plane type of each service in MICROSERVICES_MAP). High chat/agent traffic must not make contract, catalog or admin operations unavailable, and vice versa: separate deployments, separate scaling, separate database pools, and separate Kubernetes namespaces (RFC-0015).
