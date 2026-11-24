# Requisitos de escala — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24.

A escala não muda [Q4]. A U4 acrescenta uma leitura por ciclo de bot REAL com a flag ligada, troca a leitura de custo da carteira pelo caminho `CYCLE` da U2 por ela (BR1.6) e não cria tabela, processo, fila, cache nem gatilho de escala. A única mudança de dados é a regra de valores aceitos de `bot_cycle.outcome` (NFR2.7).

## Projeção de carga

| ID | Requisito | Valor |
|---|---|---|
| NFR1.8 | Volume de referência da leitura. | O do piloto do 261113 vezes 2: 21 carteiras do bot com 3 bots REAL cada, 63 bots, um ciclo a cada 5 min, ou seja, cerca de 0,21 leitura por segundo no worker. Cada leitura dura no máximo 250 ms (NFR1.7): em média, menos de 0,06 conexão ocupada pela leitura. |
| NFR1.9 | Conexões do worker. | A leitura usa uma conexão do pool de ciclo que já existe (`memetrade-worker-pool`, 36 conexões, 4 de ciclo) e a devolve antes de pedir a da transação do ciclo (NFR1.4): no máximo 1 conexão por ciclo de cada vez, e no máximo 4 ciclos em paralelo, como hoje. 0 conexões novas no pool, 0 mudanças no tamanho do pool. `hikaricp_connections_pending` do worker continua sem espera em duas leituras seguidas, como no critério do `load-test-plan.md` do 261113. |
| NFR1.10 | Leituras evitadas. Só um bot REAL, com a flag ligada, que vai rodar o ciclo faz a leitura (BR2.5; F1, passo 0). | **Como:** a lista de bots ativos que o `SlotDispatcher` lê passa a trazer o modo e o mercado de cada bot (o mesmo dado da linha do bot, interno ao módulo `bots`), e o `CycleRunner` recebe isso junto com o `BotRef`. Antes da leitura do RealBotHoldings e antes da leitura da marca M0, sem trava e só lendo, o ciclo roda as conferências prévias: modo REAL e flag ligada (em memória); estado do bot `ACTIVE` sem motivo de pausa (uma leitura da linha do bot pela chave primária); slot dentro dos 5 minutos e ainda não reclamado (uma leitura da reclamação do slot pela chave); pausa do sistema desligada (uma leitura de `system_pause_state`). São 3 consultas por chave, de custo desprezível diante dos 250 ms. Se alguma falhar, nem M0 nem o RealBotHoldings são lidos, e o ciclo segue como hoje e pula dentro da transação. As conferências que valem continuam dentro da transação, sob a trava. **Critério:** bot PAPER, flag desligada, slot passado de 5 min ou já reclamado, bot fora de `ACTIVE`, bot pausado ou pausa do sistema dão 0 leituras de M0 e 0 do RealBotHoldings (T5, com um contador de chamadas e a ordem das leituras conferida). Se o estado mudar entre a conferência prévia e a trava, o pior caso é uma leitura desperdiçada (aceito) ou um bot REAL que roda sem a leitura e é tratado como `Unavailable(READ_FAILED)`, sem venda (BR2.5). |
| NFR1.11 | A leitura é por bot e cresce com o histórico do bot desde a época de cada mint, não com o número de pessoas. | Leitura em fluxo (`JdbcClient` com `fetchSize` entre 1 e 1.000, numa transação só de leitura), reaproveitando as leituras da U2 (C12, C13), que já usam o índice da `0023` em `real_ledger_entry`. As vendas em aberto (C16) leem só ordens em andamento e decisões `PENDING` do bot. O NFR Design confere no `EXPLAIN` da consulta de C16 que não há `Seq Scan` em `real_order` nem na fila de decisões. |

## Dados

| ID | Requisito | Valor |
|---|---|---|
| NFR1.12 | Crescimento de dados. | 0 tabelas novas e 0 colunas novas na U4; 0 índices novos, salvo um que o NFR Design peça pela conferência de NFR1.11. `bot_cycle` continua com 1 linha por ciclo, como hoje (cerca de 63 × 288 = 18.144 linhas por dia no volume de NFR1.8); a U4 só acrescenta dois valores possíveis de `outcome` (`REAL_NO_OWN_QUANTITY`, 20 caracteres; `REAL_OWN_QUANTITY_UNAVAILABLE`, 29) na coluna `varchar(32)` que já existe, e as notas por mint no contexto da decisão (no máximo 1 nota por mint da carteira). A migração não reescreve a tabela (NFR2.7). |

