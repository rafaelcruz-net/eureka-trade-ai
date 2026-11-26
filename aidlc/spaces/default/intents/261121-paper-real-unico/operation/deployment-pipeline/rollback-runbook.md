# Runbook de rollback — intent `261121-paper-real-unico` (devnet e mainnet)

Data: 2026-11-25. Conversa: português (Brasil). Decisão: `deployment-pipeline-questions.md` Q5 (opção A). Fluxo e nomes: `cd-config.md`. Estratégia e flags: `deployment-strategy.md`.

**Regra de ouro (sem mudança):** o saque para a carteira principal registrada nunca é desligado. Nenhum passo deste runbook bloqueia o saque, "Vender tudo e sacar" ou as condicionais (`project.md`, Mandated).

## 1. Base: o runbook do 261113, por referência

Vale **inteiro** o `rollback-runbook.md` do intent `261113-trading-real-bot` (`aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/rollback-runbook.md`):

| Parte do 261113 | Seção lá | Situação aqui |
|---|---|---|
| Quando usar; quem decide (o fundador, único on-call) | 1, 2 | sem mudança |
| Passo 1 (kill switch REAL primeiro), 2 (esperar o que foi enviado), 3 (achar o SHA anterior) | 3 | sem mudança |
| Passo 4: caminho A (`deploy.yml` `mode=rollback`), A2 (TxSigner pelo fundador com MFA), caminho C (AWS CLI sem GitHub) | 3 | sem mudança; enquanto o `deploy.yml` não existe, vale o caminho C ou o caminho manual de `cd-config.md`, 2.2 |
| Passo 5 (nunca reverter migração), 6 (smoke), 7 (saque e reconciliação), 8 (religar o REAL), 9 (revisão pós-incidente) | 3 | sem mudança; este intent soma as migrações `0023` a `0027` à regra do passo 5 |
| Caminho B (desligar pelas flags do REAL) | 4 | sem mudança; o mesmo padrão serve à flag da U4 (passo R2 abaixo) |
| Reversibilidade, casos especiais, comandos, ensaio na devnet | 5 a 8 | sem mudança; o ensaio ganha 4 linhas (seção 5 abaixo) |
| Escalonamento e contatos | 9 | sem mudança (seção 6 abaixo) |

**O que este intent acrescenta:** a flag da U4 desligada na ordem worker → API antes ou junto da volta da imagem; as migrações `0023` a `0027` mantidas; as telas de P&L por modo voltando só pela imagem; a conferência dos alarmes novos; e a regra de que o rollback só termina com o smoke verde. As adições estão na seção 3, encaixadas nos passos do 261113.

## 2. Quando usar, neste intent

| Sinal | Origem | Primeiro passo |
|---|---|---|
| `deploy.yml` (ou o caminho manual) vermelho **depois** de trocar `api-worker` ou web: smoke, 5xx, alarme na janela | este intent ou qualquer outro | seção 3, R0 |
| `real_ops_pnl_reconciliation_mismatch` em `ALARM` logo depois da implantação | U2 | runbook `real-operations.md`, seção 8; rollback da imagem só se a divergência for do cálculo novo (reconciliação da rede `CLEAN`) |
| `real_ops_own_holdings_unavailable` ou `-stuck-bots` em `ALARM` (só com a flag ligada) | U4 | desligar a flag (R2); a imagem fica |
| Telas de P&L por modo com erro ou mostrando total geral PAPER + REAL | U1, U3 | rollback da imagem (R4); total geral é P1 (`project.md`, Forbidden) |
| `db-migrate` com saída diferente de zero | U2, U4 | **não é rollback**: nada foi trocado. Seção 4 |
| Circuit breaker devolveu a revisão sozinho | ECS | a versão antiga está em serviço; só R6 e R7, depois investigar |

## 3. Passo a passo

Anote o horário de cada passo em UTC na nota do incidente. É a linha do tempo da revisão pós-incidente.

### R0 — Classificar e decidir

- **Ação:** o fundador classifica (P1, P2 ou P3, tabela da seção 6) e decide o rollback.
- **Verificação:** nota aberta com SHA em serviço, ambiente, sinal e horário.
- **Escalonamento:** o fundador é o único on-call e o único que decide; nenhum automatismo volta a imagem depois que a revisão nova ficou saudável.

