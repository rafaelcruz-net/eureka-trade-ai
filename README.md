# MemeTrade Analytics — beta PAPER por convite

Monorepo do MemeTrade Analytics. Esta versão contém o **esqueleto de ponta a ponta (U1)**: entrada por assinatura de
carteira, sessão, auditoria por tabela de saída e uma lista mínima do scanner alimentada por uma **fonte de mercado
simulada**. Não há trading REAL, carteira real nem fornecedor de mercado real.

A **trilha de auditoria (U2)** completa a auditoria da U1:

- validação no publicador;
- consulta restrita ao administrador (`GET /api/v1/admin/audit-events`);
- retenção de 12 meses com expurgo diário por um papel de banco próprio;
- limpeza da tabela de saída;
- logins de banco separados, com migrações fora da API.

| Pasta | Conteúdo |
|---|---|
| `apps/api/` | API Spring Boot (Java 21, Maven Wrapper), módulos `shared`, `identity`, `marketdata`, `scanner`, `audit` |
| `apps/web/` | App do convidado Next.js + TypeScript `strict` (pnpm) |
| `infra/` | Sobreposição de teste do Compose e scripts do banco (criação dos logins e privilégios) |
| `scripts/` | `setup.sh`, `verify.sh` (verificação da fatia) e teste de carga |
| `docs/adr/` | Registros de decisão de arquitetura |
| `docs/api/` | Operações administrativas do contrato C8 providas pelos módulos |
| `.github/` | CI, segurança, teste de carga manual e Dependabot |

## Pré-requisitos

- **Docker Desktop** em execução (Compose e Testcontainers).
- **JDK 21** (o build exige Java 21 ou mais novo; as imagens usam o JRE 21).
- **Node LTS** da versão em `.nvmrc` e **pnpm** da versão em `apps/web/package.json` (`packageManager`). Com o
  corepack: `corepack enable` ou `corepack pnpm@10.18.3 …`.
- **Gitleaks** na versão de `.gitleaks-version`, para o hook de pré-commit:
  `winget install --id Gitleaks.Gitleaks --version 8.28.0` (Windows) ou o pacote do seu sistema.
- No Windows, rode os comandos no **Git Bash**.

## Primeira configuração

```bash
bash scripts/setup.sh          # liga o hook de pré-commit (Gitleaks) e confere a versão
cp .env.example .env           # preencha os valores locais; .env nunca é versionado
```

Gere a chave do token de acesso com, por exemplo, `openssl rand -base64 48` e coloque em `ACCESS_TOKEN_SIGNING_KEY`.

## Rodar localmente

```bash
docker compose up --build              # postgres, logins, migrações, api (8080/8081) e web (3000), só em 127.0.0.1
docker compose build api               # imagem usada também pelo db-migrate
docker compose up postgres db-bootstrap db-migrate
                                       # "perfil dev": banco com logins e migrações, para rodar api e web fora de contêiner
```

A API nunca aplica migrações (NFR6.3): `db-bootstrap` cria os logins e privilégios e `db-migrate` aplica o Liquibase
com o login dono do esquema. Depois de uma nova migração, rode `docker compose up db-migrate` de novo.

Com o banco no ar:

```bash
# API (lê DB_USER, DB_PASSWORD, DB_RETENTION_*, ACCESS_TOKEN_SIGNING_KEY e GUEST_APP_ORIGIN do ambiente)
apps/api/mvnw -f apps/api/pom.xml spring-boot:run
# App do convidado
pnpm --dir apps/web install --frozen-lockfile
API_ORIGIN=http://localhost:8080 pnpm --dir apps/web dev
```

Saúde e métricas ficam na porta de gestão: `http://127.0.0.1:8081/actuator/health` e `/actuator/prometheus`.

## Testes da U1

| O que roda | Comando |
|---|---|
| Backend, unitários e integração, com cobertura (80% de linhas) | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` |
| Backend, um teste só | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 -Dtest=IdempotencyServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` |
| Frontend, unitários com cobertura (80% de linhas) | `pnpm --dir apps/web exec jest --coverage src/features/auth src/features/scanner src/lib src/middleware.test.ts` |
| Ponta a ponta (com o ambiente de teste no ar) | `pnpm --dir apps/web exec playwright test e2e/u1-walking-skeleton` |
| Verificação completa da fatia | `bash scripts/verify.sh` |

Os testes de integração (`*IT`) usam PostgreSQL 17 real via Testcontainers e precisam do Docker.

Outros comandos úteis: `pnpm --dir apps/web lint`, `format:check`, `typecheck`, `gen:api` (gera os tipos a partir de
`apps/api/openapi/openapi-guest.yaml`) e `check:api`. Para regenerar o OpenAPI do backend depois de mudar a API:
`apps/api/mvnw -f apps/api/pom.xml -Dtest=GuestOpenApiContractTest -Dopenapi.write=true test`.

