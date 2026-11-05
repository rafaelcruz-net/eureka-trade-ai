# Perguntas do Functional Design — U6 Barreira de limites

Unit `u6-risk-control`.
- **Histórias:** US4.6 (a barreira e as recusas) e US4.7 (a verificação da perda diária e do drawdown).
- **Contratos:** C14 (`OrderGate` para a U5), C21 (REST das ordens recusadas), C13 (`PortfolioRiskView`, lida da U7), C11 (dados de mercado da U4) e C9 (trilha).
- **Componente:** RiskControl.

Já decidido nas fases anteriores (não vira pergunta):
- **Barreira obrigatória.** Nenhuma ordem simulada chega à execução sem passar pela barreira (ADR-003, FR8.3). A U5 chama `OrderGate.evaluate` dentro da transação do ciclo, com a linha do bot travada, antes de chamar `PaperExecution` (C15, ADR-005).
- **Fronteiras inclusivas.** O valor igual ao limite é permitido e o menor incremento além dele é recusado. A exceção é a perda diária: atingir o limite já bloqueia (convenção 1 das histórias, AC4.7.1).
  - Menor incremento: 0,000001 USDC para valores, 0,0001 ponto percentual para percentuais e 1 segundo para tempo.
- **Frescor de até 60 segundos.** A idade é o instante da avaliação menos o horário da observação (Q10 das histórias). Um preço `UNKNOWN` é recusado por frescor. Uma liquidez `UNKNOWN` é recusada por liquidez mínima (tabela de US4.6). Nunca vira zero.
- **Intervalo.** A próxima ordem é permitida a partir de T + I, em que T é o horário da última ordem preenchida do bot (`lastTradeAt` da C13). Sem ordem anterior, não há restrição de intervalo.
- **Perda diária.** É a perda realizada do dia UTC com taxas, lida da U7 (`realizedDayLossWithFees`). Igual ao limite P já recusa e indica pausa por "perda diária"; P menos o menor incremento ainda permite (AC4.7.1).
- **Drawdown.** É medido sobre o patrimônio virtual (saldo mais posições marcadas) a partir do maior patrimônio do bot, lido da U7 (`equity` e `peakEquity`). Fica em pontos percentuais com 4 casas, arredondado meio para o par.
- **Limites vêm da U5.** A U6 não lê as tabelas da U5: os valores dos limites e o número da versão chegam na própria chamada (regra de propriedade "o consumidor entrega na chamada", Q16 do Contract Design).
- **Dados de mercado e da carteira.** A U6 lê da U4 o preço de referência e a liquidez, e da U7 a exposição, a perda do dia, o patrimônio, o maior patrimônio, o saldo disponível e a última operação. Bot de outra pessoa é recusado pela U7 (`TenantMismatchException`, auditado).
- **Cada recusa é gravada** com os motivos, a versão dos limites, o tenant e a pessoa dona. Ela gera um único evento de auditoria `ORDER_REJECTED` pela tabela de saída, na mesma transação do ciclo (C9, AC4.6.1).
  - A leitura C21 filtra pelo tenant e pela pessoa do token, sem consultar a U5. Bot de outra pessoa responde 404.
- **Quem pausa é a U5.** A U6 só indica o gatilho (`PauseTrigger`), não grava ordens, não pausa bots e nunca assina nem envia transações. Tudo é `PAPER`.
- **Decimal exato e relógio.** Valores em USDC com 6 casas e percentuais com 4 casas (tabela "Escalas"); nunca ponto flutuante. Relógio injetável.
- **Sem telas.** A U6 não tem telas próprias. A tela do bot (U5) mostra as recusas pela C21.

## Q1. [Produto] Quando mais de um limite falha na mesma ordem

As histórias pedem que o design defina qual motivo aparece quando mais de um limite falha (nota de US4.6). O contrato C21 já guarda uma lista de motivos.

A. Todas as verificações rodam sempre. Todos os motivos que falharam são gravados, na ordem fixa da tabela de US4.6 seguida de saldo, perda diária e drawdown, e o primeiro da lista é o exibido como principal.
B. A avaliação para na primeira verificação que falha, nessa mesma ordem fixa, e grava só esse motivo.
X. Other (please specify)

[Answer]: A

## Q2. [Produto] Quais verificações valem para uma ordem de venda (SELL)

Uma venda reduz a exposição e devolve saldo. Algumas verificações pensadas para a compra podem não fazer sentido nela.

A. Na venda não se aplicam tamanho por ordem, exposição por token, exposição total e saldo PAPER. Frescor, liquidez mínima, slippage, intervalo, duplicidade, perda diária e drawdown continuam valendo.
B. Todas as verificações valem igualmente para compra e venda.
C. A venda passa só por frescor, liquidez mínima e duplicidade, para que a saída de uma posição nunca seja barrada por perda diária, drawdown ou intervalo.
X. Other (please specify)

[Answer]: A

## Q3. [Produto] Valor da ordem usado no tamanho por ordem e na exposição

A ordem candidata chega em quantidade de token. Os limites de tamanho e de exposição são em USDC.

A. Valor = quantidade × preço de referência atual, antes do slippage.
B. Valor = quantidade × preço estimado de preenchimento, já com o slippage estimado (na compra, um valor maior que o de A).
X. Other (please specify)

[Answer]: A

## Q4. [Produto] Comparação do saldo PAPER disponível (AC4.6.2)

