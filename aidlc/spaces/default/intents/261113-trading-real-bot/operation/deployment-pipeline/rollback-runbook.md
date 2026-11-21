# Runbook de rollback — devnet e mainnet (intent `261113-trading-real-bot`)

Data: 2026-11-20. Conversa: português (Brasil). Decisão: `deployment-pipeline-questions.md` Q4 (opção A). Workflow e nomes: `cd-config.md`. Estratégia e critérios de aborto: `deployment-strategy.md`.

**Regra de ouro:** o saque para a carteira principal registrada nunca é desligado. Nenhum passo deste runbook bloqueia o saque (`project.md` Mandated).

> **Correção da revisão de segurança (2026-11-20, SP-01).** O `deploy.yml` não troca mais o TxSigner: o papel de implantação não pode passar o papel do TxSigner, rodar as famílias dele nem atualizar o serviço `txsigner`. No rollback, o workflow volta `admin`, `web` e `api-worker`, e **o fundador volta o TxSigner com MFA** (passo 4, A2), com o mesmo procedimento da implantação (`cd-config.md` 4.6), sem a migração. Os passos 4, 6 e 8, o caminho B e o ensaio da seção 8 foram ajustados.

## 1. Quando usar

- O job `deploy` do `deploy.yml` ficou vermelho **depois** de trocar algum serviço: smoke, prontidão do TxSigner, taxa de 5xx ou alarme na janela de observação.
- Um problema apareceu depois de uma implantação verde e foi ligado à versão nova: alarmes do REAL, divergência na reconciliação, recusas da política do TxSigner, erros na tela.
- O circuit breaker devolveu a revisão anterior sozinho. Nesse caso a versão antiga continua em serviço: não há rollback a fazer. Faça só os passos 6 e 7 para confirmar e investigue.

## 2. Quem decide

O **fundador** (responsável do projeto) é o único on-call e o único que decide um rollback, liga ou desliga o kill switch e religa o REAL (`team.md`: revisor único dos gates do REAL). Nenhum automatismo faz rollback de imagem depois que uma revisão nova ficou saudável (`deployment-strategy.md`, seção 5).

## 3. Passo a passo

Anote o horário de cada passo em UTC. É a linha do tempo da revisão pós-incidente.

### Passo 1 — Desligar o REAL primeiro (kill switch)

**Caminho normal: kill switch REAL.**
1. Entrar na administração com a carteira do fundador.
2. Abrir "Kill switch REAL" e ligar, com um motivo (por exemplo, `rollback <sha>`).
   - Rota: `PUT /api/v1/admin/real/kill-switch`, com `Idempotency-Key` e `{ "reason": "..." }`.
3. Conferir na mesma tela que o estado é "ligado".

Efeito imediato: nenhuma ordem REAL nova é aceita; as ordens não enviadas são canceladas (`KILL_SWITCH`) agora e a cada minuto; as condicionais não terminais são canceladas; as ordens já enviadas seguem até o fim; **o saque continua** (`docs/runbooks/real-operations.md`, seção 3).

**Se a administração ou a API não responde:** use o caminho B (seção 4). Ele desliga as flags e volta as imagens numa só execução.

Reversível: sim (`DELETE /api/v1/admin/real/kill-switch`, passo 8).

### Passo 2 — Esperar o que já foi enviado

| O que | Limite | Como conferir | Se passar do limite |
|---|---|---|---|
| Ordens REAL `SENT` e `AMBIGUOUS` chegarem a estado final (U4) | 15 min | tela "Ordens" de cada pessoa ou alerta `RealExecutionAmbiguousOld`; métrica `real_orders` por estado quando o caminho das métricas existir (a definir no `observability-setup`) | seguir mesmo assim: a ordem ambígua continua reservada, as tabelas ficam, e a conferência retoma com a imagem que entrar (`docs/runbooks/real-execution.md`, seção 4) |
| Condicionais `OPEN`, `CANCELLING` e `PENDING_SIGNATURE` chegarem a zero (U7) | 5 min com a rede saudável; parar aos 15 min | aba "Condicionais"; métrica `real_conditionals` | **parar o rollback** e seguir `docs/runbooks/real-exits.md`, seção 3. Hoje as saídas estão desligadas em toda a nuvem, então esse caso não deve acontecer |

Durante a espera, silencie os alertas de trabalho parado, como manda o `docs/runbooks/real-risk.md`.

