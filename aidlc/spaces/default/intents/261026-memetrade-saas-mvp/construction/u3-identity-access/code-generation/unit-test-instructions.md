# Instruções de testes — U3 Convites, contas e sessões

Como preparar, rodar e medir os testes da Unit `u3-identity-access`, conforme o [plano de geração de código](code-generation-plan.md). A estratégia de testes do workflow é `Comprehensive`: 10 a 15 testes por componente, com unitários, integração e ponta a ponta, mais desempenho e segurança onde os NFRs pedem. O piso de 80% de linhas do escopo `enterprise` soma-se a ela. A U3 não faz parte do núcleo financeiro, então todos os passos seguem a ordem test-after do `team.md`.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), ArchUnit, JaCoCo | `apps/api/pom.xml` existente: Surefire para unitários (`*Test`), Failsafe para integração (`*IT`), JaCoCo `check` com 80% de linhas no `verify` completo; marcador `u3` |
| Base de integração | `AbstractPostgresIT` (U2) | Mesmas migrações da aplicação, inclusive a `0007` |
| Frontend | Jest e Testing Library | `apps/web/jest.config.ts` existente; testes em `src/features/auth`, `src/features/account`, `src/app/convite`, `src/app/conta`, `src/content` e `src/middleware.test.ts` |
| Smoke | Playwright | `apps/web/e2e/u3-identity-access/`, trace desligado |
| Carga | `scripts/load-test.sh` com `scripts/load/load-test.mjs` | Cenário `u3`; só local |

Pré-requisitos: Docker Desktop em execução, JDK 21, Node LTS do `.nvmrc` com pnpm, Git Bash no Windows.

## Comandos desta Unit

| O que roda | Comando exato |
|---|---|
| Backend da U3, unitários e integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u3 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Backend da U3, uma classe só | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u3 -Dit.test=InviteVerificationIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Frontend da U3 | `pnpm --dir apps/web exec jest src/features/auth src/features/account src/app/convite src/app/conta src/content src/middleware.test.ts` |
| Smoke da U3 (ambiente de pé, com `SMOKE_INVITE_CODE` exportado) | `pnpm --dir apps/web exec playwright test e2e/u3-identity-access` |
| Cobertura da suíte inteira (piso de 80%) | `apps/api/mvnw -f apps/api/pom.xml verify` e `pnpm --dir apps/web exec jest --coverage src/features/auth src/features/account src/features/scanner src/app/convite src/app/conta src/lib src/middleware.test.ts` |
| Carga da U3 (só local) | `LOAD_SCENARIO=u3 SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` |

O comando do backend da U3 fica pronto no Passo 2, antes do primeiro teste da Unit (Passo 3).

## Metas de cobertura

- 80% de linhas no backend e no frontend, medidos separadamente sobre a suíte inteira.
- Exclusões fixas, só na configuração da ferramenta, como nas Units anteriores. A U3 não acrescenta exclusões.
- O piso nunca é reduzido para fazer um passo passar; a falta de cobertura é registrada no resumo do código.

## Testes por componente

| Componente | Tipo | Quantidade prevista | Casos de risco obrigatórios |
|---|---|---|---|
| Migração `0007` | Integração | 10 | Unicidades; `uq_wallet_address` única; `terms_acceptance` sem `UPDATE`/`DELETE`; tenant `Beta` uma vez; rollback |
| Termos (`TermsCatalog`) | Unitário e integração | 8 | API não sobe sem termos; termos proibidos |
| Código e estado do convite | Unitário | 12 | Entropia e alfabeto; formato; `expiresAt` e `+1 s` |
| Repositório de convites | Integração | 10 | Bloqueio real; contagens com quatro chaves |
| Emissão, lista, revogação | Integração | 15 | Validade 1 h e 30 dias; idempotência sem o código (risco); revogação repetida sem evento |
| Limitador da consulta | Unitário e integração | 10 | 10/11; contador separado; limpeza |
| Rota pública e consulta | Integração | 12 | `POST` e subcaminhos com `401`; mal formado igual a desconhecido; cabeçalhos |
| Verificação com convite | Integração | 15 | Ordem das recusas; nonce reutilizado recusado (risco); falha técnica sem consumo |
| Concorrência | Integração | 3 × 50 repetições | Um convite uma conta; uma carteira uma conta; revogação contra verificação |
| Tela Conta (API) | Integração | 10 | Entre tenants (risco); REAL indisponível (risco) |
| Rotas administrativas | Integração e contrato | 12 | Token de convidado `403` com evento; `code` só no `201` |
| Registro de rotas e varredura de tenant | Unitário | 6 | `tenantFromClient` só em `ADMIN`; `tenantId` sem marca falha |
| Arquitetura e varreduras | ArchUnit e integração | 8 | Semeadores só em `test`; nada sensível em log e métrica |
| Semeador do smoke | Integração | 5 | Só no perfil `test` |
| Página do convite | Jest | 15 | Botão com motivo; aceites preservados; estados; sem HTML injetado |
| Sessão expirada, origem, Sair | Jest | 15 | `safeOrigin` com os casos maliciosos; aviso em vez de erro |
| Tela Conta | Jest | 12 | Sem controle de REAL; horário local |
| Termos proibidos | Jest | 3 | Textos da U3 |
| Smoke | Playwright | 1 execução | Convite → conta → Conta → Sair |
| Carga | `scripts/load-test.sh` | 1 execução | p95 por rota; sem `429` no perfil `loadtest` |

## Mocks e dublês

- **Relógio:** `MutableClock` em todo teste de prazo (validade do convite, desafio, limitador, sessão).
- **Aleatoriedade:** o gerador do código é trocado só nos testes que precisam de código fixo; a produção usa só `SecureRandom`.
- **Carteiras:** geradas no código de teste com o SDK de teste; nunca carteira real; nenhuma chave no código de produção.
- **Banco:** PostgreSQL real em contêiner com as migrações; cada teste cria os próprios dados; testes de concorrência usam conexões próprias e barreira de início.
- **Falha técnica:** repositório de teste que lança exceção na inserção do aceite, do evento ou da sessão.
- **Administrador:** sessão de administrador criada só no código de teste (apoio da U2).
- **Publicador da trilha:** o real da U2, conferido pelas linhas da tabela de saída.
- **Frontend:** API simulada nos testes Jest com as respostas do contrato; `sessionStorage` real do jsdom.

## Dados de teste

- Códigos de convite e carteiras gerados no teste; nada real.
- Tenants criados no teste, além do `Beta` semeado pela migração.
- Teste instável é tratado como defeito e corrigido, nunca ignorado.
