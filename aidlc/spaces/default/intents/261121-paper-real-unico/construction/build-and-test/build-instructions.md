# Instruções de build — intent `261121-paper-real-unico`

Data: 2026-11-25. Commit de referência: `fc65b7f` (HEAD de `main`, já no remoto). Units: `u1-mode-skeleton`, `u2-real-bot-pnl`, `u3-pnl-views`, `u4-own-quantity-cycle`.

O intent não cria app novo. Ele muda `apps/api` (módulos `bots`, `pnl`, `ledger`, `riskcontrol`, `realexecution`), `apps/web`, `infra/observability/`, `infra/terraform/`, `scripts/load/` e o spec `apps/web/e2e/zz-paper-real-unico/`. O `apps/txsigner` e o `apps/admin` não mudam, mas o build deles continua obrigatório (`team.md`, Frequência).

## 1. Pré-requisitos

| Ferramenta | Versão | Onde está fixada | Observação |
|---|---|---|---|
| JDK | Temurin 21 | `actions/setup-java` no `ci.yml`; `maven-enforcer` (`RequireJavaVersion`) | A máquina local tem Temurin 25 e 17; o enforcer aceita o 25 localmente. |
| Maven | wrapper | `apps/api/mvnw`, `apps/txsigner/mvnw` | Não instalar Maven global. |
| Node | 24 LTS | `.nvmrc` (`24`) e `engines` | |
| pnpm | 10.18.3 | `packageManager` em `apps/web/package.json` e `apps/admin/package.json` | Nesta máquina o `pnpm` não está no `PATH` do Git Bash. Use `corepack pnpm@10.18.3 ...`; `corepack pnpm` sozinho chama o pnpm 12 e falha com `ERR_PNPM_BAD_PM_VERSION`. |
| Docker | 29.x, com Compose | Docker Desktop | Testcontainers (PostgreSQL 17, promtool por digest) e o `scripts/verify.sh`. |
| Bash | Git Bash no Windows; bash na CI | | `scripts/verify.sh`, `scripts/verify-checkpoint.sh`, `scripts/load-test.sh`. |
| Terraform e Checkov | — | — | **Não instalados nesta máquina.** Os testes da IaC rodam à mão (`team.md`, Testes da IaC). |

## 2. Dependências

```bash
# Backend e TxSigner: o wrapper baixa as dependências no primeiro build
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml dependency:go-offline
sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml dependency:go-offline

# Apps web, sempre a partir dos lockfiles versionados
corepack pnpm@10.18.3 --dir apps/web install --frozen-lockfile
corepack pnpm@10.18.3 --dir apps/admin install --frozen-lockfile
corepack pnpm@10.18.3 --dir apps/web exec playwright install chromium
```

## 3. Ambiente

- `.env` local a partir do `.env.example` (sem valores no repositório). Nenhum segredo de verdade é necessário para build e testes: o `verify.sh` gera carteiras, códigos e chaves de teste efêmeros com ferramentas só de teste.
- Perfis Spring: os testes usam `local`/`test`; o cenário de carga usa `test,loadtest` (só em `scripts/load-test.sh`).
- Flags do REAL ficam desligadas por padrão: `memetrade.real.enabled=false`, `txsigner.real.enabled=false` e, da U4, `memetrade.real.own-quantity-sell=false` (`MEMETRADE_REAL_OWN_QUANTITY_SELL`). Não ligue nenhuma para build nem teste.
- Migrações novas do intent (Liquibase, aditivas): `0023-real-entry-slot`, `0024`, `0025-real-position-epoch`, `0026-bot-cycle-own-quantity-outcomes`, `0027-real-decision-request-bot-pending-sell-idx`. Os testes de integração aplicam as mesmas migrações da aplicação no PostgreSQL do Testcontainers.

## 4. Comandos de build

Os mesmos da CI (`.github/workflows/ci.yml`):

