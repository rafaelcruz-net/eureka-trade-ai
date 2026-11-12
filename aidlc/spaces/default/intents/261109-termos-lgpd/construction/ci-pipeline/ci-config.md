# Configuração da CI

Conversa: português (Brasil). Intent `261109-termos-lgpd`. Respostas em `ci-pipeline-questions.md` (todas A). Ferramenta: GitHub Actions; estratégia: tronco `main`, sem merge com a CI vermelha (`team.md`). Os workflows são protegidos: só o responsável do projeto os altera, aplicando os patches preparados pelo AI-DLC.

## Workflows

| Workflow | Dispara em | Jobs | Mudanças deste intent |
|---|---|---|---|
| `.github/workflows/ci.yml` | pull request; push em `main`; manual (`workflow_dispatch`) | `backend`, `frontend`, `admin`, `contract`, `smoke`, `e2e` (só push em `main`) | `workflow_dispatch` (`7e77c11`); `MEMETRADE_AUDIT_WALLET_HMAC_KEY: ci-placeholder` no passo "Compose config" do `backend` (`4bc09d1`) |
| `.github/workflows/security.yml` | pull request; push em `main` | `secrets`, `sast` (Java e TypeScript), `dependencies` | caminho do arquivo de exceções: `-DsuppressionFiles="${GITHUB_WORKSPACE}/dependency-check-suppressions.xml"` (patch do responsável, ainda sem commit) |
| `.github/workflows/load-test.yml` | manual | `load` | opções `reacceptance` e `data-requests` (`5d30541`) |

Ações de terceiros fixadas por SHA, `permissions: contents: read` e nenhum segredo de produção nos workflows (`team.md`, `project.md`). O único segredo lido é `NVD_API_KEY`, opcional, no job `dependencies`.

## O que cada job roda

| Job | Comandos (iguais aos do Build and Test) |
|---|---|
| `backend` | `docker compose ... config --quiet` com valores fictícios; `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` (unidade, integração com Testcontainers, ArchUnit, Spotless e JaCoCo ≥ 80%) |
| `frontend` | `pnpm --dir apps/web install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `exec jest --coverage` (piso de 80%) |
| `admin` | os mesmos comandos em `apps/admin`, mais `check:api` |
| `contract` | OpenAPI gerado igual ao versionado; `pnpm --dir apps/web check:api` |
| `smoke` | `bash scripts/verify.sh`: sobe o Compose, roda os 19 specs Playwright e varre os logs atrás de carteira, texto de feedback e códigos de convite |
| `e2e` | suíte completa de ponta a ponta depois dos demais jobs, só em push para `main` |
| `secrets` | Gitleaks com versão e checksum fixos |
| `sast` | CodeQL |
| `dependencies` | `SecurityExceptionsTest` (exceção vencida volta a bloquear); geração do XML de exceções a partir de `security/exceptions.yml`; OWASP Dependency-Check 12.1.0 com `-DfailBuildOnCVSS=7`; `pnpm audit --audit-level high` nos dois apps |

## Versões de dependências mantidas fora do BOM do Spring Boot

Em `apps/api/pom.xml`, por causa da varredura de dependências deste intent: `tomcat.version` 10.1.61, `postgresql.version` 42.7.14, `kotlin.version` 2.4.21 (só no classpath de teste) e `log4j2.version` 2.25.5. Cada uma sai quando o BOM do Spring Boot trouxer a mesma versão ou uma mais nova.

## Exceções de segurança

`security/exceptions.yml`: EX-001 a EX-012 (Spring Framework 6.2.19, sem versão aberta corrigida, cada CVE conferido contra o código) e EX-013 (falso positivo do servidor Prometheus), aprovadas pelo responsável em 2026-11-11 e válidas até 2027-02-09. A migração para o Spring Boot 4 precisa acontecer antes disso.

## Artefatos

Só artefatos do GitHub Actions, com retenção curta: relatório de cobertura do backend, relatório do Playwright, relatórios do Dependency-Check e o `load-test-report.json` da carga manual. Imagens de contêiner e registro ficam para os estágios de implantação (resposta 5A).

## OSS Index

O analisador OSS Index continua ligado (resposta 4A). Se a CI der `401 Unauthorized` na Sonatype, o responsável cadastra as credenciais gratuitas do OSS Index como segredo do repositório e o job passa a lê-las; desligar o analisador só por decisão dele.

## Pendente para a CI confirmar

As mudanças do Build and Test ainda sem commit (volta da U3, versões novas, exceções, pool fixo, scripts de carga, relatórios em `docs/perf/` e o caminho das exceções no `security.yml`) só ficam confirmadas na CI depois do commit e do push pedidos pelo responsável. A última execução verde de `ci.yml` é a `43067822342` (commit `63a7710`).
