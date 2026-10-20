# WAVER - Enterprise Project Pack v1.3

Baseline consolidada do Grupo WAVER e do produto WAVER.
Revisão v1.2 de 2026-10-19: o que mudou e por quê está em [REVIEW_v1.2.md](REVIEW_v1.2.md). Na v1.3 (mesma data) foram adotados Redis, Keycloak, Vault, Temporal, schema registry/Avro/Debezium, Qdrant, Istio e Nx/NgRx/Angular Material: ver [CHANGELOG_v1.3.md](CHANGELOG_v1.3.md). A versão v1.1 original está preservada em `../../docs_backup_2026-10-19/`.

## Arquitetura de marca
- **Grupo WAVER**: empresa, marca institucional e operação corporativa.
- **WAVER**: nome do produto/plataforma principal.
- **WAVER Control**: painel interno do Grupo WAVER.
- **WAVER Partner**: portal das revendas/parceiros.
- **WAVER Workspace**: produto utilizado pelo cliente final (inclui o Builder).
- **WAVER Builder**: criação e versionamento de agentes.
- **WAVER Runtime**: execução dos agentes.
- **WAVER Marketplace**: marketplace privado visível somente ao Grupo WAVER e às revendas autorizadas.

A nomenclatura oficial é WAVER em toda a linha de produto. Os nomes de trabalho anteriores (VERDAN, Nexora, Admin/Revenda/Sistema) estão proibidos. As peças que ainda os exibiam foram movidas para `02_BRAND/_deprecated/` na v1.2.

## Stack aprovada
TypeScript + Angular; Java + Spring Boot; PostgreSQL; Apache Kafka; Docker; Kubernetes; microsserviços; DDD + Clean/Hexagonal Architecture; database-per-service; REST/OpenAPI para síncrono e Kafka/eventos versionados para assíncrono.

Sobre essa base, a v1.3 adicionou os componentes de plataforma Keycloak, Redis, HashiCorp Vault, Temporal, Apicurio Registry + Avro + Debezium, Qdrant e Istio, e no frontend Nx, NgRx e Angular Material (ver `04_ARCHITECTURE/TECH_STACK.md` e os ADR-0020 a ADR-0027).

## Ordem de leitura
1. `00_START_HERE`: este README, [REVIEW_v1.2.md](REVIEW_v1.2.md), [CHANGELOG_v1.3.md](CHANGELOG_v1.3.md), [OPEN_DECISIONS.md](OPEN_DECISIONS.md), [PROJECT_INDEX.md](PROJECT_INDEX.md)
2. `01_PRODUCT_BASELINE`: PDFs de produto e **ERRATA_v1.2.md** (vale sobre os PDFs)
3. `02_BRAND`: BRAND_GUIDELINES v1.2, logos (PNG + SVG), ícones, tokens
4. `04_ARCHITECTURE`: TECH_STACK, MICROSERVICES_MAP, CONVENTIONS, DEPENDENCY_RULES, DELIVERY_ROADMAP
5. `03_ENGINEERING`: ADR_INDEX → RFC_INDEX → AR_INDEX → STANDARDS
6. `05_PRODUCT_UX` e `06_COMMERCIAL`
7. `07_API_EVENTS` (EVENT_CATALOG), `08_SECURITY`, `09_DEVOPS`, `10_RUNBOOKS`

## Convenção de idioma
Documentos de produto, marca, comercial e de gestão em português. Documentos de engenharia (ADR, RFC, AR, STD, runbooks, catálogos) em inglês (STD-015).