| Parte | Comando | O que inclui |
|---|---|---|
| Backend | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | compilação, Spotless (palantir), enforcer (Java, Maven, dependências proibidas, SDK Solana fora da produção), surefire, failsafe com Testcontainers, ArchUnit, JaCoCo mesclado com piso de 80% de linhas |
| Regras de alerta | `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool <imagem pelo digest do ci.yml> check rules /rules/<arquivo>.yml` | os 6 arquivos de regras (`real-alerts`, `marketdata-alerts`, `real-risk-alerts`, `real-execution-alerts`, `real-ops-alerts`, `real-exits-alerts`) |
| TxSigner | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` | testes, JaCoCo 80%, Spotless |
| Web | `pnpm --dir apps/web lint`, `format:check`, `typecheck`, `exec jest --coverage`, `check:api` | ESLint, Prettier, `tsc --noEmit`, Jest com piso de 80%, tipos gerados iguais ao `openapi-guest.yaml` |
| Admin | `pnpm --dir apps/admin lint`, `format:check`, `typecheck`, `exec jest --coverage`, `check:api` | o mesmo, contra o `openapi-admin.yaml` |
| Contrato | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml -Dtest=GuestOpenApiContractTest,AdminOpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | os dois contratos OpenAPI |
| Ponta a ponta | `bash scripts/verify.sh` | sobe o Compose de teste, espera os serviços, roda os specs Playwright (o último é `e2e/zz-paper-real-unico`), varre os logs por endereços completos e devolve código diferente de zero em qualquer falha |

## 5. Verificação do build

1. **Prova oficial nesta máquina:** o `mvnw verify` completo e o `verify.sh` são mortos por falta de memória neste notebook. Pela regra do `team.md` (Walking Skeleton, Verificação dos pontos de controle), a prova é o run verde do `ci` do mesmo commit, com a árvore sem mudança fora dos registros do AI-DLC e o commit no remoto.
2. Para o commit `fc65b7f`: `gh run view 45516978139 --json jobs` mostra os 7 jobs (`contract`, `admin`, `backend`, `smoke`, `txsigner`, `frontend`, `e2e`) com `success`. `gh run view --job <id> --log` dá os números (ver `test-results.md`).
3. A árvore local só tem mudanças nos registros do AI-DLC (`aidlc/spaces/default/intents/261121-paper-real-unico/`) e o arquivo de sessão não rastreado `apps/web/aidlc/.aidlc-sessions/<sessão>.binding.json`, que é estado de máquina do AI-DLC, fora do código e inexistente na CI.
4. Execução local permitida (leve, uma de cada vez): `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage`, `corepack pnpm@10.18.3 --dir apps/admin exec jest --coverage`, `node --test "scripts/load/*.test.mjs"`.

## 6. Problemas comuns

| Sintoma | Causa | Saída |
|---|---|---|
| `pnpm: command not found` no Git Bash | pnpm não está no `PATH` | `corepack pnpm@10.18.3 ...` |
| `ERR_PNPM_BAD_PM_VERSION` | corepack chamou o pnpm 12 | fixe a versão: `corepack pnpm@10.18.3` |
| `mvnw verify` ou `verify.sh` morto sem mensagem (código 137 ou máquina travada) | memória insuficiente (failsafe com Testcontainers e o Compose inteiro) | rode só classes direcionadas com `-Dtest=`/`-Dit.test=` e `-Djacoco.skip=true`; a prova completa é o `ci` do commit |
| `node --test scripts/load` falha | o Node 24 não aceita diretório | use o padrão entre aspas: `node --test "scripts/load/*.test.mjs"` |
| `format:check` do `apps/web` acusa `apps/web/aidlc/.aidlc-sessions/...binding.json` | arquivo de sessão do AI-DLC criado quando um comando rodou com o diretório em `apps/web` | não é código; o responsável decide se o remove. Na CI ele não existe. |
| `pull access denied for memetrade-api` no início do `verify.sh` | a imagem local ainda não existe; o Compose tenta o registro antes de construir | esperado; o script constrói a imagem em seguida |
| `RealBotHoldingsMeasurementIT` demora ou pesa | o padrão é 10.000 leituras por semente | a CI usa 300 leituras e nenhum ciclo; a medida oficial passa `-Du4.measure.reads=10000 -Du4.measure.cycles=100` |
