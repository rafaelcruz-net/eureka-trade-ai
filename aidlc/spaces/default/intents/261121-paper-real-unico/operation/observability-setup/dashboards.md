# Painéis do CloudWatch — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Observability Setup, Q3 (opção A), com o resumo confirmado. Data: 2026-11-25.

> **Nada está implantado.** Não existe conta AWS [Q1]. Os três painéis do 261113 existem só como Terraform em `infra/terraform/modules/observability/dashboards.tf`. **A linha nova descrita aqui ainda não está nesse arquivo** (no commit `09de0a8`, nenhuma Unit mexeu nos painéis: U1 a U4 registraram "nenhum painel"). Este estágio não muda IaC: a seção 2 traz o texto pronto, e aplicá-lo é uma mudança de IaC do responsável numa volta à Construction ou num intent de infraestrutura.

## 1. O que é herdado sem mudança

| Painel | Nome no CloudWatch | Situação |
|---|---|---|
| Visão REAL | `eureka-real-visao-real` | sem mudança nos widgets atuais (y = 0 a 34); **ganha a linha da seção 2**, a partir de y = 35 |
| Serviços | `eureka-real-servicos` | sem mudança; o gráfico `RDS: conexões` ganha a nota da seção 3 |
| Segurança | `eureka-real-seguranca` | sem mudança |

As regras herdadas continuam: só métricas da lista de permissão de `Eureka/App` (`app-metrics.tf`); widgets de medidores com período de 4 min (duas leituras de 120 s); nenhuma dimensão com mint, endereço ou pessoa. O teste offline `observability_metrics.tftest.hcl` falha se um painel desenhar uma métrica de `Eureka/App` fora da lista (saída `dashboard_app_metrics`). As quatro métricas usadas abaixo estão na lista (`app-metrics.tf:71-79`), e a asserção `length(dashboard_names) == 3` continua valendo.

**O que não dá para desenhar:** a U1 e a U3 não publicam métrica no CloudWatch (`bots_real_create_refusals_total`, `pnl_mode_block_unavailable_total`, `pnl_compare_*`, `pnl_read_audit_failures_total` ficam só no Prometheus; D-INF3). O painel aponta para as consultas salvas do `log-queries.md`.

## 2. Linha nova no painel Visão REAL: "P&L por modo e quantidade própria"

### 2.1 Widget a widget

| # | Posição (x, y, largura, altura) | Widget | Métricas | Estatística e período | Linhas de referência |
|---|---|---|---|---|---|
| 1 | 0, 35, 24, 2 | Texto | título da linha; runbook `docs/runbooks/real-operations.md`, seções 8, 10 e 13; aviso de que U1 e U3 só aparecem nos logs (consultas `pnl-*`) e de que a flag da U4 está desligada | — | — |
| 2 | 0, 37, 24, 2 | Alarmes | `real-ops-pnl-reconciliation-mismatch`, `real-ops-own-holdings-unavailable`, `real-ops-own-holdings-stuck-bots` | estado do alarme | — |
| 3 | 0, 39, 6, 6 | Divergências do P&L REAL | `pnl_real_reconciliation_mismatches_total` (verificações divergentes, soma das 8 de `check`) | Sum, 4 min (o período do alarme) | "alarme > 0" em 0 |
| 4 | 6, 39, 6, 6 | Posição própria: leituras e indisponíveis por motivo | `real_own_holdings_reads_total` (eixo esquerdo); `real_own_holdings_unavailable_total{reason=BUDGET_EXCEEDED|READ_FAILED|BOT_NOT_OWNED}` (eixo direito) | Sum, 10 min (o período do alarme) | — |
| 5 | 12, 39, 6, 6 | Posição própria: razão e SLI | `e1` = a expressão do alarme, `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`; `e2` = SLI do SLO-U4-1, `IF(FILL(m3, 0) > 0, FILL(m1, 0) / m3, 0)` | Sum, 10 min | "alarme 5%" em 0,05; "SLO 0,1%" em 0,001 |
| 6 | 18, 39, 6, 6 | Bots REAL presos | `real_own_holdings_stuck_bots` | Maximum, 4 min (o alarme usa Minimum de 5 min) | "alarme > 0 por 10 min" em 0 |

- **Por que 10 min nos widgets 4 e 5:** é o período do alarme da razão. Assim o gráfico mostra o mesmo número que o alarme avalia.
- **Com a flag desligada**, os widgets 4 a 6 mostram 0 ou nada: não há leitura da posição própria. Isso é esperado e fica escrito no texto do widget 1.
- **REAL e PAPER:** nenhum widget soma os modos (`project.md`, Forbidden). As métricas desta linha são só do REAL; o P&L PAPER não tem métrica na nuvem.

