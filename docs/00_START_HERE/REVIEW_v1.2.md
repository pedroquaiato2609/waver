# Revisão do projeto WAVER — v1.1 → v1.2

> **Nota (v1.3):** este relatório descreve a revisão v1.2. No mesmo dia, o responsável pelo produto decidiu adotar Redis, Keycloak, Vault, Temporal, schema registry/Avro/Debezium, Qdrant, Istio e Nx/NgRx/Angular Material; os ADRs 0008, 0011, 0013 e 0018 citados abaixo foram substituídos. Ver [CHANGELOG_v1.3.md](CHANGELOG_v1.3.md).

Data: 2026-10-19
Escopo: revisão completa do Enterprise Project Pack v1.1 (91 arquivos) e melhoria do pack, **mantendo a mesma stack tecnológica**.
Backup da v1.1: `Desktop/Waver/docs_backup_2026-10-19/` (intacto).

## 1. Resumo executivo
A v1.1 tinha uma **visão de produto e comercial forte**: B2B2B, marketplace privado, a cadeia Contrato → Entitlement e as regras arquiteturais. A parte de engenharia, porém, era quase toda modelo vazio:
- os 17 RFCs tinham o desenho técnico como "To be completed";
- os 15 padrões de engenharia tinham as regras detalhadas como "To be completed";
- as 15 regras arquiteturais (ARs) eram uma frase cada;
- a identidade visual tinha inconsistências sérias (nomes antigos, três logos diferentes, ícones com letras erradas).

Na v1.2:
- **Stack inalterada.** Angular/TypeScript, Java/Spring Boot, PostgreSQL, Kafka, Docker e Kubernetes. Nenhuma tecnologia de infraestrutura nova foi adicionada. As escolhas que estavam abertas foram fechadas *dentro* da stack (13 novos ADRs).
- **Engenharia completa.**
  - 21 RFCs com desenho real (4 novos), com cerca de 5.900 linhas de especificação;
  - 15 padrões com regras verificáveis;
  - 15 ARs com mecanismos de verificação;
  - catálogo de eventos, convenções, roadmap e runbooks.
- **Marca consertada.**
  - logo oficial definido e vetorizado;
  - ícones dos apps refeitos;
  - tokens de design completos com verificação de contraste;
  - ativos com nomes antigos tirados de uso.
- **Decisões de negócio consolidadas.** 36 decisões pendentes estão num só lugar, cada uma com recomendação, dono e prazo.

Tudo o que é novo está com status **PROPOSED**: é uma proposta completa que precisa da revisão e do aceite da equipe de arquitetura e da diretoria.

## 2. Problemas encontrados na v1.1
### Críticos
| # | Problema | Onde | Resolução na v1.2 |
|---|---|---|---|
| C-01 | Os 17 RFCs não tinham desenho técnico ("To be completed") | `03_ENGINEERING/RFC` | Todos escritos, mais 4 novos (RFC-0018 a 0021) |
| C-02 | Os 15 padrões não tinham regras | `03_ENGINEERING/STANDARDS` | Todos escritos, com regras MUST/SHOULD e tabela de enforcement |
| C-03 | Referências visuais com os nomes antigos **VERDAN** e **Nexora** (e Admin/Revenda/Sistema), apesar de o README afirmar que tinham sido removidas | `02_BRAND/visual_references` | Movidas para `02_BRAND/_deprecated/` |
| C-04 | Três logos diferentes em uso: facetas triangulares, folha em "V" e "W" de fita 3D | Logos × referências | Oficial: o símbolo de facetas dos arquivos `logos/`, agora também em SVG |
| C-05 | Ícones dos apps com as letras A, R e N, iniciais dos nomes antigos (Admin, Reseller, Nexora) | `02_BRAND/icons` | Refeitos com o símbolo oficial |
| C-06 | O Product Scope pede paleta laranja/vermelho/amarelo e "evitar superfícies escuras", contradizendo a marca verde | Scope §20 e §23 | Errata E-01: vale a marca verde |
| C-07 | Ninguém era dono da abstração de LLM (provedores, fallback, custo, BYOK) | Mapa de serviços | Novo `model-gateway-service` (ADR-0017, RFC-0019) |
| C-08 | Nada garantia que o cliente nunca acessasse dados de atacado ou margem, só "autorização por endpoint" | Arquitetura | Um BFF por público; o do cliente não tem rota para esses dados (ADR-0009) |

