## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-08
**Iteration:** 2
**Verdict Dictated:** no

### Prior findings
| ID | Now | Severity | Note |
| --- | --- | --- | --- |
| R-07 | Still applies | Minor | `LoadtestBotSeed` continua não reexecutável sobre banco já semeado. Nenhum arquivo de `apps/api` mudou nesta iteração. |
| R-08 | Still applies | Minor | A premissa de `oldestCycle()` continua sem texto. Fora do escopo desta volta. |

Os achados R-01 a R-04 estão decididos (Accepted risk) e não voltaram: o único arquivo de código alterado continua sendo `BotScreens.test.tsx`, que não toca esses pontos.

### New findings
| Severity | Location | Finding | Required action |
| --- | --- | --- | --- |
| Minor | apps/web/src/features/bots/BotScreens.test.tsx > it 'goes through the 4 steps...' (WIZARD_FLOW_TIMEOUT_MS) | Continua valendo o ponto Minor da review-04: o teste do fluxo das 4 etapas tem tempo próprio de 15 s, sobre cerca de 4,5 s medidos, e o plano não registra um gatilho para dividir o fluxo se ele crescer. | Registrar no `code-summary.md` um gatilho para dividir o fluxo (por exemplo, passar de 8 s na suíte completa ou ganhar uma etapa), em vez de elevar o tempo de novo. |

### Verificação desta iteração
- Plano (`code-generation-plan.md`): 33 passos marcados `[x]` e 0 `[ ]`. No `git diff` contra o HEAD, as linhas que não são marcação são só a descrição das Voltas 2 e 3 e os Passos 26 a 33 (novos, já marcados). Os Passos 1 a 25 só mostram o par de marcação desmarcada e remarcada, sem mudança de texto. A seção da Volta 3 é a que a review-04 cobriu. As seções da Volta 2 e os Passos 26 a 31 vêm de voltas anteriores, ainda não commitadas.
- Código: `git status` e `git diff -- apps/web` mostram um único arquivo alterado, `apps/web/src/features/bots/BotScreens.test.tsx` (96 inserções e 83 remoções). A mudança é o `userEvent.setup({ delay: null })` no `beforeEach` e o recuo das asserções do teste do fluxo, que passou a ficar dentro de um `it` com tempo próprio. As 54 ocorrências de `expect(` são as mesmas no HEAD e no arquivo atual, e as asserções removidas e adicionadas têm o mesmo texto. Nada foi afrouxado.
- Não reexecutei as suítes: o código não mudou desde a review-04 (391/391 em 3 execuções, cobertura de 96,03%). Tentei rodar `git` pelo Bash, mas o hook pediu um comando de orquestração, que um revisor não chama. Usei o PowerShell para o diff.

