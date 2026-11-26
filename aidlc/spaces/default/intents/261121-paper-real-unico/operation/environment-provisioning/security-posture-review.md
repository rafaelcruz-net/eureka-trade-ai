# Revisão da postura de segurança do delta — intent `261121-paper-real-unico`

Data: 2026-11-25. Conversa: português (Brasil). Commit de referência: `09de0a8daea32fc9ec9d8bfe380a9eac9f3b6c29` (`main`). Revisor: `aidlc-aws-platform-agent`, no passo 3 do estágio Environment Provisioning.

O escopo é **só o que este intent acrescenta** à IaC e ao banco [Q3]. A postura da base (rede, KMS, IAM, SCP, TxSigner, detecção) continua a do `security-posture-review.md` do 261113 e das correções SP-01 a SP-11 registradas lá. A revisão foi feita só por leitura. Nada foi aplicado, e nenhum código, IaC ou workflow mudou.

## Veredito

**APROVADO no delta, com dois riscos herdados que bloqueiam etapas.**

- O delta (variáveis da flag, mapeamento, saídas, 6 séries de métrica, 3 alarmes, migrações `0023` a `0027`) não remove nem contorna nenhum controle. Também não cria papel IAM, política, regra de rede, chave ou segredo.
- **D-INF2** bloqueia a primeira implantação na nuvem até o fundador decidir (`cd-config.md`, seção 9, item 6).
- **IR-G3** bloqueia a virada da flag da U4 em qualquer ambiente na nuvem. Também deixa a recuperação de migração só na repetição (`cd-config.md`, seção 8).

## 1. Revisão do delta

| ID | Mudança | Arquivo:linha | Implicação de segurança | Risco | Situação |
|---|---|---|---|---|---|
| SR-01 | Flag com padrão desligado | `infra/terraform/main.tf:155-170`, `:468`, `:475`; `environments/*.tfvars.example:36-37` | A flag só liga por uma revisão de definição de tarefa: mudança no `tfvars` da conta, `plan`, `apply` com MFA e implantação só da flag. Não existe rota, tabela nem actuator gravável que a ligue. A validação (`:166-169`) recusa no `plan` o worker ligado com a API desligada. A chave fica fora de `app_environment` (`:428-441`), então uma mudança nunca vira os dois contêineres juntos. Ligar a flag não liga o REAL: `MEMETRADE_REAL_ENABLED` e `MEMETRADE_REAL_EXITS_ENABLED` continuam `"false"` (`:434`, `:436`), e o `txsigner.real.enabled` não muda. | Baixo | Controlado. Lacuna de teste O-1 abaixo. |
| SR-02 | Métricas e alarmes sem dado pessoal | `modules/observability/app-metrics.tf:71-79`; `real-ops.tf:198-283` | As 6 séries novas não têm dimensão, ou têm só `reason`, um enum fechado com `BUDGET_EXCEEDED`, `READ_FAILED` e `BOT_NOT_OWNED` (`OwnQuantityMetrics.java`). Nenhum tenant, pessoa, bot, mint, carteira ou assinatura chega ao CloudWatch. O agente já publicava em `Eureka/App`, e nenhuma permissão IAM nova é necessária. Os alarmes usam o tópico SNS único, que já é criptografado pela chave do tópico (SP-11 do 261113). | Baixo | Controlado |
| SR-03 | Migrações só de expansão | `changes/0023` a `0027`; `db.changelog-master.yaml:47-55` | Nenhuma coluna removida e nenhum DML em tabela existente, então a imagem anterior roda sobre o esquema novo e o rollback é só de imagem. Os blocos de privilégio da `0023`, `0024` e `0025` **restringem** acesso: `REVOKE ALL ... FROM PUBLIC`, revogação dos privilégios padrão e devolução só de `SELECT, INSERT` ao login da aplicação, mais `REVOKE TRUNCATE` do dono. As tabelas `real_entry_slot` e `real_position_epoch` recusam `UPDATE` e `DELETE` por gatilho (só inserção, como o ledger). O único `GRANT` novo é `SELECT, DELETE` em `real_risk_day_deferred` ao `risk_retention`, para o expurgo (`0024-real-risk-day-deferred.sql:60`). É uma permissão de banco, não de IAM. A `0026` e a `0027` não têm `GRANT`, `REVOKE` nem DML. | Baixo | Controlado |
| SR-04 | Índices com `CONCURRENTLY` e troca de regra com espera limitada | `0023-real-entry-slot.sql:68-76`; `0027-...-idx.sql:7-15`; `0026-...-outcomes.sql:16-33` | Disponibilidade: os índices não travam a escrita das confirmações, do ciclo e da fila durante a implantação. Uma criação interrompida deixa um índice `INVALID`, e a execução seguinte o apaga (`DROP INDEX CONCURRENTLY IF EXISTS`) antes de criar de novo; nunca se usa `IF NOT EXISTS`, que manteria um índice inválido. A `0026` espera a trava no máximo 5 s (`SET LOCAL lock_timeout = '5s'`). Se falhar, a regra antiga continua e a implantação para antes das imagens. | Baixo | Controlado. A conferência do estado do índice na nuvem depende do IR-G3. |
| SR-05 | Nenhum IAM novo | `git diff 600140e HEAD -- infra/terraform` | Os únicos recursos novos são 3 `aws_cloudwatch_metric_alarm`. Papéis, políticas, `passable_role_arns`, OIDC, SCP, grupos de segurança, endpoints, chaves KMS e segredos ficam como estão. A separação do SP-01 (o papel de implantação nunca toca no TxSigner) continua intacta. | Nenhum | — |
| SR-06 | Revisão do sidecar `metrics-agent` na família `txsigner` | gerada de `app-metrics.tf` por `modules/services/metrics-agent.tf` (sem mudança) | Muda só o texto de configuração do agente. Papel, segredo, imagem e KMS do TxSigner ficam como estão. A revisão só entra em serviço pelo passo do fundador com MFA. As três métricas nunca existem no TxSigner. | Baixo | Controlado (padrão D-INF1 da U2) |
| SR-07 | Valor ligado da flag fora do git (F4) | `environments/<env>.tfvars`, fora do repositório | A trilha da virada é o `terraform plan` e o `apply` com MFA, o `RegisterTaskDefinition` no CloudTrail (trilha com object lock de 1 ano) e a nota da implantação, e não um commit. Essa trilha basta para a auditoria, mas difere do `team.md` (Deployment, Flags: "mudar uma flag é uma implantação auditada separada", versionada no Terraform). | Baixo | Diferença registrada (Q5 da U4); o texto do `team.md` só muda pelo ritual de aprendizados |

