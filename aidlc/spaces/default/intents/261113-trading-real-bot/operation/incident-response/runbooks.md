# Biblioteca de runbooks — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Incident Response. Decisões: `incident-response-questions.md`, Q1 a Q5, todas na opção A, com o resumo confirmado.

> **Nada está implantado.** Não existe conta AWS. Os nomes de alarmes, consultas, grupos de log, serviços e tabelas vêm do Terraform validado offline (`infra/terraform`) e do código. Cada comando AWS só pode ser conferido depois do primeiro `apply` da devnet. O primeiro uso real de cada runbook acontece no ensaio da devnet (`incident-plan.md`, seção 7).

**Formato (Q3).** Os runbooks ficam em Markdown, com comandos exatos. Não há documento de automação do SSM, porque não há conta para testá-lo. A versão SSM é candidata depois da devnet (`incident-plan.md`, seção 9).

**Quem executa.** O fundador é o único plantonista (`escalation-matrix.md`). Nenhum passo pede chave privada, frase de recuperação, segredo ou transação assinada à mão.

## Regras que valem em todos os runbooks

1. **Kill switch primeiro quando há dinheiro em risco.** O primeiro passo humano é ligar o kill switch REAL (C1). Nenhum automatismo liga o kill switch nem move dinheiro (Q3).
2. **O saque nunca é desligado.** O saque para a carteira principal registrada continua disponível com o kill switch, com o REAL desligado, sem a liberação, sem o novo aceite e durante a restauração (`project.md`, Mandated). Nenhum passo desta biblioteca bloqueia o saque.
3. **Nunca editar o ledger.** Uma correção é sempre um lançamento compensatório, pela rota de compensação (`POST /api/v1/admin/real/divergences/{divergenceId}/compensation`). Nunca rodar `UPDATE` nem `DELETE` em lançamento, ordem, saque, condicional ou trilha de auditoria.
4. **Nunca assinar à mão fora do TxSigner.** Nenhuma transação é montada, assinada ou reenviada fora do caminho normal (API → TxSigner → rede). A política do TxSigner (ADR-004) nunca é desligada, e o teto ou a derrapagem nunca são aumentados para "fazer passar".
5. **Nunca reutilizar uma `Idempotency-Key` para uma ordem ou ação nova.** Cada ação administrativa leva um UUID novo. A mesma chave só se repete para repetir **a mesma** ação com o mesmo corpo.
6. **Nunca reverter migração nem restaurar o banco para desfazer uma implantação** (`rollback-runbook.md`, passo 5).
7. **Nada de segredo, endereço completo ou dado pessoal** no registro do incidente, no motivo das ações administrativas, nas mensagens aos convidados ou em tickets de fornecedores.
8. **Anotar o horário de cada passo em UTC.** É a linha do tempo da revisão pós-incidente (`incident-plan.md`, seção 5).

## 0. Preparação do terminal

Rodar no Git Bash, na raiz do repositório, com o perfil de administração **com MFA** da conta afetada. Nenhum comando abaixo lê o valor de um segredo.

```bash
export AWS_PROFILE=eureka-mainnet-admin      # eureka-devnet-admin na devnet; o perfil pede o código MFA
export NAME=eureka-real CLUSTER=eureka-real   # valor padrão de var.name

# Alarmes em ALARM agora (todos os runbooks começam por aqui)
alarms() {
  aws cloudwatch describe-alarms --alarm-name-prefix "$NAME-" --state-value ALARM \
    --query 'MetricAlarms[].[AlarmName,StateUpdatedTimestamp,StateReason]' --output table
}

# Histórico de um alarme (sem o prefixo): alarm_history real-ops-divergence-open
alarm_history() {
  aws cloudwatch describe-alarm-history --alarm-name "$NAME-$1" --history-item-type StateUpdate \
    --max-items 10 --query 'AlarmHistoryItems[].[Timestamp,HistorySummary]' --output table
}

# Uma métrica de Eureka/App na última hora, em períodos de 240 s (duas leituras do agente)
#   metric real_orders_ambiguous_oldest_seconds Maximum
#   metric realwallet_withdrawal_oldest_pending_seconds Maximum Name=state,Value=AMBIGUOUS
metric() {
  local m=$1 s=$2; shift 2
  aws cloudwatch get-metric-statistics --namespace Eureka/App --metric-name "$m" ${1:+--dimensions "$@"} \
    --start-time "$(date -u -d '-1 hour' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" --period 240 \
    --statistics "$s" --query "sort_by(Datapoints,&Timestamp)[].[Timestamp,$s]" --output text
}

# Uma consulta salva do Logs Insights (log-queries.md), pelas últimas N horas:
#   logsq falhas-dos-trabalhos 1
#   logsq seguir-correlation-id 24 <correlationId>
logsq() {
  local def qid st text
  def=$(aws logs describe-query-definitions --query-definition-name-prefix "eureka-real/$1" \
    --query 'queryDefinitions[0]' --output json)
  text=$(jq -r .queryString <<< "$def")
  [ -n "${3:-}" ] && text=${text//COLE-AQUI-O-CORRELATION-ID/$3}
  # shellcheck disable=SC2046  # um argumento por grupo de log, de propósito
  qid=$(aws logs start-query --log-group-names $(jq -r '.logGroupNames[]' <<< "$def") \
    --start-time "$(date -u -d "-${2:-1} hour" +%s)" --end-time "$(date -u +%s)" \
    --query-string "$text" --query queryId --output text)
  until st=$(aws logs get-query-results --query-id "$qid" --query status --output text); \
        [ "$st" = Complete ] || [ "$st" = Failed ]; do sleep 2; done
  aws logs get-query-results --query-id "$qid" --query results --output json
}

# Estado dos serviços e eventos recentes
services() {
  aws ecs describe-services --cluster "$CLUSTER" --services api-worker txsigner web admin \
    --query 'services[].[serviceName,runningCount,desiredCount,deployments[0].rolloutState,taskDefinition]' --output table
}
service_events() {
  aws ecs describe-services --cluster "$CLUSTER" --services "$1" \
    --query 'services[0].events[:10].[createdAt,message]' --output table
}
```

As consultas salvas (`log-queries.md`) são: `eureka-real/seguir-correlation-id`, `eureka-real/recusas-txsigner-por-motivo`, `eureka-real/erros-5xx-por-componente`, `eureka-real/ordens-real-ambiguas` e `eureka-real/falhas-dos-trabalhos`.

**Leitura do banco na nuvem.** Não existe caminho de leitura direta do banco para o fundador na nuvem: o papel de implantação nega `ecs:ExecuteCommand` e `ssm:StartSession`, e nenhuma família avulsa roda SQL de leitura (lacuna IR-G3). Por isso os runbooks diagnosticam pela administração (`/api/v1/admin/real/*`), pelas métricas e pelos logs. As consultas SQL dos runbooks em `docs/runbooks/` valem no ambiente local e na CI.

## Procedimentos comuns

### C1. Ligar o kill switch REAL

1. Entrar na administração com a carteira do fundador → seção "REAL" → "Kill switch REAL" (tela própria, separada do kill switch do PAPER).
2. Ligar, com um motivo sem dado pessoal (por exemplo, `incidente 2026-11-20 divergencia`). Por baixo: `PUT /api/v1/admin/real/kill-switch`, com `Idempotency-Key` nova e `{ "reason": "..." }`.
3. Conferir na tela que o estado é "ligado".

**Efeito imediato** (`docs/runbooks/real-operations.md`, seção 3):
- nenhuma ordem REAL nova é aceita;
- as ordens não enviadas são canceladas (`KILL_SWITCH`) logo depois e a cada minuto;
- as condicionais não terminais são canceladas (U7);
- as ordens já enviadas seguem até o estado final;
- **o saque continua**.

Se a administração ou a API não responde: C2.

Desligar só com a decisão do fundador e as condições do C5: `DELETE /api/v1/admin/real/kill-switch`.

### C2. Desligar o REAL pelas flags (a administração não responde)

Caminho B do `rollback-runbook.md` (seção 4): `MEMETRADE_REAL_ENABLED=false` em `api` e `worker` e `TXSIGNER_REAL_ENABLED=false` no `txsigner`, por `terraform apply` com MFA, e depois o `deploy.yml` em `mode=rollback` (ou `mode=deploy` com o SHA em serviço) e o passo do fundador (`cd-config.md` 4.6) para o TxSigner. Leva de 20 a 30 minutos. Com o flag desligado, o TxSigner continua assinando saques.

### C3. Silenciar alarmes durante uma intervenção conhecida

Só durante um rollback ou uma restauração já registrados no incidente:

```bash
aws cloudwatch disable-alarm-actions --alarm-names "$NAME-real-risk-job-stalled-daily-loss" "$NAME-real-risk-job-stalled-day"
# ... e ao terminar, sempre:
aws cloudwatch enable-alarm-actions --alarm-names "$NAME-real-risk-job-stalled-daily-loss" "$NAME-real-risk-job-stalled-day"
```

Nunca silenciar `real-ops-divergence-open`, `real-execution-policy-refused`, os `-security-*` nem os alarmes do KMS. Anotar no incidente o horário de silenciar e de religar.

### C4. Registrar o incidente

Abrir o registro em `docs/incidents/AAAA-MM-DD-<assunto>.md` (a pasta é criada no primeiro incidente) com o modelo do `incident-plan.md`, seção 5. O registro guarda: severidade, linha do tempo UTC, alarmes, `correlationId`, ações e motivos. Nunca guarda endereço completo, segredo, token ou dado pessoal.

### C5. Religar o REAL (desligar o kill switch)

Só o fundador decide, e só quando tudo vale:
- nenhum alarme `eureka-real-*` em ALARM (exceto os de aviso já entendidos);
- "Divergências REAL" vazia e a última reconciliação de cada carteira `CLEAN`;
- nenhuma pausa `RESTORE` ou `RESTORE_CHECK_PENDING`;
- a causa entendida, ou contida.

Desligar o kill switch na administração, com motivo. Nenhum bot volta sozinho: cada pessoa retoma o dela. Se o REAL foi desligado pelas flags (C2), religar é uma implantação auditada (`cd-config.md`, seção 7).

---

## RB-01 — Ordem REAL ambígua

| Campo | Conteúdo |
|---|---|
| Gatilho | `real-execution-ambiguous-old` (ordem AMBIGUOUS há mais de 300 s); `real-execution-sent-old` (SENT há mais de 75 s); sintoma: pessoa recebe `409 ORDER_AMBIGUOUS` ao trocar de modo |
| Severidade | **P2.** Vira **P1** se a ambígua passar de 1 hora com os dois provedores de RPC saudáveis (defeito do trabalho), ou se aparecer junto de `real-ops-divergence-open` |
| Runbook existente | `docs/runbooks/real-execution.md`, seções 1 e 5 |

**Primeiros 5 minutos.**
1. `alarms` e conferir se `real-execution-job-stalled-ambiguous` ou `-confirmation` também disparou (trabalho parado → RB-14).
2. Conferir se há falha de RPC: `metric real_upstream_failures_total Sum Name=provider,Value=RPC`. Com os dois provedores fora, a ordem continua ambígua, e isso é o correto (RB-05).
3. Não fazer nada na ordem. O dinheiro dela continua reservado; nada se perde enquanto ela espera.

**Diagnóstico.**
```bash
metric real_orders_ambiguous_open Maximum
metric real_orders_ambiguous_oldest_seconds Maximum
metric real_orders_sent_oldest_seconds Maximum
logsq ordens-real-ambiguas 6
logsq seguir-correlation-id 24 <correlationId da linha acima>
```
Na administração, "Usuários" → a pessoa → as 20 ordens mais recentes mostram o estado.

**Mitigação.** O trabalho `ambiguous` resolve sozinho (a cada 15 s) até a altura de bloco em que o blockhash vence: `CONFIRMED`, `FAILED` ou `FAILED (BLOCKHASH_EXPIRED)`. Se várias ordens de pessoas diferentes ficam ambíguas ao mesmo tempo com o RPC saudável, ligar o kill switch (C1) para não acumular ordens novas.

**Recuperação.** Esperar o estado final de cada ordem. Uma ordem confirmada entra no ledger pelo efeito lido da rede; uma ordem que falhou libera a reserva.

**Verificação.** `real_orders_ambiguous_open` em 0; o alarme volta a OK; a reconciliação seguinte da carteira vem `CLEAN` (RB-02 se não vier).

**O que NÃO fazer.** Nunca reenviar a transação, nunca criar ordem nova para "compensar", nunca reutilizar a `Idempotency-Key` da ordem ambígua numa ordem nova, nunca mudar o estado da ordem no banco.

**Escalonamento.** Fundador. Passou de 1 hora com RPC saudável: P1, revisão pós-incidente obrigatória. RPC fora: provedores de RPC (`escalation-matrix.md`, seção 4).

---

## RB-02 — Divergência entre o ledger e a rede

| Campo | Conteúdo |
|---|---|
| Gatilho | `real-ops-divergence-open` (crítico); `real-ops-reconciliation-deferred` (carteira sem medida há mais de 2 h); `real-ops-job-stalled-reconciliation` (RB-14) |
| Severidade | **P1** até a causa ser conhecida. Depois de confirmada uma causa legítima (airdrop, depósito de token sem ordem, taxa não lançada), cai para **P3** |
| Runbook existente | `docs/runbooks/real-operations.md`, seção 2 |

**Primeiros 5 minutos.**
1. O código já pausou a carteira afetada (`DIVERGENCE`) e limitou o saque dela ao que a rede confirma. As outras carteiras seguem.
2. Se mais de uma carteira diverge ao mesmo tempo, ou se a diferença é de saída de fundos que ninguém pediu: **ligar o kill switch (C1)** e tratar como suspeita de comprometimento (RB-10).
3. C4: abrir o registro.

**Diagnóstico.**
- Administração → "Divergências REAL" (`GET /api/v1/admin/real/divergences`): a diferença medida (rede menos ledger) em lamports e em unidades base de cada mint.
- Administração → a pessoa (`GET /api/v1/admin/real/users/{userId}`): saldo e últimas 20 ordens.
- Endereço público da carteira do bot, mostrado por extenso na visão do administrador, num explorador público da rede.
- `metric real_divergences_open Maximum` e `metric real_reconciliation_deferred_over_2h Maximum`.
- `logsq falhas-dos-trabalhos 6` (falhas do `RealReconciliationJob`).

Uma reconciliação `DEFERRED` (ordem SENT ou AMBIGUOUS, depósito não lido) ou `UNAVAILABLE` (RPC fora) não é divergência: seguir RB-01 ou RB-05.

**Mitigação.**
- Diferença legítima: compensar pela administração ("Divergências" → a compensação vem preenchida com a medida; confirmar com o motivo). Cada item tem de ser exatamente a diferença medida, senão `422 COMPENSATION_MISMATCH`.
- Diferença não legítima ou sem explicação: **não compensar**. Kill switch (C1) e RB-10.

**Recuperação.** O worker roda a reconciliação `AFTER_COMPENSATION`. `CLEAN` → a divergência fica `RESOLVED`, e a pausa `DIVERGENCE` sai.

**Verificação.** "Divergências REAL" vazia; `real_divergences_open` em 0; o alarme volta a OK; os portões da liberação deixam de mostrar `DIVERGENCE_OPEN`.

**O que NÃO fazer.** Nunca editar o ledger nem a divergência no banco. Nunca compensar um valor diferente da medida. Nunca compensar antes de saber a causa. Nunca religar o REAL com divergência aberta.

**Escalonamento.** Fundador. Divergência sem causa conhecida em 24 h: assessor jurídico avisado se houver convidado afetado (`escalation-matrix.md`). A divergência da carteira do fundador zera a contagem dos 7 dias exigidos antes dos convidados (`cd-config.md`, seção 6, etapa 4).

---

## RB-03 — TxSigner indisponível ou recusando

| Campo | Conteúdo |
|---|---|
| Gatilho | Indisponível: `metrics-absent-txsigner`, `slo-txsigner-availability-burn-1h`, `slo-txsigner-availability-burn-6h`, `real-execution-signer-unavailable`, `real-ops-txsigner-command-pending`. Recusando: `real-execution-policy-refused`, `real-execution-signing-refused`, `real-exits-trigger-policy-refused` |
| Severidade | **P1.** Com o TxSigner fora, nenhum saque é assinado. Uma recusa da política pode ser ataque. `slo-txsigner-availability-burn-6h` sozinho (degradação lenta, processo vivo agora) é **P2** |
| Runbook existente | `docs/runbooks/real-execution.md`, seção 3 (recusa da política); `docs/runbooks/txsigner-recovery.md` (banco do TxSigner); `docs/runbooks/real-exits.md`, seção 4 (recusa de condicional) |

**Primeiros 5 minutos.**
1. **Recusa da política** (`real-execution-policy-refused` ou `-trigger-policy-refused`): **ligar o kill switch (C1)** antes de qualquer análise. Nada foi assinado; a ordem ficou `FAILED (SIGNING_REFUSED)` e a reserva foi liberada.
2. **TxSigner fora**: conferir se é o processo ou só o caminho das métricas (RB-12). `services` e `service_events txsigner`.
3. C4.

