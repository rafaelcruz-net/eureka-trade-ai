# Pipeline de CI — U5 Bots e decisão

A U5 usa os workflows existentes e não cria workflow novo. As regras de sempre continuam: ações fixadas por SHA, `permissions` mínimas, lockfiles versionados e squash-merge em `main` só com a CI verde.

## Estágios e portões

| Estágio | Job | O que a U5 acrescenta | Portão |
|---|---|---|---|
| Lint, formato e tipos | `backend`, `frontend` | Módulos `bots` e `decision`; telas em `src/features/bots` e `src/app/bots`; regra de lint contra converter `DecimalString` em `number` | Falha bloqueia |
| Testes do backend | `backend` | Marcador `u5`. Escritos antes do código: catálogos D1 a D18 (`DecisionCatalogTest`) e L1 a L14 (`BotLifecycleCatalogTest`), propriedade da decisão, reprodução (`DecisionReplayTest`). Integração: comandos e idempotência, 3 ativos em paralelo, ciclo completo com U6 e U7 reais e relógio controlado (`CycleIT`), falha fechada com o gravador depois do rollback (`CycleFailureIT`), ordem das travas (`LockOrderIT`, 50 repetições), kill switch e corrida (`SystemPauseIT`), meia-noite (`MidnightIT`), encerramento pendente (`ClosingCheckIT`), expurgo, canal ao vivo sem vazamento (`DashboardStreamIT`), rotas com dois tenants (`BotRoutesIT`), contexto por processo (`ProcessRoleContextIT`). ArchUnit e o teste de código-fonte das travas | Falha ou cobertura < 80% bloqueia |
| Testes do frontend | `frontend` | Assistente, lista e tela do bot: estados, validação, diálogos, acessibilidade | Falha ou cobertura < 80% bloqueia |
| Contrato | `contract` | OpenAPI do convidado com as rotas C4, C7 e os acréscimos; tipos regenerados | Diferença bloqueia |
| Smoke | `smoke` (`bash scripts/verify.sh`) | O Compose sobe também o `worker`. Novo smoke Playwright `e2e/u5-bots-decision`: entra com a conta de smoke, cria um bot pelo assistente (etapas 1 a 4), inicia, pausa, retoma e encerra, conferindo estados, motivos em texto e "MODO PAPER (simulado)", sem esperar ciclo (Q1, opção A). Entra no `verify.sh` por ação do responsável do projeto | Falha bloqueia |
| Segurança | `security` | Nenhuma dependência nova | Crítico ou Alto bloqueia |
| Carga | `load-test.yml` e `scripts/load-test.sh` | Cenário `u5`, abaixo | Evidência do ponto de controle |

## Cenário de carga `u5`

| Item | Valor |
|---|---|
| Processos | `api` e `worker` com `test,loadtest` |
| Preparação | 20 pessoas com 3 bots ativos cada (pelo `LoadtestBotSeed`, chamando os comandos reais) e 20.000 decisões semeadas num bot de cada pessoa. A fonte simulada da U4 fica no cenário de sinais frequentes |
| Duração | 30 minutos (6 fatias) |
| Ações | Ciclos do worker cronometrados por etapa; 20 usuários com uma ação a cada 2 s (lista, tela do bot, decisões, comandos); 20 canais ao vivo abertos |
| Limites | NFR2.1, NFR2.2 (zero recusas por frescor), NFR2.3, NFR3.1, NFR3.2, NFR3.3; pico de conexões ≤ 14 |
| Relatório | `docs/perf/u5-bots-decision-<data>.md` |

Variáveis novas no overlay de teste: `LOADTEST_U5_ENABLED` e `LOADTEST_U5_DECISIONS_PER_BOT`. O número de pessoas vem de `LOADTEST_LEDGER_PEOPLE`.

Mudanças no ferramental de carga:
- **`scripts/load-test.sh`:** aceita `LOAD_SCENARIO=u5`. Sobe o `worker` com `test,loadtest,worker`, define `LOADTEST_LEDGER_PEOPLE=20`, `LOADTEST_FILLS_PER_PERSON=0` e `LOADTEST_CYCLE_BOTS=0` (os ciclos são os do worker, não o gerador da U7) e gera os convites como o `u7`.
- **`scripts/load/load-test.mjs`:** ganha o ramo `u5` e a variável `WORKER_CONTAINER`. Do worker, ele lê:
  - o actuator em `127.0.0.1:8082` (só no overlay de teste): o indicador `botCycles` e as métricas Hikari `hikaricp.connections.active` e `.pending`;
  - os logs JSON do contêiner, com os tempos por etapa que o cronômetro do perfil `loadtest` grava por ciclo;
  - o `docker stats` do contêiner, para a memória.
- **`.github/workflows/load-test.yml`:** aceita `u5`, e o `WorkflowsTest` confere.

## Implantação e rollback

- A `0010` só acrescenta. A imagem da U6 roda sobre o banco migrado, ignorando as tabelas novas. Sem o serviço `worker`, nada da U5 roda.
- **Rollback manual no ponto de controle:**
  1. Fazer checkout do commit anterior.
  2. Rodar `docker compose up -d --build --remove-orphans`. Isso remove o contêiner `worker` órfão.
  3. Rodar `bash scripts/verify.sh`, que precisa sair com código 0.
- **Estado esperado depois do rollback:** os bots já ACTIVE continuam ACTIVE no banco, mas sem ciclo, porque não há worker. A tela da versão anterior não tem bots. Voltar para a versão da U5 retoma os ciclos na fatia seguinte, sem repetir fatias.
- **Teste automático:** o `MigrationsIT` até a `0010`.

## Ação pendente do responsável do projeto

Acrescentar `e2e/u5-bots-decision` à linha do Playwright em `scripts/verify.sh`.

## Segredos

Nenhum segredo novo. O worker recebe as mesmas variáveis da `api`.
