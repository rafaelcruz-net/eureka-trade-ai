<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-27T14:33:53Z — combinei a escolha mista de Q1 com o WSJF de Q2 usando Q1 para definir as fases e o WSJF para ordenar dentro de cada fase; a escolha mista responde à maior preocupação (Q6), e o WSJF desempata.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-27T14:33:53Z — U4 ficou antes de U3 apesar da nota WSJF ligeiramente menor (3,6 contra 3,7), porque a U4 destrava todo o núcleo financeiro.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-27T14:33:53Z — U9 (painel) ficou antes de U3 e U8 pelo WSJF, embora Q1 citasse "U3, U8, U9" nessa ordem; a fase é a mesma e o WSJF decide dentro dela.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-27T14:33:53Z — a decisão de moeda e escala (OQ-3) precisa sair antes do design funcional do B4.
