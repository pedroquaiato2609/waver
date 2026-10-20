# WAVER

Plataforma B2B2B de agentes de IA (Grupo WAVER → revendas → clientes). A documentação completa está em [`docs/`](docs/00_START_HERE/README.md); este README explica como rodar o código.

## Estado atual: Fase 0 + duas fatias da Fase 1
| Parte | O que existe |
|---|---|
| Plataforma Java | `waver-domain-kernel` (IDs UUIDv7, tenancy, CNPJ/CPF com o formato alfanumérico, Money), `waver-platform-starter` (tenant context do token, Row-Level Security, problem+json, correlation ID, outbox Avro, inbox, auditoria, credenciais de serviço, repasse de token e de erros entre serviços), `waver-platform-test` (regras ArchUnit) |
| Contratos | `contracts/events/*.avsc` (Avro, registrados no Apicurio): organização, auditoria, revenda, cliente, convite, catálogo (`ProductPublished`, `PriceBookActivated`) e marketplace (`ListingPublished`, `AvailabilityChanged`) |
| Serviços | `tenant-service`, `audit-service`, `identity-access-service` (convites), `reseller-service` (onboarding com Temporal), `customer-service` (carteira), `catalog-service` (produtos, SKUs, tabelas de preço, margem e desconto), `marketplace-service` (ofertas e disponibilidade), `control-bff`, `partner-bff`, `edge-gateway` |
| Frontend | Workspace Nx em `frontend/` com os apps `control` (tema escuro) e `partner` (tema claro), Angular 22, NgRx, Angular Material com o tema WAVER |
| Infraestrutura local | Docker Compose em `deploy/local/`: PostgreSQL, Keycloak, Redis, Vault, Kafka, Kafka Connect + Debezium, Apicurio, Temporal, Mailpit |

Fluxo que já funciona de ponta a ponta:
1. **WAVER Control** (equipe comercial): registra o lead da revenda → inicia a análise → registra a due diligence → aprova.
2. O **workflow de onboarding no Temporal** (`reseller-service`) cria a organização da revenda no `tenant-service` e pede ao `identity-access-service` o convite do dono; o Keycloak envia o e-mail (capturado pelo Mailpit).
3. A equipe conclui contrato e treinamento e **ativa** a revenda; o evento `ResellerActivated` ativa a organização no `tenant-service`.
4. O dono da revenda abre o convite, cria a senha e o segundo fator e entra no **WAVER Partner**.
5. No Partner, a revenda **cadastra clientes**: o `customer-service` cria a organização do cliente sob a revenda e registra a carteira (`CustomerOwnership`).
6. No Control, em **Catálogo**, Product Ops cria um produto com seus SKUs e o publica; a equipe comercial cria uma tabela de preço (atacado e varejo por SKU), ativa a tabela e define faixas de margem e limites de desconto.
7. Em **Marketplace**, Product Ops cria a oferta sobre o produto publicado, envia para revisão, aprova a publicação e escolhe quem pode vender (todas as revendas, tiers ou revendas escolhidas).
8. No Partner, em **Ofertas disponíveis**, a revenda vê só as ofertas liberadas para ela, com o preço que paga e o preço ao cliente.

Cada passo grava o evento no outbox (Debezium → Kafka) e uma entrada na trilha de auditoria encadeada por hash.

As decisões de negócio da Fase 1 estão como **padrão provisório** (veja `docs/00_START_HERE/OPEN_DECISIONS.md`, seção "Padrões provisórios"). Enquanto o `approval-service` não existe, três aprovações são registradas direto por quem tem o papel, e ficam na auditoria: a da revenda (equipe comercial), a ativação de tabela de preço `BASE_PRICE_CHANGE` (equipe comercial) e a publicação de oferta `LISTING_PUBLICATION` (Product Ops).

**Preço e desconto (D-04, provisório).** Nenhum limite numérico está no código: tudo vem do cadastro. A margem é o acréscimo sobre o atacado, `(varejo − atacado) ÷ atacado`. `POST /v1/price-quotes` escolhe a tabela ativa mais específica (revenda, depois tier, depois todas) e devolve atacado e varejo. `POST /v1/discount-policy-evaluations` devolve `WITHIN_POLICY`, `APPROVAL_REQUIRED` (margem abaixo da faixa ou desconto acima do limite, com a política de aprovação) ou `FORBIDDEN` (abaixo do atacado ou do piso, acima do teto ou da margem máxima, preço fixo alterado). Sem limite de desconto cadastrado, qualquer desconto exige aprovação.

