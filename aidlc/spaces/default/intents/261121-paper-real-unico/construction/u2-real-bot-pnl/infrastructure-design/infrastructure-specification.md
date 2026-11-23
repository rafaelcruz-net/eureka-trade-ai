# Especificação de infraestrutura — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Revisado depois de `reviews/review-01.md` (R-01 a R-07) e das duas decisões do fundador de 2026-11-22 (D-INF1 e D-INF2, abaixo).

A U2 não traz serviço, processo, réplica, fila nem cache novos. Ela entra nas imagens que já existem (a imagem `api`, que roda a API e o worker) e na atualização rolling do ECS já desenhada. As mudanças de infraestrutura são quatro:

- duas migrações aditivas, `0023` e `0024`, aplicadas pelo `db-migrate` [Q1];
- chaves novas só no `application.yml`, sem variável nova nos contêineres da aplicação [Q2];
- uma métrica a mais na lista de permissão do CloudWatch, o que muda a configuração do sidecar `metrics-agent`, e um alarme [Q3] [D-INF1];
- nenhum segredo novo e nenhum serviço AWS novo [Q5].

Este estágio não altera nenhum arquivo. Tudo é aplicado na Code Generation. Hoje nenhum ambiente na nuvem está provisionado; o que vale para a nuvem vale quando o Terraform de `infra/terraform/` for aplicado (`team.md`, Deployment).

## Decisões do fundador (2026-11-22)

| ID | Decisão | Efeito neste desenho |
|---|---|---|
| D-INF1 | **Aceita a mudança do sidecar na definição de tarefa.** A lista de permissão de `app-metrics.tf` vira o texto de `CW_CONFIG_CONTENT` e de `PROMETHEUS_CONFIG_CONTENT` do contêiner `metrics-agent` (`modules/services/metrics-agent.tf`) nas duas famílias, `api-worker` e `txsigner`. Por isso o `terraform apply` registra duas revisões novas de definição de tarefa. Nada entra em serviço sozinho, porque os serviços têm `ignore_changes = [task_definition]`. A revisão do `api-worker` entra na próxima implantação; a do `txsigner` entra no passo do fundador com MFA. | O alcance do NFR8.1 é: o ambiente e a configuração dos contêineres da aplicação (`api`, `worker`, `txsigner`) não mudam; só a configuração do sidecar `metrics-agent` muda. O NFR8.1 e a Q2 são alinhados a este texto na Code Generation (alinhamento A16). |
| D-INF2 | **Teto de conexões na atualização rolling: pendência do responsável, fora do escopo da U2.** | Registrado em "Conexões do banco" e em "Assumptions & Open Questions", item 2. |

## Implantação

