# Painéis do CloudWatch — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Observability Setup, Q4 (opção A).

> **Nada está implantado.** Não existe conta AWS nem ambiente. Os três painéis existem só como Terraform em `infra/terraform/modules/observability/dashboards.tf`, validado offline. Eles só aparecem no console depois do primeiro `apply` da devnet. Os comandos de conferência estão no fim do arquivo.

## Visão geral

| Painel | Nome no CloudWatch | Para que serve |
|---|---|---|
| Visão REAL | `eureka-real-visao-real` | dinheiro em movimento: ordens, recusas da barreira, carteiras, saques, divergências, kill switch e saídas condicionais |
| Serviços | `eureka-real-servicos` | sinais de ouro do api, batimento dos processos, ECS, RDS, TxSigner e fornecedores |
| Segurança | `eureka-real-seguranca` | KMS, recusas da política do TxSigner, credenciais, GuardDuty, Access Analyzer e os alarmes `-security-*` |

`eureka-real` é o valor padrão de `var.name`. As métricas das aplicações vêm do namespace `Eureka/App`, publicado pelo contêiner auxiliar do CloudWatch Agent. Todas elas estão na lista de permissão de `app-metrics.tf`, e o teste offline `observability_metrics.tftest.hcl` falha se um painel desenhar uma métrica de `Eureka/App` fora da lista. As outras métricas vêm de namespaces da própria AWS. Nenhum widget usa mint, endereço ou pessoa: as dimensões são só os rótulos fechados da lista.

## Visão REAL

| Widget | Métricas | Estatística e período |
|---|---|---|
| Texto | título e os runbooks (`docs/runbooks/real-execution.md`, `alarms.md`) | — |
| Alarmes do REAL | divergência aberta, kill switch por 30 min, pausa de restauração, ordem AMBIGUOUS e SENT antiga, SIGNING_REFUSED, TxSigner indisponível, falhas da barreira, cancelamento parado, preço velho, as duas anomalias e os dois alarmes de consumo do SLO de frescor | estado do alarme |
| Ordens REAL | `real_orders_total` (todos os eventos) e `real_orders_total{code=SIGNING_REFUSED}` | Sum, 5 min |
| Barreira REAL | `real_risk_evaluations_total{outcome=APPROVED}`, `{outcome=REJECTED}` e `real_risk_errors_total` | Sum, 5 min |
| Carteiras do bot ativas | `real_bot_wallets_active` | Maximum, 5 min (valor único) |
| Saques por estado | `SEARCH` em `realwallet_withdrawals_total{state}` | Sum, 6 min |
| Saque pendente mais antigo | `SEARCH` em `realwallet_withdrawal_oldest_pending_seconds{state}`, com linhas em 300 s (AMBIGUOUS) e 600 s (parado) | Maximum, 4 min |
| Divergências e reconciliação | `real_divergences_open` e `real_reconciliation_deferred_over_2h` | Maximum, 4 min |
| Kill switch e restauração | `real_kill_switch_active` e `real_restore_pause_active{phase=RESTORE|CHECK_PENDING}` | Maximum, 4 min |
| Ordens antigas | `real_orders_ambiguous_oldest_seconds`, `real_orders_sent_oldest_seconds` e `real_orders_ambiguous_open` (eixo direito), com linhas em 75 s e 300 s | Maximum, 4 min |
| Saídas condicionais | `real_conditional_fills_total` e `real_conditional_cancels_total` | Sum, 5 min |
| Saídas paradas | `real_conditional_cancelling_oldest_seconds` e `real_conditional_unread_oldest_seconds` | Maximum, 4 min |
| Frescor do preço | `marketdata_oldest_price_age_seconds`, com linha em 60 s (base do SLO de 99%) | Maximum, 4 min |

## Serviços

| Widget | Métricas | Estatística e período |
|---|---|---|
| Alarmes | `metrics-absent-api`, `-worker`, `-txsigner` e os quatro alarmes de consumo do SLO de disponibilidade | estado do alarme |
| api: tráfego | `AWS/ApplicationELB RequestCount` do grupo-alvo do api | Sum, 1 min |
| api: latência | `TargetResponseTime` do grupo-alvo do api | p50, p95, p99, 1 min |
| api: erros | `HTTPCode_Target_5XX_Count` (api), `HTTPCode_ELB_5XX_Count` (balanceador) e `UnHealthyHostCount` | Sum e Maximum, 1 min |
| Batimento | `process_uptime_seconds{job=api|worker|txsigner}` | SampleCount, 4 min (2 = normal; 0 = processo sem métricas) |
| ECS: CPU | `ECS/ContainerInsights CpuUtilized` de api-worker, txsigner, web e admin | Average, 1 min |
| ECS: memória e tarefas | `MemoryUtilized` dos quatro serviços e `RunningTaskCount` de api-worker e txsigner | Average e Minimum, 1 min |
| RDS: CPU, conexões e espaço livre | `CPUUtilization`, `DatabaseConnections` e `FreeStorageSpace` de `eureka-real-app` e `eureka-real-txsigner` | Average, Maximum e Minimum, 5 min |
| TxSigner: pior latência | `txsigner_latency_seconds_max` e `txsigner_kms_sign_seconds_max`, com linha em 1 s | Maximum, 4 min |
| Fornecedores | `real_upstream_failures_total{provider=TXSIGNER|RPC|JUPITER}` e `real_upstream_pauses_total` | Sum, 5 min |
| Trabalhos | `SEARCH` em `real_(risk|execution|ops|exits)_job_runs_total{job}` | Sum, 6 min |

