# ADR-0011 - Vector Store: PostgreSQL with pgvector
Status: SUPERSEDED by ADR-0025 (Qdrant) on 2026-10-19
Date: 2026-10-19

## Context
Knowledge retrieval needs similarity search. TECH_STACK lists "vector database" as not frozen. Constraint: stay on the approved stack.

## Decision
`knowledge-service` and `memory-service` store embeddings in their own PostgreSQL databases with the **pgvector** extension (HNSW indexes), combined with PostgreSQL full-text search for hybrid retrieval.
- Permission-aware retrieval is a SQL filter (org/workspace/domain/knowledge base + RLS) applied **before** ranking, never after.
- Embedding model and dimension are recorded per index; re-embedding is a versioned background job.

## Alternatives
- Dedicated vector database: better at very large scale, but a new technology and a second place where permissions must be enforced.

## Consequences
- One technology for relational and vector data; RLS also protects embeddings.
- Revisit if one tenant exceeds tens of millions of chunks or retrieval p95 exceeds the SLO in RFC-0011.

## Compliance / Verification
- Retrieval tests assert no cross-domain chunk is ever returned.
- Load test with a representative corpus before GA.

## Supersedes / Superseded by
Closes "vector database" in TECH_STACK and the storage part of the "Knowledge" open decision.
