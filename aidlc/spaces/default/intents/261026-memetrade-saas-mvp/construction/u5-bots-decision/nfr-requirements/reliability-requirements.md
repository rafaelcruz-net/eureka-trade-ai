# Requisitos de confiabilidade — U5 Bots e decisão

Requisitos de comportamento em falha da Unit `u5-bots-decision`. As fontes são o NFR10 de [requirements.md](../../../inception/requirements-analysis/requirements.md), as regras BR2, BR3 e BR7 de [rules.md](../functional-design/rules.md) e a resposta Q6 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR10.1 | Sem meta de disponibilidade no beta local | A meta vem com a hospedagem | NFR10 |
| NFR10.2 | Falha fechada no ciclo | Falha em C11, C12, U6, U7 ou no banco desfaz o ciclo inteiro e grava FAILED em transação separada; nada de ordem nem mudança de estado parcial; teste com falha injetada em cada chamada | BR3.5 |
| NFR10.3 | Sem repetição nem atraso | Reiniciar o worker no meio de uma fatia não repete o ciclo daquela fatia nem executa fatias perdidas; teste com reinício simulado | BR3.2, AC4.5.4 |
| NFR10.4 | Sem deadlock | A ordem da BR2.11 em todas as transações; teste com ciclo, resume, meia-noite e kill switch concorrentes (50 repetições) | BR2.11 |
| NFR10.5 | Tarefas de execução única | Meia-noite, conferência do encerramento e expurgo usam ShedLock com nomes próprios; repetir uma rodada não duplica efeito | BR2.6, BR2.8, BR9.1 |
| NFR10.6 | Kill switch sem brecha | A corrida entre start e `applySystemPause` termina sempre com o bot pausado pelo sistema ou com o start recusado (L11, 50 repetições) | BR7.1 |
| NFR10.7 | Espera por trava limitada | `lock_timeout` de 2 s e `statement_timeout` de 5 s no início do ciclo; comandos da API com `lock_timeout` de 2 s, e estourar dá `503` com "tentar de novo"; teste com trava segurada por 3 s | BR3.3, C15 |
| NFR10.9 | Worker como serviço próprio | O worker sobe como serviço `worker` no Docker Compose, com a mesma imagem da API, o perfil `worker` e `restart: unless-stopped`. O healthcheck é de liveness e não inclui o indicador informativo de ciclos (Q6). A API sobe com o agendador de bots desligado (`memetrade.bots.scheduler.enabled=false`), e um teste de contexto confere que só o worker tem os beans do agendador. O `verify.sh` passa com o serviço novo | C20, Q6 |
| NFR10.8 | Encerramento pendente concluído | Um bot CLOSING_PENDING vira STOPPED na fatia seguinte ao fechamento da U7, uma vez só, mesmo depois de reinício | BR2.8, AC4.12.4 |
