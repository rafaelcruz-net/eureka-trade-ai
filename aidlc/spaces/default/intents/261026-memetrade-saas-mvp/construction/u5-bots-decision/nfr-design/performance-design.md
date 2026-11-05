# Design de desempenho — U5 Bots e decisão

Como a Unit `u5-bots-decision` atende NFR2.1 a NFR2.3 e NFR3.1 a NFR3.3 de [performance-requirements.md](../nfr-requirements/performance-requirements.md), com a resposta Q1 de [nfr-design-questions.md](nfr-design-questions.md).

## Ciclo (NFR2.1, NFR2.2, NFR2.3)

- **Despacho.** `SlotDispatcher` roda no worker por cron UTC (`0 1/5 * * * *`, isto é, `slotStart + 60 s`) com ShedLock `bots-slot-dispatch`. Ele lista os bots ACTIVE por índice parcial `bot (state) WHERE state = 'ACTIVE'` e entrega cada um a um executor de 4 linhas.
- **Leituras de mercado por ciclo.** As leituras são feitas uma vez por token, assim:
  - janela pelo `MarketDataQuery.window`, com o tamanho máximo pedido pela estratégia;
  - preço de referência;
  - avaliação pela C12;
  - filtros pela consulta do scanner (`MarketListingQuery`), uma chamada por ciclo.

  Nada é guardado entre ciclos.
- **Leituras da U7.** `LedgerReadModel.positions(botId)` e `balance(botId)`: uma consulta cada.
- **Decisão.** É uma função pura em memória sobre um registro com todas as entradas (BR4.2). O custo é desprezível.
- **Gravações.** São um `INSERT` do BotCycle, um da Decision, uma atualização do bot (estado, `changeSeq`) e a `BotHolding`, todas na transação do ciclo.
- **Frescor.** A U4 coleta a cada 30 s: `MARKET_COLLECTION_INTERVAL`, padrão `30s`, conferido em `apps/api/src/main/resources/application.yml` (`collect-interval`). Cada ciclo lê o preço no seu próprio momento. Um teste de configuração da U5 falha se esse padrão passar de 30 s sem revisão deste design, como pediu a revisão dos requisitos.

## Rotas (NFR3.1, NFR3.2)

| Rota | Índice e técnica |
|---|---|
| `GET /bots` | (tenant, pessoa, estado, atualização decrescente), cursor |
| `GET /bots/{botId}/decisions` | (bot, criação decrescente, decisão), cursor; `factors`, `inputs` e `context` em JSONB lidos só na página |
| Comandos | Uma transação curta com as travas da BR2.11; idempotência pelo mecanismo comum da U1 |
| `GET /strategy-templates` | Tabela pequena semeada; lida a cada chamada |

A tela S5 faz as chamadas C4, C5 e C21 em paralelo, e cada quadro carrega sozinho (CT-UX-2).

## Canal ao vivo (NFR3.3, Q1)

- Um `DashboardMarkPoller` único roda a cada 2 s. Ele faz uma consulta com o máximo de `changeSeq` por pessoa, só das pessoas com canal aberto. A lista fica em memória do processo de API e é formada pelos pares (tenant, pessoa) dos tokens dos próprios canais.
  - Esta é a única consulta da U5 sem `TenantScope`, registrada como exceção.
  - Ela recebe a lista explícita de pares e só devolve (tenant, pessoa, marca).
  - Nunca lê outro campo nem envia dado a quem não é dono: o envio de detalhes para cada canal volta a usar o `TenantScope` daquele canal.
  - Um teste confere que um canal nunca recebe evento de outra pessoa. Na mesma passada, lê as marcas da U7 (`LedgerChangeMarks`) e a marca global da U4.
- Para cada canal cuja marca mudou, o poller busca os detalhes pelas interfaces públicas e envia o evento pelo `SseEmitter` daquele canal.
- O `heartbeat` sai a cada 15 s. O canal fecha com `auth.expired` quando o token vence.
- Latência máxima entre o commit e o evento: cerca de 2 s da leitura mais o envio. Fica dentro dos 4 s do NFR3.3.

## Medição

O cenário de carga `u5` do `scripts/load-test.sh` liga o ciclo do perfil `loadtest`, que cronometra cada etapa. O relatório vai para `docs/perf/u5-bots-decision-<data>.md`.
