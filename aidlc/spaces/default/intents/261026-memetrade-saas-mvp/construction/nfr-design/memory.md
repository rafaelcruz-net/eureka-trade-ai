<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-27T15:40:00Z — [unit u1-walking-skeleton] a coleta simulada roda no processo de API na U1, porque o worker só nasce na U5 (C20); com mais de uma instância ela precisa de executor único.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-27T15:40:00Z — [unit u1-walking-skeleton] limite de tentativas configurável com padrão 10 e elevado só no perfil de carga (Q2): mede login sob carga real, ao custo de um teste que guarda o valor padrão.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-27T15:40:00Z — [unit u1-walking-skeleton] a credencial de CORS na verificação (Q7) diverge do contrato C1 atual; a atualização do contrato fica para a geração de código da U1.
