# Índice mestre (v1.3)

## Produto
WAVER é uma plataforma B2B2B para criar, integrar, executar e governar agentes de IA. A WAVER opera a plataforma; as revendas comercializam e administram seus clientes; os clientes usam apenas as capacidades contratadas.

## Planos e serviços (MICROSERVICES_MAP v1.2: 33 serviços de backend + 3 imagens de frontend)
- **Experience:** edge-gateway, control-bff, partner-bff, workspace-bff.
- **Commercial:** reseller, customer, catalog, marketplace, contract, entitlement, provisioning, billing, support.
- **Agent:** agent-builder, agent-runtime (com os módulos objective-engine e flow-engine), memory, evaluation, model-gateway, handoff.
- **Capability:** tool-registry, tool-runtime, connector, knowledge, channel.
- **Automation:** trigger, workflow, scheduler.
- **Platform:** tenant, identity-access, audit, notification, usage-metering, approval.

Os serviços são entregues por fases (DELIVERY_ROADMAP), não todos de uma vez.

## Agent Builder
Na criação, escolhe-se uma estratégia exclusiva: Objective-driven ou Flow-driven. Policies, Knowledge, Tools, Model, Memory, Channels, Testing/Evaluation e Observability são capacidades compartilhadas. O Builder define e versiona; o Runtime executa somente versões publicadas (RFC-0016, RFC-0011).

## Integrações
Os conectores são declarativos por padrão: um manifesto sobre OpenAPI, sem código e sem deploy. Para casos complexos há um SDK Java, e o cliente pode criar um conector "Custom API". Cada ação de conector vira uma Tool, é vendida como item do marketplace e é liberada por Entitlement (ADR-0016, RFC-0018).

## Comercial
Marketplace privado somente para a WAVER e as revendas. O cliente final não vê catálogo, preço de atacado nem margem; isso é garantido porque cada público tem o seu BFF (ADR-0009).

Cadeia: Listing → Quote → Contract → ContractItem → Entitlement → Provisioning → Workspace.

Inadimplência pode restringir ou suspender, mas nunca apagar dados (RFC-0013, RFC-0017, RFC-0020).

## Engenharia
- Microsserviços isolados, banco por serviço e isolamento por tenant com Row-Level Security.
- Contratos públicos versionados.
- Eventos CloudEvents em Kafka com payload Avro (schema registry) e outbox via Debezium.
- Kubernetes para orquestração, com Istio para mTLS e autorização entre serviços.
- Keycloak para identidade, Vault para segredos, Redis para cache e contadores, Temporal para processos longos e Qdrant para busca vetorial.
- Arquitetura interna Clean/Hexagonal, imposta por módulos Maven e ArchUnit.

Nenhum serviço acessa o banco ou a implementação interna de outro.

## Onde está cada coisa
| Preciso de... | Documento |
|---|---|
| O que mudou na v1.2 | `00_START_HERE/REVIEW_v1.2.md` |
| Tecnologias adotadas na v1.3 | `00_START_HERE/CHANGELOG_v1.3.md` |
| Decisões de negócio pendentes | `00_START_HERE/OPEN_DECISIONS.md` |
| Correções dos PDFs | `01_PRODUCT_BASELINE/ERRATA_v1.2.md` |
| Regras de marca, logo e cores | `02_BRAND/BRAND_GUIDELINES.md` |
| Decisões técnicas | `03_ENGINEERING/ADR/ADR_INDEX.md` |
| Desenho detalhado | `03_ENGINEERING/RFC/RFC_INDEX.md` |
| Regras não negociáveis e como são verificadas | `03_ENGINEERING/AR/AR_INDEX.md` |
| Padrões de código | `03_ENGINEERING/STANDARDS/` |
| Convenções (IDs, nomes, HTTP, eventos, banco) | `04_ARCHITECTURE/CONVENTIONS.md` |
| Plano de entrega | `04_ARCHITECTURE/DELIVERY_ROADMAP.md` |
| Lista de eventos | `07_API_EVENTS/EVENT_CATALOG.md` |
