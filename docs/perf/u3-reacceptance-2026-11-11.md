# Carga local — U3 Novo aceite dos termos (2026-11-11)

Gerado por `scripts/load-test.sh` com `LOAD_SCENARIO=reacceptance` (perfis `test,loadtest`, catálogo de teste `reviewed`).

- Pessoas desatualizadas: 20 (aceite só da v1; vigente v2)
- Resultado: **aprovado**

| Medida | Valor | Limite |
|---|---|---|
| p95 de `POST /api/v1/terms/acceptances` (histograma da API) | 44.739 ms | 500 ms |
| p95 da consulta da trava (`memetrade_terms_reacceptance_check_seconds`) | 4.194 ms | 5 ms |
| p95 da espera por conexão do pool `memetrade-pool` (`hikaricp_connections_acquire_seconds`) | 1 ms | só informativo |
| Pessoas barradas / aceitas / liberadas | 20 / 20 / 20 | 20 / 20 / 20 |
