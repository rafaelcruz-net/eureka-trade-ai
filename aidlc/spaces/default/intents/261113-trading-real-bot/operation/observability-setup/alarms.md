# Alarmes do CloudWatch — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Observability Setup. Decisões: Q1 a Q7, todas na opção A, com o resumo confirmado.

> **Nada está implantado.** Não existe conta AWS. Todos os alarmes, o contêiner auxiliar do agente e as permissões são Terraform validado offline (`terraform validate`, `terraform test` com provedor simulado, Checkov) e um ensaio local do agente sem rede. **Cada alarme só fica verificado depois do primeiro `apply` da devnet**, com os comandos da última seção.

## 1. Como as métricas chegam ao CloudWatch (Q1)

- Um contêiner auxiliar `metrics-agent` (CloudWatch Agent) roda na tarefa `api-worker` e na tarefa do TxSigner. Arquivo: `infra/terraform/modules/services/metrics-agent.tf`.
- A cada 120 s (decisão do responsável em 2026-11-20), ele lê `/actuator/prometheus` pela rede local da tarefa:
  - api: `127.0.0.1:8081`;
  - worker: `127.0.0.1:8082`;
  - TxSigner: `127.0.0.1:8444`.
- Ele publica pelo formato EMF do CloudWatch Logs, nos grupos `/ecs/eureka-real/metrics/api-worker` e `/ecs/eureka-real/metrics/txsigner`, com 7 dias de retenção. O CloudWatch extrai as métricas desses eventos e as guarda por 15 meses.
- **Lista de permissão:** fica em `infra/terraform/modules/observability/app-metrics.tf`, com 44 métricas e 48 pares de métrica e dimensões. Ela é a fonte única. O módulo `services` monta dela o filtro `keep` do Prometheus e as declarações EMF do agente. Nada fora da lista sai da tarefa.
- O código da aplicação não mudou. As regras do Prometheus continuam valendo no ambiente local e na CI.
- **Imagem:** `public.ecr.aws/cloudwatch-agent/cloudwatch-agent`, versão `1.300074.1b2005`, de 27/10/2026. O manifesto `linux/amd64` tem o digest `sha256:cc169bdfffee5f795cd0a534660d6721d987b1cb9e0a39df8174147a36ae0086`, lido no registro público em 20/11/2026.
  - O fundador copia a imagem para o repositório próprio `eureka-real/cloudwatch-agent`, que é imutável e tem varredura no envio. A cópia usa o mesmo digest.
  - A tarefa usa a imagem fixada pelo digest (`var.cloudwatch_agent_image_digest`). Os papéis de execução só puxam de repositórios próprios, e a subrede do TxSigner não tem internet.

### Mudanças de namespace e de período forçadas pelo agente

O ensaio offline com a imagem fixada confirmou quatro fatos (seção 8):

1. **Um namespace só.** Uma configuração do agente tem um único `metric_namespace`. Por isso todos os alarmes das U1 a U7 passaram de `Eureka/RealRisk`, `Eureka/RealExecution`, `Eureka/RealOps`, `Eureka/RealExits`, `Eureka/MarketData` e `Eureka/RealBotWallet` para **`Eureka/App`**. Os testes de paridade foram ajustados junto, como os próprios arquivos pediam.
2. **Histogramas são descartados.** O agente só aceita gauge, counter e summary. Um histograma é descartado inteiro, mesmo quando uma regra de relabel muda o nome da série. Isso gera as lacunas OBS-G1, OBS-G2 e OBS-G4.
3. **Contadores viram deltas** a cada leitura. Gauges e quantis de summary passam como estão. Um conjunto de dimensões vazio (`[]`) publica a métrica sem dimensão.
4. **Resolução padrão de 60 s.** O alarme `real-price-stale` tinha período de 30 s e `treat_missing_data = breaching`. Com métricas de 60 s, ele veria dado ausente a cada duas avaliações e dispararia sozinho.

### Janelas com uma leitura a cada 120 s (decisão do responsável, 2026-11-20)

O responsável pediu leitura mais espaçada. O período de um alarme do CloudWatch só pode ser de 10 s, de 30 s ou múltiplo de 60 s. Por isso a decisão foi aplicada ao agente (`scrape_interval = "120s"`), e as janelas dos alarmes passaram a ser escolhidas para sempre conter leituras. Uma leitura a cada 120 s deixa um minuto sim e outro não sem dado.

