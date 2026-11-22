# Evidências — Practices Discovery (intent `261121-paper-real-unico`)

> **Versão integrada (Step 5), 2026-11-21T15:36:42Z, commit `600140ea20a51f8c6016e8c3f1f08109f411ea5c` (`main`).**
> O rascunho do Step 2 foi escrito em 2026-11-21T15:22:45Z, no commit `3497b2bc49052f01adb93de7094a05880532222b`.
> É uma re-execução brownfield, e a linha de base são as cinco seções afirmadas de `aidlc/spaces/default/memory/team.md`.
> Não houve revisões independentes (Step 3), porque os Collaborators estão off. A entrevista (Step 4) está em
> `practices-discovery-questions.md` (Q1 a Q8, com o resumo confirmado). As seções 1 a 3 registram a evidência como
> estava no rascunho; as divergências resolvidas estão na seção 4.

## 1. O que o líder inspecionou

| Fonte | Como | O que serviu para |
|---|---|---|
| Histórico do git | `git log --oneline -40`, `git branch -a`, tags anotadas, `git status` | Estratégia de branches, tags de release, convenção de commits, trabalho sem commit |
| GitHub (API) | `gh repo view`, `gh api .../environments`, `.../branches/main/protection`, `.../actions/permissions`, `security_and_analysis`, `gh run list -w ci -b main -L 30`, `gh pr list` | Visibilidade, Environments, proteção de branch, saúde da CI em `main` |
| Workflows (só leitura) | `.github/workflows/ci.yml`, `security.yml`, `load-test.yml`, `.github/dependabot.yml` | Jobs, gatilhos, permissões, versões das ações |
| Verificação (só leitura) | `scripts/verify.sh`, `scripts/verify-checkpoint.sh`, `apps/web/e2e/` | Comando do esqueleto e lista de specs |
| Infraestrutura | `infra/` (composições, `observability/`, `terraform/`), `.gitignore` | IaC existente e o que está sem commit |
| Testes transversais | `apps/api/src/test/java/com/memetrade/infra/WorkflowsTest.java` | Regras de integridade da CI verificadas por teste |
| Base de conhecimento do código | `code-structure.md`, `technology-stack.md`, `dependencies.md`, `code-quality-assessment.md`, `architecture.md`, `business-overview.md` (em `aidlc/spaces/default/codekb/eureka-trader-ai/`) | Layout, pilha, ferramentas, qualidade e riscos da área deste intent |
| Operação do intent `261113-trading-real-bot` | `operation/deployment-pipeline/` (`cd-config.md`, `deployment-strategy.md`, `deployment-pipeline-questions.md`), `operation/environment-provisioning/` (`environment-inventory.md`, `environment-provisioning-questions.md`), `operation/observability-setup/`, e o `aidlc-state.md` desse intent | Desenho da nuvem, do CD e da aprovação |
| CI Pipeline do intent `261113-trading-real-bot` | `construction/ci-pipeline/ci-config.md` | Troca das ações para Node 24 e proteção do download do Maven |
| Ideation deste intent | `ideation/approval-handoff/decision-log.md`, `initiative-brief.md`, `ideation/feasibility/constraint-register.md`, `ideation/intent-capture/intent-statement.md` | Decisão do ambiente único e restrições |
| Estado deste intent | `aidlc-state.md` | Escopo `feature`, Test Strategy `Standard`, Collaborators `off` |

## 2. O que a evidência confirma sem mudança

- **Trunk-based em `main`**: uma única branch (`main` e `origin/main`), nenhum PR no histórico, histórico linear.
- **Lockfiles e instalação congelada**: `pnpm install --frozen-lockfile` em todos os jobs; Node 24 em `.nvmrc`.
- **Ações fixadas por SHA e `permissions: contents: read`** nos três workflows; `WorkflowsTest` confere as duas
  regras e a ausência de `pull_request_target`.
- **Piso de 80%**: JaCoCo `check` na API e no TxSigner; `coverageThreshold.global.lines = 80` no Jest dos dois apps
  (`code-quality-assessment.md`, seção "Testes").
- **Segurança na CI**: Gitleaks com versão e checksum conferidos, CodeQL com bloqueio de nível `error`, OWASP
  Dependency-Check com `-DfailBuildOnCVSS=7`, `pnpm audit --audit-level high` nos dois apps, exceções em
  `security/exceptions.yml` com `SecurityExceptionsTest`.
