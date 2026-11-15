# Perguntas de Functional Design — U3 `u3-real-risk`

Conversa: português (Brasil). Base:
- `inception/units-generation/unit-of-work.md` (U3) e `inception/units-generation/unit-of-work-story-map.md` (US2.1, US6.1, US6.2 e a parte de recusa da US6.3);
- `inception/user-stories/stories.md` (AC2.1.1 a AC2.1.4, AC2.2.2, AC6.1.1 a AC6.1.6, AC6.2.1 a AC6.2.3, AC6.3.2 e AC6.3.3);
- `inception/requirements-analysis/requirements.md` (FR6.1 a FR6.4, FR2.3, FR3.2, FR1.3, FR1.4, NFR2, NFR8, NFR10);
- `inception/contract-design/contract-summary.md` (M1, M2, M4, M5);
- o código atual: `riskcontrol` com a barreira PAPER `OrderGate`, `ledger.api.RealLedger` com `record` e `balance` e `botwallet` com `Preferences`, `PauseReason` e `OperationMode`.

## Q1. [Teto] Como o teto é conferido numa compra (AC2.1.3, AC2.1.4, AC2.2.2)?

A. Valor sob gestão = posições abertas a preço de cotação (posição com preço `unknown` vale pelo custo de compra) + compras aprovadas ainda em andamento. Uma compra passa só se o valor sob gestão mais o valor da compra não passar do teto vigente e se o valor da compra couber no saldo livre menos a reserva para taxas. O saldo acima do teto nunca é usado. Com o teto abaixo da exposição, as compras são recusadas com `REAL_CAP_EXCEEDED`, as vendas continuam e nada é vendido à força.
B. Conferir só o saldo livre contra o teto, sem contar as posições
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Limites] A que ordens os limites se aplicam (AC6.1.1, AC6.1.3)?

A. Os limites valem só para compras: uma compra acima de 10% do teto vigente é recusada (`REAL_LIMIT_EXCEEDED`), e a 21ª compra aprovada do mesmo bot no dia UTC também. Compras que falharam ou ficaram ambíguas contam, porque foram aprovadas. As vendas de saída não contam e não são limitadas, como diz a M5 ("vendas de saída sempre passam").
B. Os limites valem para compras e vendas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Perda do dia] Como medir a perda do dia (AC6.1.2)?

A. Um trabalho logo depois de 00:00 UTC grava, por carteira do bot liberada, uma foto do dia com o valor da carteira (saldo livre + posições a preço de cotação, ou a custo com preço `unknown`) e o teto vigente. A foto também é gravada na primeira avaliação do dia, se ainda não existir. Perda do dia = valor da foto + depósitos − saques do dia − valor atual; as taxas entram porque já saíram do saldo. Base = o menor entre o teto da foto e o teto atual. Com a perda maior ou igual a 20% da base, a compra é recusada e a carteira pausa com o motivo `DAILY_LOSS` pela M1. O mesmo trabalho de 00:00 UTC retoma só essa pausa.
B. Somar só a perda realizada do ledger
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Trava] Como conferir sob trava (AC6.1.4, AC6.1.5)?

A. A avaliação roda na transação do ciclo do bot, com uma trava por carteira do bot no PostgreSQL (`pg_advisory_xact_lock`). Cada compra aprovada vira uma reserva da barreira (`OPEN`), numa tabela da U3, que conta no teto, no saldo e no número de ordens. A U4 chama a M5 `handOver(approvalId)` na mesma transação em que grava o `RESERVE` no ledger, e a reserva da barreira passa a `HANDED_OVER`. Se a decisão não virar ordem, a reserva vence sozinha em 5 minutos (`EXPIRED`). Uma ordem ambígua fica com o `RESERVE` do ledger até terminar.
B. Sem reserva própria: só a trava, contando o que já está no ledger
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Tokens] Quais tokens podem ser comprados (AC6.2.1, AC6.2.3)?

A. Só tokens com avaliação do scanner disponível (`RiskAssessmentQuery`), com cobertura suficiente e sem campo de segurança `unknown` contado como seguro. Um token na lista de bloqueio é recusado com `TOKEN_NOT_ELIGIBLE`. A lista de bloqueio é da U3 (tabela própria, por rede e mint, com motivo, quem bloqueou e quando), mudada pela M5 `blockToken` e `unblockToken` (que a U5 usa). A venda de um token bloqueado ou sem avaliação continua permitida, para o usuário poder sair.
B. Também recusar a venda de token bloqueado
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Posição sem preço] O que fazer com posição de preço `unknown` (AC6.2.2)?

A. Se qualquer posição aberta da carteira do bot estiver com preço `unknown` ou vencido (mais de 60 s), toda nova compra é recusada com `STALE_DATA`, com o mint da posição no detalhe, até o preço voltar. As vendas continuam.
B. Recusar só compras do mesmo token
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Frescor e fonte] Como recusar por dado vencido ou fonte simulada (AC6.3.2, AC6.3.3)?

