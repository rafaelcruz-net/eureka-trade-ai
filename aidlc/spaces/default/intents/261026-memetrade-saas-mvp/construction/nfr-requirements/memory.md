<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-27T15:19:14Z — [unit u1-walking-skeleton] a escolha do SDK Solana (Q4 C) conflitava com a proibição de APIs de geração e importação de chave na produção; a pessoa resolveu na Q8 deixando o SDK só nos testes, e a produção confere a assinatura com Ed25519 nativo.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-27T15:19:14Z — [unit u1-walking-skeleton] contadores do limite de tentativas na memória do processo (Q2) simplificam o esqueleto, mas obrigam a migrar para armazenamento compartilhado antes da segunda instância de API (NFR3.5).

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