**Diagnóstico.**
```bash
services
service_events txsigner
aws ecs describe-services --cluster "$CLUSTER" --services txsigner \
  --query 'services[0].deployments[].[status,taskDefinition,rolloutState,runningCount,desiredCount]' --output table
aws logs tail /ecs/$NAME/txsigner --since 30m --format short | grep -E '"level":"(ERROR|WARN)"' | tail -50
logsq recusas-txsigner-por-motivo 6
metric real_upstream_failures_total Sum Name=provider,Value=TXSIGNER
metric txsigner_swap_decisions_total Sum Name=outcome,Value=REFUSED
metric real_pending_txsigner_oldest_seconds Maximum
metric txsigner_latency_seconds_max Maximum
```
- Recusa: o motivo está no evento de auditoria (`eventType=SIGNING_REFUSED`, `operation`, `reason`). Várias pessoas com o mesmo motivo ao mesmo tempo: provável mudança da Jupiter (programa ou conta nova na rota). Uma só pessoa: provável defeito da montagem na API (`MessageBuilder`).
- `real-exits-trigger-policy-refused`: antes da gravação real da Trigger, o esperado é **zero pedidos**; qualquer recusa indica o flag das saídas ligado sem a política (`docs/runbooks/real-exits.md`, seção 4).
- Indisponível: KMS (RB-04), banco do TxSigner (RB-11), credencial de serviço (`txsigner_auth_failures_total`), tarefa reiniciando em ciclo (eventos do serviço).

**Mitigação.**
- Recusa: kill switch ligado até entender. Saídas: desligar `MEMETRADE_REAL_EXITS_ENABLED` (o `deploy.yml` já recusa `true` na nuvem).
- Indisponível: o ECS sobe outra tarefa sozinho (remediação automática, Q3). Se a tarefa não fica saudável, voltar a revisão anterior do TxSigner pelo passo do fundador (`rollback-runbook.md`, passo 4, A2). A ordem em Automática total falha `UPSTREAM_UNAVAILABLE`; a pausa `UPSTREAM` por pessoa sai sozinha depois de 5 min saudável.
- Comandos `LOWER_CAP`/`DEACTIVATE` pendentes (`real-ops-txsigner-command-pending`): o `TxSignerCommandJob` reenvia até confirmar; basta o TxSigner voltar.

**Recuperação.** Prontidão confirmada pelo probe do Terraform (`cd-config.md` 4.6, passo 5). Saque de prova na devnet; na mainnet, só por decisão do fundador.

**Verificação.** `metrics-absent-txsigner` em OK; `real_upstream_failures_total{provider=TXSIGNER}` sem crescimento; nenhum saque preso (RB-08); `real_pending_txsigner_oldest_seconds` em 0.

**O que NÃO fazer.** Nunca desligar a política, nunca ampliar a lista de programas sem revisão do ADR-004, nunca aumentar teto de taxa ou derrapagem, nunca assinar à mão com o papel de administração (a política da chave não deixa, e não se tenta). Nunca reutilizar a `Idempotency-Key` de uma ordem recusada para uma ordem nova.

**Escalonamento.** Fundador. Recusa em massa ligada à Jupiter: canal da Jupiter. TxSigner fora por KMS: suporte da AWS (plano a definir).

---

## RB-04 — KMS com erro ou throttling

| Campo | Conteúdo |
|---|---|
| Gatilho | `tx-signer-kms-errors` (mais de 3 erros `THROTTLED` ou `ERROR` em 5 min); indiretamente `real-execution-signer-unavailable` |
| Severidade | **P1**, porque o saque depende do `kms:Sign`. Um pico isolado que some na janela seguinte é **P2** |
| Runbook existente | nenhum específico; o circuito do KMS está no `reliability-design.md` da U1 (1 s por chamada, sem repetição, abre com 50% de falhas) |

**Primeiros 5 minutos.**
1. Conferir se há ação administrativa no KMS ao mesmo tempo (`kms-admin-action`, `kms-keys-per-hour`): se houver e não for do fundador, é RB-10.
2. Conferir o AWS Health Dashboard da conta (console) para o KMS na região.
3. C4.

**Diagnóstico.**
```bash
metric txsigner_kms_errors_total Sum Name=kind,Value=THROTTLED
metric txsigner_kms_errors_total Sum Name=kind,Value=ERROR
metric txsigner_kms_sign_seconds_max Maximum
aws cloudwatch get-metric-statistics --namespace AWS/Usage --metric-name CallCount \
  --dimensions Name=Service,Value=KMS Name=Type,Value=API Name=Resource,Value=Sign Name=Class,Value=None \
  --start-time "$(date -u -d '-1 hour' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" --period 300 --statistics Sum
aws logs tail /ecs/$NAME/txsigner --since 30m --format short | grep -i kms | tail -30
```
- `THROTTLED`: excesso de chamadas. Procurar a origem (rajada de ativações: `kms-keys-per-hour`; repetição em ciclo).
- `ERROR` com `AccessDenied`: política da chave ou do papel mudou, etiqueta da chave mexida, ou o pedido saiu fora do endpoint de VPC (`aws:SourceVpce`). Isso é RB-10 até prova em contrário.

**Mitigação.** O circuito do TxSigner abre e responde 503; os saques ficam REQUESTED e são tentados de novo com espera crescente (Q3, remediação automática). Throttling por rajada: kill switch (C1) reduz as assinaturas de swap e deixa a cota para os saques.

**Recuperação.** O circuito fecha sozinho com o KMS de volta. Nada a refazer: os pedidos são idempotentes por `signingRequestId`.

**Verificação.** `txsigner_kms_errors_total` sem crescimento por 15 min; saques pendentes avançando (`realwallet_withdrawal_oldest_pending_seconds` caindo).

**O que NÃO fazer.** Nunca mudar a política da chave ou do papel para "destravar". Nunca agendar exclusão, desabilitar ou mexer nas etiquetas de uma chave de carteira (a exclusão perde os fundos para sempre). Nunca criar chave nova para uma carteira existente.

**Escalonamento.** Fundador → suporte da AWS (caso técnico exige plano pago; a definir).

---

## RB-05 — RPC fora do ar (os dois provedores)

| Campo | Conteúdo |
|---|---|
| Gatilho | `real-execution-upstream-pause` (com `provider=RPC`); `real-ops-reconciliation-deferred`; `real-execution-sent-old` e `-ambiguous-old` subindo juntos; reconciliações `UNAVAILABLE`; sintoma: saldos sem atualizar, depósitos não lidos |
| Severidade | **P1** com os dois provedores fora, porque o saque não chega à rede. **P2** com um provedor fora (o outro assume) |
| Runbook existente | `docs/runbooks/real-execution.md`, seção 2 |

**Primeiros 5 minutos.**
1. `metric real_upstream_failures_total Sum Name=provider,Value=RPC`.
2. Página de status pública da rede Solana e dos dois provedores (`escalation-matrix.md`, seção 4).
3. Com os dois fora e REAL ligado: **kill switch (C1)**, para não acumular ordens que não serão enviadas. Nada do que já foi enviado se perde: fica `SENT`/`AMBIGUOUS` até a rede responder.

**Diagnóstico.**
```bash
metric real_upstream_failures_total Sum Name=provider,Value=RPC
metric real_upstream_pauses_total Sum
metric real_orders_sent_oldest_seconds Maximum
metric realwallet_withdrawal_oldest_pending_seconds Maximum Name=state,Value=AMBIGUOUS
logsq falhas-dos-trabalhos 2
aws ecs describe-task-definition --task-definition "$NAME-api-worker" \
  --query 'taskDefinition.containerDefinitions[?name==`worker`].environment[?starts_with(name, `MEMETRADE_RPC_`)].name'
```
Os endereços dos provedores ficam em `MEMETRADE_RPC_PRIMARY_URL` e `MEMETRADE_RPC_SECONDARY_URL` (a consulta acima lista só os nomes; se um endereço trouxer chave na URL, ele é segredo e não vai para o incidente).

**Mitigação.** Esperar um provedor voltar; o circuito de cada provedor troca sozinho para o outro. Um saque só vira `FAILED` quando os **dois** provedores respondem e confirmam que a assinatura não existe e que o blockhash venceu; com um provedor fora, o saque continua `AMBIGUOUS`, e a reserva fica (correto). Não existe modo "um provedor só" no código (lacuna IR-G6).

