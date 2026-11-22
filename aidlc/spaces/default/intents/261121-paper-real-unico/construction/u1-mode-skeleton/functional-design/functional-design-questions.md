# Perguntas do Functional Design — U1 `u1-mode-skeleton`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `inception/units-generation/unit-of-work.md` (U1) e `unit-of-work-story-map.md`, com as linhas da U1;
- `inception/user-stories/stories.md`;
- `inception/contract-design/contract-summary.md` (C1 a C4, C8, C9, C17 e PQ-5 e PQ-7);
- `inception/refined-mockups/mockups.md` (OQ1, OQ2 e OQ6);
- `inception/domain-design/decisions.md` (ADR-005);
- o código: `BotState` (DRAFT, VALIDATED, ACTIVE, PAUSED, CLOSING_PENDING, STOPPED) e `CreateBotRequest.market`.

## Q1. Estados do bot no `BotDirectory` (PQ-7)

Como os estados do bot aparecem para o P&L?

A. Os estados se reduzem a três:

   | Estado do bot | No P&L |
   |---|---|
   | ACTIVE e CLOSING_PENDING | `ACTIVE` ("ativo") |
   | PAUSED | `PAUSED` ("pausado") |
   | STOPPED | `REMOVED` ("removido") |
   | DRAFT e VALIDATED | não entram no P&L |

   Um rascunho não opera, então não tem lote nem P&L. Um bot removido continua com os seus lotes e o seu P&L (FR3.11). (recomendado)
B. Mostrar todos os estados como estão
X. Other (please specify)

[Answer]: A

## Q2. Código da recusa (PQ-5, ADR-005)

Que código recebe a criação REAL recusada?

A. Todos os motivos de recusa da criação REAL voltam como 409 `REAL_NOT_ELIGIBLE`, com `reason` igual a um destes valores:
   - `KILL_SWITCH`
   - `REAL_DISABLED`
   - `WALLET_INACTIVE`
   - `RELEASE_MISSING`
   - `RELEASE_QUARANTINE`

   A carteira inativa, que hoje volta como 422 `VALIDATION_FAILED`, passa a usar esse código. O ADR-005 é alinhado a esse texto, e um teste de regressão cobre a mudança. Um corpo malformado continua 400 e uma regra de campo continua 422. (recomendado)
B. Manter 422 para a carteira inativa
X. Other (please specify)

[Answer]: A

## Q3. Ordem dos motivos e momento da conferência

Em que ordem os motivos valem, e quando a elegibilidade é conferida?

A. Os motivos seguem esta ordem, do mais forte para o mais fraco: kill switch, REAL desligado, carteira inativa, liberação ausente, liberação em quarentena. O servidor devolve só o motivo mais forte. A elegibilidade é conferida em dois momentos: na leitura (C2) e de novo, na mesma regra, no comando de criação. Se a fonte da elegibilidade falhar, a criação REAL é recusada com 503, e a criação PAPER segue normal. (recomendado)
B. Conferir só na tela
X. Other (please specify)

[Answer]: A

## Q4. Hora da quarentena no motivo (OQ2)

O motivo da quarentena mostra quando a liberação passa a valer?

A. Sim. O motivo `RELEASE_QUARANTINE` traz `effectiveAt`, o instante em UTC em que a liberação passa a valer. A tela mostra "Vale a partir de dd/mm HH:MM" no fuso da pessoa. (recomendado)
B. Sem a hora
X. Other (please specify)

[Answer]: A

## Q5. Casas decimais e unidade (OQ1)

Como os valores aparecem na tela?

A. Os valores REAL saem em SOL, cortando os zeros à direita sem arredondar e com pelo menos 2 casas. O valor continua exato, a partir dos lamports. Os valores PAPER saem em "US$", com 2 casas, sobre o decimal exato do ledger PAPER. As telas REAL que já existem continuam como estão neste intent; só os blocos novos usam esse formato. (recomendado)
B. 9 casas fixas no SOL
X. Other (please specify)

[Answer]: A

## Q6. Limites REAL da conta (OQ6, C9)

De onde vêm os limites REAL da conta, e como aparecem?

A. Vêm de uma leitura nova em `riskcontrol.api` que devolve quatro limites:
   - teto, em lamports;
   - tamanho máximo da ordem, em lamports e como percentual do teto, arredondado para baixo;
   - compras por dia, como contagem;
   - perda máxima do dia, em lamports e como percentual do teto, arredondado para baixo.

   Um limite sem valor sai como "não definido". Sem teto, o tamanho da ordem e a perda do dia também saem como "não definido". (recomendado)
B. Ler a tabela de preferências direto
X. Other (please specify)

[Answer]: A

## Q7. Bloco REAL na U1

Como o bloco REAL é calculado nesta Unit?

A. O P&L de cada bot REAL é calculado com o `RealPnlCalculator` que já existe, só sobre as ordens do próprio bot (`bot_id`), atrás da interface `RealBotPnlQuery`, que é da U2. O total REAL continua o cálculo de hoje. Os campos `unowned` e `prorated` vêm `null` ou vazios. A U1 não é implantada sem a U2, e o spec da U1 usa só compras simples, que fecham com o ledger. (recomendado)
B. Esconder o bloco REAL até a U2
X. Other (please specify)

[Answer]: A

## Q8. Liberação no spec de ponta a ponta

Como o spec da U1 obtém uma liberação REAL vigente?

A. Uma semente que só existe no perfil de teste grava a liberação já vigente, com o registro feito há 24 horas pelo relógio injetável do teste. A semente não existe em produção, e um teste de guarda confere isso. A quarentena de produção não muda. (recomendado)
B. Esperar 24 h
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Estados no P&L:** ativo (ACTIVE, CLOSING_PENDING), pausado (PAUSED) e removido (STOPPED). Rascunhos (DRAFT, VALIDATED) não entram [Q1].
- **Recusa da criação REAL:** 409 `REAL_NOT_ELIGIBLE` com o motivo, para os cinco motivos. A carteira inativa deixa de usar 422, o ADR-005 é alinhado e um teste de regressão cobre a mudança [Q2].
- **Elegibilidade:** só o motivo mais forte, na ordem kill switch, REAL desligado, carteira inativa, liberação ausente, quarentena. É conferida na leitura e de novo na criação. Se a fonte falhar, a criação REAL dá 503 e a PAPER segue normal [Q3].
- **Quarentena:** o motivo traz `effectiveAt`, e a tela mostra "Vale a partir de dd/mm HH:MM" [Q4].
- **Valores:** SOL sem zeros à direita e com pelo menos 2 casas, exato. US$ com 2 casas. As telas REAL atuais não mudam [Q5].
- **Limites da conta:** uma leitura nova em `riskcontrol.api` traz o teto, a ordem, as compras por dia e a perda do dia. O que não tiver valor sai como "não definido" [Q6].
- **Bloco REAL na U1:** o cálculo atual é filtrado por bot, atrás da interface da U2. Os campos de rateio vêm vazios. A U1 não é implantada sem a U2 [Q7].
- **Spec de ponta a ponta:** a liberação vigente vem de uma semente só do perfil de teste, com um teste de guarda. A quarentena de produção não muda [Q8].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