| Faceta | Escolha | Justificativa |
|---|---|---|
| Modelo de computação | Contêineres no ECS Fargate, na tarefa que já existe `api-worker` (contêineres `api` e `worker` da mesma imagem, mais o sidecar `metrics-agent`). Nenhuma família, serviço ou contêiner novo. | A leitura do P&L REAL roda na API; a leitura de custo das rodadas e do ciclo roda no worker (NFR1.17, NFR8.1) [Q5]. |
| Definições de tarefa | `api-worker` e `txsigner` ganham, cada uma, uma revisão **só do sidecar** `metrics-agent` (`CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT` com a métrica nova). Não mudam: `api_environment`, `worker_environment`, o ambiente do `txsigner`, as imagens, os segredos, CPU, memória e contêineres. | D-INF1; o filtro `keep` e as `metric_declaration` são gerados da mesma lista para as duas famílias. |
| Dimensionamento | Sem mudança: tarefa `api-worker` com `cpu = 1024` e `memory = 3072`, contêineres `api` e `worker` com 1024 MiB cada, `desired_count` atual. O pool `pnl-mismatch-audit` (2 conexões) e o executor dele (2 threads) rodam dentro do contêiner `api`. | Por leitura, o índice das ordens ocupa no máximo cerca de 5 MB; com 8 leituras ao mesmo tempo, cerca de 40 MB, longe do limite do contêiner (`scalability-design.md`, NFR1.14, NFR1.15). |
| Rede | Sem mudança: mesma VPC, sub-redes, grupos de segurança e balanceador. `GET /api/v1/real/pnl` e C4 continuam no mesmo listener e grupo de destino. | A U2 não chama outro processo nem rede externa (NFR3.10, NFR8.13). |
| Armazenamento | O mesmo PostgreSQL (RDS `app`, módulo `data`), sem mudança de instância, armazenamento, parâmetros ou réplicas. Duas migrações aditivas: `0023` (tabela `real_entry_slot`, gatilhos, permissões e o índice `real_ledger_entry_wallet_created_idx`) e `0024` (tabela `real_risk_day_deferred`). Ver "Migrações". | NFR1.18, NFR1.19, NFR2.3, NFR3.4 a NFR3.6, NFR3.11 [Q1]. |
| Ambientes | Local (Docker Compose, Windows) e CI (Linux). Na nuvem, a devnet só para os testes do fundador e a pré-produção do mesmo SHA; a mainnet só num SHA que tem a U1 e a U2 com o Build and Test aprovado. Nenhuma configuração da U2 abre a devnet. | NFR8.3, NFR8.4; `project.md`, Forbidden (devnet fechada). |
| Abordagem de IaC | Terraform em `infra/terraform/`. Duas mudanças, as duas no módulo `observability`: a métrica em `app-metrics.tf` (que o módulo `services` lê para montar o sidecar) e o alarme em `real-ops.tf` (ver `monitoring-design.md`). `infra/terraform/main.tf` (`api_environment`, `worker_environment`) **não muda**. | NFR8.1, D-INF1 [Q2] [Q3]. |
| Segredos | Nenhum segredo novo. O HMAC da assinatura no `REAL_SLOT_CONFLICT` usa a chave que já existe (`MEMETRADE_AUDIT_WALLET_HMAC_KEY`, segredo `audit-wallet-hmac-key`). O pool `pnl-mismatch-audit` usa o mesmo login da aplicação (`DB_USER`, `DB_PASSWORD`). As listas `api_secrets` e `worker_secrets` de `modules/services/api-worker.tf` não mudam. | NFR3.9, NFR3.15 [Q5]. |
| Escala | A mesma regra de escala da tarefa `api-worker`. Nenhum gatilho de autoescala novo. Com mais de uma tarefa, cada uma acrescenta 2 conexões fixas do pool `pnl-mismatch-audit`. | NFR1.16, NFR1.17 [Q5]. |
| Custo | 1 métrica personalizada sem dimensões e 1 alarme padrão no namespace `Eureka/App`: cerca de US$ 0,30 + US$ 0,10 = US$ 0,40 por mês por conta. Revisões de definição de tarefa não custam. O orçamento de US$ 350 por conta não muda. | NFR8.2 [Q3] [Q5]; `project.md`, correção do CloudWatch Agent. |

## Migrações

As duas migrações entram em `apps/api/src/main/resources/db/changelog/changes/` e em `db.changelog-master.yaml`, depois da `0022`. Elas seguem o formato que o changelog já usa (`--liquibase formatted sql`, autor `memetrade`). O Liquibase roda só no `db-migrate` (`MigrationRunner`, perfil `migrate`, login dono do esquema, pool de 1 conexão). A API e o worker rodam com `spring.liquibase.enabled: false`.

### `0023-real-entry-slot.sql`

| Changeset | Conteúdo | Atributos | Rollback (só local e teste) |
|---|---|---|---|
| `0023-real-entry-slot` | `CREATE TABLE real_entry_slot` (`tx_signature varchar(90)` chave primária, `slot bigint not null` com `CHECK (slot >= 0)`, `recorded_at timestamptz not null`) | padrão (em transação) | `DROP TABLE real_entry_slot` |
| `0023-real-entry-slot-append-only` | gatilhos `real_entry_slot_no_update_delete` e `real_entry_slot_no_truncate` com a função `ledger_reject_change` (da `0008` e da `0016`) | `splitStatements:false`, em transação | `DROP TRIGGER` dos dois |
| `0023-real-entry-slot-privileges` | o bloco `DO` da `0018` e da `0019` (ver "Permissões") com a matriz `real_entry_slot = SELECT, INSERT` | `splitStatements:false`, em transação | `--rollback SELECT 1;`, como o `0016-real-grants`: o `DROP TABLE` do primeiro changeset já leva as permissões |
| `0023-real-ledger-entry-wallet-created-idx` | `DROP INDEX CONCURRENTLY IF EXISTS real_ledger_entry_wallet_created_idx;` e depois `CREATE INDEX CONCURRENTLY real_ledger_entry_wallet_created_idx ON real_ledger_entry (bot_wallet_id, created_at);` | `runInTransaction:false` e `splitStatements` **padrão (verdadeiro)**; um único `--rollback` | `DROP INDEX CONCURRENTLY IF EXISTS real_ledger_entry_wallet_created_idx` |

### `0024-real-risk-day-deferred.sql`

| Changeset | Conteúdo | Atributos | Rollback (só local e teste) |
|---|---|---|---|
| `0024-real-risk-day-deferred` | `CREATE TABLE real_risk_day_deferred` (`tenant_id`, `bot_wallet_id`, `day`, `reason` com `CHECK (reason = 'COST_UNKNOWN')`, `deferred_at timestamptz not null`; chave `(bot_wallet_id, day)`) e o índice de expurgo pelo `deferred_at`, como `real_risk_day_purge_idx` | padrão (em transação) | `DROP TABLE real_risk_day_deferred` |
| `0024-real-risk-day-deferred-privileges` | o bloco `DO` com a matriz `real_risk_day_deferred = SELECT, INSERT`, poupando `risk_retention`; depois `GRANT SELECT, DELETE ON real_risk_day_deferred TO risk_retention`, o mesmo papel que expurga `real_risk_day` | `splitStatements:false`, em transação | `REVOKE SELECT, DELETE ON real_risk_day_deferred FROM risk_retention` |