### Importantes
| # | Problema | Resolução |
|---|---|---|
| I-01 | Objective Engine e Flow Engine como serviços separados: um salto de rede a cada mensagem e estado distribuído | Módulos dentro do agent-runtime (ADR-0019) |
| I-02 | Sem dono para handoff humano, aprovações e suporte | Novos `handoff-service`, `approval-service` e `support-service` |
| I-03 | Isolamento de dados por tenant indefinido | Isolamento lógico + Row-Level Security do PostgreSQL (ADR-0010) |
| I-04 | Modelo de conectores indefinido (o que tornava novas integrações lentas) | Declarativo primeiro, SDK Java depois (ADR-0016, RFC-0018, com guia passo a passo) |
| I-05 | "Secret manager/vault" sem decisão; credenciais de clientes sem dono | Credenciais só no connector-service com criptografia de envelope (ADR-0014) |
| I-06 | Formato de evento, outbox e DLQ indefinidos | CloudEvents + JSON Schema + outbox/inbox (ADR-0012, RFC-0006, EVENT_CATALOG) |
| I-07 | Escolha de IAM aberta | Spring Authorization Server (ADR-0008) |
| I-08 | Ninguém era dono de uso/cobrança detalhados e de dunning | RFC-0020 |
| I-09 | 33 serviços sem plano de entrega | DELIVERY_ROADMAP em 6 fases por vertical slice |
| I-10 | Sem tokens semânticos, tema escuro, cores de estado nem checagem de contraste; Sage e Lime arriscavam ser usadas como texto ilegível | Tokens v1.2 gerados com verificação WCAG AA |
| I-11 | Logo existia só em PNG; não havia arquivo vetorial | SVGs reconstruídos (99,8% de fidelidade) + pendência D-27 para o designer |

### Menores
- Versões trocadas: arquivos v1.1 com capa "Versão 1.0".
- Erros de digitação: "Builder ≠@ Runtime", "eo catálogo", numeração 8–19 no Scope.
- A diretriz de marca dizia "WAVER é a marca corporativa. WAVER é o produto principal".
- O mapa de serviços do Master não tinha o provisioning.
- "Plan/Subscription" no Scope × "Contract" no Comercial.

Tudo isso está registrado em `01_PRODUCT_BASELINE/ERRATA_v1.2.md`.

## 3. Decisões técnicas tomadas (todas dentro da stack)
| ADR | Decisão |
|---|---|
| 0007 | Java 25 LTS, Spring Boot 4.x, Maven com um módulo por camada, um workspace Angular |
| 0008 | Spring Authorization Server (OAuth 2.1/OIDC, MFA, SSO federado, token exchange) |
| 0009 | Spring Cloud Gateway + um BFF Spring Boot por público; host `hooks.` para webhooks |
| 0010 | Isolamento lógico + Row-Level Security; coluna padrão de carteira da revenda |
| 0011 | pgvector no próprio PostgreSQL para knowledge e memória |
| 0012 | CloudEvents JSON + JSON Schema no repositório; outbox/inbox transacional |
| 0013 | Sem Redis na v1 (cache em memória e PostgreSQL para contadores e locks) |
| 0014 | Kubernetes Secrets + criptografia de envelope; credenciais só no connector-service; "lease" auditado para BYOK e canais |
| 0015 | Monorepo com fronteiras impostas pelo build |
| 0016 | Conectores declarativos primeiro, SDK Java para casos complexos |
| 0017 | model-gateway-service: única saída para LLMs, perfis de modelo, custo e BYOK |
| 0018 | Processos longos como máquinas de estado no PostgreSQL + Kafka, sem motor de workflow |
| 0019 | Engines de Objective e Flow como módulos do agent-runtime |