| Tipo de alarme | Regra da janela | Por quê |
|---|---|---|
| Dado ausente conta como violação (`breaching`): batimento, `market-source-failed`, `real-price-stale` e todos os `*-job-stalled-*` | cada período é múltiplo de **240 s**; a janela é a do Prometheus arredondada para cima a 240 s | Um período de 240 s sempre tem **duas** leituras. Uma leitura atrasada ou perdida não deixa o período vazio, então o alarme nunca dispara só pelo ritmo da leitura. Escolhi período de 240 s (e não 60 s com M de N) porque, com 60 s, metade dos minutos fica vazia por natureza e um `breaching` dispararia à toa. |
| Dado ausente não conta (`notBreaching`) | cada período tem **pelo menos 180 s**; a janela é a do Prometheus, ou 180 s quando ela é menor | Um período de 180 s sempre tem pelo menos uma leitura. Os medidores de idade (ordem mais antiga, divergência) mostram o problema na leitura seguinte. Os contadores somam o que aconteceu desde a leitura anterior, então nenhum evento some. As janelas de 5 min ou mais não mudaram. |

- **Nenhum limite mudou.** Só cresceram as janelas que precisavam de leituras.
- **Regras do Prometheus:** não mudaram. A paridade compara a janela da nuvem com a janela local transformada por essa regra (`com.memetrade.infra.CloudWatchWindows`, usada pelos cinco `*AlarmParityTest`).
- **Testes que travam a regra:** o teste `observability_metrics.tftest.hcl` exige que o agente leia a cada 120 s e que todo período em `Eureka/App` siga a regra. Um teste de mutação confirmou que um período de 180 s num alarme de trabalho parado faz o teste falhar.
- **Custo da escolha:** a detecção fica mais lenta. Os casos mais afetados:
  - `real-price-stale`: de 90 s para 4 min;
  - trabalhos de 1 min: para 4 min;
  - trabalhos de 5 min: para 8 min;
  - batimento: de 2 min para 4 min;
  - medidores de idade de 1 min: para 3 min.

## 2. Destino, severidade e escalonamento (Q7)

| Severidade | Quando | Destino | Resposta esperada |
|---|---|---|---|
| crítica | dinheiro parado ou em risco, assinatura recusada, processo sem métricas | tópico SNS `eureka-real-alarms` (chave KMS própria), e-mail do fundador | abrir registro em `docs/incidents/`; kill switch REAL se houver dúvida sobre fundos |
| alta | segurança, KMS, fonte de mercado, kill switch longo, consumo rápido do orçamento de erro (1 h) | mesmo tópico | tratar no mesmo dia |
| média | desempenho, orçamento de chamadas, consumo lento do orçamento (6 h) | mesmo tópico | tratar em até 2 dias úteis |
| aviso | anomalias, p95 informativos, liberação bloqueada, reserva expirada | mesmo tópico | ler no próximo turno |

- **Plantão:** o fundador é o único responsável, por e-mail.
- **Escalonamento:** fundador → contatos **a definir** no estágio Incident Response (segundo responsável, contato jurídico e provedores de RPC e KMS).
- O SMS citado no desenho das Units fica para quando existir um segundo canal. Hoje o tópico tem só a assinatura de e-mail (`alarm_email`).
- Os alarmes de batimento também mandam aviso quando voltam ao normal (`ok_actions`).

## 3. Alarmes

Todos vão para o tópico `eureka-real-alarms`. Nas tabelas, "breaching" quer dizer `treat_missing_data = breaching`, ou seja, dado ausente conta como violação. As colunas Métrica e Limite trazem o namespace quando ele não é `Eureka/App`.

### 3.1 Novos neste estágio