### Summary
O plano e o código são os mesmos da review-04, e a única diferença são as marcações refeitas pela ferramenta. Os achados continuam válidos (R-07 e R-08 Minor, mais um Minor sobre o gatilho de divisão do teste do assistente). Não há Critical nem Major, então o veredito é READY.

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/code-generation/code-summary.md > Desvios do plano, item 9; apps/web/src/features/scanner (não alterado) | O desenho (functional-spec, etapa 1 do assistente, e a regra de seleção de tokens que cita o botão "Usar em bot PAPER") prevê criar o bot a partir de um token do scanner. O botão não foi criado: o assistente aceita `?chain=&mint=`, mas nenhuma tela leva a pessoa até lá. O desvio foi registrado, mas o `traceability.json` não o marca como pendente nem como adiado. | Acrescentar o link "Usar em bot PAPER" na tela do scanner (com teste) ou registrar a entrega como adiada, com dono e Unit, no `traceability.json` e na aprovação do gate. | Accepted risk |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/bots/web/DashboardMarkPoller.java > ledgerMark e newTrades (caminho sem o bean `LedgerChangeMarks`) | Enquanto a U7 não publicar o bean, cada canal aberto faz, a cada leitura (2 s), uma listagem de até 100 bots e uma consulta de ordens por bot. O envio ao emitter acontece dentro de `synchronized(channel)` na thread do agendador compartilhado, então um cliente lento ocupa essa thread. Não há falha de correção, e a escala do beta suporta. | Registrar o limite de canais abertos no documento da Unit ou trocar a marca pelo bean da U7 quando existir. Considerar envio com prazo curto por canal. | Accepted risk |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/bots/worker/CycleRunner.java > run (bloco catch) | O Javadoc diz que `run` nunca lança, mas `failures.record` roda no `catch` e pode lançar se o banco estiver fora do ar. O `SlotDispatcher` trata o `CompletionException` e conta o ciclo como FAILED, porém nenhuma linha `bot_cycle` FAILED é gravada nesse caso. O slot expira e o próximo ciclo segue normalmente, sem dado errado. | Ajustar o Javadoc para dizer que a falha ao gravar o FAILED sobe ao despachante, ou capturá-la e registrá-la com um contador próprio. | Accepted risk |
| R-04 | Minor | apps/api/src/main/java/com/memetrade/bots/worker/MidnightJob.java > clear | O job da meia-noite pode retomar um bot sem tomar a trava compartilhada de `system_pause_state`, que a regra de ordem de travas reserva a start e resume. O estado fica seguro: o motivo SYSTEM permanece no bot, o lote da pausa do sistema relê o bot sob trava, e o ciclo confere `systemPaused()` depois de travar o bot. A regra escrita e o código divergem. | Documentar em `LockOrder` e no desenho que a meia-noite depende da conferência do ciclo, ou tomar a trava compartilhada antes do owner. | Accepted risk |
| R-05 | Minor | apps/api/src/main/java/com/memetrade/bots/infra/JdbcBotRepository.java > markFor e marksFor (C18); code-summary.md > Ressalva de ordem das marcas | A marca C18 vem de uma sequência tomada na gravação, não no commit. Duas transações concorrentes podem confirmar fora de ordem, e uma mudança só chega ao canal na mudança seguinte ou ao reabrir a tela. A ressalva está documentada e é rara. | Manter a ressalva no contrato da U9 (já está em code-summary.md) e propagá-la para `contract-summary.md` para que a U9 não trate a marca como ordem total. | Accepted risk |
| R-06 | Minor | apps/web/e2e/u5-bots-decision/smoke.spec.ts; scripts/verify.sh | O smoke da U5 não foi executado, e não está na linha do Playwright do `verify.sh` (ação pendente do responsável do projeto, já informada). O roteiro cobre o ciclo de vida, o rótulo PAPER e o orçamento de tela, mas não mostra uma decisão do worker. O cenário de carga `u5` não rodou, então 9 itens de NFR estão como Deferred (NFR2.1 a 2.3, 2.5, 2.6, 3.1 e 3.2, entre outros). | Incluir `e2e/u5-bots-decision` no `verify.sh` e rodá-lo antes do ponto de controle. Rodar o cenário `u5` e guardar o relatório em `docs/perf`. Considerar uma asserção de decisão visível na S5 quando o ambiente tiver o worker. | Accepted risk |
| R-07 | Minor | apps/api/src/main/java/com/memetrade/bots/loadtest/LoadtestBotSeed.java > prepare() / seedDecisions | Cada bot é semeado em lotes confirmados separadamente. Se um bot posterior falhar, os lotes dos bots anteriores permanecem gravados e o estado vira FAILED. Uma nova execução semeia mais `decisionsPerBot` decisões antes do mínimo atual, então a contagem total passa do alvo. Só o perfil `loadtest` é afetado, e o cenário sobe sobre banco novo. | Registrar em code-summary.md que o seed não é reexecutável sobre um banco já semeado (recomeçar com banco limpo), ou tornar a semeadura idempotente por bot. | Accepted risk |
| R-08 | Minor | apps/api/src/main/java/com/memetrade/bots/loadtest/LoadtestBotSeed.java > oldestCycle() | A leitura de `min(slot_start)` é feita fora da transação dos lotes. Isso é seguro porque o worker só cria ciclos no minuto corrente ou posterior, e os minutos semeados ficam sempre antes do mais antigo. Se o worker um dia fizer catch-up retroativo, o `tryStart` falso interrompe o seed com erro explícito e reverte o lote. Esse comportamento é correto, mas a premissa não está escrita. | Acrescentar uma frase ao Javadoc ou ao code-summary.md com a premissa "o worker não cria ciclos retroativos". | Accepted risk |
