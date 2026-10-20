# Kafka Baseline (v1.3)

Kafka is the asynchronous event backbone, not a universal substitute for REST (ADR-0004).

The decisions this baseline listed as "required" are now made:

| Topic | Where |
|---|---|
| Topic ownership/naming, keys, ordering, partitions, consumer groups, retention | RFC-0006, CONVENTIONS |
| Envelope and schema format | ADR-0012 + ADR-0024 (CloudEvents binary mode in Kafka headers, Avro payloads registered in Apicurio Registry) |
| Delivery semantics, transactional outbox, idempotent consumers | ADR-0012, ADR-0024 (Debezium Outbox Event Router on Kafka Connect), RFC-0006, STD-004 |
| Retries, dead-letter handling, replay | RFC-0006, runbook `10_RUNBOOKS/RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ.md` |
| PII rules, observability, compatibility | RFC-0006, RFC-0009, STD-004 |
| Event catalog | `EVENT_CATALOG.md` |

Canonical chain: `ContractActivated → EntitlementGranted → ProvisioningSucceeded → InvoiceIssued / Notification / Audit` (see EVENT_CATALOG).