| Alarme | Métrica | Limite e janela | Severidade | Fecha |
|---|---|---|---|---|
| `metrics-absent-api` | `process_uptime_seconds{job=api}`, SampleCount | < 1 em 4 min (1 período de 240 s, duas leituras); breaching | crítica | **SP-12** (Q2) |
| `metrics-absent-worker` | `process_uptime_seconds{job=worker}` | igual | crítica | **SP-12**; U1 "Worker fora do ar" |
| `metrics-absent-txsigner` | `process_uptime_seconds{job=txsigner}` | igual | crítica | **SP-12**; U1-NFR5.11 (TxSigner fora do ar; local 2 min, nuvem 4 min) |
| `slo-api-availability-burn-1h` | `AWS/ApplicationELB`: (5xx do alvo + 5xx do balanceador) / pedidos do grupo-alvo do api | razão > 0,072 em 1 h; mínimo de 10 pedidos | alta | Q3, U1:NFR4.1 |
| `slo-api-availability-burn-6h` | igual | razão > 0,03 em 6 h | média | Q3, U1:NFR4.1 |
| `slo-txsigner-availability-burn-1h` | intervalo de 240 s sem batimento do TxSigner | ≥ 2 intervalos ruins em 15; breaching | alta | Q3, U1:NFR4.1 |
| `slo-txsigner-availability-burn-6h` | igual | ≥ 3 intervalos ruins em 90; breaching | média | Q3 |
| `slo-freshness-burn-1h` | intervalo de 240 s com `marketdata_oldest_price_age_seconds` > 60 s, ou sem dado | ≥ 3 intervalos ruins em 15; breaching | alta | Q3, U2:NFR4.1 |
| `slo-freshness-burn-6h` | igual | ≥ 6 intervalos ruins em 90; breaching | média | Q3 |
| `anomaly-real-orders` | `real_orders_total`, Sum de 6 min (três leituras) | acima da faixa de 3 desvios em 3 de 3 períodos | aviso | Q7 |
| `anomaly-real-refusals` | `real_risk_evaluations_total{outcome=REJECTED}`, Sum de 6 min | igual | aviso | Q7 |
| `real-risk-slow` | `real_risk_evaluation_p95_seconds{side=BUY,quantile=0.95}`, Maximum | > 0,15 s em 2 de 2 períodos de 5 min (10 min) | aviso | **U3-NFR5.11**: escrito e em paridade, **desligado** até a OBS-G1 |
| `real-execution-slow` | `real_order_decision_to_send_p95_seconds{mode=AUTO_FULL,quantile=0.95}`, Maximum | > 3 s em 2 de 2 períodos de 5 min (10 min) | aviso | **U4-NFR5.13**: escrito e em paridade, **desligado** até a OBS-G2 |

Os dois alarmes de p95 só são criados com `p95_alarms_enabled = true`, uma variável da raiz que vale `false` por padrão. Ligá-los antes de o código publicar o summary criaria alarmes verdes para sempre, que é o defeito do SP-12. A matemática dos alarmes de SLO está em `slo-config.md`.

### 3.2 Existentes, agora com métrica publicada

Nenhum limite mudou. As janelas seguem a regra da leitura a cada 120 s (seção 1); entre parênteses, a janela da regra local quando ela é diferente. A paridade com as regras do Prometheus continua coberta pelos testes `*AlarmParityTest`.

