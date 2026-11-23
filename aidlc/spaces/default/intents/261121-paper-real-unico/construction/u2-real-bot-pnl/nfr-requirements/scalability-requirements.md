# Requisitos de escala — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

Nada muda na escala do sistema: a U2 roda no monólito atual (API e worker no mesmo app Spring Boot, um PostgreSQL), sem processo, fila ou cache novos. A única mudança de dados é a migração `0023`, com a tabela `real_entry_slot` e o índice que a leitura com o slot precisa, e a leitura em fluxo é feita por pessoa [Q6]. Os requisitos abaixo fixam esses limites.

## Projeção de carga e volume de referência

| ID | Requisito | Valor |
|---|---|---|
| NFR1.14 | Volume de referência por pessoa para o P&L REAL. | Até 10 bots (no cenário, 5 PAPER e 5 REAL) e 2 anos de histórico: 14.600 ordens REAL por pessoa na densidade de NFR1.6, com 2 a 3 lançamentos de negociação por ordem (fill, `NETWORK_FEE` e `RENT` na compra), ou seja, de 29.200 a 43.800 lançamentos REAL lidos por leitura. Acima disso, nenhuma meta da U2 é prometida, mas a leitura continua em fluxo (NFR1.15). |
| NFR1.15 | A leitura é em fluxo, por pessoa, e nunca carrega o histórico numa lista (BR10.1, C12). | `streamInNetworkOrder` e `streamSettledOrders` entregam um item por vez a um `Consumer`; 0 métodos novos que devolvam a lista de lançamentos ou de ordens da pessoa. O JDBC lê com `fetchSize` maior que 0 e no máximo 1.000 linhas, dentro de uma transação só de leitura (sem isso o driver do PostgreSQL traz o resultado inteiro). O que fica na memória por leitura é o índice compacto das ordens (ENT-204) e as posições abertas por carteira, titular e token (ENT-206). Teste de integração: a mesma pessoa com 1 e com 2 anos de histórico; o `fetchSize` efetivo é conferido no teste da consulta, e no cenário de NFR1.6 a memória do contêiner `api` fica ≤ 900 MB. |
| NFR1.16 | Concorrência: leituras de pessoas diferentes não esperam umas pelas outras, e cada leitura segura no máximo 1 conexão do pool. | No cenário de NFR1.6, com 20 leituras simultâneas: `hikaricp_connections_pending` > 0 por no máximo 10 s seguidos; cada leitura usa 1 conexão, pelo tempo da própria leitura (p95 ≤ 1.000 ms, NFR1.1); 0 travas entre leituras (NFR1.10). O tamanho do pool da API não muda. |
| NFR1.17 | A U2 não acrescenta processo, réplica, fila, cache nem gatilho de escala. | 0 serviços novos, 0 réplicas novas, 0 filas novas, 0 caches novos, 0 tabelas de P&L guardado (ADR-004: nada é guardado entre leituras). As leituras rodam no processo da API, e a leitura de custo da barreira e do ciclo no processo do worker, que já existem. |

## Índice e crescimento de dados

| ID | Requisito | Valor |
|---|---|---|
| NFR1.18 | A migração `0023` cria a tabela `real_entry_slot` (chave primária `tx_signature`) e o índice que a leitura em fluxo com a junção pela assinatura precisa em `real_ledger_entry` (PQ-10). | As colunas exatas do índice ficam com o NFR Design (por exemplo, `bot_wallet_id, tx_signature`, ou com `created_at`), mas o resultado é mensurável e se restringe à leitura grande: no cenário de NFR1.6 e num teste de integração com o volume de NFR1.14, o `EXPLAIN` da consulta principal, a leitura do ledger REAL de uma pessoa na ordem de rede (`streamInNetworkOrder`), usa o índice da `0023` pelo nome (`Index Scan`, `Index Only Scan` ou `Bitmap Index Scan`) e não tem `Seq Scan` em `real_ledger_entry`. Um `Hash Join` com `Seq Scan` em tabela pequena (`real_entry_slot`, `bot`, `bot_wallet`) é aceito, porque o planejador pode escolhê-lo legitimamente; o portão não olha essas tabelas (revisão R-06). O índice é criado na mesma migração, sem `ALTER` de coluna (NFR3.6). |
| NFR1.19 | Crescimento de `real_entry_slot`. | 1 linha por assinatura confirmada (ordem REAL, condicional executada, depósito, saque e taxa da carteira do bot), cerca de 100 bytes por linha mais a chave primária. Projeção do piloto (21 carteiras, 3 bots cada, até 4 ordens por bot por dia, mais depósitos e saques): até cerca de 100 mil linhas e de 20 MB por ano. A tabela não tem expurgo nesta Unit: ela é só de inserção, como o ledger, e cresce na mesma proporção dele. Uma política de retenção, se um dia for preciso, acompanha a do ledger REAL e é decidida em outro estágio. |

