# Pipeline de CI/CD — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Revisado depois de `reviews/review-01.md` (R-01, R-03, R-05, R-07) e das decisões do fundador D-INF1 e D-INF2 (`infrastructure-specification.md`).

A U2 não muda os jobs da CI. Os testes novos rodam no job `backend` que já existe, a regra nova de alerta entra num arquivo que o `promtool` do `ci.yml` já confere, e o `scripts/verify.sh` não muda [Q4]. O único arquivo de `.github/workflows/` que muda é o `load-test.yml`, e quem o muda é o responsável do projeto, com o texto pronto do NFR1.9 (`team.md`, Way of Working). O Build and Test da U2 só fecha com uma execução verde do cenário `pnl-by-mode` (D-NFR3).

## Estágios da CI e portões

| Job ou workflow | O que roda para a U2 | Portão | NFR |
|---|---|---|---|
| `backend` | `mvnw verify` da API: Spotless; testes de unidade e de integração novos (`RealCostParityTest`, `RealPnlPropertyTest` ≤ 60 s, `RealLedgerNetworkOrderPlanIT`, `RealEntrySlotAppendOnlyIT`, `RealEntrySlotGrantsIT`, `Migration0023AdditiveIT`, `Migration0023IndexValidIT`, `Migration0023IndexRestartIT` (recomeço depois de um índice `INVALID`), `U2DatabaseRolesIT` (bloco `DO` das permissões), `AnonymizationMigrationIT` (passa a desfazer e reaplicar a `0023` e a `0024`, inclusive o `DROP INDEX CONCURRENTLY`), `RecordSlotIT`, `AttributionFailureGateIT`, `DaySnapshotDeferredIT`, `ReconciliationAuditLimitIT`, `RealAttributionMeasurementIT` só com registro do p95, `U2MetricsExportIT` (inclusive `hikaricp_connections_pending{pool="pnl-mismatch-audit"}`), `U2LogCaptureTest`, `CorrelationParityIT`, o teste de ligação das chaves novas e as regras ArchUnit); JaCoCo com piso de 80%; enforcer; `docker compose config`; `promtool check rules` com 14 regras em `real-ops-alerts.yml`; `RealOpsAlarmParityTest` com `hasSize(14)`; `ComposeFilesTest` com a conta de 67 e, no overlay do REAL, 78 (o worker de 37 lido do arquivo) | qualquer falha deixa o commit vermelho | NFR1.7, NFR1.8, NFR1.18, NFR2.3, NFR2.7, NFR2.8, NFR2.14, NFR3.4 a NFR3.6, NFR7.1 a NFR7.3 |
| `frontend` | lint, formatação, tipos e Jest do `apps/web` (`PnlModeBlock`, BR10.4), piso de 80% | idem | NFR7.2, NFR7.5 |
| `contract` | testes de contrato do OpenAPI e `check:api` do `apps/web` (textos do contrato, BR5.6) | quebra de contrato falha | NFR7.5 |
| `smoke` | `bash scripts/verify.sh`, sem mudança, com `e2e/zz-paper-real-unico` | código de saída 0 | NFR7.6 |
| `admin` | sem mudança | idem | — |
| `txsigner` | sem mudança (a U2 não chama o TxSigner) | idem | NFR3.10 |
| `e2e` (só no push para `main`, depois dos seis jobs) | `bash scripts/verify.sh` de novo | código de saída 0 | NFR7.6 |
| workflow `security` | Gitleaks, CodeQL, OWASP Dependency-Check, `pnpm audit`; nenhuma dependência nova | Crítico ou Alto bloqueia | NFR8.5 a NFR8.9 |
| workflow `load-test` (manual) | cenário novo `pnl-by-mode`, depois que o responsável aplicar o texto do NFR1.9 | portão do Build and Test da U2 (abaixo) | NFR1.6, NFR1.9, NFR1.25 |
| Testes do Terraform e Checkov (manuais) | `terraform validate`, `terraform test` (`observability_metrics.tftest.hcl`) e Checkov em `infra/terraform/`, rodados à mão; levá-los à CI continua pendência do responsável | rodados antes do commit que muda `real-ops.tf` e `app-metrics.tf` | NFR2.14 |

Os jobs `backend`, `frontend`, `contract`, `smoke`, `admin`, `txsigner` e `e2e` e as dependências entre eles não mudam. A lista de arquivos do `promtool` no `ci.yml` não muda, porque a regra entra em `real-ops-alerts.yml`.

## Ponto de controle interno e ordem dos commits

