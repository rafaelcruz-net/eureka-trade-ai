# Requisitos de desempenho — U7 Ledger e P&L

Requisitos de tempo de resposta da Unit `u7-ledger-pnl`, derivados do NFR2 e NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos fluxos F2 a F9 de [functional-spec.md](../functional-design/functional-spec.md) e das respostas Q1 e Q2 de [nfr-requirements-questions.md](nfr-requirements-questions.md). Metas no ambiente local de referência (Docker Compose), revistas com a hospedagem.

## Requisitos

| ID | Requisito | Meta | Carga | Medição | Origem |
|---|---|---|---|---|---|
| NFR3.1 | Execução no ciclo | p95 ≤ 250 ms para `execute` e `closePositions`, sem contar a decisão e a barreira | 60 bots ativos, um ciclo por bot a cada 30 s, com as tarefas de marcação, fechamento pendente e reconciliação rodando no mesmo período nos seus intervalos normais | Teste de carga com um ciclo de teste que abre a transação e chama a `PaperExecution`, medindo o tempo da chamada durante 10 minutos | NFR2, Q1 |
| NFR3.2 | Rotas de P&L e de ledger | p95 ≤ 1 s em `GET /api/v1/pnl` (período de 30 dias e também o máximo aceito, de 366 dias) e `GET /api/v1/ledger` (primeira página) | 20 usuários simultâneos, uma ação a cada 2 s, com 10 000 preenchimentos por pessoa | Script de carga local | NFR3, Q2 |
| NFR3.3 | Demais rotas C5 | p95 ≤ 1 s em `trades`, `day-usage` e `reconciliation` | Mesma carga do NFR3.2 | Script de carga local | NFR3 |
| NFR3.4 | Tela de P&L | p95 ≤ 2 s do pedido até os totais visíveis | 20 usuários simultâneos | Playwright com marcação de tempo | NFR3 |
| NFR3.5 | Tarefas de marcação e fechamento pendente | p95 ≤ 10 s por rodada para 60 bots, medido sobre 20 rodadas seguidas, para não atrasar a próxima coleta | 60 bots com 5 posições cada e os ciclos do NFR3.1 rodando ao mesmo tempo | Teste de carga com medição do tempo de cada rodada | NFR2 |
| NFR3.6 | Reconciliação | Uma rodada para 20 pessoas com 10 000 preenchimentos cada termina em até 60 s; "verificar agora" de uma pessoa, em até 5 s | Volume do NFR2.1 | Teste de integração com volume gerado | FR10.5, BR8.1 |

## Orçamento da execução (p95 250 ms)

| Etapa | Orçamento |
|---|---|
| Trava da conta e inserção idempotente da ordem | 40 ms |
| Leitura do dado de mercado (C11) | 40 ms |
| Cálculo do preenchimento | 10 ms |
| Trava da posição, gravação do `Fill`, de 3 lançamentos e da posição | 80 ms |
| Maior patrimônio e evento da trilha | 40 ms |
| Folga | 40 ms |
| **Total** | **250 ms** |
