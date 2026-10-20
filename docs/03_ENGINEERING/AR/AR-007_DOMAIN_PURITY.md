# AR-007 - Domain Purity
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro, Temporal, Redis, Vault and Qdrant client libraries, all kept out of the domain).

## Requirement
Domain layer is independent from infrastructure/frameworks.

## Rationale
Business rules outlive frameworks, databases and vendors. A pure domain is fast to test, safe to refactor, and lets adapters change (e.g., an LLM provider or a managed database) without touching business code (Master §10, DEPENDENCY_RULES).

## Rules
1. The `<service>-domain` Maven module depends only on the JDK. Additional pure libraries require an approved entry in STD-013's domain allow-list (empty in v1).
2. Domain code MUST NOT import `org.springframework..`, `jakarta.persistence..`, `org.hibernate..`, `org.apache.kafka..`, `com.fasterxml.jackson..`, `io.micrometer..`, `org.springframework.ai..`, `org.apache.avro..`, `io.temporal..`, `io.qdrant..`, `org.redisson..`, Redis/Vault/Keycloak clients, HTTP clients, cloud or vendor SDKs.
3. Repository and gateway interfaces (ports) are declared in the domain or application layer and implemented in adapters.
4. Domain events are plain Java types; mapping to the CloudEvents headers and Avro records (ADR-0024) happens in adapters. Temporal workflow and activity classes are not domain code; they call application use cases (ADR-0023).
5. Time and identifiers come from ports (`Clock`, `IdGenerator` producing UUIDv7), never from static calls inside entities.
6. The application layer depends only on the domain and on ports; it MUST NOT depend on adapter technologies (JPA, Kafka, HTTP clients). Framework annotations allowed in the application layer are listed in STD-001.
7. Spring AI is allowed only inside model-gateway-service adapters (ADR-0017).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Module dependencies | Maven Enforcer `bannedDependencies` on every `-domain` module (all non-JDK dependencies banned except the allow-list) | Parent POM, ADR-0007, STD-013 |
| Imports | ArchUnit: classes in `..domain..` do not depend on the packages listed in rule 2 | `waver-platform-test` |
| Application layer | ArchUnit: `..application..` does not depend on `..adapters..` or adapter libraries | `waver-platform-test`, STD-001 |
| Fast domain tests | Domain module tests run without a Spring context (ArchUnit on test classes: no `@SpringBootTest` in `-domain`) | STD-010 |

## Exceptions
Register in the Exceptions register in `AR_INDEX.md`, approved by WAVER Architecture. Adding a pure library to the domain allow-list (e.g., a money or date library) is done through STD-013 and does not need a per-service exception.
