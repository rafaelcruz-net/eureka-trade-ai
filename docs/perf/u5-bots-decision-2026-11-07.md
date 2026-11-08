# Carga local — U5 Bots e decisão (2026-11-07)

Gerado por `scripts/load-test.sh` (`LOAD_SCENARIO=u5`).

- Pessoas: 20 · bots ativos: 60 · duração: 1800 s · fonte simulada no cenário de sinais frequentes
- Preparação: 400000 decisões semeadas em 285 s
- Resultado: **aprovado**

## Ciclo (worker)

| Medida | p95 | Limite |
|---|---|---|
| Ciclo inteiro (NFR2.1) | 112 ms | 2000 ms |
| Barreira mais execução (NFR2.3) | 22 ms | 500 ms |

- Linhas do cronômetro: 384; maior ciclo 406 ms; falhas 0
- Fatias completas: 5 de 5 que cabem inteiras na janela; ciclos completos 322, com falha 0
- Decisões por dado desatualizado durante a carga (NFR2.2): 0 (limite 0)
- Decisões durante a carga: BUY 108, SELL 108 (sem elas, a barreira não tem o que medir)
- Pool do worker: no máximo 4 conexões ativas (limite 14); maior espera 0.116 s (limite 1 s)

## Rotas C4 (NFR3.1)

| Rota | p95 | Limite |
|---|---|---|
| `/api/v1/bots` | 8 ms | 1000 ms |
| `/api/v1/bots/{botId}` | 7 ms | 1000 ms |
| `/api/v1/bots/{botId}/decisions` | 15 ms | 1000 ms |
| `/api/v1/bots/{botId}/commands/{command}` | 28 ms | 1000 ms |
| `/api/v1/strategy-templates` | 6 ms | 1000 ms |

## Tela S5 e canal ao vivo

- Tela S5, chamadas em paralelo (NFR3.2): p95 27 ms (limite 2000 ms) em 3588 aberturas
- Canal ao vivo, da resposta do comando ao evento `bot.state` (NFR3.3): p95 1906 ms (limite 4000 ms) em 3584 amostras; canais abertos 40, erros 0
- Comandos aceitos: 3588; erros de cliente 0
- Memória máxima: api 377 MB, postgres 467 MB, worker 279 MB (limite 900 MB)
- Falhas: nenhuma