A U7 recusa a execução com `INSUFFICIENT_BALANCE` quando o saldo não cobre o custo. A barreira deve barrar antes, pelo motivo "saldo PAPER insuficiente".

A. A compra é recusada quando o custo estimado total (valor com slippage mais a taxa de 0,3000% da política de simulação) passa do saldo disponível.
B. A compra é recusada quando o valor ao preço de referência, sem slippage e sem taxa, passa do saldo disponível.
X. Other (please specify)

[Answer]: A

## Q5. [Desenvolvimento] Como a barreira estima o slippage antes da ordem

A política de simulação `v1` da U7 calcula spread e impacto pela liquidez consumida, mas isso fica dentro da U7. A U6 não lê o código interno de outro módulo.

A. A U7 ganha uma operação nova, aditiva, na sua interface pública (C13): `estimateFill(token, side, quantity)`. Ela devolve o preço estimado, o slippage estimado e a taxa pela mesma política vigente, e a barreira usa o mesmo cálculo que a execução usará. Como a U7 já foi entregue, essa mudança no código dela pede uma nova revisão da U7.
B. A U6 calcula o slippage com uma cópia dos parâmetros da política `v1` (spread 0,5000%, impacto 1,0000% por 1% da liquidez), e um teste compara as duas contas.
X. Other (please specify)

[Answer]: B

## Q6. [Produto] Drawdown quando o patrimônio é desconhecido

Sem preço confiável de alguma posição aberta, a U7 devolve o patrimônio vazio (nunca zero). Assim, a barreira não consegue saber se a ordem fica dentro do drawdown máximo.

A. A ordem é recusada pelo motivo "drawdown", sem indicar pausa: não dá para garantir que fica dentro do limite. O bot segue ativo e tenta de novo no próximo ciclo.
B. A ordem é recusada pelo motivo "frescor", sem indicar pausa.
C. A verificação de drawdown é pulada e as demais decidem.
X. Other (please specify)

[Answer]: A

## Q7. [Produto] O que significa "uma operação levaria o drawdown acima de D" (AC4.7.2)

A. A barreira estima o patrimônio depois da ordem: patrimônio atual menos os custos estimados da ordem (taxa e slippage). Recusa quando o drawdown estimado depois da ordem passa de D. Quando o drawdown atual, antes da ordem, já é igual ou maior que D, recusa e indica pausa por "drawdown".
B. A barreira olha só o drawdown atual: igual ou maior que D recusa e indica pausa; abaixo de D, a ordem passa.
X. Other (please specify)

[Answer]: A

## Q8. [Desenvolvimento] Perda diária e drawdown atingidos na mesma avaliação

O contrato C14 devolve no máximo um gatilho de pausa (`Optional<PauseTrigger>`). A U5, que consome esse contrato, ainda não foi construída.

A. O contrato passa a devolver um conjunto de gatilhos (`Set<PauseTrigger>`, vazio quando nenhum), e a U5 aplica os dois motivos de pausa. A mudança é registrada neste design e avisada à U5.
B. O contrato fica como está, e a perda diária tem prioridade quando os dois são atingidos.
X. Other (please specify)

[Answer]: A

## Q9. [Produto] A mesma decisão avaliada duas vezes (linha "Duplicidade" de US4.6)

Uma decisão pode ser avaliada de novo depois de uma falha no meio do ciclo ou de uma repetição. Para a execução, a deduplicação por chave de idempotência já é da U7 (AC4.6.3).

A. A barreira guarda uma avaliação por decisão. A segunda avaliação da mesma decisão é recusada pelo motivo "duplicada", com a recusa gravada e o seu evento, e não chega à execução.
B. A barreira devolve o mesmo resultado da primeira avaliação, sem nova gravação nem novo evento. A segunda execução é contida pela U7, que devolve o resultado original.
X. Other (please specify)

[Answer]: A

## Q10. [Desenvolvimento] O que a recusa guarda além dos motivos

O contrato C21 mostra só os motivos, a versão dos limites e o horário. Para explicar uma recusa depois (auditoria, suporte), pode ser útil guardar os números medidos.

A. Cada motivo guarda o valor medido e o limite que barrou (por exemplo, "exposição por token: 200,000001 > 200,000000"), além dos dados de mercado usados com fonte e horário. A rota C21 ganha esses campos de forma aditiva.
B. A recusa guarda só os motivos, a versão dos limites e o horário, como o contrato C21 já descreve.
X. Other (please specify)

[Answer]: A

## Q11. [Produto] Valores da tabela de exemplos para os testes escritos antes do código

As histórias pedem que os números de cada linha da tabela de US4.6 e de US4.7 venham deste design. Valores propostos para o bot de exemplo:
- saldo virtual 1.000,000000 USDC;
- tamanho máximo por ordem 100,000000 USDC;
- exposição máxima por token 200,000000 USDC e exposição total 500,000000 USDC;
- slippage máximo 2,0000%;
- liquidez mínima 10.000,000000 USDC;
- frescor de 60 s;
- intervalo de 300 s entre operações;
- perda diária máxima 50,000000 USDC;
- drawdown máximo 10,0000%, sobre um maior patrimônio de 1.000,000000 USDC.

A. Usar esses valores. Cada linha tem o caso permitido no limite e o caso recusado com o menor incremento além dele.
B. Usar esses valores, mas com o saldo virtual de 10.000,000000 USDC e os demais valores multiplicados por 10.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
