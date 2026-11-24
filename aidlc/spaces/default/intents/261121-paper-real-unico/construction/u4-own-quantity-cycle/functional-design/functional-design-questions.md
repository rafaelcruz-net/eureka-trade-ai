# Perguntas do Functional Design — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele e ficam destacados no resumo.

## Fontes

- `inception/units-generation/unit-of-work.md` (U4) e o mapa de histórias (US6.1 e US6.2)
- `inception/requirements-analysis/requirements.md` (FR6.1 e FR6.2)
- `inception/domain-design/decisions.md` (ADR-006)
- `inception/contract-design/contract-summary.md`:
  - C15 `RealBotHoldings`, com falha fechada, prazo e sem lançar exceção
  - C16 `openSells`
  - C7 `BotPage.realOwnQuantitySell`
- `construction/u2-real-bot-pnl/`: o caminho só de quantidade, a leitura de custo por época, o `RealCost` desconhecido, a leitura de custo `CYCLE` e a marca de zeragem
- o código: `RealCycle`, `RealDecision`, `DecisionEngine`, `LiquidationJob` e as saídas condicionais

## Q1. Quanto o bot REAL pode vender (FR6.1)

Qual quantidade o bot pode vender?

A. A venda do bot fica limitada à quantidade que ele próprio tem: a posição dele pela atribuição (U2), menos as vendas dele ainda em aberto (C16, com AMBIGUOUS contando como aberta), e nunca abaixo de zero.
   - A decisão pode pedir menos que isso, e então vale o que ela pediu.
   - Se pedir mais, a venda é cortada para a quantidade do bot.
   - Se a quantidade do bot for zero, nada é vendido, e o motivo `NO_OWN_QUANTITY` fica registrado na decisão.
   - A leitura usa um instantâneo só, fora da transação do ciclo e com prazo de 250 ms (C15).
   - Se a leitura falhar ou passar do prazo, o bot não vende naquele ciclo (`OWN_QUANTITY_UNAVAILABLE`) e tenta de novo no próximo.

   (recomendado)
B. Vender a posição inteira da carteira, como hoje
X. Other (please specify)

[Answer]: A

## Q2. O que continua igual

O que não muda com a U4?

A. Só o ciclo do bot muda. Ficam como estão:
   - "vender tudo e sacar" da pessoa;
   - as saídas condicionais;
   - a liquidação;
   - o saque.

   Esses caminhos continuam vendendo a posição da carteira, sem nunca ler a atribuição, como a U2 decidiu. As compras do bot também não mudam. (recomendado)
B. Limitar também as condicionais por bot
X. Other (please specify)

[Answer]: A

## Q3. Flag e ordem de liberação (FR6, U4 por último)

Como a mudança é ligada e com que valor começa?

A. A mudança fica atrás da flag `memetrade.real.own-quantity-sell`.
   - Ela começa desligada em todo ambiente. Com a flag desligada, o comportamento é o de hoje.
   - A flag só é ligada depois que a Performance Validation do 261113 passar, numa implantação auditada separada (variável da definição de tarefa, versionada no Terraform), e só com a aprovação do responsável.
   - O campo `BotPage.realOwnQuantitySell` (C7) reflete o estado da flag.

   (recomendado)
B. Ligada por padrão
X. Other (please specify)

[Answer]: A

## Q4. Aviso na tela (FR6.2)

Que aviso aparece na tela de bots REAL?

A. Quando `realOwnQuantitySell` é verdadeiro, a tela de bots REAL mostra um aviso fixo: "Cada bot real vende só a quantidade que ele mesmo comprou. Posições herdadas ou de outros bots continuam na carteira e podem ser vendidas em Vender tudo e sacar." O aviso tem `role="status"`, não depende só da cor e não promete resultado. Quando a flag está desligada, o aviso não aparece. (recomendado)
B. Sem aviso
X. Other (please specify)

[Answer]: A

## Q5. Testes (decisão do time: testes antes do código na venda REAL)

Que testes vêm antes do código?

A. Os testes são escritos antes do código para:
   - o corte da quantidade;
   - zero;
   - vendas em aberto e AMBIGUOUS;
   - falha e prazo da leitura;
   - flag desligada igual a hoje;
   - concorrência (dois ciclos do mesmo bot nunca vendem mais que a quantidade dele, com a trava que já existe);
   - um bot que nunca vende a parte de outro bot da mesma carteira.

   A conferência final continua sendo a do P&L da U2: a soma dos bots mais a linha "Sem bot" bate com o ledger. (recomendado)
B. Testes depois
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

**Regras de dinheiro (pontos críticos):**
- **Quanto o bot vende:** a venda do bot fica limitada à quantidade dele (a posição atribuída menos as vendas dele em aberto, com AMBIGUOUS contando como aberta). Se for zero, nada é vendido e o motivo é registrado. A leitura tem um instantâneo só e prazo de 250 ms; se falhar, o bot não vende naquele ciclo [Q1].
- **O que não muda:** "vender tudo e sacar", as condicionais, a liquidação, o saque e as compras não mudam e nunca dependem da atribuição [Q2].
- **Flag:** `memetrade.real.own-quantity-sell` começa desligada. Ela só é ligada depois da Performance Validation do REAL, numa implantação separada aprovada por você [Q3].

**Demais pontos:**
- **Aviso:** um aviso fixo na tela de bots REAL quando a flag está ligada [Q4].
- **Testes:** escritos antes do código para o corte, o zero, as vendas em aberto, as falhas, a flag desligada e a concorrência, mais a conferência com o ledger [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
