# Carga local — U6 Barreira de limites (2026-11-07)

Gerado por `scripts/load-test.sh` (`LOAD_SCENARIO=u6`).

- Pessoas: 20 · bots: 60 · leitores da C21: 20 · duração: 600 s · expurgo disparado em 300 s
- Preparação: 270000 recusas e 200000 aprovadas antigas semeadas em 171 s
- Avaliações ao fim: 1219 (aprovadas 610, recusadas 609, puladas sem preço 0, falhas técnicas 0)
- Resultado: **aprovado**

## Janela sem expurgo

| Medida | p95 | Limite |
|---|---|---|
| `OrderGate.evaluate` | 22 ms | 250 ms |
| C21 `GET /api/v1/bots/{botId}/rejections` | 14 ms | 1000 ms |

## Janela com expurgo

| Medida | p95 | Limite |
|---|---|---|
| `OrderGate.evaluate` | 22 ms | 250 ms e no máximo 10% acima da primeira janela |
| C21 `GET /api/v1/bots/{botId}/rejections` | 11 ms | 1000 ms |

- Rodada do expurgo: SUCCEEDED; apagadas 200000 em 201 lotes; tamanho do lote 1000
- Lotes no log: 201; lote 1 94 ms; maior 94 ms (limite 100 ms, todos os lotes, o 1 incluído)
- Aquecimento antes do lote 1 (fora do tempo dos lotes): 7 ms

## Banco

| Tabela | Linhas (estimadas) | Tamanho com índices |
|---|---|---|
| `order_evaluation` | 270718 | 165 MB |
| `order_rejection` | 270000 | 255 MB |
| `rejection_reason_detail` | 405000 | 74 MB |
| `risk_purge_run` | -1 | 0 MB |

- Planos sem `Seq Scan` em `order_evaluation` ou `order_rejection` (página da C21, procura da decisão, seleção do expurgo): sim
- Espera do pool: máximo de 0 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 323 MB, postgres 541 MB (limite 900 MB)
- Leituras da C21: 5960 com 200, 0 com 404 (bot ainda não avaliado), erros de cliente 0, novos logins 0
- Falhas: nenhuma

## Nota da volta 2, continuação (F10)

O relatório acima é a terceira de três execuções seguidas de 2026-11-07, feitas depois do Passo 25 (aquecimento da rodada do expurgo), uma de cada vez e sem outro ambiente de pé. Cada execução sobrescreve este arquivo; as três ficam na tabela abaixo.

| Execução | Resultado | Aquecimento | Lote 1 | Maior lote | Lotes 2 a 200 | `evaluate` p95 (sem / com expurgo) | C21 p95 (sem / com expurgo) | Memória máx. (api / postgres) |
|---|---|---|---|---|---|---|---|---|
| 1 | aprovado | 8 ms | **100 ms** | 100 ms | 46 a 78 ms | 22 / 22 ms | 14 / 10 ms | 362 / 526 MB |
| 2 | **reprovado** (`evaluate with the purge more than 10% above`) | 7 ms | 95 ms | 95 ms | 44 a 78 ms | 22 / **34 ms** | 14 / 11 ms | 360 / 528 MB |
| 3 | aprovado | 7 ms | 94 ms | 94 ms | 44 a 81 ms | 22 / 22 ms | 14 / 11 ms | 323 / 541 MB |

Nas três: rodada SUCCEEDED com 200 000 apagadas em 201 lotes (o lote 201, vazio, levou 1 a 2 ms), nenhum `Seq Scan` nas consultas conferidas, nenhuma conexão pendente no pool, 0 falhas técnicas nas avaliações e 0 erros nas leituras da C21. A preparação levou 171 s em cada uma.

- **Correção da nota anterior.** A espera da semente pelo autovacuum não foi o que fez o lote 1 passar na segunda execução de 2026-11-07 (97 ms). Ela garante o plano de índice do expurgo, não o primeiro lote. O excesso do lote 1 sobre os demais é custo de primeiro uso da sessão de retenção.
- **Efeito do aquecimento (Passo 25).** Pequeno. O lote 1 continua cerca de 40 ms acima dos demais (94 a 100 ms contra cerca de 55 ms), e a folga para o limite de 100 ms ficou entre 0 e 6 ms. A conexão já era aberta antes do lote 1 pela leitura do corte (`select now() - ...`), e o `EXPLAIN` mais a leitura do índice não disparam os gatilhos de linha nem as verificações de chave estrangeira do `DELETE`. O que sobra é, com mais probabilidade, essa primeira execução por sessão (hipótese, não medida à parte).
- **Critério do plano.** As três execuções tiveram todos os lotes até 100 ms, com o limite inalterado. A folga de 0 ms na execução 1 mantém o risco de instabilidade apontado na revisão.
- **Falha nova da execução 2.** O p95 do `evaluate` durante o expurgo subiu uma faixa do histograma (22,4 ms para 33,6 ms), acima dos 10% permitidos sobre a primeira janela (NFR2.1). O limite absoluto de 250 ms ficou longe. O limite não mudou.