**Recuperação.** Com um provedor de volta: os trabalhos de confirmação, ambíguas, depósitos e reconciliação retomam sozinhos. A pausa `UPSTREAM` por pessoa sai depois de 5 min saudável.

**Verificação.** Reconciliações `CLEAN`; nenhum saque AMBIGUOUS há mais de 5 min; `real-ops-reconciliation-deferred` em OK. Depois, C5.

**O que NÃO fazer.** Nunca trocar o endereço de RPC por um provedor não contratado ou público sem decisão do fundador. Nunca forçar `FAILED` num saque ou ordem para liberar reserva.

**Escalonamento.** Fundador → os dois provedores de RPC (a definir). Mais de 4 h com os dois fora: avisar os convidados afetados (`incident-plan.md`, modelo M2).

---

## RB-06 — Jupiter fora do ar

| Campo | Conteúdo |
|---|---|
| Gatilho | `real-execution-upstream-pause` (com `provider=JUPITER`); `real-exits-conditional-unread` (condicional OPEN sem leitura há 2 h); `real-exits-cancelling-stalled` (cancelamento parado há 15 min) |
| Severidade | **P2.** Swaps param e a pessoa é pausada com `UPSTREAM`; o saque não depende da Jupiter. Condicional em custódia da Jupiter sem cancelamento por mais de 2 h: **P1** (token da pessoa preso fora da carteira do bot) |
| Runbook existente | `docs/runbooks/real-execution.md`, seção 2; `docs/runbooks/real-exits.md`, seção 3 |

**Primeiros 5 minutos.** `metric real_upstream_failures_total Sum Name=provider,Value=JUPITER`; status público da Jupiter (canal a confirmar). Hoje as saídas condicionais estão desligadas em toda a nuvem, então os dois alarmes de saídas não devem disparar; se dispararem, conferir o flag (RB-03, recusa de condicional).

**Diagnóstico.**
```bash
metric real_upstream_failures_total Sum Name=provider,Value=JUPITER
metric real_conditional_cancelling_oldest_seconds Maximum
metric real_conditional_unread_oldest_seconds Maximum
logsq falhas-dos-trabalhos 2
```
Só 5xx, 429 e tempo esgotado contam como indisponível. Outro 4xx falha só aquela ordem com `NO_ROUTE` e não pausa ninguém. Conferir que `MEMETRADE_JUPITER_BASE_URL` é `lite-api.jup.ag` ou `api.jup.ag`.

**Mitigação.** Esperar; o trabalho `health` testa a Jupiter a cada 30 s e retira a pausa `UPSTREAM` depois de 5 min saudável. Em queda longa com REAL ligado: kill switch (C1). Condicional em `CANCELLING`: o `ConditionalCancelJob` monta outro cancelamento quando o anterior vence.

**Recuperação, verificação.** Pausas `UPSTREAM` retiradas sozinhas; `real_conditional_cancelling_oldest_seconds` em 0; nenhuma recusa nova `NO_ROUTE` em massa.

**O que NÃO fazer.** Nunca retirar a pausa `UPSTREAM` à mão (não há rota, e não se edita o banco). Nunca editar `real_conditional_order`. Nunca trocar o endereço da Jupiter por outro serviço.

**Escalonamento.** Fundador → canal da Jupiter (a definir).

---

## RB-07 — Fonte de preços parada

| Campo | Conteúdo |
|---|---|
| Gatilho | `real-price-stale`, `market-source-failed`, `market-call-counter-unavailable`, `slo-freshness-burn-1h`, `slo-freshness-burn-6h`; P3: `market-monthly-budget`, `market-invalid-values`, `market-provider-unstable` |
| Severidade | **P2** (a barreira já recusa ordens com dado velho: falha fechada). **P3** para os três alarmes de qualidade e orçamento |
| Runbook existente | `docs/runbooks/coingecko-api-key.md`, seções 2 e 3 |

**Primeiros 5 minutos.** O REAL já recusa compras com `STALE_DATA`; nada de dinheiro está em risco por causa disso. Conferir se o worker está vivo (`metrics-absent-worker` → RB-12) e o estado da fonte.

**Diagnóstico.**
```bash
metric marketdata_oldest_price_age_seconds Maximum
metric marketdata_source_state Minimum Name=source,Value=coingecko Name=state,Value=FAILED
metric marketdata_provider_conservative_mode Minimum Name=source,Value=coingecko
metric marketdata_provider_budget_ratio Maximum Name=source,Value=coingecko
aws logs tail /ecs/$NAME/api-worker --since 30m --format short | grep "Market provider call failed" | tail -20
```
- 401/403: chave revogada ou vazada → `docs/runbooks/coingecko-api-key.md`, seção 2.
- 429: cota ou orçamento → `market-monthly-budget`.
- `market-call-counter-unavailable`: o banco não responde para `marketdata_provider_usage` (modo conservador) → RB-11.

**Mitigação.** 5xx e tempo esgotado: o circuito abre por 60 s e tenta de novo; acompanhar. Nunca há volta para a fonte simulada na nuvem.

**Recuperação, verificação.** `marketdata_source_state{state=OK}` em 1; `marketdata_oldest_price_age_seconds` abaixo de 60 s por duas leituras; alarmes de frescor em OK.

**O que NÃO fazer.** Nunca pôr `sim-a`/`sim-b` em `MARKET_SOURCES` na nuvem (o código recusa subir). Nunca mostrar preço velho como atual nem tratar `unknown` como zero. Nunca colar a chave da CoinGecko no incidente.

**Escalonamento.** Fundador → suporte da CoinGecko (canal do plano contratado, a definir).

---

## RB-08 — Saque parado ou recusado

| Campo | Conteúdo |
|---|---|
| Gatilho | **Nenhum alarme na nuvem ainda** (lacuna OBS-O1 / IR-G1). Sintomas: pessoa relata saque parado; painel "Visão REAL" → "Saque pendente mais antigo" acima de 600 s, ou AMBIGUOUS acima de 300 s; `txsigner_decisions_total{kind=WITHDRAWAL,decision=REFUSED}` subindo; indiretamente `metrics-absent-txsigner`, `tx-signer-kms-errors`, RPC fora |
| Severidade | **P1** sempre: o saque é o direito que nunca pode ser bloqueado (`project.md`) |
| Runbook existente | nenhum dedicado; a regra de tempo e de rede do saque está no `reliability-design.md` da U1 |

**Primeiros 5 minutos.**
1. Um saque **recusado** pelo TxSigner é ataque ou defeito: **kill switch (C1)**. O kill switch não afeta o saque.
2. Um saque **parado**: achar a dependência quebrada — TxSigner (RB-03), KMS (RB-04), RPC (RB-05), worker (RB-12), banco (RB-11).
3. C4.

**Diagnóstico.**
```bash
for s in REQUESTED SIGNED SENT AMBIGUOUS; do
  echo "== $s"; metric realwallet_withdrawal_oldest_pending_seconds Maximum Name=state,Value=$s
done
metric realwallet_withdrawals_total Sum Name=state,Value=FAILED
metric txsigner_decisions_total Sum Name=kind,Value=WITHDRAWAL Name=decision,Value=REFUSED
logsq recusas-txsigner-por-motivo 6
logsq seguir-correlation-id 24 <correlationId mostrado à pessoa na tela>
```
Na administração, a pessoa: saldo, pausa e se há divergência (com divergência, o saque fica limitado ao que a rede confirma: `WITHDRAWAL_LIMITED_BY_DIVERGENCE`, que é o comportamento certo).

Regras de tempo (U1): `REQUESTED` sem assinatura vira `FAILED` em 24 h e libera a reserva; `SIGNED`/`SENT`/`AMBIGUOUS` só terminam pela conferência na rede, e `FAILED` exige os dois provedores concordando.

**Mitigação.** Corrigir a dependência. O worker retoma os saques não terminados a partir do estado gravado, com espera crescente de 5 s até 5 min. O saque do fundador na mainnet é dinheiro real: qualquer saque de prova é decisão explícita dele.

**Recuperação.** O saque chega a `CONFIRMED`, ou a `FAILED` com a reserva liberada e a pessoa podendo pedir de novo.

**Verificação.** Nenhum saque pendente há mais de 10 min; a reconciliação seguinte da carteira `CLEAN`; a pessoa avisada (`incident-plan.md`, modelo M4).

**O que NÃO fazer.** Nunca mandar o dinheiro "por fora" para a pessoa, nunca assinar à mão, nunca trocar a carteira principal de destino (o TxSigner recusa qualquer outro destino, e isso é a proteção). Nunca marcar um saque como `FAILED` à mão. Nunca pedir à pessoa chave, frase de recuperação ou assinatura.

