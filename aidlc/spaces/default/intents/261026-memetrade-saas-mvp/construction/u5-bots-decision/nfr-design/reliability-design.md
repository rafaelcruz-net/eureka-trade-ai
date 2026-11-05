# Design de confiabilidade — U5 Bots e decisão

Como a Unit `u5-bots-decision` atende NFR10.1 a NFR10.9 de [reliability-requirements.md](../nfr-requirements/reliability-requirements.md).

| Requisito | Desenho |
|---|---|
| NFR10.1 | Sem SLO no beta local |
| NFR10.2 | `CycleRunner` chama a transação do ciclo (`TransactionTemplate`) e só captura a exceção do lado de fora dela, depois do rollback. Nessa hora a conexão do ciclo já foi devolvida e a linha RUNNING já sumiu. O `CycleFailureRecorder` abre então uma transação nova, que não está aninhada na do ciclo. Ela grava `BotCycle` FAILED (inserção que não aborta em conflito) com a causa curta e soma o contador de erro. Nunca há duas conexões do mesmo ciclo ao mesmo tempo, nem espera da linha não confirmada. Nenhum `catch` converte falha em sucesso. Teste: falha injetada em cada chamada grava FAILED em menos de 1 s, sem trava presa |
| NFR10.3 | A unicidade (bot_id, slot_start) e o corte `agora > slotStart + 5 min` (SKIPPED/SLOT_EXPIRED). Na partida, o worker só despacha a fatia corrente |
| NFR10.4 | `LockOrder` centraliza a ordem da BR2.11 com quatro métodos: `lockSystemPauseShared` (start e resume), `lockSystemPauseExclusive` (pausa e liberação de sistema), `lockOwner` e `lockBot`. Cada método confere numa variável da transação que a etapa anterior da ordem não foi pulada nem invertida, e lança exceção de programação se foi. Um teste de código-fonte (não ArchUnit) lê todos os arquivos `.java` dos pacotes `bots` e `decision` e falha se `FOR UPDATE`, `FOR SHARE` ou `FOR NO KEY UPDATE` aparecer fora de `LockOrder` |
| NFR10.5 | ShedLock `bots-midnight`, `bots-closing-check` e `bots-decision-purge`; cada uma é repetível sem efeito duplo |
| NFR10.6 | `applySystemPause` e `liftSystemPause` usam `LockOrder.lockSystemPauseExclusive`, gravam `active` e confirmam antes dos lotes (BR7.1). Start e resume usam `lockSystemPauseShared` antes de `lockOwner`. O teste L11 roda 50 vezes |
| NFR10.7 | `SET LOCAL lock_timeout = '2s'` e `statement_timeout = '5s'` no início do ciclo; comandos com `lock_timeout` de 2 s; estouro vira `503 SERVICE_BUSY` com "tentar de novo" |
| NFR10.8 | `ClosingCheckJob` a cada fatia lê `LedgerReadModel.account(botId)` com o bot travado |
| NFR10.9 | Serviço `worker` no Compose (ver infrastructure design); API com `memetrade.bots.scheduler.enabled=false` |

Não há nova tentativa dentro da fatia. A fatia seguinte decide de novo, com dados novos.
