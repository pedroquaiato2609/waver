# Registro de decisões em aberto

Status: VIVO — atualizar a cada decisão tomada
Última revisão: 2026-10-19

Este documento junta, num só lugar, as decisões **de negócio** que continuam em aberto. Elas vinham do Master §15, do Scope §24 e do Comercial §20. As decisões técnicas que estavam abertas foram fechadas pelos ADR-0007 a ADR-0019 (status PROPOSED, aguardando aceite).

A arquitetura foi desenhada para que **cada decisão abaixo vire uma política configurável**: decidir depois não exige refazer o sistema. Mesmo assim, cada item tem um prazo: ele precisa estar decidido antes da fase do roadmap (`04_ARCHITECTURE/DELIVERY_ROADMAP.md`) que depende dele.

Legenda de prazo: **F1** = antes da Fase 1 (núcleo comercial), **F2** = antes da Fase 2 (MVP de agentes), **F3+** = pode esperar.

## Padrões provisórios adotados para a Fase 1 (2026-10-19)
Para não travar o desenvolvimento, as recomendações abaixo foram adotadas como **padrão provisório** no código da Fase 1. Cada uma está implementada como política configurável e continua precisando da aprovação do dono indicado na tabela **antes de ir para produção**. Quando o dono decidir, registre a decisão como explicado no fim deste documento e ajuste a política, se for o caso.

| # | Padrão provisório no código | Validar com antes de produção |
|---|---|---|
| D-01 | O cliente pertence à revenda que o cadastrou (`CustomerOwnership`). Transferência entre revendas só com aprovação da WAVER. | Diretoria comercial + jurídico |
| D-02 | WAVER cobra a revenda; a revenda cobra o cliente. | Financeiro |
| D-03 | A revenda emite o documento fiscal ao cliente; a plataforma guarda só metadados fiscais. | Contabilidade/jurídico |
| D-04 | Preço de atacado + margem, com faixa mínima e máxima por SKU; desconto acima do limite exige aprovação. | Comercial |
| D-05 | Inadimplência do cliente: dia 1 em atraso, carência até o dia 15, restrição no dia 16, depois suspensão. Suspensão automática desligada (`dunning.autoSuspend = false`) até a aprovação. | Financeiro + jurídico |
| D-06 | Revenda inadimplente: primeiro bloqueia vendas novas; clientes só são afetados por decisão explícita da WAVER. | Diretoria |
| D-07 | Contrato só registrado na plataforma, com upload do documento assinado. | Jurídico |
| D-08 | Pagamento com baixa manual ou por API; gateway na Fase 4. | Financeiro |
| D-12 | Suspensão nunca apaga dados; após o encerramento, 90 dias para exportar e depois purge auditado. | Jurídico/DPO |
| D-13 | Entitlements por workspace, com limites; por usuário só para assentos. | Produto |
| D-22 | DPA padrão WAVER↔revenda e revenda↔cliente. | Jurídico/DPO |