### Permissões (R-02)

O nome do login da aplicação vem do `.env` (`DB_USER`) e nunca é escrito numa migração. Os dois changesets de permissões copiam o padrão da `0018-real-risk-privileges` e da `0019-real-execution-privileges`:

| Passo do bloco | O que faz |
|---|---|
| 1 | `REVOKE ALL ON <tabela> FROM PUBLIC`. |
| 2 | Num bloco `DO $$ ... $$`, descobre pelo `aclexplode(c.relacl)` os titulares de `INSERT` que não são o dono (o login da aplicação recebe `SELECT, INSERT, UPDATE, DELETE` pelos privilégios padrão do `db-bootstrap`). |
| 3 | Tira tudo de cada titular que não é o dono nem `risk_retention` e devolve aos titulares de `INSERT` só o conjunto da matriz (`SELECT, INSERT`). |
| 4 | `REVOKE TRUNCATE ON <tabela> FROM CURRENT_USER` (o dono também perde `TRUNCATE`). |
| 5 | Só na `0024`: `GRANT SELECT, DELETE` a `risk_retention`, fora do bloco. |

Teste novo no padrão do `DatabaseRolesIT` (`U2DatabaseRolesIT`, nome proposto), com o login da aplicação e o de `risk_retention`: a matriz das duas tabelas é exatamente a de cima; `UPDATE`, `DELETE` e `TRUNCATE` são recusados ao login da aplicação; `risk_retention` só lê e apaga `real_risk_day_deferred`. Ele soma com `RealEntrySlotGrantsIT` (NFR3.5).

### Regras das migrações

| Regra | Decisão | Justificativa |
|---|---|---|
| Índice sem travar o ledger | O índice é criado `CONCURRENTLY`, num changeset só dele, com `runInTransaction:false`. O `CREATE INDEX` comum pega a trava `SHARE` e bloqueia as gravações em `real_ledger_entry` enquanto constrói; o `CONCURRENTLY` pega `SHARE UPDATE EXCLUSIVE` e deixa as confirmações e os depósitos gravarem durante a implantação. | Q1; as imagens antigas continuam no ar enquanto o `db-migrate` roda (rolling). |
| Primeiro uso no repositório | Nenhum changeset de `0001` a `0022` usa `CONCURRENTLY` nem `runInTransaction:false`. A `0023` abre o padrão. O PostgreSQL recusa `CREATE INDEX CONCURRENTLY` dentro de um bloco de transação, e o `runInTransaction:false` faz o Liquibase rodar o changeset em autocommit. O `db-migrate` não tem `statement_timeout` nem `lock_timeout` no papel, no banco ou no grupo de parâmetros (conferido na revisão). | Código lido: `db/changelog/changes/`; `reviews/review-01.md`. |
| **Sem `splitStatements:false` no changeset do índice** | Os changesets do repositório com corpo de função ou bloco `DO` usam `splitStatements:false`. O do índice **não pode** usar: com ele, o `DROP INDEX CONCURRENTLY` e o `CREATE INDEX CONCURRENTLY` iriam ao servidor num só envio, que o PostgreSQL trata como uma transação implícita, e o `CONCURRENTLY` seria recusado. Com o padrão (verdadeiro), o Liquibase manda cada comando separado, cada um em autocommit. | R-03. |
| Retomada depois de falha | Se o `CREATE INDEX CONCURRENTLY` falhar no meio, o PostgreSQL deixa um índice `INVALID` e o Liquibase não marca o changeset como aplicado. O `DROP INDEX CONCURRENTLY IF EXISTS` no começo do mesmo changeset apaga esse resto na próxima execução, e a criação recomeça. **Não** usar `CREATE INDEX ... IF NOT EXISTS`: ele pularia em silêncio um índice `INVALID`. | O planejador não usa índice `INVALID`; a leitura ficaria sem o índice sem ninguém ver. |
| Provas do índice | `Migration0023IndexValidIT` (caminho feliz): depois da migração, `pg_index.indisvalid` e `indisready` valem `true`. **`Migration0023IndexRestartIT`** (ramo de recomeço, nome proposto): no contêiner de teste, com o superusuário, marca o índice como inválido (`update pg_index set indisvalid = false where indexrelid = to_regclass('real_ledger_entry_wallet_created_idx')`) e apaga a linha do changeset em `databasechangelog`, como numa execução interrompida; roda o `MigrationRunner` de novo; confere o código de saída 0, o índice válido e uma única linha do changeset no `databasechangelog`. O `RealLedgerNetworkOrderPlanIT` confere o nome do índice no plano. | NFR1.18; R-03. |
| Rollback exercitado pelo teste da anonimização | O `AnonymizationMigrationIT` desfaz todos os changesets da `0015` até a última migração e aplica tudo de novo. Ele passa a rodar os `--rollback` da `0023` e da `0024`, inclusive o `DROP INDEX CONCURRENTLY` fora de transação, e a contagem de `--rollback` por changeset. Por isso cada changeset novo declara exatamente um `--rollback`, e o do índice herda o `runInTransaction:false`. | R-03; `db/AnonymizationMigrationIT.java`. |
| Espera por transações abertas | O `CONCURRENTLY` espera as transações que já estavam abertas em `real_ledger_entry` terminarem. A mais longa do worker é a do ciclo (`statement_timeout` de 5 s); a espera fica em segundos. Nenhum `lock_timeout` de sessão: ele vazaria para os changesets seguintes da mesma conexão. | `performance-design.md` (transação do ciclo). |
| Só criação | A `0023` e a `0024` só têm `CREATE TABLE`, `CREATE INDEX`, `CREATE TRIGGER`, `GRANT`, `REVOKE` e o `DROP INDEX ... IF EXISTS` do próprio índice novo. 0 `ALTER TABLE`, `UPDATE`, `DELETE` ou `INSERT` em tabela existente. | NFR3.6, NFR2.3; `team.md`, Deployment (expand-contract). |
| Ordem na implantação | O `db-migrate` aplica a `0023` e a `0024` antes da troca das imagens. Falha no `db-migrate` para a implantação antes de qualquer imagem nova. Recuperação na seção 9 do runbook (`cicd-pipeline.md`). | NFR8.4. |
| Rollback | Só a imagem volta. Em produção nenhuma migração é revertida. A imagem anterior não lê nem escreve `real_entry_slot`, `real_risk_day_deferred` nem o índice novo (`Migration0023AdditiveIT`). Os blocos `--rollback` existem só para os ambientes local e de teste. | NFR2.3; `team.md`, Deployment (Rollback) [Q1]. |
| Testes que mudam com a lista de tabelas | `MigrationsIT.everyExpectedTableExists` passa a esperar `real_entry_slot` e `real_risk_day_deferred`. | Código lido: `db/MigrationsIT.java`. |

