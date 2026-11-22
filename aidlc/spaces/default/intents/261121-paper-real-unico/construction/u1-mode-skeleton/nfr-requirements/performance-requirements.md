# Requisitos de desempenho — U1 `u1-mode-skeleton`

A U1 entrega a forma da visão por modo (C4), a elegibilidade do REAL (C2), os limites REAL da conta (C3) e a criação no modo escolhido (C1). As metas abaixo herdam o NFR1 do intent e valem para as rotas que a U1 cria ou muda [Q1].

## Metas de tempo de resposta

| ID | Rota ou caminho | Meta | Condição de carga | Como é medido |
|---|---|---|---|---|
| NFR1.1 | `GET /api/v1/pnl/by-mode` (C4) | p95 ≤ 1.000 ms | uma pessoa com 10 bots (5 PAPER e 5 REAL) e 2 anos de ordens do próprio bot | teste de integração com dados semeados (NFR1.5) |
| NFR1.2 | `GET /api/v1/bots/real-eligibility` (C2) | p95 ≤ 1.000 ms | a mesma pessoa de NFR1.1 | teste de integração com dados semeados (NFR1.5) |
| NFR1.3 | `GET /api/v1/real/account-limits` (C3) | p95 ≤ 1.000 ms | a mesma pessoa de NFR1.1, com carteira do bot ativa | teste de integração com dados semeados (NFR1.5) |
| NFR1.4 | `POST /api/v1/bots` (C1), nos dois modos | p95 ≤ 1.000 ms | a mesma pessoa de NFR1.1; uma `Idempotency-Key` nova por pedido; metade PAPER, metade REAL elegível | teste de integração com dados semeados (NFR1.5) |

## Método de medição na U1

| ID | Requisito | Valor |
|---|---|---|
| NFR1.5 | O p95 de NFR1.1 a NFR1.4 é medido num teste de integração (`*IT`, Failsafe, Testcontainers com PostgreSQL 17 e as migrações da aplicação), no mesmo padrão dos testes de latência que já existem (`AbstractU9MeasurementIT`). | Semente: 1 pessoa, 10 bots (5 PAPER, 5 REAL), 730 dias de ordens do próprio bot, 4 ordens por bot por dia (2 compras, 2 vendas), ou seja, 2.920 ordens por bot e 29.200 no total, com os lançamentos de ledger correspondentes nos dois modos. Por rota: 10 pedidos de aquecimento descartados e 100 pedidos medidos em sequência. O p95 é o percentil 95 pelo método do posto mais próximo (o 95º menor tempo dos 100). A medição é informativa, por decisão do coordenador deste estágio, que segue o precedente do responsável no `DashboardOpenLatencyIT` (medição só registrada, decidida no Build and Test do intent `261026-memetrade-saas-mvp` e aplicada no do `261109-termos-lgpd`): o teste registra no log o p95 de cada rota ao lado da meta de 1.000 ms, e um p95 acima da meta não falha o build. O teste falha só se algum pedido medido não responder com o status esperado (200 nas leituras, 201 na criação). O portão de 1.000 ms é o cenário de carga da CI da U2 (NFR1.6). |
| NFR1.6 | O cenário de carga da CI (`load-test`, manual) e a repetição na devnet que o NFR1 pede ficam para a U2, que troca o cálculo do P&L REAL por bot (C10, C12). Nesse cenário, o p95 ≤ 1.000 ms de C4 (`GET /api/v1/pnl/by-mode`) vira portão. C1, C2 e C3 não entram no cenário da U2 e continuam com o p95 só registrado no log (NFR1.5). | Motivo de C1, C2 e C3 ficarem sem portão: o NFR1 põe o portão no P&L por bot e por modo, que depende dos 2 anos de histórico; C2 e C3 leem só o estado atual (kill switch, carteira, liberação, limites da conta), C1 grava um bot, e a U2 não muda nenhum dos três, então o custo deles não cresce com o histórico. A U1 não acrescenta cenário em `scripts/load/` nem opção no `load-test.yml`. A U1 nunca vai para um ambiente com usuários sem a U2 (BR10.3, NFR8.4). |

## Orçamentos e restrições de recursos

| ID | Requisito | Valor |
|---|---|---|
| NFR1.7 | As leituras da U1 (C2, C3, C4) nunca travam a execução de ordens. | As leituras são só consulta, sem `SELECT ... FOR UPDATE` e sem trava consultiva nas tabelas de ordens, ledger, liberação, kill switch ou carteira. Um teste de integração roda C4 em paralelo com um ciclo de bot e confere que o ciclo termina dentro da trava de 2 s que já existe. |
| NFR1.8 | A criação REAL (C1) confere a elegibilidade (BR2.3) dentro do comando idempotente, sem chamada externa. | Zero chamadas de rede de saída por pedido: as fontes da elegibilidade são o banco (kill switch, carteira, liberação) e a configuração (`memetrade.real.enabled`). |
| NFR1.9 | O bloco de cada modo em C4 é calculado de forma independente. | O tempo de um bloco que falha não entra no orçamento do outro: a falha de um modo responde pelo NFR4.4 sem estender o p95 de NFR1.1 além de 1.000 ms. |

## Sources

- `nfr-requirements-questions.md`: Q1 (metas e método), resumo confirmado [Q1].
- `inception/requirements-analysis/requirements.md`: NFR1 (p95 de 1 s, 10 bots, 2 anos; carga da CI e devnet; leitura nunca trava a execução).
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR2.3, BR2.5, BR5.3, BR10.3.
- `inception/contract-design/contract-summary.md`: C1 a C4; pendência PQ-10 (índice e carga na U2).
- `inception/delivery-planning/bolt-plan.md`: cenário de carga do NFR1 no Bolt da U2.
- Código lido: `apps/api/src/test/java/com/memetrade/testsupport/AbstractU9MeasurementIT.java`, `bots/web/DashboardOpenLatencyIT.java` (padrão de medição), `application.yml` (histograma de `http.server.requests`).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (Determinismo, Desempenho).

## Assumptions & Open Questions

1. [assumption] A densidade da semente (4 ordens por bot por dia, 29.200 ordens no total) é uma escolha deste estágio, porque o NFR1 diz "2 anos de histórico" sem fixar a quantidade de ordens. O NFR Design da U1 pode trocar a densidade; a meta de 1.000 ms não muda.
2. **Decidido (coordenador deste estágio, seguindo o precedente do responsável no `DashboardOpenLatencyIT`):** na U1, o teste de integração mede o p95 e o registra no log, sem falhar o build quando passa de 1.000 ms, porque o runner compartilhado da CI tem ruído e o responsável já escolheu a medição só registrada naquele teste (Build and Test do intent `261026-memetrade-saas-mvp`, aplicada no do `261109-termos-lgpd`, conforme o comentário do próprio teste). O portão de 1.000 ms de C4 fica no cenário de carga da CI da U2, repetido na devnet, como o NFR1 pede; C1, C2 e C3 ficam só com o registro (NFR1.6). Tornar a medição da U1 um portão é decisão do responsável, nunca do agente.
3. O índice com slot e a leitura em fluxo do ledger REAL (C12, PQ-10) são da U2; a U1 lê a linha REAL pelas ordens do bot (BR5.5) com os índices atuais.
