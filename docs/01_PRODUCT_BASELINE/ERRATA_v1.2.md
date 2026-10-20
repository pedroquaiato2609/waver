# Errata v1.2 dos documentos de produto

Status: VIGENTE a partir de 2026-10-19
Aplica-se a:
- `WAVER_Master_Product_Architecture_v1.1.pdf` (Master)
- `WAVER_Product_Scope_v1.1.pdf/.docx` (Scope)
- `06_COMMERCIAL/WAVER_Enterprise_Commercial_Reseller_Architecture_v1.1.pdf` (Comercial)

Os PDFs não foram alterados. Quando um PDF e esta errata divergirem, **vale esta errata**. Na próxima emissão dos PDFs (v1.2), estas correções devem ser incorporadas ao texto.

## Correções de conteúdo
| # | Documento / seção | Texto atual | Correção |
|---|---|---|---|
| E-01 | Scope §20 e §23 | Direção visual "branco/creme com laranja, vermelho e amarelo; evitar superfícies escuras" | **Substituída** pela identidade verde de `02_BRAND/BRAND_GUIDELINES.md` v1.2 (Forest, Mineral, Sage, Off White, Graphite, Lime). Superfícies Forest são permitidas conforme o produto (Control escuro; Workspace com áreas escuras). Continuam valendo: controles finos e compactos, hierarquia densa e progressive disclosure. |
| E-02 | Scope §26 | "Não define ainda tecnologias específicas..." | A stack foi definida no ADR-0001 (Angular/TypeScript, Java/Spring Boot, PostgreSQL, Kafka, Docker, Kubernetes) e detalhada nos ADR-0007 a ADR-0019. |
| E-03 | Master §16, passo 12 | "Só então selecionar stack final e iniciar implementação" | A stack já está fixada. O passo passa a ser: "Iniciar implementação por vertical slice conforme `04_ARCHITECTURE/DELIVERY_ROADMAP.md`". |
| E-04 | Master §15 e Scope §24 | Decisões técnicas listadas como abertas | Fechadas (como PROPOSED) pelos ADRs: isolamento de dados (ADR-0010), runtime/long-running (ADR-0018, substituído pelo ADR-0023 Temporal na v1.3), broker (Kafka, ADR-0001/0012/0024), vector store (ADR-0011, substituído pelo ADR-0025 Qdrant na v1.3), connector SDK (ADR-0016), model routing (ADR-0017). As decisões de negócio restantes estão em `00_START_HERE/OPEN_DECISIONS.md`. |
| E-05 | Master §9.1 | Lista do Commercial Plane sem `provisioning-service`; Agent Plane com Objective Engine e Flow Engine como serviços | Lista oficial em `04_ARCHITECTURE/MICROSERVICES_MAP.md` v1.2. Engines passam a ser módulos do `agent-runtime` (ADR-0019). Novos serviços: model-gateway, handoff, approval, support, BFFs e edge-gateway. |
| E-06 | Scope §18 e §21 | Entidades "Plan" e "Subscription" | O modelo comercial oficial é o do documento Comercial: um **Plan** é representado por Bundle/SolutionPack no catálogo; uma **Subscription** é um **Contract** ativo com seus ContractItems. Não existem entidades Plan/Subscription separadas. |
| E-07 | Scope §4.1 | "Organization: entidade comercial/tenant de maior nível. Relaciona plano, assinatura..." | Organization tem tipo PLATFORM (Grupo WAVER), RESELLER ou CUSTOMER. Os relacionamentos comerciais ficam em contract-service e customer-service, não na Organization (RFC-0003). |
| E-08 | Master, Comercial, Scope | "Customer Workspace" usado como nome de produto | Nome oficial do produto: **WAVER Workspace**. "Workspace" (sem prefixo) é a entidade técnica, o ambiente de trabalho do cliente. |
| E-09 | Master §4.4 e Scope §12 | Human handoff sem dono | Novo `handoff-service` (MICROSERVICES_MAP v1.2). O app nativo "Support/Inbox" (Scope §19) poderá ser construído sobre ele. |
| E-10 | 00_START_HERE/README v1.1 | "Referências antigas... foram removidas do pack" | Não tinham sido removidas: três referências com VERDAN/Nexora/Admin/Revenda/Sistema continuavam em `visual_references`. Foram movidas para `02_BRAND/_deprecated/` na v1.2. |

## Correções de forma
| # | Documento | Problema | Correção |
|---|---|---|---|
| F-01 | Master, Scope, Comercial | Arquivos nomeados v1.1, mas capa/rodapé dizem "Versão 1.0" / "Product Scope v1.0" | O conteúdo é a v1.1. Ajustar capas e rodapés na próxima emissão. |
| F-02 | Scope §3 | "Builder ≠@ Runtime" | "Builder ≠ Runtime". |
| F-03 | Scope §25 | Lista numerada de 8 a 19 | Numerar de 1 a 12. |
| F-04 | Master, página 1 | "a plataforma eo catálogo" | "a plataforma e o catálogo". |
| F-05 | Comercial | Setas da cadeia renderizadas como caracteres inválidos em alguns leitores (problema de fonte do PDF) | Regerar o PDF com fonte embutida. |
| F-06 | BRAND_GUIDELINES v1.0 | "WAVER é a marca corporativa. WAVER é o produto principal" (resto de substituição de nome) | Corrigido na v1.2: Grupo WAVER = marca corporativa; WAVER = produto. |
