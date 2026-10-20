# ADR-0018 - Long-running Execution without a Workflow Engine
Status: SUPERSEDED by ADR-0023 (Temporal) on 2026-10-19
Date: 2026-10-19

## Context
Agent runs wait for users, approvals, delays (Flow `Delay` node) or slow tools; provisioning and dunning are multi-step processes. "Runtime stack ... long-running jobs" is an open decision. A workflow engine product would be a new technology.

## Decision
Long-running processes are **persistent state machines in PostgreSQL**, owned by the service that owns the process, advanced by Kafka events and by a timer table:
- Each process instance row has `state`, `version` (optimistic locking) and `next_wakeup_at`.
- Each owner service polls its own due timers with `FOR UPDATE SKIP LOCKED`. `scheduler-service` is only for user-defined schedules (cron triggers), not for internal timers.
- Steps are idempotent (AR-006); every transition is persisted before side effects are published through the outbox.
- Cross-service sagas are **choreographed** by events. When a saga needs a coordinator, the owning service keeps its state (e.g., `provisioning-service` for ProvisioningJob).

## Alternatives
- Temporal / Camunda: powerful, but new technology and new operational burden.

## Consequences
- A small state-machine helper lives in the shared platform library.
- Process debugging relies on audit and trace data (RFC-0009).

## Compliance / Verification
- Every RFC that defines a process documents its transition table; transitions are tested exhaustively.

## Supersedes / Superseded by
Closes "Runtime: queue/event technology, state persistence and long-running execution model".