| Alarme | Métrica (`Eureka/App` salvo indicação) | Limite e janela | Severidade |
|---|---|---|---|
| `tx-signer-kms-errors` | `txsigner_kms_errors_total{kind=THROTTLED}` + `{kind=ERROR}` | > 3 em 5 min | alta |
| `kms-admin-action` | `Eureka/Kms KmsAdministrativeActions` (filtro do CloudTrail) | ≥ 1 em 5 min | alta |
| `kms-keys-per-hour` | `Eureka/Kms KmsKeysCreated` | > 10 em 1 h | alta |
| `security-founder-role-iam-change`, `security-founder-family-registered`, `security-founder-family-run`, `security-txsigner-service-updated` | `Eureka/Security` (filtros do CloudTrail, SP-11) | ≥ 1 em 5 min | alta; P1 se o fundador não fez a mudança |
| `market-source-failed` | `marketdata_source_state{source=coingecko,state=FAILED}` | ≥ 1 nas duas leituras de 4 min (Minimum; local 2 min); breaching | alta |
| `real-price-stale` | `marketdata_oldest_price_age_seconds`, Minimum | > 60 s nas duas leituras de 4 min (local 90 s); breaching | alta |
| `market-call-counter-unavailable` | `marketdata_provider_conservative_mode{source}` | ≥ 1 em todas as leituras de 5 min (Minimum) | alta |
| `market-monthly-budget` | `marketdata_provider_budget_ratio{source}` | ≥ 0,8 em 5 min | média |
| `market-invalid-values` | `marketdata_invalid_values_total` / `marketdata_observations_total` | > 5% em 1 h | média |
| `market-provider-unstable` | `marketdata_provider_calls_total{source,outcome=retry}` / `{source}` | > 10% em 1 h | média |
| `real-risk-errors` | `real_risk_errors_total` | > 5 em 5 min | crítica |
| `real-risk-job-stalled-daily-loss` | `real_risk_job_runs_total{job=daily-loss}` | < 1 em 8 min (local 5 min); breaching | crítica |
| `real-risk-job-stalled-day`, `-reservation-expiry` | `real_risk_job_runs_total{job}` | < 1 em 16 min (local 15 min); breaching | crítica |
| `real-risk-reservation-expired` | `real_risk_reservations_expired_total` | > 0 em 15 min | aviso |
| `real-execution-ambiguous-old` | `real_orders_ambiguous_oldest_seconds` | > 300 s em 3 min (local 1 min) | crítica |
| `real-execution-sent-old` | `real_orders_sent_oldest_seconds` | > 75 s em 3 min (local 1 min) | crítica |
| `real-execution-policy-refused` | `txsigner_swap_decisions_total{outcome=REFUSED}` | > 0 em 5 min | crítica |
| `real-execution-signing-refused` | `real_orders_total{code=SIGNING_REFUSED}` | > 0 em 5 min | crítica |
| `real-execution-signer-unavailable` | `real_upstream_failures_total{provider=TXSIGNER}` | > 2 em 5 min | crítica |
| `real-execution-upstream-pause` | `real_upstream_pauses_total` | > 0 em 5 min | alta |
| `real-execution-job-stalled-{queue,driver,confirmation}` | `real_execution_job_runs_total{job}` | < 1 em 4 min (local 1 min); breaching | crítica |
| `real-execution-job-stalled-{ambiguous,signing-deadline,health,liquidation}` | igual | < 1 em 8 min (local 5 min); breaching | crítica |
| `real-ops-divergence-open` | `real_divergences_open` | > 0 em 3 min (local 1 min) | crítica |
| `real-ops-restore-pause` | `real_restore_pause_active{phase=RESTORE|CHECK_PENDING}` | > 0 em 3 min (local 1 min) | crítica |
| `real-ops-kill-switch-on` | `real_kill_switch_active` | ≥ 1 em 6 de 6 × 5 min | alta |
| `real-ops-reconciliation-deferred` | `real_reconciliation_deferred_over_2h` | > 0 em 5 min | alta |
| `real-ops-txsigner-command-pending` | `real_pending_txsigner_oldest_seconds` | > 900 s em 3 min (local 1 min) | alta |
| `real-ops-release-blocked` | `real_release_blocked_oldest_seconds` | > 3600 s em 5 min | aviso |
| `real-ops-job-stalled-{gates,reconciliation,kill-switch-cancel,txsigner-resend,ops-gauges,purge}` | `real_ops_job_runs_total{job}` | janelas de 12, 32, 4, 4 e 4 min e 26 h (local 10, 30, 3, 3 e 2 min e 26 h); breaching | crítica |
| `real-ops-job-stalled-restore-check` | `real_ops_job_runs_total{job=restore-check}` com `real_restore_pause_active{phase=CHECK_PENDING}` | sem execução em 4 min com conferência pendente (local 3 min) | crítica |
| `real-exits-cancelling-stalled` | `real_conditional_cancelling_oldest_seconds` | > 900 s em 3 min (local 1 min) | crítica |
| `real-exits-conditional-unread` | `real_conditional_unread_oldest_seconds` | > 7200 s em 5 min | alta |
| `real-exits-trigger-policy-refused` | `txsigner_trigger_refusals_total` | > 0 em 5 min | alta |
| `real-exits-job-stalled-{read,cancel,exits-gauges,purge}` | `real_exits_job_runs_total{job}` | 4, 4 e 4 min e 26 h (local 2, 3 e 2 min e 26 h); breaching | crítica |

**Totais:** 62 alarmes ativos e 2 desligados (os de p95). O teste `observability_metrics.tftest.hcl` confere quatro coisas:

- cada alarme lê uma métrica da lista de permissão, com um dos conjuntos de dimensões declarados, ou uma métrica de fonte real (filtros do CloudTrail ou o balanceador);
- todo período é múltiplo de 60 s e segue a regra da leitura a cada 120 s: múltiplo de 240 s nos alarmes `breaching` e de trabalho parado, e pelo menos 180 s nos demais;
- todos os alarmes avisam o tópico;
- a lista de alarmes de `app-metrics.tf` bate com os blocos `resource` dos arquivos, de modo que um alarme novo fora da lista falha o teste.

### 3.3 Limites abaixo da quebra do SLO

