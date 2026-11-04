# Perguntas do Functional Design — U7 Ledger e P&L

Unit `u7-ledger-pnl`. Histórias: US4.11, US4.6 (idempotência e unicidade no banco), US5.1, US5.2 e US4.12 (fechamento e fechamento pendente). Contratos: C5 (REST de operações, ledger, P&L e reconciliação), C13 (`PortfolioRiskView` para a U6), C15 (`PaperExecution` para a U5), C9 (trilha) e C11 (dados de mercado). Componentes: PaperTradingLedger e PortfolioPnL.

Já decidido nas fases anteriores (não vira pergunta):
- Ordem, preenchimento, lançamentos e posição são gravados juntos ou nada, dentro da transação do ciclo aberta pela U5; `PaperExecution` recusa ser chamada fora de uma transação (C15, ADR-005).
- Deduplicação por chave de idempotência por tenant e modo, com unicidade no banco; mesma chave e mesmo conteúdo devolvem o resultado original (`Duplicate`); mesma chave e conteúdo diferente é `Conflict` e nada é gravado (AC4.6.3, AC4.6.4).
- Ledger append-only: `UPDATE` e `DELETE` recusados pelo banco para o papel da aplicação; correção só por lançamento compensatório que referencia o original; o beta não oferece correção manual (FR10.1, AC5.2.2, AC5.2.3, Q15 das histórias).
- Custo médio ponderado; posição zerada reinicia o custo (FR10.2, AC5.1.2).
- Decimal exato; quantidade de token com as casas do mint; percentuais em pontos com 4 casas (tabela "Escalas" do Contract Design).
- O P&L só lê o ledger e as posições; nunca grava lançamentos. A U7 não lê tabelas da U5 nem da U6; bot, decisão e versão de limites entram só como identificadores.
- Nunca assina nem envia transações; tudo é `PAPER` (FR9.2, FR10.6).
- Preço confiável para fechar ou marcar: valor `KNOWN`, não desatualizado (até 5 minutos, FR4.5) e com liquidez maior que zero. Sem preço confiável, o fechamento fica pendente e conclui uma única vez quando o preço volta, com evento do ator `system` (AC4.12.3, AC4.12.4).
- Período filtrado no fuso da pessoa na tela e enviado à API em UTC (Q14 das histórias).

## Q1 — Moeda de referência do saldo virtual e escala (OQ-3)

A. USDC, com 6 casas decimais para saldo, preço, taxas, P&L e exposição.
B. SOL, com 9 casas decimais.
X. Other (please specify)

[Answer]: A

## Q2 — De onde vem o saldo virtual inicial de cada bot

A `PortfolioRiskView` precisa do saldo virtual disponível, mas a U7 não lê a configuração do bot (U5).

A. Um lançamento `FUNDING` no ledger, gravado por uma operação nova `PaperExecution.fund(botId, amount, idempotencyKey)` que a U5 chama uma única vez ao ativar o bot. O saldo disponível é sempre calculado a partir do ledger.
B. A U5 informa o saldo inicial em cada chamada de execução, e a U7 não guarda o saldo inicial.
X. Other (please specify)

[Answer]: A

## Q3 — Modelo de preenchimento da política de simulação `v1`

A. Impacto pelo modelo de produto constante sobre a liquidez do pool: impacto = valor da ordem ÷ (liquidez + valor da ordem); spread = metade do spread observado, ou 0,5000% quando a fonte não informa; slippage = spread + impacto; preço de preenchimento = preço de referência × (1 ± slippage). Taxa de 0,3000% sobre o valor preenchido.
B. Percentuais fixos da política: spread 0,5000%, impacto 1,0000% por 1% da liquidez consumida, taxa 0,3000%.
X. Other (please specify)

[Answer]: B

## Q4 — Política de taxas no custo e no P&L

AC5.1.1 pede realizado, não realizado e taxas separados.

