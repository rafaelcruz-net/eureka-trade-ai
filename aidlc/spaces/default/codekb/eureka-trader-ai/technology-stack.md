# Pilha de tecnologia — eureka-trader-ai

Versões lidas no commit de referência. As bibliotecas com seu uso e as dependências entre pacotes estão em
[dependencies.md](dependencies.md).

## Linguagens e runtimes

| Item | Versão | Onde está fixada |
|---|---|---|
| Java | 21 | `apps/api/pom.xml` (`java.version`), enforcer exige JDK `[21,)` e Maven `[3.9,)` |
| JRE/JDK das imagens | Eclipse Temurin 21 Alpine (JDK no build, JRE no runtime), por digest | `apps/api/Dockerfile` |
| TypeScript | 5.9.3, modo `strict` | `apps/*/package.json`, `apps/*/tsconfig.json` |
| Node.js | 24 (LTS) | `.nvmrc`, `engines` `>=24 <25`, imagem `node:24-alpine` por digest |
| SQL | PostgreSQL 17 | `postgres:17-alpine` por digest em `docker-compose.yml` |
| Shell | Bash e POSIX sh | `scripts/*.sh`, `.githooks/pre-commit`, `infra/postgres/bootstrap/bootstrap.sh` |

## Backend

| Tecnologia | Versão | Uso |
|---|---|---|
| Spring Boot | 3.5.16 (parent) | Web MVC em Tomcat, validação, JDBC (`JdbcClient`), actuator |
| Tomcat | 10.1.61 (sobreposição por CVE) | Servidor HTTP |
| Liquibase | gerenciado pelo Boot | Migrações SQL formatadas, aplicadas só pelo processo `db-migrate` |
| PostgreSQL JDBC | 42.7.14 (sobreposição por CVE) | Driver |
| HikariCP | gerenciado pelo Boot | Pool fixo de 20 no `api`, 14 no `worker`, pools de 1 conexão para expurgos e para a trava do kill switch |
| springdoc-openapi (webmvc-api) | 2.8.17 | OpenAPI 3.1 por grupo (`guest`, `admin`) |
| nimbus-jose-jwt | 10.10 | Utilidades JOSE |
| ShedLock (spring, jdbc-template) | 6.10.0 | Execução única dos jobs entre instâncias |
| Resilience4j (retry, timelimiter) | 2.3.0 | Limite de tempo e novas tentativas das fontes de mercado, configurados em código |
| Micrometer + Prometheus | gerenciado pelo Boot | Métricas em `/actuator/prometheus` |
| logstash-logback-encoder | 8.1 | Logs JSON com máscara de carteira |
| Log4j API | 2.25.5 (sobreposição por CVE) | Só a ponte `log4j-to-slf4j` |
| Criptografia | JDK (`Signature.getInstance("Ed25519")`, HMAC) | Assinatura de login, token de acesso, HMAC da carteira na trilha |

Sem ORM, sem Spring Security (filtros próprios), sem fila, sem cache, sem cliente HTTP de saída, sem SDK Solana em
produção, sem KMS.

## Frontend

| Tecnologia | Versão | Uso |
|---|---|---|
| Next.js | 15.5.28 | App Router nos dois apps; `middleware.ts` com CSP por nonce |
| React / React DOM | 19.2.9 | UI |
| Wallet Standard | `@wallet-standard/app` 1.1.0, `base` 1.1.0, `features` 1.1.0, `@solana/wallet-standard-features` 1.3.0 | Descoberta de Phantom e Solflare, `connect` e `solana:signMessage` |
| openapi-typescript | 7.9.1 | Tipos gerados dos contratos |
| pnpm | 10.18.3 (`packageManager`) | Instalação a partir dos lockfiles (`--frozen-lockfile`) |
| postcss | 8.5.28 (override do pnpm) | Correção de segurança transitiva |

## Testes

