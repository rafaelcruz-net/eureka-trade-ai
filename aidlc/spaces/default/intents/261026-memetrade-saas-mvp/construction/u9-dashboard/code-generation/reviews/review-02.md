## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-06T17:16:16Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | apps/web/src/features/dashboard/DashboardProvider.tsx > `renew` do `createStreamClient`; apps/web/src/lib/api/guest-api.ts > `withRenewal` e `refreshSession` | A renovação do token não tem voo único. Quando o token vence, o canal (`auth.expired` ou 401 na abertura) chama `api.refreshSession()` por conta própria, e cada chamada REST do painel que recebe 401 faz o seu `POST /auth/refresh` em `withRenewal`. A carga completa de bots dispara 6 listagens em paralelo, mais as buscas de última ordem. O refresh da U3 gira o token e revoga a sessão inteira quando um token já trocado reaparece (`SessionService`, `REUSED`). Duas renovações simultâneas com o mesmo cookie podem derrubar a sessão da pessoa, e a U9 acrescenta um novo chamador concorrente (o canal). | Fazer uma única renovação em andamento por app, por exemplo com uma promessa compartilhada em `guestApi` usada por `withRenewal` e por `renew` do canal. Acrescentar um teste com 401 simultâneo do canal e de duas chamadas REST que confirme uma única chamada a `/auth/refresh`. | Resolved (reviewer) |
| R-02 | Major | apps/api/src/test/java/com/memetrade/testsupport/AbstractU9IT.java e DashboardOpenLatencyIT, DashboardStreamDelayIT (dados semeados); code-summary.md > Resultados | A suíte completa do backend falha de forma repetível (2 de 2 execuções) em `JdbcAuditEventReaderIT.bothPreparedFormsReadFromTheIndexWithoutASeparateSort` (U2). A causa provável, citada no próprio resumo, é o volume semeado pelas medições da U9 (cerca de 460 bots) no banco compartilhado, que muda as estatísticas do planner. Isso quebra a regra de que testes de integração não dependem de estado compartilhado nem da ordem de execução, e a CI não pode ficar vermelha para fazer merge. O ponto está em aberto no resumo. | Isolar os dados das medições da U9: limpar o que semeiam ao final, ou usar um esquema ou banco próprio, ou rodar `ANALYZE` e remover o volume. Rodar a suíte completa verde antes de aprovar. Não alterar o teste da U2 para acomodar a U9. | Resolved (reviewer) |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/bots/web/DashboardMarkPoller.java > `open()`, linhas do `lastEventId.map(EVENT_ID::matcher)...Long.parseLong` | O `tryAcquire` é feito antes do bloco `try`. O padrão `d+` aceita números maiores que `Long.MAX_VALUE`; um `Last-Event-ID` como `b99999999999999999999.l1.s1` lança `NumberFormatException` depois de a vaga ser tomada e antes de existir um `close`. A vaga vaza até reiniciar o processo e a resposta é 500. Só prejudica a própria pessoa, mas contradiz a garantia de liberar a vaga em toda saída, incluindo falha na abertura. | Ler as marcas antes de `tryAcquire`, ou levar tudo depois dele para dentro do `try`. Tratar marca ilegível como ausente (abrir sem retomada). Cobrir com um teste de `ChannelSlots` ou de integração. | Resolved (reviewer) |
| R-04 | Minor | apps/api/.../bots/web/DashboardMarkPoller.java > `refresh(...)` com `polling=false`; apps/web/src/features/dashboard/DashboardProvider.tsx > `handleEvent` | Na reabertura automática depois de uma queda, o canal não envia `trade.recorded` para ordens gravadas durante a queda (`lastTradeAt` começa no instante da abertura e `newTrades` não roda na abertura). O app só relê bots e a última ordem de bots ainda não lidos. A linha "Última operação" de um bot já lido pode ficar velha depois que o aviso de desatualizado some. Nada na spec exige a retomada, mas o resumo trata a remoção de `newTrades` como sem efeito visível. | Ao passar a OPEN depois de uma queda, reler a última ordem das ordens já lidas (ou apenas a mais recente), ou registrar a lacuna como limitação conhecida da U9 no resumo. | Unresolved |
| R-05 | Minor | aidlc/.../u9-dashboard/code-generation/code-summary.md > Situação dos passos (passo 12); apps/web/e2e/u9-dashboard/smoke.spec.ts | O smoke de ponta a ponta do painel foi escrito, mas nunca executado, e `scripts/verify.sh` ainda não inclui `e2e/u9-dashboard`. A atravessada navegador, API e canal ao vivo, incluindo o 429, não foi verificada, e a regra do time é rodar o smoke em toda mudança. O resumo declara isso com honestidade. | A pessoa acrescenta a linha em `scripts/verify.sh` e roda o comando de verificação autorizado antes de fechar a Unit. | Unresolved |
| R-06 | Major | aidlc/.../u9-dashboard/code-generation/code-summary.md > Medições; apps/api/src/test/java/com/memetrade/bots/web/DashboardOpenLatencyIT.java | A meta NFR3.1 (2 s) foi medida em 1906 a 1942 ms, com margem de cerca de 60 ms. Se o teste faz o build falhar num executor de CI mais lento, vira teste instável, e o time trata instabilidade como defeito. | Decidir se o teste de latência é portão de CI ou medição informativa (com limite da CI mais largo, ou perfil separado). Considerar a consulta por bot de `ledgerMark` como otimização. | Unresolved |
| R-07 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/code-generation/code-summary.md > Resultados depois das correções, comando da Unit | O comando da Unit termina vermelho por `DashboardOpenLatencyIT` (R-06), e a medição de NFR3.1 também ficou acima do limite fora da suíte completa. A aprovação da Unit depende de uma decisão que o resumo deixa em aberto. | Antes de fechar a Unit, decidir e registrar uma das opções: otimizar a abertura do quadro de bots, ou tornar a medição informativa fora do portão (perfil separado ou limite de CI mais largo). | New |

