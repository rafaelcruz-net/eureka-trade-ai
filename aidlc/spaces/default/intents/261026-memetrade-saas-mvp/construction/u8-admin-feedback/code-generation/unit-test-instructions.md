# Instruções de testes — U8 Administração e feedback

Este documento diz como preparar, rodar e medir os testes da Unit `u8-admin-feedback`, conforme o [plano de geração de código](code-generation-plan.md).

- **Volume.** A estratégia é `Comprehensive`: 10 a 15 testes por componente, com testes unitários, de integração, de ponta a ponta, de desempenho e de segurança. O piso de 80% de linhas vale separadamente para o backend, o `apps/web` e o `apps/admin`.
- **Testes antes do código.** Valem para o kill switch (BR2.1 a BR2.9) e para a deduplicação e o limite do feedback (BR6.3, BR6.4).
- **Testes depois do código.** Valem para as demais camadas.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), ArchUnit, JaCoCo | `apps/api/pom.xml`; marcador `u8` |
| Base de integração | `AbstractU8IT` sobre a base da U5 | Migrações até a `0011`; dublê controlável da `BotAdministration` e U5 real nos testes de corrida |
| `apps/web` | Jest e Testing Library | `apps/web/jest.config.ts`; `src/features/feedback` |
| `apps/admin` | Jest, Testing Library, Playwright | `apps/admin/jest.config.ts` (piso de 80%); `apps/admin/playwright.config.ts` não é usado. O smoke roda pelo `apps/web` |
| Smoke | Playwright | `apps/web/e2e/u8-admin-feedback/` |

Pré-requisitos: Docker Desktop, JDK 21, Node LTS com `corepack pnpm@10.18.3` e Git Bash no Windows.

## Comandos desta Unit

| O que roda | Comando exato |
|---|---|
| Backend da U8 | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u8 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Uma classe de integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u8 -Dit.test=KillSwitchCrashIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Diálogo de feedback (`apps/web`) | `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/feedback` |
| App do administrador | `corepack pnpm@10.18.3 --dir apps/admin exec jest` |
| Smoke da U8 (ambiente de pé) | `corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/u8-admin-feedback` |
| Cobertura completa | `apps/api/mvnw -f apps/api/pom.xml verify`, `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage` e `corepack pnpm@10.18.3 --dir apps/admin exec jest --coverage` |

O comando do backend fica pronto no Passo 2, antes do primeiro teste vermelho (Passo 5). O do `apps/admin` fica pronto no Passo 17, antes dos testes das telas.

## Metas de cobertura

- **Piso:** 80% de linhas no backend, no `apps/web` e no `apps/admin`, cada um medido separadamente.
- **Exclusões:** só o cliente gerado do contrato no `apps/admin`, listado no `jest.config.ts`, como no `apps/web`.
- **Regra:** o piso nunca é reduzido.

## Testes por componente

| Componente | Tipo | Quantidade | Riscos obrigatórios |
|---|---|---|---|
| Migração, gatilhos e papéis | Integração | 12 | Histórico só de acréscimo; `DELETE` de feedback só pela retenção |
| `KillSwitchService` | Unitário (antes do código) | 14 | Pausa impede operações; idempotência |
| `ApplyLock` e fencing | Integração (antes do código) | 12 | Ordem garantida; marcação não apagada |
| Reconciliação e quedas | Integração (antes do código) | 12 | Três quedas convergem em até 45 s |
| Corrida ON e OFF | Integração (antes do código) | 1 × 50 repetições | Estado final igual na U5 e na U8 |
| Desempenho do kill switch | Integração | 2 | p95 ≤ 5 s com 60 bots |
| Feedback | Unitário e integração (antes do código) | 15 | Deduplicação; tenant do token; limite atômico |
| Expurgo do feedback | Integração | 8 | 180 dias; só pela retenção |
| Entrada do administrador | Integração | 15 | Nonce, assinatura, lista, convidado, limites |
| CORS e papéis | Integração | 12 | Admin sem dado de pessoa; convidado sem rota de admin |
| Estado do sistema | Integração | 12 | `UNKNOWN` nunca zero; p95 ≤ 1 s |
| Contrato, saúde, métricas e logs | Integração | 12 | Nada sensível em log |
| Arquitetura | ArchUnit | 10 | Fronteiras; sem SDK Solana |
| Diálogo de feedback | Jest | 15 | Texto mantido; foco devolvido |
| App do administrador | Jest | 60 | Estados, diálogos, teclado, contraste, CSP |
| Smoke | Playwright | 1 execução | Fluxo completo do administrador |

## Mocks e dublês

| Item | Como os testes tratam |
|---|---|
| Relógio | `MutableClock` na reconciliação, nos alertas, no expurgo e nas sessões |
| U5 | Dublê controlável da `BotAdministration` (falha por bot, interrupção depois de N bots, exceção); a U5 real em `KillSwitchRaceIT` e no desempenho |
| U3, U4, U6, U2 | Reais nos testes de integração; dublês lentos ou com falha no `SystemStatusIT` |
| Trava | Uma segunda conexão real que segura a trava consultiva |
| Carteira | Chaves Ed25519 geradas no código de teste, nunca no código de produção |
| Frontend | API simulada com respostas do contrato |

## Dados de teste

- Tudo é sintético: nenhuma carteira, mint ou pessoa real.
- O texto de feedback usa um marcador único por teste para a varredura de logs.
- Os testes de concorrência usam uma barreira de início e repetições fixas.
- Teste instável é tratado como defeito.
