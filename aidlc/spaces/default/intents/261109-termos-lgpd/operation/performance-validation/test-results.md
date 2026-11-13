# Resultados de desempenho

Conversa: português (Brasil). Resposta 3A: cargas rodadas localmente em 2026-11-11, no Build and Test (`construction/build-and-test/test-results.md`), sobre o código de produção da release `v2026.11.12-1`. Relatórios gravados em `docs/perf/` (`u1-legal-documents-2026-11-11.md`, `u3-identity-access-2026-11-11.md`, `u3-reacceptance-2026-11-11.md`, `u4-data-requests-2026-11-11.md`).

## Cargas

| Cenário | Requisições | Erros | Resultado (p95) | Meta | Passou |
|---|---|---|---|---|---|
| `legal` | 1500 páginas + 1500 API, 5 min | 0 5xx, 0 de rede | páginas 146 ms; API 14 ms | 2 s; 300 ms | sim |
| `u1` | 20 usuários, 5 min | 0 | challenge 39 ms; verify 134 ms; lista 56 ms | verify 300 ms | sim |
| `u3` | 20 contas criadas, 5 min | 0; 0 `429` | com convite 224 ms; consulta do convite 4 ms; Conta 6 ms | 500 ms; 300 ms | sim |
| `reacceptance` (4ª rodada, depois do Loop-back 1) | 20 pessoas travadas, aceitas e liberadas | 0 | aceite 45 ms; trava 4,2 ms; espera de conexão ≈ 1 ms | 500 ms; 5 ms | sim |
| `data-requests` | 2980 do convidado + 298 do administrador | 0 inesperados | convidado 23 ms; administrador 16 ms | 500 ms; 1 s | sim |

Rodadas anteriores do `reacceptance` (16,8, 8,4 e 7,0 ms na trava) levaram ao Loop-back 1: pool de conexões fixo e aquecimento da consulta na subida.

## Recursos

| Cenário | Memória máxima | Pool |
|---|---|---|
| `u1` | `api` 328 MB; `postgres` 131 MB | 5 pendentes no máximo, nenhuma espera longa |
| `u3` | `api` 326 MB; `postgres` 101 MB | nenhuma espera |

Limite de memória da `api` no Compose: 1 GB.

## Análise de gargalos

| Ponto | Situação |
|---|---|
| Conexões na subida | resolvido: a rajada fria abria até 15 conexões dentro da trava; pool fixo e aquecimento levaram a trava de 16,8 para 4,2 ms. Folga pequena: alarme `TERMS_CHECK_SLOW` |
| Anonimização com contenção | dentro da meta na suíte; com contenção, a espera de trava pode chegar a 2 s (`lock_timeout`), contada no timer |
| Expurgo da trilha em volume real | não medido: `perf-audit.sh` pendente |
| Plano de consulta | sem varredura sequencial no histórico (`u1`) e nas consultas dos expurgos, pedidos e anonimização (`EXPLAIN` na suíte) |

## Capacidade

Com o perfil do beta (20 convidados), todas as rotas deste intent ficam abaixo de 25% da meta, exceto a trava (84% da meta). Recomendação: manter `DB_POOL_MAX_SIZE` em 20 por réplica e medir a trava de novo na AWS antes do G1.
