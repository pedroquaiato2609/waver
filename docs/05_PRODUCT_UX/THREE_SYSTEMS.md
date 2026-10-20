# WAVER - Três experiências principais (v1.2)

Cada experiência tem seu próprio app Angular e seu próprio BFF (ADR-0009). A arquitetura de frontend está no RFC-0021, e as regras visuais e os ícones estão em `02_BRAND/BRAND_GUIDELINES.md`.

| Experiência | App / BFF | Público | Tema padrão |
|---|---|---|---|
| WAVER Control | `control` / `control-bff` | Equipe do Grupo WAVER | Escuro (Forest) |
| WAVER Partner | `partner` / `partner-bff` | Revendas | Claro |
| WAVER Workspace | `workspace` / `workspace-bff` | Clientes finais (inclui o Builder) | Claro, com áreas Forest em chat e execução |

## WAVER Control
Painel interno do Grupo WAVER: revendas, visão consolidada de clientes, marketplace privado, catálogo, preços-base, contratos com revendas, financeiro, usage e custos de IA, suporte L2/L3, relatórios, auditoria e governança.

## WAVER Partner
Portal das revendas: carteira de clientes, propostas, contratos, marketplace autorizado, entitlements e licenças, billing, inadimplência e suspensão, suporte L1 e renovações. A revenda só vê a própria carteira.

## WAVER Workspace
Sistema operacional do cliente final: chat, agentes, agenda, projetos, knowledge, integrações, automações, relatórios e configurações.

O cliente não acessa o marketplace privado, preços de atacado nem a margem da revenda. Isso é garantido por construção, porque o `workspace-bff` não tem rota para esses dados. Quando o cliente tenta usar um recurso não contratado, a interface mostra o contato da revenda.

## WAVER Builder (dentro do Workspace)
Criação de agentes com experiências separadas para Objective-driven e Flow-driven. As duas compartilham Policies, Knowledge, Tools, Model, Memory, Channels, Testing/Evaluation e Observability (RFC-0016).

O Builder só oferece Tools, Channels e Connectors para os quais existem Entitlements válidos (RFC-0013).
