# Entidades — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `rules.md` (BR1 a BR9) e `inception/contract-design/contract-summary.md` (R5, M2, X1).

## Modelo

### ConditionalOrder (`real_conditional_order`, nova)

| Campo | Tipo | Regra |
|---|---|---|
| `conditional_order_id` | uuid | chave |
| `tenant_id`, `user_id` | uuid | sempre do token (BR7.6) |
| `bot_wallet_address` | Base58 | dona (BR1.1); anulável depois da anonimização |
| `position_mint` | Base58 | mint da posição |
| `kind` | `STOP`, `TAKE_PROFIT`, `OCO` | BR3.1 |
| `stop_price_usd`, `take_profit_price_usd` | numeric exato, anulável | conforme o tipo (BR3.2) |
| `amount_base_units` | numeric(39,0) | posição inteira na criação (BR3.7) |
| `slippage_bps` | int | do perfil (BR3.6) |
| `state` | `PENDING_SIGNATURE`, `OPEN`, `CANCELLING`, `FILLED`, `CANCELLED`, `EXPIRED`, `FAILED` | máquina de estados abaixo |
| `jupiter_order_id` | texto, anulável | id na Jupiter depois da criação |
| `create_signature`, `fill_signature`, `cancel_signature` | Base58, anulável | conferidas na rede |
| `escrow_account` | Base58, anulável | conta de custódia da ordem, gravada na criação (BR5.3b) |
| `filled_side` | `STOP`, `TAKE_PROFIT`, anulável | qual perna preencheu |
| `idempotency_key` | texto | BR4.4, único por pessoa |
| `cancel_reason` | `KILL_SWITCH`, `MODE_CHANGE`, `RELEASE_REVOKED`, `MARKET_SELL`, `USER`, anulável | BR6, BR4.3 |
| `last_read_at` | timestamptz, anulável | BR5.6 |
| `cancelling_since` | timestamptz, anulável | BR6.4, BR6.7 |
| `created_at`, `updated_at` | timestamptz (UTC) | |

Índice único parcial: uma condicional não terminal (`PENDING_SIGNATURE`, `OPEN` ou `CANCELLING`) por (`bot_wallet_address`, `position_mint`) (BR3.1).

### Lançamento do preenchimento (M2, sem tabela nem tipo novo)

Um `SWAP_FILL_OUT` (lamports recebidos e tokens vendidos) e, só se a carteira do bot pagou taxa, um `NETWORK_FEE`, com `reference = conditional:<id>` e as chaves `cond:<fill_signature>:fill|fee` (BR5.3). O CHECK do ledger não muda.

### Exportação (sem tabela)

Gerada sob demanda a partir do ledger REAL (M2) e das condicionais (BR7.8). O limite de 10 por hora usa o contador de limite que já existe.

## Máquina de estados — ConditionalOrder

| De | Para | Quando |
|---|---|---|
| (novo) | `PENDING_SIGNATURE` | criação aceita pela barreira; sessão de assinatura aberta |
| `PENDING_SIGNATURE` | `OPEN` | assinaturas conferidas, criação confirmada na rede e id da Jupiter gravado |
| `PENDING_SIGNATURE` | `EXPIRED` | prazo da sessão vencido (`SIGNING_DEADLINE_EXPIRED`) |
| `PENDING_SIGNATURE` | `FAILED` | recusa da política, transação que não confere ou falha na rede |
| `PENDING_SIGNATURE` | `CANCELLED` | kill switch, troca de modo, revogação, venda a mercado ou pedido da pessoa, antes da assinatura (BR6.5), sem transação |
| `OPEN` | `FILLED` | preenchimento conferido na rede (BR5.2) |
| `OPEN` | `CANCELLING` | kill switch, troca de modo, revogação, venda a mercado ou pedido da pessoa |
| `CANCELLING` | `CANCELLED` | cancelamento confirmado na rede |
| `CANCELLING` | `CANCELLING` | novo cancelamento a cada minuto, se o anterior expirou sem confirmar; alerta com 15 min (BR6.4) |
| `CANCELLING` | `FILLED` | preenchida antes de o cancelamento confirmar (BR6.6) |
| `OPEN` | `EXPIRED` | a Jupiter informa a expiração |

`FILLED`, `CANCELLED`, `EXPIRED` e `FAILED` são estados finais. As mudanças de estado são compare-and-set.

## Relações

- A condicional usa a posição REAL da carteira do bot (M2 e P&L da U4).
- O preenchimento gera, na M2, um `SWAP_FILL_OUT` e, quando houver, um `NETWORK_FEE`, com `reference = conditional:<id>`.
- O kill switch é lido pela M7, e o modo e as pausas pela M1.

## Resumo

- 1 tabela nova, nenhum tipo novo de lançamento e 7 estados.
- Valores em decimal exato ou unidades base.
