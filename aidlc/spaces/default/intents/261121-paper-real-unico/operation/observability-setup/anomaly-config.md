# Detecção de anomalia — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Observability Setup, Q3 (opção A), com o resumo confirmado. Data: 2026-11-25.

> **Nada está implantado** [Q1]. **Este intent não acrescenta detecção de anomalia** [Q3]. Os dois detectores do 261113 continuam como estão.

## 1. O que é herdado sem mudança

Do `anomaly-config.md` do `261113-trading-real-bot`, em `infra/terraform/modules/observability/anomaly.tf`:

| Alarme | Métrica | Faixa | Severidade |
|---|---|---|---|
| `eureka-real-anomaly-real-orders` | `Eureka/App real_orders_total`, Sum de 6 min | acima de 3 desvios em 3 de 3 períodos; `notBreaching` | aviso |
| `eureka-real-anomaly-real-refusals` | `Eureka/App real_risk_evaluations_total{outcome=REJECTED}`, Sum de 6 min | igual | aviso |

Custo herdado: US$ 0,60 por mês por ambiente. As ordens REAL dos bots deste intent (inclusive as vendas cortadas pela quantidade própria, depois da virada da flag) continuam contadas em `real_orders_total`, então o primeiro detector já as vê.

## 2. Por que nenhum detector novo

| Candidata | Por que não |
|---|---|
| `pnl_real_reconciliation_mismatches_total` | O valor normal é **0**, e qualquer valor acima de 0 já dispara o alarme fixo crítico `real-ops-pnl-reconciliation-mismatch`. Uma faixa aprendida sobre uma série quase sempre zero não acrescenta sinal. |
| `real_own_holdings_reads_total` | O volume depende da flag (hoje **desligada**: a série fica em 0) e do número de bots REAL com venda candidata. Uma mudança de volume é decisão de produto (virada da flag, novos convidados), não defeito. O modelo precisaria de cerca de 2 semanas de dados com a flag ligada, e a flag depende da F7. |
| `real_own_holdings_unavailable_total{reason}` e a razão | Já têm limites fixos ligados ao SLO (razão > 5%; gauge de bot preso > 0) e a proposta de consumo lento OBS-G6 (`alarms.md`, 5.2). Um limite fixo diz quando o SLO está em risco; uma faixa aprendida aprenderia também a degradação da semente B como "normal". |
| `real_own_holdings_stuck_bots` | Gauge com valor normal 0; o alarme fixo dispara em qualquer bot preso por 10 min. |
| Métricas da U1 e da U3 (blocos `UNAVAILABLE`, recusas, falhas da auditoria) | Não estão no CloudWatch (D-INF3; ficam só no Prometheus). Uma anomalia exigiria pôr a métrica na lista (≈ US$ 0,30 por série) e o detector. |

**Motivo resumido:** volume baixo de piloto (o fundador e até 20 convidados), séries com valor normal 0 que já têm alarme fixo, e a série com volume (`reads`) parada até a virada da flag. Os alarmes fixos bastam [Q3].

## 3. Custo evitado

Um alarme de detecção de anomalia conta como 3 métricas de alarme (a métrica e as duas bordas da faixa), a cerca de US$ 0,10 cada: **≈ US$ 0,30 por detector, por mês e por conta.** Um detector em cada uma das três séries da U4 com volume custaria ≈ US$ 0,90 por conta (≈ US$ 1,80 nas duas contas), sem sinal útil enquanto a flag estiver desligada. Uma anomalia sobre métricas da U1 ou da U3 somaria ainda ≈ US$ 0,30 por série publicada.

## 4. Quando rever

No estágio de Performance Monitoring, depois da virada da flag da U4 e de pelo menos 2 semanas de dados na mainnet: se o volume de `real_own_holdings_reads_total` tiver um padrão estável por hora e por dia da semana, um detector "acima da faixa" pode avisar laço de bot. Decide o fundador. A regra de período de 6 min (três leituras de 120 s) e de exclusão dos ensaios de carga do treino vale como no 261113.

## Sources

- `observability-setup-questions.md` (Q3, resumo confirmado) [Q3] [Q1].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/anomaly-config.md` (detectores, período, custo).
- `construction/u3-pnl-views/infrastructure-design/monitoring-design.md` (D-INF3) e `u4-own-quantity-cycle/infrastructure-design/monitoring-design.md` (métricas e alarmes da U4).
- `construction/build-and-test/test-results.md` (seção 10: flag desligada até a F7).
- Código lido: `infra/terraform/modules/observability/anomaly.tf` (por referência do 261113) e `app-metrics.tf` (lista de permissão).
- `aidlc/spaces/default/memory/project.md` (orçamento de US$ 350, CloudWatch a 120 s).

## Assumptions & Open Questions

1. [assumption] O preço de 3 métricas de alarme por detector é o mesmo usado pelo 261113, de referência da `us-east-1`.
2. [assumption] O volume de piloto do 261113 (fundador e até 20 convidados) continua a base.
