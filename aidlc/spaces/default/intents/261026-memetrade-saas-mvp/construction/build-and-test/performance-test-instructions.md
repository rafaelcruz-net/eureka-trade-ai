# Instruções de testes de desempenho

Conversa: português (Brasil). As metas numéricas vêm de `construction/<unit>/nfr-requirements/performance-requirements.md`.

## Ferramentas

| Ferramenta | O que mede | Onde roda |
|---|---|---|
| `scripts/load-test.sh` + `scripts/load/load-test.mjs` | p95 das rotas, memória dos contêineres, espera do pool, planos sem `Seq Scan`, tarefas em segundo plano | local; `.github/workflows/load-test.yml` sob demanda |
| `scripts/perf-audit.sh` | U2: consulta da trilha com cerca de 37 milhões de eventos, expurgo e entrega | só local (cerca de 30 GB de disco, portas 5432/8080/8081/3000 livres) |
| Testes `*LatencyIT` / `*DelayIT` | medições pequenas no Failsafe (U9 NFR1.1 e NFR3.1) | `mvnw verify` |

## Cenários de carga

```bash
LOAD_SCENARIO=<cenário> SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh
```

| Cenário | Unit | Duração | Relatório |
|---|---|---|---|
| `u1` | U1 | 300 s | saída JSON |
| `u3` | U3 | — | `docs/perf/u3-identity-access-<data>.md` |
| `u4` | U4, 2 000 tokens | 600 s | `docs/perf/u4-discovery-<data>.md` |
| `growth` | U4, 10 000 tokens (sem limite de reprovação; resultado anotado) | 600 s | `docs/perf/u4-discovery-<data>-growth.md` |
| `u5` | U5, 60 bots, 20 000 decisões por bot | 1 800 s | `docs/perf/u5-bots-decision-<data>.md` |
| `u6` | U6, 270 000 recusas, expurgo em 300 s | 600 s | `docs/perf/u6-risk-control-<data>.md` |
| `u7` | U7, 200 000 preenchimentos, 20 leitores | 600 s | `docs/perf/u7-ledger-pnl-<data>.md` |
| `u7-jobs` | U7, tarefas de marcação, fechamento e reconciliação | 1 800 s | mesma página, seção `u7-jobs` |

Cada cenário devolve uma linha JSON com `passed` e `failures`, e a saída é diferente de zero quando ele reprova (exceto `growth`).

## Regressão

Um cenário que antes passava e passa a reprovar é tratado como defeito. As metas não podem ser reduzidas para fazer o cenário passar.

## Resultado das execuções (2026-11-06 e 2026-11-07)

Em 2026-11-07, só `u5` e `u6` rodaram de novo. A volta 1 não mudou código medido pelos demais cenários.

| Cenário | Resultado | Principais números |
|---|---|---|
| `u1` | aprovado | p95 desafio 45 ms, verificação 224 ms, lista 45 ms |
| `u3` | aprovado na segunda execução | verificação com convite 259 ms, convite 4 ms, conta 7 ms; a primeira execução não subiu a API (ver `build-instructions.md`) |
| `u4` | aprovado | lista 500 ms, detalhe 11 ms, postgres 825 MB |
| `growth` | metas não mantidas | lista 10,02 s, postgres 990 MB, detalhe 39 ms: decisão de escala antes do G1 (U4 NFR3.5) |
| `u5` | **falhou na semente** (2026-11-06); **reprovado** (2026-11-07) | 2026-11-06: `DataIntegrityViolationException` em `insert into decision`. 2026-11-07: semente 400 000 sem erro; ciclo 134 ms; rotas até 28 ms; tela S5 26 ms; canal 1,9 s; falhas: barreira mais execução sem dados, 200 decisões `STALE_DATA`, 5 fatias para 6 esperadas (`docs/perf/u5-bots-decision-2026-11-07.md`) |
| `u6` | **reprovado** pela conferência de plano (2026-11-06); **reprovado** pelo primeiro lote do expurgo (2026-11-07) | 2026-11-06: o `Seq Scan` vinha da subconsulta de amostra do próprio script. 2026-11-07: planos sem `Seq Scan`; avaliação 22 ms; C21 11 ms; primeiro lote do expurgo 106 ms (limite 100 ms), os demais de 39 a 60 ms (`docs/perf/u6-risk-control-2026-11-07.md`) |
| `u7` | aprovado | execute 34 ms, close 62 ms, ledger 201 ms, reconciliação 25,4 s |
| `u7-jobs` | aprovado | marcação até 317 ms, fechamento até 4 ms, reconciliação até 236 ms |
| `perf-audit.sh` | não executado | a proteção do ambiente recusa rodar o script pelo agente; a pessoa roda localmente |
