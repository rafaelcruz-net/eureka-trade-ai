# Desenho de desempenho — U2 `u2-retention`

Conversa: português (Brasil). Implementa `construction/u2-retention/nfr-requirements/performance-requirements.md` (NFR3.1 a NFR3.5).

| Requisito | Desenho |
|---|---|
| NFR3.1, NFR3.2 | Os lotes apagam por chave primária: `DELETE ... WHERE pk IN (SELECT pk ... WHERE <corte> ORDER BY <corte> LIMIT 1000)`. Cada lote é uma transação própria, por `TransactionTemplate`. O laço para quando um lote apaga menos de 1000. A duração de cada lote vai para o log e para a métrica |
| NFR3.3 | O `WalletHasher` reusa um `Mac` por thread (`ThreadLocal`, clonado da instância inicial), sem alocar chave a cada evento. A checagem de `details` usa um único `Pattern` pré-compilado |
| NFR3.4 | Nenhuma consulta nova no caminho do login; o HMAC é calculado em memória. Os cenários de carga `u1` e `u3` existentes medem o efeito |
| NFR3.5 | A migração cria o índice `user_session_end_idx` sobre `coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at))`. A consulta do expurgo usa exatamente essa expressão. Os digests são apagados por `session_id IN (...)` do lote, pelo índice existente `session_used_refresh_digest_session_idx`. Os desafios usam o índice existente `login_challenge_expires_idx` |

## Orçamento por lote

Até 1000 linhas e 500 ms (p95) por lote, rodando às 03:20 e às 03:40 UTC. O teste com 100 mil linhas confere o tempo total de até 5 minutos e o plano com o índice.