## Configuração nova

As chaves novas ficam só no `apps/api/src/main/resources/application.yml`, com valor literal (sem `${...}`), lidas por `@ConfigurationProperties` com faixa validada na subida. Nenhuma entra em `api_environment` nem em `worker_environment` de `infra/terraform/main.tf`, porque o valor é o mesmo em todo ambiente [Q2]. O ambiente dos contêineres da aplicação não muda (NFR8.1, no alcance de D-INF1).

Os nomes abaixo são propostas deste estágio; a Code Generation pode ajustá-los, mantendo valor, faixa e processo.

| Chave Spring (proposta) | Valor | Faixa aceita na subida | Processo | Uso | NFR |
|---|---|---|---|---|---|
| `memetrade.pnl.real.attribution.gate-and-cycle-timeout` | `250ms` | 50 ms a 250 ms | `worker` | prazo duro da leitura de custo nos caminhos `GATE` e `CYCLE` | NFR1.4, NFR1.12 |
| `memetrade.pnl.real.attribution.pnl-timeout` | `500ms` | 50 ms a 500 ms, e menor que `memetrade.pnl.by-mode.block-timeout` | `api` | prazo duro da atribuição no caminho `PNL` | NFR1.3, NFR1.12 |
| `memetrade.pnl.real.read.work-mem` | `12MB` | 1 MB a 12 MB | `api` e `worker` | `set local work_mem` só nos dois caminhos que ordenam o histórico inteiro | NFR1.6, NFR1.16 |
| `memetrade.pnl.mismatch-audit.pool-size` | `2` | 1 a 2, conferido pelo `RuntimeSettingsGuard` | `api` | tamanho fixo do pool `pnl-mismatch-audit` (`maximumPoolSize = minimumIdle`) e das threads do executor | NFR1.16, NFR3.15 |
| `memetrade.pnl.mismatch-audit.write-timeout` | `200ms` | 50 ms a 200 ms | `api` | orçamento da gravação do evento; acima dele conta em `pnl_real_reconciliation_audit_failures_total` | NFR3.15, NFR3.16 |

**Constante, não configuração:** a espera por uma conexão do pool `pnl-mismatch-audit` é **250 ms**, uma constante no código do `MismatchAuditDataSource` (`HikariConfig.setConnectionTimeout(250)`), sem chave no `application.yml` (R-04).