A. Taxas ficam fora do custo médio. O realizado é bruto (venda menos custo médio), as taxas aparecem na própria coluna, e o resultado líquido é realizado + não realizado − taxas.
B. A taxa da compra entra no custo médio e a da venda sai do valor recebido, então o realizado já é líquido. A coluna de taxas só informa quanto foi pago.
X. Other (please specify)

[Answer]: A

## Q5 — Arredondamento

A. A quantidade é truncada para baixo nas casas do mint (nunca compra nem vende mais do que o permitido). Valores na moeda de referência são arredondados meio para cima (`HALF_UP`) na escala da Q1, uma vez por lançamento.
B. Como A, mas os valores monetários usam arredondamento bancário (`HALF_EVEN`).
X. Other (please specify)

[Answer]: B

## Q6 — Venda maior que a posição e liquidez insuficiente (AC4.11.4)

A. A venda é limitada à quantidade da posição e registra o limite aplicado. Uma ordem que a liquidez não cobre por inteiro é recusada com "liquidez insuficiente", sem preenchimento parcial.
B. A venda é limitada à posição, e a ordem sem liquidez suficiente é preenchida parcialmente até o que a liquidez permite.
C. As duas situações são recusadas, sem preenchimento.
X. Other (please specify)

[Answer]: B

## Q7 — Preço da marcação a mercado (não realizado e patrimônio)

A. Preço de referência vigente, sem custos de saída.
B. Preço estimado de saída: preço de referência menos o spread, o impacto e a taxa da política para vender a posição inteira.
X. Other (please specify)

[Answer]: B

## Q8 — P&L de um período

A. Realizado e taxas contam as operações com horário dentro do período [início, fim). O não realizado é sempre o das posições abertas agora, marcado no preço vigente, e a tela diz isso.
B. Como A, mas o não realizado é calculado no fim do período com o preço daquele instante; fora das últimas 24 horas de histórico, aparece `unknown`.
X. Other (please specify)

[Answer]: A

## Q9 — Reconciliação (FR10.5)

A. Uma tarefa a cada 5 minutos confere operações e ledger por pessoa e guarda o resultado. A tela mostra o último resultado com o horário, e um botão "verificar agora" roda a conferência na hora.
B. A conferência roda a cada abertura da tela, sem guardar resultado.
X. Other (please specify)

[Answer]: A

## Q10 — Maior patrimônio do bot (para o drawdown da U6)

O patrimônio é o saldo virtual mais as posições marcadas a mercado.

A. O maior patrimônio é atualizado depois de cada preenchimento e a cada marcação feita após a coleta de mercado. Se alguma posição não tem preço confiável, o patrimônio fica `unknown` e o maior patrimônio não muda.
B. O maior patrimônio é atualizado só depois de cada preenchimento.
X. Other (please specify)

[Answer]: A

## Q11 — Limites na rota de uso do dia (C5 `DayUsage`)

O contrato `DayUsage` traz `maxTotalExposure` e `maxDailyLoss`, que vêm da versão de limites do bot (U5), e a U7 não lê a U5.

A. A rota da U7 devolve só exposição, perda do dia e o dia UTC. A tela do bot junta esses valores com os limites que ela já recebe da U5. Corrijo o contrato C5.
B. A U5 passa a gravar uma cópia dos limites vigentes na U7 a cada versão, e a rota devolve tudo junto.
X. Other (please specify)

[Answer]: A

## Q12 — Quanto da liquidez uma ordem pode consumir (acompanha Q3 e Q6)

Com o preenchimento parcial (Q6, opção B) e o impacto de 1,0000% por 1% da liquidez consumida (Q3, opção B), falta dizer até onde a ordem é preenchida.

A. Até 10% da liquidez do pool por ordem (impacto máximo de 10,0000%); o restante não é preenchido e a operação registra a quantidade pedida e a preenchida.
B. Até 5% da liquidez do pool por ordem (impacto máximo de 5,0000%).
C. Até 2% da liquidez do pool por ordem (impacto máximo de 2,0000%).
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
