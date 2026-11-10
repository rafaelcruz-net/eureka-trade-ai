# Perguntas de NFR Design — U5 `u5-anonymization`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Onde a transação da anonimização é aberta

A. Um `AnonymizationService` do Privacy abre a única transação (`@Transactional`, READ COMMITTED), define `lock_timeout` de 2 s com `set local` no início e chama, nesta ordem, a J2 `anonymizeOwnerBots`, a J3 `deleteByUser`, a J1 `anonymizeAccount`, o J6 e a conclusão do pedido. As três interfaces usam `Propagation.MANDATORY` e falham fora de uma transação. A idempotência usa o mecanismo comum, com a operação nova `DATA_REQUEST_ANONYMIZE`. (recomendado)
B. Cada módulo abre a sua transação e o Privacy compensa em caso de falha.
X. Other (please specify)

[Answer]: A

## Q2 — Contador de falhas

A. O `AnonymizationMetrics` registra `memetrade_accounts_anonymized_total` e `memetrade_account_anonymization_failures_total{reason}`, com `reason` em `LOCK_TIMEOUT` ou `ROLLED_BACK`. A falha é contada depois do rollback, fora da transação desfeita, para que o contador não suma junto com ela. (recomendado)
B. Só o log de erro.
X. Other (please specify)

[Answer]: A
