## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-16
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | construction/u6-real-modes-ui/functional-design/rules.md > BR3.2 (relógio via cabeçalho `Date`) | A API e o app web estão em origens diferentes (não existe proxy: `apps/web/src/app/api` só tem `health`). `GuestCorsPolicy` expõe apenas `X-Correlation-Id` e `Retry-After` (e `ETag` no legal). `Date` não é um cabeçalho de resposta seguro para CORS, então `response.headers.get('Date')` devolve `null` no navegador e a correção de relógio não funciona. O adendo de contrato só fala de `release` e `capSetBy`. | Escolher e registrar uma saída: (a) acrescentar `Date` a `setExposedHeaders` da política CORS do convidado, como segunda mudança de servidor no adendo, com teste; ou (b) usar um instante do corpo da resposta (por exemplo `asOf`). Dizer também o que a contagem mostra se a medida falhar (cair para o relógio local, com o servidor decidindo). | Resolved (reviewer) |
| R-02 | Major | rules.md > BR3.3, functional-spec.md > F2 e S14 | O código `409 SIGNING_EXPIRED` não existe. `ErrorCode.java` tem `SIGNING_DEADLINE_EXPIRED` (409), e o POST também devolve `SIGNED_MESSAGE_MISMATCH` (422) e `SIGNING_POLICY_REFUSED` (422). Essas duas recusas, e a ordem que já saiu de `QUOTED` (409), não têm texto nem cenário. | Trocar para `SIGNING_DEADLINE_EXPIRED`. Acrescentar regra e cenário para `SIGNED_MESSAGE_MISMATCH`, `SIGNING_POLICY_REFUSED` e ordem já não assinável, com o texto que a pessoa vê e o que o navegador descarta. | Resolved (reviewer) |
| R-03 | Major | rules.md > BR5.2b, functional-spec.md > F4 e S11; entities.md > `RealPreferencesRequest` | `PUT /api/v1/real/bot-wallet/preferences` não existe. O contrato usa `PATCH` (`changeRealPreferences`). A CORS do convidado só permite `PUT` nas rotas bearer, e o `PATCH` não consta em `setAllowedMethods`. O corpo também carrega `maxSlippageBps` e `paused`, que a U6 não trata. | Corrigir o verbo para `PATCH` e conferir se a CORS aceita `PATCH`; se não aceitar, registrar a mudança de servidor no adendo. Dizer que a tela envia só `capLamports` (mais `challengeId` e `signature` ao subir). | Resolved (reviewer) |
| R-04 | Major | rules.md > BR4.2, BR5.2b, BR6.2; functional-spec.md > S17, "Sessão do login expirada" | A regra "mesma `Idempotency-Key` em toda tentativa" colide com o `IdempotencyService`: a mesma chave com conteúdo diferente devolve `409 IDEMPOTENCY_CONFLICT` (`DIFFERENT_CONTENT`). No consentimento, o desafio expirado (`CONSENT_EXPIRED`) ou inválido exige novo `challengeId` e nova assinatura, ou seja, corpo novo. Reusar a chave trava a pessoa. O `IN_PROGRESS` (mesmo corpo) é o único caso em que reusar é seguro. | Separar duas regras: reenvio idêntico (falha de rede, `IN_PROGRESS`) reutiliza a chave; novo desafio ou nova assinatura gera chave nova. Registrar o tratamento de `IDEMPOTENCY_CONFLICT`, `CONSENT_EXPIRED` e `CONSENT_INVALID` com o texto da tela. | Resolved (reviewer) |
| R-05 | Major | rules.md > adendo de contrato e BR1.1, BR1.2, BR2.5; entities.md > `BotWalletView` | Hoje `BotWalletService.view` lança `BotWalletError.NOT_FOUND` (404) quando não há carteira ativa. O adendo diz que a leitura "devolve só `release` e `network`" e que "nenhuma regra muda". Isso troca um 404 por um 200 parcial (todos os outros campos ausentes, sem `required` no esquema), e o `release` hoje não existe em `BotWalletView`. A tela da U1 (`BotWalletScreen`) e os specs da U1 dependem do 404. O texto não define como o cliente distingue "sem carteira" de "com carteira", nem como `REAL_NOT_RELEASED` aparece na leitura. | Escrever o adendo como mudança de comportamento: novo formato sem carteira (campo discriminador, por exemplo `address` ausente), efeito sobre `BotWalletScreen` e os testes da U1, e quais campos de `release` existem por estado. Ou manter o 404 e criar uma rota separada para o estado da liberação. Dizer qual leitura trata `403 REAL_DISABLED` e quando `REAL_NOT_RELEASED` aparece. | Resolved (reviewer) |
| R-06 | Major | rules.md > BR3.5, BR3.6; functional-spec.md > F2 e "Evento do canal perdido" | O código existente não sustenta o que a regra assume. (1) `apps/web/src/features/auth/wallets.ts` só expõe `signMessage`, não há `solana:signTransaction` no `WalletPort`. (2) `features/dashboard/envelopes.ts` só valida `bot.state`, `trade.recorded`, `pnl.updated`, `data.status`, `heartbeat` e `auth.expired`; um evento `real.signing.requested` é descartado. (3) A "leitura da sessão aberta" a cada troca de rota não tem rota nomeada, e o contrato só oferece `GET /orders?state=QUOTED` e depois `GET .../signing-session`. | Listar essas mudanças no escopo da U6: estender `WalletPort` com `signTransaction`; incluir `real.signing.requested` (e `real.order.state`) na validação de envelopes; nomear a consulta de recuperação (ordens `QUOTED` e a sessão de cada uma) e o que acontece com várias sessões abertas. | Resolved (reviewer) |
| R-07 | Minor | rules.md > BR6.4, BR8.3, BR9.3; functional-spec.md > Erros e casos de canto | Faltam textos para códigos reais que a tela recebe: `ORDER_AMBIGUOUS`, `WITHDRAWAL_LIMITED_BY_DIVERGENCE`, `INSUFFICIENT_FREE_BALANCE`, `WITHDRAWAL_DESTINATION_INVALID`, `CAP_ABOVE_MAXIMUM`, `ORDER_ALREADY_IN_PROGRESS`, `REAL_PAUSED`, `BOT_WALLET_ALREADY_ACTIVE`, `RELEASE_EXISTS`, `TERMS_V3_MISSING`, `NO_BOT_WALLET`, `DIVERGENCE_NOT_OPEN`. A BR9.3 cita só alguns códigos do administrador. | Acrescentar uma tabela código para texto de tela, ou dizer que o `detail` do ProblemDetail é mostrado para os não listados e quais precisam de texto próprio. | Resolved (reviewer) |
| R-08 | Minor | construction/u6-real-modes-ui/functional-design/traceability.json | Só cobre AC1.1 a AC4.1 (19 critérios). As telas de US5.x, US7.x e US8.x e US4.2 aparecem na tabela de telas, mas não no mapa de cobertura, e a verificação de que não há critério órfão não é possível. | Incluir no `upstream_ids` e no `coverage` os critérios de US4.2, US5.1, US5.2, US7.1 a US7.3 e US8.1 a US8.2, ou registrar por que ficam fora. | Resolved (reviewer) |
| R-09 | Minor | rules.md > BR1.4, BR3.4; functional-spec.md > S23 | O app já trata `403 TERMS_REACCEPTANCE_REQUIRED` globalmente (`ReacceptanceRedirect` leva a `/novo-aceite`). A exceção do saque e da leitura da carteira vale no servidor, mas a regra não diz que o handler do cliente não pode redirecionar a leitura de `GET /real/bot-wallet`, nem o `reject` nem a sessão. Também faltam estados para recusa na carteira quando o `POST .../reject` falha. | Registrar que o redirecionamento não se aplica a `GET bot-wallet` e às rotas de saque, e o que a tela mostra se `reject` falhar. | Resolved (reviewer) |
| R-10 | Major | construction/u6-real-modes-ui/functional-design/rules.md > BR1.4 e BR3.5 | O BR3.5 faz a faixa de assinatura chamar `GET /orders?state=QUOTED&limit=1` na carga e a cada troca de rota, em todas as telas. Sem o novo aceite, o servidor responde `403 TERMS_REACCEPTANCE_REQUIRED` a essa leitura, que não está entre as exceções. O BR1.4 só isenta `GET bot-wallet` e as duas rotas de saque do redirecionamento global. Na tela de depósito e saque, a faixa então redirecionaria a pessoa para o novo aceite e quebraria a exceção do saque (S23 e S42). O mesmo risco vale para o canal ao vivo se ele devolver o mesmo 403. | Dizer que, nessa tela e sem o novo aceite, a faixa não faz a releitura de ordens nem abre o canal, ou que o `403 TERMS_REACCEPTANCE_REQUIRED` vindo da faixa e do canal é ignorado em silêncio. Acrescentar o caso ao S42. | New |
| R-11 | Minor | rules.md > Resumo | O resumo ainda diz "9 grupos e 37 regras", mas a revisão acrescentou BR3.7 e BR8.8. | Atualizar a contagem. | New |
| R-12 | Minor | rules.md > BR3.2 | Não diz o que a contagem faz se `serverTime` faltar ou se a diferença for absurda. | Escrever o caso: usar o relógio local, com o servidor decidindo a expiração. | New |
| R-13 | Minor | construction/u6-real-modes-ui/functional-design/traceability.json | Ainda ficam fora do mapa critérios como AC1.2.x, AC2.2.x, AC3.3.x, AC6.x e AC8.4 e AC8.5. Eles podem pertencer a outras Units, mas nenhuma nota diz isso. | Registrar em uma linha por que esses critérios ficam fora da U6. | New |

