# Requisitos de confiabilidade — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

Este arquivo cobre o comportamento sob falha (Q4), a exatidão dos valores (NFR2, Q2) e a verificação (NFR7, Q2). Exatidão e testes ficam aqui porque são parte de "a U2 faz o que promete", como na U1 [Q2] [Q4].

## Disponibilidade

| ID | Requisito | Valor |
|---|---|---|
| NFR4.4 | A U2 não cria meta de disponibilidade nova além da do monólito. | 0 SLOs novos; o grupo de prontidão (`readinessState`, `db`, `migrations`) não muda; nenhuma leitura da U2 entra na verificação de saúde. |
| NFR4.5 | Uma falha do bloco REAL nunca derruba o bloco PAPER nem a resposta de C4 (BR5.3 da U1, BR8.7). | Resposta 200 em 100% dos casos de falha da U2 (divergência, `UNKNOWN_BOT`, erro e tempo esgotado da atribuição, erro do fluxo do ledger): o bloco REAL sai `UNAVAILABLE` e o bloco PAPER sai com os mesmos números de antes da falha. Teste de integração com os 5 casos. |

## Tolerância a falhas e degradação

| ID | Requisito | Valor |
|---|---|---|
| NFR1.20 | No `RealCycle`, com a atribuição falhando, a quantidade sempre chega e o custo chega desconhecido (BR2.7). | Teste de integração: com a leitura de custo falhando, o ciclo não decide compra do token afetado (0 decisões de compra) e uma venda desse token segue pela quantidade (1 decisão de venda aceita pela barreira). O ciclo não termina com erro por causa da atribuição. |
| NFR1.21 | Um conflito de slot nunca derruba a confirmação (BR1.4, C13). | Teste de integração nos três gravadores (confirmação da ordem REAL, `FillPosting` da condicional, confirmação de depósito, saque e taxa): com a assinatura já gravada com outro slot, `recordSlot` devolve `CONFLICT_REFUSED`, a ordem ou o movimento chega ao estado final, os lançamentos do ledger ficam gravados, o slot da tabela continua o primeiro, 1 evento `REAL_SLOT_CONFLICT` é gravado e `ledger_real_slot_conflicts_total` sobe 1. Com o mesmo slot, `ALREADY_RECORDED` e 0 eventos. Com o slot desconhecido, 0 linhas gravadas e a linha fica `SLOT_MISSING` na leitura. |
| NFR1.22 | A leitura do P&L pode ser repetida sem efeito colateral e nada é guardado entre leituras (ADR-004, F8). | 0 gravações por leitura com `MATCH`; com `MISMATCH`, no máximo o evento limitado de NFR3.15 (teste de integração conta as linhas das tabelas tocadas antes e depois de 3 leituras seguidas). Depois de uma divergência, a leitura seguinte refaz a conferência do zero: o bloco volta a `PRONTO` quando a causa some, sem passo manual. |

## Exatidão

