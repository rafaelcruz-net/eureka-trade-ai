# Instruções de build — intent `261113-trading-real-bot`

Escopo: as sete Units do REAL (U1 `u1-bot-wallet` a U7 `u7-exits-and-fiscal`), construídas no monorepo já existente. Este arquivo descreve como instalar, configurar, compilar e conferir o build da API (`apps/api`), do TxSigner (`apps/txsigner`), do app web (`apps/web`) e do app administrativo (`apps/admin`).

## 1. Pré-requisitos

| Ferramenta | Versão | Onde está fixada |
|---|---|---|
| JDK | Temurin 21 | `ci.yml` (`actions/setup-java`, `java-version: '21'`); enforcer `RequireJavaVersion` nos dois `pom.xml` |
| Maven | wrapper do projeto | `apps/api/mvnw`, `apps/txsigner/mvnw` (enforcer `RequireMavenVersion`) |
| Node.js | LTS 24 | `.nvmrc` |
| pnpm | 10.18.3 | campo `packageManager` de `apps/web/package.json` e `apps/admin/package.json`; use `corepack pnpm@10.18.3` |
| Docker | Docker Desktop (Windows) ou Docker Engine (CI Linux) | necessário para Testcontainers, `docker compose` e `scripts/verify.sh` |
| Git Bash | qualquer versão recente | os scripts `scripts/*.sh` rodam em Bash |

Fim de linha LF e UTF-8 em tudo (`.gitattributes`, `.editorconfig`); exceção CRLF só em `*.cmd`/`*.bat`.

## 2. Instalação das dependências

Os lockfiles são versionados e a instalação parte sempre deles (`project.md`, Mandated).

```bash
# Frontend e administrativo (lockfile congelado, como na CI)
corepack pnpm@10.18.3 --dir apps/web install --frozen-lockfile
corepack pnpm@10.18.3 --dir apps/admin install --frozen-lockfile

# Backend e TxSigner: o Maven baixa as dependências no primeiro build
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml dependency:go-offline
sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml dependency:go-offline
```

## 3. Ambiente

- **Variáveis:** ficam em `.env` local, ignorado pelo git; o repositório guarda só o `.env.example`, sem valores. Na CI não há `.env`: o `scripts/verify.sh` gera segredos efêmeros por execução.
- **Flags do REAL:** `memetrade.real.enabled`, `txsigner.real.enabled` e `memetrade.real.exits.enabled` vêm **desligados por padrão** em todos os perfis. Os testes de "REAL desligado por padrão" dependem disso; não ligue os flags no `application.yml`.
- **Serviços locais:** `docker-compose.yml` (base), `infra/docker-compose.test.yml` (overlay de teste) e `infra/docker-compose.real.yml` (REAL local: `txsigner`, `txsigner-migrate`, `kms-sim`, `solana-validator`). O TxSigner não publica porta.
- **Fixtures do REAL:** `infra/solana-fixtures/` (contas clonadas, programas, mapeamentos do stub da Jupiter, `recorded.env` da gravação do swap). A gravação da Trigger (portão BR0 da U7) ainda não existe.
- **Proibições:** nenhum teste usa devnet, mainnet, KMS real ou chave real (`team.md`, Determinismo). O `AwsKmsEd25519ProofTest` (`@Tag("aws-proof")`) fica fora da CI pelo `excludedGroups`.

## 4. Comandos de build

| Artefato | Comando | O que faz |
|---|---|---|
| API | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | compila, Spotless, Surefire + Failsafe (Testcontainers, PostgreSQL 17), JaCoCo merged com `check` de 80% de linhas, enforcer (SDK Solana proibido fora de `test`), ArchUnit |
| TxSigner | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` | o mesmo para o processo isolado, com JaCoCo 80% |
| Web | `corepack pnpm@10.18.3 --dir apps/web lint`, `format:check`, `typecheck`, `exec jest --coverage`, `check:api` | lint, Prettier, TypeScript `strict`, Jest com `coverageThreshold` de 80%, conferência dos tipos gerados do OpenAPI |
| Administrativo | os mesmos scripts com `--dir apps/admin` | idem |
| Imagens e ambiente | `bash scripts/verify.sh` | constrói as imagens marcadas com `IMAGE_TAG`/`BUILD_SHA`, sobe o ambiente, espera a saúde e roda todos os specs Playwright |
| Regras de alerta | `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus@sha256:63805ebb… check rules /rules/<arquivo>.yml` | valida os seis arquivos de regras (`real-alerts`, `marketdata-alerts`, `real-risk-alerts`, `real-execution-alerts`, `real-ops-alerts`, `real-exits-alerts`) |
| Composição | `docker compose -f docker-compose.yml config --quiet` e com `-f infra/docker-compose.test.yml` | confere que os arquivos de composição resolvem |

## 5. Verificação do build

O build está bom quando:

1. os dois `mvnw verify` terminam com `BUILD SUCCESS` e `All coverage checks have been met.`;
2. os jobs do web e do administrativo terminam sem erro de lint, formatação, tipos, testes ou `check:api`;
3. o `promtool` responde `SUCCESS` para os seis arquivos;
4. o `scripts/verify.sh` termina com código 0 e todos os specs passam;
5. o workflow `security` (Gitleaks, CodeQL, Dependency-Check e `pnpm audit`) termina verde.

Na execução de referência (commit `d7e6a17997aa242c4ad2a7693336c6e58f281521`, reexecução depois da volta 1), os itens 1 a 4 estão verdes no run `ci` 44688329451, e o item 5 está verde no run `security` 44688329359 (tentativa 2; a tentativa 1 falhou só por erro de rede ao baixar o Maven). Ver `test-results.md`.

## 6. Regra de execução nesta máquina

A máquina de desenvolvimento tem pouca memória: o `mvnw verify` completo e o `scripts/verify.sh` completo morrem por falta de memória. Pela regra acordada no projeto, roda-se primeiro o que cabe localmente (Jest, `pnpm audit`); quando uma execução morre por memória, vale como evidência o run `ci` do GitHub Actions do mesmo commit.

## 7. Problemas comuns

| Sintoma | Causa provável | Correção |
|---|---|---|
| `mvnw verify` morto sem mensagem, ou Testcontainers sem resposta | memória insuficiente no Windows | rodar só o subconjunto da Unit (`-Dtest=…`, ver `unit-test-instructions.md` de cada Unit) ou usar o run da CI |
| `Could not find a valid Docker environment` | Docker Desktop parado | iniciar o Docker Desktop; conferir `docker info` |
| `ERR_PNPM_OUTDATED_LOCKFILE` | lockfile desatualizado | não use `--no-frozen-lockfile`; atualize o lockfile num commit próprio |
| `check:api` falha | contrato mudou sem regenerar os tipos | `corepack pnpm@10.18.3 --dir apps/web gen:api` (e no administrativo) e commit |
| Teste passa no Windows e falha na CI | fim de linha CRLF | conferir `.gitattributes`; `git add --renormalize .` |
| `real-swap` pulado | `RECORDED_FIXTURES` ausente | `infra/solana-fixtures/recorded.env` precisa existir; o `verify.sh` o carrega |
| `enforcer` recusa dependência | SDK Solana em escopo de produção | mover para `test` (`project.md`, Forbidden) |