### R1 — REAL desligado primeiro (passo 1 do 261113)

- **Ação:** se o REAL estiver ligado no ambiente, ligar o kill switch REAL na administração (tela "Kill switch REAL", motivo `rollback <sha>`; rota `PUT /api/v1/admin/real/kill-switch` com `Idempotency-Key`). Se a administração não responder, caminho B do 261113 (flags do REAL `false` por Terraform).
- **Verificação:** a tela mostra "ligado"; uma ordem REAL nova é recusada; o saque continua disponível.
- **Hoje:** o REAL está desligado em todo ambiente (`MEMETRADE_REAL_ENABLED = "false"`), então R1 é só a conferência: `aws ecs describe-task-definition --task-definition <revisão em serviço> --query "taskDefinition.containerDefinitions[?name=='api'].environment[?name=='MEMETRADE_REAL_ENABLED'].value"` → `"false"`.
- **Escalonamento:** falha do kill switch = P1.

### R2 — Flag da U4 desligada, primeiro o worker, depois a API (adição deste intent)

Só quando a flag estiver ligada no ambiente. Hoje ela é `false` em todo lugar (condição 1); então R2 é só a conferência do comando da condição 1 (`cd-config.md`, seção 6).

**R2a — worker.**
- **Ação:** no `tfvars` do ambiente, `real_own_quantity_sell_worker = false`; `terraform plan -var-file=environments/<env>.tfvars -out=<env>.plan` (uma diferença: `MEMETRADE_REAL_OWN_QUANTITY_SELL` = `"false"` no contêiner `worker`); `terraform apply <env>.plan` com MFA. Depois, uma das duas:
  - só desligar o comportamento (a imagem fica): `gh workflow run deploy.yml --ref main -f sha=<SHA em serviço> -f environment=<env> -f mode=deploy`, ou o caminho manual (`cd-config.md`, 2.2, passos 4 e 6);
  - desligar **junto** com a volta da imagem (como o caminho B do 261113): ir direto para R4; o modo rollback parte da revisão mais recente, que agora tem o worker `false`.
- **Verificação:** a revisão `PRIMARY` do `api-worker` tem `worker false`; `real_own_holdings_reads_total` para de subir no ciclo seguinte; os alarmes `real-ops-own-holdings-*` voltam a `OK` em até 20 minutos.
- **Escalonamento:** `plan` com outra diferença além dessa = parar e revisar (P2).

**R2b — API.**
- **Ação:** depois de R2a em serviço, `real_own_quantity_sell_api = false`; `plan` (uma diferença no contêiner `api`), `apply` com MFA e uma implantação só da flag (como em R2a). A ordem inversa (API antes do worker) é recusada pela validação do Terraform.
- **Verificação:** `api false` na revisão em serviço; o aviso some de `/bots`.
- **Nota:** os resultados gravados (`REAL_NO_OWN_QUANTITY`, `REAL_OWN_QUANTITY_UNAVAILABLE`, `context.ownQuantity`) ficam como histórico.

### R3 — Esperar o que já foi enviado e achar o SHA anterior (passos 2 e 3 do 261113)

- **Ação:** esperar as ordens REAL `SENT`/`AMBIGUOUS` (até 15 min) e as condicionais não terminais; achar o SHA anterior: `gh run list --workflow deploy.yml --branch main --status success --limit 10 --json displayTitle,databaseId,createdAt`, ou a nota da implantação anterior (caminho manual), ou `aws ecs describe-task-definition --task-definition <ARN_ANTERIOR> --query 'taskDefinition.containerDefinitions[].[name,image]' --output table`.
- **Verificação:** as imagens do SHA anterior existem no ECR: `aws ecr describe-images --repository-name <NAME_PREFIX>/api --image-ids imageTag=<SHA_ANTERIOR>`.
- **TxSigner:** este intent não muda o código do TxSigner. Conferir: `git diff --quiet <SHA_ANTERIOR> <SHA> -- apps/txsigner && echo "TxSigner igual: pular A2"`. Se igual, o passo A2 do 261113 não é necessário (a revisão do sidecar do `txsigner` pode ficar em serviço).

### R4 — Voltar a imagem, migrações mantidas (passo 4 do 261113 + adições)

