# ADR-0019 - Objective and Flow Engines as Modules of agent-runtime
Status: PROPOSED
Date: 2026-10-19

## Context
The v1.1 service map lists `objective-engine` and `flow-engine` as separate services next to `agent-runtime`. Every conversational turn would then need network hops and distributed state between runtime and engine, adding latency and failure modes to the hottest path, while the engines own no independent data (ObjectiveState and FlowState belong to the run).

## Decision
- `objective-engine` and `flow-engine` are **separate Maven modules behind one port** (`StrategyEngine`) inside the `agent-runtime` deployable. They remain separate engines (the product decision is preserved) and do not share code beyond the port.
- ObjectiveState and FlowState are owned by agent-runtime's database.
- Either engine can be extracted into its own service later without changing external contracts, because the runtime only talks to it through the port.

## Alternatives
- Separate deployables now: rejected for the latency and consistency reasons above.

## Consequences
- Two fewer deployables in the service map.

## Compliance / Verification
- ArchUnit/Maven rule: objective-engine and flow-engine modules must not depend on each other.

## Supersedes / Superseded by
Amends MICROSERVICES_MAP v1.1.
