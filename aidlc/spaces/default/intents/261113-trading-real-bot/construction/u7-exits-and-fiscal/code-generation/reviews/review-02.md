## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-20
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | apps/api/src/main/java/com/memetrade/realexits/application/ConditionalService.java > `create` (e `position`) | A criação lê a posição do ledger e insere a condicional sem a trava da pessoa e sem olhar uma ordem de venda REAL já em andamento (BR3.7 só exige "sem outra condicional aberta"). Uma venda a mercado enviada antes da inserção, mas ainda não lançada, deixa a condicional apontar para uma posição que já saiu. A venda dupla continua impossível: o guarda cancela a condicional na próxima venda, e o provedor real recusaria a criação sem o token. Mas hoje nenhum teste cobre essa janela. | Antes do adaptador real, criar sob `withPersonLock` e recusar (ou esperar) enquanto houver ordem de venda REAL em andamento na posição; acrescentar um teste para essa janela. | Accepted risk |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/realexits/application/FillPosting.java > `post` (cálculo de `sold`) | Quando a variação do token lida na rede é maior ou igual a zero, `sold` assume `amountBaseUnits` da condicional, sem conferir se ele cabe na posição do ledger. O resumo declara isso como desvio por causa do adaptador simulado, que nunca preenche. Com o adaptador real, um valor inventado poderia entrar no ledger. | Com o adaptador real, tornar a variação do token da rede obrigatória (sem `fallback`) e recusar o lançamento quando `sold` for maior que a posição do ledger. Registrar isso no plano da entrega do BR0. | Accepted risk |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/ledger/infra/JdbcRealLedger.java > `streamEntries` (consulta) | A função de janela `min(created_at) over (partition by reference)` roda sobre todo o ledger REAL do tenant no período, e só depois filtra por `bot_wallet_id`. O isolamento está correto (tenant e carteiras da pessoa, modo REAL), mas o custo cresce com o tenant inteiro, não com a pessoa. | Mover o filtro `bot_wallet_id in (:wallets)` para dentro da subconsulta, antes da janela. O teste de 20 mil lançamentos continua valendo. | Accepted risk |
| R-04 | Minor | apps/api/src/main/java/com/memetrade/realexits/fiscal/FiscalExportService.java > `export` e FiscalExportLimiter | A auditoria `REAL_FISCAL_EXPORTED` só é gravada se o fluxo termina. Uma exportação interrompida no meio gasta uma das 10 por hora e não deixa rastro. O limitador é em memória, por instância da API, então com mais de uma instância o limite efetivo passa de 10. Ambos são aceitáveis no piloto. | Registrar a interrupção na auditoria (sem conteúdo) e anotar no runbook que o limite é por instância até haver limitador compartilhado. | Accepted risk |
| R-05 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/construction/u7-exits-and-fiscal/code-generation/code-summary.md > Cobertura e Itens abertos | O `verify` da API foi parado por falta de memória antes de terminar as ITs, o relatório e o `jacoco:check`: o piso de 80% da API não foi medido. Os specs `real-exports` e `real-exits` e a linha do `promtool` não rodam na CI até o responsável colar os trechos no `verify.sh` e no `ci.yml` (item aberto, declarado). Não rodei o Maven, como pedido. | Fechar o piso da API na execução da CI ou numa nova execução local, e colar os trechos prontos antes de considerar a Unit entregue. | Accepted risk |
| R-06 | Minor | apps/api/src/test/java/com/memetrade/realexits/web/ConditionalCreationIT.java > br35TheRefusalOfTheProviderIsTriggerRejectedWithItsReasonAndLeavesNothingBehind, asserção de `real_risk_reservation` | A checagem "nenhuma reserva OPEN" passa trivialmente, porque o condicional nunca reserva (BR4.2). Ela não prova que uma reserva foi liberada, só que nenhuma existe. A nota do teste e o pedido de revisão já registram isso. | Manter o texto "no OPEN reservation" no código-fonte e no resumo, sem chamar a asserção de "reserva liberada". Quando um caminho que reserva existir, trocar por um teste que reserve e depois libere. | New |
| R-07 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/construction/u7-exits-and-fiscal/code-generation/traceability.json > linhas Deferred BR1.1 a BR2.5, NFR1.1 | O texto de adiamento é igual em nove linhas. Está correto, mas repetitivo. O teste SIGNING_POLICY_REFUSED cobre a tradução de erro da API (BR2.4) com o `ScriptedTriggerPort`, não a política real, e BR2.4 continua corretamente Deferred. | Sem ação obrigatória. Opcional: citar, na nota de BR2.4, que a API traduz o `Rejected(signingPolicy=true)` e que a política real do TxSigner fica para depois do portão BR0. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Leitura de traceability.json (UTF-8, `json.load`) | 117 linhas, 117 IDs únicos, 103 OK e 14 Deferred, `reverse` vazio | Coerente com o relatado. |
| Existência dos alvos das linhas OK | 0 ausentes (cada `target` é um arquivo existente) | Nenhuma linha OK aponta para arquivo inexistente. |
| Busca de U+FFFD no arquivo | 0 ocorrências | O arquivo é UTF-8 válido. A impressão do console mostrou caracteres trocados só por causa da codificação do terminal. |
| Linhas Deferred | BR0.2, BR0.5, BR1.1, BR1.3, BR1.4, BR2.1 a BR2.5, BR9.2, NFR1.1, NFR1.3, NFR8.2, todas com a justificativa do portão BR0 | Completas. Nenhum item sob portão foi marcado OK. |
| `git diff` da produção | Só `ConditionalCreationIT.java` mudou em `apps/` (o restante é `apps/web/aidlc/`, não rastreado e fora do escopo) | Nenhum código de produção mudou. Nenhuma `TriggerPolicy` nem adaptador real foi adicionado. O TxSigner segue recusando com `POLICY_NOT_RECORDED`. O portão BR0 não foi enfraquecido. |
| Não rodado | Maven e `verify.sh`, por restrição do pedido. A CI verde é a evidência informada | A execução dos ITs não foi verificada por este revisor. |

### Summary

Os dois novos ITs exercitam o caminho real de `ConditionalService`. TRIGGER_REJECTED devolve 422 em RFC 7807 com `code`, `type`, `correlationId` e o motivo do provedor no `detail`. O condicional termina FAILED, sem sessão de assinatura nem ordem no provedor, sem lançamento no ledger, com segunda assinatura recusada (409) e a posição livre de novo (201). O teste de SIGNING_POLICY_REFUSED confere 422, o `code`, FAILED e ledger vazio. A rastreabilidade está honesta e o portão BR0 está intacto. Os dois achados são Minor.
