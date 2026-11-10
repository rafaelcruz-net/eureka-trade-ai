# Desenho de desempenho — U3 `u3-reacceptance`

Conversa: português (Brasil). Implementa `construction/u3-reacceptance/nfr-requirements/performance-requirements.md` (NFR3.1 a NFR3.3).

| Requisito | Desenho |
|---|---|
| NFR3.1 | O aceite faz:<ul><li>a leitura da vigente, em memória;</li><li>um `INSERT ... ON CONFLICT (user_id, terms_version) DO NOTHING`;</li><li>a leitura do aceite existente quando não houve inserção;</li><li>a publicação do evento na mesma transação.</li></ul>O cenário `scripts/load/reacceptance.mjs` mede o p95 |
| NFR3.2 | `SELECT EXISTS (SELECT 1 FROM terms_acceptance WHERE user_id = ? AND terms_version = ?)`, pelo índice da restrição única. Um `Timer` do Micrometer, `memetrade.terms.reacceptance.check`, envolve só essa consulta. Ele é criado com `publishPercentileHistogram()`, para o Prometheus expor os buckets. O cenário `reacceptance` lê `/actuator/prometheus` no fim e calcula o p95 pelos buckets, como o cenário `u7` faz com o tempo de execução |
| NFR3.3 | `/novo-aceite` usa o mesmo cliente de servidor de L1 da U1, com cache de 300 s, e não traz dependência nova |
