# U5 — Bots e decisão (C4, C7, C16, C18, C20)

A U5 cria os módulos `com.memetrade.bots` e `com.memetrade.decision`. Com eles, a pessoa monta um bot PAPER pelo assistente, inicia, pausa, retoma e encerra o bot e acompanha as decisões explicadas. Tudo é `PAPER`. Nenhum caminho assina ou envia transação, e o trading REAL continua desligado.

## Módulos e fronteiras

| Pacote | O que tem |
|---|---|
| `bots.api` | C16 `BotAdministration` (pausa do sistema, resumo do painel, última decisão) e C18 `BotChangeMarks`; enums `BotState` e `PauseReason` |
| `bots.domain` | Máquina de estados pura (`BotStateMachine`), validação do assistente (`BotConfigValidator`), modelos e limites padrão |
| `bots.application` | `BotCommandService` (criar, salvar etapa, iniciar, pausar, retomar, encerrar, mudar limites) e consultas |
| `bots.infra` | Repositórios JDBC, `LockOrder`, `CycleAuthContext` do worker, propriedades e configuração |
| `bots.worker` | Ciclo (`CycleRunner`), despacho da fatia, meia-noite UTC, encerramento pendente, expurgo e saúde `botCycles` — só no processo `worker` |
| `bots.web` | Rotas C4 e canal ao vivo C7 (`DashboardMarkPoller`) — só no processo `api` |
| `bots.loadtest` | Semeador e cronômetro do cenário de carga `u5`; só existem com o perfil `loadtest` |
| `decision.domain` | `DecisionEngine`: função pura e determinística, com aritmética decimal exata (BigDecimal, `HALF_EVEN`) |
| `decision.infra` | `DecisionJson` (gravação e reprodução) e o repositório das decisões |

Os módulos dependem só das interfaces públicas das outras Units: C9 da auditoria, C10 da identidade, C11 da U4, C12 e C13 da avaliação de risco e do P&L, C14 da U6 e C5 e C17 da U7. O `BotsArchitectureTest` confere as fronteiras e também:

- que não há rede, assinatura nem SDK Solana;
- que não há `double` nem `float`;
- que não há `BigDecimal.ZERO` no lugar de um valor desconhecido;
- que não há relógio de parede;
- que o domínio é puro;
- que o pacote de carga não é usado fora dele.

## Estados e motivos

| Estado | Texto na tela | Comandos aceitos |
|---|---|---|
| `DRAFT` | Rascunho | etapas do assistente |
| `VALIDATED` | Pronto para iniciar | `start` |
| `ACTIVE` | Ativo | `pause`, `stop`, mudar limites |
| `PAUSED` | Pausado | `resume`, `stop`, mudar limites |
| `CLOSING_PENDING` | Encerramento pendente | nenhum (aguarda preço para fechar) |
| `STOPPED` | Encerrado | nenhum |

Um bot pausado guarda todos os motivos ao mesmo tempo. A tela mostra cada motivo em texto, nunca só por cor:

| Motivo | Texto | Como sai |
|---|---|---|
| `PERSON` | pausado por você | `resume` |
| `DAILY_LOSS` | perda diária atingida | sai sozinho à meia-noite UTC; a tela mostra "volta a operar às HH:MM" no fuso da pessoa |
| `DRAWDOWN` | drawdown atingido | `resume`, depois de revisar os limites |
| `SYSTEM` | pausado pelo sistema | liberação pela administração (C16), que troca o motivo por `ADMIN_RELEASED` |
| `ADMIN_RELEASED` | pausado pela administração; retome quando quiser | `resume` |
| `WAITING_ACTIVE_SLOT` | já há 3 bots ativos | `resume` quando houver vaga |

Os comandos recusados voltam como 409 em RFC 7807, com estes códigos:

- `INVALID_TRANSITION`;
- `ACTIVE_BOT_LIMIT` (no máximo 3 bots ativos por pessoa);
- `PAUSED_BY_SYSTEM`;
- `DAILY_LOSS_LIMIT_REACHED`, com a perda do dia no campo `reason`.

Uma validação recusada volta como 422 `VALIDATION_FAILED`, com o campo e a mensagem de cada erro. Toda escrita exige `Idempotency-Key`, e a mesma chave devolve a mesma resposta.

