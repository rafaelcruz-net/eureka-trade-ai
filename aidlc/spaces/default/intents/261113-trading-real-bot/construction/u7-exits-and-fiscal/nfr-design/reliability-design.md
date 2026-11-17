# Projeto de confiabilidade — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `construction/u7-exits-and-fiscal/nfr-requirements/reliability-requirements.md` (U7-NFR4.x) e `nfr-design-questions.md` (Q2, Q3, Q6).

| Requisito | Como o projeto atende |
|---|---|
| U7-NFR4.1 | Toda transição é um `UPDATE ... WHERE state = ?` dentro do `withPersonLock` da M5. |
| U7-NFR4.2 | O `FillPosting` grava os lançamentos pela M2 e o `FILLED` na mesma transação do banco. As chaves `cond:<sig>:fill` e `cond:<sig>:fee` ficam no índice único de idempotência da M2. |
| U7-NFR4.3 | O `PositionExitGuard.releaseForSale` responde de três formas: <br>• sem condicional: devolve a posição; <br>• condicional `OPEN`: pede o cancelamento e devolve "aguarde", e a U4 recoloca a decisão na fila com o atraso que já usa; <br>• condicional `FILLED`: devolve a posição relida, e com zero a venda termina com `NOTHING_TO_SELL`. |
| U7-NFR4.4 | O `ConditionalReadJob` e o `ConditionalCancelJob` têm ShedLock com nome próprio e publicam `real_exits_job_runs_total{job}` a cada execução. |
| U7-NFR4.5 | O `ConditionalCancelJob` busca as condicionais `CANCELLING` e confere a assinatura do último cancelamento na rede; se o blockhash venceu sem confirmar, monta outro. |
| U7-NFR4.6 | Erro da Jupiter ou do RPC vira "sem leitura" e não muda o estado; o `last_read_at` não avança. |
| U7-NFR4.7 | O saque da U1 não chama nada da U7. |
| U7-NFR4.8 | A `ReconciliationPolicy` recebe as custódias como mais uma entrada da foto. Falha na leitura vira `UNAVAILABLE`. |
| U7-NFR4.9 | O `closeForAnonymization` consulta o `OpenExitsCheck` antes de qualquer gravação. |
| U7-NFR4.10 | O `ExitsAvailability` lê o `RealProperties.exits.enabled`, que vem `false` por padrão. |
| U7-NFR4.11 | `needsUserCancellation` é calculado na leitura como `state = CANCELLING` e `now - cancelling_since >= 15 min`, com o `Clock` injetado. |
| U7-NFR4.12 | A rota de exportação não passa pela barreira nem pelo flag; só pelo novo aceite e pelo limite. |
