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
corepack pnpm@10.18.3 --dir apps/web install --frozen-lockfile
corepack pnpm@10.18.3 --dir apps/web exec tsc --noEmit
corepack pnpm@10.18.3 --dir apps/web exec eslint .
corepack pnpm@10.18.3 --dir apps/web exec prettier --check .
corepack pnpm@10.18.3 --dir apps/web exec jest --coverage
```

Os mesmos comandos valem para `apps/admin`, trocando `--dir`. O limite de cobertura do Jest falha o comando abaixo de 80%.

## Ambiente completo e verificação de ponta a ponta

`bash scripts/verify.sh` é o comando de verificação autorizado. Ele sobe `docker-compose.yml` com `infra/docker-compose.test.yml`, espera os serviços ficarem saudáveis, roda os smokes Playwright e devolve código diferente de zero em qualquer falha. O arquivo é protegido, e só a pessoa responsável pelo projeto o altera.

## Resultado desta execução (2026-11-08, quarta)

| Comando | Resultado |
|---|---|
| `mvnw verify` | BUILD SUCCESS: Surefire 909/909; Failsafe 763/763 (volta 3 da U6, sobre o código final) |
| JaCoCo mesclado | 13339 de 14204 linhas, 93,91%, piso cumprido |
| `apps/web` | `tsc`, `eslint` e `prettier` limpos; Jest 33 suítes, 391/391 em 3 execuções seguidas (volta 3 da U5); linhas 96,03% |
| `apps/admin` | `install --frozen-lockfile`, `tsc`, `eslint` e `prettier` limpos; Jest 10 suítes, 85/85; linhas 91,34% |
| `scripts/verify.sh` | saída 0, 10 testes Playwright com os smokes da U3 e da U9 (pontos de controle do esqueleto e da U3, 2026-11-08) |

O repositório não tem `package.json` na raiz, então cada app instala com `--dir` (igual a `.github/workflows/ci.yml`).

## Limitações

- `scripts/verify.sh` passou a rodar `e2e/u3-identity-access` e `e2e/u9-dashboard` em 2026-11-08 (mudança feita pela pessoa). O código do convite de smoke é capturado com `tr -d '\r\n'` por causa do fim de linha do Git Bash no Windows.
- `scripts/load-test.sh` não define `MSYS2_ENV_CONV_EXCL`. A primeira execução do cenário `u3` falhou ao subir a API, e a segunda passou. É o mesmo assunto do achado R-06 da U1, ainda em aberto.
