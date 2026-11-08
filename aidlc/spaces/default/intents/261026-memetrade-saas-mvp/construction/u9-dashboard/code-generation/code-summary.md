# Resumo da geração de código — U9 Painel e navegação

Conversa: português (Brasil). Este resumo cobre o [plano de geração de código](code-generation-plan.md) aprovado; a lista completa de arquivos está em `source-manifest.json` e o mapeamento de requisitos em `traceability.json`.

## Situação dos passos

- Passos 1 a 11, 13 e 14: concluídos.
- **Passo 12 (ponta a ponta): incompleto.** O spec `apps/web/e2e/u9-dashboard/smoke.spec.ts` foi escrito e listado com `playwright test --list`, mas não foi executado. O harness protege `scripts/verify.sh`, então a linha do `playwright test` ainda não inclui `e2e/u9-dashboard`; a pessoa acrescenta e roda o comando de verificação autorizado.

## Arquivos principais

| Área | Arquivos |
|---|---|
| Teto de canais (U5) | `apps/api/.../bots/web/ChannelSlots.java`, `DashboardStreamController.java`, `DashboardMarkPoller.java`, `bots/infra/BotProperties.java`, `application.yml` |
| Envelopes do canal | `apps/api/.../bots/web/DashboardEvents.java`, `apps/api/openapi/openapi-guest.yaml`, `apps/web/src/lib/api/schema.d.ts` (gerado) |
| CORS | `apps/api/.../identity/web/GuestCorsPolicy.java` (`Last-Event-ID`) |
| Cliente do canal | `apps/web/src/features/dashboard/sse-reader.ts`, `stream-client.ts`, `envelopes.ts`, `warn.ts` |
| Estado e carga | `dashboard-reducer.ts`, `dashboard-loader.ts`, `concurrency.ts`, `period.ts`, `DashboardProvider.tsx` |
| Quadros | `DashboardScreen.tsx`, `DashboardHeader.tsx`, `DataNoticesBanner.tsx`, `BotsCard.tsx`, `BotRow.tsx`, `PauseButton.tsx`, `LastTradeLine.tsx`, `PnlCard.tsx`, `CardError.tsx`, `messages.ts` |
| Casca única | `apps/web/src/components/shell/AppShell.tsx`, `PrimaryNav.tsx`, `WalletBadge.tsx`, `NoticesRegion.tsx`; telas migradas (scanner, bots, P&L, conta); `AccountMenu.tsx` removido |
| Lint | `apps/web/eslint.config.mjs` |

## Decisões principais

- **Teto de canais atômico:** `ChannelSlots` reserva a vaga com incremento condicional em `ConcurrentHashMap.compute`; um `AtomicBoolean` por canal devolve a vaga uma única vez em qualquer fim (término, tempo esgotado, erro, falha de escrita, `auth.expired`, exceção na abertura). O `429 RATE_LIMITED` com `Retry-After: 30` é escrito pelo `ProblemWriter`, porque a rota produz `text/event-stream`.
- **Envelopes como records `@Schema`:** o JSON enviado não mudou; o OpenAPI passa a descrever os 6 eventos como `oneOf` do 200, e `SourceStatus` entra por `$ref`.
- **Sem dependência nova no frontend:** leitor SSE e cliente do canal em TypeScript puro sobre `fetch`; carregador fora do React com limitador de 3 buscas e "uma em andamento, no máximo uma pendente"; um único `useReducer`.
- **Rota de "Continuar rascunho":** `/bots/new/{botId}`, a rota real da U5 (o desenho dizia `?botId=`).
- **Títulos iguais ao menu (BR6.2):** "P&L PAPER" e "Bots PAPER".
- **Entrada:** `HOME = '/'` e `/` em `GUEST_ROUTES`, para a sessão expirada voltar ao painel.
- **Lint:** proibição de `EventSource` em `src/**`, repetida nos blocos de P&L, bots e painel (o bloco mais específico substitui o geral); no painel também `Number(`, `parseFloat(` e `+valor`.
- **Medição do atraso do canal:** ordens gravadas pelo `PaperDriver` sobre o `PaperExecution` real da U7, com perfil de referência de 3 bots ativos e 20 encerrados semeado pelos comandos reais.

## Desvios do plano

- **Correção fora do plano:** a CORS do convidado não aceitava `Last-Event-ID`, o que faria o navegador recusar toda reabertura do canal depois do primeiro evento. O cabeçalho foi aceito nas rotas com token, com teste de regressão em `GuestCorsPolicyTest`.
- **Abertura do canal da U5 sem `newTrades`:** com a chamada, o p95 do grupo CHANNEL ficava entre 2044 e 2121 ms (meta 2 s) em três execuções. O painel lê as últimas ordens por REST ao abrir, então nada visível muda; uma ordem gravada no exato instante da abertura não vira `trade.recorded`.

## Correções depois da primeira revisão

A pessoa escolheu corrigir antes de fechar a Unit os dois achados Major e o Minor da vaga presa.

