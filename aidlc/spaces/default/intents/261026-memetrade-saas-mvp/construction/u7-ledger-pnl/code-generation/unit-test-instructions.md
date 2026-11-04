# Instruções de testes — U7 Ledger e P&L

Como preparar, rodar e medir os testes da Unit `u7-ledger-pnl`, conforme o [plano de geração de código](code-generation-plan.md). A estratégia de testes do workflow é `Comprehensive`: 10 a 15 testes por componente, com unitários, integração e ponta a ponta, mais desempenho e segurança onde os NFRs pedem. O piso de 80% de linhas do escopo `enterprise` soma-se a ela. A U7 é o núcleo financeiro do `team.md` (ledger, P&L e deduplicação): banco, repositório, lógica e API têm os testes escritos antes do código; configuração, carga, telas e smoke seguem implementar e depois testar.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), ArchUnit, JaCoCo | `apps/api/pom.xml` existente: Surefire (`*Test`), Failsafe (`*IT`), JaCoCo `check` com 80% de linhas no `verify` completo; marcador `u7` |
| Base de integração | `AbstractU7IT` sobre `AbstractPostgresIT` (U2) | Mesmas migrações, inclusive a `0008`; conexão extra com o papel da aplicação para os testes de privilégio |
| Frontend | Jest e Testing Library | `apps/web/jest.config.ts` existente; testes em `src/features/pnl` e `src/app/pnl` |
| Smoke | Playwright | `apps/web/e2e/u7-ledger-pnl/` |
| Carga | `scripts/load-test.sh` com `scripts/load/load-test.mjs` | Cenários `u7` e `u7-jobs`; só local |

Pré-requisitos: Docker Desktop em execução, JDK 21, Node LTS do `.nvmrc` com pnpm (ou `corepack pnpm@10.18.3`), Git Bash no Windows.

## Comandos desta Unit

| O que roda | Comando exato |
|---|---|
| Backend da U7, unitários e integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u7 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Backend da U7, uma classe só | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u7 -Dit.test=PaperExecutionIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Catálogo de cenários | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u7 -Dtest=LedgerScenarioCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test` |
| Frontend da U7 | `pnpm --dir apps/web exec jest src/features/pnl src/app/pnl` |
| Smoke da U7 (ambiente de pé) | `pnpm --dir apps/web exec playwright test e2e/u7-ledger-pnl` |
| Cobertura da suíte inteira (piso de 80%) | `apps/api/mvnw -f apps/api/pom.xml verify` e `pnpm --dir apps/web exec jest --coverage` |
| Carga da U7 (só local) | `LOAD_SCENARIO=u7 SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` e `LOAD_SCENARIO=u7-jobs SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` |

O comando do backend da U7 fica pronto no Passo 2, antes do primeiro teste vermelho (Passo 4). Nos passos de testes antes do código, a saída vermelha de cada passo é registrada no resumo do código antes da implementação.

## Metas de cobertura

- 80% de linhas no backend e no frontend, medidos separadamente sobre a suíte inteira.
- Exclusões fixas, só na configuração da ferramenta. A U7 não acrescenta exclusões; o pacote `ledger.loadtest` é coberto pelo teste que confirma que seus beans só existem com o perfil `loadtest`.
- O piso nunca é reduzido para fazer um passo passar.

## Testes por componente

| Componente | Tipo | Quantidade prevista | Casos de risco obrigatórios |
|---|---|---|---|
| Migração `0008` e privilégios | Integração | 15 | Ledger sem edição (`UPDATE`/`DELETE` falham); transições; pico não diminui; só PAPER |
| Cálculo do preenchimento | Unitário (catálogo) | 15 | Cenários C1 a C15 exatos; `unknown` nunca zero; saldo insuficiente |
| Posição, lançamentos, compensação | Unitário | 12 | Custo médio; reinício; compensação só de `FEE` |
| P&L, saída e risco (domínio) | Unitário | 12 | Parcial e `unknown`; soma exata; perda do dia |
| Propriedade | Propriedade | 1 × 500 sequências | Saldo, posição e reconciliação coerentes |
| Repositório e `LedgerReadModel` | Integração | 12 | Isolamento; `ON CONFLICT`; compensações nas pontas |
| `fund`, `execute`, `closePositions` | Integração | 15 | Deduplicação; `NotFilled`; `TENANT_MISMATCH`; REAL desligado |
| Concorrência, atomicidade e travas | Integração | 4 × 50 repetições | Mesma chave em paralelo; sem deadlock; trava presa |
| Fechamento pendente | Integração | 8 | Conclusão única; reinício |
| Maior patrimônio e marcação | Integração | 8 | Sem pico falso |
| Visão de risco | Integração | 8 | Perda do dia sem o resumo |
| P&L do período | Integração | 12 | Pontas com compensação; 366 dias |
| Reconciliação | Integração | 12 | Cada tipo de divergência; compensação válida |
| Rotas C5 | Integração e contrato | 15 | Outra pessoa `404`; dois tenants; `403` com outro modo; limite |
| Saúde, trilha e varreduras | Integração | 8 | Nada sensível em log e métrica |
| Arquitetura | ArchUnit | 8 | Sem `double`/`float`; sem rede; fronteira de módulos |
| Telas | Jest | 15 | Estados distintos; parcial; divergência |
| Smoke | Playwright | 1 execução | Estados vazios e rótulo PAPER |
| Carga | `scripts/load-test.sh` | 2 execuções | p95 de execução e rotas; rodadas das tarefas |

## Mocks e dublês

- **Relógio:** `MutableClock` em todo teste de dia UTC, período, desatualizado e tarefas.
- **Dado de mercado:** dublê da C11 com preço, liquidez, `decimals`, `unknown` e desatualizado sob comando; nenhum acesso de rede.
- **Transação do ciclo:** os testes abrem a transação como a U5 fará (`TransactionTemplate`), já que a U5 não existe.
- **Falha técnica:** repositório de teste que lança exceção em cada gravação.
- **Banco:** PostgreSQL real em contêiner com as migrações; cada teste cria os próprios dados; concorrência com conexões próprias e barreira de início.
- **Publicador da trilha:** o real da U2, conferido pela tabela de saída.
- **Frontend:** API simulada com as respostas do contrato.

## Dados de teste

- Mints, bots e pessoas sintéticos gerados no teste; nada real.
- Valores do catálogo C1 a C15 escritos no teste como texto, a partir da functional-spec.md.
- Teste de propriedade com semente fixa.
- Teste instável é tratado como defeito e corrigido, nunca ignorado.