Regras da configuração:

| Regra | Decisão | Justificativa |
|---|---|---|
| Teto da faixa igual ao orçamento | O máximo de cada prazo é o próprio orçamento aprovado. Subir um orçamento é decisão do fundador e muda o código, não a configuração. | NFR1.8 (orçamentos só baixam sem o fundador). |
| Piso do Hikari (conferido) | 250 ms é o **menor valor aceito** pelo HikariCP 6.3.3, a versão que o Spring Boot 3.5.16 do projeto fixa. Conferido no `HikariConfig` dessa versão: `SOFT_TIMEOUT_FLOOR` vale 250 ms (propriedade de sistema `com.zaxxer.hikari.timeoutMs.floor`, sem uso no projeto); `setConnectionTimeout` com valor entre 1 e 249 ms **lança** `IllegalArgumentException`, e 0 significa espera sem limite. Só um valor que chegue ao campo por outro caminho é trocado por 30 s, com aviso, na validação do pool. A constante de 250 ms fica no piso. Quem limita a espera ao tempo que resta do bloco é o executor, não o Hikari. | R-04; `security-design.md` (espera limitada ao tempo que resta). |
| `work_mem` só local | O valor entra por `set local` na transação de leitura. O parâmetro do RDS (`aws_db_parameter_group.postgres`) e o do Compose não mudam. O teto de 12 MB mantém o pior caso teórico em 624 MB, abaixo dos 900 MB que o cenário de carga confere. | `performance-design.md`, "Memória de ordenação". |
| Pool só na API | O `MismatchAuditDataSource` só é criado com `MEMETRADE_PROCESS=api`. O worker não abre essas 2 conexões. Teste de contexto: o worker sobe sem o bean. | Conta de conexões abaixo. |
| Pool fora da prontidão | O `MismatchAuditDataSource` não é bean `DataSource`, então o `DataSourceHealthIndicator` não o inclui e a prontidão (`/actuator/health/readiness`) não muda. | NFR4.4; `logical-components.md`. |
| Métricas do pool | Como o pool não é bean `DataSource`, o Spring Boot não liga as métricas `hikaricp_*` a ele. O `MismatchAuditDataSource` chama `HikariConfig.setMetricRegistry(meterRegistry)` com o `MeterRegistry` do contexto e `setPoolName("pnl-mismatch-audit")`. `U2MetricsExportIT` confere que `hikaricp_connections_pending{pool="pnl-mismatch-audit"}` aparece em `/actuator/prometheus`. A métrica fica só no Prometheus. | R-04; diagnóstico de `pnl_real_reconciliation_audit_failures_total`. |
| Sem variável de ambiente | Nenhuma variável nova em `.env.example`, `docker-compose.yml`, `infra/docker-compose.*.yml` ou no ambiente dos contêineres da aplicação no Terraform. | NFR8.1 [Q2]. |
| O que não muda | `memetrade.pnl.by-mode.block-timeout` (800 ms), `pool-size` (8), `queue-capacity` (16) e `real-max-orders` (50.000) ficam como a U1 deixou, com `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT` e `MEMETRADE_PNL_BYMODE_REALMAXORDERS` em `api_environment`. O teto passa a ser aplicado dentro do fluxo da U2 (F-2). | `performance-design.md`, F-2. |
| Ligação testada | Teste de ligação (job `backend`): valores dentro da faixa chegam às classes de propriedades; um valor fora da faixa impede a subida com mensagem que nomeia a propriedade. | Padrão da U1 (`PnlByModeProperties`). |

## Conexões do banco

O `RuntimeSettingsGuard` confere hoje só o pool principal e o do scheduler. A U2 acrescenta, no ramo `api`, a conferência de `memetrade.pnl.mismatch-audit.pool-size` entre 1 e 2, com mensagem que nomeia a propriedade, e a Javadoc ganha a linha do pool novo. O pool principal não muda (API de 10 a 50, padrão 20; worker de 36 a 48, padrão 36) [Q2].

### Compose local

| Conta | Hoje | Com a U2 | Limite | Onde é conferido |
|---|---|---|---|---|
| `docker-compose.yml` | api 20 + retenção da trilha 1 + retenção U6 1 + trava U8 1 + retenção do feedback 1 + worker 36 + subida 2 + superusuário 3 = 65 | 65 + `pnl-mismatch-audit` 2 = **67** | `max_connections` 100 do `postgres:17` | `ComposeFilesTest.theConnectionTallyFitsTheDatabaseLimit` (65 passa a 67) |
| Overlay do REAL (`infra/docker-compose.real.yml`: worker com `DB_POOL_MAX_SIZE: '37'` e o TxSigner até 10) | 65 − 36 + 37 + 10 = **76** (o teste e o comentário dizem 75, porque somam a fórmula com o worker de 36) | **78** | 100 | o mesmo teste, que passa a ler o `DB_POOL_MAX_SIZE` do worker em `infra/docker-compose.real.yml` em vez de somar 10 à conta base |

