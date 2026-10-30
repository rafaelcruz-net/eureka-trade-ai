# Instruções de testes — U1 Esqueleto de ponta a ponta

Como preparar, rodar e medir os testes da Unit `u1-walking-skeleton`, conforme o [plano de geração de código](code-generation-plan.md). A estratégia de testes do workflow é `Comprehensive` (10 a 15 testes por componente, com unitários, integração e ponta a ponta, mais desempenho e segurança onde os NFRs pedem), somada ao piso de 80% de linhas do escopo `enterprise`.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17`), ArchUnit, JaCoCo | `apps/api/pom.xml`: Surefire para unitários (`*Test`), Failsafe para integração (`*IT`), JaCoCo `check` com 80% de linhas |
| Frontend | Jest, Testing Library, `jsdom` | `apps/web/jest.config.ts` com `coverageThreshold.global.lines = 80` |
| Ponta a ponta | Playwright (Chromium) | `apps/web/playwright.config.ts`; specs em `apps/web/e2e/u1-walking-skeleton/` |
| Verificação da fatia | Docker Compose, Playwright | `scripts/verify.sh` |

Pré-requisitos: Docker Desktop em execução (Testcontainers e Compose), JDK 21, Node LTS de `.nvmrc`, pnpm de `packageManager`. No Windows, os comandos rodam no Git Bash. A instalação do frontend usa só o lockfile: `pnpm --dir apps/web install --frozen-lockfile`.

## Comandos desta Unit

Todo teste da U1 no backend leva a tag JUnit `u1`; todo teste da U1 no frontend fica nas pastas listadas abaixo. Os comandos rodam só os testes desta Unit.

| O que roda | Comando exato |
|---|---|
| Backend, unitários e integração, com cobertura | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` |
| Backend, um teste só (ciclo Red/Green do Passo 7) | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 -Dtest=IdempotencyServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` |
| Frontend, unitários com cobertura | `pnpm --dir apps/web exec jest --coverage src/features/auth src/features/scanner src/lib src/middleware.test.ts` |
| Ponta a ponta da U1 (com o ambiente de teste no ar) | `pnpm --dir apps/web exec playwright test e2e/u1-walking-skeleton` |
| Verificação completa da fatia (sobe, testa, derruba) | `bash scripts/verify.sh` |

O comando do backend fica pronto no Passo 3 e o do frontend no Passo 4, antes do primeiro teste escrito antes do código (Passo 7). No Passo 7, a saída do comando de um teste só com os testes falhando é registrada antes da implementação.

## Metas de cobertura

- 80% de linhas no backend (JaCoCo `check`, falha o `verify`) e 80% de linhas no frontend (`coverageThreshold` do Jest, falha o comando), medidos separadamente.
- Exclusões fixas, só na configuração da ferramenta: backend `MemetradeApplication`, classes `*Config` e `*Properties`, `HealthProbe`; frontend `src/lib/api/schema.d.ts` (gerado do OpenAPI), arquivos `*.config.*` e `src/app/layout.tsx`.
- O piso nunca é reduzido para fazer um passo passar; a falta de cobertura é registrada no resumo do código.

## Testes por componente

| Componente | Tipo | Quantidade prevista | Casos de risco obrigatórios |
|---|---|---|---|
| Migrações e banco | Integração | 10 | `audit_event` sem `UPDATE` nem `DELETE`; `CHECK` de `value` ou `unknown_reason` |
| Núcleo comum | Unitário | 12 | `ProblemDetail` sem stack trace; mascaramento; `mode` REAL recusado |
| Idempotência | Unitário e integração | 12 | Deduplicação por chave; `409` em conteúdo diferente; concorrência |
| Auditoria (C9) | Integração | 10 | Evento some com a transação; entrega única; sem segredos |
| Assinatura Ed25519 e Base58 | Unitário | 12 | Assinatura de outra carteira; mensagem alterada |
| Desafio | Unitário e integração | 10 | Expiração de 5 minutos; nonce único |
| Verificação | Integração | 15 | Nonce reutilizado; assinatura expirada; verificação simultânea; carteira de administrador |
| Sessão e contexto (C10) | Unitário e integração | 15 | Sessão revogada; reutilização do token de renovação; assinatura de login não serve para operar |
| Limite de tentativas | Integração | 10 | `429` por IP; dono da carteira não bloqueado por terceiro |
| Borda e isolamento | Integração | 10 | `tenantId` do cliente ignorado; registro de rotas com teste negativo |
| Fonte simulada (C19) | Unitário | 10 | `unknown` nunca zero; 0 informado é `KNOWN` |
| Lista do scanner (C2) | Unitário, integração e desempenho | 15 | Ordem e cursor; `stale` no limite de 5 minutos; p95 abaixo de 150 ms com 500 tokens |
| Saúde, logs e contrato | Integração | 12 | Varredura de logs sem nonce, assinatura, token nem endereço sem máscara; OpenAPI gerado igual ao versionado |
| Arquitetura | ArchUnit | 6 | Sem geração de chave na produção; sem leitura entre módulos |
| Casca do app | Jest | 10 | CSP com nonce; token só em memória; contraste 4,5:1 |
| Tela S1 | Jest | 12 | Nenhum campo de segredo; sessão expirada |
| Tela S3 | Jest | 15 | `unknown`, carregando, erro, parcial e desatualizado distintos |
| Compose e CI | Unitário (leitura de arquivos) | 6 | Perfil de teste fora do `docker-compose.yml`; ações fixadas por SHA |
| Ponta a ponta | Playwright | 3 specs | Smoke F7; só teclado em S1 e S3; tempo de tela de S3 |

## Mocks e dublês

- **Relógio:** `Clock` fixo ou ajustável injetado em todo teste de prazo (5 minutos do desafio, 15 minutos, 8 horas e 12 horas da sessão, `stale` de 5 minutos, janelas do limite). Nenhum teste usa `Thread.sleep` para esperar prazo de domínio.
- **Banco:** PostgreSQL real em contêiner com as migrações Liquibase da aplicação; cada teste de integração cria os próprios dados e não depende de ordem nem de estado de outro teste.
- **Fonte de mercado:** a implementação simulada com semente fixa; nenhum teste acessa rede.
- **Carteira:** pares de chaves Ed25519 gerados no código de teste a cada execução; no frontend, a carteira de teste registrada pelo Wallet Standard; nos testes de Jest, o adaptador de carteira é substituído por um dublê.
- **API no frontend:** respostas simuladas no nível do `fetch` com tipos gerados do OpenAPI.

## Dados de teste

- Fixtures nunca usam carteira, mint ou dado pessoal real: endereços e mints vêm de chaves geradas no teste ou de valores sintéticos da semente.
- A conta `BETA_USER` do smoke é criada pelo componente do perfil `test` a partir de `SMOKE_BETA_WALLET_ADDRESS`; a chave privada fica num arquivo temporário fora do repositório e é apagada no fim do `scripts/verify.sh`.
- Valores de mercado em `BigDecimal` e texto decimal; nenhum teste compara valor monetário com ponto flutuante.
- Teste instável é tratado como defeito e corrigido, nunca ignorado.
