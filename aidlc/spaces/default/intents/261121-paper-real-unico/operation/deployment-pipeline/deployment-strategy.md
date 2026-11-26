# Estratégia de implantação — intent `261121-paper-real-unico`

Data: 2026-11-25. Conversa: português (Brasil). Decisões: `deployment-pipeline-questions.md` (Q1 a Q5, opção A). Fluxo, migrações, matriz e portões: `cd-config.md`. Passo a passo do rollback: `rollback-runbook.md`.

## 1. Estratégia: herdada do 261113, sem mudança [Q1]

Vale inteira a `deployment-strategy.md` do intent `261113-trading-real-bot` (`aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/deployment-strategy.md`), seções 1 a 8:

| Elemento | Valor | Onde está o detalhe |
|---|---|---|
| Tipo | atualização rolling no ECS, `minimumHealthyPercent = 100`, `maximumPercent = 200`: a tarefa nova fica saudável antes de a antiga sair | 261113, seção 1; conferido em `infra/terraform/modules/services/api-worker.tf` (linhas 122 a 126), `main.tf` e `web-admin.tf` |
| Circuit breaker | ligado, com volta automática para a revisão anterior enquanto a nova nunca ficou saudável | 261113, seções 1 e 5 |
| Ordem | TxSigner primeiro (passo do fundador com MFA), depois `db-migrate`, `api-worker`, web e admin | 261113, seção 2.1 |
| Smoke | digest em todas as tarefas, alvos saudáveis, `GET /api/v1/legal/current`, `/api/health` e `/privacidade` do web e do admin, TxSigner `HEALTHY` | 261113, seção 5; `cd-config.md` do 261113, 4.5 |
| Janela de observação | 10 minutos; aborta com qualquer alarme `<NAME_PREFIX>-*` em `ALARM` ou 5xx acima de 1% (mínimo de 5 erros) | 261113, seção 5 |
| Quando implantar | o fundador é o único on-call; uma implantação na mainnet só começa quando ele pode acompanhar a janela de 10 minutos **e os 30 minutos seguintes**. Fora disso, só devnet | 261113, seção 8; `team.md` |

**Por que nada muda:** o intent não traz serviço, tarefa, contêiner, porta, health check nem permissão nova (U1 a U4, `infrastructure-specification.md`). Tudo roda nas tarefas `api-worker` e `web` que já existem. As alternativas recusadas (blue/green, canary) continuam recusadas pelos mesmos motivos.

### 1.1 O que este intent acrescenta à leitura da janela de observação

A janela do 261113 já falha com qualquer alarme `<NAME_PREFIX>-*` em `ALARM`. Com este intent, entram nessa leitura:

| Alarme | Unit | Esperado depois de implantar o código (flag `false`) |
|---|---|---|
| `<NAME_PREFIX>-real-ops-pnl-reconciliation-mismatch` | U2 | `OK`; `pnl_real_reconciliation_mismatches_total` em 0 |
| `<NAME_PREFIX>-real-ops-own-holdings-unavailable` | U4 | `OK` (`notBreaching`); `real_own_holdings_reads_total` não sobe, porque a flag está desligada |
| `<NAME_PREFIX>-real-ops-own-holdings-stuck-bots` | U4 | `OK` |

Conferência à mão (caminho manual) ao fim da janela:

```bash
aws cloudwatch describe-alarms --alarm-name-prefix "<NAME_PREFIX>-real-ops-" --state-value ALARM \
  --query 'MetricAlarms[].AlarmName' --output text        # esperado: vazio
```

### 1.2 Duas versões lado a lado durante o rolling

A tabela do 261113 (seção 4) continua valendo. Para este intent:

| Mudança | Por que a convivência é segura |
|---|---|
| `0023` a `0025` (tabelas `real_entry_slot`, `real_risk_day_deferred`, `real_position_epoch`, índice do ledger) | a imagem anterior não lê nem escreve as tabelas novas (`Migration0023AdditiveIT`; comentário da `0025`) |
| `0026` (regra de `bot_cycle.outcome` mais larga) | a imagem anterior só grava valores da lista antiga, que a regra nova aceita (`Migration0026IT`) |
| `0027` (índice parcial) | a imagem anterior não usa o índice |
| P&L por modo (U1, U3) e P&L REAL por bot (U2) | só leitura; contrato da API só com acréscimos; o web antigo sobre a API nova funciona |
| Flag da U4 | `false` na implantação do código: a imagem nova se comporta como a anterior (U4-NFR1.3 verde) |