**Escalonamento.** Fundador. Saque parado por mais de 4 h: aviso à pessoa (M4) e, se convidado, assessor jurídico informado. Revisão pós-incidente obrigatória.

---

## RB-09 — Perda máxima do dia em massa

| Campo | Conteúdo |
|---|---|
| Gatilho | Sem alarme dedicado. Sintomas: várias carteiras pausadas com `DAILY_LOSS` no mesmo dia (administração → "Usuários"); `anomaly-real-refusals` e `anomaly-real-orders` (aviso); `real-risk-job-stalled-daily-loss` (o trabalho que pausa parou → RB-14) |
| Severidade | **P2.** Vira **P1** se vier junto de preço velho ou inválido (`real-price-stale`, `market-invalid-values`), de recusa da política do TxSigner ou de divergência: pode ser dado errado ou ataque, não mercado |
| Runbook existente | `docs/runbooks/real-risk.md`, seção 2 |

**Primeiros 5 minutos.** O código já pausou cada carteira que chegou a 20% de perda da base; compras e vendas do bot param, e a liquidação "vender tudo e sacar" e o saque continuam. Conferir se a causa é o mercado (movimento geral) ou o sistema (dado de preço, execução).

**Diagnóstico.**
```bash
metric real_risk_evaluations_total Sum Name=outcome,Value=REJECTED
metric marketdata_oldest_price_age_seconds Maximum
metric marketdata_invalid_values_total Sum
logsq seguir-correlation-id 24 <correlationId de uma recusa DAILY_LOSS>
```
A administração mostra, por pessoa, a pausa e as últimas ordens.

**Mitigação.** Mercado: nada a fazer; as pausas voltam sozinhas às 00:00 UTC (trabalho `day`). Sistema ou dúvida: kill switch (C1) até entender. Para tirar um token problemático de todos os bots: administração → "Tokens bloqueados" (`POST /api/v1/admin/real/token-blocks`), com motivo sem dado pessoal.

**Recuperação, verificação.** Pausas retomadas no dia seguinte; nenhuma recusa nova sem causa; P&L REAL conferido com cenários conhecidos se houver suspeita de cálculo.

**O que NÃO fazer.** Nunca retomar à mão uma pausa `DAILY_LOSS` (não há rota, e não se edita `real_preferences`). Nunca afrouxar `daily-loss-max-bps` (o código recusa valor mais frouxo fora de `test`/`local`). Nunca prometer recuperação de perda aos convidados.

**Escalonamento.** Fundador. Com convidados afetados: aviso (M1) sem promessa de resultado.

---

## RB-10 — Suspeita de comprometimento

| Campo | Conteúdo |
|---|---|
| Gatilho | `security-founder-role-iam-change`, `security-founder-family-registered`, `security-founder-family-run`, `security-txsigner-service-updated` **sem um passo do fundador correspondente**; `kms-admin-action`; `kms-keys-per-hour`; regras do EventBridge `eureka-real-guardduty-high` (achado com severidade 7 ou mais) e `eureka-real-access-analyzer-active`; `real-execution-policy-refused` em massa; divergência sem explicação; credencial ou token vazado |
| Severidade | **P1** |
| Runbook existente | `docs/runbooks/coingecko-api-key.md`, seção 2 (chave da CoinGecko); `docs/runbooks/real-operations.md`, seção 5 (token do GitHub); `docs/runbooks/txsigner-recovery.md`, seção 3 (credencial do TxSigner) |

**Primeiros 5 minutos.**
1. **Kill switch (C1).** Se a administração não responde, C2.
2. Conferir se o alarme corresponde a um ato seu (passo 4.6, `apply`, rollback A2). Os alarmes `-security-*` disparam a cada ato do fundador, e isso é esperado: anote e encerre.
3. Se não foi você: C4, P1, e siga.

**Diagnóstico.**
```bash
# Quem fez: eventos da trilha nas últimas 2 h (o grupo de log da trilha é /eureka-real/cloudtrail)
for ev in RegisterTaskDefinition RunTask StartTask UpdateService PutRolePolicy AttachRolePolicy UpdateAssumeRolePolicy \
          PutKeyPolicy ScheduleKeyDeletion DisableKey TagResource UntagResource CreateKey; do
  aws cloudtrail lookup-events --lookup-attributes AttributeKey=EventName,AttributeValue=$ev \
    --start-time "$(date -u -d '-2 hour' +%FT%TZ)" \
    --query 'Events[].[EventTime,EventName,Username,Resources[0].ResourceName]' --output text
done
# Quem registrou a revisão em serviço do TxSigner
aws ecs describe-task-definition --task-definition "$(aws ecs describe-services --cluster "$CLUSTER" --services txsigner \
  --query 'services[0].taskDefinition' --output text)" --query 'taskDefinition.[registeredBy,registeredAt,taskRoleArn]'
# Achados
aws guardduty list-findings --detector-id "$(aws guardduty list-detectors --query 'DetectorIds[0]' --output text)" \
  --finding-criteria '{"Criterion":{"severity":{"Gte":7}}}'
aws accessanalyzer list-findings --analyzer-arn "$(aws accessanalyzer list-analyzers --query 'analyzers[0].arn' --output text)" \
  --filter '{"status":{"eq":["ACTIVE"]}}'
logsq recusas-txsigner-por-motivo 24
```

**Mitigação (contenção), na ordem.**
1. Kill switch já ligado. O saque continua.
2. **Papel suspeito:** revogar as sessões ativas do papel com uma política de negação por data de emissão (é o que o botão "Revoke active sessions" do console faz):
   ```bash
   ROLE=<eureka-real-txsigner | eureka-real-deploy-<env> | outro papel envolvido>
   aws iam put-role-policy --role-name "$ROLE" --policy-name AWSRevokeOlderSessions --policy-document \
     "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"*\",\"Resource\":\"*\",\"Condition\":{\"DateLessThan\":{\"aws:TokenIssueTime\":\"$(date -u +%FT%TZ)\"}}}]}"
   ```
   No papel do TxSigner, isso para as assinaturas (inclusive saques) até a tarefa pegar credencial nova; force uma tarefa nova do TxSigner pelo passo do fundador (`cd-config.md` 4.6, passo 4, com o SHA em serviço). O alarme `security-founder-role-iam-change` dispara, e é esperado.
3. **Implantação suspeita:** no GitHub, desativar o workflow `deploy.yml` e remover os revisores/aprovações pendentes do Environment; o papel de implantação só é assumido de dentro do Environment.
4. **Revisão do TxSigner não registrada pelo fundador:** voltar a revisão do Terraform (`rollback-runbook.md`, passo 4, A2), nunca a "mais recente" da família.
5. **Segredo vazado:** revogar primeiro, trocar depois:
   - token de serviço do TxSigner: `docs/runbooks/txsigner-recovery.md`, seção 3, **sem** a convivência de 24 h (retirar a versão anterior na hora);
   - token do GitHub: `docs/runbooks/real-operations.md`, seção 5;
   - chave da CoinGecko: `docs/runbooks/coingecko-api-key.md`, seção 2;
   - se o segredo entrou no Git: revogar e trocar, depois limpar o histórico (`team.md`).

**Recuperação.** Causa entendida; acessos revogados; revisões de serviço conferidas (`registeredBy` do fundador ou do Terraform); reconciliação `CLEAN` em todas as carteiras; então C5.

**Verificação.** Nenhum achado ativo novo no GuardDuty e no Access Analyzer por 24 h; `-security-*` só com atos do fundador; política `AWSRevokeOlderSessions` mantida até a revisão pós-incidente decidir.

**O que NÃO fazer.** Nunca apagar logs, trilha, achados ou o grupo `/eureka-real/cloudtrail` (é a evidência). Nunca agendar exclusão, desabilitar ou mexer nas etiquetas das chaves de carteira (perde os fundos). Nunca mover fundos de convidados "para proteger": o saque só vai para a carteira principal registrada de cada um, e quem saca é a pessoa. Nunca desligar a política do TxSigner.

**Escalonamento.** Fundador → suporte da AWS (incidente de segurança) → assessor jurídico (dados pessoais ou fundos de terceiros: avaliar obrigação de comunicar à ANPD e aos titulares, pela LGPD; a decisão é do assessor jurídico, a definir).

---

## RB-11 — Banco indisponível ou restauração