### 2.2 Texto pronto para `dashboards.tf`

Acrescentar no fim da lista `widgets` de `local.real_dashboard`, depois do widget "Frescor do preço mais velho" (y = 29):

```hcl
      # 261121-paper-real-unico (Observability Setup, Q3; dashboards.md): P&L por modo e quantidade própria.
      { type = "text", x = 0, y = 35, width = 24, height = 2, properties = { markdown = "### P&L por modo e quantidade própria\nRunbook: docs/runbooks/real-operations.md, seções 8 (divergência do P&L REAL), 10 (posição própria) e 13 (STALE_SNAPSHOT). U1 e U3 não publicam métrica na nuvem: use as consultas salvas pnl-* do Logs Insights. Com a flag de quantidade própria desligada, os gráficos da U4 ficam em 0." } },
      {
        type = "alarm", x = 0, y = 37, width = 24, height = 2
        properties = {
          title = "Alarmes do P&L REAL e da quantidade própria"
          alarms = [
            aws_cloudwatch_metric_alarm.real_ops_pnl_reconciliation_mismatch.arn,
            aws_cloudwatch_metric_alarm.real_ops_own_holdings_unavailable.arn,
            aws_cloudwatch_metric_alarm.real_ops_own_holdings_stuck_bots.arn,
          ]
        }
      },
      {
        type = "metric", x = 0, y = 39, width = 6, height = 6
        properties = {
          title       = "Divergências do P&L REAL (verificações por 4 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 240
          metrics     = [concat(local.app_metric["pnl_real_reconciliation_mismatches_total"], [{ label = "verificações divergentes" }])]
          annotations = { horizontal = [{ label = "alarme > 0", value = 0 }] }
        }
      },
      {
        type = "metric", x = 6, y = 39, width = 6, height = 6
        properties = {
          title = "Posição própria: leituras e indisponíveis (10 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 600
          metrics = concat(
            [concat(local.app_metric["real_own_holdings_reads_total"], [{ label = "leituras" }])],
            [for reason in ["BUDGET_EXCEEDED", "READ_FAILED", "BOT_NOT_OWNED"] : [local.ns, "real_own_holdings_unavailable_total", "reason", reason, { label = reason, yAxis = "right" }]],
          )
        }
      },
      {
        type = "metric", x = 12, y = 39, width = 6, height = 6
        properties = {
          title = "Posição própria: razão do alarme e SLI de BUDGET_EXCEEDED (10 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 600
          metrics = [
            [{ expression = "IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)", id = "e1", label = "razão do alarme" }],
            [{ expression = "IF(FILL(m3, 0) > 0, FILL(m1, 0) / m3, 0)", id = "e2", label = "SLI BUDGET_EXCEEDED" }],
            [local.ns, "real_own_holdings_unavailable_total", "reason", "BUDGET_EXCEEDED", { id = "m1", visible = false }],
            [local.ns, "real_own_holdings_unavailable_total", "reason", "READ_FAILED", { id = "m2", visible = false }],
            concat(local.app_metric["real_own_holdings_reads_total"], [{ id = "m3", visible = false }]),
          ]
          annotations = { horizontal = [{ label = "alarme 5%", value = 0.05 }, { label = "SLO 0,1%", value = 0.001 }] }
        }
      },
      {
        type = "metric", x = 18, y = 39, width = 6, height = 6
        properties = {
          title       = "Bots REAL presos (3+ leituras indisponíveis seguidas)", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics     = [concat(local.app_metric["real_own_holdings_stuck_bots"], [{ label = "bots presos" }])]
          annotations = { horizontal = [{ label = "alarme > 0 por 10 min", value = 0 }] }
        }
      },
```

**Conferência offline depois de aplicar:** `terraform fmt -check`, `terraform validate` e `terraform test` (o `dashboard_app_metrics` passa a listar as quatro métricas, todas da lista; as linhas de expressão não entram nessa saída porque o primeiro item não é o namespace). Checkov sem mudança. Rollback: apagar o bloco.

## 3. Nota do gráfico "RDS: conexões" (D-INF2)

O gráfico existe no painel Serviços (`dashboards.tf:222-226`): `AWS/RDS DatabaseConnections` de `eureka-real-app` e `eureka-real-txsigner`, `Maximum`, **5 min**. É hoje o único sinal de conexões: **não existe alarme** (D-INF2, `alarms.md`, seção 5.1).