**Ativação da revenda.** Com catálogo e marketplace no ar, `CATALOG_ASSIGNED` e `PRICING_RULES_SET` passaram a ser obrigatórios para ativar (`waver.reseller.activation.required-items`); a equipe comercial os marca no checklist depois de liberar ofertas e definir as regras de preço da revenda.

**Visibilidade (RLS).** A revenda só lê produtos publicados, as tabelas e regras do próprio escopo e as ofertas cuja regra de disponibilidade a inclui; ela nunca lê a regra em si (que cita outras revendas). O `workspace-bff` não tem cliente de catálogo nem de marketplace (ADR-0009). Os tiers ainda não existem no `reseller-service`: regras por tier ficam gravadas, mas só passam a valer quando o tier for projetado (`ResellerTierChanged`).

## Pré-requisitos
- JDK 25 (Eclipse Temurin), Docker Desktop, Node 20.19+ e Python 3.
- Maven não precisa ser instalado: use `./mvnw` (Maven Wrapper).

## Rodar localmente
```bash
python deploy/local/up.py
```
Gera `deploy/local/.env` com segredos de teste (não versionado), sobe a infraestrutura e prepara bancos, Keycloak, Vault, tópicos e schemas. Pode rodar de novo a qualquer momento: aplica só o que mudou.

```bash
./mvnw install -DskipTests
```

```bash
python deploy/local/services.py start
```

```bash
python deploy/local/up.py connectors
```

Os dois apps rodam em servidores de desenvolvimento separados (um terminal para cada):

```bash
cd frontend && npx nx serve control
```

```bash
cd frontend && npx nx serve partner
```

| Endereço | O que é |
|---|---|
| http://localhost:8080 (ou http://control.localhost:8080) | WAVER Control. Entre com `admin@waver.local`; a senha está em `deploy/local/.env` (`WAVER_ADMIN_PASSWORD`) |
| http://partner.localhost:8080 | WAVER Partner. Entra o dono da revenda convidado |
| http://localhost:8025 | Mailpit: todos os e-mails locais, incluindo os convites |
| http://localhost:8233 | Temporal Web: os workflows de onboarding (`reseller-onboarding:<id>`) |

Código do segundo fator dos usuários de teste:

```bash
python deploy/local/totp.py
```

```bash
python deploy/local/totp.py reseller
```

Use o primeiro comando para o admin e o segundo para o dono da revenda. O segundo só funciona depois que você salvar em `deploy/local/.env` a chave mostrada pelo Keycloak na configuração do autenticador, na variável `WAVER_RESELLER_OWNER_TOTP_SECRET`.

Para parar: `python deploy/local/services.py stop` e `python deploy/local/up.py down`. Para reiniciar só um serviço: `python deploy/local/services.py stop reseller-service`, e depois `start reseller-service`.

## Portas locais
| Porta | Componente |
|---|---|
| 8080 | edge-gateway (entrada única; roteia por host: `partner.localhost` → Partner, os demais → Control) |
| 8081 / 8082 | control-bff / partner-bff |
| 8091 / 8092 / 8093 | tenant / audit / identity-access |
| 8094 / 8095 | reseller / customer |
| 8096 / 8097 | catalog / marketplace |
| 4200 / 4300 | servidores de desenvolvimento do Control / do Partner |
| 8180 | Keycloak |
| 5433 | PostgreSQL (5433 para não conflitar com um PostgreSQL local na 5432; `max_connections=200`, um pool por serviço) |
| 6379 / 8200 / 9092 / 8083 / 8085 | Redis / Vault / Kafka / Kafka Connect / Apicurio |
| 7233 / 8233 | Temporal (gRPC / Web) |
| 1025 / 8025 | Mailpit (SMTP / Web) |

## Testes
```bash
./mvnw verify
```
Inclui testes de domínio, regras de arquitetura (ArchUnit), testes de isolamento entre tenants contra PostgreSQL real (Testcontainers) e testes do workflow de onboarding com o servidor de testes do Temporal (tempo simulado). No marketplace, o teste de isolamento confere caso a caso que a Row-Level Security e a regra de domínio (`AvailabilityRule.allows`) dão a mesma resposta.

## Observações deste ambiente Windows
- `deploy/local/services.py` aponta o JDK para `deploy/local/run/sock` porque uma política desta máquina bloqueia sockets AF_UNIX em `%TEMP%`, que o Java usa internamente.
- O npm 10.8 tem um bug com dependências opcionais; `frontend/.npmrc` usa `legacy-peer-deps=true`.
- `*.localhost` resolve para a própria máquina nos navegadores modernos, então `partner.localhost` funciona sem editar o arquivo `hosts`.
