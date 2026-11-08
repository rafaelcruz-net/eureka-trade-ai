# Instruções de testes — U5 Bots e decisão

Este documento diz como preparar, rodar e medir os testes da Unit `u5-bots-decision`, conforme o [plano de geração de código](code-generation-plan.md).

- **Volume.** A estratégia é `Comprehensive`: 10 a 15 testes por componente, com testes unitários, de integração e de ponta a ponta, mais testes de desempenho e de segurança. O piso de 80% de linhas vale para o backend e para o frontend.
- **Testes antes do código.** Valem no núcleo financeiro do `team.md`: regras de pausa, perda diária, meia-noite UTC e encerramento, o ciclo e a decisão, e a deduplicação.
- **Testes depois do código.** Valem para configuração, telas, carga e documentação.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), ArchUnit, JaCoCo | `apps/api/pom.xml`; marcador `u5` |
| Base de integração | `AbstractU5IT` sobre `AbstractPostgresIT` (U2) | Migrações até a `0010`; contexto `api` e contexto `worker` (`memetrade.process=worker`) |
| Frontend | Jest e Testing Library | `apps/web/jest.config.ts`; `src/features/bots` e `src/app/bots` |
| Smoke | Playwright | `apps/web/e2e/u5-bots-decision/` |
| Carga | `scripts/load-test.sh`, `scripts/load/load-test.mjs` | Cenário `u5` |

Pré-requisitos: Docker Desktop, JDK 21, Node LTS com `corepack pnpm@10.18.3` e Git Bash no Windows.

## Comandos desta Unit

| O que roda | Comando exato |
|---|---|
| Backend da U5 | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u5 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Uma classe de integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u5 -Dit.test=CycleIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Catálogo de decisões | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u5 -Dtest=DecisionCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test` |
| Frontend da U5 | `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/bots src/app/bots` |
| Smoke da U5 (ambiente de pé) | `corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/u5-bots-decision` |
| Cobertura completa | `apps/api/mvnw -f apps/api/pom.xml verify` e `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage` |
| Carga da U5 (só local) | `LOAD_SCENARIO=u5 SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` |
| Volta 1: regressão da semente | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u5 -Dit.test=LoadtestBotSeedIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Volta 1: backend da U5 | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u5 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Volta 1: suíte inteira com cobertura | `apps/api/mvnw -f apps/api/pom.xml verify` |
| Volta 2: fatias e falhas do script de carga | `node --test scripts/load/load-test-u5-slices.test.mjs` |
| Volta 2: frescor no cenário de sinais frequentes | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u5 -Dit.test=LoadtestFrequentSignalsFreshnessIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Volta 2: backend da U5 | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u5 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Volta 2: suíte inteira com cobertura | `apps/api/mvnw -f apps/api/pom.xml verify` |
| Volta 2: carga da U5 | `LOAD_SCENARIO=u5 SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` |
| Volta 3: suíte completa do frontend | `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage` (3 vezes seguidas) |

Na volta 1 de Build and Test (Passos 23 a 25), os testes de regressão de `LoadtestBotSeedIT` são escritos antes da correção e falham com a violação da chave estrangeira `decision.cycle_id`. Depois da correção, eles passam. O cenário de carga `u5` roda de novo no Build and Test, não nesta etapa.

Na volta 2 (Passos 26 a 29), cada teste novo é escrito antes da mudança, e a saída vermelha fica registrada no resumo:
- **Contagem de fatias:** o teste `node --test` falha porque a regra antiga espera 6 fatias.
- **Frescor:** o teste de integração `LoadtestFrequentSignalsFreshnessIT` falha enquanto alguma decisão sair `STALE_DATA` com a fonte simulada operante. O nome da classe pode mudar conforme a causa encontrada no Passo 27, e o resumo registra o nome final.
- **Carga:** nesta volta o cenário `u5` roda aqui mesmo, e depois de novo no Build and Test.

O comando do backend fica pronto no Passo 2, antes do primeiro teste vermelho (Passo 4). A saída vermelha de cada passo com testes antes do código é registrada no resumo.

## Metas de cobertura

- **Piso.** 80% de linhas no backend e no frontend, medidos separadamente.
- **Exclusões.** Nenhuma exclusão nova. Os componentes do perfil `loadtest` são cobertos pelo teste de perfil.
- **Regra.** O piso nunca é reduzido para fazer um passo passar.

## Testes por componente

| Componente | Tipo | Quantidade | Riscos obrigatórios |
|---|---|---|---|
| Papel do processo e guarda | Unitário e contexto | 8 | Nada roda em dobro; worker sobe sem segredos web |
| Migração `0010` | Integração | 10 | Só PAPER; unicidades |
| Máquina de estados e motivos | Unitário (catálogo L) | 20 | Todas as transições; pausa |
| Validação e padrões | Unitário | 12 | Fronteiras inclusivas |
| Decisão (estratégias, saídas, tamanho, prioridade) | Unitário (catálogo D) e propriedade | 25 | `unknown` nunca zero; determinismo |
| Reprodução da decisão | Unitário | 5 | Mesma decisão a partir do gravado |
| Repositórios e `LockOrder` | Integração e teste de código-fonte | 12 | `TenantScope`; ordem das travas |
| Comandos | Integração | 15 | Idempotência; 3 ativos em paralelo; kill switch |
| Ciclo | Integração | 15 | Pausa impede operação; uma ordem por fatia; falha fechada; REAL desligado |
| Meia-noite, conferência, expurgo | Integração | 12 | Remoção só da perda diária; fechamento pendente uma vez |
| Administração e marcas | Integração | 8 | Pausa de sistema repetível |
| Rotas C4 | Integração e contrato | 15 | Dois tenants; 403 com outro modo |
| Canal C7 | Integração | 6 | Nenhum evento de outra pessoa |
| Saúde, métricas, logs, trilha | Integração | 8 | Nada sensível |
| Arquitetura | ArchUnit | 10 | Sem `double`, sem rede, sem IA, sem Solana |
| Telas | Jest | 30 | Estados; diálogos; validação; acessibilidade |
| Smoke | Playwright | 1 execução | Ciclo de vida pelo assistente |
| Carga | `scripts/load-test.sh` | 1 execução | Ciclo, rotas, tela, canal |

## Mocks e dublês

| Item | Como os testes tratam |
|---|---|
| Relógio | `MutableClock` em fatias, meia-noite (com usuário em UTC−3), tempo máximo de posição e expurgo |
| Dados de mercado | Dublê da C11 e da C12 com janelas por balde, preço, liquidez, avaliação e `unknown` sob comando |
| U6 e U7 | Reais nos testes de integração do ciclo, com contas criadas por `fund`; dublês só nos testes unitários |
| Transação | O ciclo real (`CycleRunner`) |
| Falha técnica | Dublês que lançam exceção em cada chamada |
| Banco | PostgreSQL em contêiner; concorrência com barreira de início |
| Trilha | Publicador real da U2, conferido pela tabela de saída |
| Frontend | API simulada com respostas do contrato |

## Dados de teste

- Tudo é sintético, sem carteira, mint ou pessoa real.
- Os valores dos catálogos D1 a D18 e L1 a L14 vêm da functional-spec.md e ficam escritos no teste como texto.
- Os testes de propriedade usam seed fixa.
- Teste instável é tratado como defeito.
