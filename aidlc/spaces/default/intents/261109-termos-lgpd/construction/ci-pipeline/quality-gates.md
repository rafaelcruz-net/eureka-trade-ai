# Portões de qualidade

Conversa: português (Brasil). Portões que bloqueiam o merge em `main` (resposta 3A). Nenhum merge acontece com a CI vermelha (`team.md`). Os pisos e limites vêm do `team.md` e dos NFRs e nunca são reduzidos para fazer um job passar.

## Portões que bloqueiam o merge

| Portão | Workflow / job | Critério de reprovação | Origem |
|---|---|---|---|
| Backend | `ci` / `backend` | qualquer teste de unidade, integração ou ArchUnit falhando; Spotless; JaCoCo mesclado abaixo de 80% de linhas; arquivos do Compose inválidos | `team.md` (piso de 80%); Build and Test |
| Frontend do convidado | `ci` / `frontend` | erro de lint, formatação ou tipos; teste falhando; linhas abaixo de 80% | `team.md` |
| Frontend do administrador | `ci` / `admin` | idem, mais tipos gerados diferentes do contrato administrativo | `team.md` |
| Contrato | `ci` / `contract` | OpenAPI ou tipos gerados diferentes dos versionados | `team.md` (quebra de contrato falha a CI) |
| Ponta a ponta | `ci` / `smoke` | `scripts/verify.sh` com saída diferente de zero: serviço não saudável, qualquer um dos 19 specs falhando (inclusive `e2e/reacceptance`, `e2e/data-requests` e `e2e/anonymization`), carteira, feedback ou convite nos logs | `team.md`; comando de verificação autorizado |
| Segredos | `security` / `secrets` | qualquer segredo | `project.md` |
| Análise estática | `security` / `sast` | achado Crítico ou Alto | `team.md` |
| Dependências | `security` / `dependencies` | CVSS ≥ 7 sem exceção válida, pela base da NVD (o analisador OSS Index está desligado por decisão do responsável de 2026-11-12); exceção vencida (`SecurityExceptionsTest`); `pnpm audit` Alto ou acima | `team.md`; exceções EX-001 a EX-013 |

## Portões só de aviso

| Portão | Onde | Efeito |
|---|---|---|
| Vulnerabilidade Média | `security` / `dependencies` | aviso (`team.md`) |
| Carga por cenário | `load-test` (manual) | reprova o job quando chamado; não roda em pull request nem em merge |
| Abertura do painel da U9 (`DashboardOpenLatencyIT`) | `ci` / `backend` | p95 acima de 2 s vira `WARN` no log, não reprova (decisão Q1 = B do responsável, anotada no NFR3.1 da U9) |

## Correspondência com o Build and Test

| Comando do Build and Test (`build-instructions.md`) | Portão |
|---|---|
| `env -u MEMETRADE_AUDIT_WALLET_HMAC_KEY sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | `backend` |
| `apps/web`: `install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `check:api`, `test:coverage` | `frontend` e `contract` |
| `apps/admin`: os mesmos comandos | `admin` |
| `bash scripts/verify.sh` | `smoke` |
| `gitleaks detect`, `pnpm audit --audit-level high`, OWASP Dependency-Check | `secrets`, `dependencies` |
| `LOAD_SCENARIO=<cenário> ... bash scripts/load-test.sh` | `load-test` (manual, aviso) |

Todo comando que o Build and Test registrou tem um portão na CI; a carga é o único que não roda em pull request, por decisão (resposta 3A).

## Regras dos portões

- Ações de terceiros fixadas por SHA; `permissions: contents: read`; nenhum segredo de produção em workflow de branch ou fork.
- Lockfiles versionados e `--frozen-lockfile` em todo job.
- Teste instável é defeito: um job que falha por instabilidade é investigado e corrigido na causa, nunca desligado nem reexecutado até passar (exemplos deste intent: o log reiniciado pelo `MigrationRunner` e a cópia oculta do streaming no `/privacidade`).
- Exceções de segurança só em `security/exceptions.yml`, com justificativa, validade de no máximo 90 dias e aprovação registrada do responsável; exceção vencida volta a bloquear.
- Depois de mudar um workflow, confirmar com `gh run list` que a execução de fato disparou.
