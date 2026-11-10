# Desenho de desempenho — U4 `u4-data-requests`

Conversa: português (Brasil). Implementa `construction/u4-data-requests/nfr-requirements/performance-requirements.md` (NFR3.1 a NFR3.5).

| Requisito | Desenho |
|---|---|
| NFR3.1 | A criação faz:<ul><li>a contagem da janela de uma hora;</li><li>um `INSERT` com o índice único parcial;</li><li>a publicação do evento na mesma transação.</li></ul>A lista do convidado é uma consulta pelo índice `(tenant_id, user_id, received_at DESC)`, sem paginação (poucos pedidos por pessoa). O cenário `scripts/load/data-requests.mjs` mede o p95 |
| NFR3.2 | A lista administrativa usa paginação por cursor (`limit` 50) ordenada por `due_at`, com filtro de `status` pelo índice `(status, due_at)` |
| NFR3.3 | A prévia do resumo faz quatro chamadas às interfaces J1 a J4, que são leituras indexadas por pessoa em cada módulo, e a lista dos pedidos da pessoa |
| NFR3.4 | O job de 15 minutos faz:<ul><li>`UPDATE ... WHERE state = 'PENDING' AND expires_at <= now RETURNING` para as verificações vencidas;</li><li>`UPDATE ... SET overdue_notified_at = now WHERE status IN (abertos) AND due_at <= now AND overdue_notified_at IS NULL RETURNING`.</li></ul>Os eventos são publicados para as linhas devolvidas, em lotes de 500 |
| NFR3.5 | Índices:<ul><li>`(tenant_id, user_id, received_at)`;</li><li>`(status, due_at)`;</li><li>`closed_at` parcial (`WHERE closed_at IS NOT NULL`);</li><li>único parcial dos abertos por tipo.</li></ul>Os testes de `EXPLAIN` usam `SET LOCAL enable_seqscan = off` |
