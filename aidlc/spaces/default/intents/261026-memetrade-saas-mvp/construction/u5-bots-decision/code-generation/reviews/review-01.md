## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-05T17:35:00Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/code-generation/code-summary.md > Desvios do plano, item 9; apps/web/src/features/scanner (não alterado) | O desenho (functional-spec, etapa 1 do assistente, e a regra de seleção de tokens que cita o botão "Usar em bot PAPER") prevê criar o bot a partir de um token do scanner. O botão não foi criado: o assistente aceita `?chain=&mint=`, mas nenhuma tela leva a pessoa até lá. O desvio foi registrado, mas o `traceability.json` não o marca como pendente nem como adiado. | Acrescentar o link "Usar em bot PAPER" na tela do scanner (com teste) ou registrar a entrega como adiada, com dono e Unit, no `traceability.json` e na aprovação do gate. | New |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/bots/web/DashboardMarkPoller.java > ledgerMark e newTrades (caminho sem o bean `LedgerChangeMarks`) | Enquanto a U7 não publicar o bean, cada canal aberto faz, a cada leitura (2 s), uma listagem de até 100 bots e uma consulta de ordens por bot. O envio ao emitter acontece dentro de `synchronized(channel)` na thread do agendador compartilhado, então um cliente lento ocupa essa thread. Não há falha de correção, e a escala do beta suporta. | Registrar o limite de canais abertos no documento da Unit ou trocar a marca pelo bean da U7 quando existir. Considerar envio com prazo curto por canal. | New |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/bots/worker/CycleRunner.java > run (bloco catch) | O Javadoc diz que `run` nunca lança, mas `failures.record` roda no `catch` e pode lançar se o banco estiver fora do ar. O `SlotDispatcher` trata o `CompletionException` e conta o ciclo como FAILED, porém nenhuma linha `bot_cycle` FAILED é gravada nesse caso. O slot expira e o próximo ciclo segue normalmente, sem dado errado. | Ajustar o Javadoc para dizer que a falha ao gravar o FAILED sobe ao despachante, ou capturá-la e registrá-la com um contador próprio. | New |
| R-04 | Minor | apps/api/src/main/java/com/memetrade/bots/worker/MidnightJob.java > clear | O job da meia-noite pode retomar um bot sem tomar a trava compartilhada de `system_pause_state`, que a regra de ordem de travas reserva a start e resume. O estado fica seguro: o motivo SYSTEM permanece no bot, o lote da pausa do sistema relê o bot sob trava, e o ciclo confere `systemPaused()` depois de travar o bot. A regra escrita e o código divergem. | Documentar em `LockOrder` e no desenho que a meia-noite depende da conferência do ciclo, ou tomar a trava compartilhada antes do owner. | New |
| R-05 | Minor | apps/api/src/main/java/com/memetrade/bots/infra/JdbcBotRepository.java > markFor e marksFor (C18); code-summary.md > Ressalva de ordem das marcas | A marca C18 vem de uma sequência tomada na gravação, não no commit. Duas transações concorrentes podem confirmar fora de ordem, e uma mudança só chega ao canal na mudança seguinte ou ao reabrir a tela. A ressalva está documentada e é rara. | Manter a ressalva no contrato da U9 (já está em code-summary.md) e propagá-la para `contract-summary.md` para que a U9 não trate a marca como ordem total. | New |
| R-06 | Minor | apps/web/e2e/u5-bots-decision/smoke.spec.ts; scripts/verify.sh | O smoke da U5 não foi executado, e não está na linha do Playwright do `verify.sh` (ação pendente do responsável do projeto, já informada). O roteiro cobre o ciclo de vida, o rótulo PAPER e o orçamento de tela, mas não mostra uma decisão do worker. O cenário de carga `u5` não rodou, então 9 itens de NFR estão como Deferred (NFR2.1 a 2.3, 2.5, 2.6, 3.1 e 3.2, entre outros). | Incluir `e2e/u5-bots-decision` no `verify.sh` e rodá-lo antes do ponto de controle. Rodar o cenário `u5` e guardar o relatório em `docs/perf`. Considerar uma asserção de decisão visível na S5 quando o ambiente tiver o worker. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `apps/api/mvnw -f apps/api/pom.xml verify` (evidência informada no brief, não reexecutada) | BUILD SUCCESS, 813 unitários, 647 de integração, 93,78% de linhas (`bots` e `decision` com 92,17%) | Piso de 80% mantido. Os testes de ciclo, falha, travas, tenant e REAL desligado fazem asserções reais (CycleIT 41, CycleFailureIT 22). |
| Jest, ESLint, `tsc`, Prettier e `check:api` (evidência informada) | 245 testes, 95,69% de linhas, sem erros | Piso mantido. A regra de `DecimalString` foi estendida às telas de bots. |
| `bash scripts/verify.sh` (evidência informada) | exit 0, 6 testes Playwright | Não inclui o smoke da U5, que depende do dono. |
| Conferência dos caminhos do `traceability.json` e do `source-manifest.json` (rodada nesta revisão) | 0 caminhos ausentes entre as entradas OK; 238 de 238 escritas do manifesto existem; 144 IDs (133 OK, 2 N/A para a U8, 9 Deferred) | Rastreio resolve. Os adiados são os da carga e o botão do scanner (não listado, ver o Major). |
| Varreduras de código (rodadas nesta revisão) | Sem `double`/`float` em `bots` e `decision`; sem `Instant.now` e sem `now()` no domínio (relógio injetado); logs sem token, nonce ou assinatura; SDK Solana só em escopo `test` no `pom.xml`; todo `@Scheduled` tem `@ProcessRole`; SQL com filtro de tenant e pessoa nas leituras e escritas por bot | Regras duras respeitadas. A varredura de `@Scheduled` achou todos os 18 arquivos com o papel, inclusive o nome qualificado. |

### Summary

A Unit está pronta para o gate. O ciclo trava só a linha do bot, confere pausa e kill switch depois de travar, passa pela barreira da U6 e pela execução da U7 com chave `order:<decisionId>`, e grava o FAILED em transação nova depois do rollback. A ordem de travas é imposta em código e testada, e cada trabalho roda em um único papel de processo. O que fica aberto é o botão "Usar em bot PAPER" prometido pelo desenho e não entregue (Major), mais pendências de verificação (smoke no `verify.sh`, carga `u5`) e pequenos ajustes de documentação. Totais: 0 Critical, 1 Major, 5 Minor.
