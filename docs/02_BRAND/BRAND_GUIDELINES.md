# WAVER Brand Guidelines - v1.2

Status: PROPOSTA (revisão de 2026-10-19 sobre a v1.0)
Mudanças em relação à v1.0: ver "Histórico" no fim do documento.

## 1. Arquitetura de marca
| Nome | O que é | Onde aparece |
|---|---|---|
| **Grupo WAVER** | Empresa, marca institucional e operação corporativa | Site institucional, contratos, comunicação corporativa |
| **WAVER** | Produto/plataforma principal | Login, materiais comerciais, rodapé "powered by" |
| **WAVER Control** | Painel interno do Grupo WAVER | App interno |
| **WAVER Partner** | Portal das revendas | App das revendas |
| **WAVER Workspace** | Produto usado pelo cliente final (inclui o Builder) | App do cliente |
| **WAVER Builder / Runtime / Marketplace** | Módulos da plataforma | Nomes de módulo dentro dos apps; não têm ícone próprio |

Regras:
- Sempre "WAVER" em maiúsculas, sem tradução e sem abreviação.
- Nome de produto = "WAVER" + nome do produto com inicial maiúscula ("WAVER Partner", nunca "Waver partner" ou "WAVER PARTNER" em texto corrido).
- **Nomes de trabalho antigos estão proibidos** em qualquer peça: VERDAN, Nexora, "WAVER Admin", "WAVER Revenda", "WAVER Sistema", "Reseller" como nome de produto. As peças que ainda os mostravam foram movidas para `_deprecated/`.
- Revendas comercializam a plataforma **sem whitelabel** por padrão: a marca WAVER fica visível (Product Scope §18). Níveis de co-branding são uma decisão em aberto (`00_START_HERE/OPEN_DECISIONS.md`).

## 2. Logo
### 2.1 Versão oficial
O logo oficial é o **símbolo de facetas** — três planos angulares que formam um "W" em movimento — com o wordmark `W A V E R` espaçado e a assinatura **"IDEIAS EM MOVIMENTO"**.

| Arquivo | Uso |
|---|---|
| `logos/svg/waver_lockup_light_bg.svg` | Logo completo em fundos claros (**preferencial**) |
| `logos/svg/waver_lockup_dark_bg.svg` | Logo completo em fundos Forest |
| `logos/svg/waver_symbol_light_bg.svg` | Símbolo em fundos claros |
| `logos/svg/waver_symbol_dark_bg.svg` | Símbolo em fundos escuros |
| `logos/svg/waver_symbol_mono.svg` | Uma cor (`currentColor`): carimbos, gravações, documentos P&B |
| `logos/*.png` | Versões raster originais da v1.1 (mantidas) |

Os SVGs foram reconstruídos a partir dos PNGs oficiais (correspondência de 99,8% dos pixels) por `tools/generate_brand_assets.py`.

> **Pendência:** o pack não tem o arquivo-mestre vetorial do designer (AI/Figma). O wordmark nos SVGs usa a fonte Inter como texto. Os PNGs originais parecem ter sido renderizados com uma fonte genérica, diferente da tipografia da marca. O designer deve gerar o master com o wordmark convertido em curvas e confirmar a fonte do wordmark.

### 2.2 Cores do logo
- Fundo claro: facetas externas Forest 900, faceta central Mineral 600, wordmark Forest 900.
- Fundo escuro: facetas externas Off White, faceta central Sage 300, wordmark Off White.
- A faceta central é sempre a mais clara (claro) ou a de menor contraste (escuro): ela cria a sensação de profundidade e não pode ser invertida.

### 2.3 Área de proteção e tamanho mínimo
- Área de proteção: no mínimo a **altura da faceta central** livre em todos os lados.
- Tamanho mínimo: símbolo com 24 px (digital) / 8 mm (impresso) de largura; logo completo com 120 px / 35 mm. Abaixo disso, use só o símbolo.
- A assinatura "IDEIAS EM MOVIMENTO" é omitida quando o logo completo tiver menos de 200 px de largura.

