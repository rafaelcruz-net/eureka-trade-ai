# Matriz de escalonamento — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Incident Response, Q2 (opção A), com o resumo confirmado.

> **Nada está implantado.** Não existe conta AWS. Os canais abaixo passam a valer depois do primeiro `apply` da devnet e da confirmação manual da assinatura de e-mail do tópico. **Nenhum telefone ou e-mail foi inventado:** tudo que não está decidido aparece como "a definir", e o valor fica com o responsável do projeto.

## 1. Plantão

| Papel | Quem | Disponibilidade | Pode decidir |
|---|---|---|---|
| Plantonista único, comandante do incidente | **fundador** (responsável do projeto) | **24x7** no piloto (Q2) | tudo: kill switch, rollback, compensação, confirmação de restauração, liberações, religar o REAL (`team.md`: revisor único dos gates do REAL) |
| Segundo contato | **a definir** | a definir | a definir (seção 5) |

- Não há serviço de plantão pago no piloto (Q2).
- Não há rodízio: o guia de plantão recomenda pelo menos duas pessoas treinadas, e isso só é possível com o segundo contato. Até lá, o sistema se contém sozinho quando o fundador não responde (`incident-plan.md`, seção 2).
- O fundador precisa ter sempre à mão: a carteira de administração (entrada administrativa), o perfil AWS com MFA de cada conta (`eureka-devnet-admin`, `eureka-mainnet-admin`), o acesso ao GitHub e o telefone que recebe o SMS.

## 2. Rotas por severidade

As severidades do alarme vêm do `alarms.md` (seção 2). O canal decide **como** o fundador é avisado; a prioridade P1, P2 ou P3 (`incident-plan.md`, seção 1) decide **como** ele responde.

| Severidade do alarme (Q2) | Alarmes | Canal | Resposta esperada |
|---|---|---|---|
| **Alta (dinheiro e segurança)**: as severidades "crítica" e "alta" do `alarms.md` | `metrics-absent-*`, `real-execution-*` (exceto `-slow`), `real-ops-*` (exceto `-release-blocked`), `real-risk-errors`, `real-risk-job-stalled-*`, `real-exits-*`, `tx-signer-kms-errors`, `kms-*`, `security-*`, `market-source-failed`, `real-price-stale`, `market-call-counter-unavailable`, `slo-*-1h`; regras do EventBridge `guardduty-high` e `access-analyzer-active` | **e-mail e SMS** | P1 reconhecido em até 15 min; P2 em até 1 h |
| **Média** e **aviso** | `slo-*-6h`, `market-monthly-budget`, `market-invalid-values`, `market-provider-unstable`, `anomaly-real-*`, `real-risk-reservation-expired`, `real-ops-release-blocked`, `real-risk-slow`, `real-execution-slow` | **só e-mail** | até 2 dias úteis (P3) |
| Custo da conta | AWS Budgets `eureka-real-<env>-monthly` (80% da previsão e 100% do real) | e-mail direto do AWS Budgets (não passa pelo tópico) | até 2 dias úteis |

Os alarmes de batimento (`metrics-absent-*`) também mandam o aviso de volta ao normal (`ok_actions`), só por e-mail. Os alarmes de trabalho de expurgo parado (`*-job-stalled-purge`) são "crítica" no `alarms.md` e por isso também vão por SMS, embora sejam P3; se isso acordar o fundador sem motivo, eles entram na lista de "só e-mail" da seção 3.2.

## 3. SNS: o que existe no Terraform e o que falta

### 3.1 O que existe

| Recurso | Arquivo | O que faz |
|---|---|---|
| `aws_sns_topic.alarms` (`${name}-alarms`) | `infra/terraform/main.tf` | um tópico só para todos os alarmes, com chave KMS própria (`aws_kms_key.alarms`) |
| `aws_sns_topic_policy.alarms` | `infra/terraform/main.tf` | só a conta, os alarmes do CloudWatch e as regras do EventBridge da conta publicam |
| `aws_sns_topic_subscription.alarm_email` | `infra/terraform/main.tf` | **só e-mail** (`protocol = "email"`, `endpoint = var.alarm_email`); a assinatura é confirmada à mão pelo link do e-mail |
| `aws_budgets_budget.monthly` | `infra/terraform/main.tf` | e-mail direto para `var.alarm_email` |
| `aws_cloudwatch_event_rule.guardduty_high` e `.access_analyzer_active` | `infra/terraform/modules/observability/detection.tf` | achados para o tópico |

