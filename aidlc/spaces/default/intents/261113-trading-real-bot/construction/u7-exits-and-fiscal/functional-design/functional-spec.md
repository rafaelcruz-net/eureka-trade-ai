# Especificação funcional — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `rules.md` (BR1 a BR9), `entities.md`, `inception/contract-design/contract-summary.md` (R5) e `functional-design-questions.md` (Q1 a Q9).

## Escopo

A U7 entrega:
- as saídas automáticas: stop, alvo e OCO da Jupiter, criados pela carteira do bot com a cossinatura da carteira principal;
- a detecção do preenchimento com lançamento no ledger REAL;
- o cancelamento automático no kill switch, na troca de modo e na revogação;
- a exportação fiscal em CSV;
- as telas de cada um desses pontos.

## Mudanças de contrato (adendo da U7)

**R5 — rotas**

| Rota | Pedido | Respostas |
|---|---|---|
| `POST /api/v1/real/conditional-orders` | `positionMint`, `kind` (`STOP`, `TAKE_PROFIT`, `OCO`), `stopPriceUsd`, `takeProfitPriceUsd` (`DecimalString`, conforme o tipo); `Idempotency-Key` obrigatória | `201 { conditionalOrderId, signingSession }`; `403 REAL_DISABLED`, `REAL_NOT_RELEASED`, `EXITS_DISABLED`; `409` (já existe condicional na posição, `REAL_PAUSED`); `422 PRICE_UNKNOWN`, `TP_NOT_ABOVE_SL`, `TRIGGER_ON_WRONG_SIDE`, `TRIGGER_OUT_OF_BOUNDS`, `TRIGGER_REJECTED`, `TOKEN_BLOCKED`, `VALIDATION_FAILED` |
| `GET /api/v1/real/conditional-orders` | `cursor`, `limit` | `200 { items: ConditionalOrder[], nextCursor, total }` |
| `POST /api/v1/real/conditional-orders/{id}/signature` | `signedTransactionBase64` | `202 ConditionalOrder`; `409 SIGNING_DEADLINE_EXPIRED`; `422 SIGNED_MESSAGE_MISMATCH`, `SIGNING_POLICY_REFUSED`; `404` |
| `DELETE /api/v1/real/conditional-orders/{id}` | `Idempotency-Key` | `202 ConditionalOrder` (`CANCELLING` ou `CANCELLED`); `404`; `409` (estado final) |
| `GET /api/v1/real/exports/fiscal` | `from`, `to` (datas) | `200 text/csv` com `Content-Disposition`; `422 VALIDATION_FAILED` (período maior que 1 ano ou invertido); `429 RATE_LIMITED` com `Retry-After` |

**`ConditionalOrder`:** `conditionalOrderId`, `positionMint`, `kind`, `stopPriceUsd`, `takeProfitPriceUsd`, `state` (`PENDING_SIGNATURE`, `OPEN`, `CANCELLING`, `FILLED`, `CANCELLED`, `EXPIRED`, `FAILED`), `filledSide`, `needsUserCancellation` (BR6.7), `filledAt`, `txSignature`, `createdAt`. O `triggerPrice` do rascunho do Inception é substituído pelos dois preços.

**Códigos de erro novos:**
- `PRICE_UNKNOWN`;
- `TP_NOT_ABOVE_SL`;
- `TRIGGER_ON_WRONG_SIDE`;
- `TRIGGER_OUT_OF_BOUNDS`;
- `TRIGGER_REJECTED`;
- `EXITS_DISABLED`;
- `NOTHING_TO_SELL` (U4, BR4.3);
- `OPEN_CONDITIONALS` (U5, BR6.9).

**Canal ao vivo:** evento `real.conditional.state`.

**TxSigner:** `/signatures` aceita `TRIGGER_CREATE` e `TRIGGER_CANCEL`. Até a gravação, recusa os dois com `POLICY_NOT_RECORDED` (BR0.3).

## Fluxos

### F1 — Criar uma condicional (BR1.2, BR3, BR4)

