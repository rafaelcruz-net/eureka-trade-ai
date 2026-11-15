<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-11-14T04:30:00Z — aprovado com achados abertos da revisão: campo do teto e aba Configurações, cartão REAL no painel, estados de carregando/vazio/erro em R2, R6 e A1, validação do saque, texto do MoneyConfirmDialog e lista de posições em vender tudo e sacar, detalhe do administrador (liberar, baixar teto, bloquear token, confirmar kill switch e desligar REAL, recusa de anonimização), trocar color.real para não confundir com color.warning, estados expirado e desconectado no SigningDialog, aviso de risco no modo Automática total; levar ao Functional Design de cada Unit.
