# Pipeline de CI/CD — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` (R-02 a R-04) e da decisão do fundador de 2026-11-23 (D-INF4, abaixo).

Nenhum job novo e nenhuma dependência nova entre jobs [Q3]. Os testes novos rodam nos jobs que já existem, e o spec `e2e/zz-paper-real-unico` ampliado já está na lista do `scripts/verify.sh`, sem linha nova. Dois arquivos de `.github/workflows/` recebem texto pronto, e só o responsável os muda (`team.md`, Way of Working):

- `load-test.yml`: o texto da U3, com 9 leituras e 7 portões, **substitui** o da U2;
- `ci.yml`: um passo a mais no job `frontend`, que roda os testes dos portões de carga (D-INF4).

Os dois textos ficam no `code-summary.md` da U3. O responsável os aplica uma vez só, e uma execução do `pnl-by-mode` avalia os portões da U2 e da U3, cada grupo separado (regra de leitura abaixo).

## Decisão do fundador (2026-11-23)

| ID | Decisão | Efeito neste desenho |
|---|---|---|
| D-INF4 | **Testes dos portões de carga na CI (R-02).** O texto pronto para o responsável acrescenta ao `ci.yml` um passo que roda os testes de `scripts/load`. | Passo no job `frontend` (abaixo). Hoje nenhum job, nem o `verify.sh` ou o `load-test.sh`, roda `scripts/load/*.test.mjs`; com o passo, um portão com erro falha a CI em vez de dar verde falso na carga. |

## Texto pronto do passo do `ci.yml` (D-INF4)

Entregue no `code-summary.md` da U3, para o responsável aplicar.

| Item | Decisão |
|---|---|
| Job | `frontend`. Ele já tem o `actions/setup-node` fixado pelo SHA (`49933ea5288caeca8642d1e84afbd3f7d6820020`, v4.4.0) com `node-version-file: .nvmrc` (Node 24), não precisa de Java e é o job mais curto com Node. O `contract` também tem Node, mas antes roda o Maven; o passo ficaria preso ao tempo dele. |
| Posição | Depois do passo "Jest with the 80% line floor", o último do job. |
| Dependências | Nenhuma: os testes só importam `node:test`, `node:crypto`, `node:fs` e os módulos de `scripts/load/`. Nenhuma instalação, ação, permissão ou variável nova. |
| Comando | `node --test "scripts/load/*.test.mjs"`, com o padrão entre aspas para o Node expandir. A forma `node --test scripts/load` (diretório) **falha no Node 24** (conferido nesta máquina, v24.22.1: "test failed" no próprio diretório); o padrão roda os 95 testes de hoje, todos verdes. |

```yaml
      - name: Gates of the load scenarios (scripts/load)
        run: node --test "scripts/load/*.test.mjs"
```

O passo usa o estilo dos passos do job (`name` em inglês e `run` de uma linha) e herda o `setup-node` já fixado. O `WorkflowsTest` não muda: não entra ação nem permissão.

## Estágios da CI e portões

| Job ou workflow | O que roda para a U3 | Portão | NFR |
|---|---|---|---|
| `backend` | `mvnw verify` da API: Spotless; testes novos de unidade e integração (`PnlCompareMetricsTest`, `PnlReadAuditMetricsTest`, `PnlViewsPrometheusIT`, `PnlViewsLogCaptureTest`, `PnlViewsCorrelationIT`, `ReadRefusalAuditLimitIT`, `BotNotFoundTimingParityIT`, `PnlReadsDoNotBlockCycleIT`, `PnlViewsReadOnlyIT`, `PnlCompareEqualsByModeIT`, `BotPnlLinesSumIT`, `PnlBotCountsTenantIT`, `PnlByModeMarketFailureIT`, `PnlCompareSideFailureIT`, `PnlViewsLatencyIT` só com registro do p95, `GuestOpenApiContractTest`, a regra ArchUnit `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS`); JaCoCo com piso de 80%; `docker compose config`; `promtool check rules` com as mesmas regras; `ComposeFilesTest` com a conta de 67 e 78, sem mudança | qualquer falha deixa o commit vermelho | NFR1.5, NFR1.10, NFR3.1, NFR3.9 a NFR3.17, NFR4.7, NFR4.9, NFR4.13, NFR7.2 a NFR7.4 |
| `frontend` | lint, formatação, tipos e Jest do `apps/web` (reducer de `/pnl`, `windowedRefresh`, `BotPnlLines`, `ComparisonView`, `PeriodSelector`, cliente com `signal` e `retry: false`), piso de 80%; depois que o responsável aplicar o texto de D-INF4, `node --test "scripts/load/*.test.mjs"` (inclusive `load-test-pnl-by-mode.test.mjs` com cada portão novo reprovando sozinho) | idem | NFR1.7, NFR1.8, NFR4.8 a NFR4.12, NFR5.1 a NFR6.2, NFR7.3 |
| `contract` | testes de contrato do OpenAPI e `check:api` do `apps/web` (`openapi-guest.yaml` com C4, `listBots`, C5 com o 503, C6) | quebra de contrato falha | NFR7.4, NFR7.8 |
| `smoke` | `bash scripts/verify.sh`, sem mudança no script; o spec `e2e/zz-paper-real-unico` ampliado com os 10 casos, último da lista | código de saída 0 | NFR7.6 |
| `admin` | sem mudança | idem | — |
| `txsigner` | sem mudança (a U3 não chama o TxSigner) | idem | NFR3.8 |
| `e2e` (só no push para `main`, depois dos seis jobs) | `bash scripts/verify.sh` de novo | código de saída 0 | NFR7.6 |
| workflow `security` | Gitleaks, CodeQL, OWASP Dependency-Check, `pnpm audit`; nenhuma dependência nova | Crítico ou Alto bloqueia | NFR3.7 |
| workflow `load-test` (manual) | cenário `pnl-by-mode` ampliado, depois que o responsável aplicar o texto pronto da U3 | portão do Build and Test da U2 e da U3 (abaixo) | NFR1.1, NFR1.3, NFR1.4, NFR1.6 |
| Testes do Terraform e Checkov (manuais) | não se aplicam: a U3 não muda `infra/terraform/` | — | NFR8.1 |

