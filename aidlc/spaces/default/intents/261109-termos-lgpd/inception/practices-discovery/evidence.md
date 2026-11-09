# Evidências — Practices Discovery (integração final do líder; resumo reconfirmado em 2026-11-09)

Intent: `261109-termos-lgpd` (escopo `feature`, brownfield). Commit inspecionado: `ffde88f` (`main`, igual a
`origin/main`). Colaboradores desligados nesta rodada: nenhuma revisão de apoio; a integração final usa só o
rascunho do líder e a entrevista concluída (`practices-discovery-questions.md`).

## Linha de base

As práticas afirmadas em `aidlc/spaces/default/memory/team.md` (afirmadas no intent `261026-memetrade-saas-mvp`) são a
linha de base. `team-practices.md` mantém as cinco seções e aplica só as decisões da entrevista listadas abaixo. As
regras ALWAYS/NEVER de `aidlc/spaces/default/memory/project.md` já estão promovidas e não são repetidas em
`discovered-rules.md`.

## O que foi inspecionado

| Fonte | O que mostrou |
|---|---|
| `git log` (13 commits, 2026-10-30 a 2026-11-08) | Um único autor; todos os commits direto em `main`; nenhum commit de merge; nenhuma branch além de `main`; uma tag local `v2026.11.08-1` |
| `.github/workflows/ci.yml` | Jobs `backend` (`mvnw verify`: Spotless, testes, JaCoCo 80%), `frontend` (só `apps/web`), `contract` (OpenAPI dos dois grupos e tipos de `apps/web`), `smoke` (`scripts/verify.sh`), `e2e` (só em push para `main`, roda de novo `scripts/verify.sh`); ações fixadas por SHA; `permissions: contents: read` |
| `.github/workflows/security.yml` | Gitleaks (versão fixa, checksum), CodeQL Java e JS/TS (erro bloqueia), exceções vencidas bloqueiam, Dependency-Check CVSS ≥ 7, `pnpm audit` High/Critical em `apps/web` e `apps/admin` |
| `.github/workflows/load-test.yml`, `.github/dependabot.yml` | Carga só manual; Dependabot semanal para Maven, npm (`apps/web`, `apps/admin`, espera de 5 dias), Docker, Compose e Actions |
| `.githooks/pre-commit`, `.gitleaks-version`, `security/exceptions.yml` | Gitleaks no pré-commit com versão conferida; registro de exceções vazio, com validade de até 90 dias |
| `scripts/verify.sh` (lido, não executado) | Sobe Compose + sobreposição de teste, espera saúde, roda os specs Playwright de U1, U2, U4, U7, U5, U8, U9 e U3, varre os logs atrás de carteira, código de convite e texto de feedback, derruba tudo; sai com código diferente de zero em falha |
| `apps/web/jest.config.ts`, `apps/admin/package.json` | Piso de 80% de linhas e exclusões fixas na configuração; o app administrativo tem `lint`, `typecheck`, `test:coverage` e `check:api`, mas a CI não os chama |
| `.claude/scopes/aidlc-feature.md`, `<record>/aidlc-state.md` | Escopo `feature` com `skeleton: on`; Test Strategy deste intent: `Standard` |
| Registros do intent anterior (`operation/deployment-execution/`) | Release local com ensaio de rollback para o commit anterior; tag criada só depois de três `verify.sh` verdes e não enviada ao GitHub sem pedido |

Artefatos de engenharia reversa lidos em `aidlc/spaces/default/codekb/eureka-trader-ai/`:

- `code-structure.md` — layout `apps/api`, `apps/web`, `apps/admin`, `infra/`, `scripts/`, `docs/`; módulos por pacote
  com `domain`, `infra`, `web`.
- `technology-stack.md` — Java 21, Spring Boot, Maven, Liquibase, Testcontainers, ArchUnit, Spotless com
  palantir-java-format; Next.js 15, pnpm, Jest 30, ESLint 9, Prettier, Playwright.
