# Instruções de build

Conversa: português (Brasil). Cobre o monorepo inteiro depois das Units U1 a U9.

## Pré-requisitos

| Item | Versão ou valor | Onde está fixado |
|---|---|---|
| Java | 21 (Temurin) | `apps/api/pom.xml`, `.github/workflows/ci.yml` |
| Maven | wrapper `apps/api/mvnw` | `apps/api/.mvn/wrapper/` |
| Node | LTS fixado no projeto | `.nvmrc` / campo `engines` |
| pnpm | 10.18.3 por `corepack` | campo `packageManager` |
| Docker | Docker Desktop com Compose v2 | `docker-compose.yml`, `infra/docker-compose.test.yml` |
| Variáveis | `.env` local copiado de `.env.example`, sem valores reais no repositório | `.env.example` |

No Windows, os scripts rodam no Git Bash. Os scripts de carga geram uma chave de assinatura aleatória em base64; quando ela começa com `/`, o MSYS pode converter o valor em caminho (ver "Limitações").

## Backend (`apps/api`)

```bash
apps/api/mvnw -f apps/api/pom.xml verify
apps/api/mvnw -f apps/api/pom.xml jacoco:report@report-merged jacoco:check@check-line-coverage
```

- `verify` roda `spotless:check`, os testes de unidade (Surefire), os de integração (Failsafe, PostgreSQL real por Testcontainers) e as regras ArchUnit.
- O segundo comando mescla os `.exec` e falha abaixo de 80% de linhas.

## Frontend (`apps/web` e `apps/admin`)

```bash
corepack pnpm@10.18.3 install --frozen-lockfile
corepack pnpm@10.18.3 --dir apps/web exec tsc --noEmit
corepack pnpm@10.18.3 --dir apps/web exec eslint .
corepack pnpm@10.18.3 --dir apps/web exec prettier --check .
corepack pnpm@10.18.3 --dir apps/web exec jest --coverage
```

Os mesmos comandos valem para `apps/admin`, trocando `--dir`. O limite de cobertura do Jest falha o comando abaixo de 80%.

## Ambiente completo e verificação de ponta a ponta

`bash scripts/verify.sh` é o comando de verificação autorizado. Ele sobe `docker-compose.yml` com `infra/docker-compose.test.yml`, espera os serviços ficarem saudáveis, roda os smokes Playwright e devolve código diferente de zero em qualquer falha. O arquivo é protegido, e só a pessoa responsável pelo projeto o altera.

## Resultado desta execução (2026-11-06)

| Comando | Resultado |
|---|---|
| `mvnw verify` | **BUILD FAILURE**: Surefire 890/890; Failsafe 748 testes, 2 falhas (ver `test-results.md`) |
| JaCoCo mesclado | 12331 de 13139 linhas, 93,85%, piso cumprido |
| `apps/web` | `tsc`, `eslint` e `prettier` limpos; Jest 33 suítes, 391/391; linhas 96,03% |
| `apps/admin` | `tsc`, `eslint` e `prettier` limpos; Jest 10 suítes, 85/85; linhas 91,34% |
| `scripts/verify.sh` | saída 0, 8 testes Playwright (prova do ponto de controle da U9, 2026-11-06 17:07–17:10) |

## Limitações

- `scripts/verify.sh` ainda não roda `e2e/u3-identity-access` nem `e2e/u9-dashboard`. As duas inclusões estão pendentes com a pessoa.
- `scripts/load-test.sh` não define `MSYS2_ENV_CONV_EXCL`. A primeira execução do cenário `u3` falhou ao subir a API, e a segunda passou. É o mesmo assunto do achado R-06 da U1, ainda em aberto.