**O-1 (lacuna de teste, Baixo).** A garantia de que `MEMETRADE_REAL_OWN_QUANTITY_SELL` nunca entra em `app_environment` hoje vem só da leitura (V-07 do `validation-report.md`). A asserção do `metrics-agent` no `own_quantity_flag_default` (`tests/observability_metrics.tftest.hcl:253-255`) não prova isso, porque o sidecar nunca recebe `app_environment` (`metrics-agent.tf:126-135`). Proposta para a próxima volta da U4: um teste de texto que recuse a chave dentro do bloco `app_environment` de `main.tf`. Custo zero. Decide o fundador.

Nenhum controle de segurança é removido ou contornado. Nenhuma mudança em IAM, rede ou criptografia.

## 2. Riscos herdados que afetam este intent

### 2.1 IR-G3: não existe caminho SQL do fundador na nuvem

**Origem:** `261113-trading-real-bot/operation/incident-response/runbooks.md`, linhas 83 e 765. O papel de implantação nega `ecs:ExecuteCommand` e `ssm:StartSession`, e nenhuma família avulsa roda SQL de leitura.

**Efeito aqui:**

| Procedimento | Runbook `docs/runbooks/real-operations.md` | O que precisa |
|---|---|---|
| Conferências da virada da flag | seção 11 (três consultas só de leitura em `real_decision_request`, `real_order` e `decision`, com contagens e UUIDs) | leitura |
| Índice da `0023` interrompido | seção 9 (`to_regclass`, `pg_index.indisvalid`) | leitura do catálogo |
| Trava do changelog presa | seção 9, passo 5 (`select ... from databasechangeloglock` e depois `update databasechangeloglock ...`) | leitura e **uma escrita** de manutenção |
| `0026` ou `0027` barrada | seção 12 (`pg_stat_activity`) | leitura das estatísticas |

Hoje a virada da flag fica bloqueada também por isso. Uma trava do changelog presa bloqueia a implantação (P2) (`rollback-runbook.md`, linha 136).