Reversível: não se aplica (é só espera).

### Passo 3 — Achar o SHA anterior

- No resumo do último run verde do `deploy.yml` deste ambiente, seção "Revisions before this run", e no título do run (`deploy <env> <sha> (deploy)`):

  ```bash
  gh run list --workflow deploy.yml --branch main --status success --limit 10 --json displayTitle,databaseId,createdAt
  ```
- Ou direto na AWS: a imagem da revisão em serviço antes da troca.

  ```bash
  aws ecs describe-task-definition --task-definition <ARN_ANTERIOR> \
    --query 'taskDefinition.containerDefinitions[].[name,image]' --output table
  ```

O SHA anterior precisa ter imagens no ECR. A retenção guarda as 30 últimas.

Reversível: não se aplica (só leitura).

### Passo 4 — Voltar as imagens, na ordem inversa

**Caminho A (preferido): `deploy.yml` em modo rollback.**

```bash
gh workflow run deploy.yml --ref main \
  -f sha=<SHA_ANTERIOR> -f environment=<devnet|mainnet> -f mode=rollback -f real_off_confirmed=true
gh run watch "$(gh run list --workflow deploy.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Na `mainnet`, aprove o Environment quando o GitHub pedir. O workflow:
- confere que o SHA está em `main` e tem `ci` e `security` verdes;
- não roda migração;
- troca só as imagens, partindo da revisão mais recente de cada família. Assim as flags atuais ficam, inclusive um REAL desligado no caminho B;
- troca na ordem `admin` e `web` → `api-worker`, cada uma até `COMPLETED`. **Não troca o TxSigner** e escreve no resumo se o TxSigner em serviço é de outro SHA;
- roda o smoke (com o TxSigner só conferido como `HEALTHY`) e a janela de observação.

**A2 — TxSigner, pelo fundador com MFA (logo depois de A, se o TxSigner mudou entre os dois SHAs).** O papel de implantação não consegue fazer isso. Rode, da máquina do fundador e com o perfil de administração com MFA, os passos 0, 1, 2, 4 e 5 do `cd-config.md` 4.6 com `SHA=<SHA_ANTERIOR>`. **Pule o passo 3 (migração):** migração nunca volta (passo 5). Assim:
- a imagem anterior já está no ECR (passo 1 só confere o digest);
- a revisão nova parte da revisão do Terraform (`founder_task_definition_arns`), com as flags atuais, inclusive o `TXSIGNER_REAL_ENABLED=false` do caminho B, e troca só a imagem;
- o passo 4 confere `COMPLETED` e 1/1, e o passo 5 roda o probe de prontidão (`${name}-txsigner-probe`).

Os alarmes `<NAME_PREFIX>-security-*` mandam um e-mail para cada revisão, execução e troca. São os seus próprios atos: anote o horário na linha do tempo.

**Caminho C (sem GitHub): AWS CLI com o papel de administração e MFA do fundador.** Use quando o GitHub estiver fora do ar. O papel de implantação não serve aqui, porque só é assumido de dentro do Environment. A sessão precisa de MFA: a SCP recusa, sem MFA, passar o papel do TxSigner e os papéis das famílias dele. O `update-service` do `txsigner` dispara o alarme `<NAME_PREFIX>-security-txsigner-service-updated`, o que é esperado.

```bash
export AWS_PROFILE=<perfil de administração com MFA>   # a definir
CLUSTER=<NAME_PREFIX>                                   # por exemplo eureka-real

# 1. Revisões em serviço agora
aws ecs describe-services --cluster "$CLUSTER" --services admin web api-worker txsigner \
  --query 'services[].[serviceName,taskDefinition]' --output table

# 2. Antes de usar uma revisão antiga, conferir as flags dela: ela pode ter o REAL ligado
aws ecs describe-task-definition --task-definition <ARN_ANTERIOR_API_WORKER> \
  --query 'taskDefinition.containerDefinitions[].environment[?starts_with(name, `MEMETRADE_REAL`)]'
aws ecs describe-task-definition --task-definition <ARN_ANTERIOR_TXSIGNER> \
  --query 'taskDefinition.containerDefinitions[].environment[?starts_with(name, `TXSIGNER_`)]'