1. Na posição, "Definir stop e alvo" mostra o preço atual em USD (U2). Sem preço, o botão fica desabilitado com o texto "Sem preço atual" (BR3.3).
2. A pessoa digita os preços. A tela confere o formato e o lado de cada preço e envia `POST` com `Idempotency-Key`.
3. O servidor confere:
   - o flag (BR9.3);
   - a barreira (BR4.1);
   - a posição e o preço;
   - as regras de BR3.
4. O servidor pede a transação à Trigger API com a carteira do bot como dona, grava `PENDING_SIGNATURE` e abre a sessão de assinatura.
5. O navegador assina pela carteira principal e envia. O servidor confere que a mensagem é a mesma, pede a assinatura do TxSigner (`TRIGGER_CREATE`), envia e confirma na rede. Depois grava `OPEN` com o id da Jupiter.

### F2 — Detectar o preenchimento (BR5)

1. A cada 30 s, para cada condicional `OPEN` ou `CANCELLING`, o trabalho lê a Jupiter.
2. Se houver preenchimento, o trabalho confere a transação na rede (`finalized`) e lança na M2 um `SWAP_FILL_OUT` e, se a carteira do bot pagou taxa, um `NETWORK_FEE`, com `reference = conditional:<id>` e chaves pela assinatura (BR5.3). Depois grava `FILLED`, `filled_side` e `fill_signature`.
3. Por fim, publica no canal e audita.

### F3 — Cancelar (BR6, BR4.3)

1. Um gatilho dispara o cancelamento: kill switch (trabalho de 1 min), troca de modo, revogação, venda a mercado da mesma posição ou pedido da pessoa.
2. O servidor grava `CANCELLING`, pede a transação de cancelamento à Jupiter, obtém a assinatura do TxSigner (`TRIGGER_CANCEL`), envia e confirma, e grava `CANCELLED`.
3. Na venda a mercado, a ordem da U4 só segue depois do `CANCELLED` ou do `FILLED`.

### F4 — Exportar (BR7)

1. A tela pede o período; `GET /exports/fiscal?from&to`.
2. O servidor confere o tenant, o limite e o período e lê o ledger REAL e as condicionais da pessoa. Ele gera o CSV em fluxo (streaming), sem guardar.
3. O download recebe o nome `historico-real-<from>-<to>.csv`.

## Cenários