**Opções:**

| Opção | Desenho | Impacto | Custo/mês por conta (US$) |
|---|---|---|---|
| **A. Família avulsa de leitura do fundador** (recomendada antes da virada) | Família `${name}-db-readonly` declarada no Terraform, no padrão da `-txsigner-probe` (SP-01). **Papel de tarefa:** nenhum. **Papel de execução:** só `ecr:` pull do repositório `db-bootstrap`, `logs:CreateLogStream`/`PutLogEvents` em `/ecs/<name>/ops` (90 dias) e `secretsmanager:GetSecretValue` em **um** segredo novo, `<name>/db-readonly`. O papel **fica fora** de `passable_role_arns` e entra no Deny do papel de implantação. `RunTask` só pelo fundador com MFA: a SCP põe o papel na lista de `PassRole` com MFA, e o filtro do alarme `-security-founder-family-run` ganha a família. **Imagem:** a mesma `db-bootstrap` (`postgres:17-alpine` fixada por digest, com `psql`), com um script `readonly.sh` de consultas nomeadas e fixas (`activity`, `changelog-lock`, `index-0023`, `index-0027`, `flip-checks`), sem SQL livre na linha de comando. **Login do banco:** `memetrade_readonly`, só `CONNECT`, `pg_read_all_stats` e `SELECT` em `databasechangeloglock`, `real_decision_request`, `real_order` e `decision`, com `default_transaction_read_only = on`; criado pelo `10-roles.sql` do bootstrap. **Rede:** sub-rede `private_b`, grupo próprio com saída só para 5432 do RDS `app` e 443 dos endpoints. **Escrita da trava (seção 9, passo 5):** fica fora desta família. É feita como "vidro de emergência" pela família `-db-bootstrap` que já existe, só do fundador e com MFA, com o comando fixo do runbook, e é registrada na nota. Nenhum IAM novo para isso. | Fecha o IR-G3 para as consultas da virada e para o diagnóstico das migrações. Destrava a virada quando a condição 1 for cumprida. Cria um principal novo com leitura de dados de clientes, limitada a contagens e UUIDs na saída das consultas fixas, sem endereço de carteira. A superfície aumenta pouco e é coberta por MFA, CloudTrail e alarme. Exige uma volta à Construction (Infrastructure Design e Code Generation): Terraform (família, papéis, segredo, grupo, regras, SCP e Deny), papel no bootstrap, script, testes (`security_delivery.tftest.hcl`: papel fora de `passable_role_arns`) e runbook. **Este estágio não implementa nada disso.** | segredo ≈ 0,40; Fargate 0,25 vCPU e 0,5 GB ≈ 0,012 por hora, < 0,05 com até 4 horas de uso; logs ≈ 0. **Total ≈ 0,45** (≈ 0,90 nas duas contas) |
| B. ECS Exec no `api-worker` | `enableExecuteCommand = true` no serviço, `ssmmessages:*` no papel de tarefa, endpoint de interface `ssmmessages` (ou saída pelo NAT) e liberação de `ecs:ExecuteCommand` ao fundador. | Abre um shell interativo num contêiner que tem os segredos da aplicação e o login completo dela. Contraria o menor privilégio e a separação do SP-01, que nega `ExecuteCommand` de propósito. **Não recomendada.** | 0 pelo NAT; ≈ 7,30 com o endpoint numa zona |
| C. Aceitar sem caminho SQL | Nada muda. Vale a linha da lista de conferência do `cd-config.md` (seção 6): "o fundador aceita implantar sem leitura SQL na nuvem (sem virada da flag)". | A primeira implantação do código, com a flag `false`, pode seguir. A virada continua bloqueada. A recuperação de migração fica só na repetição (`cd-config.md`, seção 3). Uma trava do changelog presa para as implantações até existir a opção A. | 0 |

**Proposta:** C para a primeira implantação do código na devnet e na mainnet, com a flag `false`; A antes da primeira virada da flag. **Quem decide:** o fundador. **Quem implementa A:** uma volta à Construction deste intent, ou um intent de infraestrutura próprio. O `cd-config.md` (seção 8) atribuía o fechamento a este estágio, mas este estágio não faz mudança de IaC [Q1].