## Custo e ambientes

| ID | Requisito | Valor |
|---|---|---|
| NFR8.1 | A U2 roda no mesmo app, sem serviço AWS novo. | 0 recursos de serviço novos em `infra/terraform/`; 0 mudanças nas definições de tarefa do ECS. As únicas mudanças de IaC são a métrica nova na lista do CloudWatch e o alarme de NFR2.14, dentro do CloudWatch que já existe. |
| NFR8.2 | O orçamento de cada conta AWS não muda. | US$ 350 por mês por conta (`devnet` e `mainnet`). Custo recorrente acrescentado pela U2: 1 métrica personalizada no namespace `Eureka/App` e 1 alarme padrão, cerca de US$ 0,40 por mês por conta. O armazenamento da tabela nova (NFR1.19) cabe no RDS que já existe. |
| NFR8.3 | Os usuários têm um único ambiente, a mainnet; a devnet continua fechada aos usuários. | 0 rotas, telas ou configurações da U2 que abram a devnet a uma conta de usuário. |
| NFR8.4 | A U2 vai para um ambiente com usuários junto com a U1, e a migração `0023` roda antes das imagens novas. | A U1 só chega à `mainnet` num SHA que já contém a U2 com o Build and Test aprovado (NFR8.4 da U1, BR10.3). A `0023` é aplicada pelo processo `db-migrate` antes da troca das imagens, e as imagens anteriores continuam funcionando com a tabela nova (NFR2.3). |

## Sources

- `nfr-requirements-questions.md`: Q6 e o resumo confirmado [Q6]; Q1 (volume de referência e carga) [Q1].
- `inception/requirements-analysis/requirements.md`: NFR1 (10 bots, 2 anos), NFR8 (mainnet única, nenhum serviço AWS novo, US$ 350).
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR10.1, BR10.2, BR10.3; `functional-spec.md`: F1, F6, F7; `entities.md`: ENT-202, ENT-204, ENT-206.
- `inception/contract-design/contract-summary.md`: C12, C13, C14, PQ-10.
- `inception/domain-design/decisions.md`: ADR-004 (nada guardado entre leituras).
- `construction/u1-mode-skeleton/nfr-requirements/scalability-requirements.md`: NFR1.10, NFR1.11, NFR8.1 a NFR8.4 da U1.
- `aidlc/spaces/default/memory/team.md`: Deployment (contas, orçamento, `db-migrate`, expand-contract); `project.md`: orçamento de US$ 350 e a leitura do CloudWatch Agent a cada 120 s.
- Código lido: `scripts/load/load-test.mjs` (limites de memória de 900 MB e de `hikaricp_connections_pending`), `infra/terraform/modules/observability/app-metrics.tf`, `db/changelog/changes/` (última migração: `0022-real-exits.sql`).

## Assumptions & Open Questions

1. [assumption] O tamanho por linha de `real_entry_slot` (assinatura de até 90 caracteres, slot `bigint`, `recorded_at`) e a projeção do piloto são estimativas deste estágio; o NFR Design pode conferir com o tamanho real no PostgreSQL. A projeção não muda nenhuma meta.
2. [assumption] O custo de US$ 0,40 por mês usa o preço público de uma métrica personalizada e de um alarme padrão do CloudWatch; ele é pequeno diante do orçamento e não muda a decisão.
3. A escolha das colunas do índice e a decisão sobre o limite `real-max-orders` da U1 ficam com o NFR Design (PQ-10; suposição 3 do `functional-spec.md`).
