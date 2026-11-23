# Perguntas do Functional Design — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele: as regras de dinheiro aparecem destacadas no resumo.

## Fontes

- `inception/units-generation/unit-of-work.md` (U2) e `unit-of-work-story-map.md`
- `inception/user-stories/stories.md`: US3.x, US5.x e os pontos abertos 2 e 4
- `inception/requirements-analysis/requirements.md`: FR3.3 a FR3.11, FR5.1 a FR5.4 e NFR1
- `inception/domain-design/decisions.md`: ADR-002, ADR-004 e ADR-011
- `inception/contract-design/contract-summary.md`: C10 a C14 e PQ-2, PQ-4 e PQ-10
- `construction/u1-mode-skeleton/`: C10 na primeira versão e a nota sobre o `realizedLamports`
- O código atual:
  - `RealPnlCalculator.ceilShare` arredonda para cima o custo retirado numa venda parcial;
  - `ledger/domain/RealPositions` arredonda o mesmo custo para baixo.

## Q1. Arredondamento do custo retirado numa venda parcial (custo médio único)

Numa venda parcial, o custo retirado da posição é `custo × vendido / quantidade`, e a divisão quase nunca é exata. Hoje o P&L arredonda para cima e a posição do ledger arredonda para baixo. Esse custo precisa ser um só (ADR-002).

A. **Para cima.** O custo retirado é arredondado para cima, e o resto do custo fica com o lote que continua aberto.
   - O realizado nunca aparece maior do que é: o arredondamento conta contra o usuário, nunca a favor.
   - Os valores que o P&L REAL já mostra não mudam, porque ele já arredonda assim.
   - A posição da carteira e a barreira de risco passam a usar o mesmo cálculo, pela porta `RealCostMethod`.
   - Quando a posição zera, o custo restante sai inteiro na última venda, então nunca sobra lamport.

   (recomendado)
B. Para baixo, como a posição do ledger faz hoje.
X. Other (please specify)

[Answer]: A

## Q2. Rateio da quantidade de token entre os bots donos

Como repartir a quantidade de token vendida sem bot entre os bots donos?

A. Pelo **maior resto**, como os lamports (FR3.7):
   - Cada bot recebe a parte inteira, em unidades base, de `vendido × quantidade do bot / quantidade total`.
   - As unidades que sobram vão uma a uma para os maiores restos.
   - Empate: a data de criação do bot decide, depois o id.

   A soma das partes é sempre igual ao vendido. O valor recebido e a taxa são rateados pela mesma regra, e o custo de cada parte segue a Q1. (recomendado)
B. Arredondar tudo para baixo e mandar a sobra para "Sem bot".
X. Other (please specify)

[Answer]: A

## Q3. Custo da parte "excedente sem dono" (AC3.5.2, ponto aberto 4)

Qual custo vai para a parte vendida que não tem bot dono?

A. O custo da parte sem dono é o custo dessa quantidade na carteira pelo ledger REAL (FR5.2), menos o custo que os bots donos já tinham, calculado com a regra da Q1. O resultado da parte sem dono vai para a linha "Sem bot", com o motivo `UNOWNED_EXCESS`. Se o ledger não tiver custo para essa quantidade (posição herdada sem compra registrada), o custo é zero e o motivo é `UNOWNED_NO_COST`. (recomendado)
B. Custo sempre zero.
X. Other (please specify)

[Answer]: A

## Q4. Ordem pela rede com a tabela à parte (ADR-011, PQ-2 e PQ-4)

Como as operações REAL são ordenadas?

A. A ordem vem do slot gravado em `real_entry_slot` (tabela só de inserção, decidida no Contract Design), e depois da assinatura.
   - Os lançamentos novos gravam o slot na confirmação, por `recordSlot`.
   - As linhas antigas, sem slot, ficam `SLOT_MISSING` e são ordenadas pela gravação, com o motivo registrado.
   - O ADR-011 é alinhado a esse texto.
   - A posição da carteira, o custo, os bots e o total REAL usam a mesma chave de ordem, e um teste de paridade confere isso.

   (recomendado)