> R-01 Reviewer note: `RealSigningSession` ganha `serverTime` no adendo e o BR3.2 usa esse campo, sem o cabeçalho `Date`. Falta só dizer o que a contagem faz se `serverTime` vier ausente (cair para o relógio local); ver a nota Minor abaixo.

> R-02 Reviewer note: O BR3.3 usa `SIGNING_DEADLINE_EXPIRED`. O BR3.7 e os cenários S38 e S39 tratam `SIGNED_MESSAGE_MISMATCH`, `SIGNING_POLICY_REFUSED` e a ordem fora de `QUOTED`.

> R-03 Reviewer note: O BR5.2b usa `PATCH`, e o adendo registra a mudança da CORS do convidado para aceitar `PATCH`.

> R-04 Reviewer note: O BR6.2 passa a ter uma chave por corpo exato. Um desafio, uma assinatura ou um valor novo gera chave nova, e `IDEMPOTENCY_CONFLICT`, `CONSENT_EXPIRED` e `CONSENT_INVALID` têm texto (BR8.8, S40).

> R-05 Reviewer note: A nova rota `GET /api/v1/real/release` carrega o estado da liberação. `GET /bot-wallet` mantém o 404 sem carteira, então a U1 não muda (BR1.1, BR1.2, BR2.5, S43).