| ID | Requisito | Valor |
|---|---|---|
| NFR2.1 | Uma divergência com o ledger deixa o bloco REAL `UNAVAILABLE`, sem nenhum número parcial (BR8.7, F4). | Teste de integração com cada uma das 8 verificações forçada a falhar (`REALIZED`, `FEES`, `UNREALIZED`, `COST_CONSERVATION`, `QUANTITY_CONSERVATION`, `EVENT_SUMS`, `UNOWNED_WITHOUT_REASON`, `UNKNOWN_BOT`): bloco REAL `UNAVAILABLE` com `code = RECONCILIATION_MISMATCH`; 0 linhas, 0 shares e 0 totais REAL em C4, em `botPnl` e em `GET /api/v1/real/pnl`; 1 evento `REAL_PNL_RECONCILIATION_MISMATCH` na primeira leitura da hora (limite de NFR3.15); `pnl_real_reconciliation_mismatches_total{check}` sobe 1 na série da verificação que falhou, em toda leitura. A falha da gravação do evento não muda a resposta (NFR3.15). |
| NFR2.2 | Um erro técnico ou o tempo esgotado da atribuição deixa o bloco REAL `UNAVAILABLE` com a causa técnica, e não com `RECONCILIATION_MISMATCH` (BR2.7, F8). | Teste de integração: erro de leitura e tempo esgotado (500 ms da atribuição, ou o prazo de 800 ms do bloco, NFR1.12) dão `UNAVAILABLE` com a causa técnica, 0 eventos de divergência e 0 incrementos de `pnl_real_reconciliation_mismatches_total`; o caminho `PNL` de `pnl_real_attribution_read_seconds` registra `outcome = ERROR` ou `TIMEOUT`. |
| NFR2.3 | A migração `0023` é aditiva e o rollback devolve só a imagem (`team.md`, Deployment). | A `0023` só cria (NFR3.6). As imagens da versão anterior sobem e respondem C4 com a tabela nova presente: teste de integração da versão anterior contra o banco migrado, ou, se o NFR Design preferir, a conferência de que nenhum código anterior lê ou escreve objetos alterados pela `0023`. Rollback em produção: volta a imagem, a tabela fica; o `rollback` do changeset (`DROP TABLE`) só vale com a tabela vazia, nos ambientes locais. |
| NFR2.4 | Nenhuma linha anterior à `0023` é preenchida; elas ficam `SLOT_MISSING` e são contadas (BR1.5, PQ-4). | 0 escritas em linhas antigas; o `slotStatus` é calculado na leitura. `ledger_real_slot_missing_total` conta as linhas `SLOT_MISSING` entregues pelo fluxo (NFR2.12). Teste de integração com linhas antigas: o resultado é igual ao do fallback de herança da BR1.3. |
| NFR2.5 | Valores em unidades base, nunca em ponto flutuante (`project.md` NEVER; `team.md`, Code Style). | Lamports e unidades base em `long` com `Math.addExact`, `subtractExact` e `multiplyExact`; frações do rateio e do custo médio em `BigInteger`; preços em `BigDecimal`. A regra ArchUnit de ponto flutuante (`ArchitectureTest`: sem `double` nem `float` em campos, parâmetros e retornos) cobre os pacotes novos da U2 e continua verde. 0 conversões para `double` no cálculo. |
| NFR2.6 | O P&L REAL fecha com o ledger por igualdade exata (BR8.1 a BR8.5). | Diferença de 0 lamport em `All` para o realizado, as taxas, o não realizado e o total, e em `Range` para o realizado e as taxas; conservação de custo e quantidade por carteira e token em `All`. Comparação de inteiros (`==` em `long`, `equals` em `BigInteger`), nunca com tolerância. |
| NFR2.7 | Teste de propriedade com sequências aleatórias e sementes fixas (BR9.2) [Q2]. | Pelo menos 1.000 sequências por execução, de 10 a 60 eventos cada, geradas a partir de 3 sementes fixas declaradas no teste (no padrão do `LedgerPropertyTest`). As sequências misturam compras de bots, vendas próprias com e sem excedente, liquidações, condicionais inteiras e parciais, ordens que falham, taxas no modo Assinada, operações no mesmo slot, linhas `SLOT_MISSING`, compras sem bot, vendas além do registrado e bots removidos. Para cada sequência: BR8.1 a BR8.5 dão `MATCH` em `All` e em 3 períodos aleatórios; a paridade de NFR2.8 vale; duas execuções dão o mesmo resultado; gravar as linhas com slot em outra ordem dá o mesmo resultado; nenhuma quantidade ou custo fica negativo; posição zerada tem custo 0. A semente e o índice da sequência aparecem na mensagem de falha. Tempo do teste na CI ≤ 60 s. |
| NFR2.8 | Teste de paridade com três oráculos independentes (BR9.1) [Q2]. | Os oráculos são escritos no próprio teste, sem `RealCostBasis` nem `RealProration`: (1) quantidade bruta dos `SWAP_FILL_IN` e `SWAP_FILL_OUT` na ordem de rede, igual ao caminho só de quantidade, à soma dos titulares e, sem `COMPENSATION` de token, ao `tokenBalances`; (2) conservação do custo: pernas SOL das compras mais `RENT` de compra = custo retirado de todas as partes + custo aberto; (3) recálculo do custo médio por titular com a regra da Q1 escrita de novo. Conferido depois de cada evento e no fim, para cada carteira e token, com AC5.2.1, AC5.2.2 e vendas próprias de bots com custos médios diferentes. Diferença tolerada: 0. |
| NFR2.9 | Os testes do núcleo financeiro da U2 são escritos antes do código (`team.md`, Ordering; BR9.3, BR9.4, BR9.5). | Antes do código: os exemplos de AC3.2.1 a AC3.9.1, AC5.1.1, AC5.3.1, AC5.4.1 e AC5.4.2 e os cenários do FR5.4, em unidades base; o teste de regressão da venda condicional (BR9.3), que falha contra o `RealPnlCalculator` da U1; o teste da fronteira do período (BR9.5); NFR2.7 e NFR2.8. O histórico de commits mostra cada teste antes ou no mesmo commit da implementação que o faz passar, nunca depois. |
| NFR2.10 | A ordem de rede é determinista (BR1.1 a BR1.3). | Teste de AC3.6.2: gravar as mesmas linhas em outra ordem dá a mesma chave e o mesmo P&L; o desempate final nunca é o `entryId`; duas leituras seguidas dão o mesmo resultado em 100% dos casos. |

## Verificação

