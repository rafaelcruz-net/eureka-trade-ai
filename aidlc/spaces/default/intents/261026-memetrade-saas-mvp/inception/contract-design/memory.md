<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

- 2026-10-27T13:21:21Z — tratei as interfaces Java entre módulos como contratos de esquema compartilhado, ao lado do OpenAPI e do AsyncAPI; num monólito modular, essas interfaces são as fronteiras reais entre as Units de backend.
- 2026-10-27T13:21:21Z — a criação de conta por convite ficou na mesma rota de verificação do login (C1) com inviteCode e versão dos termos, para manter "conta, carteira, convite e aceite numa única operação" (AC1.1.3).

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-27T13:21:21Z — a escolha de auditoria (Q9 B, Q15 B) diverge de "exatamente um evento" de FR2.3 e DP-2; registrei como questão aberta OQ-1 em vez de ajustar o requisito nesta etapa.

- 2026-10-27T14:19:39Z — com o papel ADMIN restrito à entrada administrativa (Q20), o smoke do esqueleto não pode mais entrar com a carteira de administrador pela API do convidado; registrei como OQ-8 a conta BETA_USER semeada só no perfil de teste.

## Tradeoffs
- 2026-10-27T14:19:39Z — o painel passou de eventos em processo para leitura periódica de marcas de alteração (Q19); custa uma consulta leve a cada 2 s por canal aberto, em troca de funcionar para gravações feitas no worker sem broker.
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

- 2026-10-27T13:21:21Z — token na memória com renovação por cookie (Q11 C, Q13 B) obriga a API e os dois apps a ficarem no mesmo site por causa de SameSite=Strict, e o canal do painel a usar fetch em vez de EventSource (Q14 A).

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-27T13:21:21Z — eventos em processo não atravessam do worker para a API (OQ-2); decidir o mecanismo do painel no NFR Design.