### 1.3 Teto de conexões no rolling (D-INF2)

Com `maximumPercent = 200`, duas tarefas `api-worker` convivem por alguns minutos. O teto teórico é de cerca de 127 conexões contra cerca de 112 da `db.t4g.micro`; o regime fica em no máximo 66. É pendência do responsável antes da primeira implantação na nuvem (`cd-config.md`, seção 9, item 6). Não existe alarme de conexões do RDS: o 261113 só tem o gráfico `AWS/RDS DatabaseConnections` no painel (`modules/observability/dashboards.tf`). Até a decisão, o fundador acompanha esse gráfico durante a troca, e um erro de conexão recusada aparece como 5xx na janela de observação.

## 2. Promoção e migrações (resumo; detalhe em `cd-config.md`)

- O mesmo SHA de `main`, com `ci` e `security` verdes e **um** run verde do `ci` (condição 3), vai primeiro para a devnet e só depois para a mainnet, com a aprovação do fundador [Q2].
- A mainnet fica bloqueada pelas condições 2, 4 e 5 (`cd-config.md`, seção 6).
- O `db-migrate` aplica `0023` → `0024` → `0025` → `0026` (dois changesets com `lock_timeout` de 5 s) → `0027` antes das imagens; falha para a implantação antes de qualquer troca; a repetição é com o mesmo SHA (`cd-config.md`, seção 3) [Q3].

## 3. Flags de funcionalidade [Q4]

### 3.1 Flags do REAL, kill switch e liberação: sem mudança

A tabela da seção 7 do `cd-config.md` do 261113 vale como está: `MEMETRADE_REAL_ENABLED`, `TXSIGNER_REAL_ENABLED`, `MEMETRADE_REAL_EXITS_ENABLED` (todas `"false"` em `infra/terraform/main.tf`), o kill switch REAL (`real_kill_switch`, rota `PUT`/`DELETE /api/v1/admin/real/kill-switch`) e a liberação por pessoa com quarentena de 24 h. Este intent não muda nenhuma delas. Ligar a flag da U4 não liga o REAL.

### 3.2 Flag da U4 `memetrade.real.own-quantity-sell` (F4)

Declaração versionada em `infra/terraform/main.tf` (já aplicada na Code Generation da U4, sem mudança neste estágio):

```hcl
variable "real_own_quantity_sell_api" {
  type    = bool
  default = false
}

variable "real_own_quantity_sell_worker" {
  type    = bool
  default = false

  validation {
    condition     = !var.real_own_quantity_sell_worker || var.real_own_quantity_sell_api
    error_message = "real_own_quantity_sell_worker needs real_own_quantity_sell_api: turn the api on first and the worker off first (U4 NFR2.8)."
  }
}
```

Os mapas levam cada variável a um contêiner só: `api_environment` recebe `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_api)` e `worker_environment` recebe `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_worker)`. A chave nunca entra em `app_environment`.

**Linhas exatas no `tfvars` de cada ambiente** (`infra/terraform/environments/devnet.tfvars` e `mainnet.tfvars`, fora do repositório; os `*.tfvars.example` já trazem as mesmas linhas):

| Estado | `real_own_quantity_sell_api` | `real_own_quantity_sell_worker` | Efeito |
|---|---|---|---|
| **Desligada (hoje, e até a condição 1)** | `real_own_quantity_sell_api    = false` | `real_own_quantity_sell_worker = false` | comportamento de antes da U4 |
| Passo intermediário (só API) | `real_own_quantity_sell_api    = true` | `real_own_quantity_sell_worker = false` | aviso em `/bots`; o ciclo ainda vende como antes |
| Ligada | `real_own_quantity_sell_api    = true` | `real_own_quantity_sell_worker = true` | o ciclo REAL vende só a quantidade própria de cada bot |
| **Proibida (recusada no `plan`)** | `real_own_quantity_sell_api    = false` | `real_own_quantity_sell_worker = true` | `Error: Invalid value for variable` com a mensagem da validação |

