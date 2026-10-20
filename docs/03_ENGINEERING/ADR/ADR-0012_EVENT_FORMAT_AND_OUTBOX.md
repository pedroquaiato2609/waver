# ADR-0012 - Event Envelope, Schema Format and Transactional Outbox
Status: PARTIALLY SUPERSEDED by ADR-0024 on 2026-10-19 (schema format and outbox relay); CloudEvents envelope, inbox, retry/DLQ and keys remain valid
Date: 2026-10-19

## Context
KAFKA_BASELINE lists required decisions (envelope, schema format, outbox, DLQ...). A schema registry product would be a new technology.

## Decision
- **Envelope**: CloudEvents 1.0, structured JSON mode. Required extensions: `orgid`, `correlationid`, `causationid`, `actorid`, `actortype`. Conditional extensions: `workspaceid` and `domainid` (when the event is scoped to them), `supportgrantid` (when the action was performed under a support access grant). Also used: `aggregateversion` (aggregate version after the change, for ordering checks and idempotency) and `traceparent` (CloudEvents distributed-tracing extension, W3C format).
- **Schemas**: JSON Schema files versioned in the monorepo under `contracts/events/<context>/<EventName>.v<N>.json`, published as a Maven artifact and an npm package. CI checks compatibility (only backward-compatible changes inside a major version). No runtime schema registry in v1.
- **Producing**: transactional outbox table in each producing service (`outbox_event`), relayed to Kafka by a poller using `SELECT ... FOR UPDATE SKIP LOCKED` (shared platform library). No CDC tooling.
- **Consuming**: idempotent consumers with an inbox table (`processed_event(consumer_group, event_id)`), Spring Kafka retry topics with exponential backoff, and `<topic>.dlq` for poison messages.
- **Keys**: aggregate ID; ordering is guaranteed per aggregate only.
- Topic naming and the event catalog: `07_API_EVENTS/EVENT_CATALOG.md`.

## Alternatives
- Avro/Protobuf + schema registry: stronger tooling, but new technology.
- Debezium CDC outbox: new technology; polling is enough at v1 volumes.

## Consequences
- Small publish latency (poll interval, default 200 ms).
- Event contracts are code-reviewed like APIs.

## Compliance / Verification
- CI compatibility check on `contracts/events`.
- Consumer contract tests generated from the schemas.

## Supersedes / Superseded by
Implements AR-004, AR-006 and AR-015 for events.