- `dependencies.md` — tabelas de cliente referenciam `app_user` sem `ON DELETE`; `check:api` do admin fora da CI.
- `code-quality-assessment.md` — dívidas 1 a 6 ligadas ao intent (endereço completo na trilha, falta de expurgo,
  falta de anonimização, falta de reaceite, termos v1 provisórios, texto livre sem triagem) e dívida 7 (lacunas da CI).
- `architecture.md` — monólito modular verificado por ArchUnit; tabela de retenção por tabela; migrações
  expand-contract com `MigrationsBackwardCompatIT`; trilha de auditoria recusa UPDATE para todos os logins.
- `business-overview.md` — contexto do beta PAPER e do intent.

## O que foi inferido

- **Way of Working**: trunk-based em `main`, como afirmado. A proteção de segredos, a segurança da CI, a fixação por
  SHA, as permissões mínimas, os lockfiles e o Dependabot batem com o afirmado.
- **Code Style**: bate com o afirmado (Spotless com palantir-java-format, ESLint, Prettier, `strict`, RFC 7807,
  ArchUnit nas fronteiras). Detalhes menores sem pergunta: uma supressão `checkstyle:ParameterNumber` sem Checkstyle
  configurado e um README desatualizado (`code-quality-assessment.md`).
- **Deployment**: continua só local; a primeira release foi marcada por tag local após ensaio de rollback, coerente
  com "artefatos marcados com o SHA do commit" (`IMAGE_TAG` = SHA na CI) e "implantação só termina quando o smoke
  passa".

## Divergências entre o afirmado e o repositório

1. **Commits por Bolt**: o afirmado é uma worktree por Bolt e um squash-merge em `main` por Bolt, com o slug do Bolt
   no commit. O histórico mostra commits direto em `main`, sem merge, alguns juntando duas Units (`a5b8c1e`: U3 e U7)
   e commits de correção dos ciclos de Build and Test.
2. **CI sem o app administrativo**: `ci.yml` não roda lint, formatação, typecheck, Jest nem `check:api` de
   `apps/admin`. O afirmado diz que lint, formatação e tipos rodam na CI antes do merge, que o piso de 80% vale no
   frontend e que uma quebra de contrato falha a CI.
3. **Jest do web em recorte**: o job `frontend` roda Jest só em `features/auth`, `features/scanner`, `lib` e
   `middleware`; `features/account`, `bots`, `dashboard`, `feedback` e `pnl` ficam de fora da CI.
4. **"Suíte completa no merge"**: o job `e2e` em `main` roda o mesmo `scripts/verify.sh` do job `smoke`; não há uma
   suíte maior do que o smoke no merge.
5. **Test Strategy**: `team.md` diz que a Test Strategy do workflow é `Comprehensive`; o estado deste intent registra
   `Standard`.
6. **Walking Skeleton neste intent**: o escopo `feature` liga o esqueleto, mas o esqueleto do produto (U1) já existe e
   está verificado. O afirmado descreve a fatia do scanner, não uma fatia de termos e privacidade. O comando de
   verificação está "a definir" em `team.md`; no intent anterior foi `bash scripts/verify.sh`.
7. **Testes obrigatórios por risco**: a lista afirmada não cobre dados pessoais (anonimização, exclusão, reaceite de
   termos, expurgos de retenção), e a ordem test-first vale só para o núcleo financeiro, embora a anonimização toque
   tabelas append-only como a trilha de auditoria e as ligadas ao ledger.

## Decisões da entrevista

O responsável do projeto respondeu A em todas as perguntas e confirmou o resumo consolidado ("Looks correct").