B. Continuar ordenando só pela gravação.
X. Other (please specify)

[Answer]: A

## Q5. Sentido do realizado e o total REAL

Como o realizado e o total REAL passam a ser calculados?

A. A partir da U2:
   - o realizado é **bruto**: o recebido menos o custo retirado, sem descontar taxa;
   - as taxas ficam numa linha própria, com as regras do FR3.9;
   - o total do modo é `realizado + não realizado − taxas`, como no C4 e no AC4.1.1, igual ao PAPER.

   A nota "Algumas operações ainda não têm bot dono" e o rótulo "Taxas (já incluídas)" da U1 saem da tela. A conferência com o ledger (FR5.3) usa essa fórmula. (recomendado)
B. Manter o realizado líquido da U1.
X. Other (please specify)

[Answer]: A

## Q6. Conferência exata com o ledger e falhas

Como a U2 garante que o P&L bate com o ledger, e o que acontece quando não bate?

A. Para cada pessoa e período:
   - a soma dos bots mais a linha "Sem bot" tem de ser igual ao P&L do modo calculado a partir do ledger, nas unidades base;
   - o não realizado só é conferido em "desde o início".

   Uma diferença é um defeito:
   - o bloco REAL vira `UNAVAILABLE` com `cause=RECONCILIATION_MISMATCH`, sem mostrar números parciais;
   - o evento é auditado e entra numa métrica;
   - um teste de propriedade com sequências aleatórias e semente fixa confere a igualdade.

   (recomendado)
B. Mostrar os números mesmo com diferença.
X. Other (please specify)

[Answer]: A

## Q7. Desempenho (NFR1, PQ-10)

Como a U2 cumpre o tempo de resposta?

A. Uma leitura em fluxo por pessoa:
   - os lançamentos e as ordens assentadas são lidos já na ordem de rede, com junção em `real_entry_slot`, num índice que inclui o slot;
   - o cálculo de rateio, de custo e de P&L por bot é feito numa passada só.

   O portão de p95 de 1 s para C4 entra no cenário de carga da CI, com uma pessoa, 10 bots e 2 anos de histórico. O texto pronto do `load-test.yml` é entregue ao responsável. (recomendado)
B. Guardar o rateio em tabela.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

**Regras de dinheiro (pontos críticos):**
- **Custo retirado numa venda parcial:** arredondado para cima, contra o usuário. O resto fica no lote aberto, e a última venda leva o custo restante inteiro. A posição da carteira e a barreira passam a usar esse mesmo cálculo [Q1].
- **Rateio da quantidade de token:** pelo maior resto, como os lamports. A soma é sempre igual ao vendido [Q2].
- **Parte sem dono:** recebe o custo da carteira menos o custo dos bots donos e vai para "Sem bot" com o motivo `UNOWNED_EXCESS`. Sem custo no ledger, o custo é zero e o motivo é `UNOWNED_NO_COST` [Q3].
- **Realizado e total REAL:** o realizado passa a ser bruto e as taxas ficam numa linha própria. O total REAL é `realizado + não realizado − taxas`, igual ao PAPER [Q5].
- **Diferença com o ledger:** é um defeito. O bloco REAL fica indisponível, o evento é auditado e um teste de propriedade confere a igualdade [Q6].

**Demais pontos:**
- **Ordem pela rede:** slot da tabela à parte, depois a assinatura. As linhas antigas ficam `SLOT_MISSING`. A mesma chave vale para tudo, e o ADR-011 é alinhado [Q4].
- **Desempenho:** uma leitura em fluxo por pessoa, com índice que inclui o slot. O portão de 1 s fica no teste de carga da CI, com o texto pronto do `load-test.yml` para o responsável [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