Os jobs, as dependências entre eles e a lista de arquivos do `promtool` no `ci.yml` não mudam; a única mudança é o passo de D-INF4 no `frontend`. Até o responsável aplicá-lo, a Code Generation roda `node --test "scripts/load/*.test.mjs"` à mão e registra a saída no `code-summary.md` da U3. Os nomes dos testes vêm do NFR Design e podem mudar na Code Generation, mantendo o que cada um prova.

## Ordem de construção e ponto de controle

| Passo | Conteúdo | Portão |
|---|---|---|
| 1 | Testes do núcleo financeiro antes do código (NFR7.7): igualdade dos lados de C6 com C4, soma exata das páginas de C5, estabilidade do cursor, limites do `RANGE`, nenhum terceiro número em C6 | escritos antes do código |
| 2 | Backend, contrato e `apps/web` (o contrato e o leitor no mesmo commit, NFR7.8) | `ci` verde no commit |
| 3 | Spec `e2e/zz-paper-real-unico` ampliado | `smoke` verde |
| 4 | `scripts/load/pnl-by-mode.mjs`, `scripts/load/load-test.mjs`, `scripts/load/load-test-pnl-by-mode.test.mjs` e, no `code-summary.md` da U3, o texto pronto completo do `load-test.yml` (que diz substituir o da U2) e o do passo do `ci.yml` (D-INF4). O `code-summary.md` da U2 **não** é editado: a linha de ponteiro do alinhamento A6 do NFR Design sai (R-03) | `ci` verde no commit; `node --test "scripts/load/*.test.mjs"` verde |
| Build and Test | execução verde do `pnl-by-mode` ampliado no SHA candidato | abaixo |

Todos os pontos de controle e implantações usam só um commit com a CI verde (`project.md`, Mandated). O ponto de controle é `bash scripts/verify-checkpoint.sh`.

## Teste de carga e portão do Build and Test

