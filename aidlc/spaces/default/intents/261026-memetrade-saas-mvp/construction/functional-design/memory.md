<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] mensagem alterada ou assinante diferente consome o nonce, para que ninguém tente de novo com o mesmo desafio; a recusa por prazo marca o desafio como expirado.
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] AC1.4.3 e AC1.4.4 ficaram como N/A na U1 porque ainda não existem tarefas em segundo plano nem registros financeiros; a regra BR2.3 obriga os testes quando a U5 e a U7 os criarem.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] a entrada do administrador (AC1.2.6) saiu da U1 para a U8, seguindo o Contract Design (Q20) e a Q1 desta Unit.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] o mecanismo comum de idempotência nasce no módulo de identidade da U1, onde está a primeira rota que muda estado; as demais Units o usam por interface em vez de cada uma ter o seu.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
