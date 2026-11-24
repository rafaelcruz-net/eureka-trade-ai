# Perguntas do NFR Design — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/u4-own-quantity-cycle/nfr-requirements/` (56 requisitos, F1 e F2);
- `construction/u4-own-quantity-cycle/functional-design/` (D1 a D5);
- `construction/u2-real-bot-pnl/nfr-design/` (leitura de custo `CYCLE` por época, `ReadDeadline`, ponto de salvamento);
- código: `CycleRunner`, `SlotDispatcher`, `RealCycle`, `DecisionConsumer`, fila `real_decision_request`.

## Q1. Leitura da quantidade própria

Como o ciclo lê a quantidade própria do bot?

A. A leitura usa o caminho da U2:
   - uma transação só de leitura, com um instantâneo (REPEATABLE READ), fora da transação do ciclo;
   - prazo de 250 ms, com `ReadDeadline` conferido por linha e por busca;
   - a posição atribuída do bot por época (marca de zeragem), menos as vendas em aberto do bot: ordens em andamento, AMBIGUOUS e decisões SELL PENDING (C16 estendido).

   Se o índice da fila por bot e PENDING não existir, entra numa migração só de expansão, conferida por `EXPLAIN` num teste. (recomendado)
B. Ler dentro da transação do ciclo
X. Other (please specify)

[Answer]: A

## Q2. Concorrência e marca do bot

Como dois ciclos do mesmo bot são impedidos de vender além da quantidade dele?

A. O ciclo lê a marca do bot (`change_seq`) antes do instantâneo e de novo depois de travar o bot.
   - Se a marca mudou, o ciclo não vende e registra `STALE_SNAPSHOT`.
   - As conferências prévias (estado, slot, mercado vindo do `SlotDispatcher`) rodam antes da leitura, só para bot REAL que vai rodar.
   - O corte da venda é aplicado à decisão antes de gravá-la na fila.

   (recomendado)
B. Trava por pessoa no ciclo
X. Other (please specify)

[Answer]: A

## Q3. Flag e virada

Como a flag é implementada e ligada?

A. A flag fica em `@ConfigurationProperties` (`memetrade.real.own-quantity-sell`, padrão `false`), lida na subida, com uma variável para a API e outra para o worker, separadas no Terraform.
   - O C7 (`realOwnQuantitySell`) devolve o valor da API.
   - As consultas de virada (SELL PENDING e ordens SELL em andamento) ficam no runbook como SQL só de leitura.
   - Um teste de contexto confere que o padrão é desligado nos dois processos.

   (recomendado)
B. Flag no banco
X. Other (please specify)

[Answer]: A

## Q4. Observabilidade e alarme (F2)

O que entra em métricas e alarmes?

A. Entram:
   - três contadores no worker, com 7 séries registradas com zero na subida e prefixo `real_`;
   - a regra `RealOpsOwnHoldingsUnavailable`: razão de 5%, janela de 10 min, `for 15m`;
   - o gêmeo no CloudWatch: expressão, 600 s, 2 de 2;
   - o `hasSize(15)`;
   - o T20 com promtool por Testcontainers, usando o digest do `ci.yml`;
   - a seção nova no runbook;
   - a revisão semanal da razão de `STALE_SNAPSHOT`.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Leitura:** um instantâneo só de leitura, fora do ciclo, com prazo conferido por linha. Lê a posição do bot por época menos as vendas em aberto (ordens, AMBIGUOUS e decisões na fila). O índice da fila é conferido por EXPLAIN [Q1].
- **Concorrência:** a marca do bot é lida antes e depois da trava; se mudou, o ciclo não vende (`STALE_SNAPSHOT`). As conferências prévias vêm antes da leitura. O corte é aplicado antes de gravar a decisão [Q2].
- **Flag:** padrão desligado, com uma variável para a API e outra para o worker. As consultas da virada ficam no runbook. Um teste confere o padrão desligado [Q3].
- **Observabilidade:** contadores e alarme por razão com o gêmeo no CloudWatch, o teste da regra por contêiner, o runbook e a revisão semanal [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