### 2.2 D-INF2: teto de conexões no rolling, sem alarme de conexões do RDS

**Origem:** `u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md`, linha 142 e item 2. Com `deployment_minimum_healthy_percent = 100` e `deployment_maximum_percent = 200` (`modules/services/api-worker.tf:122-123`), duas tarefas `api-worker` convivem por alguns minutos.

| Conta | Valor |
|---|---|
| Regime (uma tarefa) | no máximo 66 |
| Teto teórico no rolling | 2 × 62 + 3 = **cerca de 127** (cerca de 123 antes da U2) |
| `max_connections` da `db.t4g.micro` | **cerca de 112** (fórmula `DBInstanceClassMemory / 9531392`; confirmar na primeira conta, U-09 do `validation-report.md`) |
| Alarme de `AWS/RDS DatabaseConnections` | **não existe**; só o gráfico do painel (`modules/observability/dashboards.tf:225`) |

O risco é de disponibilidade: conexão recusada durante a troca, que aparece como 5xx na janela de observação ou como falha de um ciclo do worker. Nada de fundos: o saque e as ordens são idempotentes (`project.md`, Mandated).

**Opções:**

| Opção | Desenho | Impacto | Custo/mês por conta (US$) |
|---|---|---|---|
| **a. Pool menor** | Os limites do `RuntimeSettingsGuard` são: worker de 36 a 48 (4 ciclos + 4 de reserva + 28 trabalhos agendados) e API de 10 a 50. **O worker não pode baixar sem mudar o código.** Só a API baixa: `DB_POOL_MAX_SIZE` de `"20"` para `"10"` em `api_environment` (`main.tf`), e cada tarefa fica com 52. O teto vai a 2 × 52 + 3 = **cerca de 107**, contra cerca de 112. | A folga fica em cerca de 5 conexões, frágil. O pool `pnl-mode-block` da U3 usa até 8 conexões do pool principal, então sobram 2 para login, bots e o resto da API durante leituras de P&L, com risco para a meta de 1.000 ms (U3 NFR1.6, AC3.11.2). Exige uma nova medição `pnl-by-mode` (condição 2) e uma mudança de IaC. **Não recomendada sozinha.** | 0 |
| **b. Classe maior para o RDS `app`** (recomendada) | `db.t4g.small` (2 GiB) só para o banco `app`: `max_connections` de **cerca de 225** pela mesma fórmula, com folga de cerca de 98 sobre o teto. Hoje `instance_class = "db.t4g.micro"` é um valor fixo para os dois bancos (`modules/data/main.tf:144`, `for_each` de `app` e `txsigner`). A mudança pede um mapa por banco. Como nada está provisionado, entrar já com a classe certa não custa reinício nem janela. | Resolve o teto com sobra para o crescimento e mantém a estratégia rolling do `team.md`. O TxSigner fica igual. | + ≈ 11,68 (0,032 − 0,016 por hora × 730, `us-east-1`); ≈ 23,36 nas duas contas. Se a classe subir também no `txsigner`, + ≈ 23,36 por conta. |
| **c. Alarme de conexões** (recomendada junto com b) | `aws_cloudwatch_metric_alarm` em `AWS/RDS` `DatabaseConnections`, `DBInstanceIdentifier = ${name}-app`, `Maximum`, `period = 60`, 3 avaliações, 2 pontos (180 s, dentro da regra do `project.md`), limite de 85% do `max_connections` (95 na `micro`, 190 na `small`), `notBreaching`, tópico único. Entra em `alarm_resource_names` e nos testes (`other_metric_sources`). | Só detecta, não previne. Avisa no rolling e fora dele, por exemplo num vazamento de conexões. As métricas básicas do RDS não custam. | ≈ 0,10 |

Saídas descartadas: `deployment_minimum_healthy_percent = 0` no `api-worker`, que contraria o `team.md` ("tarefa nova saudável antes de a antiga sair") e o `cd-config.md` (item 4 das dúvidas abertas); e `max_connections` maior no grupo de parâmetros da `micro`, que é estático, pede reinício e pressiona a memória de 1 GiB.

