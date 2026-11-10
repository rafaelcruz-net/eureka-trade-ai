# Desenho de desempenho — U5 `u5-anonymization`

Conversa: português (Brasil). Implementa `construction/u5-anonymization/nfr-requirements/performance-requirements.md` (NFR3.1, NFR3.2) e `scalability-requirements.md` (NFR3.3).

| Requisito | Desenho |
|---|---|
| NFR3.1 | A transação faz um número fixo de comandos, sem laço por linha: <ul><li>J2: um `SELECT ... FROM bot WHERE tenant_id = ? AND owner_user_id = ? ORDER BY bot_id FOR UPDATE`; um `UPDATE bot SET state = 'STOPPED' ... WHERE tenant_id = ? AND owner_user_id = ? AND state IN ('ACTIVE', 'PAUSED', 'CLOSING_PENDING')`; um `UPDATE bot SET name = 'Bot anonimizado'` pela mesma chave;</li><li>J3: uma chamada a `erase_feedback_of_user`, que faz um único `DELETE` e devolve a contagem;</li><li>J1: um `SELECT ... FROM user_session WHERE user_id = ? ORDER BY session_id FOR UPDATE`, um `SELECT ... FROM app_user WHERE user_id = ? FOR UPDATE`, um `UPDATE user_session` pela pessoa, um `DELETE FROM login_challenge WHERE wallet_address = ?`, um `UPDATE wallet` e um `UPDATE app_user`.</li></ul> O `AnonymizationLatencyIT` semeia 20 bots, 100 sessões e 50 feedbacks por conta, anonimiza 20 contas e confere p95 ≤ 2 s |
| NFR3.2 | Os acessos usam índices existentes: `bot_owner_state_idx (tenant_id, owner_user_id, state, updated_at)`, `user_session_user_idx (user_id)`, `feedback_entry_person_time_idx (user_id, created_at)` e a restrição única de `wallet.user_id`. A migração `0015-anonymization.sql` cria `login_challenge_wallet_idx ON login_challenge (wallet_address)`. O `AnonymizationIndexIT` roda `EXPLAIN` de cada um dos cinco comandos com `enable_seqscan = off` e falha se aparecer `Seq Scan` |
| NFR3.3 | Volume raro (algumas por mês): sem fila nem cache; nada além de NFR3.1 |
