# Projeto de confiabilidade — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/nfr-requirements/reliability-requirements.md` (U5-NFR4.1 a U5-NFR4.6) e `nfr-design-questions.md` (Q2, Q3, Q6).

| Requisito | Como o projeto atende |
|---|---|
| U5-NFR4.1 | Na mainnet, a criação de liberação pega `pg_advisory_xact_lock(<constante da U5>)` só durante a contagem e a gravação. Um índice único parcial garante uma liberação não revogada por usuário e rede. A revogação roda dentro de `withPersonLock` da M5, junto com o `cancelUnsent`. As mudanças de liberação, divergência, kill switch (coluna `version`) e comandos são `UPDATE ... WHERE estado = ?`. As rotas usam o `IdempotencyGuard`. |
| U5-NFR4.2 | ShedLock com nome próprio por trabalho; o batimento `real_ops_job_runs_total{job}` é contado a cada execução. |
| U5-NFR4.3 | `CiStatus` devolve `GREEN`, `RED` ou `UNREACHABLE`; qualquer erro do RPC na reconciliação dá `UNAVAILABLE`. |
| U5-NFR4.4 | Ver "Restauração" abaixo. |
| U5-NFR4.5 | O saque chama o `WithdrawalLimit`, uma leitura pura, e não depende de trabalho nenhum. |
| U5-NFR4.6 | Cada item da compensação é conferido contra a última medida (`lastRunId`); a gravação é só de inclusão, pela M2. |

## Restauração (Q3)

1. No `ApplicationReadyEvent` do worker, o `RestoreDetector` abre o marcador `RESTORE_CHECK_PENDING` antes de qualquer leitura. O `DepositScanner` não roda enquanto ele estiver aberto, então o cursor de depósitos nunca avança sobre uma assinatura que a conferência ainda não viu.
2. Para cada carteira, pagina `signaturesSince(address, until = a assinatura mais recente que o banco conhece entre ordens, envios, saques e depósitos)`, com `before` até esgotar.
3. Para cada assinatura que o banco não conhece, chama `transactionSigners`.
   - Se a carteira do bot for assinante, a assinatura é desconhecida e indica restauração.
   - Se não for, é um depósito, que o `DepositScanner` lê depois.
4. Com alguma desconhecida, troca o marcador por `RESTORE`. Senão, fecha o marcador.
5. Com o RPC fora, o marcador continua aberto e o trabalho `restore-check` tenta de novo a cada minuto.

## Expurgo

O `RealOpsPurgeJob` apaga as reconciliações limpas com `NOT EXISTS` na divergência que as referencia, para nunca esbarrar no `ON DELETE RESTRICT`.