| Ponto | Nota |
|---|---|
| Por que importa neste intent | no rolling, duas tarefas `api-worker` convivem por alguns minutos: teto teórico de cerca de 127 conexões contra cerca de 112 da `db.t4g.micro` (U2, D-INF2). O pool `pnl-mode-block` da U3 usa até 8 conexões do pool principal |
| Limite do gráfico | com período de 5 min, um pico curto da troca pode ficar escondido pela agregação. O `Maximum` ajuda, mas um gráfico de 1 min mostraria melhor a janela da troca |
| Até o fundador decidir | durante cada implantação, acompanhar este gráfico na janela de observação; uma conexão recusada aparece como 5xx (`deployment-strategy.md`, 1.3) |
| Proposta, junto com a decisão do D-INF2 | trocar `period = 300` por `period = 60` (métrica básica do RDS, sem custo) e acrescentar `annotations = { horizontal = [{ label = "alarme 85%", value = 95 }, { label = "max_connections", value = 112 }] }` com os valores da classe escolhida (190 e 225 na `db.t4g.small`). Custo: 0. Decide o fundador junto com a classe e o alarme |

## 4. Custo

A linha nova entra num painel que já existe. A conta continua com 3 painéis (grátis até 3 por conta, até 50 métricas cada). A linha acrescenta 4 métricas desenhadas e 6 linhas de métrica no widget 5 (3 escondidas) ao painel Visão REAL. As expressões `SEARCH` herdadas continuam sendo o único risco de passar de 50 métricas (nota do 261113). **Custo novo: US$ 0.**

## 5. Conferência depois do primeiro apply da devnet (com a linha aplicada)

```bash
# O painel Visão REAL tem a linha nova (procura o título do widget de texto)
aws cloudwatch get-dashboard --dashboard-name eureka-real-visao-real --query DashboardBody --output text \
  | grep -c "P&L por modo e quantidade própria"     # esperado: 1

# As métricas desenhadas existem em Eureka/App
aws cloudwatch list-metrics --namespace Eureka/App --metric-name real_own_holdings_stuck_bots --query 'length(Metrics)'
```

No console, com o ambiente no ar e a flag desligada: o widget 3 fica em 0 ou vazio, e os widgets 4 a 6 também. Nenhum widget pode mostrar erro de expressão.

## Sources

- `observability-setup-questions.md` (Q3, resumo confirmado) [Q3] [Q1].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/dashboards.md` (painéis herdados, regras de período, custo).
- `operation/environment-provisioning/environment-inventory.md` (seção 2.4: as 6 séries; seção 4: painéis sem mudança) e `security-posture-review.md` (2.2, D-INF2).
- `operation/deployment-pipeline/deployment-strategy.md` (1.3: o gráfico de conexões na troca).
- `construction/u1-mode-skeleton/`, `u2-real-bot-pnl/`, `u3-pnl-views/` e `u4-own-quantity-cycle/` `infrastructure-design/monitoring-design.md` (seção "Painéis": nenhum painel novo; D-INF3).
- Código lido no commit `09de0a8`: `infra/terraform/modules/observability/dashboards.tf` (posições, sintaxe dos widgets, `dashboard_app_metrics`, gráfico do RDS nas linhas 222-226), `app-metrics.tf` (lista), `real-ops.tf` (nomes dos alarmes).
- `aidlc/spaces/default/memory/project.md` (Forbidden: nunca somar PAPER e REAL; correção do CloudWatch Agent a 120 s).

## Assumptions & Open Questions

1. [assumption] Os widgets 1 a 6 ficam abaixo do último widget atual (y = 29, altura 6); a grade do CloudWatch aceita o painel crescer para baixo.
2. [assumption] Um widget de métrica aceita linhas de expressão e linhas escondidas (`visible = false`) com `id`, como as linhas `SEARCH` que o arquivo já usa.
3. Aberta: aplicar o texto da seção 2.2. A Q3 decidiu a linha, mas este estágio não muda IaC. Dono: o responsável, numa volta à Construction ou no intent de infraestrutura do D-INF2 e do IR-G3.
4. Aberta: a nota do gráfico do RDS (período de 1 min e linhas) depende da decisão do D-INF2. Dono: fundador.
5. [assumption] Um widget de log (Logs Insights) para os eventos da U1 e da U3 não entrou: ele roda a consulta a cada abertura do painel e cobra pelo volume lido. As consultas salvas cobrem o caso sob demanda.
