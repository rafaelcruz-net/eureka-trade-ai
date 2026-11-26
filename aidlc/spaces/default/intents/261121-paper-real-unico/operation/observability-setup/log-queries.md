# Consultas salvas do Logs Insights — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Observability Setup, Q3 (opção A), com o resumo confirmado. Data: 2026-11-25.

> **Nada está implantado** [Q1]. As cinco consultas do 261113 existem como `aws_cloudwatch_query_definition` em `infra/terraform/modules/observability/log-queries.tf`. **As seis consultas abaixo ainda não estão nesse arquivo**: as Units deste intent registraram "nenhuma consulta salva nova", e a Q3 deste estágio decidiu acrescentá-las. Este estágio não muda IaC: a seção 4 traz o texto pronto para o mapa `local.log_queries`. Até ele ser aplicado, as consultas rodam coladas à mão no console.

## 1. O que é herdado sem mudança

- **Grupos:** `/ecs/eureka-real/api-worker` (api e worker), `/ecs/eureka-real/txsigner`, `/ecs/eureka-real/web`, `/ecs/eureka-real/admin`. Nenhum grupo novo neste intent.
- **Retenção:** 90 dias (`log_retention_days`), por minimização da LGPD [Q3]. A trilha de auditoria fica no banco.
- **Formato:** JSON do `LogstashEncoder` (`apps/api/src/main/resources/logback-spring.xml`), com `timestamp` (UTC), `level`, `component`, `message` e `correlationId` (MDC). Todo valor de texto passa pelo `Base58AddressValueMasker`.
- **As cinco consultas** `eureka-real/seguir-correlation-id`, `recusas-txsigner-por-motivo`, `erros-5xx-por-componente`, `ordens-real-ambiguas` e `falhas-dos-trabalhos`: sem mudança. A `seguir-correlation-id` serve também aos eventos deste intent; num ciclo de bot, o `correlationId` é o `cycleId`.

**Como os eventos deste intent aparecem:** o nome e os campos estão **dentro do texto** de `message`, como argumentos do SLF4J (por exemplo, `event=REAL_OWN_HOLDINGS_UNAVAILABLE botId=<uuid> reason=BUDGET_EXCEEDED elapsedMs=500 streak=3`). Eles não são campos JSON próprios. Por isso as consultas usam `parse message /.../` com grupos nomeados.

## 2. Eventos deste intent no código

Lidos em `apps/api/src/main/java` no commit `09de0a8`. Todos em inglês, com `correlationId`.