| Passo | Conteúdo | Portão |
|---|---|---|
| 0 | Linha de base da barreira em `docs/perf/u2-barrier-baseline.md`, num commit próprio, antes de qualquer mudança na barreira | 3 execuções do `RealRiskMeasurementIT` na mesma máquina (NFR1.7) |
| Fase 1 | `0023`, `recordSlot`, fluxo na ordem da rede, `RealCostMethod`, `RealCostBasis`, leitura de custo `GATE`/`CYCLE`, `0024`, paridade com três oráculos | os sete itens no mesmo commit com o `ci` verde (NFR7.4) |
| Fase 2 | rateio, conferência, atribuição `PNL`, chaves do `application.yml`, pool `pnl-mismatch-audit`, `RuntimeSettingsGuard`, `ComposeFilesTest`, métricas, regra e alarme, `app-metrics.tf`, runbook, cenário `pnl-by-mode` e o texto pronto do `load-test.yml` entregue ao responsável | `ci` verde no commit |
| Build and Test | medianas de NFR1.4 e NFR1.5 e a execução verde de `pnl-by-mode` no SHA candidato | abaixo |

Todos os pontos de controle e implantações usam só um commit com a CI verde (`project.md`, Mandated).

## Teste de carga e portão do Build and Test

| Item | Decisão |
|---|---|
| Texto pronto | O texto completo do `.github/workflows/load-test.yml` está em `construction/u2-real-bot-pnl/nfr-requirements/performance-requirements.md`, NFR1.9. A Code Generation o entrega ao responsável, no mesmo commit em que o cenário entra em `scripts/load-test.sh` e `scripts/load/load-test.mjs` (com `scripts/load/pnl-plans.mjs`). |
| O que o texto muda | (1) a opção `pnl-by-mode`; (2) a opção `real`, que o `load-test-plan.md` do intent `261113-trading-real-bot` pediu e ainda não está no arquivo; (3) o comentário do topo; (4) `timeout-minutes` de 75 para `real`, 90 para `pnl-by-mode` e 360 para os demais. Todas as ações continuam fixadas pelo SHA; `permissions: contents: read` não muda; nenhuma variável nova. |
| Quem aplica | Só o responsável (`team.md`, Way of Working). A Code Generation não edita `.github/workflows/`. |
| Quem dispara | O fundador, por `workflow_dispatch`, com `scenario = pnl-by-mode`, no SHA candidato e com o `ci` verde. |
| Portão | O Build and Test da U2 só fecha com 1 execução verde, com o `load-test-report.json` e o `docs/perf/u2-real-bot-pnl-<data>.md` anexados ao registro da etapa. Sem o texto aplicado ou sem a execução verde, a Unit fica pendente (NFR1.25, D-NFR3). |
| Critérios de falha | p95 de C4 acima de 1.000 ms; p95 da atribuição `PNL` acima de 500 ms; qualquer erro; qualquer divergência; blocos REAL `TIMEOUT` acima de 0,5%; qualquer outro bloco REAL `UNAVAILABLE`; pool pendente por mais de 10 s; contêiner acima de 900 MB; leitura do ledger REAL sem o índice `real_ledger_entry_wallet_created_idx` (NFR1.6). |
| Runner sem fôlego | Se o runner (`ubuntu-24.04`, 2 vCPU) não aguentar 20 pessoas por pool, memória ou CPU, o número de pessoas simultâneas cai a partir dos dados medidos, nunca abaixo de 5, e a redução fica registrada. O volume por pessoa e a meta de 1.000 ms nunca baixam. C4 acima de 1.000 ms com o índice dispara o plano B de NFR8.11, que volta ao fundador. |
| `verify.sh` | Não muda [Q4]. |
| Custo do Actions | Uma execução de 45 a 70 min por Build and Test, na cota do repositório privado (`team.md`, Hospedagem). |

## Artefatos

| Artefato | Origem | Marca |
|---|---|---|
| Imagem `memetrade-api` (API, worker e `db-migrate`) | `Dockerfile` que já existe | SHA do commit (`BUILD_SHA`) |
| Imagem `web` | `Dockerfile` que já existe | SHA do commit |
| Migrações `0023` e `0024` | dentro da imagem `memetrade-api` (`classpath:db/changelog/`) | a mesma imagem |
| Relatório de carga | artefato `load-test-report` do workflow, retenção de 30 dias | SHA do commit |

Nenhum artefato, repositório de imagem ou registro novo.

## Implantação e promoção

