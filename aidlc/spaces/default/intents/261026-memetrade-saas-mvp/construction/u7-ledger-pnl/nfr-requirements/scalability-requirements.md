# Requisitos de escalabilidade — U7 Ledger e P&L

Requisitos de volume e crescimento da Unit `u7-ledger-pnl`, derivados do NFR2 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e da resposta Q2 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR2.1 | Volume do beta | O desenho e os testes consideram 20 pessoas, 60 bots, até 10 000 preenchimentos por pessoa (200 000 no total) e no máximo 3 lançamentos por preenchimento (compra e venda: TRADE_COST ou TRADE_PROCEEDS, SLIPPAGE e FEE; fechamento: CLOSE e FEE, catálogo C1 a C15), mais uma eventual compensação por lançamento, só em teste; o dimensionamento usa 4 por preenchimento como folga (cerca de 800 000 lançamentos), sem limpeza (FR10.1) | NFR2, Q2 |
| NFR2.2 | Consultas por índice | Toda rota C5 e toda tarefa usa índice por pessoa e bot; nenhuma consulta percorre o ledger inteiro. O plano de execução da consulta de P&L e da primeira página do ledger, com o volume do NFR2.1, não tem varredura completa de `ledger_entry` nem de `fill` | NFR3.2, NFR3.6 |
| NFR2.3 | Paginação | `ledger` e `trades` por cursor (`createdAt`, `entryId` ou `orderId`), `limit` padrão 20 e máximo 100 | regras comuns |
| NFR2.4 | Saldo sem somar o histórico a cada ordem | O saldo disponível é a soma dos lançamentos do bot por índice; com 10 000 preenchimentos por bot o cálculo cabe no orçamento do NFR3.1 (medido no teste de carga) | NFR3.1, BR2.5 |
| NFR2.5 | Uma instância no beta | O limite de "verificar agora" fica na memória do processo, como os limites das outras Units; as tarefas têm execução única pelo ShedLock e funcionam com mais de uma instância | NFR2 |