**Período dos widgets:** o agente lê a cada 120 s. Por isso, os widgets de medidores de `Eureka/App` usam 4 min (duas leituras) e as expressões `SEARCH` de contadores usam 6 min (três leituras), para não mostrar buracos a cada minuto. Os widgets de métricas da AWS (balanceador, ECS, RDS) continuam com 1 min.

**Latência no painel:** o p95 do api vem do balanceador, porque o agente descarta histogramas, inclusive `http_server_requests` (ver `alarms.md`, lacuna OBS-G4). No TxSigner, o painel mostra o pior valor (`_max`): o p95 do TxSigner não chega ao CloudWatch.

## Segurança

| Widget | Métricas | Estatística e período |
|---|---|---|
| Texto | lembra que mudança do caminho de assinatura não feita pelo fundador é P1, com o kill switch REAL primeiro | — |
| Alarmes de segurança | os quatro `-security-*` (SP-11), `kms-admin-action`, `kms-keys-per-hour`, `tx-signer-kms-errors`, `real-execution-policy-refused` e `real-exits-trigger-policy-refused` | estado do alarme |
| KMS pela trilha | `Eureka/Kms KmsAdministrativeActions` e `KmsKeysCreated` (filtros do CloudTrail) | Sum, 5 min |
| KMS do TxSigner | `txsigner_kms_errors_total{kind=THROTTLED|ERROR}` e `AWS/Usage CallCount` de `KMS/API/Sign` (eixo direito) | Sum, 5 min |
| Chaves órfãs e credenciais | `txsigner_orphan_keys` (Maximum) e `txsigner_auth_failures_total` (Sum) | 5 min |
| Recusas da política | `SEARCH` em `txsigner_decisions_total{kind,decision=REFUSED}` | Sum, 6 min |
| Swaps e Trigger recusados | `txsigner_swap_decisions_total{outcome=REFUSED}` e `txsigner_trigger_refusals_total` | Sum, 5 min |
| GuardDuty e Access Analyzer | `AWS/Events MatchedEvents` das regras `guardduty-high` e `access-analyzer-active`, e `FailedInvocations` da regra do GuardDuty | Sum, 1 h |
| Caminho de assinatura | as quatro métricas de `Eureka/Security` (SP-11) | Sum, 5 min |

O GuardDuty não publica métricas próprias no CloudWatch. Por isso o painel mostra os achados encaminhados pela regra do EventBridge, a mesma que manda o e-mail.

## Custo

A conta tem 3 painéis grátis, com até 50 métricas cada. Se ela tiver outro painel, cada um a mais custa US$ 3 por mês. As expressões `SEARCH` podem passar de 50 métricas quando os rótulos crescerem; nesse caso, o painel é cobrado. A estimativa completa está em `alarms.md`, seção "Custo".

## Conferência depois do primeiro apply da devnet

```bash
# Os três painéis existem
aws cloudwatch list-dashboards --dashboard-name-prefix eureka-real- --query 'DashboardEntries[].DashboardName'

# O corpo de cada painel foi aceito sem erro de validação
for d in visao-real servicos seguranca; do
  aws cloudwatch get-dashboard --dashboard-name "eureka-real-$d" --query DashboardName --output text
done

# As métricas das aplicações chegaram (sem isso, os widgets de Eureka/App ficam vazios)
aws cloudwatch list-metrics --namespace Eureka/App --query 'length(Metrics)'
```

Depois, abrir cada painel no console e conferir que nenhum widget mostra "No data available" por mais de 15 minutos com o ambiente no ar. Os widgets de saídas condicionais e de saques podem ficar vazios até a primeira operação.

## Sources

- `observability-setup-questions.md` (Q4, resumo confirmado).
- `construction/u1-bot-wallet/infrastructure-design/monitoring-design.md` (painel "Carteiras REAL").
- `construction/u2-real-market-data/infrastructure-design/monitoring-design.md` a `u7-exits-and-fiscal/infrastructure-design/monitoring-design.md`.
- `infra/terraform/modules/observability/dashboards.tf` e `app-metrics.tf`.
- Documentação da AWS sobre a conversão de métricas do Prometheus pelo CloudWatch Agent: <https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights-Prometheus-metrics-conversion.html>.