### 2.4 Não fazer
- Não usar os logos alternativos que aparecem nas referências antigas (folha em "V", "W" de fita 3D).
- Não recolorir com Lime, gradientes, sombras ou contornos.
- Não distorcer, girar ou reordenar as facetas.
- Não usar outras assinaturas ("Pessoas • Soluções • Resultados", "Pessoas • Processos • Crescimento"). A assinatura oficial é "Ideias em movimento" — **confirmar com a diretoria**, ver OPEN_DECISIONS.

## 3. Ícones dos apps e favicon
Os três apps usam o **mesmo símbolo**; a diferença é a cor de fundo, coerente com o tema de cada app (seção 6).

| App | Arquivo | Fundo | Facetas |
|---|---|---|---|
| WAVER Control | `icons/waver_control_app_icon.png` / `.svg` | Forest 900 | Off White + Sage |
| WAVER Partner | `icons/waver_partner_app_icon.png` / `.svg` | Off White (borda sutil) | Forest + Mineral |
| WAVER Workspace | `icons/waver_workspace_app_icon.png` / `.svg` | Mineral 600 | Off White + Lime |

- Favicons: `icons/waver_favicon_{16,32,48,180}.png` (Forest + símbolo claro).
- Cantos arredondados: 22% do lado. O símbolo ocupa 58% da largura.
- Os ícones antigos com letras (A, R, N) ficaram fora de uso. As letras vinham dos nomes de trabalho antigos (Admin, Reseller, Nexora). Estão em `_deprecated/icons/`.

## 4. Cores
### 4.1 Paleta de marca (inalterada)
| Token | Hex | Papel |
|---|---|---|
| Forest 900 | `#0F3D2E` | Marca, navegação, superfícies escuras |
| Mineral 600 | `#2E7D6B` | Ação principal, links, seleção |
| Sage 300 | `#A7C4B0` | Superfícies secundárias, bordas decorativas, texto secundário **somente em fundo escuro** |
| Off White | `#F7F6F2` | Fundo claro principal |
| Graphite | `#2D2D2D` | Texto e contraste |
| Lime Accent | `#C7E36B` | Destaque pontual; nunca dominar a UI |

### 4.2 Regras de contraste (WCAG 2.2 AA)
Todas as combinações dos tokens semânticos são verificadas por `palette/generate_tokens.py` (relatório em `tokens.json → contrastReport`).
- Graphite sobre Off White: 12,7:1 — texto padrão.
- Mineral 600 sobre Off White: 4,56:1 — está no limite. Use Mineral 700 (`#245F51`, 6,9:1) para links e textos pequenos; Mineral 600 fica para botões e áreas de ação.
- **Sage 300 sobre fundo claro: 1,7:1 — nunca usar para texto ou para borda de campo.** Para bordas de controles, use `border.control` (Sage 500 `#74877C`, ≥ 3:1).
- **Lime nunca é cor de texto em fundo claro.** Lime sobre Forest (8,5:1) é permitido para indicadores e destaques.

### 4.3 Cores de estado (novas na v1.2)
Tons sóbrios, alinhados à direção "calma e institucional" (sem neon):

| Estado | Claro | Escuro | Uso |
|---|---|---|---|
| Sucesso | `#2F7A4F` | `#6CC08E` | Ativo, pago, provisionado |
| Atenção | `#9A6212` | `#E0B04F` | Vencendo, grace period, cota próxima |
| Perigo | `#B3412F` | `#EE907E` | Inadimplente, suspenso, falha |
| Informação | `#2B6A8A` | `#6FB0D2` | Neutro informativo |

Gráficos usam uma paleta categórica própria de 6 cores (`--wv-color-chart-1` a `-6`), com versões para os temas claro e escuro, todas com contraste ≥ 3:1 sobre a superfície. As cores de estado não são usadas como séries de gráfico.

Os estados comerciais, financeiros e técnicos são exibidos em **badges separados** (nunca um único "status"), seguindo a arquitetura comercial §13.

