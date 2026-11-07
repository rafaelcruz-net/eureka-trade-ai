# Instruções de testes — U9 Painel e navegação

Este documento diz como preparar, rodar e medir os testes da Unit `u9-dashboard`, conforme o [plano de geração de código](code-generation-plan.md).

- **Volume.** A estratégia é `Comprehensive`: 10 a 15 testes por componente, com testes de unidade, de componente, de integração, de desempenho e de ponta a ponta. O piso de 80% de linhas vale separadamente para o backend e para o `apps/web`.
- **Ordem.** A U9 não toca o núcleo financeiro, então todas as camadas seguem "implementar a camada e, em seguida, escrever e executar os testes dela" (`team.md`).

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend (teto de canais, esquemas do canal, medições) | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), JaCoCo | `apps/api/pom.xml`; marcador `u9`; base de integração sobre a da U5 |
| `apps/web` (painel, cliente do canal, casca, lint) | Jest e Testing Library, relógio falso do Jest | `apps/web/jest.config.ts` (piso de 80%, exclusão só do cliente gerado do contrato) |
| Ponta a ponta | Playwright com a carteira de teste de `apps/web/e2e/support` | `apps/web/playwright.config.ts`; `apps/web/e2e/u9-dashboard/` |

Pré-requisitos: Docker Desktop, JDK 21, Node LTS com `corepack pnpm@10.18.3` e Git Bash no Windows. Nenhum teste acessa rede real; o canal é simulado por um fluxo de texto controlável nos testes do Jest.

## Comandos desta Unit

| O que roda | Comando exato |
|---|---|
| Backend da U9 (teto de canais e esquemas) | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u9 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Uma classe de integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u9 -Dit.test=DashboardChannelLimitIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Painel e cliente do canal (`apps/web`) | `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/dashboard` |
| Casca e telas migradas (`apps/web`) | `corepack pnpm@10.18.3 --dir apps/web exec jest src/components/shell src/features/scanner src/features/bots src/features/pnl src/features/account src/features/auth` |
| Regras de lint do painel | `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/dashboard/lint-rules.test.ts` |
| Smoke da U9 (ambiente de pé) | `corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/u9-dashboard` |
| Cobertura completa | `apps/api/mvnw -f apps/api/pom.xml verify` e `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage` |

Os comandos do backend e do `apps/web` ficam prontos no Passo 2, antes do primeiro teste da Unit (Passo 3). As medições `DashboardOpenLatencyIT` e `DashboardStreamDelayIT` têm o marcador `u9` e rodam com o comando do backend; na CI rodam na suíte completa do merge para `main`.

## Metas de cobertura

- **Piso:** 80% de linhas no backend e no `apps/web`, cada um medido separadamente, pelas ferramentas já configuradas (JaCoCo e Jest).
- **Exclusões:** nenhuma nova. O cliente gerado do contrato (`schema.d.ts`) continua sendo a única exclusão do `apps/web`.
- **Regra:** o piso nunca é reduzido, nem as exclusões ampliadas, para fazer uma etapa passar.

## Testes por componente

| Componente | Tipo | Quantidade | Riscos obrigatórios |
|---|---|---|---|
| Teto de canais (U5) | Unidade e integração | 10 | Contagem atômica; outra pessoa não afetada; nada de dado de pessoa no `429` |
| Esquemas do canal no OpenAPI | Integração | 3 | Contrato gerado igual ao versionado |
| Leitor do `text/event-stream` | Unidade | 12 | Teto de 1 MiB; texto nunca vira marcação |
| Cliente do canal | Unidade | 15 | Token só no cabeçalho; uma renovação por tentativa; console sem dado |
| Estado do painel | Unidade | 15 | Isolamento (sem `tenantId`/`userId`); `unknown` nunca zero |
| Quadros do painel | Componente | 30 | Pausa sem transição repetida; `unknown` nunca zero; decimal exato |
| Casca e telas migradas | Componente | 12 + os existentes | Carteira só abreviada |
| Regras de lint e armazenamento | Unidade | 4 | `EventSource` proibido; token fora do armazenamento |
| Abertura do painel e atraso do canal | Integração com medição | 2 | p95 ≤ 2 s por grupo; p95 ≤ 5 s e máximo ≤ 60 s |
| Smoke | Playwright | 1 execução | Pausa vista sem recarregar |

## Simulações e dados de teste

- **Rotas:** respostas montadas a partir dos tipos gerados (`schema.d.ts`), com fábricas pequenas por tipo (`BotSummary`, `PaperTrade`, `PnlReport`, `ReconciliationResult`, `SourceStatus`); um caso de erro parte de um valor válido e muda só o campo testado.
- **Canal:** fluxo de texto que entrega pedaços sob comando do teste, inclusive partidos no meio de linha e de caractere.
- **Relógio:** relógio falso do Jest para espera crescente, `STALE` em 60 s, idade das fontes e virada do dia UTC; no backend, o relógio injetável do Spring.
- **Contas e carteiras:** só as contas semeadas do perfil de teste e do perfil `loadtest`; nenhuma carteira, mint ou dado pessoal real.
- **Isolamento:** cada teste monta os próprios dados; os testes de integração usam as mesmas migrações da aplicação e não dependem da ordem de execução.