Pela regra da fase Operation, todo limite fica abaixo da quebra do SLO:

- os alarmes de consumo disparam com 2% (1 h) e 5% (6 h) do orçamento de 30 dias gasto;
- os alarmes de trabalho parado e de batimento disparam em até 32 minutos (a maioria em 4 a 8), muito antes de um mês de 99,5% estar em risco, porque 0,5% de 30 dias são 216 minutos;
- `real-price-stale` dispara em 4 minutos, e o SLO de frescor permite 432 minutos ruins no mês.

## 4. O que este estágio fecha

| Item | Situação |
|---|---|
| **SP-12** (alarmes leem métricas que ninguém publica) | **Resolvido na IaC, pendente de verificação na devnet.** O agente publica as 44 métricas da lista em `Eureka/App`. Há um alarme de métrica ausente por processo (`breaching`), e um teste offline impede alarme sem fonte. |
| **U3-NFR5.11** (p95 da barreira) | Alarme `real-risk-slow` escrito, em paridade com `RealRiskSlow` (`RealRiskAlarmParityTest`) e desligado. Continua `Unverified` até a OBS-G1 e a devnet. |
| **U4-NFR5.13** (p95 da execução) | Alarme `real-execution-slow` escrito, em paridade com `RealExecutionSlow` (`RealExecutionAlarmParityTest`) e desligado. Continua `Unverified` até a OBS-G2 e a devnet. |

## 5. Lacunas de métrica (o código não publica o que o alarme precisa)

Nenhum código de aplicação foi alterado neste estágio. As lacunas ficam para a Code Generation.

| ID | Alarme ou consulta | Lacuna | Mudança de código sugerida | Bloqueia |
|---|---|---|---|---|
| **OBS-G1** | `real-risk-slow` (U3-NFR5.11) | `real_risk_evaluation_seconds` é histograma, e o agente o descarta | Registrar ao lado um Timer `real_risk_evaluation_p95` com `publishPercentiles(0.95)` e sem buckets, com o rótulo `side`. Ele sai como summary `real_risk_evaluation_p95_seconds{side,quantile="0.95"}`. Depois, `p95_alarms_enabled = true`. | nada (meta informativa) |
| **OBS-G2** | `real-execution-slow` (U4-NFR5.13) | `real_order_decision_to_send_seconds` é histograma | Timer `real_order_decision_to_send_p95` com `publishPercentiles(0.95)` e o rótulo `mode` | nada (meta informativa) |
| **OBS-G3** | consulta `erros-5xx-por-componente` | o log de erro do `ProblemDetailsHandler` não traz a rota | Incluir método e rota (o padrão, não a URL com IDs) no log de 5xx | nada |
| **OBS-G4** | p95 que só existem no Prometheus local | `http_server_requests`, `real_order_send_to_final`, `real_decision_queue_wait`, `real_conditional_fill_lag` e `real_risk_lock_wait` são histogramas | O api é medido pelo balanceador. Para os outros, a mesma solução da OBS-G1, se o fundador quiser alarme na nuvem. | nada |

**Observação sobre a regra local `TxSignerSlow`:** ela lê `txsigner_latency_seconds_bucket`, mas o Timer `txsigner_latency` é registrado sem histograma. Por isso a série `_bucket` não existe, e a regra local nunca dispara. Não é lacuna da nuvem: o painel usa `txsigner_latency_seconds_max`. O defeito fica registrado para a Code Generation (U1).

## 6. Itens em aberto

| ID | O quê | Dono | Antes de |
|---|---|---|---|
| OBS-O1 | Os alertas da U1 em `real-alerts.yml` ainda não têm gêmeo na nuvem: saque recusado, chave órfã, saque AMBIGUOUS e parado, falhas de credencial, recuperação parada, recusas de consentimento, depósito desconhecido e credencial antiga. As métricas `txsigner_decisions_total`, `txsigner_orphan_keys`, `txsigner_auth_failures_total` e `realwallet_withdrawal_oldest_pending_seconds` já estão na lista e nos painéis. Falta escrever os alarmes com as anotações de paridade. | próxima volta deste estágio | mainnet |
| OBS-O2 | O `deploy.yml` (cd-config.md) exige `MEMETRADE_REAL_NETWORK` em todos os contêineres da família `api-worker`. O contêiner auxiliar repete a variável só para passar nessa conferência. A conferência deveria olhar só os contêineres `api` e `worker`. | Deployment Pipeline | — |
| OBS-O3 | Conferir na devnet se os alarmes `breaching` de 240 s (batimento, `market-source-failed`, `real-price-stale`, trabalhos de 1 a 3 min) disparam sozinhos pelo atraso de entrega do EMF. Se dispararem, usar 2 de 3 períodos de 240 s. | este estágio, na devnet | mainnet |
| OBS-O4 | Ligar `p95_alarms_enabled` depois da OBS-G1 e da OBS-G2 | fundador | — |
| OBS-O5 | Contatos de escalonamento | Incident Response | convidados |