A. Com a fonte ativa simulada (M4 `activeSourceKind()`), toda ordem REAL é recusada, inclusive venda, com `SIMULATED_SOURCE_ACTIVE`. Na compra, preço, liquidez ou volume do token com mais de 60 s (pelo `observedAt` da M4) ou `unknown` levam a `STALE_DATA`. A cotação de 10 s é da Jupiter e é conferida pela U4 antes de assinar, não pela U3. A venda não é recusada por dado vencido.
B. Recusar também a venda por dado vencido
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Pausa e estado] Quando a barreira recusa por estado da carteira?

A. Pela M1 `readRealState`: sem liberação vigente, com o REAL desligado ou sem carteira do bot, toda ordem é recusada com `REAL_PAUSED`. Com qualquer pausa ativa (`USER`, `DAILY_LOSS`, `ADMIN`, `DIVERGENCE`, `KILL_SWITCH`, `UPSTREAM`), as ordens do bot são recusadas, compra ou venda ("pausa do bot impede novas operações"). A venda pedida pelo usuário em "vender tudo e sacar" (origem `USER_LIQUIDATION`) passa nas pausas `USER`, `DAILY_LOSS`, `ADMIN` e `UPSTREAM`, mas não em `KILL_SWITCH` nem em `DIVERGENCE`, porque com elas as posições ficam como estão.
B. Pausa bloqueia só compras
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Ordem das conferências] Em que ordem e com qual motivo?

A. A ordem fixa é: estado (`REAL_PAUSED`), fonte (`SIMULATED_SOURCE_ACTIVE`), token (`TOKEN_NOT_ELIGIBLE`), frescor (`STALE_DATA`), limite por ordem e número de ordens (`REAL_LIMIT_EXCEEDED`), perda do dia (`REAL_LIMIT_EXCEEDED` com detalhe `DAILY_LOSS`) e teto (`REAL_CAP_EXCEEDED`). A primeira recusa encerra a avaliação. O detalhe traz os números usados (em lamports) e nunca dados de outro usuário.
B. Avaliar todas e devolver a lista de motivos
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Registro] Onde ficam as avaliações e recusas?

A. Em tabelas próprias do REAL (`real_risk_evaluation` e as reservas), separadas das tabelas PAPER, sempre filtradas por tenant. Cada recusa gera um evento de auditoria REAL e conta na métrica `real_risk_rejections_total{code}`, sem mint nem endereço nos rótulos. As avaliações seguem a mesma retenção das avaliações PAPER.
B. Reaproveitar as tabelas PAPER com uma coluna de modo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Leituras que faltam] Como a U3 lê a carteira e o ledger?

A. A U3 acrescenta as leituras que a M1 e a M2 já preveem e que a U1 ainda não expôs: em `botwallet.api`, `RealStateQuery.readRealState` e `pauseUser`/`resumeUser` (motivo `DAILY_LOSS`); em `ledger.api`, `RealLedger.positions` (mint, quantidade em unidades base e custo em lamports) e `RealLedger.flowsSince` (depósitos e saques desde um instante). Os nomes seguem o contrato M1/M2, e a mudança entra no mesmo commit, como manda a regra das interfaces de módulo.
B. A U3 lê as tabelas da BotWallet e do ledger direto
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q12. [Dinheiro] Que unidades e arredondamento?

A. Tudo em lamports (inteiro) e unidades base do token (inteiro); o valor de posição a preço de cotação é calculado em decimal exato e arredondado para baixo em lamports. Os percentuais são em pontos-base (10% = 1000, 20% = 2000), e a conta de limite arredonda o limite para baixo, ou seja, contra a compra. Nunca ponto flutuante.
B. Valores em SOL com decimais
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Teto sobre posições (a cotação, ou a custo se `unknown`) mais compras em andamento; o excedente acima do teto nunca é usado; teto abaixo da exposição bloqueia só compras [Q1].
- Limites só para compras: até 10% do teto por ordem e 20 compras aprovadas por bot no dia UTC; vendas de saída livres [Q2].
- Perda do dia pela foto da carteira às 00:00 UTC, com depósitos e saques descontados; ao chegar a 20% do menor teto do dia, pausa `DAILY_LOSS`, retomada no dia seguinte [Q3].
- Trava por carteira do bot e reserva da barreira até a U4 gravar o `RESERVE` no ledger (`handOver`), com vencimento em 5 min [Q4].
- Só tokens com avaliação e cobertura suficiente; lista de bloqueio da U3 mudada pela M5; venda sempre possível [Q5].
- Posição com preço `unknown` ou vencido bloqueia novas compras [Q6].
- Fonte simulada recusa toda ordem; dado de sinal com mais de 60 s recusa a compra; a cotação de 10 s fica na U4 [Q7].
- Pausa ou falta de liberação recusa as ordens do bot; "vender tudo e sacar" passa, exceto com kill switch ou divergência [Q8].
- Ordem fixa das conferências, a primeira recusa encerra [Q9].
- Tabelas REAL próprias, auditoria e métrica por código [Q10].
- A U3 acrescenta as leituras da M1 e da M2 que faltam [Q11].
- Lamports, unidades base e pontos-base, arredondando contra a compra [Q12].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
