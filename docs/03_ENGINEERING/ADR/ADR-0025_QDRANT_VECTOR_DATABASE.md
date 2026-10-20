# ADR-0025 - Dedicated Vector Database: Qdrant
Status: PROPOSED
Date: 2026-10-19
Supersedes: ADR-0011

## Context
ADR-0011 stored embeddings in PostgreSQL with pgvector. The product owner decided (2026-10-19) to adopt a dedicated vector database for retrieval performance and scale.

## Decision
**Qdrant** (Apache 2.0, HA cluster per environment) stores embeddings for `knowledge-service` and `memory-service`.
- **Ownership**: each service owns its own collections, named per CONVENTIONS (`knowledge_service__chunks_v<N>`, `memory_service__entries_v<N>`, each behind an unversioned alias) and its own Qdrant API key restricted to them; no service queries another service's collections (AR-002).
- **Source of truth stays in PostgreSQL**: documents, chunks' text, metadata and permissions live in the service's PostgreSQL database; Qdrant holds vectors plus the minimal payload needed for filtering (`org_id`, `workspace_id`, `domain_id`, `knowledge_base_id`, `chunk_id`, `acl_tags`, `embedding_model`). A collection can always be rebuilt from PostgreSQL.
- **Tenant isolation**: payload index on `org_id` with Qdrant's tenant-optimized indexing (`is_tenant`); every query carries a mandatory filter built by the platform library from the validated token (never from the request body). Large tenants can be moved to a dedicated shard/collection.
- **Permission-aware retrieval**: filters (org, workspace, domain, knowledge base, ACL tags) are applied **inside** the vector search, before ranking. Hybrid retrieval combines Qdrant results with PostgreSQL full-text search and a re-ranking step in the service.
- Writes are idempotent (point ID = chunk ID). Index changes (new embedding model) create a new versioned collection, filled by a Temporal ingestion workflow, then swapped atomically via collection alias.

## Alternatives
- pgvector (ADR-0011): one technology, simpler; limits at very large scale.
- Weaviate / Milvus: comparable; Qdrant chosen for filtering performance with multitenancy, Java client and operational simplicity.

## Consequences
- Dual write (PostgreSQL + Qdrant) handled by the ingestion workflow with reconciliation; retrieval returns only chunks that exist and are permitted in PostgreSQL (final check).
- New stateful component to operate (snapshots, backups, capacity planning).

## Compliance / Verification
- Retrieval tests: no cross-tenant or cross-domain result under any filter combination (tenancy test kit extended to Qdrant).
- Rebuild drill: a collection is recreated from PostgreSQL in staging each quarter.

## Supersedes / Superseded by
Supersedes ADR-0011.