## Comercial e financeiro
| # | Decisão | Recomendação (precisa de aprovação) | Dono sugerido | Prazo |
|---|---|---|---|---|
| D-01 | O cliente final pertence comercialmente à revenda ou à WAVER? Como funciona a transferência entre revendas? | O cliente pertence à revenda (`CustomerOwnership`). A transferência exige aprovação da WAVER, novo contrato e trilha de auditoria (RFC-0003/0017). | Diretoria comercial + jurídico | F1 |
| D-02 | Quem cobra quem? | WAVER cobra a revenda (atacado) e a revenda cobra o cliente. Cobrança direta da WAVER ao cliente fica como exceção configurável (RFC-0020). | Financeiro | F1 |
| D-03 | Quem emite o documento fiscal ao cliente final? | A revenda, coerente com D-02. A plataforma guarda só metadados fiscais; o emissor é um adapter plugável. | Contabilidade/jurídico | F1 |
| D-04 | Qual a autonomia da revenda sobre preço e desconto? | Modelo "Wholesale + Margem" com faixa mínima e máxima por SKU. Desconto acima do limite exige aprovação da WAVER (approval-service). | Comercial | F1 |
| D-05 | Inadimplência do cliente: grace period padrão, níveis de restrição e quem pode reverter | Política por contrato/plano. Padrão sugerido para discussão: PAST_DUE no dia 1, GRACE_PERIOD até o dia 15, RESTRICTED no dia 16, SUSPENDED a partir do dia 30. A revenda pode reverter dentro da própria carteira, com motivo. | Financeiro + comercial | F1 |
| D-06 | O que acontece quando a própria revenda fica inadimplente com a WAVER? | Primeiro restringir novas vendas e novos provisionamentos da revenda. Clientes finais só são afetados por decisão explícita da WAVER (Comercial §17). | Diretoria | F1 |
| D-07 | O contrato é assinado dentro da plataforma ou só registrado? | v1: apenas registro, com upload do documento assinado e aceite eletrônico simples. Assinatura digital integrada fica para depois (adapter). | Jurídico | F1 |
| D-08 | Integração com gateway de pagamento já na v1? | v1: baixa manual/por API (status de pagamento). Gateway na Fase 4. | Financeiro | F1 |
| D-09 | Unidade de cobrança | Híbrida: mensalidade por workspace + agentes/canais como capacidade + pacote de execuções (runs) com excedente. Tokens ficam como custo interno (COGS), não como unidade vendida. | Produto + financeiro | F2 |
| D-10 | Comissão, revenue share e publishers terceiros: quando ativar? | Arquitetura preparada e desligada. Só a WAVER publica na v1. Avaliar após 6 meses de operação. | Diretoria | F3+ |
| D-11 | Branding: o que a revenda pode personalizar? | Marca WAVER sempre visível. Revenda pode exibir logo e contatos de suporte próprios no Workspace ("vendido e suportado por"). Sem whitelabel. | Marketing | F2 |
| D-12 | Prazos de retenção após cancelamento e suspensão | Suspensão: nunca apaga dados. Após TERMINATED: 90 dias para export, depois purge auditado. Validar com o jurídico (LGPD). | Jurídico/DPO | F1 |
| D-29 | Período de faturamento e início da cobrança | Mês fiscal no fuso America/Sao_Paulo; a cobrança começa no **sucesso do provisionamento** (não na ativação do contrato), coerente com "o contrato não parece entregue antes de provisionar" (RFC-0020). | Financeiro | F1 |
| D-30 | Armazenamento de documentos (aceite de contrato, faturas, documentos de knowledge) | Object storage do provedor de nuvem (depende de D-24), acessado por uma porta própria em cada serviço dono. | Arquitetura | F1 |

## Produto
| # | Decisão | Recomendação (precisa de aprovação) | Dono sugerido | Prazo |
|---|---|---|---|---|
| D-13 | Granularidade de entitlements | Capability por **workspace**, com limites (EntitlementLimit) por organização, workspace ou domínio conforme o item. Por usuário só para seats (RFC-0013). | Produto | F1 |
| D-14 | Primeiros canais em produção e provedor de WhatsApp | Web Chat + API na Fase 2; WhatsApp na Fase 3 (provedor definido por cotação). Voz depois. | Produto | F2 |
| D-15 | Quais apps nativos são estratégicos? | Nenhum na v1. O primeiro candidato é o Inbox de atendimento, construído sobre o handoff-service. | Produto | F3+ |
| D-16 | Um agente pode migrar de estratégia (Objective ↔ Flow)? | Não. Cria-se um novo agente a partir de uma cópia (RFC-0016). | Produto | F2 |
| D-17 | Uma conversa pode passar entre agentes ou domínios? | Entre agentes do mesmo domínio, sim (transferência explícita). Entre domínios, só por handoff explícito, sem compartilhar memória automaticamente. | Produto | F2 |
| D-18 | O que a memória pode lembrar, por quanto tempo e em que escopo? | Memória de sessão sempre. Memória de longo prazo só com política habilitada pelo admin do workspace, com expiração padrão e exclusão sob pedido. | Produto + DPO | F2 |
| D-19 | Handoff humano: dono, filas, SLA | Filas por domínio; SLA configurável pelo cliente; a revenda não atende conversas do cliente final. | Produto | F3+ |
| D-20 | Critérios mínimos de qualidade para publicar uma versão de agente | Gate padrão: suíte de testes do agente 100% verde + nenhuma violação de política. O cliente pode endurecer o gate. | Produto | F2 |
| D-21 | Clientes podem usar as próprias chaves de LLM (BYOK)? | Sim, como item de catálogo (desconto no consumo). Arquitetura no ADR-0017. Sem fallback para as chaves da WAVER por padrão. | Produto + comercial | F3+ |
| D-31 | Quando uma ação de ferramenta exige aprovação humana? | Sempre para ações DESTRUCTIVE; para WRITE, só quando a entrada vem de conteúdo não confiável (RFC-0012). O cliente pode endurecer a regra. | Produto + segurança | F2 |
| D-32 | O uso no Playground conta no orçamento/cota do cliente? | Conta no custo (COGS) mas não na cota vendida, com limite diário próprio para evitar abuso. | Produto + financeiro | F2 |
| D-33 | Unidade do orçamento de IA visível ao cliente | Tokens/execuções (não moeda), para não expor custo de atacado (RFC-0019). | Produto | F2 |
| D-34 | Redação padrão de dados sensíveis enviados ao LLM | Números de cartão sempre; CPF/CNPJ e e-mail como opção do cliente. Validar com o DPO. | Produto + DPO | F2 |
| D-35 | Existe um campo livre de "instruções adicionais" no Builder? | Sim, em "Avançado", limitado em tamanho e sujeito às políticas — sem virar a caixa de system prompt (RFC-0016). | Produto | F2 |
| D-36 | Ambientes de agente são por Workspace ou por Domínio? | Por Workspace, com Deployments por Domínio (RFC-0016). | Produto | F2 |

