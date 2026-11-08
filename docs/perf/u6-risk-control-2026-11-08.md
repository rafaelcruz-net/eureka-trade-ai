# Carga local — U6 Barreira de limites (2026-11-08)

Gerado por `scripts/load-test.sh` (`LOAD_SCENARIO=u6`).

- Pessoas: 20 · bots: 60 · leitores da C21: 20 · duração: 600 s · expurgo disparado em 300 s
- Preparação: 270000 recusas e 200000 aprovadas antigas semeadas em 172 s
- Avaliações ao fim: 1220 (aprovadas 610, recusadas 610, puladas sem preço 0, falhas técnicas 0)
- Resultado: **aprovado**

## Janela sem expurgo

| Medida | p95 | Limite |
|---|---|---|
| `OrderGate.evaluate` (601 amostras) | 21,93 ms | 250 ms |
| C21 `GET /api/v1/bots/{botId}/rejections` | 11 ms | 1000 ms |

## Janela com expurgo

| Medida | p95 | Limite |
|---|---|---|
| `OrderGate.evaluate` (619 amostras) | 16,09 ms | 250 ms e no máximo 10% acima da primeira janela |
| C21 `GET /api/v1/bots/{botId}/rejections` | 7 ms | 1000 ms |

- p95 de `OrderGate.evaluate` com expurgo em relação à janela sem expurgo: -26,6% (limite +10%)
- Forma de cálculo do p95 de `OrderGate.evaluate`: percentil 95 por posição mais próxima (ceil(0,95 × n)) das amostras de cada chamada, em microssegundos, guardadas pelo perfil loadtest. Só como referência, a faixa do histograma `loadtest_risk_evaluate_seconds` (a medida até a volta 2) deu 22 ms sem expurgo e 17 ms com expurgo
- Rodada do expurgo: SUCCEEDED; apagadas 200000 em 201 lotes; tamanho do lote 1000
- Lotes no log (duração arredondada para cima em ms): 201; lote 1 39 ms; maior 57 ms; lotes 2 em diante de 2 a 57 ms (limite 100 ms, todos os lotes, o 1 incluído)
- Aquecimento antes do lote 1 (fora do tempo dos lotes): 82 ms

## Banco

| Tabela | Linhas (estimadas) | Tamanho com índices |
|---|---|---|
| `order_evaluation` | 270713 | 165 MB |
| `order_rejection` | 270000 | 255 MB |
| `rejection_reason_detail` | 405000 | 74 MB |
| `risk_purge_run` | -1 | 0 MB |

- Planos sem `Seq Scan` em `order_evaluation` ou `order_rejection` (página da C21, procura da decisão, seleção do expurgo): sim
- Espera do pool: máximo de 0 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 365 MB, postgres 532 MB (limite 900 MB)
- Leituras da C21: 5940 com 200, 0 com 404 (bot ainda não avaliado), erros de cliente 0, novos logins 0
- Falhas: nenhuma

## Nota da volta 3 de Build and Test (F10 e F12)

Este arquivo guarda a **carga 3** desta volta. Mudanças medidas aqui:
- **F12 (A1):** o p95 de `OrderGate.evaluate` sai das amostras de cada chamada (microssegundos, posição mais próxima), e não mais da faixa do histograma. As regras não mudaram: 250 ms por janela e no máximo 10% acima da janela sem expurgo.
- **F10 (B1):** o aquecimento executa o `DELETE` do lote 1 numa transação desfeita com `rollback`, e a duração de cada lote é arredondada para cima em milissegundos. O limite de 100 ms por lote não mudou.

| Carga | Resultado | Aquecimento | Lote 1 | Maior lote | Lotes 2 a 200 | `evaluate` p95 sem / com expurgo (amostras) | Faixa antiga do histograma (referência) |
|---|---|---|---|---|---|---|---|
| 1 | aprovado | 68 ms | 41 ms | 58 ms | 35 a 58 ms | 21,30 / 16,90 ms (601 / 613) | 22 / 22 ms |
| 2 | aprovado | 74 ms | 51 ms | 57 ms | 35 a 57 ms | 16,64 / 16,61 ms (601 / 613) | 17 / 17 ms |
| 3 | aprovado | 82 ms | 39 ms | 57 ms | 35 a 57 ms | 21,93 / 16,09 ms (601 / 619) | 22 / 17 ms |

- A carga 3 foi repetida em 2026-11-08 (04:3x UTC), sozinha e sem outro ambiente de pé, depois que a primeira tentativa foi interrompida pelo sistema por falta de memória no computador.
- Nas três cargas: rodada SUCCEEDED com 200 000 apagadas em 201 lotes; nenhum `Seq Scan`; nenhuma conexão pendente; 0 falhas técnicas e 0 erros da C21; memória máxima de 372 MB na api e 570 MB no postgres.
- Na carga 2, o cron diário do expurgo (03:30 UTC) caiu dentro da carga e fez uma segunda rodada: aquecimento de 3 ms e 1 lote vazio de 1 ms.
- **F10 e F12 resolvidas** pelo critério do plano: 3 execuções seguidas aprovadas, todos os lotes até 100 ms (maior 58 ms) e o p95 com expurgo sempre abaixo do p95 sem expurgo.
