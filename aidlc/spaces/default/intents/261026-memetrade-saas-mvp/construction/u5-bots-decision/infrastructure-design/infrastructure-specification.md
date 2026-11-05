# Especificação de infraestrutura — U5 Bots e decisão

Esta é a infraestrutura da Unit `u5-bots-decision` no ambiente local: Docker Compose no Windows e CI no GitHub Actions. Ela vem do desenho em [../nfr-design/](../nfr-design/) e das decisões de [infrastructure-design-questions.md](infrastructure-design-questions.md).

A U5 cria o processo worker previsto no ADR-005 e na C20.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Modelo de computação | Na `api`: módulos `bots` e `decision` (rotas C4, C7 e comandos). Num serviço novo, `worker`: a mesma imagem `memetrade-api` com `SPRING_PROFILES_ACTIVE=worker` (ciclo, meia-noite, conferência do encerramento, expurgo) | C20, NFR10.9 |
| Papel do processo | Propriedade `memetrade.process` com os valores `api` (padrão) e `worker`, lida por uma anotação comum `@ProcessRole`. Ela vale para todo trabalho que roda sozinho, não só para os `@Scheduled`, conforme a tabela "Trabalhos por processo" abaixo. Um teste de contexto por processo (`ProcessRoleContextIT`) lista, em cada um, os beans com `@Scheduled`, os ouvintes de `ApplicationReadyEvent`, os `CommandLineRunner`/`ApplicationRunner` e os `SmartLifecycle`, e confere a tabela | C20, ADR-005 |
| Web no worker | O worker sobe com o servidor web embutido na porta 8081 e sem publicar porta no host. Todos os controladores e filtros de requisição ficam com `@ProcessRole(api)` e não existem no worker. Assim, a 8081 do worker só serve o actuator (`/actuator/health/liveness` e `/actuator/health`) | NFR10.9 |
| Rede | Sem acesso de saída novo; o worker não publica porta | NFR6.1 |
| Armazenamento | PostgreSQL 17 existente. Tabelas novas: `strategy_template` (semeada), `bot`, `bot_owner`, `bot_limits_version`, `bot_cycle`, `bot_holding`, `system_pause_state` (uma linha semeada) e `decision`, mais a sequência `bot_change_seq` | logical-components.md |
| Migração | `0010-bots-decision.sql`: tabelas, `CHECK mode = 'PAPER'`, índices de scalability-design.md e semeaduras. Só acrescenta | NFR5.3 |
| Ambientes | Só local e CI | `team.md` |
| Dimensionamento | **`worker`:** `mem_limit` de 1 GB, pool de banco de 14 conexões (`DB_POOL_MAX_SIZE=14` no serviço, a mesma propriedade que a API já usa), executor de ciclos de 4 linhas e agendador de 4 linhas (`SCHEDULER_POOL_SIZE=4`). **`api`:** o pool de 20 continua; o agendador passa de 13 para 14 linhas por causa do `DashboardMarkPoller` (`@ProcessRole(api)`, a cada 2 s). **`RuntimeSettingsGuard`:** passa a conferir por processo. Na API, 14 a 32 linhas e pool de 10 a 50. No worker, 4 a 8 linhas e pool de 14 a 30. Os testes do guarda e o `ComposeFilesTest` acompanham | NFR2.4 |
| Conexões no PostgreSQL | API 20 + retenção da trilha (U2) 1 + retenção da U6 1 + worker 14 + `db-bootstrap` e `db-migrate` na partida 2 + 3 reservadas do superusuário = 41, contra o `max_connections` padrão de 100 do `postgres:17`. Os testes de integração usam o próprio contêiner. A conta fica num comentário do `docker-compose.yml`, e um teste confere a soma | NFR2.4 |
| Memória | API 1 GB + worker 1 GB + PostgreSQL 900 MB + web 512 MB, no Docker Desktop local | NFR2.4 |

## Trabalhos por processo

Contagem conferida no código: 13 métodos `@Scheduled` hoje, mais os trabalhos de partida.

| Trabalho | Unit | Tipo | Processo |
|---|---|---|---|
| Purga e retenção da trilha, relay da tabela de saída, limpeza do outbox | U2 | `@Scheduled` | api |
| `AuditStartup` (recuperação de tarefas atrasadas na partida) | U2 | `ApplicationReadyEvent` | api |
| Limpezas de sessão, nonce e limitador de login | U1, U3 | `@Scheduled` | api |
| Despachante de coleta, limpeza do histórico, limpeza do limitador do scanner | U4 | `@Scheduled` | api |
| Marcação, fechamento pendente, reconciliação, limpeza do limitador de "verificar agora" | U7 | `@Scheduled` | api |
| Expurgo de avaliações | U6 | `@Scheduled` | api |
| Semeadores do perfil `test` (conta de smoke) e componentes do perfil `loadtest` das U6 e U7 | U1, U6, U7 | partida e `@Scheduled` | api |
| `DashboardMarkPoller` | U5 | `@Scheduled` a cada 2 s | api |
| `SlotDispatcher`, `MidnightJob`, `ClosingCheckJob`, `DecisionPurgeJob` | U5 | `@Scheduled` | worker |
| Cronômetro do ciclo do perfil `loadtest` | U5 | instrumentação | worker |