| Item | Decisão |
|---|---|
| Onde fica o texto pronto | O texto completo fica no `code-summary.md` da Code Generation da U3, com a frase de que ele substitui o texto da U2 [Q3]. O `code-summary.md` da U2 não é editado (R-03). Alinhamento registrado: o NFR1.6 dizia que o texto ampliado ficava no `code-summary.md` da U2; vale a Q3, sem edição do artefato da U2. Se o responsável aplicar o texto da U2 antes, o da U3 só ajusta o comentário do topo, porque os portões estão em `scripts/load/`. |
| O que o texto da U3 contém | Tudo o que o texto da U2 já trazia (as opções `real`, pendência do `261113`, e `pnl-by-mode`; `timeout-minutes` de 75 para `real`, 90 para `pnl-by-mode` e 360 para os demais), mais o comentário do topo do `pnl-by-mode` reescrito com o ciclo de 9 leituras (C4 `ALL`, `GET /api/v1/real/pnl`, C4 `RANGE` 30 e 366 dias, C5 1ª página e página seguinte, C6 `ALL`, `RANGE` 30 e 366 dias) e os 7 portões novos. |
| O que não muda no workflow | As ações fixadas pelos mesmos SHAs; `permissions: contents: read`; nenhuma variável, segredo ou etapa nova; o tempo máximo de 90 min do `pnl-by-mode`; o artefato `load-test-report`. |
| Quem aplica | Só o responsável, uma vez só (`team.md`, Way of Working). A Code Generation não edita `.github/workflows/`. |
| Quem dispara | O fundador, por `gh workflow run load-test -f scenario=pnl-by-mode`, no SHA candidato e com o `ci` verde. |
| Portão | **Uma execução do `pnl-by-mode` serve às duas Units** [Q3], com o `load-test-report.json` e os relatórios `docs/perf/` anexados ao registro das duas etapas. Sem o texto aplicado ou sem a execução, as duas Units ficam pendentes. |
| Regra de leitura do relatório (R-04) | O relatório separa os portões em dois grupos, cada um com o próprio resultado: **U2** (C4 `ALL`, atribuição `PNL`, divergências, `UNAVAILABLE` por causa, pool, memória, índice da `0023`) e **U3** (as 7 leituras novas). Os dois grupos são avaliados separados. Grupo U2 verde fecha o portão de carga da U2, mesmo com o grupo U3 vermelho; grupo U3 vermelho deixa só a U3 pendente, até uma nova execução verde do grupo U3. Grupo U2 vermelho deixa a U2 pendente, e a U3 também, porque as leituras da U3 rodam sobre a mesma passada REAL da U2. As respostas diferentes de 200 e o `UNAVAILABLE` são atribuídos ao grupo da leitura (pelo rótulo) que os recebeu. O `pnl-by-mode.mjs` grava o resultado de cada grupo no relatório, e o `load-test-pnl-by-mode.test.mjs` cobre os quatro casos (os dois verdes, só U3 vermelho, só U2 vermelho). |
| Critérios de falha da U3 | p95 acima de 1.000 ms em qualquer uma das 7 leituras novas (por rótulo no cliente); qualquer resposta diferente de 200 (NFR1.6). |
| Critérios de falha da U2 (sem mudança de limite) | p95 de C4 `ALL` acima de 1.000 ms; atribuição `PNL` acima de 500 ms; qualquer divergência; `UNAVAILABLE` por `TIMEOUT` acima de 0,5%; qualquer outra causa; pool pendente por mais de 10 s; contêiner acima de 900 MB; leitura REAL sem o índice da `0023`. |
| Semente | A da U2, com uma condição a mais: cada pessoa tem pelo menos 1 par PAPER e REAL de mesmo `templateId`, para C6 (só nos perfis `test,loadtest`). |
| Runner sem fôlego | Vale a redução da U2: o número de pessoas cai pelos dados medidos, nunca abaixo de 5, registrado; a meta de 1.000 ms nunca baixa. |
| Custo do Actions | Uma execução de 45 a 70 min, para as duas Units, na cota do repositório privado (`team.md`, Hospedagem). |

## Artefatos

| Artefato | Origem | Marca |
|---|---|---|
| Imagem `memetrade-api` (API, worker e `db-migrate`) | `Dockerfile` que já existe | SHA do commit |
| Imagem `web` | `Dockerfile` que já existe | SHA do commit |
| Relatório de carga | artefato `load-test-report` do workflow, retenção de 30 dias (`load-test-report.json` e `docs/perf/u3-pnl-views-<data>.md`) | SHA do commit |

Nenhum artefato, repositório de imagem ou registro novo. A imagem não leva migração nova.

## Implantação e promoção

O `deploy.yml` (manual, GitHub Environments `devnet` e `mainnet`, OIDC) continua uma pendência do responsável e ainda não existe em `.github/workflows/`. Quando existir, a U3 segue o fluxo de `team.md`, mais curto que o da U2:

| Passo | Ação | Portão |
|---|---|---|
| 1 | Commit de `main` com `ci` e `security` verdes e o Build and Test da U2 e da U3 aprovado (com a execução verde do `pnl-by-mode`) | `project.md`, Mandated |
| 2 | Terraform: nada a aplicar; o `terraform plan` sai sem mudança | plano vazio |
| 3 | Passo do fundador com MFA (TxSigner): nada muda no TxSigner pela U3; o fundador só confere a prontidão, como em toda implantação | `team.md`, Deployment |
| 4 | Tarefa `db-migrate`: roda como sempre e não aplica nada novo | código de saída 0 |
| 5 | Atualização rolling de `api-worker` com a imagem nova (tarefa nova saudável antes de a antiga sair), circuit breaker com volta automática | prontidão em `/actuator/health/readiness`, sem mudança |
| 6 | Smoke e janela de observação de 10 minutos; na devnet, a repetição das leituras do NFR1 com o limite de 1.000 ms, registrada em `docs/perf/` | NFR1.6 (repetição na devnet com o responsável, nos estágios de operação) |
| 7 | Devnet primeiro, depois o mesmo SHA na mainnet, com a aprovação do fundador no Environment `mainnet` | NFR8.3 |

