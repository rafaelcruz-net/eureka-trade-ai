# Resultados do Build and Test

Conversa: português (Brasil). Intent `261109-termos-lgpd`, execução de 2026-11-11 no Windows (local) e no Linux (GitHub Actions).

## Build

| Parte | Comando | Resultado |
|---|---|---|
| Backend | `env -u MEMETRADE_AUDIT_WALLET_HMAC_KEY sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | `BUILD SUCCESS` (local, commit `63a7710`); job `backend` verde na CI `43067822342` |
| `apps/web` | `install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `check:api`, `test:coverage` | verde (local); job `frontend` verde |
| `apps/admin` | os mesmos comandos com `--dir apps/admin` | job `admin` verde na CI `43067822342` |
| Contrato | OpenAPI gerado igual aos arquivos versionados; tipos gerados | job `contract` verde |

## Testes

| Suíte | Total | Passaram | Falharam | Ignorados |
|---|---|---|---|---|
| Backend, unidade (Surefire), depois do Loop-back 1 | 1326 | 1326 | 0 | 0 |
| Backend, integração (Failsafe, Testcontainers), depois do Loop-back 1 | 949 | 949 | 0 | 0 |
| `apps/web`, Jest | 533 | 533 | 0 | 0 |
| `apps/admin`, Jest | verde na CI | — | 0 | — |
| Ponta a ponta, `scripts/verify.sh` (Playwright) | 19 | 19 | 0 | 0 |

Cobertura: a regra `check` do JaCoCo passou ("All coverage checks have been met", 80% de linhas no relatório mesclado); o limite de 80% do Jest passou em `apps/web` e em `apps/admin`.

## Falhas encontradas e corrigidas nesta execução

Todas ganharam teste de regressão ou foram corrigidas na causa, sem relaxar meta nenhuma.

| Falha | Causa | Correção | Commit |
|---|---|---|---|
| `e2e/reacceptance`: `voltar` era `/` em vez de `/bots` | defeito do produto: `/bots` e `/pnl` não estavam em `GUEST_ROUTES` | rotas acrescentadas; regressão em `origin.test.ts` | `5d30541` |
| `e2e/u1-walking-skeleton/keyboard.spec.ts`: tempo esgotado no login | com os specs novos, o spec bateu no limite real de 10 logins por minuto | espera uma janela e tenta de novo pelo teclado (o limite não mudou) | `5d30541` |
| CI `backend`, passo "Compose config" | o `ci.yml` não passava `MEMETRADE_AUDIT_WALLET_HMAC_KEY`, exigida desde a U2 | valor fictício no passo (patch do responsável) | `4bc09d1` |
| `AnonymizationNoWalletLeftIT` e `AnonymizationObservabilityIT` na CI | os testes `db/*MigrationIT` chamavam o `MigrationRunner`, que reiniciava o Logback em `WARN` no meio da suíte | `AbstractPostgresIT.migrate` mantém o log da JVM; regressão em `OwnTestDatabaseIT` | `092fd1f` |
| `e2e/legal-documents` na CI: `legal-version` com 2 elementos | cópia oculta do conteúdo transmitido em partes (`loading.tsx`) | o spec procura só no `<main>` visível | `092fd1f` |
| `DashboardOpenLatencyIT` (U9 NFR3.1), 2,058 s | oscila entre 2,02 e 2,16 s desde o intent anterior | decisão Q1 = B do responsável aplicada: medição informativa, anotada no NFR3.1 da U9 | `63a7710` |
| `security.yml`, job `dependencies` | CVSS ≥ 7 em Tomcat 10.1.55, pgjdbc 42.7.11, kotlin-stdlib 1.9.25, Spring 6.2.19 e falso positivo do Prometheus | versões novas no `pom.xml`; exceções EX-001 a EX-013 em `security/exceptions.yml`, aprovadas pelo responsável | ainda sem commit; confirmado na nova execução depois da volta |
| carga `u3`: `account creation failed with 422` | defeito do script: `acceptedTermsVersion: 'v1'` fixo, e a versão vigente é `v2` desde a U1 | o script lê a versão vigente de `/api/v1/legal/current`, como o `data-requests` | ainda sem commit |
| carga `reacceptance`: trava p95 16,8 ms e 8,4 ms (meta 5 ms) | rajada fria de 20 pessoas; o pool abria até 15 conexões novas dentro da checagem | pool de tamanho fixo (`minimum-idle` = 20) no `application.yml`: caiu para 7,0 ms, ainda acima da meta | ainda sem commit; ver Loop-back 1 |

## Desempenho e carga

Comando: `LOAD_SCENARIO=<cenário> SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` (20 usuários, chave HMAC efêmera gerada pelo script).