A lista exata de classes sai do Grep no Passo 1 da geração de código e vai para o `ProcessRoleContextIT`, que falha se surgir um trabalho sem papel.

## Serviço `worker` no Compose

| Item | Valor |
|---|---|
| Imagem | `memetrade-api:${IMAGE_TAG:-local}` |
| Ambiente | Só o que o worker usa: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_MAX_SIZE=14`, `SCHEDULER_POOL_SIZE=4`, as variáveis de mercado da U4, `SPRING_PROFILES_ACTIVE=worker` e `MEMETRADE_PROCESS=worker`. Não recebe `ACCESS_TOKEN_SIGNING_KEY`, `GUEST_APP_ORIGIN`, `TRUSTED_PROXIES`, `ADMIN_WALLET_ADDRESSES` nem logins de retenção. As propriedades de segurança web ficam com `@ProcessRole(api)`, e um teste de contexto sobe o worker sem essas variáveis. O expurgo de decisões (BR9.1) usa o login da aplicação, que tem `DELETE` em `decision` e `bot_cycle` pelos privilégios padrão: não são registros imutáveis |
| Depende de | `db-migrate` concluído com sucesso e `api` saudável (a `api` faz a coleta de mercado) |
| Healthcheck | A classe Java `HealthProbe` que a `api` já usa, com a URL `http://127.0.0.1:8081/actuator/health/liveness` como argumento, a cada 10 s. A imagem não tem `curl` nem `wget`. O indicador de ciclos é informativo e fica fora (Q6 dos requisitos) |
| Reinício no meio da fatia | Com `lockAtLeastFor` de 4 min, um worker que reinicia no meio da fatia (inclusive pelo `docker compose up -d --build` do `verify.sh`) não redespacha os bots que faltavam. Eles viram SKIPPED/SLOT_EXPIRED e decidem na fatia seguinte. É o comportamento esperado do NFR10.3, sem atraso nem repetição, e tem teste no `CycleIT` |
| Reinício | `restart: unless-stopped` |
| Logs | Mesmo `logging` dos demais serviços |

O overlay de teste (`infra/docker-compose.test.yml`) repete o serviço com o perfil `test,worker`. Os semeadores do perfil `test` não rodam no worker, porque têm `@ProcessRole(api)`. No overlay, e só nele, o worker publica `127.0.0.1:8082:8081`, para o cenário de carga ler o actuator e as métricas. O `ComposeFilesTest` confere o serviço, as dependências e a ausência de porta publicada.

## Configuração

| Propriedade | Variável | Padrão | Uso |
|---|---|---|---|
| `memetrade.process` | `MEMETRADE_PROCESS` | `api` | Papel do processo |
| `memetrade.bots.scheduler.enabled` | — | `true` só no perfil `worker` | Despacho e tarefas da U5 |
| `memetrade.bots.slot-cron` | — | `0 1/5 * * * *` (UTC) | BR3.1 |
| `memetrade.bots.cycle-threads` | — | `4` | NFR2.4 |
| `memetrade.bots.virtual-balance.min` e `.cap` | — | `100.000000` e `10000.000000` | BR1.4 |
| `memetrade.bots.decision-retention` | — | `30d` | BR9.1 |

Regras de risco e de ciclo são fixas no `application.yml`, sem variável de ambiente.

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL 17 | database | `0010` | Volume da U5 abaixo de 2 GB no beta |
| ShedLock | coordenação | `bots-slot-dispatch` (pelo menos 4 min, no máximo 5 min), `bots-midnight`, `bots-closing-check`, `bots-decision-purge` | Tabela da U2 |
| Worker | processo | Serviço novo acima | Um só no beta |
| Cache, fila, CDN, balanceador | — | Não usados | — |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| Imagem `memetrade-api` | U1 | API e worker | Papel por `memetrade.process` |
| `OrderGate` (C14) | U6 | U5 | Na transação do ciclo |
| `PaperExecution`, `LedgerReadModel` (C15) | U7 | U5 | Execução e leitura por bot |
| `PortfolioRiskView` (C13) | U7 | U6, U5 | Perda do dia no resume |
| `MarketDataQuery`, `MarketListingQuery`, `RiskAssessmentQuery` | U4 | U5 | Leitura |
| `BotAdministration` (C16) | U5 | U8, U9 | Pausa de sistema, contagens, resumo |
| `BotChangeMarks` (C18) e canal C7 | U5 | U9 | Por pessoa |

## Para a hospedagem futura

- A API e o worker são implantados separadamente, mas da mesma versão. As migrações seguem expand-contract (C20).
- Mais de um worker exige revisar o despacho (Q2 do NFR Design).
