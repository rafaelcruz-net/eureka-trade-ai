## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-05T12:20:00Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/nfr-requirements/performance-requirements.md > NFR2.2 (fatia completa) | A meta aceita que os 60 bots terminem até 120 s depois de `slotStart + 60 s`, ou seja, até `slotStart + 180 s`. O NFR2 da inception exige que cada ciclo termine antes de o dado ficar desatualizado (≤ 60 s), e a barreira da U6 usa a mesma janela de 60 s. Um bot do fim da fila pode ler dado já velho e virar NO_TRADE por dado velho, ou ter a ordem recusada pela barreira. O artefato não relaciona os 120 s com os 60 s de frescor. | Reconciliar os dois números. Opção 1: derivar e registrar a conta (60 bots / 4 em paralelo × p95 2 s ≈ 30 s) e fixar uma meta de fim da fatia compatível com o frescor de 60 s. Opção 2: manter os 120 s e declarar, no próprio NFR2.2, que bots tardios podem ficar sem operar por dado velho, com um limite aceito (por exemplo, % de ciclos NO_TRADE por dado velho no cenário `u5`) e a justificativa de por que isso atende o NFR2. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/nfr-requirements/scalability-requirements.md > NFR2.4 (paralelismo do worker) | O pool do worker é dimensionado em 4 conexões para os ciclos mais 2 para tarefas. Mas a falha do ciclo grava FAILED em transação separada (NFR10.2) enquanto a conexão do ciclo ainda está presa, e o ShedLock e o `lock_timeout` também pedem conexão. Com 4 ciclos falhando ao mesmo tempo (por exemplo, queda do mercado), são necessárias até 8 conexões. O pool de 6 se esgota, e o ciclo espera uma conexão que só é liberada por ele mesmo. | Recalcular o pool incluindo a transação separada de FAILED (e o ShedLock). Fixar o tamanho mínimo, o tempo de espera por conexão e o que acontece ao esgotar. Adicionar um teste com 4 ciclos falhando em paralelo que prove que o FAILED é gravado sem deadlock. | Resolved (reviewer) |
| R-03 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/nfr-requirements/security-requirements.md > NFR4.x (testes obrigatórios por risco) | Dois testes obrigatórios do `team.md` não têm requisito verificável na U5. (1) Dado de mercado ausente vira `unknown`, nunca zero: o NFR1 está N/A e só cita o dado velho (BR4.1); nenhum NFR exige que campo `unknown` nas entradas da decisão gere NO_TRADE e seja gravado como `unknown` em `inputs`. (2) Deduplicação: o NFR4.2 cobre só os comandos de API; nada exige que um ciclo repetido ou reiniciado para o mesmo `(botId, slotStart)` gere uma só ordem candidata, com a mesma chave de idempotência na U6 e na U7. | Adicionar um NFR de `unknown` (decisão com campo ausente: NO_TRADE e nenhum zero em `inputs`, `context` ou tela) e um NFR de deduplicação da ordem do ciclo (repetir ou reiniciar a fatia gera uma só ordem). Incluir ambos em traceability.json e no critério de teste. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/nfr-requirements/tech-stack-decisions.md > Processo worker | A U5 cria um processo novo (perfil `worker`), mas nenhum NFR diz como ele sobe: serviço no Docker Compose, healthcheck, política de reinício, variáveis, e a garantia de que a API não roda o agendador. O smoke do esqueleto e o cenário `u5` dependem disso. A Q6 B tira o indicador da prontidão, e então falta outra forma de saber que o worker está vivo. | Acrescentar um requisito do serviço `worker` no Compose (perfil, healthcheck de liveness simples, reinício) e um teste de contexto de que as tarefas só existem no perfil `worker`. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/nfr-requirements/performance-requirements.md > NFR2.1 e NFR2.3 (medição) | A Q5 B exclui o histograma de duração, mas o NFR2.3 exige medir separadamente `OrderGate.evaluate` mais `PaperExecution.execute` dentro do ciclo, e o NFR2.1 mede "do início da transação ao commit". `finishedAt` é gravado antes do commit e nenhuma instrumentação é declarada. | Declarar como a medição é feita (por exemplo, cronômetros de teste no cenário `u5`, sem métrica de produção) e usar o commit real como fim do NFR2.1. | Resolved (reviewer) |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/nfr-requirements/performance-requirements.md > NFR3.2 | A meta é p95 com 20 usuários, mas a medição é "Playwright no smoke da U5", que roda um usuário por vez e não produz p95 com carga. | Medir o NFR3.2 no cenário `u5` com 20 usuários (Playwright ou medição de rede), ou mudar a carga declarada para 1 usuário no smoke. | Resolved (reviewer) |
| R-07 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u5-bots-decision/nfr-requirements/performance-requirements.md > Frescor dentro da fatia | O argumento de frescor depende de a U4 coletar a cada 30 s. Esse intervalo é uma premissa de outra Unit e não está ligado a nenhum contrato citado no artefato. | No Code Generation, conferir o intervalo de coleta da U4 no contrato compartilhado e registrá-lo como premissa explícita. Não bloqueia. | New |

> R-01 Reviewer note: O NFR2.2 exige zero recusas por frescor causadas pela posição na fila e conta as recusas STALE_DATA no relatório. A seção "Frescor dentro da fatia" explica que cada ciclo lê o preço no próprio instante (coleta de 30 s, dentro dos 60 s da U6) e que as janelas usam o balde fechado em `slotStart` (até 3 min, dentro dos 5 min da BR4.1).

> R-02 Reviewer note: O NFR2.4 passa a 10 conexões (4 ciclos, 4 para a gravação separada de FAILED, 2 para tarefas) e traz o teste de 4 falhas injetadas simultâneas.

> R-03 Reviewer note: NFR4.6 (`unknown`, velho ou indisponível por campo vira NO_TRADE ou sai do filtro, gravado como `UNKNOWN`) e NFR4.7 (uma ordem por fatia, `BotCycle` único e chave `order:<decisionId>`, 50 repetições, segunda chamada `Duplicate`) existem e estão no traceability.json.

> R-04 Reviewer note: O NFR10.9 define o serviço `worker` no Compose, com healthcheck de liveness, reinício, agendador desligado na API e teste de contexto.

> R-05 Reviewer note: A seção "Medição sem métrica permanente" declara o ciclo do perfil `loadtest` cronometrando cada etapa, sem histograma permanente em produção.

> R-06 Reviewer note: O NFR3.2 é medido no cenário `u5` com 20 usuários, mais uma conferência de um usuário no Playwright.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Conferência manual de traceability.json | PASS | NFR4.6, NFR4.7 e NFR10.9 estão definidos nos artefatos e listados; os demais IDs seguem definidos. |

### Summary

Os seis achados anteriores estão corrigidos e não há Critical nem Major abertos. Resta uma observação Minor sobre a premissa de coleta de 30 s da U4.