# 3. web e admin (se existirem), depois api-worker, depois txsigner; cada um até estável
for pair in "admin <ARN_ANTERIOR_ADMIN>" "web <ARN_ANTERIOR_WEB>" \
            "api-worker <ARN_ANTERIOR_API_WORKER>" "txsigner <ARN_ANTERIOR_TXSIGNER>"; do
  set -- $pair
  aws ecs update-service --cluster "$CLUSTER" --service "$1" --task-definition "$2" > /dev/null
  aws ecs wait services-stable --cluster "$CLUSTER" --services "$1"
  aws ecs describe-services --cluster "$CLUSTER" --services "$1" \
    --query 'services[0].deployments[?status==`PRIMARY`].[taskDefinition,rolloutState,runningCount,desiredCount]' --output table
done
```

A linha da revisão `PRIMARY` precisa mostrar o ARN anterior, `COMPLETED` e 1/1. Se o ARN for outro, o circuit breaker devolveu a revisão: pare e investigue.

Se alguma revisão anterior tiver o REAL ligado e o REAL deve ficar desligado, **não** a use pelo caminho C. Use o caminho B.

Reversível: sim. Implante de novo o SHA que estava em serviço (`mode=deploy`) ou volte a revisão pelo mesmo comando.

### Passo 5 — Nunca reverter migração

As migrações são só de expansão (`deployment-strategy.md`, seção 3). As imagens anteriores funcionam sobre o esquema novo, então **o banco fica como está**. Não rode os blocos de rollback do Liquibase em devnet nem em mainnet, e não restaure o banco por PITR para desfazer uma implantação [Q4 B recusada].

Por que não é reversível: desfazer uma migração depois de a versão nova ter gravado dados perderia ou corromperia lançamentos do ledger (append-only), saques, auditoria e redações da anonimização. Há casos que nem o desenvolvimento consegue voltar: a 0021 com saque redigido e a 0022 com condicional gravada. A correção de um erro de esquema é sempre uma migração nova para frente.

### Passo 6 — Smoke

O modo rollback do workflow já roda o smoke. No caminho C, rode à mão:

```bash
curl -fsS "<API_BASE_URL>/api/v1/legal/current" | jq -e '(.version | length) > 0 and .publishable == true'
aws elbv2 describe-target-health --target-group-arn <API_TARGET_GROUP_ARN> \
  --query 'TargetHealthDescriptions[].TargetHealth.State'
curl -fsS -o /dev/null "<WEB_BASE_URL>/api/health" && curl -fsS -o /dev/null "<ADMIN_BASE_URL>/api/health"
aws cloudwatch describe-alarms --alarm-name-prefix "<NAME_PREFIX>-" --state-value ALARM --query 'MetricAlarms[].AlarmName'
```

Prontidão do TxSigner (caminhos A2 e C): só o fundador, com MFA, roda a família `<NAME_PREFIX>-txsigner-probe`, agora declarada no Terraform (sem papel de tarefa e com papel de execução sem segredo). Use a revisão do Terraform, nunca a mais recente da família, e passe o IP privado da tarefa do TxSigner em `TXSIGNER_PROBE_IP`. Ela termina com código 0 quando a readiness responde 200 (até 30 tentativas de 10 s).

```bash
PROBE=$(cd infra/terraform && terraform output -json founder_task_definition_arns | jq -r '."txsigner-probe"')
aws ecs run-task --cluster "$CLUSTER" --task-definition "$PROBE" --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[<SUBNET_PRIVATE_A>],securityGroups=[<TXSIGNER_SECURITY_GROUP>],assignPublicIp=DISABLED}" \
  --overrides '{"containerOverrides":[{"name":"probe","environment":[{"name":"TXSIGNER_PROBE_IP","value":"<IP_DO_TXSIGNER>"}]}]}'