| Cenário | Metas | Resultado | Passou |
|---|---|---|---|
| `legal` | páginas p95 ≤ 2 s; API p95 ≤ 300 ms; 0 5xx | páginas 146 ms; API 14 ms; 0 5xx (1500 + 1500 requisições, 5 min) | sim |
| `u1` | verificação do login p95 ≤ 300 ms com HMAC | challenge 39 ms, verify 134 ms, lista 56 ms; 0 erros; plano sem varredura sequencial | sim |
| `u3` | conta com convite p95 ≤ 500 ms | 1ª rodada: falha do script (422); 2ª: verificação com convite 224 ms, consulta do convite 4 ms, tela Conta 6 ms; 20 contas | sim (2ª rodada) |
| `reacceptance` | aceite p95 ≤ 500 ms; trava ≤ 5 ms p95 | rodadas 1 a 3: aceite 134 / 50 / 39 ms, trava 16,8 / 8,4 / 7,0 ms (não); **rodada 4, depois do Loop-back 1: aceite 45 ms, trava 4,2 ms, espera por conexão ≈ 1 ms**; 20 de 20 travados, aceitos e liberados | sim (rodada 4) |
| `data-requests` | criar/listar/abrir ≤ 500 ms; admin ≤ 1 s com 1000 pedidos; prévia ≤ 1 s | convidado 23 ms (2980 amostras); admin 16 ms (298 amostras); 0 inesperados | sim |

Os valores do `reacceptance` vêm do histograma do Micrometer, então cada número é o limite superior de uma faixa: 7,0 ms quer dizer entre 5,6 e 7,0 ms.

Medições na suíte (verdes): `SessionPurgePerformanceIT`, `WalletHmacOverheadTest`, `DataRequestTimersIT`, `AnonymizationLatencyIT`, `AnonymizationIndexIT`. `DashboardOpenLatencyIT` registrou o p95 do quadro de bots em 1886 ms na última execução local.

## Segurança

| Verificação | Resultado |
|---|---|
| Gitleaks (local, 20 commits, e job `secrets`) | sem achado |
| CodeQL Java e TypeScript (jobs `sast`) | sem achado bloqueante |
| `pnpm audit --audit-level high` em `apps/web` e `apps/admin` | 1 baixo e 3 médios em cada; nada Alto (aviso, não bloqueia) |
| OWASP Dependency-Check (job `dependencies`) | CI `43065596780`: reprovado (Tomcat, pgjdbc, Kotlin, log4j, Spring, Prometheus); a CI não lia o arquivo de exceções (`Unable to read suppression file '../../dependency-check-suppressions.xml'`), caminho corrigido no `security.yml` pelo responsável. Local, com as versões novas e EX-001 a EX-013: `BUILD SUCCESS` (sem o analisador OSS Index, que deu `401` sem login) |

## Target Verification Matrix

A matriz final, com uma linha por meta, está em `build-and-test-summary.md`.

## Depois do Loop-back 1

- Passos 15 e 16 da U3 construídos (`TermsReacceptanceWarmup` e a leitura de `hikaricp_connections_acquire_seconds` no cenário), revisados pelo revisor de arquitetura (READY, sem achado Crítico ou Major) e verificados no ponto de controle da U3 (`bash scripts/verify.sh`: 19 de 19).
- Dois ajustes do próprio Build and Test que a suíte completa acusou: as datas de `security/exceptions.yml` ficaram entre aspas (o YAML as lia como data, e o `SecurityExceptionsTest` falhava) e os testes voltaram a manter `minimum-idle: 5` só no classpath de teste (`apps/api/src/test/resources/config/application.yml`), porque o pool fixo de 20 por contexto esgotava o PostgreSQL de teste.
- O `log4j-api` 2.24.3 (CVE-2026-34478, CVSS 3.1 de 7,5) subiu para 2.25.5; a suíte completa passou de novo (1326 de unidade, 949 de integração, JaCoCo atingido).
- Os cenários `legal`, `u1`, `u3` e `data-requests` não foram repetidos: a volta só acrescentou o aquecimento na subida e o pool fixo, que não pioram aqueles caminhos.

## Loop-Back Log

### Loop-back 1 — 2026-11-11T18:40:00Z

- **Diagnosis:** U3 NFR3.2 não cumprida. A checagem da trava (`memetrade.terms.reacceptance.check`, que mede só a consulta `EXISTS` em `JdbcTermsAcceptanceLookup`) ficou com p95 de 16,8 ms, 8,4 ms e 7,0 ms em três rodadas, contra 5 ms. A consulta usa a restrição única `(user_id, terms_version)`. O cenário mede cerca de 40 checagens numa rajada simultânea de 20 pessoas logo depois da subida da API. O pool de tamanho fixo (correção dentro do estágio) tirou a abertura de conexões da medida, mas sobrou o custo frio do caminho da consulta.
- **Root-cause stage:** code-generation da Unit `u3-reacceptance` (o caminho da consulta da trava e a sua medição).
- **Planned fix:** aquecer na subida da API o caminho da consulta da trava (conexões do pool e a consulta preparada, sem guardar estado em memória, conforme o NFR3.4) e publicar separadamente a espera por conexão e a duração da consulta, para o cenário mostrar qual parte passa da meta. A meta de 5 ms não muda.
- **Estimated impact:** esforço de 1 a 2 horas; custo financeiro zero; risco baixo (só a U3 é reaberta; as demais Units mantêm o trabalho e as aprovações); o ganho não é garantido. Escolha do responsável: "Retry with fix".
