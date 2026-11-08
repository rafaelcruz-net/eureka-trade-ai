<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-11-06T20:30:00Z — Loop-back 1: execução reprovada (2 falhas no Failsafe, sementes ou conferências de carga da U5 e da U6, rastreabilidade entre Units). A geração de código foi reaberta para u2, u4, u5, u6, u8 e u9; o diagnóstico e o impacto estão em `test-results.md`. `scripts/perf-audit.sh` não rodou porque a proteção do ambiente recusa o script; a execução fica com a pessoa.

- 2026-11-07T15:47:00Z — Loop-back 2: a segunda execução teve o backend e o frontend verdes. Os cenários de carga `u5` (fatias, decisões `STALE_DATA`, barreira sem dados) e `u6` (primeiro lote do expurgo com 106 ms) reprovaram. A geração de código foi reaberta para u5 e u6; o diagnóstico e o impacto estão em `test-results.md`. O comando de instalação do frontend em `build-instructions.md` foi corrigido no próprio estágio.

- 2026-11-08T00:55:00Z — Loop-back 3 (última automática): a terceira execução teve o backend verde (906 e 760 testes, 93,92%) e a carga `u5` aprovada. O teste do assistente S4 no `apps/web` estourou o tempo de 5 s na suíte completa (3 de 3; passa sozinho), e 1 das 3 cargas `u6` reprovou pela regra de 10% sobre p95 lido do histograma, com o lote 1 do expurgo no limite. A geração de código foi reaberta para u5 e u6; o diagnóstico e o impacto estão em `test-results.md`.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-11-06T18:00:00Z — pendência da pessoa: acrescentar `e2e/u9-dashboard` à linha do `playwright test` em `scripts/verify.sh` (arquivo protegido; ela não está no computador agora). Até lá o smoke da U9 não roda no comando de verificação e o alvo dele fica `Unverified`.
- 2026-11-06T18:00:00Z — decisão da pessoa: `DashboardOpenLatencyIT` (NFR3.1 da U9, p95 ≤ 2 s) passa a ser medição informativa, fora do portão de CI; o requisito em `u9-dashboard/nfr-requirements/performance-requirements.md` ainda diz portão e precisa ser atualizado.