## Ciclo

- O despacho roda a cada 5 minutos, no minuto 1 da fatia (`0 1/5 * * * *`). Ele usa uma trava ShedLock, de no mínimo 4 e no máximo 5 minutos, e distribui os bots ativos em 4 threads.
- Cada bot roda numa transação própria, em `READ COMMITTED`, com `lock_timeout` de 2 s e `statement_timeout` de 5 s.
- A fatia do bot é reservada em `bot_cycle` (`bot_id`, `slot_start` únicos). Uma fatia nunca é processada duas vezes.
- **Ordem do ciclo:**
  1. Ler os dados de mercado do momento (C11) e a avaliação de risco (C12).
  2. Decidir.
  3. Gravar a decisão.
  4. Para uma ordem, avaliar a barreira (C14) e executar a ordem simulada (C17).
  5. Aplicar os gatilhos de pausa.
  6. Renovar a marca de mudança (C18).
- Um ciclo que falha é desfeito por inteiro. A fatia `FAILED` é gravada depois, numa transação própria, com a causa. A falha incrementa `bots.cycle.errors{cause}` e nunca vira aprovação.
- Um dado ausente é `unknown` e vira `NO_TRADE` com motivo, nunca zero. Um dado velho vira `STALE_DATA`. Sem preço confiável, nenhuma saída é avaliada e a posição fica aberta.
- A tarefa da meia-noite UTC tira o motivo `DAILY_LOSS`. O bot volta a `ACTIVE` só se não sobrar outro motivo e houver vaga; sem vaga, ganha `WAITING_ACTIVE_SLOT`.
- O encerramento pendente é conferido a cada fatia.
- O expurgo diário (`0 15 4 * * *`) apaga, em lotes de 1000, decisões e ciclos com mais de 30 dias.

## Estratégias (modelos v1)

Parâmetros comuns:

| Parâmetro | Padrão | Faixa |
|---|---|---|
| `takeProfitPct` | 10 | 1 a 100 % |
| `stopLossPct` | 5 | 1 a 50 % |
| `maxHoldMinutes` | 240 | 15 a 1440 min |
| `buyFractionPct` | 10 | 1 a 100 % |

- **`MOMENTUM_VOLUME`.** Compra quando, na janela (10 a 60 min, padrão 15), o volume cresce pelo menos `volumeGrowthMinPct` e o preço sobe pelo menos `priceRiseMinPct`. A força do sinal é a menor das duas razões.
- **`RANGE_BREAKOUT`.** Compra no rompimento da máxima da faixa (30 a 720 min, padrão 120). Sai com `RANGE_EXIT` quando o preço cai abaixo da mínima.
- **Saídas**, nesta ordem: `TAKE_PROFIT`, `STOP_LOSS`, `MAX_HOLD` e, só no rompimento, `RANGE_EXIT`.
- **Motivos de `NO_TRADE`:**
  - `UNKNOWN_DATA`, `STALE_DATA`, `INSUFFICIENT_HISTORY`, `ASSESSMENT_UNAVAILABLE`;
  - `NO_POSITION_TO_SELL`, `RISK_BAND_HIGH`, `NO_SIGNAL`, `NO_TOKENS`, `QUANTITY_BELOW_MINIMUM`.

Cada decisão grava os parâmetros, as entradas (com fonte, horário e `unknown`), o contexto e os fatores. Os decimais vão sempre como texto. O `DecisionReplayTest` refaz cada decisão a partir do que foi gravado e confere que o resultado é igual.

## Travas

Há uma ordem global única, ligada à transação. Uma trava fora da transação ou em ordem invertida é erro de programação.

1. Pausa do sistema (`system_pause_state`): compartilhada nos comandos e no ciclo, exclusiva na pausa do sistema.
2. Dono (`bot_owner`): serializa o limite de 3 ativos por pessoa.
3. Bot (`bot`).

Os comandos pegam as travas antes de reservar a chave de idempotência. Um tempo de espera esgotado (SQLSTATE 55P03) vira `CannotAcquireLockException`, que volta como 503 e pode ser repetido com a mesma chave. O `LockOrderIT` e o `LockClauseSourceTest` conferem a ordem.

## Processo `worker`

