# Instruções de teste — U3 `u3-reacceptance`

Conversa: português (Brasil). Estratégia `Comprehensive`: 10 a 15 testes por componente, de unidade, de integração e de ponta a ponta. O piso é de 80% de linhas no backend e no web. Pela ordem do time, os testes da trava e do aceite vêm antes do código (Passo 2 do plano); nas demais camadas, cada camada é implementada e depois testada.

## Ferramentas e configuração

| Parte | Ferramenta | Configuração existente |
|---|---|---|
| Backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL) e JaCoCo | `apps/api/pom.xml` (regra `check` em 80%) |
| Web | Jest e Testing Library | `apps/web/jest.config.*` (limite de 80%) |
| Ponta a ponta | Playwright, pelo `scripts/verify.sh` | `apps/web/playwright.config.ts` |
| Carga | Node, com `node --test` para o próprio script | `scripts/load/` |

## Comandos da Unit (só os testes desta Unit)

Rodar da raiz do repositório.

```bash
# Backend: trava, aceite, rotas, worker, migração, Conta e seeder
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml verify -Dtest='TermsReacceptance*Test,TermsAcceptance*Test,ReacceptanceMigration*Test,AccountQueryService*Test,SmokeAccountSeeder*Test,ProblemResponse*Test' -Dit.test='TermsAcceptance*IT,TermsReacceptance*IT,ReacceptanceWorker*IT,AccountRoute*IT,ProcessRoleContextIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true

# Web: telas de novo aceite, convite, Conta e cliente da API
corepack pnpm@10.18.3 --dir apps/web exec jest src/features/reacceptance src/features/auth src/features/account src/lib/api --coverage=false

# Script de carga (só o teste do cenário)
node --test scripts/load/load-test-reacceptance.test.mjs

# Ponta a ponta da Unit (com o ambiente do verify.sh já de pé)
corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/reacceptance
```

Antes do Passo 2, os comandos já rodam e devolvem "nenhum teste encontrado" sem falhar.

### Volta do Build and Test (Passos 15 e 16)

O comando do backend acima já pega os testes novos do aquecimento: `TermsReacceptanceWarmupTest` (padrão `TermsReacceptance*Test`) e `TermsReacceptanceWarmupIT` (padrão `TermsReacceptance*IT`). O `ProcessRoleContextIT` confere que o aquecimento é um trabalho só da `api`. Para rodar só esses testes:

```bash
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml verify -Dtest='TermsReacceptanceWarmup*Test' -Dit.test='TermsReacceptanceWarmup*IT,ProcessRoleContextIT,WorkerProcessRoleContextIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

O teste do script de carga continua o mesmo comando (`node --test scripts/load/load-test-reacceptance.test.mjs`), agora com a leitura de `hikaricp_connections_acquire_seconds`. No Node 24 do Windows, `node --test scripts/load/` (a pasta) não é aceito; para todos os testes de carga, use `node --test 'scripts/load/*.test.mjs'`.

## Metas de cobertura

80% de linhas no backend e no web, medido pela configuração existente. As exclusões ficam só na configuração da ferramenta.

## Mocks e dados de teste

- **Relógio:** `Clock` fixo nos testes de serviço.
- **Catálogo:** o catálogo de teste `terms-test/reviewed`, com a `v1` e a `v2` revisada, esta vigente. A pessoa desatualizada tem aceite só da `v1`.
- **Banco:** Testcontainers com as migrações da aplicação, sem estado compartilhado.
- **Carteiras:** geradas no código de teste, nunca reais.
- **App:** API simulada por `fetch` mockado; roteador simulado no teste do tratador.