### 4.4 Tokens
- `palette/tokens.json`: fonte da verdade (primitivos, semânticos claro/escuro, tipografia, espaçamento, raios, sombras, movimento, densidade).
- `palette/tokens.css`: variáveis CSS geradas (`--wv-*`). Tema escuro via `[data-theme="dark"]`; densidade compacta via `[data-density="compact"]`.
- As variáveis da v1.1 (`--waver-forest-900` etc.) continuam existindo por compatibilidade.
- Componentes consomem **apenas tokens semânticos** (`--wv-color-action-primary`), nunca hex nem primitivos diretamente.
- v1.3: a interface usa Angular Material (ADR-0027). O tema do Material é gerado **a partir destes tokens**: papéis de cor, tipografia Inter, formas e densidade compacta. As telas usam os componentes `wv-*`, que envolvem o Material. Qualquer tela com a "cara padrão" do Material (roxo, sombras e cantos padrão) está fora da marca.

## 5. Tipografia
- **Inter** para toda a interface e materiais. Pesos: 400, 500, 600.
- Inter é distribuída sob a licença SIL Open Font License 1.1, que permite uso comercial e embutir a fonte em apps. **Confirmar com o jurídico** e hospedar os arquivos da fonte junto do app, sem depender de CDN de terceiros.
- Escala (px): 12 · 13 · 14 (corpo padrão) · 16 · 20 · 24 · 32. Interface densa: corpo de 14 px, tabelas e controles de 13 px.
- Títulos de marca em caixa alta usam espaçamento de 0,2em (eco do wordmark). Não usar caixa alta espaçada em textos longos.
- Números financeiros e métricas: algarismos tabulares (`font-variant-numeric: tabular-nums`).

## 6. Aplicação por produto
| Produto | Tema | Regras |
|---|---|---|
| WAVER Control | Escuro por padrão (Forest) | Dashboards executivos; Lime só para indicadores de seleção e KPIs em destaque |
| WAVER Partner | Claro | Foco comercial; tabelas densas; badges de estado comercial/financeiro |
| WAVER Workspace | Claro, com áreas Forest | Chat, execução de agentes e Playground do Builder podem usar superfícies escuras |

- Controles finos e compactos; evitar botões grandes (Product Scope §20).
- Progressive disclosure: configurações avançadas atrás de "Avançado".
- Quando o cliente tenta usar algo não contratado: mensagem neutra e contato da revenda — **nunca** abrir o marketplace.

## 7. Imagens e fundos
- Fotografia: arquitetura com vegetação, folhagens, luz natural. Evitar estética genérica de "AI startup", neon, redes neurais e robôs.
- `backgrounds/*.jpg`: fundos derivados da paleta para implementação. Não são as fotografias das referências geradas.
- `visual_references/builder_flow_ui_concept.png`: referência de layout do Flow Builder. **Atenção:** o logo dessa imagem (folha em "V") não é o oficial.

## 8. Ativos fora de uso (`_deprecated/`)
| Arquivo | Motivo |
|---|---|
| `visual_references/legacy_name_VERDAN_brand_board.png` | Mostra o nome antigo VERDAN e outro símbolo |
| `visual_references/legacy_names_Admin_Reseller_Nexora_ribbon_logo.png` | Nomes antigos de produto (Admin, Reseller, Nexora) e logo de fita 3D |
| `visual_references/legacy_names_Admin_Revenda_Sistema_leaf_logo.png` | Nomes antigos de produto e logo de folha; outra assinatura |
| `icons/*_letter_v1.1.png` | Ícones com as iniciais dos nomes antigos |

Os arquivos foram mantidos só para histórico. Não devem ser usados nem enviados a fornecedores.

## 9. Conflito com o Product Scope
O Product Scope v1.1 (§20 e §23) ainda descreve a direção visual "branco/creme com laranja, vermelho e amarelo; evitar superfícies escuras". **Esta diretriz de marca prevalece**: verde institucional, com superfícies Forest permitidas conforme a seção 6. A correção está registrada em `01_PRODUCT_BASELINE/ERRATA_v1.2.md`.

## Histórico
- v1.2 (2026-10-19):
  - arquitetura de marca corrigida (a v1.0 dizia "WAVER é a marca corporativa. WAVER é o produto principal");
  - logo oficial definido e vetorizado;
  - ícones dos apps refeitos;
  - regras de contraste, cores de estado, tokens semânticos, tema escuro e densidade adicionados;
  - ativos com nomes antigos removidos do uso.
- v1.0: versão inicial.