```

A implantação, e o rollback com ela, só termina com o smoke verde (`team.md`).

Reversível: não se aplica (só leitura).

### Passo 7 — Saque e reconciliação

1. **Saques pendentes.** Os alertas `WithdrawalStuck` e `WithdrawalAmbiguousTooLong` estão em silêncio, e todo saque pedido durante o incidente chegou a `CONFIRMED` ou está avançando.
2. **Um saque de prova.**
   - Na devnet: saque pequeno da carteira do bot de teste para a carteira principal de teste.
   - Na mainnet: só se o fundador decidir, da carteira do bot dele para a carteira principal dele. São fundos reais, então exige a decisão explícita dele (`project.md` Forbidden).
3. **Reconciliação.** Esperar o próximo ciclo (no máximo 15 min, `reconciliation-interval` PT15M). Na administração, "Divergências REAL" precisa estar vazia, e a última reconciliação de cada carteira precisa ser `CLEAN`. Nenhuma pausa `DIVERGENCE` aberta.

Se aparecer divergência, **não religue o REAL**. Siga `docs/runbooks/real-operations.md`, seção 2.

Reversível: não se aplica (conferência). O saque de prova na mainnet move fundos reais da carteira do bot para a carteira principal do próprio fundador e não se desfaz, por isso é opcional e decidido por ele.

### Passo 8 — Religar o REAL, só com a aprovação do fundador

Só quando tudo abaixo estiver verdadeiro:
- smoke verde;
- nenhum alarme `<NAME_PREFIX>-*` em ALARM;
- reconciliação `CLEAN` em todas as carteiras;
- a causa entendida, ou contida pela versão anterior.

Na administração, desligar o kill switch REAL com motivo (`DELETE /api/v1/admin/real/kill-switch`, com `Idempotency-Key`). Nenhum bot é religado sozinho, e cada pessoa retoma o dela. Se o REAL foi desligado pelas flags (caminho B), religar é uma implantação auditada (`cd-config.md`, seção 7).

Reversível: sim (ligar de novo o kill switch).

### Passo 9 — Revisão pós-incidente

Obrigatória para P1 e P2 (seção 9). Registrar:
- a linha do tempo em UTC;
- a causa raiz;
- o que impede a repetição.

Todo defeito corrigido ganha um teste de regressão que o reproduz (`team.md`). A versão corrigida volta pelo caminho normal: CI verde, devnet e mainnet.

## 4. Caminho B — desligar pelas flags (a administração não responde)

1. Commit em `main` com `MEMETRADE_REAL_ENABLED = "false"` nos contêineres `api` e `worker` e `TXSIGNER_REAL_ENABLED = "false"` no `txsigner`, nos mapas `environment` do Terraform. Com a API fora do ar, o commit não espera a CI. O `deploy.yml` não exige CI verde do commit de flags, porque ele implanta o **SHA anterior** das imagens.
2. `terraform plan` e `terraform apply` pelo fundador, com MFA. O apply registra revisões novas com as flags desligadas e não as põe em serviço.
3. Passo 2 (espera) e passo 4, caminho A, com `real_off_confirmed=true`. O modo rollback parte da revisão mais recente: **flags desligadas + imagens anteriores, numa só execução** para `api-worker`, `web` e `admin`.
4. Passo 4, A2, pelo fundador com MFA: o TxSigner com `TXSIGNER_REAL_ENABLED=false` (a revisão do Terraform que o apply acabou de registrar) e a imagem anterior, ou a imagem atual se o TxSigner não mudou. Mesmo com o TxSigner desligado, o saque continua.
5. Passos 5 a 9. Para religar, outra implantação auditada com as flags em `true` (`cd-config.md` 7 e 4.6).

Também use o caminho B quando a revisão anterior exigir variáveis diferentes das atuais. Por exemplo, voltar para uma imagem anterior à U2 exige `MARKET_SOURCES` voltando junto (`infrastructure-specification.md` da U2). Ajuste o Terraform primeiro e depois rode o modo rollback.

## 5. Reversibilidade de cada passo

| Passo | Reversível | Como, ou por que não |
|---|---|---|
| 1. Kill switch ligado | sim | `DELETE /api/v1/admin/real/kill-switch` |
| 1. Flags desligadas (caminho B) | sim | implantação auditada com as flags em `true` |
| 2. Espera | — | não muda nada |
| 3. Achar o SHA | — | só leitura |
| 4. Imagens anteriores | sim | `deploy.yml` com o SHA que estava em serviço; o TxSigner, pelo passo 4.6 do `cd-config.md` com o mesmo SHA |
| 5. Migração mantida | — | nada é desfeito; reverter **não** é reversível com segurança (ledger append-only, redações), por isso é proibido |
| 6. Smoke | — | só leitura |
| 7. Saque de prova na mainnet | não | é uma transferência real para a carteira do próprio fundador; opcional e decidido por ele |
| 8. REAL religado | sim | ligar o kill switch de novo |
| 9. Revisão | — | documento |

## 6. Casos especiais

| Caso | O que fazer | Fonte |
|---|---|---|
| Rollback para uma imagem sem a U3 | o alerta de trabalho parado dispara; silenciar durante o rollback | `docs/runbooks/real-risk.md` |
| Ordem `AMBIGUOUS` depois de 15 min | seguir com o rollback; ela continua reservada e aparece no alerta | `docs/runbooks/real-execution.md`, seção 4 |
| Condicional não terminal depois de 15 min | parar o rollback | `docs/runbooks/real-exits.md`, seções 3 e 6 |
| Saque redigido e rollback da 0021 | não se aplica em produção: o banco nunca volta | `docs/runbooks/real-operations.md`, seção 7 |
| Banco do TxSigner ou da aplicação perdido | não é rollback; é recuperação | `docs/runbooks/txsigner-recovery.md` |
| Circuit breaker devolveu sozinho | a versão antiga está em serviço; passos 6 e 7, depois investigar | `deployment-strategy.md`, seção 5 |

## 7. Comandos de referência

| Objetivo | Comando |
|---|---|
| Implantar | passo do fundador (`cd-config.md` 4.6) e depois `gh workflow run deploy.yml --ref main -f sha=<SHA> -f environment=<env> -f mode=deploy` |
| Rollback pelo workflow | `gh workflow run deploy.yml --ref main -f sha=<SHA_ANTERIOR> -f environment=<env> -f mode=rollback -f real_off_confirmed=true` e depois o passo 4, A2 (TxSigner, fundador com MFA) |
| Revisões do fundador (Terraform) | `cd infra/terraform && terraform output -json founder_task_definition_arns` |
| Quem registrou uma revisão | `aws ecs describe-task-definition --task-definition <ARN> --query 'taskDefinition.[registeredBy,registeredAt]'` |
| Acompanhar | `gh run watch <id>` |
| Revisão em serviço | `aws ecs describe-services --cluster <NAME_PREFIX> --services api-worker txsigner --query 'services[].[serviceName,taskDefinition]'` |
| Estado da troca | `aws ecs describe-services --cluster <NAME_PREFIX> --services <svc> --query 'services[0].deployments[].[status,taskDefinition,rolloutState]'` |
| Eventos do serviço | `aws ecs describe-services --cluster <NAME_PREFIX> --services <svc> --query 'services[0].events[:10].[createdAt,message]'` |
| Imagens de um SHA | `aws ecr describe-images --repository-name <NAME_PREFIX>/api --image-ids imageTag=<SHA>` |

## 8. Ensaio na devnet antes da primeira mainnet

O `team.md` pede um rollback "documentado e testado", e a matriz de promoção exige este ensaio antes da etapa 3. Faça-o na devnet, com dois SHAs de `main` já verdes: `N-1` e `N`.

| # | Ação | Esperado | Evidência |
|---|---|---|---|
| 1 | Passo do fundador (`cd-config.md` 4.6) com `N-1`, depois `deploy.yml` devnet `N-1` (`mode=deploy`) | verde; e-mails dos alarmes `-security-*` recebidos para cada ato do fundador | link do run; horários dos e-mails |
| 2 | Ativar a carteira do bot de teste, depositar SOL da devnet e ligar o REAL do fundador na devnet (flags por implantação auditada; liberação com quarentena) | carteira ativa e saldo visível | captura da tela e do registro da liberação |
| 3 | Passo do fundador com `N`, depois `deploy.yml` devnet `N` (`mode=deploy`) | verde | link do run e revisões anteriores no resumo |
| 4 | Passo 1: kill switch ligado | ordens novas recusadas; saque disponível | captura e evento na trilha |
| 5 | Passo 2: espera | sem ordem `SENT` | horário |
| 6 | Passo 4, caminho A: `deploy.yml` devnet `N-1` (`mode=rollback`, `real_off_confirmed=true`), e A2: TxSigner `N-1` pelo fundador | verde; ordem `admin`/`web` → `api-worker` → `txsigner`; prontidão confirmada pelo probe | link do run; saída do A2; tempo total |
| 7 | Passo 6: smoke | verde | resumo do run |
| 8 | Passo 7: saque de prova e reconciliação | saque `CONFIRMED`; reconciliação `CLEAN` | captura |
| 9 | Passo 8: kill switch desligado | ordens voltam a ser aceitas | captura |
| 10 | Caminho B uma vez: flags desligadas por Terraform + modo rollback | REAL desligado e imagens anteriores na mesma execução | link do run e `terraform plan` |
| 11 | Caminho C uma vez: `update-service` pela CLI para uma revisão anterior e volta | `COMPLETED` nos dois sentidos | saída dos comandos |
| 12 | Circuit breaker: implantar uma revisão que não sobe (por exemplo, uma variável obrigatória vazia só na devnet) | o ECS devolve a revisão anterior; o job falha com "the circuit breaker rolled back" | link do run |
| 13 | SP-01 na prática: com o papel de implantação (um run de teste do `deploy.yml` na devnet, ou o simulador de políticas), tentar `update-service` do `txsigner`, `run-task` de `<NAME_PREFIX>-txsigner-migrate` e um `register-task-definition` com o papel do TxSigner | os três recusados (`AccessDenied`); o `RegisterTaskDefinition` sem papel do TxSigner passa, mas o alarme `-security-founder-family-registered` dispara | saída do simulador (`aws iam simulate-principal-policy`) ou do run |
| 14 | `deploy.yml` sem o passo do fundador para o SHA | o job para em "Founder step done" antes de qualquer troca | link do run |

**Critério de aprovação:**
- os passos 1 a 14 com o resultado esperado;
- nenhum saque recusado ou perdido durante o ensaio;
- o tempo do passo 1 ao passo 7 anotado: é a referência do tempo de recuperação.

Registrar o resultado num documento novo em `docs/proofs/`, como as provas do KMS e da Jupiter (nome a definir pelo responsável), e citá-lo na aprovação da mainnet.

Esta é a única forma de "testado" disponível antes de existir uma conta AWS. Nenhum teste da CI usa devnet nem KMS real (`team.md`, Determinismo).

## 9. Escalonamento e contatos

| Severidade | Exemplos | Primeira ação | Tempo para agir |
|---|---|---|---|
| P1 | recusa da política do TxSigner fora do esperado (`RealExecutionPolicyRefused`); divergência que não é legítima; assinatura ou chave suspeita (`OrphanBotWalletKey`, `kms-admin-action`); saque recusado ou parado; um alarme `<NAME_PREFIX>-security-*` sem um passo do fundador correspondente; achado alto do GuardDuty ou achado ativo do Access Analyzer | kill switch REAL na hora; depois o runbook da área (para os alarmes de segurança: `aws ecs describe-task-definition ... registeredBy`, CloudTrail do evento e revogação das sessões do papel envolvido) | imediato |
| P2 | implantação vermelha depois da troca; API fora do ar; 5xx acima do limite; TxSigner sem prontidão | este runbook | assim que visto |
| P3 | alarme de custo ou de fonte de mercado; implantação vermelha antes de qualquer troca | investigar no horário normal | no mesmo dia |

| Papel | Quem | Contato |
|---|---|---|
| On-call único, decisor do rollback e dos gates do REAL | fundador (responsável do projeto) | a definir |
| Suporte AWS (KMS, ECS, RDS) | plano de suporte da conta | a definir |
| Provedores de RPC (dois por ambiente) | a escolher no Environment Provisioning | a definir |
| CoinGecko e Jupiter | fornecedores | a definir |
| GitHub (Actions fora do ar) | status público do GitHub | usar o caminho C |

Sem um segundo on-call, um P1 fora do horário do fundador é contido pelo próprio sistema: o REAL recusa ordens sob divergência, os alarmes ficam ativos, e o saque continua disponível. A revisão pós-incidente de todo P1 e P2 é obrigatória (regra da fase Operation).

## Sources

- `operation/deployment-pipeline/deployment-pipeline-questions.md` (Q4)
- `operation/deployment-pipeline/cd-config.md` e `deployment-strategy.md` (este estágio)
- `infrastructure-specification.md` e `cicd-pipeline.md` das Units U1 a U7 (`construction/u*/infrastructure-design/`)
- `construction/ci-pipeline/ci-config.md` e `construction/ci-pipeline/quality-gates.md`
- `verification/phase-check-construction.md`
- `docs/runbooks/real-operations.md`, `real-execution.md`, `real-exits.md`, `real-risk.md`, `txsigner-recovery.md`
- `apps/api/openapi/openapi-admin.yaml` (`/api/v1/admin/real/kill-switch`), `infra/observability/real-alerts.yml` (nomes dos alertas)
- `team.md` (Deployment, Testing Posture), `project.md` (Mandated, Forbidden)