- **Ação (caminho A):** `gh workflow run deploy.yml --ref main -f sha=<SHA_ANTERIOR> -f environment=<env> -f mode=rollback -f real_off_confirmed=true`; acompanhar com `gh run watch <id>`; na mainnet, aprovar o Environment.
- **Ação (hoje, sem `deploy.yml`):** caminho C do 261113 ou o caminho manual (`cd-config.md`, 2.2, passos 4, 6 e 7) com `SHA=<SHA_ANTERIOR>`: revisão nova = a mais recente registrada pelo Terraform (flags atuais, inclusive o worker `false` de R2a) com a imagem do SHA anterior; ordem `admin` e `web` → `api-worker`; **sem** `db-migrate`.
- **Verificação:** cada serviço com o ARN novo, `COMPLETED`, 1/1; o digest das tarefas é o do SHA anterior.

  ```bash
  aws ecs describe-services --cluster <NAME_PREFIX> --services admin web api-worker --output json \
    --query 'services[].[serviceName, deployments[?status==`PRIMARY`] | [0].[taskDefinition, rolloutState, runningCount, desiredCount]]'
  ```
- **Migrações mantidas (adição):** `0023` a `0027` **ficam**. Nenhum `--rollback` do Liquibase roda na devnet nem na mainnet; nenhuma restauração por PITR desfaz uma implantação. Verificação: nenhuma tarefa `db-migrate` iniciada pelo rollback (`aws ecs list-tasks --cluster <NAME_PREFIX> --family <NAME_PREFIX>-db-migrate --desired-status STOPPED` sem tarefa com horário depois de R0, e nenhuma em `RUNNING`). Por que é seguro: as imagens anteriores não leem as tabelas da `0023` a `0025`, gravam só valores aceitos pela regra larga da `0026` e não usam os índices (`Migration0023AdditiveIT`, `Migration0026IT`).
- **Flag da U4 e imagem anterior (adição):** uma imagem anterior à U4 ignora `MEMETRADE_REAL_OWN_QUANTITY_SELL`, mesmo com `"true"`. Antes de voltar, depois, para uma imagem da U4, deixar o worker `false` (R2a) e refazer a virada desde o passo 1 (`deployment-strategy.md`, 3.3).
- **Escalonamento:** circuit breaker na volta (ARN diferente na `PRIMARY`) = parar, P2, investigar pelos eventos do serviço (`aws ecs describe-services --cluster <NAME_PREFIX> --services <svc> --query 'services[0].events[:10].[createdAt,message]'`).

### R5 — Telas de P&L por modo: só pela imagem (adição deste intent)

- **Ação:** nenhuma ação própria. As telas de P&L por modo (U1), o P&L REAL por bot (U2) e as visões de P&L (U3) **não têm flag**: elas voltam com a imagem de `api-worker` e de `web` (R4). Não há variável a desfazer: as três variáveis da U1 (`MEMETRADE_PNL_BYMODE_*`, `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT`) podem ficar na revisão, porque uma imagem anterior as ignora.
- **Verificação:** o fundador entra no web da devnet (ou da mainnet) com a carteira dele e confere que as telas de Bots e de P&L respondem com a versão anterior; em nenhuma tela, rota ou relatório aparece total geral PAPER + REAL; `real_ops_pnl_reconciliation_mismatch` em `OK`.
- **Escalonamento:** total geral somando os modos = P1 (`project.md`, Forbidden); voltar mais um SHA.

### R6 — Smoke (passo 6 do 261113)

- **Ação (caminho A):** o modo rollback já roda o smoke e a janela. **Caminho manual ou C:**

  ```bash
  curl -fsS "<API_BASE_URL>/api/v1/legal/current" | jq -e '(.version | length) > 0 and .publishable == true'
  aws elbv2 describe-target-health --target-group-arn <API_TARGET_GROUP_ARN> \
    --query 'TargetHealthDescriptions[].TargetHealth.State'                  # todos "healthy"
  curl -fsS -o /dev/null "<WEB_BASE_URL>/api/health" && curl -fsS -o /dev/null "<ADMIN_BASE_URL>/api/health"
  curl -fsS -o /dev/null "<WEB_BASE_URL>/privacidade"
  aws cloudwatch describe-alarms --alarm-name-prefix "<NAME_PREFIX>-" --state-value ALARM \
    --query 'MetricAlarms[].AlarmName'                                         # vazio
  ```

  Depois, a janela de 10 minutos e os 30 minutos seguintes com o fundador presente.