- **Renovação do token em voo único:** cada instância da API tem agora uma só promessa de renovação (`apps/web/src/lib/api/guest-api.ts`). O canal (`channel-renewal.ts`, usado pelo `DashboardProvider`), as chamadas REST em `withRenewal` e o `SessionProvider` esperam a mesma promessa, então sai um único `POST /auth/refresh`. Um 401 que chega depois de outra renovação repete a chamada com o token novo, sem renovar de novo. A regra de uma renovação por tentativa do canal (NFR6.6) e o comportamento quando a renovação falha não mudaram. Testes em `apps/web/src/lib/api/renewal.test.ts` (4 casos, entre eles 401 simultâneos de REST e do canal com exatamente 1 refresh).
- **Medições da U9 num banco só delas:** o ledger é append-only e a auditoria só aceita remoção pela retenção. Por isso os dados semeados não podem ser apagados no fim da classe. `DashboardOpenLatencyIT` e `DashboardStreamDelayIT` passam a rodar num banco próprio `u9_measure_*` no mesmo contêiner, preparado e migrado pelo mesmo caminho do `U7VolumeDatabase` (`AbstractU9MeasurementIT`), com `ANALYZE` depois de semear. O teste da U2 não mudou.
- **Vaga do canal:** `DashboardMarkPoller` lê o `Last-Event-ID` (`resumeMarks`) antes de reservar a vaga; um id ilegível, inclusive com número acima de `Long.MAX_VALUE`, é tratado como ausente, e a criação do emissor libera a vaga se falhar. Testes: `ResumeMarksTest` (4) e `DashboardChannelLimitIT.anUnreadableLastEventIdNeverLeaksASlot`.

Resultados depois das correções:
- `jest --coverage`: 33 suítes, 391 testes; linhas 96,03%. `tsc`, `eslint`, `prettier` e `spotless:check`: limpos.
- Suíte completa do backend (`mvnw verify`): BUILD SUCCESS, failsafe 748 testes sem falha (inclusive `JdbcAuditEventReaderIT` 6 de 6), cobertura cumprida.
- Comando da Unit (`-Dgroups=u9 ...`): surefire 16 de 16; failsafe 9 de 10. **Falha `DashboardOpenLatencyIT`** quando roda isolada: grupo BOTS com p95 entre 2,02 e 2,08 s contra o limite de 2 s, em três execuções. A versão anterior, no banco compartilhado, também deu 2,020 s isolada, então a falha não vem do banco separado. Na suíte completa passou. O limite e o teste não foram alterados.

## Resultados da primeira geração

- Backend da U9 (`-Dgroups=u9 ...`): BUILD SUCCESS. Surefire 12 de 12; failsafe 9 de 9.
- `DashboardStreamIT` da U5: 8 de 8. Contratos OpenAPI (Guest 12, U5 4, U9 3) e `GuestCorsPolicyTest` (6): verdes.
- Suíte completa do backend (`mvnw verify`), duas execuções: surefire 886 testes sem falha; failsafe 747 testes com **1 falha nas duas vezes** em `JdbcAuditEventReaderIT.bothPreparedFormsReadFromTheIndexWithoutASeparateSort` (U2). Sozinho, passa 6 de 6. Causa provável: o volume semeado pelas medições da U9 (cerca de 460 bots) muda as estatísticas do planner no banco compartilhado. O teste da U2 não foi alterado.
- Cobertura do backend (`jacoco:report@report-merged jacoco:check@check-line-coverage` sobre o `.exec` mesclado): 12309 de 13113 linhas, 93,9%, piso cumprido.
- `apps/web`: `jest --coverage` com 32 suítes, 387 de 387; linhas 96,01%. `tsc --noEmit`, `eslint .`, `prettier --check` e `spotless:check`: limpos.
- Medições: NFR3.1 com BOTS 1916 a 1935 ms e CHANNEL 1906 a 1942 ms (P&L, fontes e casca entre 600 e 900 ms); NFR1.1 com p95 de 2,18 a 3,28 s e máximo de 3,31 s.

## O que não rodou

- Smoke `e2e/u9-dashboard`: depende da mudança em `scripts/verify.sh` (protegido) e do ambiente completo.

## Pontos em aberto

- NFR3.1 no limite: `DashboardOpenLatencyIT` falha isolada nesta máquina (grupo BOTS entre 2,02 e 2,08 s) e passa na suíte completa; é um teste instável e precisa de decisão (otimizar a abertura do quadro de bots, ou tratar a medição como informativa fora do portão).
- Na reabertura automática, o canal não envia `trade.recorded` das ordens gravadas durante a queda; a linha "Última operação" de um bot já lido pode ficar velha até a próxima carga completa. Otimização possível: `ledgerMark` faz uma consulta por bot, e uma marca por pessoa depende da U7 oferecer `LedgerChangeMarks`.
- Avisos `act(...)` do React em `DashboardScreen.test.tsx` (despachos de `PNL_FAILED` e `RECONCILIATION_FAILED` depois do fim de alguns testes): os testes passam, mas o console fica com ruído.

## Volta 1 de Build and Test (2026-11-07)

- **Achado T1:** o `traceability.json` estava no formato `unit`/`basePath`/`links`, e o leitor de rastreabilidade o recusava. Ele foi reescrito no formato `stage`/`unit`/`upstream_ids`/`coverage`, com as mesmas 59 linhas, todas `OK`. Cada `target` agora é um único arquivo existente, o primeiro da lista original, e o texto original ficou em `note`. O leitor aceita o arquivo, e nenhum ID da U9 fica sem cobertura.
- **Achado F2 (`DashboardOpenLatencyIT`), não corrigido nesta volta:** a pessoa aprovou o plano como estava, sem o passo que tornaria a medição informativa (decisão Q1 = B do Build and Test). O teste continua reprovando com p95 acima de 2 s, e o NFR3.1 continua escrito como portão. A diferença volta a aparecer no Build and Test.
- **Passo 12:** continua incompleto, pelo mesmo motivo de antes. A inclusão de `e2e/u9-dashboard` em `scripts/verify.sh` é uma pendência da pessoa.
- **Código:** nenhum código de produção ou de teste mudou nesta volta.