**Proposta:** b + c, cerca de **US$ 11,78 por mês por conta**. Com isso, a mainnet vai de cerca de 314 a 323 para **cerca de 326 a 335**, abaixo dos US$ 350. Somada a opção A do IR-G3 (≈ 0,45), continua em cerca de 326 a 335. A folga para o orçamento fica entre cerca de US$ 15 e 24. **Quem decide:** o fundador (responsável do projeto), antes da primeira implantação na nuvem (`cd-config.md`, seção 6, "D-INF2 decidido e aplicado"). **Quem implementa:** a mesma volta à Construction ou o intent de infraestrutura do IR-G3. Este estágio não muda a IaC.

## 3. Pendências da base que continuam valendo

Os achados do 261113 que ficaram como pendência obrigatória (`validation-report.md` daquele intent, seção 11) continuam valendo sem mudança, por exemplo SP-06 (mTLS), SP-07 (`TRUSTED_PROXIES`), SP-09, SP-10, SP-16 a SP-18 e SP-20 (Terraform e Checkov na CI). O SP-12 (métrica que o código não publica deixa o alarme verde para sempre) é coberto para as métricas deste intent pelo alarme `metrics_absent` por processo (`observability_metrics.tftest.hcl:193-196`) e pela prova U-05 na devnet.

## Sources

- `operation/environment-provisioning/environment-provisioning-questions.md` (Q1 a Q3 e o resumo confirmado) [Q1] [Q2] [Q3].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/environment-provisioning/security-posture-review.md` (SP-01 a SP-21), `validation-report.md` (seções 10 e 11), `environment-inventory.md` (custos).
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/runbooks.md` (linhas 83 e 765, IR-G3).
- `construction/u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md` (linha 142 e itens 2 e 3: D-INF2, cerca de 112 e 127).
- `construction/u3-pnl-views/infrastructure-design/infrastructure-specification.md` (conta de conexões, `pnl-mode-block`).
- `construction/u4-own-quantity-cycle/infrastructure-design/infrastructure-specification.md` (implicações de segurança, F4, Q5).
- `operation/deployment-pipeline/cd-config.md` (seções 3, 6, 8 e 9), `deployment-strategy.md` (1.3), `rollback-runbook.md` (linha 136).
- Código lido no commit `09de0a8`: `infra/terraform/main.tf`, `modules/services/api-worker.tf`, `metrics-agent.tf`, `one-off.tf`, `modules/data/main.tf`, `modules/observability/app-metrics.tf`, `real-ops.tf`, `dashboards.tf`, `tests/observability_metrics.tftest.hcl`, `infra/postgres/bootstrap/Dockerfile`, `apps/api/.../shared/config/RuntimeSettingsGuard.java` (limites dos pools), `apps/api/.../bots/infra/OwnQuantityMetrics.java`, `changes/0023` a `0027`, `docs/runbooks/real-operations.md` (seções 9, 11 e 12).
- `git diff 600140e HEAD -- infra/terraform`.
- `aidlc/spaces/default/memory/team.md` (Deployment: estratégia, flags, rollback) e `project.md` (Mandated, orçamento de US$ 350, CloudWatch a 120 s).

## Assumptions & Open Questions

1. [assumption] Preços on-demand de referência da `us-east-1`: RDS PostgreSQL `db.t4g.micro` US$ 0,016 por hora e `db.t4g.small` US$ 0,032 por hora; Secrets Manager US$ 0,40 por segredo; Fargate US$ 0,04048 por vCPU-hora e US$ 0,004445 por GB-hora; alarme padrão US$ 0,10. Na região escolhida, recalcular.
2. [assumption] `max_connections` de cerca de 225 na `db.t4g.small` vem da mesma fórmula da AWS usada para os 112 da `micro`; confirmar na primeira conta.
3. [assumption] O "+ 3" do teto teórico (família `db-migrate` e afins) e os 62 por tarefa são os da U2; este estágio não refez a conta.
4. Aberta: a decisão do D-INF2 (b + c proposta). Dono: fundador, antes da primeira implantação na nuvem.
5. Aberta: a decisão do IR-G3 (C agora e A antes da virada, proposta). Dono: fundador. O `cd-config.md` atribuía o fechamento a este estágio; ele fica aberto porque este estágio não muda a IaC.
6. Aberta: O-1 (teste de texto da flag fora de `app_environment`). Dono: fundador, na próxima volta da U4.
7. Diferença registrada, não resolvida aqui: o valor ligado da flag no `tfvars` (F4) contra o texto do `team.md` sobre flags versionadas (SR-07).
