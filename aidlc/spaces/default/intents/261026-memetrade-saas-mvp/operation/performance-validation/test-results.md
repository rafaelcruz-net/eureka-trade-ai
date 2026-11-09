# Resultados de desempenho

Conversa: português (Brasil). Nesta etapa, nenhuma carga nova foi rodada (resposta 1A). Os resultados abaixo consolidam as cargas do Build and Test (`construction/build-and-test/test-results.md` e `performance-test-instructions.md`) para a release `v2026.11.08-1` (`8fd7a7b`). Elas valem para essa release porque ela só muda o `postcss` do frontend em relação a `0cd9ccf`. As metas vêm dos `performance-requirements.md` e `scalability-requirements.md`; o desenho, dos `performance-design.md` e `scalability-design.md`; a coleta, das métricas de `operation/observability-setup/dashboards.md`.

## Ambiente das medições

Notebook Windows com Docker Desktop. Contêineres com 1 GB (postgres, api, worker) e 512 MB (web, admin). Dados sintéticos e fonte de mercado simulada. Não é um ambiente parecido com produção. Sem CloudWatch nem X-Ray: a evidência são as métricas Prometheus, os relatórios dos scripts e `docs/perf/`.

## Cenários

| Cenário | Data | Resultado | p95 principais | Observação |
|---|---|---|---|---|
| `u1` | 2026-11-06 | aprovado | desafio 45 ms; verificação 224 ms; lista 45 ms | 20 usuários |
| `u3` | 2026-11-06 | aprovado (2ª execução) | verificação com convite 259 ms; convite 4 ms; conta 7 ms | a 1ª não subiu a API |
| `u4` | 2026-11-06 | aprovado | lista 500 ms; detalhe 11 ms | postgres 825 MB |
| `growth` | 2026-11-06 | **metas não mantidas** | lista 10,02 s; detalhe 39 ms | postgres 990 MB; U4-NFR3.5 |
| `u5` | 2026-11-07 | aprovado | ciclo 112 ms; barreira mais execução 22 ms; S5 27 ms; canal 1,9 s | 5 de 5 fatias; 216 ordens; 0 `STALE_DATA` |
| `u6` (3 cargas) | 2026-11-08 | aprovado nas 3 | `evaluate` sem/com expurgo 21,30/16,90, 16,64/16,61, 21,93/16,09 ms; lote máximo 58 ms | planos sem `Seq Scan` |
| `u7` | 2026-11-06 | aprovado | `execute` 34 ms; `close` 62 ms; ledger 201 ms; reconciliação 25,4 s | 60 bots |
| `u7-jobs` | 2026-11-06 | aprovado | marcação até 317 ms; fechamento até 4 ms; reconciliação até 236 ms | 60 rodadas |
| `perf-audit` (U2) | 2026-11-08 | **não terminou** | — | interrompido por falta de memória no notebook |

## Smokes da release

`verify.sh` 10/10 em `0cd9ccf` e duas vezes em `8fd7a7b`. Inclui as metas de tela: S3 em até 2 s e as telas de U3, U4, U5, U7, U8 e U9 (`operation/deployment-execution/smoke-test-results.md`).

## Conclusão

- Desempenho do beta com 20 usuários e 60 bots: **dentro das metas** em todas as Units medidas.
- **Aberto:** U4-NFR3.5 (crescimento, Not Met) e U2-NFR3.1, NFR3.3 e NFR4.2 (Unverified). Os dois fecham no staging antes do G1 (`load-test-plan.md`), sob responsabilidade do responsável do projeto.
- Nenhuma meta foi afrouxada.