| Unit | Texto da mensagem (modelo exato) | Nível | Classe |
|---|---|---|---|
| U1 | `REAL bot creation refused: reason={}` | INFO | `BotCommandService` |
| U1 | `REAL refusal audit failed: reason={}, cause={}` | WARN | `BotCommandService` |
| U1 | `REAL eligibility unavailable: operation={}, cause={}` | WARN | `RealEligibilityReader` |
| U1 (C4) | `P&L block unavailable: market={}, cause={}` | WARN | `ModePnlView` |
| U2 | `event=REAL_PNL_RECONCILIATION_MISMATCH checks={} window={}` | ERROR | `ReconciliationAuditGate` |
| U2 | `event=REAL_PNL_MISMATCH_AUDIT_FAILED category={} exceptionClass={}` | ERROR | idem |
| U2 | `event=REAL_SLOT_CONFLICT writer={} recordedSlot={} refusedSlot={} txSignature={}` (assinatura abreviada) | WARN | ledger |
| U2 | `event=REAL_SLOT_CONFLICT_AUDIT_REFUSED writer={} reason={} field={}` | — | ledger |
| U2 | `event=REAL_ATTRIBUTION_READ_FAILED path={} outcome={} reason={} exceptionClass={}` | WARN | `pnl.application` |
| U2 | `event=REAL_RISK_DAY_PHOTO_DEFERRED reason=COST_UNKNOWN`; `event=REAL_EXIT_SKIPPED reason=COST_UNKNOWN rule=TAKE_PROFIT,STOP_LOSS` | WARN; INFO | risco; `RealCycle` |
| U3 | `event=PNL_READ_NOT_FOUND route={} side={} reason={}` (`reason` = `TENANT_MISMATCH` ou `NOT_FOUND`) | INFO | `pnl` |
| U3 | `event=PNL_COMPARE_TEMPLATE_MISMATCH` | INFO | `pnl` |
| U3 | `event=PNL_COMPARE_SIDE_UNAVAILABLE market={} cause={}` | WARN | `pnl` |
| U3 | `event=PNL_BOT_LINES_UNAVAILABLE cause={}` | WARN | `pnl` |
| U3 | `event=PNL_READ_AUDIT_FAILED action={} category={} exceptionClass={}` (no máximo 1 por minuto por pessoa e ação) | ERROR | `pnl` |
| U4 | `event=REAL_OWN_HOLDINGS_UNAVAILABLE botId={} reason={} elapsedMs={} streak={}` | WARN | `OwnHoldingsStep` |
| U4 | `event=REAL_OWN_HOLDINGS_UNAVAILABLE botId={} reason={}` (só `BOT_NOT_OWNED`) | ERROR | `OwnHoldingsStep` |
| U4 | `event=REAL_OWN_HOLDINGS_UNAVAILABLE botId={} reason={} phase=PRE exceptionClass={}`; `... reason={} precheck=NOT_READ` | WARN | `OwnHoldingsStep`, `CycleRunner` |
| U4 | `event=REAL_OWN_HOLDINGS_STEP_FAILED botId={} phase=AFTER_READ exceptionClass={}`; `... phase=BOOKKEEPING exceptionClass={}` | WARN | `OwnHoldingsStep` |
| U4 | `event=REAL_OWN_HOLDINGS_STREAKS_FULL entries={}` | WARN | `OwnHoldingsStreaks` |
| U4 | `event=REAL_OWN_SELL_STALE botId={} markBefore={} markUnderLock={}` (é o `STALE_SNAPSHOT`) | INFO | `CycleRunner` |
| U4 | `event=REAL_OWN_SELL_NO_QUANTITY botId={} mint={} requestedBaseUnits={}`; `event=REAL_OWN_SELL_CAPPED botId={} mint={} requestedBaseUnits={} sellBaseUnits={}` | INFO | `CycleRunner` |
| U4 | `Bot cycle: cycleId={}, botId={}, slotStart={}, result={}, action={}, outcome={}`, com `outcome` = `REAL_OWN_QUANTITY_UNAVAILABLE` ou `REAL_NO_OWN_QUANTITY` (resultados novos de `bot_cycle`, migração `0026`) | INFO | `CycleRunner` |

- `REAL_OWN_HOLDINGS_READ_ENDED` sai em **DEBUG** (`RealBotHoldingsReader`) e não chega à nuvem, onde a raiz é INFO.
- Os eventos de **auditoria** (`BOT_REAL_CREATION_REFUSED`, `PNL_COMPARE_TEMPLATE_MISMATCH`, `REAL_PNL_RECONCILIATION_MISMATCH`, `REAL_SLOT_CONFLICT`, em `AuditActions.java`) ficam na trilha do banco, não no log. As consultas cobrem as **falhas** de gravação da auditoria (`REAL refusal audit failed`, `REAL_PNL_MISMATCH_AUDIT_FAILED`, `REAL_SLOT_CONFLICT_AUDIT_REFUSED`, `PNL_READ_AUDIT_FAILED`).

## 3. Consultas novas

Pasta `eureka-real/`, grupo `api-worker` em todas (os eventos são da API e do worker).