## Custo e ambientes

| ID | Requisito | Valor |
|---|---|---|
| NFR8.1 | A U4 roda no mesmo app, sem serviço AWS novo (NFR8 do intent). | 0 serviços AWS novos; a leitura roda no worker da tarefa `api-worker` que já existe. |
| NFR8.2 | O orçamento de cada conta AWS não muda. | Com o alarme por razão [fundador F2], entram na lista do CloudWatch 4 métricas personalizadas: `real_own_holdings_unavailable_total` com a dimensão `reason` (3 séries, 3 métricas) e `real_own_holdings_reads_total` sem dimensão (1 métrica), a cerca de US$ 0,30 cada, ou seja, cerca de US$ 1,20; e o alarme de expressão com 3 métricas, a cerca de US$ 0,10 por métrica, cerca de US$ 0,30. Ao todo, cerca de US$ 1,50 por conta e por mês com a razão; com o alarme do bot preso da decisão F3 (NFR2.18), mais 1 métrica (`real_own_holdings_stuck_bots`, cerca de US$ 0,30) e 1 alarme padrão (cerca de US$ 0,10): **cerca de US$ 1,90 por conta e por mês** (alinhamento A17), dentro do orçamento de US$ 350 (`project.md`). A primeira versão deste arquivo estimava US$ 0,40 com 1 métrica; a diferença vem da razão e do bot preso. `real_cycle_sell_capped_total` fica só no Prometheus: US$ 0. |
| NFR8.3 | Os usuários têm um único ambiente, a mainnet; a devnet continua fechada aos usuários (`project.md` NEVER). | A flag é ligada primeiro na devnet (testes do fundador) e depois na mainnet com o mesmo SHA (BR5.2, NFR2.8). Nenhuma configuração nova abre a devnet. |
| NFR8.4 | A flag é uma variável de ambiente das definições de tarefa, versionada no Terraform, desligada por padrão [Q4]. | `infra/terraform/main.tf`: `MEMETRADE_REAL_OWN_QUANTITY_SELL = "false"` em `api_environment` e em `worker_environment` (variáveis separadas, porque a API e o worker são dois contêineres da mesma tarefa e viram em implantações diferentes, NFR2.8). Teste offline do Terraform: o valor padrão é `"false"` nos dois mapas. |

## Sources

- `nfr-requirements-questions.md`: Q4 e o resumo confirmado [Q4]; Q3 (custo do CloudWatch) [Q3].
- `reviews/review-01.md` (R-03) e a decisão do fundador de 2026-11-24 F2 (alarme por razão, custo das métricas).
- `inception/requirements-analysis/requirements.md`: NFR1, NFR8.
- `construction/u4-own-quantity-cycle/functional-design/rules.md`: BR1.6, BR2.2, BR2.5, BR5.2; `functional-spec.md`: "Efeito nos contratos".
- `construction/u2-real-bot-pnl/nfr-requirements/scalability-requirements.md`: NFR1.15, NFR1.18 (fluxo e índice da `0023`), NFR8.1 a NFR8.4 da U2.
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/performance-validation/load-test-plan.md`: 63 bots, 0,21 ciclo/s, pool do worker.
- `aidlc/spaces/default/memory/project.md`: orçamento de US$ 350, leitura do CloudWatch Agent a cada 120 s; `team.md`: Deployment (contas, Flags).
- Código lido: `infra/terraform/main.tf` (`app_environment`, `api_environment`, `worker_environment`), `infra/terraform/modules/services/api-worker.tf` (uma tarefa com os contêineres `api` e `worker`), `db/changelog/changes/0020-real-bot-cycle.sql` (`outcome varchar(32)`).

## Assumptions & Open Questions

1. [assumption] Os custos de US$ 0,30 (métrica personalizada) e de US$ 0,10 (alarme padrão) usam o preço público do CloudWatch; a região ainda não foi definida (`team.md`) e a diferença entre regiões não muda a decisão.
2. A existência de índice na fila de decisões por bot e estado `PENDING` fica para o NFR Design (NFR1.11); se faltar, um índice novo seria uma migração só de expansão, com `CREATE INDEX CONCURRENTLY`.