| Campo | Conteúdo |
|---|---|
| Gatilho | Indisponível: `slo-api-availability-burn-1h`/`-6h`, `real-risk-errors` (dependências `wallet`, `ledger`, `market`), `market-call-counter-unavailable`, muitos `*-job-stalled-*` ao mesmo tempo, `real-ops-txsigner-command-pending`. Restauração: `real-ops-restore-pause`, `real-ops-job-stalled-restore-check` |
| Severidade | **P1** (o saque depende dos dois bancos). A pausa de restauração depois de um ensaio planejado na devnet é **P3** |
| Runbook existente | `docs/runbooks/real-operations.md`, seção 4 (pausa de restauração); `docs/runbooks/txsigner-recovery.md`, seção 1 (banco do TxSigner); procedimento completo de recuperação em `incident-plan.md`, seção 6 |

**Primeiros 5 minutos.** Qual banco: `eureka-real-app` (API e worker) ou `eureka-real-txsigner`. O REAL já falha fechado sem banco (a barreira recusa, os trabalhos param). C4.

**Diagnóstico.**
```bash
for db in app txsigner; do
  aws rds describe-db-instances --db-instance-identifier $NAME-$db \
    --query 'DBInstances[0].[DBInstanceIdentifier,DBInstanceStatus,AvailabilityZone,LatestRestorableTime]' --output text
  aws rds describe-events --source-identifier $NAME-$db --source-type db-instance --duration 120 \
    --query 'Events[].[Date,Message]' --output text
done
aws cloudwatch get-metric-statistics --namespace AWS/RDS --metric-name FreeStorageSpace \
  --dimensions Name=DBInstanceIdentifier,Value=$NAME-app --start-time "$(date -u -d '-6 hour' +%FT%TZ)" \
  --end-time "$(date -u +%FT%TZ)" --period 300 --statistics Minimum --output text
aws logs tail /ecs/$NAME/api-worker --since 30m --format short | grep -iE 'jdbc|connection|hikari' | tail -20
metric real_restore_pause_active Maximum Name=phase,Value=CHECK_PENDING
metric real_restore_pause_active Maximum Name=phase,Value=RESTORE
```
Também o painel "Serviços" → "RDS: CPU, conexões e espaço livre". Não há alarme direto de RDS (lacuna IR-G4).

**Mitigação.**
- Instância parada, reiniciando ou em manutenção: esperar `available`; os pools de conexão reconectam.
- Espaço cheio: aumentar o armazenamento (`aws rds modify-db-instance --db-instance-identifier $NAME-<db> --allocated-storage <GB> --apply-immediately`) e depois alinhar o Terraform no próximo `apply`.
- Dado perdido ou corrompido: **restauração**, pelo `incident-plan.md`, seção 6 (saque primeiro, REAL pausado até a reconciliação limpa).
- Pausa `RESTORE` depois de uma restauração: `docs/runbooks/real-operations.md`, seção 4.

**Recuperação.** Depois de uma restauração: o worker abre `RESTORE_CHECK_PENDING` sozinho; com algo desconhecido, a pausa vira `RESTORE` e só sai com todas as carteiras `CLEAN` e a confirmação do fundador (`POST /api/v1/admin/real/restore/confirmation`).

**Verificação.** H7 do `validation-report.md` (`available`, `LatestRestorableTime` a menos de 5 min); smoke (`rollback-runbook.md`, passo 6); nenhuma pausa de restauração; saque de prova na devnet.

**O que NÃO fazer.** Nunca restaurar o banco para desfazer uma implantação. Nunca tirar o bloqueio de uma chave do TxSigner sem o procedimento com MFA de `txsigner-recovery.md`. Nunca confirmar a restauração com uma carteira não `CLEAN` (a API recusa com `409 RESTORE_NOT_CLEAN`).

**Escalonamento.** Fundador → suporte da AWS (RDS).

---

## RB-12 — Caminho das métricas quebrado (`metrics-absent`)

| Campo | Conteúdo |
|---|---|
| Gatilho | `metrics-absent-api`, `metrics-absent-worker`, `metrics-absent-txsigner` (nenhuma leitura em 4 min); vários alarmes `breaching` ao mesmo tempo |
| Severidade | **P2** quando o processo está vivo e só as métricas sumiram (estamos cegos). **P1** quando o processo caiu: TxSigner → RB-03; worker → saques e trabalhos parados (RB-08, RB-14) |
| Runbook existente | `alarms.md`, seções 1, 7 (rollback do agente) e 10 (verificação) |

**Primeiros 5 minutos.** Separar processo de métrica: `services` (1/1 e `COMPLETED`?) e os logs do próprio processo nos últimos minutos.

**Diagnóstico.**
```bash
services
aws logs tail /ecs/$NAME/api-worker --since 15m --format short | tail -5          # o processo escreve?
aws logs tail /ecs/$NAME/txsigner --since 15m --format short | tail -5
aws logs filter-log-events --log-group-name /ecs/$NAME/api-worker \
  --log-stream-name-prefix api-worker/metrics-agent --filter-pattern '"E!"' --max-items 20
aws logs filter-log-events --log-group-name /ecs/$NAME/txsigner \
  --log-stream-name-prefix txsigner/metrics-agent --filter-pattern '"AccessDenied"' --max-items 20
aws logs describe-log-streams --log-group-name /ecs/$NAME/metrics/api-worker \
  --order-by LastEventTime --descending --max-items 3 --query 'logStreams[].[logStreamName,lastEventTimestamp]'
```
- Processo vivo e agente com erro: permissão (`AccessDenied` no grupo de métricas), endpoint de Logs, configuração.
- Os três ausentes juntos: atraso de entrega do EMF (OBS-O3) ou problema regional do CloudWatch.

**Mitigação.** O agente não é essencial: o processo segue sem ele. Uma tarefa nova costuma resolver (`aws ecs update-service --cluster "$CLUSTER" --service api-worker --force-new-deployment`; no TxSigner, só pelo passo do fundador 4.6). Enquanto as métricas estão ausentes, acompanhar pelo painel "Serviços" (métricas da AWS) e pelos logs.

**Recuperação, verificação.** `metric process_uptime_seconds SampleCount Name=job,Value=<api|worker|txsigner>` com 2 por período; os três alarmes em OK (eles avisam também na volta, `ok_actions`); nenhum alarme preso em `INSUFFICIENT_DATA` (`alarms.md`, seção 10, comando 4).

**O que NÃO fazer.** Nunca mudar `treat_missing_data` nem desligar os alarmes `breaching` para calar o barulho; nunca dar ao papel `PutMetricData` ou mais permissões que a instrução restrita.

**Escalonamento.** Fundador.

---

## RB-13 — Implantação com falha

| Campo | Conteúdo |
|---|---|
| Gatilho | job `deploy` do `deploy.yml` vermelho; o circuit breaker do ECS devolveu a revisão; `slo-api-availability-burn-1h` ou alarmes do REAL na janela de observação; passo do fundador (4.6) com saída diferente de 0 |
| Severidade | **P2.** **P1** se o REAL estiver ligado e houver alarme de dinheiro (divergência, recusa da política, saque). **P3** se o job falhou antes de trocar qualquer serviço |
| Runbook existente | `operation/deployment-pipeline/rollback-runbook.md` (completo, inclusive o passo A2 do TxSigner e o caminho C sem GitHub) |

**Primeiros 5 minutos.** Ler o resumo do run (`gh run view <id>`) e ver em que passo parou. Se trocou algum serviço e há alarme de dinheiro: kill switch (C1).

**Diagnóstico.**
```bash
gh run list --workflow deploy.yml --limit 5 --json databaseId,displayTitle,conclusion,createdAt
gh run view <id> --log-failed | tail -80
services
service_events api-worker
logsq erros-5xx-por-componente 1
```

**Mitigação.** Circuit breaker já devolveu: a versão antiga está em serviço; só os passos 6 e 7 do `rollback-runbook.md`. Senão: `rollback-runbook.md`, passos 1 a 9.

**Recuperação, verificação.** Smoke verde; nenhum alarme em ALARM; reconciliação `CLEAN`; C5.

**O que NÃO fazer.** Nunca reverter migração nem restaurar o banco para desfazer a implantação. Nunca usar uma revisão antiga com o REAL ligado se o REAL deve ficar desligado (caminho B). Nunca dar ao papel de implantação permissão sobre o TxSigner para "agilizar".

**Escalonamento.** Fundador → status público do GitHub (Actions fora: caminho C).

---

## RB-14 — Trabalho em segundo plano parado (complementar)

Não é um modo de falha do Q1: existe para que cada alarme `*-job-stalled-*` tenha runbook. A causa costuma ser um dos modos acima (worker fora, banco, RPC).