- **Pré-commit com Gitleaks** (`.githooks/pre-commit`); `.env` e `.claude/settings.local.json` ignorados
  (`git check-ignore`).
- **Esqueleto**: `bash scripts/verify.sh` roda os 18 grupos de specs de `apps/web/e2e/`, inclusive
  `real-bot-wallet`, `real-swap`, `real-ops`, `real-ui`, `real-v1-exports` e `real-v2-exits`, com validador Solana
  local e KMS simulado.
- **Code Style**: Spotless com palantir-java-format, ESLint e Prettier, TypeScript `strict`, ArchUnit, tipos gerados
  do OpenAPI com `check:api` (`technology-stack.md`, "Qualidade e segurança"; `code-structure.md`, "Padrões de
  código").
- **Methodology `custom` e a Ordering afirmada**: a área deste intent (P&L REAL por bot) já cai em "P&L" do núcleo
  financeiro; o registro de restrições (CT-15) pede os cenários antes do código. Nenhuma evidência pede mudar a
  ordem.

## 3. Divergências entre a evidência e o `team.md` (perguntar na entrevista)

Cada linha abaixo **difere** do `team.md` afirmado ou abre um ponto que ele não cobre. O `team-practices.md` marca o
mesmo ID onde o texto depende da resposta.

### DIV-01 — Hospedagem: o repositório é público ⚠️ DIFERE

- **`team.md` diz**: "repositório privado no GitHub, com CI no GitHub Actions"; a cota gratuita de minutos ficava a
  confirmar.
- **Evidência**: `gh repo view rafaelcruz-net/eureka-trade-ai` devolve `"visibility":"PUBLIC"`, `isPrivate: false`;
  `allow_forking: true`. Em `security_and_analysis`, a varredura de segredos do GitHub, a proteção no push e os
  alertas de segurança do Dependabot estão `disabled` (gratuitos em repositório público). Em
  `actions/permissions`, `sha_pinning_required: false` (o GitHub permite exigir ações por SHA no repositório).
- **Por que importa**: o repositório público expõe o PRD, os ADRs (inclusive o modelo de ameaças da carteira do bot),
  os runbooks, as exceções de segurança e o Terraform das contas. Os workflows rodam em PRs de fork (`pull_request`),
  sem segredos, mas consumindo runners. Em compensação, os runners padrão passam a ser gratuitos, o que encerra a
  pergunta da cota.
- **Perguntar**: o repositório volta a ser privado ou continua público? Se continuar público: ligar a varredura de
  segredos nativa com proteção no push, os alertas do Dependabot e a exigência de SHA nas ações; exigir aprovação
  para runs de fork; confirmar que nada no Terraform ou nos runbooks identifica contas, domínios ou endereços de
  produção. Candidata C5 em `discovered-rules.md`.

### DIV-02 — Implantação: a nuvem já está desenhada, mas não aplicada ⚠️ DIFERE

- **`team.md` diz**: não há conta de nuvem, região nem orçamento; staging "a definir", implantado a cada merge em
  `main`; IaC e plataforma de CD "ficam para os estágios de infraestrutura"; "uma conta AWS nova é aberta para o KMS".
- **Evidência**:
  - `environment-provisioning-questions.md` Q1 = A (completar a IaC sem aplicar) e Q2 = A (AWS Organizations com
    conta de gerenciamento e contas membro `devnet` e `mainnet` separadas, SCP de região e de MFA);
    `environment-inventory.md`: todos os recursos "NÃO PROVISIONADO".
  - `deployment-pipeline-questions.md` Q1 a Q6 = A: rolling no ECS, quatro portões (CI verde, devnet, mainnet do
    fundador, convidados), GitHub Environments `devnet` e `mainnet` com o responsável como revisor obrigatório da
    `mainnet`, `deploy.yml` manual (`workflow_dispatch`) com OIDC, flags no Terraform, workflow entregue como texto.
  - `cd-config.md` 4.6 e `security-posture-review.md` SP-01: o TxSigner é implantado só pelo fundador, com MFA,
    antes do `deploy.yml`.
  - `project.md` (Corrections de 2026-11-20): orçamento de US$ 350 por conta; CloudWatch Agent a cada 120 s.
  - GitHub: `environments` devolve `total_count: 0`; não existe `.github/workflows/deploy.yml`.
  - A IaC já existe em `infra/terraform/` (raiz, `org/`, `modules/`, `environments/*.tfvars.example`, `tests/`).
- **Por que importa**: o `team.md` está desatualizado. Também muda o "staging": o desenho não implanta na devnet a
  cada merge, e sim o SHA escolhido, à mão.
- **Perguntar**: afirmar o desenho do intent `261113-trading-real-bot` como a prática de implantação (duas contas,
  Terraform, `deploy.yml` manual com Environments e OIDC, passo do fundador com MFA)? A devnet substitui o
  "staging", com implantação manual, ou queremos implantação automática na devnet a cada push em `main`? A região
  continua a definir? Candidatas C3 e C4.

### DIV-03 — Ambiente único dos usuários na mainnet ⚠️ DIFERE

- **`team.md` diz**: "Produção do beta PAPER: exige aprovação manual separada"; o rollout do REAL vai do validador
  local e da devnet "desde o primeiro Bolt" até a mainnet; não diz quem usa a devnet.
- **Evidência**: `decision-log.md` D2 ("o ambiente único dos usuários fica na mainnet"), D3 ("a devnet fica só para
  os testes do fundador", suposição aceita), D9 ("nenhum serviço AWS novo e nenhum custo novo");
  `intent-statement.md` ("no pedido, 'devnet' quer dizer simulado"); `constraint-register.md` CO-05.
- **Por que importa**: o PAPER passa a morar no mesmo ambiente de produção que o REAL (a mainnet). A "produção do
  beta PAPER" deixa de ser um ambiente à parte: a aprovação dela é a mesma do Environment `mainnet`. A devnet deixa
  de ser um ambiente de usuário e vira só pré-produção e testes do fundador.
- **Perguntar**: confirmar que nenhum usuário usa a devnet e que a aprovação da mainnet cobre o PAPER e o REAL.
  Confirmar que a decisão D3, que veio de uma suposição aceita, vira prática. Candidata C1.

### DIV-04 — CI: jobs e ferramentas que o `team.md` não cita; Node 24 não aplicado ⚠️ DIFERE

- **`team.md` diz**: a CI roda lint, formatação, tipos, Jest com piso e `check:api` dos dois apps, o backend e o
  `scripts/verify.sh`.
- **Evidência em `ci.yml`**:
  - job `txsigner` (`mvnw verify` do `apps/txsigner`, JaCoCo 80%, Spotless), do commit `4240ba9`;
  - passo `Alert rules (promtool)` no `backend`, que confere 6 arquivos de regras em `infra/observability/`;
  - job `e2e`, só no push para `main`, que roda o `scripts/verify.sh` de novo depois dos seis outros jobs;
  - workflow `load-test.yml`, só manual, com 11 cenários;
  - o `codekb` (`code-quality-assessment.md`, "Lint, formatação e CI") ainda não lista o job `txsigner`.
- **Node 24**: `construction/ci-pipeline/ci-config.md` (b) do intent `261113-trading-real-bot` decidiu trocar
  `actions/checkout`, `setup-java`, `cache`, `upload-artifact`, `setup-node`, `pnpm/action-setup` e
  `github/codeql-action` para as versões em Node 24 (CodeQL v3 → v4, que a própria ação descontinua em dezembro de
  2026), e (a) proteger o download do Maven. **Não foi aplicado**: os três workflows continuam com
  `actions/checkout@11bd719… # v4.2.2`, `setup-java@c5195ef… # v4.7.1`, `codeql-action@51f7732… # v3.29.5` etc.
  (todas `node20`). O estágio entregou o texto pronto, e só o responsável altera os workflows.
- **IaC sem CI**: `infra/terraform/tests/*.tftest.hcl` (6 arquivos), `terraform validate` e Checkov existem, mas
  nenhum workflow os roda (`grep terraform|checkov .github/workflows` vazio).
- **Perguntar**: atualizar a lista da CI no `team.md` com `txsigner`, `promtool` e `e2e`? Manter o job `e2e`, que
  repete a mesma suíte no push para `main`? Quando aplicar o texto do Node 24 e do Maven (antes da Construction
  deste intent?) Os testes do Terraform entram na CI?

### DIV-05 — `main` fica vermelha com frequência; sem proteção de branch ⚠️ DIFERE

- **`team.md` diz**: as mudanças entram em `main` "com a CI verde"; "nenhum merge acontece com a CI vermelha".
- **Evidência**: `gh api .../branches/main/protection` → `Branch not protected` (404). Nenhum PR. Dos últimos 30
  runs do `ci` em `main` (2026-11-13 a 2026-11-20), 18 terminaram em `failure` e 1 em `cancelled`; cada falha foi
  corrigida por um commit seguinte (por exemplo, `f5327ca` vermelho, `3497b2b` verde). O
  `scripts/verify-checkpoint.sh` aceita o run da CI como prova quando o `verify.sh` local morre.
- **Por que importa**: a prática real é push direto e correção para a frente; o texto afirmado promete outra coisa.
  Com a implantação manual pelo SHA verde (DIV-02), um `main` vermelho não chega a ambiente nenhum, mas o portão
  "CI verde antes de `main`" não existe.
- **Perguntar**: (a) afirmar o push direto com correção para a frente e "só se implanta SHA verde"; ou (b) proteger
  `main` e passar a usar branch curta com PR e CI verde antes do squash-merge. Candidata C6.

### DIV-06 — Permissão de escrita do `deploy.yml` contra o `WorkflowsTest` ⚠️ DIFERE

- **`team.md` diz**: cada workflow declara `permissions` mínimas, "por padrão, só leitura do conteúdo".
- **Evidência**: o `deploy.yml` de `cd-config.md` 4.4 declara `id-token: write` no job `deploy` (OIDC) e
  `actions: read` no `verify`. O `WorkflowsTest.everyWorkflowDeclaresReadOnlyPermissionsAndNeverUsesPullRequestTarget`
  exige `permissions` de topo igual a `{contents=read}` e recusa qualquer `: write` no texto de **todo** workflow.
  Ao criar o `deploy.yml`, o backend da CI fica vermelho.
- **Perguntar**: afirmar a exceção "`id-token: write` só no job de implantação que roda num GitHub Environment" e
  ajustar o teste para ela? Candidata C4.

### DIV-07 — Resultados da operação do intent `261113-trading-real-bot` sem commit ⚠️ NÃO COBERTO

- **Evidência**: `git status` mostra 36 arquivos alterados e 30 entradas novas (arquivos ou pastas) fora de `aidlc/`, entre eles o Terraform
  (`infra/terraform/org/`, `modules/delivery/`, `modules/services/*.tf`, `modules/observability/*.tf`,
  `environments/`, `tests/`), `infra/docker-compose.loadtest-real.yml`, runbooks em `docs/runbooks/`,
  `security/exceptions.yml`, `apps/web/package.json`, `apps/admin/package.json`, testes novos da API e
  `scripts/load/*`. O último commit é de 2026-11-20. O intent `261113-trading-real-bot` está em
  `performance-validation`. Há também uma pasta solta `apps/web/aidlc/` não rastreada.
- **Por que importa**: a regra de commit do `team.md` (commit pedido pelo responsável) não diz o que fazer com o
  resultado de um intent ainda aberto quando outro começa. A Construction deste intent vai partir de uma árvore
  misturada, e o `verify-checkpoint.sh` recusa a CI como prova com mudanças fora de `aidlc/`.
- **Perguntar**: fazer o commit do trabalho do intent `261113-trading-real-bot` antes da Construction deste intent?
  Afirmar a prática "um intent não começa a Construction com mudanças de outro intent sem commit"?

### DIV-08 — Layout do repositório ⚠️ DIFERE

- **`team.md` diz**: `apps/web/`, `apps/api/`, `infra/` (Docker Compose local; IaC futura), `docs/`.
- **Evidência**: existem também `apps/admin/`, `apps/txsigner/`, `infra/terraform/`, `infra/observability/`,
  `infra/kms-sim/`, `infra/solana-fixtures/`, `docs/runbooks/`, `security/`, `scripts/verify-checkpoint.sh`.
  O `code-structure.md` do `codekb` ainda não mostra `apps/txsigner/` nem `infra/terraform/`, e o
  `reverse-engineering-timestamp.md` diz que `botwallet` e `apps/txsigner` nunca foram analisados a fundo.
- **Perguntar**: só confirmar a atualização do layout (fato, sem decisão).

### DIV-09 — Tags de release já usadas sem ambiente ⚠️ DIFERE

- **`team.md` diz**: "Releases são marcadas por tags em `main` quando houver um ambiente para implantar."
- **Evidência**: tags anotadas `v2026.11.08-1` ("Local release …", em `8fd7a7b`) e `v2026.11.12-1` ("Release local …
  intent 261109-termos-lgpd …", em `9b2695c`). Nenhuma tag para o intent `261113-trading-real-bot`. Nenhum GitHub
  Release.
- **Perguntar**: afirmar o formato `vAAAA.MM.DD-N` e a tag ao fechar cada intent (release local), ou só quando
  houver implantação? Qual é a relação entre a tag e o SHA implantado pelo `deploy.yml`?

### DIV-10 — Convenção das mensagens de commit ⚠️ NÃO COBERTO

- **Evidência**: as 40 últimas mensagens estão em inglês, com prefixo `feat:`, `fix:`, `test:`, `chore:`, `ci:` ou
  `docs:`, e citam a Unit ou o intent.
- **Perguntar**: afirmar como prática ou deixar como hábito sem regra?

### DIV-11 — Verificação do ponto de controle pela CI ⚠️ NÃO COBERTO

- **Evidência**: `scripts/verify-checkpoint.sh` (commit `f394d48`) roda o `verify.sh` local e, se a execução local
  morrer, aceita o run verde do `ci` do mesmo commit, com a árvore limpa fora de `aidlc/` e o commit no remoto. A
  memória do usuário também registra "verify local primeiro; se morrer por memória, vale a execução ci do GitHub".
- **Perguntar**: levar essa regra para o `team.md` (Walking Skeleton)?

### DIV-12 — (sem divergência) Observabilidade

- Regras Prometheus locais conferidas por `promtool` na CI e alarmes CloudWatch no Terraform; as decisões de 120 s
  e de US$ 350 já estão em `project.md`. Nada a perguntar além da DIV-04.

### DIV-13 — Test Strategy deste intent ⚠️ DIFERE

- **`team.md` diz**: "a Test Strategy do workflow é `Comprehensive`" (nos intents anteriores).
- **Evidência**: `aidlc-state.md` deste intent: `Test Strategy: Standard` (escopo `feature`).
- **Perguntar**: manter `Standard` neste intent ou voltar a `Comprehensive`, como o `team.md` registra? (O piso de
  80% e os testes obrigatórios por risco valem nos dois casos.)

### DIV-14 — Fatia fina deste intent ⚠️ NÃO COBERTO

- **Evidência**: `constraint-register.md` CO-04 ("um spec de ponta a ponta novo, para a visão única com um bot PAPER
  e um bot REAL, entra no `verify.sh` pelo responsável"); D11 (ordem: cálculo, API, tela, ciclo); D12 (blocos por
  modo, sem total geral, aviso no simulado); `code-quality-assessment.md`, "Riscos para o intent
  261121-paper-real-unico" (P&L REAL não lê as vendas condicionais; dois custos médios REAL; `bot.mode` sempre
  `PAPER`).
- **Perguntar**: confirmar a fatia proposta no `team-practices.md` e o teste obrigatório por risco "a visão única
  nunca soma os modos". Candidata C2.

### DIV-16 — Alertas de segurança do Dependabot desligados ⚠️ DIFERE

- **`team.md` diz**: versões novas esperam alguns dias, "salvo correções de segurança".
- **Evidência**: `.github/dependabot.yml` tem `cooldown.default-days: 5` e o comentário "security updates open at
  once", mas `dependabot_security_updates` está `disabled` no repositório; sem essa opção, o Dependabot não abre PR
  de correção de segurança.
- **Perguntar**: ligar as atualizações de segurança do Dependabot?

(Não há DIV-15: o número ficou reservado para a Ordering, que a evidência não pede mudar.)

## 4. Decisões da entrevista e fatos posteriores ao rascunho

### 4.1 Respostas (todas opção A; resumo confirmado com "Looks correct")

| Pergunta | Divergência | Decisão |
|---|---|---|
| Q1 (crítica) | DIV-01, DIV-16 | O repositório volta a ser privado, e os alertas do Dependabot são ligados. |
| Q2 (crítica) | DIV-05 | Os commits continuam entrando direto em `main`, sem proteção de branch. A `main` pode ficar vermelha por pouco tempo, e a correção vem no commit seguinte, antes de qualquer outra mudança. Os pontos de controle e as implantações só usam um commit com a CI verde. |
| Q3 | DIV-02 | O desenho do intent `261113-trading-real-bot` passa a ser a prática de implantação: duas contas AWS, Terraform, `deploy.yml` manual com Environments e OIDC, e o passo do TxSigner feito pelo fundador com MFA. Ele ainda não foi aplicado. A devnet recebe implantação manual, e não "staging a cada merge". |
| Q4 | DIV-03 | Os usuários usam só a mainnet. A devnet fica para os testes do fundador e para a pré-produção. A produção do beta PAPER passa a ser o ambiente mainnet. |
| Q5 | DIV-04, DIV-06 | A CI é registrada como ela é hoje: os jobs `txsigner` e `e2e`, o `promtool` e o `load-test.yml` manual. Ficam como pendências do responsável o Node 24, o CodeQL v4, a proteção do download do Maven, `terraform fmt/validate/test`, o Checkov e o ajuste do `WorkflowsTest` para aceitar `id-token: write` só no `deploy.yml`. |
| Q6 | DIV-08, DIV-09, DIV-10 | Entram no registro as pastas novas, as tags `vAAAA.MM.DD-N` (versões locais) e os commits em inglês com os prefixos `feat`, `fix`, `test`, `chore`, `ci` e `docs`. |
| Q7 | DIV-11 | O `scripts/verify-checkpoint.sh` roda primeiro no local; se a máquina não aguentar, a prova é a CI do mesmo commit. |
| Q8 | DIV-13, DIV-14 | O volume de testes do projeto continua `Comprehensive`, e cada intent segue a estratégia que registra (este intent registra `Standard`). A fatia fina deste intent é a visão única com um bot PAPER e um bot REAL no validador local, com um spec novo no `verify.sh` que confere que os modos nunca são somados. O responsável acrescenta o spec. |

### 4.2 Fatos posteriores ao rascunho (informados pelo orquestrador, 2026-11-21)

- **DIV-07 resolvida**: o commit `600140e` ("feat: operation stages of the REAL intent and the start of the
  paper-real-unico feature") enviou o trabalho pendente do intent `261113-trading-real-bot`. `git rev-parse HEAD`
  confirma `600140ea20a51f8c6016e8c3f1f08109f411ea5c`.
- **DIV-01 resolvida**: o repositório ficou privado em 2026-11-21, com os alertas do Dependabot e as correções
  automáticas de segurança ligados (o que também resolve a DIV-16).
- A varredura de segredos do GitHub não existe para um repositório privado pessoal sem GitHub Advanced Security.
  Por isso, a proteção de segredos continua sendo o Gitleaks no pré-commit e na CI.
- Os minutos do Actions voltam a contar na cota do plano. A frequência dos jobs é revista com dados de consumo se a
  cota apertar.

### 4.3 Regras rígidas tiradas da entrevista (`discovered-rules.md`)

- `ALWAYS`: os pontos de controle e as implantações usam só um commit com a CI verde (Q2).
- `ALWAYS`: uma `main` vermelha é corrigida no commit seguinte, antes de qualquer outra mudança (Q2).
- `NEVER`: abrir a devnet aos usuários (Q4).
- `NEVER`: somar os modos PAPER e REAL num total geral (Q8).

## 5. Incertezas abertas

- A região da AWS continua a definir (`environment-inventory.md`, seção 2).
- As pendências da CI (Q5) não têm data. O `deploy.yml` não pode entrar antes do ajuste do `WorkflowsTest`.
- O `codekb` não descreve a fundo `botwallet` nem `apps/txsigner`.
- O consumo de minutos do Actions no plano privado ainda não foi medido.

## Sources

- `aidlc/spaces/default/memory/team.md`, `project.md`, `org.md`
- `aidlc/spaces/default/codekb/eureka-trader-ai/`: `code-structure.md`, `technology-stack.md`, `dependencies.md`,
  `code-quality-assessment.md`, `architecture.md`, `business-overview.md`, `reverse-engineering-timestamp.md`
- `aidlc/spaces/default/intents/261113-trading-real-bot/construction/ci-pipeline/ci-config.md`
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/` (`cd-config.md`,
  `deployment-strategy.md`, `deployment-pipeline-questions.md`)
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/environment-provisioning/`
  (`environment-inventory.md`, `environment-provisioning-questions.md`)
- `aidlc/spaces/default/intents/261121-paper-real-unico/ideation/` (`decision-log.md`, `initiative-brief.md`,
  `constraint-register.md`, `intent-statement.md`) e `aidlc-state.md`
- `.github/workflows/ci.yml`, `security.yml`, `load-test.yml`, `.github/dependabot.yml`, `scripts/verify.sh`,
  `scripts/verify-checkpoint.sh`, `apps/api/src/test/java/com/memetrade/infra/WorkflowsTest.java`, `infra/`
- `git log`, `git status`, tags; `gh repo view`, `gh api` (environments, branch protection, actions permissions,
  security_and_analysis), `gh run list`, executados em 2026-11-21

## Assumptions & Open Questions

None.
