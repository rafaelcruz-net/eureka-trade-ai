# Carga local — U7 Ledger e P&L (2026-11-06)

Gerado por `scripts/load-test.sh` (`LOAD_SCENARIO=u7` e `u7-jobs`).

<!-- section:u7 -->
## Cenário `u7`

- Pessoas: 20 · bots: 60 · preenchimentos gravados antes da medição: 200305 · preparação: 623 s
- Usuários lendo as rotas C5: 20 · duração da medição: 600 s
- Ciclos do gerador ao fim: 1134 (falhas técnicas: 0)
- Resultado: **aprovado**

| Medida | p95 | Limite |
|---|---|---|
| execute | 34 ms | 250 ms |
| close | 62 ms | 250 ms |
| pnl | 39 ms | 1000 ms |
| ledger | 201 ms | 1000 ms |
| trades | 39 ms | 1000 ms |
| dayUsage | 134 ms | 1000 ms |
| reconciliation | 14 ms | 1000 ms |
| checkNow | 931 ms | 5000 ms |

- Bots encerrados com `closePositions` (NFR3.1): 10 de 10 (falhas: 0)
- Rodadas de reconciliação (NFR3.6): 4, durações 8484, 25372, 13779, 10618 ms, maior 25372 ms (limite 60000 ms)
- "Verificar agora" (NFR3.6): 20 pessoas, maior 994 ms (limite 5000 ms), falhas 0, divergentes 0
- Planos sem `Seq Scan` em `ledger_entry` ou `fill`: sim
- Espera do pool: máximo de 0 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 367 MB (limite 900 MB), postgres 857 MB (limite 1024 MB)
- Erros de cliente: 0 · novos logins por token vencido: 0
- Falhas: nenhuma
<!-- /section:u7 -->

<!-- section:u7-jobs -->
## Cenário `u7-jobs`

- Pessoas: 20 · bots: 60 · preenchimentos gravados antes da medição: 317 · preparação: 11 s
- Usuários lendo as rotas C5: 0 · duração da medição: 1800 s
- Ciclos do gerador ao fim: 3652 (falhas técnicas: 0)
- Resultado: **aprovado**

| Medida | p95 | Limite |
|---|---|---|
| execute | 28 ms | 250 ms |

- Rodadas de marcação: 76, maior 317 ms (limite 10000 ms)
- Rodadas de fechamento pendente: 155, maior 4 ms (limite 10000 ms)
- Rodadas de reconciliação (NFR3.6): 6, durações 185, 225, 211, 181, 216, 236 ms, maior 236 ms (limite 60000 ms)
- Planos sem `Seq Scan` em `ledger_entry` ou `fill`: não conferido neste cenário
- Espera do pool: máximo de 0 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 335 MB (limite 900 MB), postgres 102 MB (limite 900 MB)
- Erros de cliente: 0 · novos logins por token vencido: 0
- Falhas: nenhuma
<!-- /section:u7-jobs -->