**Mantida `false` em todos os ambientes até a condição 1** (`phase-check-construction.md`, seção 7): medida por passo, decisão F7 e medida oficial (10.000 leituras × 3, Linux, com ciclos) dentro da meta, além das pré-condições da seção 11 do runbook (Performance Validation do 261113 aprovada; código da U4 em serviço; na mainnet, a prova do alarme da razão na devnet). Hoje há mais um bloqueio: as conferências SQL abaixo não têm caminho na nuvem (IR-G3, `cd-config.md`, seção 8).

### 3.3 Ligar: primeiro a API, depois o worker

Cada passo é uma implantação **só da flag** (mesmo SHA de imagem em serviço, sem código junto), primeiro na devnet e depois na mainnet. O fundador acompanha a janela de 10 minutos e os 30 seguintes.

| Passo | Ação | Verificação |
|---|---|---|
| 1 | Conferir a condição 1 e as pré-condições da seção 11 do runbook; na mainnet, a prova do alarme na devnet (1 hora com o worker ligado; `e1` com valor em todo período de 600 s). | todas registradas na nota da virada |
| 2 | Linha de base, só leitura: as duas contagens do passo 4 e os alarmes `real-ops-own-holdings-*` em `OK`. | anotadas |
| 3 | **Implantação da API.** No `tfvars` do ambiente: `real_own_quantity_sell_api    = true`. `terraform plan -var-file=environments/<env>.tfvars -out=<env>.plan`; `terraform apply <env>.plan` (MFA). Depois `gh workflow run deploy.yml --ref main -f sha=<SHA em serviço> -f environment=<env> -f mode=deploy`, ou o caminho manual (`cd-config.md`, 2.2, passos 4 e 6). | o `plan` mostra **uma** diferença: `MEMETRADE_REAL_OWN_QUANTITY_SELL` = `"true"` no contêiner `api`; smoke verde; janela de 10 min; o aviso aparece em `/bots`; o ciclo continua o de hoje |
| 4 | **Conferência só de leitura, logo antes do worker** (runbook, seção 11, passo 2): `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;` `SELECT now() AS t1;` `SELECT count(*) FROM real_decision_request WHERE side = 'SELL' AND state = 'PENDING';` `SELECT count(*) FROM real_order WHERE side = 'SELL' AND bot_id IS NOT NULL AND state IN ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS');` `COMMIT;` | as duas contagens dão 0; senão, esperar e repetir (uma `AMBIGUOUS` segue o runbook do 261113) |
| 5 | **Implantação do worker.** No `tfvars`: `real_own_quantity_sell_worker = true` (a API continua `true`). `plan`, `apply` com MFA e a mesma implantação do passo 3. | `plan` com uma diferença no contêiner `worker`; smoke; janela de 10 min |
| 6 | **Conferência depois da troca**, quando a última tarefa antiga saiu: as duas contagens de novo e `SELECT r.decision_request_id, r.bot_id, r.created_at FROM real_decision_request r JOIN decision d ON d.decision_id = r.decision_id WHERE r.side = 'SELL' AND r.created_at >= :t1 AND NOT jsonb_exists(d.context, 'ownQuantity');` | cada linha vai para a nota (venda como antes; excesso no rateio da U2); `real_own_holdings_reads_total` sobe; alarmes `OK`; `pnl_real_reconciliation_mismatches_total` em 0 |
| 7 | Primeira hora: razão de `STALE_SNAPSHOT` (runbook, seção 13) ≤ 1%. Na devnet, a prova do alarme para a mainnet. | registrada |

As consultas usam `jsonb_exists` e não o operador `?`, que um cliente JDBC trata como marcador.

### 3.4 Desligar: ordem inversa, primeiro o worker, depois a API

| Passo | Ação | Verificação |
|---|---|---|
| 0 | Se houver venda errada em andamento: kill switch REAL primeiro (segundos). O saque continua disponível. | estado "ligado" na administração |
| 1 | `real_own_quantity_sell_worker = false` no `tfvars`; `plan` (uma diferença no `worker`), `apply` com MFA; implantação só da flag. | `worker false` na revisão em serviço (comando da condição 1 em `cd-config.md`, seção 6); `real_own_holdings_reads_total` para de subir |
| 2 | `real_own_quantity_sell_api = false`; `plan`, `apply`, implantação. Desligar a API antes do worker é recusado pela validação. | `api false`; o aviso some de `/bots` |
| 3 | Os resultados gravados (`REAL_NO_OWN_QUANTITY`, `REAL_OWN_QUANTITY_UNAVAILABLE`, `context.ownQuantity`) ficam como histórico. | — |