O `deploy.yml` (manual, GitHub Environments `devnet` e `mainnet`, OIDC) continua uma pendência do responsável e ainda não existe em `.github/workflows/`. Quando existir, a U2 segue o fluxo de `team.md`:

| Passo | Ação | Portão |
|---|---|---|
| 1 | Commit de `main` com `ci` e `security` verdes e o Build and Test da U2 aprovado (inclusive a execução verde de `pnl-by-mode`) | `project.md`, Mandated |
| 2 | O fundador aplica o Terraform: a métrica na lista de permissão e o alarme. A lista muda `CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT` do sidecar `metrics-agent`, então o `apply` registra **duas revisões novas de definição de tarefa**, `api-worker` e `txsigner`, só do sidecar. Nenhuma entra em serviço sozinha (`ignore_changes = [task_definition]`): a do `api-worker` entra no passo 5, a do `txsigner` no passo 3. A ordem em relação à imagem não importa: sem a imagem nova o contador não existe e o alarme fica `OK` com `notBreaching`. | `terraform plan` revisado pelo fundador, com as duas revisões só do sidecar (D-INF1) |
| 3 | Passo do fundador com MFA (TxSigner): o código e a imagem do TxSigner não mudam, mas o fundador coloca em serviço a revisão nova da definição de tarefa `txsigner` do passo 2, que muda só o sidecar `metrics-agent`, e confere a prontidão. O papel de implantação do GitHub continua sem tocar no TxSigner. | `team.md`, Deployment; D-INF1 |
| 4 | Tarefa avulsa `db-migrate` com a imagem nova: aplica a `0023` (com o índice `CONCURRENTLY`) e a `0024`, enquanto a imagem antiga continua no ar | código de saída 0; falha para a implantação |
| 5 | Atualização rolling de `api-worker` com a imagem nova e a revisão do sidecar do passo 2 (tarefa nova saudável antes de a antiga sair), circuit breaker com volta automática. O teto teórico de conexões do RDS durante a troca é pendência do responsável (D-INF2), a decidir antes da primeira implantação na nuvem | prontidão em `/actuator/health/readiness`, sem mudança |
| 6 | Smoke e janela de observação de 10 minutos; uma leitura de C4 com 100 pedidos e limite de 1.000 ms, registrada em `docs/perf/` (só na devnet) | NFR1.24 |
| 7 | Devnet primeiro, depois o mesmo SHA na mainnet, com a aprovação do fundador no Environment `mainnet` | NFR8.3, NFR8.4 |

| Ambiente | Recebe a U2? | Quem aprova |
|---|---|---|
| Local e CI | sim | portões da CI |
| Devnet (sem usuários) | sim, testes do fundador e pré-produção do mesmo SHA | fundador |
| Mainnet | sim, junto com a U1, só depois da devnet no mesmo SHA | fundador, revisor obrigatório do Environment `mainnet` |

Nenhuma flag do REAL muda nesta implantação; mudar flag é uma implantação separada (`team.md`, Deployment).

## Rollback

| Passo | Ação |
|---|---|
| 1 | Se o REAL estiver ligado no ambiente, desligar primeiro o flag do REAL (`memetrade.real.enabled` e `txsigner.real.enabled`) ou acionar o kill switch REAL. O saque continua disponível durante todo o rollback (`project.md`, Mandated). |
| 2 | Voltar só a imagem de `api-worker` para a tag anterior, pelo `rollback-runbook.md` do intent `261113-trading-real-bot`. |
| 3 | Não reverter a `0023` nem a `0024`: a imagem anterior não usa `real_entry_slot`, `real_risk_day_deferred` nem o índice novo (`Migration0023AdditiveIT`). Em produção nenhuma migração volta. |
| 4 | Enquanto a imagem anterior estiver no ar, as confirmações não gravam slot. Na volta da U2, essas linhas aparecem como `SLOT_MISSING`, contadas em `ledger_real_slot_missing_total`, e seguem a ordem por herança (NFR2.4). Nada é preenchido depois. |
| 5 | O alarme, a métrica e as revisões do sidecar podem ficar: sem a imagem nova o contador não chega e o alarme fica `OK`; a imagem anterior roda com a revisão nova do sidecar sem diferença. |
| 6 | A implantação de volta só termina quando o smoke passa. |

### Falha no meio da criação do índice (seção 9 do runbook)

