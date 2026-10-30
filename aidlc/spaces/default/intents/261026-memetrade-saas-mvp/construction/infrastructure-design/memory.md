<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-27T16:05:00Z — [unit u1-walking-skeleton] a revisão mostrou que o CodeQL escolhido na Q3 do NFR Requirements exige licença paga em repositório privado; a pessoa trocou por Semgrep Community Edition (Q9), e o tech-stack-decisions.md da U1 fica desatualizado nesse ponto.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-27T15:50:00Z — [unit u1-walking-skeleton] imagens descartadas depois do smoke (Q6): nenhum custo de registro enquanto não há ambiente, mas a primeira implantação vai precisar acrescentar a publicação no registro.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-27T15:50:00Z — [unit u1-walking-skeleton] a cota gratuita de minutos do GitHub Actions ainda precisa ser confirmada frente a cinco jobs paralelos por pedido de merge (team.md, Sprint 0).
