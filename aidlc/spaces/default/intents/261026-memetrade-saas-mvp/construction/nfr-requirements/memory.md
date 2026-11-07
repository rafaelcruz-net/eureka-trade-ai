<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-27T15:19:14Z — [unit u1-walking-skeleton] a escolha do SDK Solana (Q4 C) conflitava com a proibição de APIs de geração e importação de chave na produção; a pessoa resolveu na Q8 deixando o SDK só nos testes, e a produção confere a assinatura com Ed25519 nativo.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-11-06T13:41:41Z — [unit u9-dashboard] o desenho funcional citava um smoke em que a ordem nova aparece sem recarregar, mas não há rota de teste que grave ordem e o ciclo pode dar NO_TRADE; a Q5 trocou a prova ponta a ponta por uma pausa feita em outra aba, que é determinística.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-27T15:19:14Z — [unit u1-walking-skeleton] contadores do limite de tentativas na memória do processo (Q2) simplificam o esqueleto, mas obrigam a migrar para armazenamento compartilhado antes da segunda instância de API (NFR3.5).

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-11-06T13:41:41Z — [unit u9-dashboard] a segunda revisão do desenho funcional apontou que a busca da última ordem percorre todos os bots que não são rascunho, inclusive encerrados; com muitos encerrados isso pesa na abertura. A meta de p95 ≤ 2 s por quadro (Q1) vai mostrar na medição se é preciso limitar essa busca aos bots em uso.
- 2026-11-06T13:46:00Z — [unit u9-dashboard] a revisão aprovou com pendências para o NFR Design: o teto de 5 canais por pessoa (NFR2.1) não existe no canal da U5 e precisa de dono, recusa definida e teste, ou sair da tabela de ameaças; o teste de `EventSource` deve ignorar comentários e testes (ou virar regra de lint); o critério de armazenamento deve procurar só o token, porque a U3 usa `sessionStorage`; NFR6.6 deve cobrir `auth.expired` e `401` repetido; a abertura do canal deve mandar `X-Correlation-Id`; e a medição de atraso de ordem deve usar os acionadores do perfil `loadtest`.