| Campo | Conteúdo |
|---|---|
| Gatilho | os 21 alarmes `real-risk-job-stalled-*`, `real-execution-job-stalled-*`, `real-ops-job-stalled-*` e `real-exits-job-stalled-*` (lista na seção de cobertura); `real-risk-reservation-expired` (aviso: a U4 não consumiu a reserva a tempo) |
| Severidade | **P2** para trabalhos do caminho do dinheiro (`queue`, `driver`, `confirmation`, `ambiguous`, `reconciliation`, `kill-switch-cancel`, `txsigner-resend`, `daily-loss`, `cancel`, `restore-check`); **P3** para `purge`, `ops-gauges`, `exits-gauges`, `gates`, `day`, `reservation-expiry`, `signing-deadline`, `health`, `liquidation`, `read`. Muitos ao mesmo tempo: o worker caiu → RB-12 |
| Runbook existente | `docs/runbooks/real-execution.md`, seção 5; `docs/runbooks/real-risk.md`, seções 3 e 6; `docs/runbooks/real-exits.md`, seção 4; `docs/runbooks/real-operations.md` (alertas de cada seção) |

**Diagnóstico.**
```bash
alarms
logsq falhas-dos-trabalhos 2
for f in real_risk real_execution real_ops real_exits; do
  echo "== $f"; aws cloudwatch list-metrics --namespace Eureka/App --metric-name ${f}_job_runs_total \
    --query 'Metrics[].Dimensions[0].Value' --output text
done
metric real_execution_job_runs_total Sum Name=job,Value=<job>
```
Logs úteis: `REAL order step stopped`, `REAL risk job failed` (nome do trabalho e classe do erro).

**Mitigação.** Corrigir a dependência. Reiniciar o worker só se ele estiver vivo e travado (`--force-new-deployment` do `api-worker`); os trabalhos são idempotentes e usam ShedLock ou `SKIP LOCKED` (Q3). Durante um rollback para uma imagem sem a Unit, silenciar os alarmes da Unit (C3), como manda o `docs/runbooks/real-risk.md`, seção 6.

**Verificação.** O contador do trabalho volta a crescer; o alarme volta a OK.

**O que NÃO fazer.** Nunca apagar a linha do ShedLock com o trabalho rodando; nunca "rodar à mão" um trabalho que move estado de ordem ou saque.

**Escalonamento.** Fundador.

---

## RB-15 — Avisos de operação (complementar)

Também complementar: cobre os alarmes de aviso que não são falha, mas pedem uma ação.

| Alarme | O que significa | Ação | Severidade |
|---|---|---|---|
| `real-ops-kill-switch-on` | kill switch ligado há 30 min | lembrete: se o incidente acabou, C5; senão, anotar no registro e seguir | P3 |
| `real-ops-release-blocked` | liberação `BLOCKED` há mais de 1 h | ver o motivo (`CI_RED`, `CI_UNREACHABLE`, `DIVERGENCE_OPEN`, `KILL_SWITCH_ON`, `NETWORK_MISMATCH`) em `docs/runbooks/real-operations.md`, seção 1 | P3 |
| `anomaly-real-orders`, `anomaly-real-refusals` | volume de ordens ou recusas fora da faixa | olhar o painel "Visão REAL"; se vier com outros alarmes, seguir o runbook deles (RB-07, RB-09, RB-10) | P3 |
| `real-risk-reservation-expired` | reserva venceu sem a U4 consumir | `docs/runbooks/real-risk.md`, seção 3; nada a corrigir no banco | P3 |
| `real-risk-slow`, `real-execution-slow` | p95 acima da meta (desligados até OBS-G1 e OBS-G2) | `docs/runbooks/real-risk.md`, seção 5; informativo | P3 |
| `slo-api-availability-burn-6h` | consumo lento do orçamento de erro do api | `logsq erros-5xx-por-componente 6`; política do orçamento em `slo-config.md`, seção 3 | P3 (P2 se o de 1 h também disparar → RB-11 ou RB-13) |
| Orçamento da AWS (Budgets: 80% da previsão, 100% do real) | custo acima do previsto | Cost Explorer; economias de `alarms.md`, seção 9 | P3 |

---

## Cobertura: alarme → runbook

Todos os alarmes de `alarms.md` (62 ativos e 2 desligados), mais as duas regras do EventBridge e o orçamento. Os nomes reais levam o prefixo `eureka-real-` (`var.name`). "Sev." é a severidade do `alarms.md` (Q7 do Observability Setup).

| # | Alarme | Sev. | Runbook | Prioridade |
|---|---|---|---|---|
| 1 | `metrics-absent-api` | crítica | RB-12 | P2 (P1 se o api caiu) |
| 2 | `metrics-absent-worker` | crítica | RB-12 | P2 (P1 se o worker caiu) |
| 3 | `metrics-absent-txsigner` | crítica | RB-12 → RB-03 | P1 se o TxSigner caiu |
| 4 | `slo-api-availability-burn-1h` | alta | RB-11 / RB-13 | P2 |
| 5 | `slo-api-availability-burn-6h` | média | RB-15 | P3 |
| 6 | `slo-txsigner-availability-burn-1h` | alta | RB-03 | P1 |
| 7 | `slo-txsigner-availability-burn-6h` | média | RB-03 | P2 |
| 8 | `slo-freshness-burn-1h` | alta | RB-07 | P2 |
| 9 | `slo-freshness-burn-6h` | média | RB-07 | P3 |
| 10 | `anomaly-real-orders` | aviso | RB-15 | P3 |
| 11 | `anomaly-real-refusals` | aviso | RB-15 (RB-09) | P3 |
| 12 | `real-risk-slow` (desligado) | aviso | RB-15 | P3 |
| 13 | `real-execution-slow` (desligado) | aviso | RB-15 | P3 |
| 14 | `tx-signer-kms-errors` | alta | RB-04 | P1 |
| 15 | `kms-admin-action` | alta | RB-10 | P1 se não foi o fundador |
| 16 | `kms-keys-per-hour` | alta | RB-10 / RB-04 | P1 |
| 17 | `security-founder-role-iam-change` | alta | RB-10 | P1 se não foi o fundador |
| 18 | `security-founder-family-registered` | alta | RB-10 | P1 se não foi o fundador |
| 19 | `security-founder-family-run` | alta | RB-10 | P1 se não foi o fundador |
| 20 | `security-txsigner-service-updated` | alta | RB-10 | P1 se não foi o fundador |
| 21 | `market-source-failed` | alta | RB-07 | P2 |
| 22 | `real-price-stale` | alta | RB-07 | P2 |
| 23 | `market-call-counter-unavailable` | alta | RB-07 → RB-11 | P2 |
| 24 | `market-monthly-budget` | média | RB-07 | P3 |
| 25 | `market-invalid-values` | média | RB-07 | P3 |
| 26 | `market-provider-unstable` | média | RB-07 | P3 |
| 27 | `real-risk-errors` | crítica | RB-11 | P2 |
| 28 | `real-risk-job-stalled-daily-loss` | crítica | RB-14 (RB-09) | P2 |
| 29 | `real-risk-job-stalled-day` | crítica | RB-14 | P3 |
| 30 | `real-risk-job-stalled-reservation-expiry` | crítica | RB-14 | P3 |
| 31 | `real-risk-reservation-expired` | aviso | RB-15 | P3 |
| 32 | `real-execution-ambiguous-old` | crítica | RB-01 | P2 |
| 33 | `real-execution-sent-old` | crítica | RB-01 (RB-05) | P2 |
| 34 | `real-execution-policy-refused` | crítica | RB-03 (RB-10) | P1 |
| 35 | `real-execution-signing-refused` | crítica | RB-03 | P1 |
| 36 | `real-execution-signer-unavailable` | crítica | RB-03 | P1 |
| 37 | `real-execution-upstream-pause` | alta | RB-05 (RPC), RB-06 (Jupiter), RB-03 (TxSigner) | P2 |
| 38 | `real-execution-job-stalled-queue` | crítica | RB-14 | P2 |
| 39 | `real-execution-job-stalled-driver` | crítica | RB-14 | P2 |
| 40 | `real-execution-job-stalled-confirmation` | crítica | RB-14 (RB-01) | P2 |
| 41 | `real-execution-job-stalled-ambiguous` | crítica | RB-14 (RB-01) | P2 |
| 42 | `real-execution-job-stalled-signing-deadline` | crítica | RB-14 | P3 |
| 43 | `real-execution-job-stalled-health` | crítica | RB-14 | P3 |
| 44 | `real-execution-job-stalled-liquidation` | crítica | RB-14 | P3 |
| 45 | `real-ops-divergence-open` | crítica | RB-02 | P1 |
| 46 | `real-ops-restore-pause` | crítica | RB-11 | P1 (P3 em ensaio) |
| 47 | `real-ops-kill-switch-on` | alta | RB-15 | P3 |
| 48 | `real-ops-reconciliation-deferred` | alta | RB-02 (RB-05) | P2 |
| 49 | `real-ops-txsigner-command-pending` | alta | RB-03 | P2 |
| 50 | `real-ops-release-blocked` | aviso | RB-15 | P3 |
| 51 | `real-ops-job-stalled-gates` | crítica | RB-14 | P3 |
| 52 | `real-ops-job-stalled-reconciliation` | crítica | RB-14 (RB-02) | P2 |
| 53 | `real-ops-job-stalled-kill-switch-cancel` | crítica | RB-14 | P2 |
| 54 | `real-ops-job-stalled-txsigner-resend` | crítica | RB-14 (RB-03) | P2 |
| 55 | `real-ops-job-stalled-ops-gauges` | crítica | RB-14 | P3 (os alarmes de medidores ficam cegos) |
| 56 | `real-ops-job-stalled-purge` | crítica | RB-14 | P3 |
| 57 | `real-ops-job-stalled-restore-check` | crítica | RB-11 (RB-14) | P2 |
| 58 | `real-exits-cancelling-stalled` | crítica | RB-06 | P2 (P1 depois de 2 h) |
| 59 | `real-exits-conditional-unread` | alta | RB-06 | P2 |
| 60 | `real-exits-trigger-policy-refused` | alta | RB-03 | P1 (hoje o esperado é zero) |
| 61 | `real-exits-job-stalled-read` | crítica | RB-14 | P3 |
| 62 | `real-exits-job-stalled-cancel` | crítica | RB-14 (RB-06) | P2 |
| 63 | `real-exits-job-stalled-exits-gauges` | crítica | RB-14 | P3 |
| 64 | `real-exits-job-stalled-purge` | crítica | RB-14 | P3 |
| — | EventBridge `eureka-real-guardduty-high` (achado ≥ 7) | alta | RB-10 | P1 |
| — | EventBridge `eureka-real-access-analyzer-active` | alta | RB-10 | P1 |
| — | AWS Budgets `eureka-real-<env>-monthly` (e-mail direto, sem o tópico) | — | RB-15 | P3 |