## 4. O que foi criado ou reescrito
| Área | Arquivos |
|---|---|
| Início | `REVIEW_v1.2.md` (este), `OPEN_DECISIONS.md` (novo), README e PROJECT_INDEX atualizados |
| Produto | `01_PRODUCT_BASELINE/ERRATA_v1.2.md` (novo) |
| Marca | BRAND_GUIDELINES v1.2; `logos/svg/*` (5 SVGs); ícones dos 3 apps em PNG/SVG; favicons; `palette/tokens.json`/`.css` v1.2 + gerador com verificação de contraste; `tools/generate_brand_assets.py`; `_deprecated/` |
| Engenharia | 13 ADRs novos + ADR_INDEX; 21 RFCs (4 novos) + RFC_INDEX; 15 ARs expandidos; 15 padrões; template de RFC |
| Arquitetura | MICROSERVICES_MAP v1.2, CONVENTIONS (novo), TECH_STACK v1.2, DEPENDENCY_RULES, DELIVERY_ROADMAP (novo) |
| API e eventos | EVENT_CATALOG (novo), resumos de API e Kafka atualizados |
| Segurança e DevOps | SECURITY_BASELINE, KUBERNETES_BASELINE, REPOSITORY_LAYOUT |
| Runbooks | Template com orientações + 2 exemplos (lag/DLQ do Kafka, falha de provisionamento) |

Os PDFs e o DOCX originais **não foram alterados**; as correções estão na errata.

## 5. Resposta à pergunta "é fácil adicionar uma integração?"
Agora sim, por desenho (RFC-0018 §"How to add a new integration"):
1. **Integração REST comum:** escrever um manifesto YAML sobre o OpenAPI do sistema, sem código e sem deploy.
2. O manifesto gera **Tools** automaticamente (RFC-0012).
3. A integração é publicada como **Listing** no marketplace, e a WAVER escolhe quais revendas podem vendê-la.
4. A revenda inclui a integração no contrato; o **Entitlement** libera a integração para o cliente e ela aparece no Builder.
5. Casos difíceis (SOAP, assinaturas, arquivos) usam o SDK Java. O cliente também pode criar um conector "Custom API" para o próprio sistema.

## 6. O que ainda falta (não dá para resolver só com documentação)
1. **Decisões de negócio**: 36 itens em `OPEN_DECISIONS.md`. Os que bloqueiam a Fase 1 têm prazo **F1**: titularidade do cliente, quem cobra e quem emite nota fiscal, política de inadimplência, provedor de nuvem etc.
2. **Aceite dos ADRs e RFCs PROPOSED** pela equipe de arquitetura.
3. **Arquivo-mestre do logo** feito por designer, confirmação da assinatura "Ideias em movimento" e da licença da fonte Inter (D-26 a D-28).
4. **Reemissão dos PDFs** de produto incorporando a errata.
5. **Questões abertas técnicas menores**: cada RFC tem a sua seção "Open Questions". Os conflitos entre documentos encontrados nesta revisão foram resolvidos e estão marcados como "resolved in the v1.2 review".

## 7. Como a consistência foi verificada
- Contraste de todas as combinações de tokens (claro e escuro) verificado por script; todas passam WCAG AA.
- SVG do logo comparado pixel a pixel com o PNG oficial: 99,8% de correspondência.
- Varredura de todo o pack procurando tecnologias fora da stack: só aparecem como alternativas rejeitadas.
- Varredura de referências a documentos inexistentes: nenhuma (só arquivos de código futuros, como `package.json`).
- Nomes de tópicos, tipos de evento, bancos de dados e métricas padronizados em todos os documentos (CONVENTIONS + EVENT_CATALOG).
- Layout do repositório idêntico entre ADR-0015 e REPOSITORY_LAYOUT.

**Não verificado:** não há código, então nada foi compilado nem executado. Os valores padrão de engenharia (timeouts, SLOs, RPO/RTO, partições) são propostas iniciais e precisam ser validados com carga real.