| Tecnologia | Versão | Uso |
|---|---|---|
| JUnit 5 + Spring Boot Test | gerenciado pelo Boot | Unitários (`*Test`, Surefire) e integração (`*IT`, Failsafe) |
| Testcontainers (junit-jupiter, postgresql) | gerenciado pelo Boot | PostgreSQL real com as migrações da aplicação |
| ArchUnit | 1.5.1 | Fronteiras de módulo, pureza do domínio, proibições de rede, chave e ponto flutuante |
| solanaj | 1.28.0, escopo `test` | Carteira e assinatura de teste (banido em produção pelo enforcer) |
| JaCoCo | 0.8.15 | Cobertura mesclada com piso de 80% de linhas |
| Jest + jsdom | 30.2.0 | Testes dos dois apps com piso de 80% de linhas |
| Testing Library | react 16.3.0, user-event 14.6.1, jest-dom 6.10.0 | Testes de componentes |
| Playwright | 1.56.1 | Smokes e ponta a ponta |
| `node:test` | do Node 24 | Testes dos cenários de carga em `scripts/load/` |

## Qualidade e segurança

| Ferramenta | Versão | Uso |
|---|---|---|
| Spotless + palantir-java-format | 2.46.1 / 2.80.0 | Formatação Java, `format-check` no `verify` |
| maven-enforcer-plugin | gerenciado pelo Boot | Versões mínimas e dependências banidas |
| ESLint + eslint-config-next | 9.39.5 / 15.5.28 | Lint dos apps |
| Prettier | 3.6.2 | Formatação dos apps |
| Gitleaks | 8.30.1 (`.gitleaks-version`) | Pré-commit e CI |
| CodeQL | ação `github/codeql-action` v3.29.5 por SHA | SAST do Java |
| OWASP Dependency-Check | no workflow `security.yml` | Dependências Maven |
| `pnpm audit` | do pnpm | Dependências npm dos dois apps, nível `high` |
| Dependabot | — | Maven, npm (espera de 5 dias), Docker, Compose, Actions |

## Infraestrutura e entrega

| Tecnologia | Uso |
|---|---|
| Docker Compose | Ambiente local completo, só em `127.0.0.1`; sobreposição `infra/docker-compose.test.yml` |
| GitHub Actions | `ci.yml`, `security.yml`, `load-test.yml`; ações fixadas por SHA; `permissions: contents: read` |
| Ambiente | Só local (Windows); sem nuvem, região, staging nem IaC (AWS prevista no PRD) |

## Ausências relevantes para o Trading REAL

Estado de 2026-11-13: nenhuma destas peças existia no repositório: cliente Solana RPC/WebSocket, cliente da API da
Jupiter (cotação, swap, ordens condicionais), assinatura de transação, gerenciador de segredos ou KMS/HSM, fila durável,
configuração de cluster (devnet/mainnet) além da cadeia fixa `solana:mainnet` da mensagem de login.

## Tecnologia REAL nas áreas analisadas (2026-11-21)

As versões das bibliotecas acima não foram relidas nesta varredura focada. Nas áreas analisadas, o REAL usa:

| Peça | Onde | Observação |
|---|---|---|
| Cliente HTTP da Jupiter (cotação e swap) | `realexecution/infra/jupiter/JupiterClient.java`, `JupiterConfig.java` | Próprio, sem SDK |
| Montagem de transação Solana | `realexecution/domain/solana/` (`MessageBuilder`, `MessagePlan`, `WireTransaction`, `ShortVec`, `ComputeBudget`, `Ed25519Check`) | Sem SDK Solana em produção, alinhado ao `project.md` |
| Porta da API Trigger da Jupiter | `realexits/domain/TriggerPort.java`, `infra/SimulatedTriggerAdapter.java` | Só o adaptador simulado no perfil de teste até a gravação real da API |
| Fila de decisões REAL | Tabela `real_decision_request` e `DecisionConsumer` no worker | Fila durável no PostgreSQL, não um broker |
| Aritmética REAL | `long` em lamports e unidades base, `BigInteger` nas frações, `BigDecimal` de 18 casas nos preços; `bigint` no app web (`features/real/money.ts`) | Nunca ponto flutuante |
| Assinatura no navegador (modo Assinada) | `apps/web/src/features/real/sign-transaction.ts` | O navegador só assina; montagem e envio ficam no servidor |

KMS, TxSigner, RPC Solana e a configuração de cluster ficam no `botwallet` e no `apps/txsigner`, não relidos.
