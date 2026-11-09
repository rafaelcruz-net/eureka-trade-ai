# Pilha de tecnologia — eureka-trader-ai

Versões lidas dos manifestos no commit `ffde88f`. Este é o inventário dono de versões; relações entre pacotes e
riscos de dependência estão em [dependencies.md](dependencies.md).

## Linguagens e runtimes

| Item | Versão | Onde está fixado |
|---|---|---|
| Java | 21 (enforcer exige `[21,)`) | `apps/api/pom.xml` `java.version`, imagens `eclipse-temurin:21-*-alpine` |
| Maven | 3.9 ou mais novo (Wrapper) | `maven-enforcer-plugin`, `apps/api/.mvn/` |
| TypeScript | 5.9.3, modo `strict` | `apps/*/package.json`, `tsconfig.json` |
| Node.js | 24 (`engines >=24 <25`) | `.nvmrc`, `node:24-alpine` |
| pnpm | 10.18.3 | `packageManager` dos dois apps |
| SQL | PostgreSQL 17 (plpgsql nos gatilhos) | `postgres:17-alpine` fixado por digest |
| Shell | bash/sh (Git Bash no Windows) | `scripts/`, `infra/postgres/bootstrap/bootstrap.sh` |

## Backend (`apps/api`)

| Biblioteca | Versão | Uso |
|---|---|---|
| Spring Boot (parent) | 3.5.16 | `starter-web`, `starter-validation`, `starter-jdbc`, `starter-actuator` |
| Liquibase core | gerenciada pelo Boot | Migrações SQL (só no perfil `migrate`) |
| PostgreSQL JDBC | gerenciada pelo Boot | Driver, leitura do nome da constraint |
| HikariCP | gerenciada pelo Boot | Pool principal (20) e pools de retenção (1 conexão) |
| springdoc-openapi-starter-webmvc-api | 2.8.17 | OpenAPI 3.1, grupos `guest` e `admin` |
| nimbus-jose-jwt | 10.10 | Token de acesso HMAC |
| ShedLock (spring, jdbc-template) | 6.10.0 | Execução única dos jobs |
| Resilience4j retry e timelimiter | 2.3.0 | Chamadas às fontes de mercado |
| logstash-logback-encoder | 8.1 | Logs JSON com máscara Base58 |
| micrometer-registry-prometheus | gerenciada pelo Boot | Métricas em `/actuator/prometheus` |
| Jackson | gerenciada pelo Boot | JSON (UTC, recusa campos desconhecidos) |

Criptografia: Ed25519 nativo do Java e decodificação Base58 própria (`identity/domain/signature`); SHA-256 para
digests de refresh, código de convite e idempotência. Sem SDK Solana em produção (proibido pelo enforcer).

### Teste e qualidade do backend

| Ferramenta | Versão | Uso |
|---|---|---|
| JUnit 5 / spring-boot-starter-test | gerenciada pelo Boot | Unitários e integração |
| Testcontainers (junit-jupiter, postgresql) | gerenciada pelo Boot | PostgreSQL 17 real |
| ArchUnit | 1.5.1 | Fronteiras de módulos e regras proibidas |
| solanaj | 1.28.0, só `test` | Gera a carteira do smoke |
| JaCoCo | 0.8.15 | Cobertura, `check` de 80% de linhas |
| Spotless + palantir-java-format | 2.46.1 + 2.80.0 | Formatação na fase `verify` |
| Surefire / Failsafe | gerenciados pelo Boot | `*Test` e `*IT`, seleção por `-Dgroups=uN` |

## Frontends (`apps/web`, `apps/admin`)

| Biblioteca | Versão | Uso |
|---|---|---|
| Next.js | 15.5.28 | App Router, `output: 'standalone'` |
| React / React DOM | 19.2.9 | UI |
| @wallet-standard/app, base, features | 1.1.0 | Descoberta de carteiras |
| @solana/wallet-standard-features | 1.3.0 | Recurso de assinatura de mensagem |
| openapi-typescript | 7.9.1 | Gera `schema.d.ts` do OpenAPI |
| Jest / jest-environment-jsdom | 30.2.0 | Testes unitários (80% de linhas) |
| @testing-library/react, jest-dom, user-event | 16.3.0, 6.10.0, 14.6.1 | Testes de componentes |
| Playwright | 1.56.1 (só `apps/web`) | Ponta a ponta |
| ESLint / eslint-config-next | 9.39.5 / 15.5.28 | Lint |
| Prettier | 3.6.2 | Formatação |
| pg | 8.16.3 (dev, só `apps/web`) | Leitura da trilha nos smokes |
| Override `postcss` | 8.5.28 | Correção de segurança transitiva |

## Infraestrutura e entrega

| Item | Versão / referência |
|---|---|
| Docker Compose | arquivo `docker-compose.yml` com sobreposição `infra/docker-compose.test.yml` |
| Imagens | `postgres:17-alpine`, `eclipse-temurin:21-jdk-alpine` (build) e `21-jre-alpine`, `node:24-alpine`, todas por digest SHA-256 |
| GitHub Actions | `ubuntu-24.04`; `actions/checkout` v4.2.2, `setup-java` v4.7.1, `setup-node` v4.4.0, `pnpm/action-setup` v4.1.0, `upload-artifact` v4.6.2, `cache` v4.2.3, `codeql-action` v3.29.5 — todas por SHA |
| Gitleaks | 8.30.1 (`.gitleaks-version`) |
| OWASP Dependency-Check, CodeQL, pnpm audit | Workflow `security.yml` |
| Dependabot | Maven, npm (dois apps, espera de 5 dias), Docker, docker-compose, GitHub Actions |

## O que o PRD cita e o código não tem

Redis, fila, IA, SDK Solana em produção, trading REAL, assinaturas/pagamentos, AWS e IaC: ausentes.