**Não existe assinatura SMS no Terraform** (lacuna IR-G8 do `runbooks.md`). Hoje todos os alarmes, de qualquer severidade, chegam só por e-mail, no mesmo endereço.

### 3.2 Texto pronto para o SMS (opção A: filtro no mesmo tópico)

Para o responsável colar em `infra/terraform/main.tf`, logo depois de `aws_sns_topic_subscription.alarm_email`. O número **não** é preenchido aqui: ele fica em `terraform.tfvars` do ambiente (que não é versionado), ou em `TF_VAR_alarm_sms_number`. Este estágio não alterou o Terraform.

```hcl
variable "alarm_sms_number" {
  description = "On-call phone in E.164 (+55...) subscribed by SMS to the alarm topic, for the critical and high alarms only; null creates no subscription"
  type        = string
  default     = null
  sensitive   = true
}

locals {
  # Medium and warning alarms (observability-setup alarms.md, section 2) go by e-mail only. Everything else goes by
  # SMS too, so a new alarm defaults to the louder channel.
  alarm_email_only = [for suffix in [
    "slo-api-availability-burn-6h", "slo-txsigner-availability-burn-6h", "slo-freshness-burn-6h",
    "market-monthly-budget", "market-invalid-values", "market-provider-unstable",
    "anomaly-real-orders", "anomaly-real-refusals",
    "real-risk-reservation-expired", "real-ops-release-blocked",
    "real-risk-slow", "real-execution-slow",
  ] : "${var.name}-${suffix}"]
}

resource "aws_sns_topic_subscription" "alarm_sms" {
  count               = var.alarm_topic_arn == null && var.alarm_sms_number != null ? 1 : 0
  topic_arn           = aws_sns_topic.alarms[0].arn
  protocol            = "sms"
  endpoint            = var.alarm_sms_number
  filter_policy_scope = "MessageBody"
  filter_policy = jsonencode({
    "$or" = [
      # CloudWatch alarms: only the transition to ALARM, and only the critical and high ones
      { AlarmName = [{ "anything-but" = local.alarm_email_only }], NewStateValue = ["ALARM"] },
      # EventBridge findings (GuardDuty severity >= 7, active Access Analyzer findings)
      { source = ["aws.guardduty", "aws.access-analyzer"] },
    ]
  })
}
```

**Por que assim:** um tópico só, como hoje, e a assinatura SMS filtra pelo corpo da mensagem. Um alarme novo vai por SMS até alguém o pôr na lista de "só e-mail": o erro cai do lado barulhento, não do silencioso.

**Não verificado.** O filtro pelo corpo (`MessageBody`) depende do formato da mensagem que o CloudWatch e o EventBridge publicam no tópico. Isso só se confere numa conta. Na devnet, depois do `apply`:

```bash
# Deve chegar SMS e e-mail
aws cloudwatch set-alarm-state --alarm-name eureka-real-real-ops-divergence-open \
  --state-value ALARM --state-reason "teste da rota SMS (incident-response)"
# Deve chegar só e-mail
aws cloudwatch set-alarm-state --alarm-name eureka-real-market-monthly-budget \
  --state-value ALARM --state-reason "teste da rota e-mail (incident-response)"
# Achado de exemplo do GuardDuty: deve chegar SMS e e-mail (H21 do validation-report.md)
aws guardduty create-sample-findings --detector-id "$(aws guardduty list-detectors --query 'DetectorIds[0]' --output text)" \
  --finding-types 'Backdoor:EC2/C&CActivity.B!DNS'
# Conferir as entregas
aws sns list-subscriptions-by-topic --topic-arn "$(aws sns list-topics --query "Topics[?ends_with(TopicArn, ':eureka-real-alarms')].TopicArn | [0]" --output text)"
```

