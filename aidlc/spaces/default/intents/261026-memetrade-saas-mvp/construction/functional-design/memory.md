<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] mensagem alterada ou assinante diferente consome o nonce, para que ninguém tente de novo com o mesmo desafio; a recusa por prazo marca o desafio como expirado.
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] AC1.4.3 e AC1.4.4 ficaram como N/A na U1 porque ainda não existem tarefas em segundo plano nem registros financeiros; a regra BR2.3 obriga os testes quando a U5 e a U7 os criarem.
- 2026-11-05T03:25:18Z — [unit u6-risk-control] o drawdown é comparado em USDC contra o piso (maior patrimônio × (1 − D)), não em percentual arredondado; com 4 casas, o percentual deixaria passar o menor incremento de 0,000001 USDC exigido pela convenção de fronteiras.
- 2026-11-05T03:25:18Z — [unit u6-risk-control] a rota C21 responde 404 só quando a U6 já tem avaliação do bot gravada com outro dono; sem consultar a U5, um bot que nunca passou pela barreira devolve lista vazia, sem vazar dado.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] a entrada do administrador (AC1.2.6) saiu da U1 para a U8, seguindo o Contract Design (Q20) e a Q1 desta Unit.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-27T14:58:56Z — [unit u1-walking-skeleton] o mecanismo comum de idempotência nasce no módulo de identidade da U1, onde está a primeira rota que muda estado; as demais Units o usam por interface em vez de cada uma ter o seu.
- 2026-11-05T03:25:18Z — [unit u6-risk-control] o slippage estimado copia os parâmetros da política v1 da U7 em vez de pedir uma operação nova à U7 (Q5); evita reabrir a U7 entregue, ao custo de manter as duas contas iguais por um teste de conferência.
- 2026-11-05T03:25:18Z — [unit u6-risk-control] o contrato C14 mudou de um gatilho de pausa opcional para um conjunto (Q8) enquanto a U5 ainda não existe; mudar depois exigiria alterar o consumidor junto.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
