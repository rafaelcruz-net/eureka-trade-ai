<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-26T14:10:00Z — Resolver nos requisitos os pontos abertos aceitos dos mockups (lista de bots, edição de limites, encerramento com posições, sessão expirada com bot ativo, rótulo do risco) com critérios explícitos, marcando a origem como revisão dos mockups, em vez de deixá-los como perguntas.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-26T14:10:00Z — Tornar mensuráveis o tempo de resposta (p95 ≤ 2 s) e o contraste (4,5:1) como premissas explícitas, em vez de perguntar mais, para cumprir a regra de requisitos testáveis sem alongar a entrevista.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