| Nome | Responde a | Ligação |
|---|---|---|
| `eureka-real/pnl-blocos-indisponiveis` | Quantos blocos e lados do P&L saíram `UNAVAILABLE`, por tipo, modo e causa, por hora? | SLO-PNL-2 (`TIMEOUT`), SLO-U2-1 (`RECONCILIATION_MISMATCH`); evidência na nuvem da U1 e da U3 (D-INF3) |
| `eureka-real/pnl-recusas-e-auditoria` | Por que criações REAL e leituras do P&L foram recusadas, e a auditoria falhou? | U1 (NFR3.10, NFR3.11), U3 (NFR3.10, NFR3.16). `PNL_READ_NOT_FOUND` com `TENANT_MISMATCH` mostra tentativa de ler bot de outra conta |
| `eureka-real/pnl-real-divergencias` | O que aconteceu numa divergência do P&L REAL, num conflito de slot ou numa falha da atribuição? | alarme `real-ops-pnl-reconciliation-mismatch`; runbook, seção 8, passo 2 |
| `eureka-real/real-posicao-propria-indisponivel` | Qual motivo deixa a posição própria indisponível, a cada 10 min? | alarme `real-ops-own-holdings-unavailable`; runbook, seção 10 |
| `eureka-real/real-posicao-propria-bots-presos` | Quais bots têm 3 ou mais leituras indisponíveis seguidas? | alarme `real-ops-own-holdings-stuck-bots`; runbook, seção 10 |
| `eureka-real/real-venda-propria-cortes` | Quantos `STALE_SNAPSHOT`, cortes e vendas sem quantidade por dia, e quantos ciclos terminaram `REAL_OWN_QUANTITY_UNAVAILABLE` ou `REAL_NO_OWN_QUANTITY`? | runbook, seção 13 (`real_cycle_sell_capped_total` fica só no Prometheus) |

### Texto das consultas

**`pnl-blocos-indisponiveis`**

```
fields @timestamp, message
| filter message like /^P&L block unavailable|event=PNL_COMPARE_SIDE_UNAVAILABLE|event=PNL_BOT_LINES_UNAVAILABLE/
| parse message /(?<kind>P&L block unavailable|PNL_COMPARE_SIDE_UNAVAILABLE|PNL_BOT_LINES_UNAVAILABLE)/
| parse message /market=(?<market>[A-Z]+)/
| parse message /cause=(?<cause>[A-Za-z_]+)/
| stats count(*) as blocos by kind, market, cause, bin(1h)
| sort blocos desc
```

**`pnl-recusas-e-auditoria`**

```
fields @timestamp, message
| filter message like /REAL bot creation refused|REAL refusal audit failed|REAL eligibility unavailable|event=PNL_READ_NOT_FOUND|event=PNL_COMPARE_TEMPLATE_MISMATCH|event=PNL_READ_AUDIT_FAILED/
| parse message /(?<kind>REAL bot creation refused|REAL refusal audit failed|REAL eligibility unavailable|PNL_READ_NOT_FOUND|PNL_COMPARE_TEMPLATE_MISMATCH|PNL_READ_AUDIT_FAILED)/
| parse message /reason=(?<reason>[A-Z_]+)/
| parse message /route=(?<route>[A-Z_]+)/
| parse message /action=(?<action>[A-Z_]+)/
| parse message /category=(?<category>[A-Z_]+)/
| stats count(*) as eventos by kind, reason, route, action, category
| sort eventos desc
```

**`pnl-real-divergencias`**

```
fields @timestamp, level, correlationId, message
| filter message like /event=(REAL_PNL_RECONCILIATION_MISMATCH|REAL_PNL_MISMATCH_AUDIT_FAILED|REAL_SLOT_CONFLICT|REAL_ATTRIBUTION_READ_FAILED|REAL_RISK_DAY_PHOTO_DEFERRED)/
| parse message /event=(?<event>[A-Z_]+)/
| parse message /checks=(?<checks>\[[^\]]*\]|\S+)/
| parse message /writer=(?<writer>[A-Z_]+)/
| parse message /path=(?<path>[A-Z]+) outcome=(?<outcome>[A-Z_]+) reason=(?<reason>[A-Z_]+)/
| parse message /category=(?<category>[A-Z_]+)/
| display @timestamp, level, event, checks, writer, path, outcome, reason, category, correlationId
| sort @timestamp desc
| limit 200
```

O `display` mostra só os campos extraídos, e nunca a `message` inteira: o `REAL_SLOT_CONFLICT` traz a assinatura abreviada da transação, que a consulta não precisa mostrar. As diferenças em lamports ficam só no evento de auditoria, nunca no log.

**`real-posicao-propria-indisponivel`**