A mesma imagem roda em dois processos, escolhidos por `MEMETRADE_PROCESS` (`api` ou `worker`) e pela anotação `@ProcessRole`.

- **`api`:** rotas, canal ao vivo e as tarefas das Units anteriores.
- **`worker`:** ciclo, meia-noite, encerramento pendente e expurgo da U5.
  - Perfil `worker`, porta de gestão 8081, sem porta publicada no `docker-compose.yml`.
  - Pool de 14 conexões, executor do ciclo com 4 threads, agendador com 4.
  - A guarda por processo aceita pool de 14 a 30 e agendador de 4 a 8.
  - Na `api`, o pool vai de 10 a 50 e o agendador de 14 a 32 (padrão 14).
- O overlay de teste publica o actuator do worker só em `127.0.0.1:8082`, para o cenário de carga.
- O `ProcessRoleContextIT` e o `WorkerProcessRoleContextIT` conferem que cada tarefa existe só no seu processo.

## Canal ao vivo (C7)

`GET /api/v1/stream/dashboard` abre um `text/event-stream` lido com `fetch` e o cabeçalho `Authorization`, nunca com `EventSource`.

- Um `DashboardMarkPoller` único lê as marcas a cada 2 s e envia os eventos do canal que mudou: `bot.state`, `trade.recorded`, `pnl.updated` e `data.status`.
- O `heartbeat` sai a cada 15 s. O canal fecha com `auth.expired` quando o token vence.
- A leitura das marcas (`marksFor`) é a única consulta sem `TenantScope`. Ela recebe a lista explícita de pares (tenant, pessoa) dos próprios canais e devolve só a marca. O `DashboardStreamIT` confere que um canal nunca recebe evento de outra pessoa.

## Rollback

1. Fazer checkout do commit anterior.
2. Rodar `docker compose up -d --build --remove-orphans`, que remove o contêiner `worker`.
3. Rodar `bash scripts/verify.sh`, que precisa sair com código 0.

A migração `0010` só acrescenta tabelas. Os bots `ACTIVE` continuam assim no banco, mas sem ciclo. Voltar à versão da U5 retoma os ciclos na fatia seguinte, sem repetir fatias. O `MigrationsIT` confere a migração e o rollback da `0010`.

## Carga (`LOAD_SCENARIO=u5`)

`scripts/load-test.sh` sobe `api` e `worker` com `test,loadtest`. A fonte simulada roda no cenário de sinais frequentes, aceito só no perfil `test`.

1. O `scripts/load/load-test.mjs` faz entrar 20 pessoas e cria 3 bots ativos por pessoa pelas rotas reais.
2. O `LoadtestBotSeed` semeia 20.000 decisões num bot de cada pessoa.
3. Durante 30 minutos, o cronômetro do worker (`CycleStopwatch`) mede cada ciclo.

Limites:

| Medida | Limite |
|---|---|
| p95 do ciclo | ≤ 2 s |
| p95 de barreira mais execução | ≤ 500 ms |
| Decisões por dado velho | zero |
| p95 das rotas C4 | ≤ 1 s |
| p95 da tela S5 | ≤ 2 s |
| p95 do canal ao vivo | ≤ 4 s |
| Conexões do worker | ≤ 14 |

O relatório vai para `docs/perf/u5-bots-decision-<data>.md`.

## Pendências

- **Responsável do projeto:**
  - Acrescentar `e2e/u5-bots-decision` à linha do Playwright em `scripts/verify.sh`. A linha fica assim:

    ```bash
    "${PNPM[@]}" --dir apps/web exec playwright test e2e/u1-walking-skeleton e2e/u2-audit-trail e2e/u4-discovery e2e/u7-ledger-pnl e2e/u5-bots-decision
    ```

  - O ponto de controle da U5 depende de o `verify.sh` passar com esse smoke.
  - Rodar o cenário de carga `u5` para obter a evidência de NFR2 e NFR3.
- **U7:** a U7 não publicou o bean `LedgerChangeMarks`. Até ele existir, o canal ao vivo usa, como marca do ledger, o instante da operação mais recente.
- **U8:** usa só a C16 (`BotAdministration`): pausa do sistema, liberação e resumo do painel.
- **U9:** usa só a C18 (`BotChangeMarks`).