**Resultado: 64 de 64 alarmes com runbook; nenhum alarme sem runbook.**

**Sobre a severidade:** a coluna "Sev." é a do alarme, que decide o canal (`escalation-matrix.md`, seção 2). A coluna "Prioridade" é a do incidente (`incident-plan.md`, seção 1), que decide a resposta. Vários alarmes de trabalho parado são "crítica" no alarme, porque o `breaching` pega processo morto, mas viram P3 quando o trabalho não está no caminho do dinheiro.

## Lacunas encontradas

### Modos de falha sem alarme (o runbook existe, o gatilho não)

| ID | Lacuna | Efeito | Dono | Antes de |
|---|---|---|---|---|
| **IR-G1** | **Saque parado ou recusado (RB-08) não tem alarme na nuvem.** É a OBS-O1 do `alarms.md`: os alertas locais `TxSignerRefusedAWithdrawal`, `WithdrawalStuck`, `WithdrawalAmbiguousTooLong`, `OrphanBotWalletKey`, `TxSignerCredentialFailures`, `ConsentRefusals` e `UnknownSourceDeposit` não têm gêmeo no CloudWatch, embora as métricas (`txsigner_decisions_total`, `realwallet_withdrawal_oldest_pending_seconds`, `txsigner_orphan_keys`, `txsigner_auth_failures_total`) já estejam na lista de permissão. O modo de falha mais sensível do produto só é visto por relato da pessoa ou pelo painel. | P1 sem detecção | próxima volta do Observability Setup | **mainnet** |
| IR-G2 | `TxSignerRecoveryStalled` e `TxSignerCredentialOld` dependem de `txsigner_recovery_phase` e `txsigner_credential_age_days`, que **não estão** na lista de permissão. Na nuvem, a recuperação parada e a credencial com mais de 80 dias não avisam (o SP-16 já pede o alarme de idade do segredo). | recuperação e giro sem aviso | Observability Setup e Environment Provisioning (SP-16) | convidados |
| IR-G4 | Nenhum alarme direto de RDS (espaço livre, CPU, conexões, estado). O banco fora aparece só de forma indireta (RB-11). | detecção mais lenta | Observability Setup | mainnet |
| IR-G5 | Perda do dia em massa (RB-09) não tem alarme próprio; `real_risk_daily_loss_pauses_total` não está na lista de permissão. | só pelo painel e pelas anomalias | Observability Setup | convidados |

### Lacunas de procedimento (o runbook não consegue ser cumprido como está)

| ID | Lacuna | Efeito | Dono | Antes de |
|---|---|---|---|---|
| **IR-G7** | **Nenhum cliente chama `POST /internal/v1/recovery/wallets` nem `/internal/v1/recovery/complete`.** O `txsigner-recovery.md` diz que "a API chama", mas não existe esse código na API (só o controlador no TxSigner). O fundador também não alcança o TxSigner: ele fica na subrede sem internet, atrás do token de serviço, sem `ExecuteCommand`. Depois de restaurar o banco do TxSigner, a carteira cuja política se perdeu fica **sem saque** até alguém registrar a política. Isso contraria "saque primeiro" (Q5). | DR incompleto | Code Generation (U1): cliente na API ou tarefa avulsa do fundador | **mainnet** |
| IR-G3 | Não existe caminho de leitura do banco na nuvem para o fundador (nem tarefa avulsa de leitura, nem `ExecuteCommand`). O desbloqueio de uma chave do TxSigner (`txsigner-recovery.md`, seção 1) e as consultas SQL dos runbooks não têm como ser feitos na nuvem. | diagnóstico limitado à administração e aos logs | Environment Provisioning: família avulsa do fundador, com papel de leitura e MFA | mainnet |
| IR-G6 | O `cd-config.md` (seção 7) lista um "modo um provedor só" como comando administrativo da U1; ele não existe no código. Com um provedor de RPC fora por muito tempo, os saques AMBIGUOUS não podem virar FAILED (a regra exige os dois). | saque AMBIGUOUS preso enquanto um provedor estiver fora | fundador decide se é preciso; senão, corrigir o `cd-config.md` | mainnet |
| IR-G8 | O SMS do plantão não está no Terraform (só e-mail). Texto pronto em `escalation-matrix.md`, seção 3. | P1 de madrugada só por e-mail | responsável do projeto | mainnet |

## Sources

- `incident-response-questions.md` (Q1 a Q5, resumo confirmado).
- `operation/observability-setup/alarms.md` (nomes, severidades, janelas, OBS-G1 a OBS-G4 e OBS-O1 a OBS-O5), `slo-config.md`, `log-queries.md` (as cinco consultas salvas) e `dashboards.md` (painéis "Visão REAL", "Serviços" e "Segurança").
- `operation/deployment-pipeline/rollback-runbook.md` e `cd-config.md` (4.6, 6 e 7).
- `operation/environment-provisioning/validation-report.md` (seções 6, 9, 10 e 11) e `security-posture-review.md` (SP-01 a SP-21).
- `docs/runbooks/real-execution.md`, `real-risk.md`, `real-exits.md`, `real-operations.md`, `real-ui.md`, `coingecko-api-key.md` e `txsigner-recovery.md`.
- `construction/u1-bot-wallet/` a `u7-exits-and-fiscal/`: `nfr-design/reliability-design.md` e `nfr-design/security-design.md`; `infrastructure-design/infrastructure-specification.md` (implantação, rollback e recuperação de cada Unit).
- `construction/u5-real-operations/code-generation/code-summary.md` (kill switch, restauração, divergência e pausas).
- Código e IaC conferidos: `infra/terraform/modules/observability/*.tf` (nomes dos alarmes e lista de permissão), `infra/terraform/main.tf` (tópico e assinatura de e-mail), `infra/observability/real-alerts.yml`, `apps/api/openapi/openapi-admin.yaml` (rotas `/api/v1/admin/real/*`), `apps/txsigner/.../web/RecoveryController.java` e `apps/api/.../botwallet/application/RestoreService.java`.
- `team.md` (Deployment: rollback começa pelo flag ou pelo kill switch; fundador revisor único) e `project.md` (saque sempre disponível; ledger só com compensação; nada de assinar fora do TxSigner).
- `.claude/knowledge/aidlc-operations-agent/incident-response-guide.md`.