| ID | Dado | Quando | Então |
|---|---|---|---|
| S1 | modo Automática nas saídas, posição com preço | criar `OCO` com alvo acima e stop abaixo | `PENDING_SIGNATURE` e sessão aberta (AC3.4.1) |
| S2 | sessão aberta | a carteira principal assina a tempo | TxSigner assina, rede confirma, estado `OPEN` |
| S3 | posição sem preço atual | criar | `422 PRICE_UNKNOWN` |
| S4 | alvo abaixo do stop | criar | `422 TP_NOT_ABOVE_SL` |
| S5 | stop acima do preço atual | criar | `422 TRIGGER_ON_WRONG_SIDE` |
| S6 | modo Assinada | criar | recusa da barreira (modo) |
| S7 | token bloqueado ou kill switch ligado | criar | recusa da barreira |
| S8 | já existe condicional aberta na posição | criar outra | `409` (índice único) |
| S9 | mesma `Idempotency-Key` | repetir a criação | mesma condicional |
| S10 | flag desligado | criar | `403 EXITS_DISABLED`; leituras e cancelamento funcionam |
| S11 | prazo da sessão vencido | assinar depois | `409 SIGNING_DEADLINE_EXPIRED`; estado `EXPIRED` |
| S12 | transação diferente | assinar | `422 SIGNED_MESSAGE_MISMATCH`; nada enviado |
| S13 | condicional `OPEN` | a Jupiter mostra o stop preenchido | rede conferida, `SWAP_FILL_OUT` no ledger com `reference = conditional:<id>`, posição e P&L atualizados, `FILLED` (AC3.4.2) |
| S14 | mesmo preenchimento lido duas vezes | trabalho de leitura | um único lançamento |
| S15 | Jupiter diz preenchida e a rede ainda não confirma | leitura | continua `OPEN` até `finalized` |
| S16 | condicional `OPEN` | kill switch ligado | `CANCELLING` e depois `CANCELLED` em até 2 min (AC8.1.3) |
| S17 | condicional `OPEN` | pessoa volta para Assinada | canceladas |
| S18 | liberação revogada | revogar | canceladas |
| S19 | condicional `OPEN` | o bot decide vender a posição a mercado | cancela antes; a venda segue depois do `CANCELLED` |
| S20 | `CANCELLING` | a Jupiter preenche antes | `FILLED` vale; lançamento feito |
| S21 | política do TxSigner | criação com destino de saída fora da carteira do bot | recusada com motivo |
| S22 | política do TxSigner | criação com mint diferente da posição | recusada |
| S23 | política do TxSigner | criação com quantidade acima do saldo | recusada |
| S24 | política do TxSigner | cancelamento devolvendo para outra conta | recusado |
| S25 | política do TxSigner | programa fora da lista | recusado |
| S26 | política do TxSigner | signatário extra além da carteira principal | recusado |
| S27 | Jupiter fora por 2 horas | leitura | alerta; nada muda até voltar |
| S28 | ledger com compra, venda, condicional, depósito, saque | exportar o ano | CSV com BOM, `;`, vírgula decimal, uma linha por evento, valores exatos (AC4.3.1) |
| S29 | símbolo do token começando com `=` | exportar | campo com apóstrofo |
| S30 | outra pessoa | exportar | só os próprios dados |
| S31 | período maior que 1 ano | exportar | `422 VALIDATION_FAILED` |
| S32 | 11ª exportação na hora | exportar | `429 RATE_LIMITED` |
| S33 | dado do PAPER na conta | exportar | nunca aparece |
| S34 | kill switch ligado | exportar ou sacar | funcionam |
| S35 | campo sem dado (preço de depósito) | exportar | vazio, nunca zero |
| S36 | condicional `PENDING_SIGNATURE` | kill switch ligado | `CANCELLED` sem transação; sessão encerrada |
| S37 | condicional preencheu e a venda a mercado da U4 chega depois | venda | relê o saldo zero e termina com `NOTHING_TO_SELL`, sem enviar |
| S38 | cancelamento não confirma | 15 min em `CANCELLING` | alerta; `needsUserCancellation` verdadeiro; tentativa nova a cada minuto |
| S39 | kill switch ligado e REAL desligado | cancelamento | o TxSigner assina o `TRIGGER_CANCEL` normalmente |
| S40 | política do TxSigner | criação mandando o token para conta de custódia diferente da derivada | recusada |
| S41 | política do TxSigner | discriminador do programa Trigger fora dos gravados | recusada |
| S42 | política do TxSigner | saída mínima na rede abaixo do piso do pedido | recusada |
| S43 | stop a 40% do preço atual ou alvo a 12 vezes | criar | `422 TRIGGER_OUT_OF_BOUNDS` |
| S44 | antes da gravação real | TxSigner recebe `TRIGGER_CREATE` | `POLICY_NOT_RECORDED` |
| S45 | ledger de exemplo com todos os tipos | exportar o período | a soma de BR7.6 bate com a variação do saldo |
| S46 | ordem com `NETWORK_FEE` | exportar | a taxa aparece só em `taxa_sol` da linha da ordem; nenhuma linha `TAXA` duplicada |
| S47 | condicional `OPEN` | anonimizar | `409 OPEN_CONDITIONALS` |
| S48 | `DELETE` pela pessoa | condicional `OPEN` | `202 CANCELLING`; depois `CANCELLED` |
| S49 | condicional `OPEN` com o token na custódia | reconciliação da U5 | soma o saldo da custódia; nenhuma divergência |
| S50 | modo `AUTO_EXITS` com o flag desligado | criar condicional | `403 EXITS_DISABLED`; ordens a mercado seguem cossinadas |

## Erros e casos de canto

- **Anonimização:** o `closeForAnonymization` da U5 já recusa com posição (`POSITIONS`). A U7 acrescenta a recusa `OPEN_CONDITIONALS` com condicional não terminal (BR6.9). A anonimização não cancela nada sozinha.
- **Posição vendida por outro caminho antes do cancelamento:** se o preenchimento da condicional falhar por falta de saldo, a Jupiter não executa; a leitura marca `EXPIRED` ou `CANCELLED`, conforme a resposta.
- **Fuso:** a API trafega UTC. A exportação traz as duas colunas, `data_utc` e `data_local`.
