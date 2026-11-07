# Carga local — U6 Barreira de limites (2026-11-06)

Gerado por `scripts/load-test.sh` (`LOAD_SCENARIO=u6`).

- Pessoas: 20 · bots: 60 · leitores da C21: 20 · duração: 600 s · expurgo disparado em 300 s
- Preparação: 270000 recusas e 200000 aprovadas antigas semeadas em 141 s
- Avaliações ao fim: 1215 (aprovadas 608, recusadas 607, puladas sem preço 0, falhas técnicas 0)
- Resultado: **reprovado**

## Janela sem expurgo

| Medida | p95 | Limite |
|---|---|---|
| `OrderGate.evaluate` | 22 ms | 250 ms |
| C21 `GET /api/v1/bots/{botId}/rejections` | 13 ms | 1000 ms |

## Janela com expurgo

| Medida | p95 | Limite |
|---|---|---|
| `OrderGate.evaluate` | 22 ms | 250 ms e no máximo 10% acima da primeira janela |
| C21 `GET /api/v1/bots/{botId}/rejections` | 8 ms | 1000 ms |

- Rodada do expurgo: SUCCEEDED; apagadas 200000 em 201 lotes; tamanho do lote 1000
- Lotes no log: 201; maior 90 ms (limite 100 ms)

## Banco

| Tabela | Linhas (estimadas) | Tamanho com índices |
|---|---|---|
| `order_evaluation` | 270661 | 164 MB |
| `order_rejection` | 270028 | 255 MB |
| `rejection_reason_detail` | 405026 | 74 MB |
| `risk_purge_run` | -1 | 0 MB |

- Planos sem `Seq Scan` em `order_evaluation` ou `order_rejection` (página da C21, procura da decisão, seleção do expurgo): não (page, decision)
- Espera do pool: máximo de 0 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 347 MB, postgres 582 MB (limite 900 MB)
- Leituras da C21: 5960 com 200, 0 com 404 (bot ainda não avaliado), erros de cliente 0, novos logins 0
- Falhas: query plan