Nenhuma flag do REAL muda nesta implantação.

## Rollback

| Passo | Ação |
|---|---|
| 1 | Se o REAL estiver ligado no ambiente e houver risco ao REAL, desligar primeiro o flag do REAL ou acionar o kill switch, como em todo rollback (`team.md`, Deployment). A U3 só lê o P&L e não toca em ordens; o saque continua disponível. |
| 2 | Voltar só a imagem de `api-worker` (e a do `web`, se trocada junto) para a tag anterior, pelo `rollback-runbook.md` do intent `261113-trading-real-bot` [Q1]. |
| 3 | Nada mais a desfazer: 0 migrações, 0 variáveis, 0 recursos Terraform, 0 revisões do sidecar. Os eventos de auditoria já gravados ficam na trilha (só inserção). |
| 4 | A implantação de volta só termina quando o smoke passa. |

## Segredos na CI/CD

| Item | Decisão |
|---|---|
| Segredos novos | Nenhum. |
| Segredos efêmeros do `verify.sh` e do `load-test.sh` | Os de hoje: sem `.env`, os scripts geram segredos por execução. |
| Variáveis novas nos workflows | Nenhuma; o texto pronto da U3 não acrescenta variável. |
| Credencial AWS | OIDC no `deploy.yml`, quando existir; nenhuma chave longa. |
| Proteção de segredos | Gitleaks no hook `.githooks/pre-commit` e no workflow `security`, sem mudança. |

## Sources

- `infrastructure-design-questions.md` desta pasta: Q1 e Q3 e o resumo confirmado [Q1] [Q3].
- `reviews/review-01.md` (R-02 a R-04) e a decisão do fundador de 2026-11-23 (D-INF4).
- Execução local, nesta máquina (Node v24.22.1): `node --test scripts/load` falha no diretório; `node --test "scripts/load/*.test.mjs"` passa com 95 testes.
- `construction/u3-pnl-views/nfr-requirements/performance-requirements.md`: NFR1.1, NFR1.3 a NFR1.6 (D-NFR2: 9 leituras, 7 portões, fechamento); `reliability-requirements.md`: NFR7.1 a NFR7.8.
- `construction/u3-pnl-views/nfr-design/`: `logical-components.md` (ordem de construção, A5, A6), `reliability-design.md` (testes), `observability-design.md` (testes das métricas e dos logs), `security-design.md` (testes de auditoria e paridade).
- `construction/u2-real-bot-pnl/infrastructure-design/cicd-pipeline.md` (formato, fluxo, critérios da U2) e `construction/u2-real-bot-pnl/code-generation/code-summary.md` (texto pronto da U2, com `real` e `pnl-by-mode`, ainda não aplicado).
- Arquivos lidos: `.github/workflows/ci.yml` (jobs, `e2e` depois dos seis), `.github/workflows/load-test.yml` (sem `real` nem `pnl-by-mode` hoje), lista de `.github/workflows/` (sem `deploy.yml`), `scripts/verify.sh` (já com `e2e/zz-paper-real-unico`), `scripts/load-test.sh` (já aceita `real` e `pnl-by-mode`), `scripts/load/pnl-by-mode.mjs` (ciclo de 2 leituras da U2).
- `aidlc/spaces/default/memory/team.md`: Way of Working (workflows do responsável, Hospedagem), Deployment; `project.md`: Mandated (CI verde), Testing Posture (spec `zz-` por último).

## Assumptions & Open Questions

1. [assumption] Os nomes dos testes vêm do NFR Design; a Code Generation pode trocá-los mantendo o que cada um prova.
2. A execução do `pnl-by-mode` depende de o responsável aplicar o texto pronto da U3; até lá, a U2 e a U3 ficam pendentes no Build and Test.
3. [assumption] Se o responsável aplicar antes o texto da U2, o texto da U3 continua sendo o arquivo inteiro a aplicar; a única diferença que resta é o comentário do topo do `pnl-by-mode`, porque o roteiro das leituras fica em `scripts/load/`.
4. Desvio registrado na decisão D-INF4: o fundador citou o comando `node --test scripts/load`; o texto pronto usa `node --test "scripts/load/*.test.mjs"`, porque a forma com diretório falha no Node 24 do `.nvmrc`. Os testes rodados são os mesmos.