> R-06 Reviewer note: O escopo lista o evento `real.signing.requested` em `envelopes.ts`, a releitura por `GET /orders?state=QUOTED&limit=1` e depois a sessão, e `signTransaction` no `WalletPort` (BR3.1, BR3.5, BR3.6).

> R-07 Reviewer note: A tabela do BR8.8 traz o texto dos códigos de erro do REAL, com o `detail` do ProblemDetail como reserva.

> R-08 Reviewer note: `traceability.json` passou a 36 critérios, todos existentes em `stories.md` e todos com cobertura.

> R-09 Reviewer note: O BR1.4 e o S42 excluem a tela de depósito e saque e as três chamadas do redirecionamento global, e o BR3.4 e o S41 tratam a falha do `reject`. Ver o achado Major novo sobre as chamadas da faixa nessa tela.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Conferência de `traceability.json` contra `stories.md` | 36 ids, todos existentes e todos com linha de cobertura | R-08 corrigido; restam critérios de outras histórias sem nota |
| Conferência dos códigos e rotas contra `ErrorCode.java` e os OpenAPI | `SIGNING_DEADLINE_EXPIRED`, `PATCH` e os códigos do BR8.8 existem; `GET /real/release` e `serverTime` são adendos declarados | As correções da iteração 1 se sustentam |

### Summary

As seis correções Major da iteração 1 estão resolvidas de forma implementável. Resta um Major: a releitura da faixa de assinatura pode redirecionar a tela de saque para o novo aceite, o que viola a regra de que o saque está sempre disponível. É um ponto único, de uma frase, e dentro do limite de dois Major, por isso READY. Corrigir antes da geração de código.

**Verdict:** READY
