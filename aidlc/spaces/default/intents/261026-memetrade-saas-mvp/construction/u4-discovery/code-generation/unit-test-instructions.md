# Instruções de testes — U4 Descoberta e avaliação

Como preparar, rodar e medir os testes da Unit `u4-discovery`, conforme o [plano de geração de código](code-generation-plan.md). A estratégia de testes do workflow é `Comprehensive`: 10 a 15 testes por componente, com unitários, integração e ponta a ponta, mais desempenho e segurança onde os NFRs pedem. O piso de 80% de linhas do escopo `enterprise` soma-se a ela. A U4 não faz parte do núcleo financeiro, então todos os passos seguem a ordem test-after do `team.md`.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), ArchUnit, JaCoCo | `apps/api/pom.xml`: Surefire para unitários (`*Test`), Failsafe para integração (`*IT`), JaCoCo `check` com 80% de linhas no `verify` completo; marcador `u4` |
| Base de integração | `AbstractPostgresIT` (U2) | Mesmas migrações da aplicação, três logins de banco |
| Frontend | Jest e Testing Library | `apps/web/jest.config` existente; testes em `src/features/scanner` e `src/content` |
| Smoke | Playwright | `apps/web/e2e/u4-discovery/` |
| Carga | `scripts/load-test.sh` com `scripts/load/load-test.mjs` | Cenários U1, U4 e crescimento; só local |

Pré-requisitos: Docker Desktop em execução, JDK 21, Node LTS do `.nvmrc` com pnpm, Git Bash no Windows.

## Comandos desta Unit

| O que roda | Comando exato |
|---|---|
| Backend da U4, unitários e integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u4 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Backend da U4, uma classe só | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u4 -Dit.test=ScannerListQueryIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Frontend da U4 | `pnpm --dir apps/web exec jest src/features/scanner src/content` |
| Smoke da U4 (ambiente de pé) | `pnpm --dir apps/web exec playwright test e2e/u4-discovery` |
| Cobertura da suíte inteira (piso de 80%) | `apps/api/mvnw -f apps/api/pom.xml verify` e `pnpm --dir apps/web exec jest --coverage src/features/auth src/features/scanner src/lib src/middleware.test.ts` |
| Carga (só local) | `SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` |

O comando do backend da U4 fica pronto no Passo 2, antes do primeiro teste da Unit (Passo 3).

## Metas de cobertura

- 80% de linhas no backend e no frontend, medidos separadamente sobre a suíte inteira.
- Exclusões fixas, só na configuração da ferramenta, como na U1 e na U2. A U4 não acrescenta exclusões.
- O piso nunca é reduzido para fazer um passo passar; a falta de cobertura é registrada no resumo do código.

## Testes por componente

| Componente | Tipo | Quantidade prevista | Casos de risco obrigatórios |
|---|---|---|---|
| Migração `0006` | Integração | 10 | Uma versão `ACTIVE`; privilégios; rollback (consultas da U1 sobre o esquema novo) |
| Fontes simuladas e validação | Unitário e integração | 12 | Casamento por chain + mint; descarte contado; nenhum socket |
| Coleta e estado das fontes | Integração | 15 | `PENDING`; tempo limite; tudo ou nada; reinício; sobreposição |
| Leitura de campo, frescor e divergência | Unitário e integração | 15 | `unknown` nunca zero (risco); 5:00 e 5:01; 5% exato; janela de 1 hora |
| Limpeza do histórico | Integração | 6 | Fronteira de 24 h |
| Cálculo do indicador | Unitário e propriedade | 15 | `unknown` nunca seguro (risco); cobertura 80%; faixas; determinismo |
| Versões de pesos | Integração | 12 | Só administrador; um evento por ativação |
| Recálculo | Integração | 10 | Versão nunca regride; gatilhos juntados |
| Consulta da lista | Integração | 15 | Fronteiras; `excludedByUnknown`; `503`; fonte com falha |
| Limitador | Unitário e integração | 10 | 60/61; `Retry-After`; sem sessão |
| Rotas | Integração e contrato | 12 | Papéis 401/200/403; `400`; OpenAPI |
| Saúde, métricas e logs | Integração | 10 | Nunca `DOWN`; rótulos sem dado pessoal |
| Arquitetura | ArchUnit | 6 | Sem `double`/`float`; sem dependência de operações |
| Tela S3 | Jest | 15 | Estados distintos; marcas em texto; teclado |
| Painel lateral | Jest | 12 | Sem indicador provisório; foco |
| Termos proibidos | Jest | 3 | Catálogo inteiro |
| Smoke | Playwright | 1 execução | Filtro, painel, Esc |
| Carga | `scripts/load-test.sh` | 1 execução | p95 por rota; espera do pool; memória; plano |

## Mocks e dublês

- **Relógio:** `MutableClock` em todo teste de prazo (desatualizado, janela de 1 hora, 24 h do histórico, limitador, recálculo em dia).
- **Espera:** `Sleeper` sem espera real nas novas tentativas; aleatoriedade com semente fixa.
- **Fontes:** adaptadores simulados com semente fixa e fixtures gravadas para A e B; um adaptador de teste que demora, falha ou devolve dado inválido sob comando. Nenhum acesso de rede.
- **Banco:** PostgreSQL real em contêiner com as migrações; cada teste cria os próprios dados.
- **Administrador:** sessão de administrador criada só no código de teste (apoio da U2).
- **Publicador da trilha:** o real da U2, conferido pelas linhas da tabela de saída.

## Dados de teste

- Mints e símbolos sintéticos gerados no teste (`SyntheticIds`); nada real.
- Tabelas de valores conhecidos para as curvas e faixas da versão 1, escritas no teste a partir das regras.
- A carga sintética usa só valores gerados.
- Teste instável é tratado como defeito e corrigido, nunca ignorado.
