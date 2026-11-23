# Perguntas do NFR Design — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

## Fontes

- `construction/u2-real-bot-pnl/nfr-requirements/` (NFR1.1 a NFR8.14, D-NFR1 a D-NFR3, revisão 02 com 3 achados Minor)
- `construction/u2-real-bot-pnl/functional-design/` (BR*, F1 a F9, D1 a D3)
- `inception/contract-design/contract-summary.md` (C10 a C14)
- `construction/u1-mode-skeleton/nfr-design/` (pool `pnl-mode-block`, prazo do bloco)

## Q1. Leitura em fluxo e índice (PQ-10)

Como o P&L REAL é lido do banco?

A. A leitura principal é feita por pessoa, em fluxo, na ordem da rede:
   - `real_ledger_entry` com junção à esquerda em `real_entry_slot` pela assinatura;
   - índice da migração `0023` em `real_entry_slot (tx_signature)`, que é a chave primária;
   - índice `real_ledger_entry (wallet_id, created_at, ...)` já existente, ou um novo se o EXPLAIN mostrar que precisa.
   - o cursor do JDBC usa `fetchSize` 500;
   - o EXPLAIN da consulta principal é conferido num teste de integração.

   O teto `real-max-orders` da U1 é retirado: a leitura em fluxo não precisa dele. (recomendado)
B. Manter o teto de 50.000 ordens.
X. Other (please specify)

[Answer]: A

## Q2. Prazo e cancelamento da atribuição (D-NFR1)

Como a leitura de atribuição respeita o prazo?

A. A leitura de atribuição roda com `statement_timeout` local igual ao orçamento:
   - 250 ms no caminho da barreira e do ciclo;
   - 500 ms no caminho do P&L.

   No caminho da barreira, ela roda num ponto de salvamento (`NESTED`) dentro da transação que tem a trava da carteira. Se o prazo esgotar, o ponto de salvamento é desfeito, a quantidade segue sem custo e a compra é recusada. A venda, a liquidação e o saque seguem normalmente.

   O portão efetivo é a taxa de `TIMEOUT` de no máximo 0,5%. O alvo de p95 fica só como meta. (recomendado)
B. Prazo só na aplicação, sem `statement_timeout`.
X. Other (please specify)

[Answer]: A

## Q3. Limite da auditoria de divergência com várias instâncias (NFR3.15)

Como garantir no máximo 1 evento de auditoria por pessoa, por divergência e por hora, com várias instâncias da API?

A. Antes de gravar, consultar a própria auditoria: procurar um evento `RECONCILIATION_MISMATCH` da mesma pessoa, com a mesma impressão digital, na última hora. A consulta usa o índice que já existe na auditoria (pessoa e ação), e a gravação usa uma trava consultiva do PostgreSQL por pessoa e impressão digital. Assim não há estado em memória nem tabela nova. (recomendado)
B. Cache em memória por instância.
X. Other (please specify)

[Answer]: A

## Q4. Ponto de controle interno e ordem de construção

Em que ordem a U2 é construída?

A. A Code Generation da U2 tem duas fases, com o ponto de controle interno entre elas:
   1. Migração `0023` (`real_entry_slot`), `recordSlot`, chave de ordem da rede, `RealCostMethod` e `RealCostBasis`, caminho só de quantidade e teste de paridade com três oráculos. A fase 1 só termina com tudo verde.
   2. Rateio, linha "Sem bot", taxas, conferência com o ledger, período, atribuição, troca do `RealBotPnlQueryV1`, textos da tela (BR10.4), alarme e texto pronto do `load-test.yml`.

   Se a fase 1 não ficar verde na primeira passada, a U2 é dividida, como o fundador já decidiu. (recomendado)
B. Uma passada só.
X. Other (please specify)

[Answer]: A

## Q5. Alinhamentos pendentes

Como os textos pendentes de etapas anteriores são alinhados?

A. A Code Generation da U2 alinha, num commit junto com o código:
   - BR2.7 (500 ms);
   - ENT-215 (assinatura abreviada com HMAC);
   - os nomes das métricas no Functional Design e em C13;
   - a nota de Q1 que aponta para D-NFR1 e D-NFR3;
   - "as cinco" métricas.

   As pendências de texto da U1 (`performance-design.md`, NFR8.1 e BR1.9) também entram, porque tocam o mesmo código. (recomendado)
B. Deixar para o Build and Test.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Leitura:** em fluxo, por pessoa e na ordem da rede, com junção na tabela de slots. Os índices são conferidos por EXPLAIN num teste. O teto de 50.000 ordens da U1 sai [Q1].
- **Prazo da atribuição:** `statement_timeout` de 250 ms (barreira e ciclo) e de 500 ms (P&L). Na barreira, a leitura roda num ponto de salvamento: se o prazo esgotar, só a compra é recusada. O portão efetivo é a taxa de `TIMEOUT` de no máximo 0,5% [Q2].
- **Limite da auditoria:** a própria auditoria é consultada antes de gravar, com uma trava por pessoa. Nada fica em memória nem em tabela nova [Q3].
- **Construção em duas fases:** primeiro o slot, o custo único, o caminho só de quantidade e a paridade, que formam o ponto de controle interno. Depois o rateio, a conferência e o restante. A U2 é dividida se a fase 1 não ficar verde [Q4].
- **Alinhamentos:** os textos pendentes da U1 e da U2 são alinhados na Code Generation [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