| Passo | Ação |
|---|---|
| 1 | O `db-migrate` termina com código diferente de zero; a atualização rolling não começa e a imagem antiga continua no ar. |
| 2 | Conferir no banco, com o login dono (`DB_OWNER_USER`): `select to_regclass('real_ledger_entry_wallet_created_idx') is not null as exists, (select indisvalid from pg_index where indexrelid = to_regclass('real_ledger_entry_wallet_created_idx')) as valid;`. `to_regclass` devolve `null` sem erro quando o índice não existe. `exists = true` e `valid = false` é o resto de uma criação interrompida; `exists = false` quer dizer que a falha veio antes do índice. |
| 3 | Rodar a tarefa `db-migrate` de novo. O changeset não foi marcado como aplicado; o `DROP INDEX CONCURRENTLY IF EXISTS` apaga o resto e o índice é criado de novo. |
| 4 | Se a falha se repetir, ler a causa no grupo de log do `db-migrate` (por exemplo, `lock`, disco ou conexão) antes de tentar outra vez. Nunca criar o índice à mão fora do Liquibase. |
| 5 | Se a trava do changelog ficar presa depois de o processo morrer (`select locked, lockgranted, lockedby from databasechangeloglock;` mostra `locked = true` e nenhuma tarefa `db-migrate` está rodando no ECS), liberar só ela, com o login dono: `update databasechangeloglock set locked = false, lockgranted = null, lockedby = null where id = 1;`. Depois repetir o passo 3. O comando é o mesmo que o `releaseLocks` do Liquibase executa; a imagem não traz a linha de comando do Liquibase. |

## Segredos na CI/CD

| Item | Decisão |
|---|---|
| Segredos novos | Nenhum [Q5]. O HMAC do conflito usa a chave que já existe (`MEMETRADE_AUDIT_WALLET_HMAC_KEY`). |
| Segredos efêmeros do `verify.sh` e do `load-test.sh` | Os de hoje: sem `.env`, os scripts geram segredos por execução. |
| Variáveis novas nos workflows | Nenhuma; o texto do NFR1.9 não acrescenta variável. |
| Credencial AWS | OIDC no `deploy.yml`, quando existir; nenhuma chave longa; `id-token: write` só nesse workflow. |
| Proteção de segredos | Gitleaks no hook `.githooks/pre-commit` e no workflow `security`, sem mudança. |

## Sources

- `infrastructure-design-questions.md`: Q1, Q4 e Q5 e o resumo confirmado [Q1] [Q4] [Q5].
- `construction/u2-real-bot-pnl/nfr-requirements/performance-requirements.md`: NFR1.6 a NFR1.9 (texto pronto do `load-test.yml`), NFR1.25, D-NFR3; `reliability-requirements.md`: NFR2.3, NFR7.4, NFR7.6.
- `construction/u2-real-bot-pnl/nfr-design/`: `logical-components.md` (ordem de construção), `reliability-design.md` (ponto de controle interno, rollback, testes), `performance-design.md` (portão de carga), `observability-design.md` (testes da paridade).
- `construction/u1-mode-skeleton/infrastructure-design/cicd-pipeline.md` (formato, fluxo de implantação).
- Arquivos lidos: `.github/workflows/ci.yml` (jobs, lista do `promtool`, `e2e` depois dos seis jobs), `.github/workflows/load-test.yml` (opções atuais, sem `real` nem `pnl-by-mode`), lista de `.github/workflows/` (sem `deploy.yml`), `apps/api/src/main/java/com/memetrade/ops/MigrationRunner.java`, `infra/terraform/modules/services/one-off.tf` (`db-migrate`), `docs/runbooks/real-operations.md`.
- `aidlc/spaces/default/memory/team.md`: Way of Working (workflows do responsável, Hospedagem), Testing Posture (Testes da IaC), Deployment; `project.md`: Mandated (CI verde, saque sempre disponível).

## Assumptions & Open Questions

1. [assumption] Os nomes dos testes novos vêm do NFR Design; a Code Generation pode trocá-los mantendo o que cada um prova.
2. A seção 9 do runbook ("Índice da migração 0023") leva os passos de "Falha no meio da criação do índice" acima; ela está na tabela de arquivos que mudam de `monitoring-design.md` e na rastreabilidade (NFR1.18, NFR2.3).
3. [assumption] O acesso do fundador ao banco da nuvem para os passos 2 e 5 da recuperação usa o caminho de manutenção do `rollback-runbook.md` do intent `261113-trading-real-bot`; este estágio não cria caminho novo.
4. A execução de `pnl-by-mode` depende de o responsável aplicar o texto do NFR1.9; até lá, a U2 fica pendente no Build and Test, como o NFR1.25 já prevê.
