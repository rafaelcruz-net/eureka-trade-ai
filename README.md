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

## Descoberta e avaliação (U4)

### Fontes simuladas

Há duas fontes simuladas, `sim-a` e `sim-b`, sempre marcadas como simuladas: nenhuma chama a rede nem usa dado de
mercado real (C-06). Os 50 primeiros tokens são comuns às duas fontes; nesses tokens a `sim-b` diverge de propósito
em alguns campos, para a tela mostrar "Fontes divergem" e o estado de cada fonte. Com a semente fixa, cada execução
mostra os mesmos casos. Detalhes em [ADR-0004](docs/adr/ADR-0004-duas-fontes-simuladas-e-valor-mais-recente.md); o
indicador de risco e a versão 1 dos pesos estão em [ADR-0005](docs/adr/ADR-0005-indicador-de-risco-em-validacao.md),
e o contrato C2 completo em [docs/api/c2-market-scanner-assessment.md](docs/api/c2-market-scanner-assessment.md).

### Variáveis novas

Nenhuma é segredo. Todas têm padrão e passam pelo `docker-compose.yml`; um valor fora da faixa impede a API de subir,
com mensagem que nomeia a variável.

| Variável | Padrão | Faixa | Uso |
|---|---|---|---|
| `DB_POOL_MAX_SIZE` | `20` | 10 a 50 | Pool principal de conexões |
| `SCHEDULER_POOL_SIZE` | `7` | 7 a 32 | Linhas do agendador (3 da U1/U2 e 4 da U4) |
| `MARKET_SOURCES` | `sim-a,sim-b` | `sim-a` e/ou `sim-b` | Fontes ativas |
| `MARKET_COLLECTION_INTERVAL` | `30s` | 5 s a 60 s | Intervalo de coleta de cada fonte |
| `MARKET_SIMULATED_TOKENS_PER_SOURCE` | `200` | 50 a 10 000 | Tokens por fonte simulada |
| `MARKET_SIMULATED_SEED` | `42` | qualquer inteiro | Semente fixa das fontes simuladas |
| `MARKET_DIVERGENCE_TOLERANCE_PERCENT` | `5.0000` | 0 a 100 | Tolerância de divergência, em pontos percentuais |
| `SCANNER_RATE_LIMIT_PER_MINUTE` | `60` | 1 a 10 000 | Pedidos por pessoa e rota em 60 s |

### Testes da U4

| O que roda | Comando |
|---|---|
| Backend da U4, unitários e integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u4 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Frontend da U4 | `pnpm --dir apps/web exec jest src/features/scanner src/content` |
| Smoke da U4 (com o ambiente de teste no ar) | `pnpm --dir apps/web exec playwright test e2e/u4-discovery` |

### Cenário de carga da U4 (só local)

`SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` roda o cenário da U1 (limite de p95 de 300 ms
mantido) e, com `LOAD_SCENARIO=u4`, o cenário da U4: 2 000 tokens por fonte, 20 usuários por 10 minutos em lista com
filtros (p95 até 1 s) e detalhe (p95 até 500 ms), espera do pool, memória de `api` e `postgres` (até 900 MB) e plano da
consulta da lista. Com `LOAD_SCENARIO=growth`, o mesmo com 10 000 tokens, sem reprovação, só anotado. O relatório vai
para `load-test-report.json` e `docs/perf/u4-discovery-<data>.md`.

### Rollback para a U2

A migração `0006` só acrescenta (tabelas, colunas, índices e o estado `PENDING`). Para voltar à versão anterior:

1. Pare o ambiente sem apagar o volume: `docker compose down`.
2. Construa a imagem da `api` no commit anterior à U4 e suba com `IMAGE_TAG` apontando para ela.
3. O `db-migrate` antigo encontra a `0006` já aplicada e não reaplica nada; as tabelas da U4 ficam sem uso.
4. Rode `bash scripts/verify.sh` até sair 0.

Atenção: uma fonte da U4 que ainda esteja `PENDING` (nunca coletou) não é lida pela versão anterior, que não conhece
esse estado. Antes de voltar, confirme que `sim-a` e `sim-b` já coletaram ao menos uma vez.

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