## 7. Implicações de segurança e avaliação de risco

As mudanças de IAM e de imagem exigem avaliação de risco pela regra da fase Operation.

| Mudança | Risco | Mitigação |
|---|---|---|
| A tarefa `api-worker` ganha seu primeiro papel de tarefa (`eureka-real-api-worker-task`). Antes, ela não tinha nenhuma permissão AWS (NFR1.2). | O código do api e do worker também pode usar essas credenciais. Um processo comprometido poderia gravar pontos falsos no próprio grupo de métricas, por exemplo para esconder um trabalho parado. | Uma única instrução: `logs:CreateLogStream`, `PutLogEvents` e `DescribeLogStreams` só no grupo `/ecs/eureka-real/metrics/api-worker`. O papel não lê segredo, chave, log nem métrica. A confiança é só de tarefas ECS da própria conta. O risco é aceito: um processo comprometido já tem acesso maior que este. O teste `metrics_agent_sidecar` trava a política. |
| O papel do TxSigner (papel do KMS) ganha a mesma permissão no grupo `/ecs/eureka-real/metrics/txsigner`. | Aumentar o papel mais sensível. | É a mesma instrução restrita, e nada mais. O alarme `security-founder-role-iam-change` dispara no `apply`, e isso é esperado (passo do fundador com MFA). |
| `cloudwatch:PutMetricData` com a condição `cloudwatch:namespace` | **Não foi concedido.** O agente publica por EMF (`PutLogEvents`) e nunca chama `PutMetricData`; uma permissão sem uso seria privilégio a mais. O IAM não consegue limitar o namespace de um evento EMF. | O namespace é fixado pela configuração do agente e conferido pelo teste offline. |
| O deploy passa a poder passar o papel de tarefa `api-worker` (`passable_role_arns`). | O deploy poderia dar esse papel a outra família. | O ganho máximo é gravar métricas num grupo de log. Os papéis exclusivos do fundador continuam fora, e a precondição do SP-01 continua valendo. |
| Nova imagem de terceiro na tarefa do TxSigner | Cadeia de suprimentos | A imagem é a oficial da AWS, de versão com mais de 2 semanas, fixada por digest e copiada para o ECR próprio, imutável e com varredura no envio. O contêiner não é essencial, tem raiz somente leitura, não tem segredo e só fala com o endpoint de Logs. |
| Endpoints da VPC (SP-05) | Mudança de política | **Nenhuma mudança.** O endpoint `logs` já permite `logs:*` para principais e recursos da conta. O grupo de segurança do TxSigner já sai na porta 443 para os endpoints. A leitura das métricas é local (`127.0.0.1`) e não abre porta nova. |
| Memória da tarefa `api-worker` de 2 GB para 3 GB | Custo | O agente precisa de 128 MB, e o Fargate com 1 vCPU só oferece 2 GB ou 3 GB. |

**Rollback:** reverter o commit e aplicar. Os alarmes voltam ao estado anterior, e o contêiner sai das novas revisões. A mudança não toca dados, e o REAL e o saque não dependem dela. Para desligar só o agente sem novo deploy, há dois caminhos:

- `aws ecs update-service` para uma revisão anterior;
- ou retirar a permissão `metrics-agent` do papel. Nesse caso, o agente falha, os alarmes `metrics-absent-*` disparam, e é isso que se espera.

## 8. Validação offline (20/11/2026)