Os alarmes forçados voltam ao estado real na avaliação seguinte.

**Se o filtro não funcionar (opção B, alternativa):** um segundo tópico `${name}-alarms-urgent`, com a mesma chave e a mesma política, com a assinatura SMS sem filtro, e o ARN dele acrescentado a `alarm_actions` dos alarmes críticos e altos e ao alvo das duas regras do EventBridge. É mais trabalho (uma variável nova no módulo `observability` e a mudança de cada alarme), mas não depende do formato da mensagem. O teste `observability_metrics.tftest.hcl` precisaria conferir que todo alarme crítico ou alto tem os dois tópicos.

**Passos fora do Terraform, pelo fundador, em cada conta (uma vez):**
1. Uma conta nova fica no *sandbox* de SMS: só manda SMS para números verificados. Verificar o número do plantão (`aws sns create-sms-sandbox-phone-number --phone-number <+55...>` e `aws sns verify-sms-sandbox-phone-number --phone-number <+55...> --one-time-password <código>`), ou pedir a saída do sandbox. Para um único plantonista, o número verificado no sandbox basta.
2. Tipo de mensagem `Transactional` e um limite de gasto mensal com folga (`aws sns set-sms-attributes --attributes DefaultSMSType=Transactional,MonthlySpendLimit=<US$ a definir>`). Com o limite padrão da conta, os SMS param sem aviso quando o limite é atingido.
3. Conferir se o envio de SMS para números do Brasil, na região escolhida, exige registro de remetente. **A definir** na primeira configuração.
4. Rodar os testes acima.

O número de telefone é dado pessoal: ele não entra no repositório, no registro de incidentes nem nos logs.

## 4. Contatos externos

Nenhum contato foi inventado. "a definir" é decisão do responsável; a coluna "Antes de" diz quando ela precisa existir.

| Contato | Para quê | Canal | Situação | Antes de |
|---|---|---|---|---|
| **Suporte da AWS** | KMS, ECS, RDS, incidente de segurança na conta | caso aberto no console (Support Center) da conta afetada | **plano a definir.** O plano Basic (gratuito) não abre casos técnicos; KMS, RDS ou segurança exigem um plano pago | mainnet |
| AWS Health Dashboard | saber se o problema é da AWS na região | console da conta (AWS Health) | disponível em toda conta | — |
| **Provedor de RPC 1** | rede Solana fora, latência, cota | a definir (suporte do plano contratado) | **provedor a definir** (`environment-inventory.md`: plano gratuito na devnet; mainnet sem plano escolhido) | mainnet |
| **Provedor de RPC 2** | idem; é o segundo provedor exigido para a mainnet | a definir | **provedor a definir** | mainnet |
| Estado da rede Solana | saber se a queda é da rede e não do provedor | página pública de status da Solana (`status.solana.com`) | disponível | — |
| **Jupiter** | rotas recusadas, API fora, mudança de programa na rota, Trigger API | a definir (canal de suporte ou de desenvolvedores; a página de status a confirmar) | **a definir** | mainnet |
| **CoinGecko** | chave, cota, formato da API, fonte parada | a definir (suporte do plano contratado) | **plano a definir** (`validation-report.md`, seção 7) | mainnet |
| **Assessor jurídico** | incidente com fundos ou dados de convidados; avaliar comunicação à ANPD e aos titulares (LGPD); parecer de custódia | a definir | **a definir** | convidados |
| GitHub | Actions fora (implantação pelo caminho C) | página pública de status do GitHub (`www.githubstatus.com`) | disponível | — |
| Registrador de domínio e DNS | domínio ou certificado fora | a definir | a definir | mainnet |

**Ao abrir um chamado com qualquer fornecedor:** nunca enviar chave, token, segredo, endereço completo de carteira, dado pessoal ou captura de tela com esses dados. Mandar só horário UTC, código de erro, rota e o `correlationId` quando ele ajudar.

## 5. Segundo contato (condição antes dos convidados)