| Pergunta | Decisão | Divergência resolvida | Onde entrou em `team-practices.md` |
|---|---|---|---|
| Q1 | As mudanças entram direto em `main`, em commit pedido pelo responsável, com a CI verde; squash-merge só quando houver uma branch pedida por ele | 1 (commits por Bolt) | `## Way of Working`, "Estratégia de branches" |
| Q2 | As lacunas da CI (`apps/admin` sem lint, typecheck, Jest e `check:api`; Jest do web só em parte das telas) são corrigidas neste intent; o responsável aplica no `ci.yml` as mudanças de `aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/ci-pipeline/ci-config.md` antes do primeiro commit do intent; só ele altera o `ci.yml` | 2 e 3 | `## Way of Working`, "Lacunas da CI" |
| Q3 | Em toda mudança e no merge rodam os testes de unidade e integração de todas as partes, com o piso de cobertura, mais o `verify.sh`; o `verify.sh` com todos os specs é a suíte completa | 4 | `## Testing Posture`, "Frequência" |
| Q4 | Test Strategy `Comprehensive`, piso de 80% mantido | 5 (no texto das práticas; o campo do estado ainda registra `Standard`, ver incertezas) | `## Testing Posture`, "Volume de testes" |
| Q5 | Anonimização, exclusão, novo aceite dos termos e expurgos entram na ordem de testes antes do código; dois testes obrigatórios novos (carteira sem resíduo após a anonimização; versão nova dos termos exige novo aceite antes de qualquer outra rota) | 7 | `## Testing Posture`, `- **Ordering**:`, `- **Methodology evidence**:` e "Testes obrigatórios por risco" |
| Q6 | Fatia fina: `/privacidade` servida pela API a partir do catálogo com a versão vigente e mostrada sem login, com spec de ponta a ponta novo; verificação `bash scripts/verify.sh` com o spec que o responsável acrescenta | 6 | `## Walking Skeleton`, "Fatia do intent `261109-termos-lgpd`" |

`- **Methodology**:` continua `custom`, porque a ordem mistura testes antes do código (núcleo financeiro e dados
pessoais) com testes depois (demais camadas).

Nenhuma resposta trouxe restrição rígida nova além das regras já promovidas em `project.md`; por isso
`discovered-rules.md` segue com `None.` nas duas seções.

## Perguntas feitas na entrevista (registro)

1. Neste intent, cada Bolt volta a ser integrado em `main` por um squash-merge com o nome do Bolt, como está
   afirmado, ou o time passa a registrar que commita direto em `main` como no histórico?
2. As lacunas da CI (app administrativo sem lint, typecheck, Jest e `check:api`; Jest do web só em parte das telas)
   são corrigidas dentro deste intent, já que ele mexe nas duas telas, ou ficam registradas como dívida com prazo?
3. O job que roda no merge para `main` deve ser uma suíte maior que o smoke de cada mudança, ou o time aceita que o
   smoke completo (`verify.sh`) é a "suíte completa" e ajusta o texto afirmado?
4. Qual volume de testes vale neste intent: `Standard` (registrado no estado) ou `Comprehensive` (afirmado no
   `team.md`)?
5. A lógica de dados pessoais (anonimização, exclusão, reaceite de termos, expurgos) entra na ordem test-first e na
   lista de testes obrigatórios por risco, como o núcleo financeiro?
6. Qual é a fatia fina de ponta a ponta deste intent (por exemplo, `/termos` e `/privacidade` servidos pela API com a
   versão vigente) e o comando de verificação continua `bash scripts/verify.sh` com um spec novo?

## Incertezas que continuam abertas

- Test Strategy no estado: a decisão Q4 é `Comprehensive`, mas o `aidlc-state.md` deste intent ainda registra
  `Standard`. Ajustar o campo cabe ao orquestrador (por exemplo, `/aidlc --test-strategy comprehensive`), não a
  este estágio.
- Mudanças no `ci.yml` (Q2): dependem de o responsável aplicá-las antes do primeiro commit do intent; até lá, a CI
  segue sem os portões do app administrativo e com o Jest do web em recorte.
- Spec novo no `verify.sh` (Q6): depende de o responsável acrescentá-lo ao script; o comando de verificação da
  Construction continua a ser autorizado por ele.

- Cota de minutos do GitHub Actions: segue "a definir" em `team.md`; nenhuma evidência no repositório.
- Não confirmei se a tag `v2026.11.08-1` foi enviada ao GitHub (a consulta ao remoto não listou tags).
- Staging, DAST, varredura de imagens e SBOM seguem "a definir", sem mudança desde a afirmação.