| Verificação | Resultado |
|---|---|
| `terraform fmt -check -recursive` | saída 0 |
| `terraform init -backend=false` e `validate` numa cópia em `%TEMP%` | "Success! The configuration is valid." |
| `terraform test` (provedor simulado) | **13 de 13 verdes**: os 10 de antes, mais `metrics_agent_sidecar`, `every_alarm_reads_a_published_metric` (que agora confere a leitura a cada 120 s e as janelas da seção 1) e `p95_alarms_when_the_code_publishes_them`. Repetido depois da mudança para 120 s. |
| Testes de mutação do `observability_metrics` | uma métrica renomeada, um alarme fora da lista e um período de 180 s num trabalho parado fazem o teste falhar, como esperado |
| Checkov 3.4.6 (`-d infra/terraform`) | 538 aprovados, **0 falhas**, 80 supressões (eram 77), todas justificadas na própria linha |
| `promtool check rules` nos 6 arquivos de regras | todos `SUCCESS` (as regras não mudaram com a leitura a cada 120 s) |
| Ensaio do agente: imagem fixada, `--read-only`, sem rede, WireMock como `/actuator/prometheus` (OpenMetrics) e como CloudWatch Logs | Os 48 pares de métrica e dimensões da lista saíram como EMF em `Eureka/App`, no grupo certo. Os histogramas e a métrica fora da lista não saíram. `usage_data` ficou desligado. |
| `apps/api`: `RealRiskAlarmParityTest` (4), `RealExecutionAlarmParityTest` (5), `RealOpsAlarmParityTest` (5), `RealExitsAlarmParityTest` (5), `CoinGeckoAlarmParityTest` (5) e `ComposeFilesTest` (28) | 52 testes, 0 falhas (`-Djacoco.skip=true`), repetidos depois da mudança para 120 s, com a regra comum `CloudWatchWindows` |
| `apps/txsigner`: `KmsPolicyDocumentsTest` (5) e `ResilientKmsPortTest` (7) | 12 testes, 0 falhas |

## 9. Custo mensal estimado por ambiente (us-east-1)

| Item | Base | US$/mês |
|---|---|---|
| Memória do Fargate (+1 GB na tarefa `api-worker`) | 1 GB × US$ 0,004445/h × 730 h | ≈ 3,25 |
| CPU do agente | sai da cota da tarefa: 64 unidades de 1024 e 64 de 512 | 0 |
| Métricas customizadas | ≈ 105 séries (48 pares × valores dos rótulos: `code`, `state`, `kind`/`decision`, `job`...) × US$ 0,30 | ≈ 31,50 |
| Alarmes novos | 19 métricas de alarme (3 de batimento; 6, 2 e 2 nos alarmes de SLO; 2 × 3 nas anomalias) × US$ 0,10; os p95, quando ligados, somam US$ 0,20 | ≈ 1,90 |
| Painéis | 3 grátis por conta; US$ 3 cada se já houver outros | 0 a 9 |
| Ingestão do EMF | ≈ 1 KB por série por leitura, medido no ensaio; ≈ 120 séries por processo, 1 leitura a cada 120 s | ≈ 2,25 (≈ 4,5 GB × US$ 0,50) |
| Armazenamento dos logs EMF (7 dias), imagem no ECR, consultas do Logs Insights sob demanda | — | < 1 |
| **Acréscimo** | | **≈ 39 a 48** |

O total da conta passa de ≈ 254 para ≈ 293 a 302 (devnet) e de ≈ 273 para ≈ 312 a 321 (mainnet). As três contas somam ≈ 605 a 623, antes ≈ 527; a conta de gerenciamento não tem observabilidade.

O orçamento de cada conta subiu para **US$ 350 por mês** (`monthly_budget_usd`, padrão em `infra/terraform/main.tf`), por decisão do responsável. O aviso de 80% da previsão chega perto de US$ 280.

Formas de baixar o custo:

- **ler a cada 120 s:** aplicado por decisão do responsável (2026-11-20). A ingestão caiu a um quarto do custo da leitura a cada 30 s, e as janelas seguem a regra da seção 1. As métricas customizadas não ficam mais baratas, porque elas são cobradas por série, não por leitura;
- tirar dos painéis as séries com mais rótulos (`txsigner_decisions_total{kind,decision}`, `realwallet_withdrawals_total{state}`): economia de ≈ US$ 6.

Os valores reais aparecem no Cost Explorer depois de uma semana na devnet e substituem esta estimativa.

## 10. Verificação depois do primeiro apply da devnet

Rodar com o papel de administração do fundador, com MFA, na conta da devnet. Trocar `<conta>` e `<região>`.