| ID | Requisito | Valor |
|---|---|---|
| NFR7.1 | Volume de testes da U2: a Test Strategy `Standard` registrada no estado do intent [Q2]. | Testes de unidade e de integração de cada regra BR1.1 a BR10.5, com caminho feliz e pelo menos 2 casos de erro ou de borda por componente. |
| NFR7.2 | Piso de cobertura de 80% de linhas em cada parte. | Backend: JaCoCo, unitários e integração mesclados, ≥ 80%; `apps/web`: Jest, `coverageThreshold.global.lines = 80`; `apps/admin` e `apps/txsigner` não mudam e continuam ≥ 80%. A ferramenta falha o build abaixo do piso, e o piso nunca é reduzido. |
| NFR7.3 | Testes obrigatórios por risco que a U2 toca, sempre verdes (`team.md`). | (1) isolamento entre contas, inclusive na leitura de custo chamada em segundo plano (NFR3.1, NFR3.2); (2) separação REAL e PAPER, com os modos nunca somados (NFR4.1 a NFR4.3); (3) ledger sem edição, agora também em `real_entry_slot` (NFR3.4); (4) P&L com cenários determinísticos em unidades base (NFR2.6 a NFR2.9); (5) dado ausente vira `unknown`: preço desconhecido deixa só o não realizado desconhecido (BR7.3); (6) REAL desligado por padrão (NFR3.10); (7) saque disponível com a atribuição falhando (NFR3.11); (8) uma única transação por ordem REAL: o `recordSlot` repetido é idempotente (NFR1.21). |
| NFR7.4 | Ponto de controle interno da U2 (F9) [Q4]. | Os quatro itens verdes na CI, no mesmo commit: (1) a `0023` com os gatilhos e as permissões (NFR3.4, NFR3.5), `recordSlot` nos três gravadores (NFR1.21) e `streamInNetworkOrder` com a chave e a herança (NFR2.10); (2) a porta `RealCostMethod` com o caminho só de quantidade e o acumulador por titular, `positions` e `view` sem C13 nem C8 (NFR1.11) e a venda e a liquidação seguindo com a atribuição falhando (NFR3.11); (3) `RealCostBasis` com os testes escritos antes (NFR2.9); (4) a paridade de NFR2.8 verde, com os testes da barreira REAL e os do 261113 verdes. O rateio, a linha "Sem bot", a conferência, o período e a carga vêm depois desse ponto. |
| NFR7.5 | As mudanças de texto e de contrato entram no mesmo commit da troca da fórmula (BR5.6, BR10.4). | No mesmo commit: o rótulo "Taxas" e a saída da nota do bloco REAL no `apps/web`; os motivos `UNOWNED_EXCESS`, `UNOWNED_NO_COST` e `ORDER_RECORD_MISSING` em C10, no `openapi-guest.yaml`, no `ModePnlDtos` e no `schema.d.ts` regerado. Os jobs `frontend` e `contract` ficam verdes, e o teste de componente do `PnlModeBlock` confere o rótulo novo e a ausência da nota. |
| NFR7.6 | O spec de ponta a ponta da fatia do intent continua verde com o bloco REAL calculado pela U2. | `bash scripts/verify.sh` devolve 0 nos jobs `smoke` e `e2e`; o spec que cria um bot PAPER e um bot REAL e faz uma compra em cada modo confere que o bloco REAL mostra a linha do bot e que nenhum total soma os modos. |

## Sources

- `nfr-requirements-questions.md`: Q2, Q4 e o resumo confirmado [Q2] [Q4].
- `inception/requirements-analysis/requirements.md`: NFR2, NFR4, NFR7.
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR1.1 a BR1.5, BR2.7, BR5.6, BR7.3, BR8.1 a BR8.7, BR9.1 a BR9.5, BR10.4.
- `construction/u2-real-bot-pnl/functional-design/functional-spec.md`: F4, F6, F7, F8, F9; `entities.md`: ENT-213 (`failedChecks`).
- `inception/contract-design/contract-summary.md`: C10, C12, C13, C14, PQ-4.
- `inception/domain-design/decisions.md`: ADR-004.
- `construction/u1-mode-skeleton/nfr-requirements/reliability-requirements.md`: forma, NFR4.4, NFR7.1 a NFR7.3 da U1.
- `aidlc/spaces/default/memory/team.md`: Testing Posture (Ordering, Volume, Piso, testes obrigatórios por risco, Determinismo), Deployment (expand-contract, rollback só da imagem), Code Style (valores do REAL em inteiros); `project.md`: Forbidden (ponto flutuante), Mandated (ledger append-only).
- Código lido: `ledger/domain/LedgerPropertyTest.java` (sementes fixas), `architecture/ArchitectureTest.java` (regra de ponto flutuante), `pnl/application/RealBotPnlQueryV1.java`.

## Assumptions & Open Questions

1. [assumption] O tamanho das sequências (10 a 60 eventos), as 3 sementes e o limite de 60 s do teste de propriedade são escolhas deste estágio; a Q2 fixou só o mínimo de 1.000 sequências por execução e as sementes fixas. O NFR Design pode mudar o tamanho, nunca para menos de 1.000 sequências.
2. [assumption] A forma de provar o NFR2.3 (subir a versão anterior contra o banco migrado ou conferir que nenhum código anterior toca objetos alterados) fica com o NFR Design; como a `0023` só cria objetos novos, as duas provas são equivalentes.
3. [assumption] O NFR2.9 é conferido pela ordem dos commits na Code Generation, como nas Units do núcleo financeiro do 261113.
