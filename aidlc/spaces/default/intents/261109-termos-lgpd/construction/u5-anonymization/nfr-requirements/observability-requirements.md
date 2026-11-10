# Requisitos de observabilidade — U5 `u5-anonymization`

Conversa: português (Brasil). Fontes: `construction/u5-anonymization/functional-design/` (BR5.2) e `nfr-requirements-questions.md` (Q2).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR8.1 | `memetrade_accounts_anonymized_total` no `/actuator/prometheus`, sem rótulo de pessoa | teste de integração da métrica |
| NFR8.2 | `ACCOUNT_ANONYMIZED` na trilha, pelo administrador, com alvo no identificador interno e `details` só com contagens (`botsStopped`, `sessionsRevoked`, `feedbackDeleted`) | teste de auditoria |
| NFR8.3 | Um `INFO` no sucesso e um `ERROR` na falha (com a classe da exceção), só com o identificador do pedido e as contagens | teste de log |
| NFR8.4 | `memetrade_account_anonymization_failures_total` no `/actuator/prometheus`, com um único rótulo `reason` de valores fechados (`LOCK_TIMEOUT`, `ROLLED_BACK`) e sem rótulo de pessoa nem de pedido; cresce a cada falha que deixa o pedido em `IN_PROGRESS`, para que falhas repetidas sejam agregáveis e alertáveis | teste de integração que força cada motivo e lê a métrica |
