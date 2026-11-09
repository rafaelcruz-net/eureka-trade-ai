# Portões de qualidade

Conversa: português (Brasil). Portões que bloqueiam o merge em `main` (resposta 3A). Nenhum merge acontece com a CI vermelha (`team.md`). Os pisos e limites vêm do `team.md` e dos NFRs e nunca são reduzidos para fazer um job passar.

## Portões que bloqueiam o merge

| Portão | Workflow / job | Comando | Critério de reprovação | Origem |
|---|---|---|---|---|
| Build, testes e cobertura do backend | `ci` / `backend` | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | qualquer teste unitário, de integração ou ArchUnit falhando; Spotless; JaCoCo mesclado abaixo de 80% de linhas | `team.md` (piso de 80%); Build and Test |
| Frontend do convidado | `ci` / `frontend` | `pnpm --dir apps/web install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `exec jest --coverage` | qualquer erro de lint, formatação ou tipos; qualquer teste falhando; linhas abaixo de 80% na suíte inteira | `team.md`; lacuna 2 do Build and Test |
| Frontend do administrador | `ci` / `admin` (novo) | `pnpm --dir apps/admin install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `exec jest --coverage`, `check:api` | idem, mais tipos gerados diferentes do contrato administrativo | `team.md`; lacuna 1 do Build and Test |
| Contrato | `ci` / `contract` | `GuestOpenApiContractTest`, `AdminOpenApiContractTest`; `pnpm --dir apps/web check:api` | OpenAPI gerado diferente do versionado; tipos do frontend diferentes do contrato | `team.md` (quebra de contrato falha a CI) |
| Smoke de ponta a ponta | `ci` / `smoke` | `bash scripts/verify.sh` | saída diferente de zero: serviço não saudável, coleta da fonte simulada ausente, tarefas de auditoria não concluídas, qualquer dos 10 testes Playwright falhando (U1, U2, U3, U4, U5, U7, U8, U9), endereço de carteira, texto de feedback ou código de convite nos logs | `team.md` (smoke em toda mudança); comando de verificação autorizado |
| Segredos | `security` / `secrets` | Gitleaks (versão fixada, checksum conferido) | qualquer segredo encontrado | `project.md` (bloquear commits e merges com segredo) |
| Análise estática | `security` / `sast` | CodeQL | achado de nível error (Crítico ou Alto) | `team.md` (Crítico ou Alto bloqueia) |
| Dependências | `security` / `dependencies` | OWASP Dependency-Check (CVSS ≥ 7 bloqueia); `pnpm audit --audit-level high` no `apps/web` e no `apps/admin`; `SecurityExceptionsTest` | vulnerabilidade Crítica ou Alta sem exceção válida; exceção vencida | `team.md`; resposta 5A (o `postcss` 8.5.28 tira os 2 avisos High) |

## Portões só de aviso

| Portão | Onde | Efeito |
|---|---|---|
| Vulnerabilidade Média | `security` / `dependencies` | aviso, não bloqueia (`team.md`) |
| Carga por cenário | `load-test` (manual) | reprova o job quando chamado, mas não roda em pull request nem em merge (resposta 3A) |

## Execução depois do merge

| Portão | Job | Quando |
|---|---|---|
| Suíte completa de ponta a ponta | `ci` / `e2e` | push em `main`, depois de `backend`, `frontend`, `admin`, `contract` e `smoke` |

## Correspondência com o Build and Test

| Comando do Build and Test (`build-instructions.md`) | Portão |
|---|---|
| `apps/api/mvnw -f apps/api/pom.xml verify` e `jacoco:check` | `backend` |
| `apps/web`: `install --frozen-lockfile`, `tsc`, `eslint`, `prettier --check`, `jest --coverage` | `frontend` (com a mudança 1 do `ci-config.md`) |
| `apps/admin`: os mesmos comandos | `admin` (mudança 2 do `ci-config.md`) |
| `bash scripts/verify.sh` | `smoke` |
| `pnpm audit --audit-level high` | `dependencies` |

Enquanto a pessoa não aplicar as mudanças 1 a 3 do `ci-config.md` no `ci.yml`, os portões do `apps/admin` e da suíte inteira do `apps/web` existem só no Build and Test local, não na CI.

## Regras dos portões

- Ações de terceiros fixadas por SHA; `permissions: contents: read`; nenhum segredo de produção em workflow de branch ou fork (`team.md`).
- Lockfiles versionados e instalação com `--frozen-lockfile` em todo job (`project.md`).
- Teste instável é defeito: um job que falha por instabilidade é investigado, nunca desligado nem reexecutado até passar.
- Exceções de segurança só em arquivo versionado, com justificativa, validade de no máximo 90 dias e aprovação registrada da pessoa; exceção vencida volta a bloquear.