- **Verificação:** todos os comandos verdes; nenhum alarme `<NAME_PREFIX>-*` (inclusive `real-ops-pnl-reconciliation-mismatch` e `real-ops-own-holdings-*`) em `ALARM`; 5xx ≤ 1%.
- **Regra:** **o rollback só termina com o smoke verde** (`team.md`). Smoke vermelho depois do rollback = P2; voltar mais um SHA ou investigar.

### R7 — Saque e reconciliação (passo 7 do 261113)

- **Ação:** conferir que os alertas `WithdrawalStuck` e `WithdrawalAmbiguousTooLong` estão em silêncio; na devnet, um saque de prova da carteira do bot de teste para a carteira principal de teste; na mainnet, só por decisão explícita do fundador (fundos reais, `project.md`, Forbidden); esperar o próximo ciclo de reconciliação (até 15 min).
- **Verificação:** saque `CONFIRMED`; "Divergências REAL" vazia; última reconciliação de cada carteira `CLEAN`.
- **Escalonamento:** saque recusado ou parado = P1; divergência = `real-operations.md`, seção 2, e o REAL não religa.

### R8 — Religar (passo 8 do 261113)

- **Ação:** só com smoke verde, nenhum alarme em `ALARM`, reconciliação `CLEAN` e causa entendida: desligar o kill switch REAL (`DELETE /api/v1/admin/real/kill-switch`) se R1 o ligou. A flag da U4 **não** volta aqui: religá-la é a virada inteira (`deployment-strategy.md`, 3.3), de novo sob a condição 1.
- **Verificação:** ordens REAL voltam a ser aceitas (se o REAL estava ligado).

### R9 — Revisão pós-incidente (passo 9 do 261113)

Obrigatória para P1 e P2: linha do tempo em UTC, causa raiz e o que impede a repetição. Todo defeito corrigido ganha um teste de regressão (`team.md`). A versão corrigida volta pelo caminho normal: CI verde (um run verde, condição 3), devnet e mainnet.

## 4. Falha de migração durante a implantação (não é rollback)

Quando o `db-migrate` falha, a troca de imagens não começou: a versão anterior continua no ar. Não há imagem a voltar.

| Passo | Ação | Verificação | Escalonamento |
|---|---|---|---|
| 1 | Ler o log: `aws logs filter-log-events --log-group-name /ecs/<NAME_PREFIX>/migrate --start-time <epoch ms do início> --filter-pattern '?55P03 ?INVALID ?changeset'` | changeset e causa identificados | — |
| 2 | `55P03` na `0026` (`lock_timeout` de 5 s): esperar de 1 a 5 minutos; **nunca** matar a transação do ciclo ou da fila. Índice `INVALID` na `0023` ou na `0027`: nada a fazer à mão; o `DROP INDEX CONCURRENTLY IF EXISTS` da repetição apaga o resto. | — | — |
| 3 | Repetir a mesma implantação com o **mesmo SHA** (`gh workflow run deploy.yml --ref main -f sha=<SHA> -f environment=<env> -f mode=deploy`, ou o passo 5 do caminho manual). Se o changeset 1 da `0026` entrou e só o `VALIDATE` falhou, a repetição roda só o `VALIDATE`. | `db-migrate` com saída 0; depois, o resto da implantação | — |
| 4 | Três repetições com a mesma falha: parar. | nota com os três logs | P2, fundador |
| 5 | Trava do changelog presa (`databasechangeloglock`), ou necessidade de ver `pg_stat_activity` / `to_regclass` (`real-operations.md`, seções 9 e 12): **sem caminho na nuvem hoje** (IR-G3). A implantação fica parada até existir a família de leitura e manutenção do fundador. Nunca aplicar migração à mão fora do Liquibase. | — | P2; pendência do Environment Provisioning |

## 5. Ensaio na devnet: linhas acrescentadas ao ensaio do 261113

O ensaio da seção 8 do 261113 continua obrigatório antes da primeira mainnet. Com este intent, somam-se:

| # | Ação | Esperado | Evidência |
|---|---|---|---|
| 15 | Implantar na devnet um SHA anterior a este intent (`N-1`, na `0022`) e depois o SHA deste intent (`N`) | `db-migrate` aplica `0023` a `0027` com saída 0; smoke verde; flag da U4 `false` | nota com a saída do `db-migrate` |
| 16 | Rollback de `N` para `N-1` (R4), sem `db-migrate` | imagens `N-1` sobre o esquema `0027`; smoke verde; nenhum alarme | nota com o tempo de R0 a R6 |
| 17 | Só depois da condição 1 e do IR-G3: virada da flag na devnet (API, depois worker) e desligamento (worker, depois API) | cada `plan` com uma diferença; validação recusa o worker sem a API (tentar uma vez de propósito no `plan`) | saída dos `plan`, nota da virada |
| 18 | Só depois da linha 17: com a flag ligada, R2a junto com R4 (worker `false` + imagem anterior numa execução) | a revisão em serviço tem `worker false` e a imagem `N-1` | nota |

Resultado num documento em `docs/proofs/`, citado na aprovação da mainnet.

## 6. Escalonamento e contatos

O fundador é o único on-call (`team.md`). A tabela de severidade e os contatos são os da seção 9 do runbook do 261113, com estas linhas a mais:

| Severidade | Exemplos deste intent | Primeira ação | Tempo para agir |
|---|---|---|---|
| P1 | total geral PAPER + REAL em tela, API ou relatório; saque afetado durante o rollback; venda pela quantidade de outro bot com a flag ligada | kill switch REAL (se ligado); R2 e R4 | imediato |
| P2 | smoke vermelho depois da troca ou do rollback; `real_ops_pnl_reconciliation_mismatch` por defeito do cálculo; `real_ops_own_holdings_unavailable` sistêmico; `db-migrate` falhando três vezes | este runbook | assim que visto |
| P3 | `STALE_SNAPSHOT` acima de 1% (`real-operations.md`, seção 13); custo acima do esperado | investigar no horário normal | no mesmo dia |

| Papel | Quem | Contato |
|---|---|---|
| On-call único, decisor do rollback, do kill switch e da flag da U4 | fundador (responsável do projeto) | a definir (261113, seção 9) |
| Suporte AWS (ECS, RDS) | plano de suporte da conta | a definir |
| GitHub (Actions fora do ar) | status público do GitHub | caminho C do 261113 |

Sem segundo on-call, um P1 fora do horário do fundador é contido pelo próprio sistema: o REAL e a flag da U4 estão desligados por padrão, os alarmes ficam ativos e o saque continua disponível. A revisão pós-incidente de todo P1 e P2 é obrigatória (regra da fase Operation).

## Sources

- `operation/deployment-pipeline/deployment-pipeline-questions.md` (Q5) e os documentos irmãos `cd-config.md` e `deployment-strategy.md`
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/rollback-runbook.md` (seções 1 a 9) e `cd-config.md` (4.4, 4.6, 7)
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/runbooks.md` (IR-G3)
- `construction/u1-mode-skeleton/infrastructure-design/cicd-pipeline.md` (Rollback), `u2-real-bot-pnl/infrastructure-design/cicd-pipeline.md` e `infrastructure-specification.md` (Rollback, `0023`, `0024`), `u3-pnl-views/infrastructure-design/cicd-pipeline.md` (Rollback), `u4-own-quantity-cycle/infrastructure-design/cicd-pipeline.md` ("Desligar a flag", "Rollback da imagem", "Falha da migração")
- `docs/runbooks/real-operations.md`, seções 7 a 13
- `apps/api/src/main/resources/db/changelog/changes/0023` a `0027`
- `infra/terraform/main.tf` (validação da flag), `environments/*.tfvars.example`
- `verification/phase-check-construction.md` (condições 1 a 5)
- `team.md` (Deployment), `project.md` (Forbidden, Mandated), regra da fase Operation (Incident Response)

## Assumptions & Open Questions

1. [assumption] Enquanto o `deploy.yml` não existe, o rollback usa o caminho C do 261113 ou o caminho manual de `cd-config.md`, 2.2; os dois exigem a sessão de administração do fundador com MFA.
2. [assumption] A conferência das telas de P&L depois do rollback (R5) é manual, pelo fundador no navegador, porque o smoke do 261113 é sem autenticação.
3. Aberta: IR-G3. Sem leitura SQL na nuvem, a recuperação de migração fica só na repetição (seção 4) e a virada da flag não pode ser conferida.
4. Aberta: contatos de escalonamento (fundador, suporte AWS) ainda "a definir", como no 261113.