O segundo contato é **"a definir"** e é condição para abrir o REAL aos convidados (Q2), ao lado das três condições do `team.md` (quarentena de 24 h, 7 dias da carteira do fundador na mainnet sem divergência e parecer de custódia).

| Item | Situação |
|---|---|
| Quem | a definir |
| Canal (telefone, mensagem) | a definir |
| O que pode fazer | a definir pelo fundador. Recomendação (opção recomendada, a confirmar): só **ligar** o kill switch REAL, nunca desligar, e acordar o fundador. Isso exige uma carteira de administração própria e o papel ADMIN na entrada administrativa |
| Quando é acionado | P1 sem reconhecimento do fundador em 30 min |
| Acesso AWS | nenhum, salvo decisão do fundador (o papel de administração com MFA é dele) |
| Treinamento | ler `runbooks.md` (regras gerais, C1, RB-02, RB-08 e RB-10) e participar de um ensaio na devnet |

Até existir o segundo contato, um P1 fora do alcance do fundador é contido pelo próprio sistema (falha fechada, pausas automáticas, saque disponível) e tratado quando ele voltar.

## 6. Escalonamento por tempo

| Prioridade | 0 min | 15 min | 30 min | 4 h | 24 h |
|---|---|---|---|---|---|
| **P1** | SMS e e-mail ao fundador | fundador reconhece e liga o kill switch (C1) se há dinheiro em risco | sem reconhecimento: segundo contato (quando existir) | fornecedor envolvido acionado; aviso M1 a convidados afetados (até 1 h) | sem solução: avaliar com o assessor jurídico a comunicação formal |
| **P2** | SMS e e-mail ao fundador | — | — | fundador mitigou ou registrou por que não; fornecedor acionado se for dele | — |
| **P3** | e-mail | — | — | — | tratado em até 2 dias úteis |

Todo P1 e P2 termina com a revisão pós-incidente (`incident-plan.md`, seção 5).

## 7. Itens em aberto para o responsável

| ID | O quê | Antes de |
|---|---|---|
| ESC-1 | Valor de `alarm_email` (e confirmar a assinatura pelo link) em cada conta | devnet |
| ESC-2 | Aplicar o texto do SMS (seção 3.2) e fixar `alarm_sms_number` fora do repositório; sandbox, tipo e limite de gasto do SMS; testar as rotas | mainnet |
| ESC-3 | Plano de suporte da AWS | mainnet |
| ESC-4 | Os dois provedores de RPC da mainnet, com o canal de suporte de cada um | mainnet |
| ESC-5 | Canal da Jupiter e da CoinGecko | mainnet |
| ESC-6 | Segundo contato: quem, canal, permissões e treino | convidados |
| ESC-7 | Assessor jurídico e canal | convidados |
| ESC-8 | Canal de comunicação com os convidados durante incidentes (`incident-plan.md`, seção 4) | convidados |

## Sources

- `incident-response-questions.md` (Q2 e Q4).
- `operation/observability-setup/alarms.md` (seção 2: severidades, tópico, plantão, OBS-O5; seções 3 e 10) e `dashboards.md`.
- `operation/deployment-pipeline/rollback-runbook.md` (seção 9: escalonamento e contatos "a definir") e `cd-config.md` (seções 6 e 8).
- `operation/environment-provisioning/validation-report.md` (H16, H20, H21, seções 7 e 9) e `environment-inventory.md` (RPC e CoinGecko sem plano escolhido).
- `infra/terraform/main.tf` (`aws_sns_topic.alarms`, `aws_sns_topic_policy.alarms`, `aws_sns_topic_subscription.alarm_email`, `aws_budgets_budget.monthly`) e `infra/terraform/modules/observability/detection.tf` (regras do EventBridge).
- `construction/u1-bot-wallet/nfr-design/security-design.md` (alarmes de ações administrativas do KMS) e `reliability-design.md`; `construction/u1-bot-wallet/infrastructure-design/infrastructure-specification.md`.
- `team.md` (Deployment: fundador revisor único; condições para os convidados) e `project.md` (Forbidden: segredos e dados fora de logs).
- `.claude/knowledge/aidlc-operations-agent/incident-response-guide.md` (escalonamento e rodízio).
