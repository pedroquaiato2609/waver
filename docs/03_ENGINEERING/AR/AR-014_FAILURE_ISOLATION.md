# AR-014 - Failure Isolation
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Debezium outbox relay, Temporal activity retries, Istio with mesh retries disabled).

## Requirement
Cross-service failures use timeout/retry/circuit breaking/outbox where applicable.

## Rationale
In a distributed system every call can be slow or fail. Without timeouts, a slow dependency exhausts threads and cascades; without an outbox, a crash between a database write and a Kafka publish loses or invents events (ADR-0012, ADR-0024).

## Rules
1. Every outbound HTTP call has a connect timeout and a response timeout. Internal defaults: connect 1 s, response 2 s; external calls follow the Tool `ExecutionPolicy` (RFC-0012) or model profile (ADR-0017). Infinite timeouts are forbidden.
2. Retries only for idempotent operations or with an `Idempotency-Key` (AR-006): at most 3 attempts, exponential backoff with jitter, honouring `Retry-After`. Retries live in the application (Resilience4j) only; Istio mesh retries are disabled so retries are never multiplied by layers (ADR-0026).
3. A circuit breaker (Resilience4j) protects each downstream dependency; the fallback behaviour is documented in the service RFC.
4. Data-plane services use bulkheads (separate pools) per downstream dependency.
5. A state change that must be announced uses the transactional outbox, relayed to Kafka by the Debezium connector of the service's database; dual writes (database + direct Kafka send) are forbidden (ADR-0024).
6. Kafka consumers use retry topics with backoff and then `<topic>.dlq`; a poison message never blocks a partition indefinitely.
7. Deadlines shrink along the call chain; a callee's timeout is shorter than its caller's.
8. Readiness probes do not include other services or shared components, so one outage does not cascade into load-balancer removal (RFC-0015 §4).
9. Temporal activities declare start-to-close (and, for long ones, heartbeat) timeouts and a bounded retry policy with backoff; non-retryable error types are declared explicitly. Unbounded activity retries are forbidden for activities that call external systems (ADR-0023).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Timeouts everywhere | ArchUnit: HTTP clients are built only by the platform HTTP client factory (direct `RestClient`/`WebClient` construction forbidden); the factory refuses clients without timeouts | `waver-platform-starter`, `waver-platform-test` |
| Breakers configured | Startup validation fails if a declared client has no Resilience4j configuration | `waver-platform-starter`, RFC-0005 |
| Fault injection | WireMock tests for latency, 5xx and connection reset per client | `waver-platform-test`, STD-010 |
| No dual writes | ArchUnit: no `KafkaTemplate`/producer use for domain events in service code (only the platform retry/DLQ publisher); events are written only through the platform outbox writer | `waver-platform-test`, RFC-0006, ADR-0024 |
| No mesh retries | CI check on rendered mesh resources: retries disabled on every generated route | RFC-0015 §13, STD-012 |
| Activity policies | Startup validation fails if an activity has no timeout or an unbounded retry policy where forbidden | `waver-platform-starter`, ADR-0023 |
| Runtime | Metrics for breaker state, retries, DLQ depth, Debezium replication-slot lag with alerts | RFC-0009, RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ, RUNBOOK_DEBEZIUM_REPLICATION_SLOT |

## Exceptions
Longer timeouts for streaming endpoints (agent-runtime, model-gateway) are configured per client, not exceptions. Other deviations are registered in the Exceptions register in `AR_INDEX.md` with compensating controls, approved by WAVER Architecture.
