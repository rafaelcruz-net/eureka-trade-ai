# Modelos de tela — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `rules.md` e o contrato atual (`apps/api/openapi/openapi-guest.yaml` e `openapi-admin.yaml`, rotas REAL da U1 à U5). A U6 não cria tabela. Os tipos vêm do contrato gerado (`schema.d.ts`), nunca escritos à mão; as mudanças de contrato estão no adendo de `rules.md`.

## Modelo

| Modelo (esquema) | Origem | Campos usados | Observação |
|---|---|---|---|
| `BotWalletView` | `GET /api/v1/real/bot-wallet` | `address`, `mainWalletAddress`, `status`, `network`, `mode`, `pausedReason`, `capLamports`, `maxCapLamports`, `maxSlippageBps`, `policyResetRequired`, `balance`, e os campos novos `release` e `capSetBy` | os valores em lamports viram `bigint` na tela |
| `RealReleaseView` (novo) | `GET /api/v1/real/release` | `network`, `release` (`state`, `effectiveAt`, `blockedReason`) | usado pela ativação e pelo menu |
| `RealBalanceView` | dentro de `BotWalletView.balance` | `totalLamports`, `freeLamports`, `operableLamports`, `reservedLamports`, `feeReserveLamports`, `aboveCapLamports`, `asOf` | |
| `RealChallengeRequest` e `RealChallengeView` | `POST /api/v1/real/consents/challenge` | pedido: `kind` (`ACTIVATE`, `RAISE_MODE`, `RAISE_CAP`), `mode`, `capLamports`; resposta: `challengeId`, `message`, `nonce`, `expiresAt` | assinado com `signMessage` |
| `RealActivationRequest` | `POST /api/v1/real/bot-wallet` | o desafio assinado | com `Idempotency-Key` |
| `RealModeRequest` e `RealModeView` | `PUT /api/v1/real/mode` | `mode`, `challengeId`, `signature` | com `Idempotency-Key` |
| `RealPreferencesRequest` | `PATCH /api/v1/real/bot-wallet/preferences` | `capLamports`; ao subir o teto, também `challengeId` e `signature` | |
| `RealSigningSession` | `GET /api/v1/real/orders/{id}/signing-session` | `transactionBase64`, `messageDigest`, `deadline`, `display` e o campo novo `serverTime` | a transação não é alterada e é descartada ao fechar |
| `RealSigningDisplay` | dentro da sessão | `side`, `input`, `expectedOutput`, `minOutput`, `feeLamports`, `reason` | quantidades em `RealTokenAmount` (`mint`, `baseUnits`, `decimals`) |
| `RealSignedTransactionRequest` | `POST /api/v1/real/orders/{id}/signing-session` | `signedTransactionBase64` | |
| `RealOrderPage` e `RealOrder` | `GET /api/v1/real/orders` e `/{id}` | `items`, `nextCursor`, `total`; na ordem, estado, token, lado, quantidades, `feeLamports`, `createdAt`, `failureCode` e assinatura | paginação por cursor |
| `RealPnl` | `GET /api/v1/real/pnl` | `realizedLamports`, `unrealizedLamports`, `feesLamports`, `positions` | o não realizado pode ser `unknown` por posição |
| `RealWithdrawalRequest` e `RealWithdrawalView` | `POST /api/v1/real/bot-wallet/withdrawals` e `GET .../{withdrawalId}` | `amount` (`lamports` ou `all`), `state`, `txSignature`, `feeLamports` | o destino é sempre a carteira principal |
| `RealLiquidationView` | `POST /api/v1/real/liquidations` | `liquidationId` e o estado lido depois | |
| `RealSigningSessionOpened` (evento `real.signing.requested`) e `RealOrderStateChanged` | canal ao vivo do painel | `realOrderId`, `deadline` e o estado | |
| Modelos do administrador | rotas R4 da U5 (`openapi-admin.yaml`) | lista e detalhe do usuário, liberação, teto, tokens bloqueados, kill switch REAL (`active` e `version`), divergências com a medida por ativo, restauração | a carteira principal vem abreviada |

## Estado local da tela

| Estado | Onde | Duração |
|---|---|---|
| `idempotencyKey` por corpo de envio | `MoneyConfirmDialog` e as ações do administrador | do corpo montado até ele mudar ou o diálogo fechar (BR6.2) |
| `clockSkewMs` | `SigningBanner` | da leitura da sessão ao fechar |
| texto do teto | campo do teto | até salvar ou sair |

Nenhum estado do REAL vai para `localStorage`, `sessionStorage` ou cookie.

## Relações

- `BotWalletView.mode` define se a faixa (`SigningBanner`) existe.
- `BotWalletView.release` e o estado dos termos definem o passo da ativação.
- `RealOrder` define o selo de estado (`OrderStateBadge`).

## Resumo

- 15 grupos de modelos, todos lidos do contrato gerado; uma leitura nova (`/real/release`) e três campos novos de leitura (`release`, `capSetBy` e `serverTime`).
- O único estado local é efêmero: a chave por diálogo, a diferença de relógio e o texto digitado.
