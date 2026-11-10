# Requisitos de desempenho — U5 `u5-anonymization`

Conversa: português (Brasil). Fontes: `construction/u5-anonymization/functional-design/` (BR5.2, F2) e `nfr-requirements-questions.md` (Q1).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.1 | A anonimização de uma conta com 20 bots, 100 sessões e 50 feedbacks termina com p95 ≤ 2 s | teste de integração com 20 repetições em contas semeadas |
| NFR3.2 | A anonimização usa só leituras e escritas indexadas por pessoa: `bot_owner`, `user_session.user_id`, `feedback_entry` por pessoa, `wallet.user_id` e o apagamento dos desafios de login do endereço (F2, passo J1) por `login_challenge.wallet_address`. Esta última coluna não tem índice hoje (`0001-identity.sql` só indexa `nonce` e `expires_at`), então a migração `0015-anonymization.sql` cria `login_challenge_wallet_idx` em `login_challenge (wallet_address)` | `EXPLAIN` com `enable_seqscan = off` para cada uma das cinco consultas, incluída a de `login_challenge` |