```
fields @timestamp, message
| filter message like /event=REAL_OWN_HOLDINGS_(UNAVAILABLE|STEP_FAILED|STREAKS_FULL)/
| parse message /event=(?<event>[A-Z_]+)/
| parse message /reason=(?<reason>[A-Z_]+)/
| parse message /phase=(?<phase>[A-Z_]+)/
| parse message /exceptionClass=(?<exceptionClass>[A-Za-z0-9_$.]+)/
| stats count(*) as linhas by event, reason, phase, exceptionClass, bin(10m)
| sort linhas desc
```

Lê junto com o widget 4 do painel (leituras por 10 min): a razão do alarme é `BUDGET_EXCEEDED + READ_FAILED` sobre as leituras. `BOT_NOT_OWNED` (ERROR) é sempre defeito.

**`real-posicao-propria-bots-presos`**

```
fields @timestamp, message
| filter message like /event=REAL_OWN_HOLDINGS_UNAVAILABLE/ and message like /streak=([3-9]|[1-9][0-9]+)\b/
| parse message /botId=(?<botId>[0-9a-f-]{36}) reason=(?<reason>[A-Z_]+) elapsedMs=(?<elapsedMs>[0-9]+) streak=(?<streak>[0-9]+)/
| stats count(*) as leiturasNaSequencia, latest(streak) as ultimaSequencia, latest(reason) as ultimoMotivo by botId
| sort leiturasNaSequencia desc
| limit 50
```

O `botId` é o uuid interno do bot, sem dado pessoal, e é o que o runbook (seção 10) pede para achar o bot. Uma carteira inteira com defeito aparece como 3 bots da mesma pessoa; a consulta não mostra a pessoa nem a carteira.

**`real-venda-propria-cortes`**

```
fields @timestamp, message
| filter message like /event=REAL_OWN_SELL_(STALE|NO_QUANTITY|CAPPED)/ or message like /^Bot cycle: .*outcome=REAL_(OWN_QUANTITY_UNAVAILABLE|NO_OWN_QUANTITY)/
| parse message /event=(?<event>REAL_OWN_SELL_[A-Z_]+)/
| parse message /outcome=(?<outcome>REAL_[A-Z_]+)/
| stats count(*) as linhas by event, outcome, bin(1d)
| sort linhas desc
```

**`STALE_SNAPSHOT` na nuvem (runbook, seção 13):** a razão é o número de `REAL_OWN_SELL_STALE` em 24 h (desta consulta) sobre a soma de `real_own_holdings_reads_total` em 24 h no CloudWatch (`aws cloudwatch get-metric-statistics --namespace Eureka/App --metric-name real_own_holdings_reads_total --period 86400 --statistics Sum ...`). Meta: ≤ 1%. Acima disso, ou com 3 `REAL_OWN_SELL_STALE` seguidos do mesmo bot, abrir defeito.

## 4. Texto pronto para `log-queries.tf`

Acrescentar estas entradas ao mapa `local.log_queries`, depois de `"falhas-dos-trabalhos"`. A forma é a mesma das cinco que já existem (`groups` e `query` em heredoc). O `query` de cada uma é o texto da seção 3.

```hcl
    # 261121-paper-real-unico (Observability Setup, Q3; log-queries.md). The events of the intent are inside the text
    # of message (SLF4J arguments), so the queries parse them; none shows a full message, an address or a signature.
    "pnl-blocos-indisponiveis" = {
      groups = ["api-worker"]
      query  = <<-EOT
        <texto da seção 3, pnl-blocos-indisponiveis>
      EOT
    }
    "pnl-recusas-e-auditoria"          = { groups = ["api-worker"], query = <<-EOT ... EOT }
    "pnl-real-divergencias"            = { groups = ["api-worker"], query = <<-EOT ... EOT }
    "real-posicao-propria-indisponivel" = { groups = ["api-worker"], query = <<-EOT ... EOT }
    "real-posicao-propria-bots-presos" = { groups = ["api-worker"], query = <<-EOT ... EOT }
    "real-venda-propria-cortes"        = { groups = ["api-worker"], query = <<-EOT ... EOT }
```

Cada `<<-EOT ... EOT` leva o texto completo da consulta, cada linha numa linha própria, como em `log-queries.tf`. A saída `log_query_names` passa de 5 para 11 nomes; se um teste offline contar as consultas, ele muda junto. Rollback: apagar as entradas.

