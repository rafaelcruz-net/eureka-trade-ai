# Desenho de observabilidade — U5 `u5-anonymization`

Conversa: português (Brasil). Implementa `construction/u5-anonymization/nfr-requirements/observability-requirements.md` (NFR8.1 a NFR8.4).

| Requisito | Desenho |
|---|---|
| NFR8.1 | O `AnonymizationMetrics` registra o contador `memetrade_accounts_anonymized_total` sem rótulos e o incrementa depois do commit (`TransactionSynchronization.afterCommit`), para não contar uma anonimização desfeita |
| NFR8.2 | Nova ação `ACCOUNT_ANONYMIZED` em `AuditActions`, com ator o administrador, `targetRef` com o `userId` interno e `details` só com `botsStopped`, `sessionsRevoked` e `feedbackDeleted`. O validador da trilha recusa outra chave em `details` dessa ação |
| NFR8.3 | `INFO` "Account anonymized" com `requestId` e as três contagens, depois do commit. `ERROR` "Account anonymization failed" com `requestId` e a classe da exceção, no tratamento do rollback; nunca o endereço, o `userId` só pelo identificador do pedido |
| NFR8.4 | O mesmo `AnonymizationMetrics` registra `memetrade_account_anonymization_failures_total{reason}`, com `reason` em `LOCK_TIMEOUT` (SQLSTATE `55P03`) ou `ROLLED_BACK` (qualquer outra exceção depois da validação). É incrementado no `afterCompletion` com status de rollback, fora da transação desfeita. As recusas de validação (`409`, `422`) não contam como falha. O alerta que usa essa métrica é definido no Infrastructure Design da U5 |
