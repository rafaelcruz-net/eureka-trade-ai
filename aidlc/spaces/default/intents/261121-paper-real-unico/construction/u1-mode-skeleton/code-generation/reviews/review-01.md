## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-22
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u1-mode-skeleton/code-generation/traceability.json > linhas AC4.1.5, BR10.1, NFR7.7, NFR7.12 (alvo apps/web/e2e/real-v3-paper-real-unico/paper-real-unico.spec.ts) | O BR10.1 e o `team.md` (fatia do intent) exigem que o spec crie um bot PAPER e um REAL, faça uma compra em cada modo e confira a separação. O spec não faz compra: com os dois bots ativos e sem posição, os dois blocos estão vazios, e "cada bloco não mostra o outro bot e a outra moeda" vale trivialmente. O spec também não foi executado e a linha dele não está no `scripts/verify.sh` (linha 162 termina em `e2e/real-v2-exits`), então nenhum job `smoke` ou `e2e` o roda hoje. Mesmo assim essas quatro linhas estão `OK`. O desvio 2 do `code-summary.md` admite a lacuna, mas a rastreabilidade a esconde. | Fazer o spec cumprir o BR10.1 com compras determinísticas (veja a recomendação no resumo), ou registrar a decisão do responsável que altera o BR10.1 e a fatia do `team.md`. Enquanto o spec não rodar com compra, marcar AC4.1.5, BR10.1 e NFR7.7 como parciais na rastreabilidade, com a nota do que falta. O responsável deve acrescentar a linha ao `verify.sh` antes do ponto de controle. | New |
| R-02 | Major | apps/api/src/test/java/com/memetrade/architecture/NeverSumModesArchitectureTest.java > listas PAPER_RESULTS e REAL_RESULTS, partes 1 a 3 | A regra NEVER_SUM_MODES vigia só os tipos de domínio (`PaperModeBlock`, `RealModePnl`, `LamportsValue` etc.) e só dentro de `pnl.modeview`. Os tipos que a rota realmente manipula juntos são os DTOs de `ModePnlDtos` (`PaperModeBlockDto`, `RealModeBlockDto`, `PaperBotPnlLineDto`, `RealBotPnlLineDto`, `LamportsKnownDto`). Eles não estão em nenhuma lista, e `PnlController.byMode` e `ModePnlDtos.PnlByModeDto.of` ficam fora do pacote `modeview`. Uma soma de `paper.total` com `real.total` nessas classes passaria no ArchUnit. As amostras da parte 4 provam só que a regra falha para os tipos listados. | Incluir os DTOs de bloco de cada modo nas duas listas (e nelas o teste de listas fechadas e disjuntas) e estender as partes 2 e 3 a `pnl.web` para a rota C4, com uma amostra de teste que soma DTOs e faz a regra falhar. | New |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/bots/worker/RealCycle.java > `maxOrderSize` (captura só `RealLimitsUnavailable \| DataAccessException`) e apps/api/src/main/java/com/memetrade/riskcontrol/real/infra/RealAccountLimitsReader.java > `share` | `share` usa `Math.multiplyExact`, que lança `ArithmeticException` com um teto absurdo (acima de cerca de 9,2e14 lamports com 10.000 bps). Essa exceção não é capturada no ciclo e derruba o ciclo do bot REAL, em vez de virar `NO_BUY`. Em C3 viraria 500. É improvável, mas o princípio é falhar fechado e sem 500. | Capturar `ArithmeticException` no `maxOrderSize` (teto vira `NO_BUY`, com `WARN`) e converter em `RealLimitsUnavailable` na leitura de C9; incluir um teste com teto próximo de `Long.MAX_VALUE`. | New |
| R-04 | Minor | apps/api/src/test/java/com/memetrade/bots/BotModePathIT.java | O BR1.9 diz que as vendas não usam o teto da conta. O IT cobre compra com teto zero (`withoutACapTheOrderOfARealBotIsZero...`), mas não achei teste de que uma venda de um bot REAL com posição segue normal quando `maxOrderSize` é `NO_BUY`. | Acrescentar um caso: bot REAL com posição aberta, sem teto definido, e uma decisão de venda; a venda segue e só a compra é bloqueada. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u1-mode-skeleton/code-generation/code-summary.md > Pendências registradas | Dois textos de design (`performance-design.md`: valores "nunca lidos do ambiente"; NFR8.1: "0 mudanças nas definições de tarefa do ECS") contradizem o código e o `infrastructure-specification.md` (três chaves lidas por `@ConfigurationProperties` e três variáveis no `api_environment`). O código está certo, mas a contradição fica aberta. | Alinhar os dois textos e o BR1.9 no próximo estágio que puder editar o design, antes da U2. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Verificação dos alvos de `traceability.json` | PASS: 166 alvos de arquivo conferidos, todos existentes e nenhum é diretório | O formato (um arquivo existente por alvo OK) está correto. O problema do BR10.1 é de conteúdo, não de formato. |
| `mvnw verify`, Jest, lint, typecheck e check:api | Não reexecutei (relato do desenvolvedor: 2.179 testes de unidade, 1.454 de integração, JaCoCo 92,6%; 866 testes Jest, 94,6%) | Aceito como evidência declarada. O spec de ponta a ponta não foi executado por ninguém. |

### Summary

A implementação está sólida nos pontos de risco. O ramo REAL do ciclo usa o limite de ordem da conta (arredondado para baixo, 0 sem teto, `limitsVersion = 0`, sem ponto flutuante). A elegibilidade corre dentro do comando idempotente em `NESTED`, a auditoria da recusa em `REQUIRES_NEW` depois do rollback, e nem a recusa nem o 503 ficam gravados. Os cinco motivos dão 409, incluindo `WALLET_INACTIVE` (o `RealRoutesIT` mudou de 422 para 409 sem enfraquecer nenhuma asserção). O `ModePnlView` tem prazo por bloco, pool limitado, MDC copiado e fila cheia como `UNAVAILABLE`. A guarda da semente varre comentários, Dockerfiles, workflows, `.env.example` e Terraform. Os dois pontos Major (spec sem compra por modo e regra NEVER_SUM_MODES que não cobre os DTOs) somam dois, o limite do READY, e devem ser corrigidos antes do ponto de controle. O 409 sem `effectiveAt` é coerente com o contrato C1 (o campo é só de C2).

**Recomendação sobre a escolha em aberto (o spec deve esperar um ciclo com compra?):** não esperar o ciclo de 5 minutos. A espera deixa o teste lento e instável, e o `team.md` trata teste instável como defeito. A compra deve ser determinística:
- REAL: reaproveitar a estratégia gravada que o `e2e/real-swap` já usa (uma compra por bot no mint gravado).
- PAPER: criar a compra por um gancho do perfil de teste (execução de ciclo ou lançamento no ledger PAPER pelo `PaperExecution`) e conferir que cada bloco passa a mostrar a posição ou o P&L do próprio modo, sem o outro. Só assim o spec cumpre o BR10.1 e prova a separação com dados.

Se o responsável preferir manter a versão atual, deve decidir isso por escrito, alterando o BR10.1 e a fatia do `team.md`, e a rastreabilidade deve marcar as linhas como parciais.