## 5. Dados que nunca aparecem

- Nenhuma consulta lê ou mostra endereço de carteira, assinatura completa, token, cabeçalho `Authorization`, nonce, assinatura de login ou segredo (`project.md`, Forbidden). O `mint` de `REAL_OWN_SELL_*` passa pela máscara Base58 do encoder e não é selecionado.
- Nenhuma consulta mostra pessoa ou tenant. Os únicos identificadores mostrados são o `correlationId` e o `botId` (uuid interno).
- Nenhuma consulta soma PAPER e REAL: o campo `market` separa os modos em linhas próprias (`project.md`, Forbidden).

## 6. Custo

A definição de consulta salva não tem custo. O Logs Insights cobra US$ 0,005 por GB lido, só quando a consulta roda. Uma consulta de 24 h no grupo `api-worker` do piloto lê poucos MB. **Custo novo: ≈ US$ 0.**

## 7. Conferência depois do primeiro apply da devnet (com as consultas aplicadas)

```bash
# As onze consultas existem
aws logs describe-query-definitions --query-definition-name-prefix eureka-real/ --query 'queryDefinitions[].name'

# Uma consulta nova roda sem erro de sintaxe (pode devolver zero linhas com a flag desligada)
QID=$(aws logs start-query --log-group-names /ecs/eureka-real/api-worker \
  --start-time "$(date -u -d '-1 day' +%s)" --end-time "$(date -u +%s)" \
  --query-string 'fields @timestamp, message | filter message like /^P&L block unavailable|event=PNL_/ | parse message /market=(?<market>[A-Z]+)/ | stats count(*) by market' \
  --query queryId --output text)
aws logs get-query-results --query-id "$QID" --query 'status'      # esperado: "Complete"
```

## Sources

- `observability-setup-questions.md` (Q3, resumo confirmado) [Q3] [Q1].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/log-queries.md` (grupos, retenção, formato, as cinco consultas).
- `construction/u1-mode-skeleton/nfr-design/observability-design.md` (logs NFR3.11), `u2-real-bot-pnl/nfr-design/observability-design.md` (logs NFR3.12), `u3-pnl-views/infrastructure-design/monitoring-design.md` (D-INF3, eventos NFR3.10), `u4-own-quantity-cycle/nfr-design/observability-design.md` (logs NFR3.9) e `infrastructure-design/monitoring-design.md` (runbook, seções 10 e 13).
- Código lido no commit `09de0a8`: `bots/application/BotCommandService.java`, `bots/application/RealEligibilityReader.java`, `pnl/modeview/ModePnlView.java`, `pnl/application/ReconciliationAuditGate.java`, `pnl/application/RealBotHoldingsReader.java`, `bots/worker/OwnHoldingsStep.java`, `OwnHoldingsStreaks.java`, `CycleRunner.java`, `RealCycle.java`, `audit/api/AuditActions.java`, `resources/logback-spring.xml`, `resources/db/changelog/changes/0026-bot-cycle-own-quantity-outcomes.sql`; `infra/terraform/modules/observability/log-queries.tf`.
- `aidlc/spaces/default/memory/project.md` (Forbidden: log sem segredo; nunca somar os modos) e `team.md` (Code Style: logs em inglês).

## Assumptions & Open Questions

1. [assumption] O Logs Insights aceita vários `parse` com expressão regular e grupos nomeados na mesma consulta; uma linha que não casa com um `parse` fica com o campo vazio, e o `stats` agrupa esse vazio como um valor.
2. [assumption] O `failedChecks` do evento de divergência sai como lista entre colchetes (`[FEES, REALIZED]`); o `parse` aceita também um valor sem colchetes.
3. Aberta: aplicar o texto da seção 4 em `log-queries.tf`. A Q3 decidiu as consultas, mas este estágio não muda IaC. Dono: o responsável, na mesma volta que aplicar a linha do painel.
4. [assumption] O nível do `REAL_SLOT_CONFLICT_AUDIT_REFUSED` não foi lido; a consulta `pnl-real-divergencias` não filtra por nível.