## Compliance e infraestrutura
| # | Decisão | Recomendação (precisa de aprovação) | Dono sugerido | Prazo |
|---|---|---|---|---|
| D-22 | LGPD: base legal, consentimento, DPA com clientes e revendas | DPA padrão WAVER↔revenda e revenda↔cliente; registro de operações de tratamento por serviço (RFC-0007). | Jurídico/DPO | F1 |
| D-23 | Residência de dados / multi-região | v1 em região única no Brasil. Multi-região só com demanda enterprise (ADR futura). | Diretoria + arquitetura | F1 |
| D-24 | Provedor de nuvem | Escolher um provedor com Kubernetes, PostgreSQL e Kafka gerenciados e KMS. A decisão afeta custos, não o desenho. | Diretoria + arquitetura | F1 |
| D-25 | Backend de observabilidade e provedor de CI | Escolher junto com D-24. Os serviços já emitem em formatos abertos (RFC-0009). | Arquitetura | F1 |
| D-37 | Licença do Redis 8 (RSALv2/SSPL/AGPLv3) para uso interno | Uso como serviço interno sem modificação é compatível; se o jurídico preferir evitar, usar o Valkey (BSD), compatível na API (ADR-0020). | Jurídico | F1 |
| D-38 | Licença do HashiCorp Vault (BSL 1.1) | Uso interno como cofre de segredos é permitido pela BSL; alternativa compatível: OpenBao (MPL 2.0) (ADR-0022). | Jurídico | F1 |
| D-39 | Componentes de infraestrutura gerenciados ou no cluster (Redis, Kafka, Keycloak, Vault, Temporal, Qdrant) | Gerenciados onde o provedor de nuvem oferecer (Kafka, Redis, PostgreSQL); Keycloak, Temporal, Vault, Apicurio, Qdrant e Istio no cluster com operadores. Decidir junto com D-24. | Arquitetura | F1 |

## Marca
| # | Decisão | Recomendação (precisa de aprovação) | Dono sugerido | Prazo |
|---|---|---|---|---|
| D-26 | Assinatura oficial | "Ideias em movimento", a que está nos arquivos oficiais de logo. | Marketing | F1 |
| D-27 | Arquivo-mestre vetorial do logo e fonte do wordmark | Encomendar ao designer o master (AI/Figma) com o wordmark em curvas, a partir dos SVGs da v1.2. | Marketing | F1 |
| D-28 | Licença da fonte Inter | SIL OFL 1.1 (permite uso comercial). Confirmar com o jurídico. | Jurídico | F1 |

## Como registrar uma decisão
1. Atualize a linha: troque "Recomendação" pela decisão tomada, com data e responsável.
2. Se a decisão muda comportamento do sistema, abra ou atualize o RFC/ADR correspondente.
3. Quando todos os itens de uma fase estiverem decididos, a fase está liberada para começar.
