# Pack WAVER v1.3: adoção de novas tecnologias

Data: 2026-10-19
Decisão do responsável pelo produto: adotar as tecnologias que a v1.2 tinha deixado de fora de propósito.

## O que entrou
| Tecnologia | Para quê | ADR | Substitui |
|---|---|---|---|
| **Redis** | Sessões dos BFFs, limite de requisições na borda, contadores de cota em tempo real, chaves de idempotência, cache compartilhado, streaming da resposta dos agentes | ADR-0020 | ADR-0013 (só PostgreSQL) |
| **Keycloak** | Login, MFA/passkeys, SSO com o IdP do cliente, emissão de tokens, organizações | ADR-0021 | ADR-0008 (Spring Authorization Server) |
| **HashiCorp Vault** | Segredos, credenciais dinâmicas de banco, criptografia das credenciais dos clientes (Transit) | ADR-0022 | ADR-0014 (mecanismo; as regras de posse continuam) |
| **Temporal** | Processos longos: provisionamento, inadimplência, faturamento, transferências, esperas dos agentes, workflows dos clientes | ADR-0023 | ADR-0018 (máquinas de estado no PostgreSQL) |
| **Schema registry (Apicurio) + Avro + Debezium** | Contratos de evento validados em tempo de execução; envio do outbox para o Kafka lendo o log do PostgreSQL | ADR-0024 | ADR-0012 (formato e relay; o envelope CloudEvents continua) |
| **Qdrant** (banco vetorial) | Busca semântica de knowledge e memória | ADR-0025 | ADR-0011 (pgvector) |
| **Istio** (service mesh, modo ambient) | mTLS automático entre serviços, autorização serviço a serviço, controle de saída para a internet, canary | ADR-0026 | — (antes: sem mesh) |
| **Nx + NgRx + Angular Material** | Organização do frontend, estado das telas, biblioteca de componentes com o tema WAVER | ADR-0027 | Partes de frontend do ADR-0007 |

## O que não mudou
- **Stack central (ADR-0001):** Angular/TypeScript, Java/Spring Boot, PostgreSQL, Kafka, Docker e Kubernetes.
- **PostgreSQL continua sendo a fonte da verdade** de todos os dados de negócio. Redis e Qdrant são auxiliares e podem ser reconstruídos.
- Regras arquiteturais (AR-001 a AR-015), mapa de serviços, BFF por público, isolamento por tenant com Row-Level Security, conectores declarativos, model-gateway.
- Regras e decisões de negócio (comercial, inadimplência, contratos).

## Cuidados que vieram junto
1. **Mais componentes para operar:** o time de plataforma passa a cuidar de 8 sistemas com estado ou de controle a mais. Faça o deploy de cada um como código em `deploy/platform/` e prefira serviços gerenciados quando o provedor de nuvem for escolhido (D-39).
2. **Licenças:** Redis 8 (tri-licença) e Vault (BSL 1.1) permitem o uso interno da WAVER, mas o jurídico precisa confirmar (D-37, D-38). Se houver problema, existem alternativas compatíveis: Valkey e OpenBao.
3. **Aparência do Angular Material:** o tema precisa ser 100% feito com os tokens da marca e passar pelos componentes `wv-*`. Sem isso, as telas ficam com a "cara padrão do Material" (ADR-0027, RFC-0021).
4. **Retries duplicados:** o mesh fica com os retries desligados. A política de repetição continua só no Resilience4j (ADR-0026, RFC-0005).
5. **Temporal fora do caminho crítico:** uma mensagem comum de chat não abre workflow; só esperas longas usam o Temporal (ADR-0023).

## Documentos atualizados
- Novos: ADR-0020 a ADR-0027; runbooks do Debezium (replication slot) e do Vault.
- Revisados para a v1.3: TECH_STACK, ADR_INDEX, CONVENTIONS, MICROSERVICES_MAP, DELIVERY_ROADMAP (Fase 0 com a infraestrutura), SECURITY_BASELINE, KAFKA_BASELINE, API_GUIDELINES_BASELINE, OPEN_DECISIONS (D-37 a D-39), ADR-0009, ADR-0015 e REPOSITORY_LAYOUT.
- RFCs, padrões (STD), ARs e runbooks: revisados com a linha "Revision 1.3" no cabeçalho de cada arquivo alterado.
- Marcados como substituídos: ADR-0008, 0011, 0013 e 0018 (total); ADR-0012 e 0014 (parcial); ADR-0007 (parte de frontend).