> R-01 Reviewer note: `guestApi` agora tem uma promessa compartilhada (`renewSession`) usada por `withRenewal`, por `refreshSession()` e, via `channelRenewal`, pelo canal. Um 401 tardio com o token já trocado repete a chamada sem novo refresh. `renewal.test.ts` cobre 401 simultâneo do canal e de duas chamadas REST com uma única chamada a `/auth/refresh`, a falha compartilhada e a renovação seguinte. Executei jest em `src/lib/api` e `src/features/dashboard`: 11 suítes, 128 testes verdes.

> R-02 Reviewer note: `AbstractU9MeasurementIT` roda `DashboardOpenLatencyIT` e `DashboardStreamDelayIT` em um banco próprio `u9_measure_*`, com `ANALYZE`, e o teste da U2 não foi alterado. O resumo registra `mvnw verify` com BUILD SUCCESS e `JdbcAuditEventReaderIT` 6 de 6. Não reexecutei a suíte completa e confio no resultado declarado.

> R-03 Reviewer note: `open()` lê o `Last-Event-ID` por `resumeMarks` antes de `tryAcquire`, e `NumberFormatException` vira marca ausente. A criação do emissor libera a vaga se falhar. Há `ResumeMarksTest` e um teste de integração do vazamento de vaga.

> R-04 Reviewer note: Fora de escopo por decisão da pessoa. A lacuna da reabertura sem `trade.recorded` continua registrada nos pontos em aberto do resumo.

> R-05 Reviewer note: Fora de escopo por decisão da pessoa. O smoke `e2e/u9-dashboard` não foi executado e `scripts/verify.sh` não o inclui.

> R-06 Reviewer note: Piorou: o resumo agora informa que `DashboardOpenLatencyIT` falha quando roda isolada (BOTS com p95 entre 2,02 e 2,08 s contra 2 s, em 3 execuções), então o próprio comando da Unit (`-Dgroups=u9`) termina com 9 de 10 no failsafe. Na suíte completa passa. Isso é teste instável e o time o trata como defeito. A pessoa deixou fora de escopo; mantenho como Major porque o comando de verificação da Unit fica vermelho.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| jest src/lib/api src/features/dashboard | PASS, 11 suítes, 128 testes (avisos `act` de `WalletBadge`, sem falha) | Confirma a correção de R-01 e o restante do painel. |
| mvnw `-Dgroups=u9` | Não executado por mim | Uso o resultado declarado no resumo: surefire 16 de 16, failsafe 9 de 10 (a falha é a latência, R-06). |

### Summary

R-01, R-02 e R-03 estão corrigidos no código e em testes. Restam R-06 (agora Major, pois o comando da Unit falha isolado) e dois Minor deixados fora de escopo de propósito, o que dá 1 Major e nenhum Critical. O veredicto é READY, com a recomendação de decidir sobre o teste de latência antes de fechar a Unit.