**Comentário do `docker-compose.yml` (R-05).** O teste confere três trechos fixos do comentário, e o comentário quebra a linha entre eles. A Code Generation reescreve o comentário com cada trecho conferido inteiro numa única linha e muda os três trechos do teste no mesmo commit:

| Trecho que o teste confere hoje | Trecho novo (cada um numa linha só) |
|---|---|
| `api 20 + trail retention (U2) 1 + U6 retention 1` | `api 20 + pnl-mismatch-audit 2 + trail retention (U2) 1 + U6 retention 1` |
| `+ U8 apply lock 1 + U8 feedback retention 1 + worker 36` | sem mudança |
| `+ db-bootstrap and db-migrate at start-up 2 + 3 reserved` e `for the superuser = 65` | `+ db-bootstrap and db-migrate at start-up 2 + 3 reserved` e `for the superuser = 67` |

A frase do fim do comentário passa de "65 of 100; the REAL overlay adds the TxSigner (up to 10), 75 of 100" a "67 of 100; the REAL overlay (worker 37) adds the TxSigner (up to 10), 78 of 100". Nenhuma variável do Compose muda. O pool `pnl-mismatch-audit` usa as mesmas variáveis de conexão (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`).

### RDS `app` na nuvem

| Situação | Conta | Limite | Observação |
|---|---|---|---|
| Regime, uma tarefa `api-worker` | API 20 fixas + `pnl-mismatch-audit` 2 fixas + worker de 2 (`minimum-idle: 2` em `application-worker.yml`) até 36 em uso + pools de retenção e trava de 0 a 4 (`minimumIdle = 0`) + `db-migrate` 1 durante a implantação + reserva 3 | cerca de 112 (fórmula padrão do RDS para `db.t4g.micro`; o grupo de parâmetros não define `max_connections`) | No máximo 66; na prática bem abaixo, porque o worker e as retenções só abrem o que usam. O TxSigner tem RDS próprio. |
| **Teto teórico** durante a atualização rolling (duas tarefas `api-worker` por alguns minutos, `deployment_maximum_percent = 200`) | hoje 2 × 60 + 3 = **cerca de 123**; com a U2, 2 × 62 + 3 = **cerca de 127** | cerca de 112 | Só acontece se as duas tarefas ocuparem ao mesmo tempo as 36 conexões do worker e os pools de retenção; não é a conta de regime. A U2 soma **4 conexões fixas** (2 por tarefa) a esse teto. O teto existe sem a U2 e não muda a decisão. Pendência do responsável (D-INF2). |

## Serviços de infraestrutura

| Serviço | Papel | Configuração | Observações |
|---|---|---|---|
| PostgreSQL da aplicação (RDS `app`, módulo `data`) | banco de dados | Sem mudança de classe (`db.t4g.micro`), armazenamento (20 GB `gp3`), grupo de parâmetros ou réplicas. +2 tabelas e +1 índice (`0023`, `0024`). +2 conexões fixas da API. | `real_entry_slot` cresce cerca de 20 MB por ano no piloto e o índice novo cerca de 40 bytes por lançamento: cabem no armazenamento atual (NFR1.19). |
| Hikari principal (API 20, worker 36) | pool de conexões | Sem mudança. 1 conexão por leitura do P&L REAL, pela thread do pool `pnl-mode-block`. | NFR1.16. |
| Pool `pnl-mismatch-audit` | pool de conexões (novo, dentro da API) | 2 conexões fixas, espera constante de 250 ms, login da aplicação, executor de 2 threads, gravação limitada a 200 ms, métricas pelo `setMetricRegistry`. Só a gravação do evento de divergência. | Não é bean `DataSource`; o `JdbcAuditEventPublisher` não muda (NFR3.15). |
| Pool de threads `pnl-mode-block` | executor | Sem mudança (8 threads, fila de 16). `GET /api/v1/real/pnl` passa a usá-lo, como C4. | NFR1.16. |
| Balanceador da API (ALB, módulo `services`) | load-balancer | Sem mudança de listener, regras, grupo de destino ou health check. | Nenhuma leitura da U2 entra na prontidão (NFR4.4). |
| Sidecar `metrics-agent` (CloudWatch Agent), famílias `api-worker` e `txsigner` | coleta de métricas | Leitura de `/actuator/prometheus` a cada 120 s. A lista de permissão ganha `pnl_real_reconciliation_mismatches_total`, sem dimensões; isso muda `CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT` das duas famílias. | D-INF1; `monitoring-design.md` [Q3]. No `txsigner` a métrica nunca existe; o filtro só fica igual. |
| CloudWatch Logs (grupos `/ecs/...` do `api-worker`) | agregação de logs | Sem mudança de grupo, retenção ou consultas salvas. | Os eventos de log da U2 levam `correlationId`, sem endereço nem assinatura completa (NFR3.7, NFR3.12). |
| Cache, fila, busca, CDN, DNS | — | Não usados. | 0 serviços novos (NFR1.17). |

## Infraestrutura compartilhada

| Recurso compartilhado | Unit dona | Units consumidoras | Fronteira de acesso |
|---|---|---|---|
| PostgreSQL da aplicação | já existente | U1, U2 e o intent `261113-trading-real-bot` | `real_entry_slot` é do módulo `ledger` e `real_risk_day_deferred` do `riskcontrol`; os outros módulos só leem pela interface pública (ArchUnit). Login da aplicação só com `SELECT, INSERT` nas duas. |
| Lista de permissão do agente (`app-metrics.tf`) e o sidecar das famílias `api-worker` e `txsigner` | já existente (Observability Setup do `261113`) | U2 (1 métrica) | Entrada nova só com decisão de estágio; esta é a Q3, com o alcance de D-INF1. |
| Arquivo `real-ops-alerts.yml` e `real-ops.tf` | U5 do `261113` | U2 (1 regra, 1 alarme) | Mesmo grupo `real-ops`, mesma paridade testada (`RealOpsAlarmParityTest`). |
| Runbook `docs/runbooks/real-operations.md` | U5 do `261113` | U2 (seções 8 e 9) | As seções novas não mudam as seções 1 a 7. |
| Ambiente dos contêineres da aplicação (`api_environment`, `worker_environment`) | já existente | U1 (três variáveis) | A U2 não toca. |

## Implicações de segurança

| Mudança | Implicação | Risco |
|---|---|---|
| `real_entry_slot` | Só inserção (gatilhos); login da aplicação só com `SELECT, INSERT`, descoberto pelo bloco `DO`; sem `TRUNCATE` para ninguém, nem para o dono. | Baixo; `RealEntrySlotAppendOnlyIT`, `RealEntrySlotGrantsIT` e `U2DatabaseRolesIT` (NFR3.4, NFR3.5). |
| `real_risk_day_deferred` | Login da aplicação só com `SELECT, INSERT`; `risk_retention` só com `SELECT, DELETE` para o expurgo. | Baixo; mesmo modelo de `real_risk_day`. |
| Índice `CONCURRENTLY` | Nenhuma permissão nova; o dono do esquema já cria índices. Um índice `INVALID` deixado por falha é apagado na repetição. | Baixo, com `Migration0023IndexValidIT` e `Migration0023IndexRestartIT`. |
| Pool `pnl-mismatch-audit` | Mesmo login da aplicação; nenhuma credencial nova; 2 conexões fixas a mais por tarefa. | Baixo no regime; ver o teto teórico na atualização rolling (D-INF2). |
| Revisão do sidecar nas duas famílias | Só a configuração do agente muda; nenhuma permissão IAM, segredo ou imagem nova. A revisão do `txsigner` só entra em serviço pelo passo do fundador com MFA; o papel de implantação do GitHub continua sem tocar no TxSigner. | Baixo (D-INF1). |
| Métrica e alarme | Métrica sem dimensões e sem dado pessoal; nenhuma permissão IAM nova (o agente já publica em `Eureka/App`). | Baixo. |
| Assinatura no evento de conflito | Só abreviada e em HMAC com a chave que já existe; `audit_event.tx_signature` vazio. | Baixo (NFR3.9). |
| REAL e PAPER | Nada da U2 soma os modos; nenhuma flag do REAL muda; o saque não depende de nada da U2. | Coberto por NFR4.1 a NFR4.3; `project.md`, Mandated. |

Nenhum controle de segurança é removido ou contornado.

## Sources

- `infrastructure-design-questions.md`: Q1 a Q5 e o resumo confirmado [Q1] [Q2] [Q3] [Q4] [Q5].
- `reviews/review-01.md` (R-01 a R-07) e as decisões do fundador de 2026-11-22 (D-INF1, D-INF2).
- `construction/u2-real-bot-pnl/nfr-design/`: `performance-design.md` (índice, `work_mem`, prazos, F-2), `scalability-design.md` (pools, conexões, crescimento, custo), `security-design.md` (changesets da `0023`, pool `pnl-mismatch-audit`, HMAC), `reliability-design.md` (`0024`, rollback, NFR2.3), `observability-design.md`, `logical-components.md`, `traceability.json`.
- `construction/u2-real-bot-pnl/nfr-requirements/`: `scalability-requirements.md` (NFR1.14 a NFR1.19, NFR8.1 a NFR8.4), `security-requirements.md` (NFR3.4 a NFR3.6, NFR3.9, NFR3.15), `performance-requirements.md` (NFR1.3, NFR1.4, NFR1.8, NFR1.12).
- `construction/u1-mode-skeleton/infrastructure-design/` (formato e chaves da U1).
- Código lido: `apps/api/src/main/resources/application.yml` (Hikari, `spring.liquibase.enabled: false`, `memetrade.pnl.by-mode`), `application-migrate.yml`, `application-worker.yml` (`minimum-idle: 2`), `ops/MigrationRunner.java`, `db/changelog/db.changelog-master.yaml`, `db/changelog/changes/0001` a `0022` (nenhum `CONCURRENTLY` nem `runInTransaction`), `0018-real-risk.sql` (bloco `DO` das permissões, `risk_retention`), `shared/config/RuntimeSettingsGuard.java`, `docker-compose.yml` (comentário da conta de conexões), `infra/docker-compose.real.yml` (worker com 37), `infra/ComposeFilesTest.java`, `db/MigrationsIT.java`, `db/AnonymizationMigrationIT.java` (rollback desde a `0015`), `infra/terraform/main.tf` (`api_environment`, `worker_environment`, `app_metric_declarations` passado ao módulo `services`), `modules/data/main.tf` (`db.t4g.micro`, grupo de parâmetros sem `max_connections`), `modules/services/api-worker.tf` (`deployment_maximum_percent = 200`, `ignore_changes = [task_definition]`, segredos), `modules/services/metrics-agent.tf` (`CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT` das famílias `api-worker` e `txsigner`).
- HikariCP 6.3.3 (`com/zaxxer/hikari/HikariConfig.class` no repositório Maven local, versão fixada pelo `spring-boot-dependencies` 3.5.16): `SOFT_TIMEOUT_FLOOR` = 250 ms, `setConnectionTimeout` e `validateNumerics`.
- `aidlc/spaces/default/intents/261113-trading-real-bot/construction/u4-real-execution/infrastructure-design/` (conta de conexões da nuvem, cerca de 112 na `db.t4g.micro`).
- `aidlc/spaces/default/memory/team.md`: Deployment (rollback só da imagem, expand-contract, `db-migrate`, passo do fundador com MFA); `project.md`: orçamento de US$ 350, CloudWatch Agent a 120 s.

## Assumptions & Open Questions

1. [assumption] Os nomes das chaves e dos testes novos são propostas; a Code Generation pode trocá-los mantendo valor, faixa, processo e o que cada teste prova.
2. **Pendência do responsável, anterior à U2 e fora do escopo dela (D-INF2):** o teto teórico do RDS `app` durante a atualização rolling é de cerca de 123 conexões hoje e cerca de 127 com a U2, contra cerca de 112 da `db.t4g.micro`. Não é a conta de regime (no máximo 66, na prática menos). A U2 soma 4 conexões fixas. Precisa ser decidido antes da primeira implantação na nuvem, entre: parâmetro `max_connections` no grupo de parâmetros (estático, pede reinício), uma classe de instância maior, ou `deployment_minimum_healthy_percent = 0` só para `api-worker`.
3. [assumption] O valor de cerca de 112 conexões vem da fórmula padrão do RDS, registrada na revisão da U4 do intent `261113-trading-real-bot`; ele deve ser confirmado na primeira conta provisionada (`SHOW max_connections`).
4. **Alinhamento para a Code Generation (A15):** o `security-design.md` do NFR Design e o `tech-stack-decisions.md` (NFR8.6) descrevem o changeset `0023-real-ledger-entry-wallet-created-idx` com `CREATE INDEX` comum. A Q1 deste estágio, confirmada depois, decide `CREATE INDEX CONCURRENTLY` com `runInTransaction:false`. Vale a Q1. A Code Generation alinha os dois textos.
5. **Alinhamento para a Code Generation (A16, D-INF1):** o NFR8.1 (`nfr-requirements/scalability-requirements.md` e `nfr-design/scalability-design.md`) e a Q2 deste estágio dizem que a definição de tarefa não muda. O texto passa a: "0 recursos de serviço novos; o ambiente e a configuração dos contêineres da aplicação não mudam; só a configuração do sidecar `metrics-agent` muda, com uma revisão das definições de tarefa `api-worker` e `txsigner`".
6. **Diferença anterior à U2:** o `ComposeFilesTest` soma 75 para o overlay do REAL, mas o arquivo põe o worker com 37 conexões, então o total real é 76 hoje. A U2 corrige a conta no teste (lendo o valor do arquivo) e o total passa a 78 de 100.
7. [assumption] O expurgo de `real_risk_day_deferred` é feito pelo mesmo trabalho de expurgo de `real_risk_day`, com a mesma conexão de `risk_retention`; nenhuma conexão nova.