## Trilha de auditoria (U2)

### Modo de desenvolvimento

`docker compose up postgres db-bootstrap db-migrate` sobe só o banco, já com os logins e as migrações. API e app
rodam fora de contêiner, com o login de aplicação e o de retenção do `.env` (veja "Rodar localmente"). O
`.env.example` lista as quatro senhas de banco: superusuário, dono, aplicação e retenção.
`AUDIT_RETENTION_MONTHS` é opcional: o padrão é 12, e a API não sobe com menos que isso.

### Tarefas diárias

- **Expurgo da trilha**, às 03:00 UTC: apaga eventos com mais de 12 meses, em lotes de 1000, pelo login de
  retenção, e grava `AUDIT_RETENTION_PURGED`.
- **Limpeza da tabela de saída**, às 03:30 UTC: apaga entradas entregues há mais de 7 dias.

As duas tarefas têm limite de 30 minutos e execução única pelo ShedLock. Ao subir, a API roda a tarefa que estiver
sem sucesso há mais de 24 h. A saúde mostra os componentes `auditRetention` e `auditOutboxCleanup`, que ficam
degradados sem sucesso há mais de 26 h ou depois de uma execução `FAILED` ou `TIME_LIMIT`.

A consulta administrativa está descrita em [docs/api/c8-admin-audit-events.md](docs/api/c8-admin-audit-events.md).
As decisões estão em [ADR-0002](docs/adr/ADR-0002-papeis-de-banco-e-migracoes-fora-da-api.md) (papéis de banco e
migrações fora da API) e em [ADR-0003](docs/adr/ADR-0003-sem-agregacao-de-recusas.md) (sem agregação de recusas).

### Limite do rollback para antes da U2

As migrações da U2 só acrescentam, e a aplicação mantém os privilégios de dados sobre os objetos que passaram ao
dono. Por isso, a versão da U1 roda sobre um banco já migrado pela U2. Mas a versão da U1 aplica o Liquibase dentro
da API com o login de aplicação, que não cria objetos. Então o rollback para antes da U2 só funciona sobre um banco
sem migração nova pendente: uma migração escrita depois da U2 só é aplicada pelo `db-migrate`. Para voltar, use o
commit anterior e rode `bash scripts/verify.sh`.

### Testes da U2

| O que roda | Comando |
|---|---|
| Backend da U2, unitários e integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u2 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Cobertura da suíte inteira (80% de linhas) | `apps/api/mvnw -f apps/api/pom.xml verify` |
| Smoke da U2 (com o ambiente de teste no ar) | `pnpm --dir apps/web exec playwright test e2e/u2-audit-trail` |
| Regenerar o OpenAPI administrativo | `apps/api/mvnw -f apps/api/pom.xml -Dtest=AdminOpenApiContractTest -Dopenapi.write=true test` |

### Desempenho da trilha (só local)

O script `scripts/perf-audit.sh` mede a trilha cheia e não roda na CI. Ele ainda não está no repositório: o passo
que o cria está pendente na geração de código da U2.

O desenho do script:

- usa o projeto Compose `memetrade-perf`, com volume próprio, e exige as portas 5432, 8080, 8081 e 3000 livres;
- semeia cerca de 37 milhões de eventos;
- mede a consulta em três cenários (meta: p95 até 2 s);
- mede a entrega em 1 hora (meta: 99% em até 5 s);
- mede o expurgo e a limpeza de 100 mil linhas (meta: até 30 minutos cada);
- grava `docs/perf/u2-audit-trail-<data>.md`.

Pré-requisitos: o ambiente de uso diário desligado e cerca de 30 GB livres em disco.

## `scripts/verify.sh`

1. Gera uma carteira de teste com a ferramenta de teste (SDK Solana só no escopo de teste); a chave fica num arquivo
   temporário fora do repositório e é apagada no fim.
2. Sobe `docker-compose.yml` + `infra/docker-compose.test.yml` (perfil `test`, que semeia a conta `BETA_USER` dessa
   carteira).
3. Espera os três serviços saudáveis e a primeira coleta da fonte simulada (até 60 s).
4. Roda os specs Playwright da U1, varre os logs da API atrás do endereço completo, derruba o ambiente e devolve
   código diferente de zero em qualquer falha ou tempo esgotado.

Sem `.env`, o script gera senhas e chave efêmeras para a execução.

## Segurança

- Nenhum segredo entra no repositório; o hook de pré-commit e a CI rodam o Gitleaks.
- O app nunca pede frase secreta nem chave privada; a assinatura de login serve só para entrar.
- Exceções de varredura ficam em `security/exceptions.yml`, com justificativa, aprovação e validade de até 90 dias.