Desligar um ambiente não muda o outro.

### 3.5 Divergência com o `team.md` (item para o ritual de aprendizados)

O `team.md` (Deployment, Flags) e a seção 7 do `cd-config.md` do 261113 dizem: "as flags do REAL são variáveis de ambiente das definições de tarefa, versionadas no Terraform; mudar uma flag é uma implantação auditada separada", com o passo 1 "commit em `main` mudando o mapa `environment`". A F4 do fundador (2026-11-24) decide outra coisa para a flag da U4: a declaração e o padrão `false` ficam versionados, mas **o valor ligado vive no `tfvars` fora do repositório**, sem commit; o registro da virada é o `terraform plan`, o `apply` com MFA (`RegisterTaskDefinition` no CloudTrail) e a nota da implantação. As duas regras concordam em "implantação auditada separada, nunca junto com código". Este estágio aplica a F4, que é a decisão mais recente e específica, e **não** edita o `team.md`. O texto do `team.md` só muda pelo ritual de aprendizados, se o fundador quiser (por exemplo: "flags por ambiente cujo valor ligado depende da conta ficam no `tfvars`, fora do repositório, como `founder_user_id`").

## 4. Portões específicos deste intent durante a implantação

1. **Flag da U4 `false`** em toda implantação de código (condição 1). Na guarda do `deploy.yml`, quando o responsável aplicar a linha proposta em `cd-config.md`, seção 7; no caminho manual, pelo comando da condição 1.
2. **Um run verde do `ci`** para o SHA, com toda repetição do `e2e` registrada (condição 3).
3. **Mainnet bloqueada** até as condições 2, 4 e 5 (`cd-config.md`, seção 6).
4. **Os modos nunca são somados** (`project.md`, Forbidden): nenhum passo de implantação ou de smoke lê ou mostra total geral PAPER + REAL.
5. **Saque sempre disponível** (`project.md`, Mandated): nenhum passo de implantação, virada ou rollback deste intent toca o saque, "Vender tudo e sacar" ou as condicionais.

## Sources

- `operation/deployment-pipeline/deployment-pipeline-questions.md` (Q1 a Q5)
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/deployment-strategy.md` (seções 1 a 8) e `cd-config.md` (4.6, 5, 7)
- `verification/phase-check-construction.md` (seção 7, condições 1 a 5; seção 8, decisão do fundador)
- `construction/u4-own-quantity-cycle/infrastructure-design/infrastructure-specification.md` (F4, variáveis, validação, mapas, saída `api_worker_container_environment`) e `cicd-pipeline.md` (virada, desligamento, caminho manual)
- `construction/u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md` (D-INF1, D-INF2, `0023`, `0024`), `u1-mode-skeleton/infrastructure-design/cicd-pipeline.md`, `u3-pnl-views/infrastructure-design/cicd-pipeline.md`
- `docs/runbooks/real-operations.md`, seções 10 a 13
- `infra/terraform/main.tf` (linhas 145 a 170 e mapas de ambiente), `environments/devnet.tfvars.example`, `environments/mainnet.tfvars.example`, `modules/services/api-worker.tf`
- `apps/api/src/main/resources/db/changelog/changes/0025-real-position-epoch.sql` (comentário: a imagem anterior não lê a tabela)
- `team.md` (Deployment), `project.md` (Forbidden, Mandated)
- `.claude/knowledge/aidlc-pipeline-deploy-agent/deployment-strategies.md`

## Assumptions & Open Questions

1. [assumption] Os nomes completos dos alarmes seguem o padrão `${var.name}-real-ops-...` de `real-ops.tf`; com `name = eureka-real`, o prefixo da janela (`<NAME_PREFIX>-`) já os cobre.
2. Aberta: a condição 1 depende da decisão F7 do fundador e de uma volta da U4 à Construction; até lá a flag não liga em nenhum ambiente.
3. Aberta: IR-G3 (leitura SQL do banco na nuvem). Sem ele, os passos 2, 4 e 6 da virada não rodam na nuvem, e a virada fica bloqueada mesmo depois da condição 1.
4. Aberta: D-INF2 (teto de conexões no rolling), decisão do responsável antes da primeira implantação na nuvem.
5. Divergência `team.md` × F4 (seção 3.5): registrada para o ritual de aprendizados; este estágio não edita a memória.