```bash
# 0. Copiar a imagem do agente pelo digest, antes do apply das tarefas (o digest não muda na cópia)
crane copy public.ecr.aws/cloudwatch-agent/cloudwatch-agent@sha256:cc169bdfffee5f795cd0a534660d6721d987b1cb9e0a39df8174147a36ae0086 \
  <conta>.dkr.ecr.<região>.amazonaws.com/eureka-real/cloudwatch-agent:1.300074.1b2005
aws ecr describe-images --repository-name eureka-real/cloudwatch-agent \
  --image-ids imageDigest=sha256:cc169bdfffee5f795cd0a534660d6721d987b1cb9e0a39df8174147a36ae0086 \
  --query 'imageDetails[0].imageScanStatus'

# 1. O agente subiu sem erro de permissão nem de configuração (logs do contêiner metrics-agent)
aws logs filter-log-events --log-group-name /ecs/eureka-real/api-worker \
  --log-stream-name-prefix api-worker/metrics-agent --filter-pattern '"E!"' --max-items 20
aws logs filter-log-events --log-group-name /ecs/eureka-real/txsigner \
  --log-stream-name-prefix txsigner/metrics-agent --filter-pattern '"AccessDenied"' --max-items 20

# 2. Os eventos EMF chegam nos dois grupos
aws logs describe-log-streams --log-group-name /ecs/eureka-real/metrics/api-worker --query 'logStreams[].logStreamName'
aws logs describe-log-streams --log-group-name /ecs/eureka-real/metrics/txsigner --query 'logStreams[].logStreamName'

# 3. As métricas existem com as dimensões esperadas (deve listar job=api, worker e txsigner)
aws cloudwatch list-metrics --namespace Eureka/App --metric-name process_uptime_seconds \
  --query 'Metrics[].Dimensions[0].Value'
aws cloudwatch list-metrics --namespace Eureka/App --query 'Metrics[].MetricName' --output text | tr '\t' '\n' | sort -u

# 4. Nenhum alarme preso em INSUFFICIENT_DATA depois de 30 minutos no ar (exceto anomalias e p95)
aws cloudwatch describe-alarms --alarm-name-prefix eureka-real- --state-value INSUFFICIENT_DATA \
  --query 'MetricAlarms[].AlarmName'
aws cloudwatch describe-alarms --alarm-name-prefix eureka-real- --state-value ALARM \
  --query 'MetricAlarms[].[AlarmName,StateReason]'

# 5. A rota de aviso funciona: forçar um alarme, conferir o e-mail e devolver o estado
aws cloudwatch set-alarm-state --alarm-name eureka-real-metrics-absent-txsigner \
  --state-value ALARM --state-reason "teste do Observability Setup"

# 6. Simulação de falha: parar a tarefa do TxSigner (o ECS sobe outra). O alarme
#    metrics-absent-txsigner vai para ALARM em 4 a 8 minutos (dois períodos de 240 s sem leitura) e volta a OK.
aws ecs stop-task --cluster eureka-real --reason "teste metrics-absent" \
  --task "$(aws ecs list-tasks --cluster eureka-real --service-name txsigner --query 'taskArns[0]' --output text)"
aws cloudwatch describe-alarm-history --alarm-name eureka-real-metrics-absent-txsigner \
  --history-item-type StateUpdate --max-items 5
```

O passo 6 é um passo do fundador, feito com MFA. Se o CloudTrail registrar a tarefa nova como `RunTask` ou `StartTask` da família do TxSigner, o alarme `security-founder-family-run` também dispara, e isso é esperado. A tarefa nova tem de subir com a mesma revisão.

## Sources

- `observability-setup-questions.md` (Q1 a Q7, resumo confirmado).
- `operation/environment-provisioning/security-posture-review.md` (SP-05, SP-11 e SP-12) e `validation-report.md` (seções 11 e 12).
- `construction/build-and-test/build-and-test-summary.md` (linhas U3-NFR5.11 e U4-NFR5.13).
- `construction/u1-bot-wallet/` a `u7-exits-and-fiscal/`: `infrastructure-design/monitoring-design.md`, `nfr-requirements/observability-requirements.md` e `performance-requirements.md`.
- `infra/observability/*.yml` e `infra/terraform/modules/{observability,services}`.
- `operation/deployment-pipeline/cd-config.md` (conferências do `deploy.yml`).
- AWS: conversão de métricas do Prometheus pelo CloudWatch Agent <https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights-Prometheus-metrics-conversion.html> e configuração do agente para Prometheus no ECS <https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights-Prometheus-Setup-configure-ECS.html>.
- Registro público `public.ecr.aws/cloudwatch-agent/cloudwatch-agent`: tags e digest lidos em 20/11/2026.
