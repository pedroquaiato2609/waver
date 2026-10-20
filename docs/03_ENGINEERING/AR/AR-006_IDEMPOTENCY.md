# AR-006 - Idempotency
Status: ACCEPTED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal workflows, Debezium outbox relay, Redis idempotency keys).

## Requirement
Events and commands with side effects must be idempotent.

## Rationale
Kafka delivery with the outbox pattern is at-least-once (the Debezium relay may republish after a restart), HTTP clients retry, and Temporal retries activities and replays workflows after crashes (ADR-0024, ADR-0023). Without idempotency, retries create duplicate invoices, duplicate provisioning, repeated tool calls against customer systems or double suspensions (Master §11).

## Rules
1. Every POST that creates a resource or triggers a side effect accepts an `Idempotency-Key` (UUID). The server stores key + request hash + response for 24 h (in Redis under `waver:idem:<service>:<key>` with TTL, and in PostgreSQL when the response must be kept for audit, ADR-0020) and replays the stored response for the same key; the same key with a different request hash is rejected with a problem response (CONVENTIONS, RFC-0008). If Redis is unavailable, the endpoint falls back to the PostgreSQL store or rejects with `DEPENDENCY_UNAVAILABLE`; it never executes without the check.
2. Every Kafka consumer records `processed_event(consumer_group, event_id)` in the same database transaction as its state change and skips already-processed IDs (ADR-0024 keeps the inbox rules of ADR-0012).
3. Consumers tolerate duplicates and reordering across aggregates; ordering is guaranteed only per aggregate key.
4. Long-running processes are Temporal workflows (ADR-0023): workflow code is deterministic and has no side effects; every activity is idempotent, using a key derived from the workflow ID and the step (or the business ID it acts on), because Temporal may run an activity more than once. Activities that announce state changes write to the outbox in the same transaction as the change. Workflow IDs are derived from the business ID (e.g., `provisioning-<jobId>`) so a duplicate start is rejected by Temporal.
5. Calls to external systems (tool executions, payments, channel deliveries) carry a deterministic key derived from the originating ID (e.g., `ToolExecution.id`). When the external system cannot deduplicate, the execution records an `UNKNOWN_OUTCOME` state instead of retrying blindly (RFC-0012).
6. Billing, provisioning, tool execution, suspension and reactivation are always in scope.
7. Backfills and maintenance jobs are idempotent and resumable (RFC-0014 §5).
8. PUT and DELETE are idempotent by design; partial updates use `If-Match` (CONVENTIONS).

## Verification
| Check | Mechanism | Implemented by |
|---|---|---|
| Inbox on every consumer | ArchUnit: every `@KafkaListener` method is routed through the platform inbox (`@IdempotentConsumer`) | `waver-platform-starter`, `waver-platform-test`, RFC-0006 |
| Duplicate delivery | Test kit `assertDuplicateDeliveryIsNoOp(event)` required for each consumer; CI checks one test per listener | `waver-platform-test`, STD-010 |
| Idempotent POST | Test kit `assertIdempotentPost(...)`; OpenAPI lint requires the `Idempotency-Key` header on side-effect POSTs | STD-003, STD-012 |
| Workflows | Temporal workflow replay tests in CI for every workflow change; activity tests that execute each activity twice and assert a single effect (Temporal test framework) | ADR-0023, STD-010, STD-012 |
| Workflow determinism | ArchUnit: workflow classes call infrastructure only through activities | `waver-platform-test`, ADR-0023 |
| Runtime | Metric of duplicates skipped per consumer; DLQ alerts | RFC-0009, RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ |

## Exceptions
Read-only operations need no exception. A side-effecting operation that cannot be idempotent (e.g., an external API without deduplication) MUST be documented in the owning RFC with its compensating control (unknown-outcome state, human approval, reconciliation job) and registered in the Exceptions register in `AR_INDEX.md`, approved by WAVER Architecture.
