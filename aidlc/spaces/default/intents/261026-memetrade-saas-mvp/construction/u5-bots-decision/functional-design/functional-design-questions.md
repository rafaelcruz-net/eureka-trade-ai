# Perguntas do Functional Design — U5 Bots e decisão

Unit `u5-bots-decision`.

- **Histórias:** US4.1, US4.2, US4.3, US4.4, US4.5, US4.7 (motivos de pausa e meia-noite UTC), US4.8, US4.9, US4.10, US4.12 (confirmação e estados) e US7.2 (pausa de sistema).
- **Contratos:** C4 (REST dos bots), C7 (canal ao vivo do painel), C14 (barreira da U6, já entregue), C15 (execução PAPER da U7, já entregue), C16 (pausa de sistema e contagens), C18 (marcas de alteração) e C20 (processos API e worker).
- **Componentes:** BotManagement e DecisionEngine.

Já decidido nas fases anteriores (não vira pergunta):
- **Estados e transições.** Os estados são DRAFT, VALIDATED, ACTIVE, PAUSED, CLOSING_PENDING e STOPPED. A tabela de transições é a do anexo das histórias, e qualquer outra transição é recusada (AC4.4.3). Os motivos de pausa se acumulam (pessoa, perda diária, drawdown, sistema), e o bot só volta a ACTIVE sem nenhum motivo.
- **Limite de ativos.** No máximo 3 bots ACTIVE por pessoa, inclusive com ativações simultâneas. Bots pausados não contam. Retomar conta como ativação (AC4.3.2, Q11 das histórias).
- **Meia-noite UTC.** Ela remove só o motivo "perda diária". Se a pessoa já tiver 3 ativos, o bot fica pausado com aviso (AC4.7.3, AC4.7.4). Retomar depois da perda diária exige um limite maior que a perda acumulada (AC4.7.5).
- **Ciclo.** Ele roda no processo worker:
  - a transação é `READ COMMITTED` e trava a linha do bot;
  - confere que o bot está ACTIVE e sem motivo de pausa;
  - depois chama a decisão, a barreira da U6 e a execução da U7;
  - os gatilhos de pausa que a U6 devolver são aplicados na mesma transação;
  - no máximo uma decisão por ciclo;
  - depois de um reinício não repete o ciclo interrompido nem executa intervalos perdidos (ADR-005, C14, C15, AC4.5.4).
- **Decisão.** Ela é determinística e explicada, sem modelo de IA:
  - traz token, ação (BUY, SELL, HOLD, NO_TRADE), versão da estratégia, fatores, entradas com horário, validade e uma frase simples;
  - é NO_TRADE com dado `unknown`, desatualizado, histórico insuficiente ou avaliação indisponível;
  - SELL sem posição vira NO_TRADE (FR8.1, FR8.2, FR8.6, AC4.5.3).
- **Dados de mercado.** São lidos pela C11. A janela de preço e volume vem em baldes de 5 minutos UTC, e o volume de cada balde é o volume de 24 horas naquele instante.
- **Pausa.** Ela bloqueia qualquer nova operação, inclusive saídas por alvo, stop ou tempo (AC4.8.1). Pausar não pede confirmação. Pausa e kill switch gravados pela API esperam a trava do bot.
- **Encerrar.** Encerrar pede confirmação e chama `closePositions` da U7. Sem preço confiável, o bot fica CLOSING_PENDING, e a U7 conclui o fechamento uma única vez (US4.12).
- **Kill switch.** Ele aplica o motivo "sistema" a todos os bots ativos. Desligar remove esse motivo sem retomar ninguém. Iniciar e retomar são recusados com "pausado pelo sistema" (ADR-007, US7.2).
- **Limites.** São versionados. Editar vale só para operações futuras e é permitido com o bot ACTIVE ou PAUSED (US4.9, AC4.7.5). O saldo virtual vira um `FUNDING` na U7, uma única vez, na ativação (C15).
- **Idempotência e auditoria.** Todo comando usa a chave de idempotência pelo mecanismo comum da U1 e gera um único evento de auditoria.
- **Isolamento e modo.** Os dados de bot pertencem só à pessoa dona. Tudo é PAPER, e REAL é recusado (AC4.2.5, AC4.3.4, AC4.3.5).
- **Canal ao vivo (C7).** Ele é hospedado pela U5 no processo de API e lê as marcas da C18 a cada 2 s.

## Q1. [Produto] Regras da estratégia "momentum de volume"

A estratégia compra quando o volume acelera e o preço sobe junto. Os dados chegam em baldes de 5 minutos, com o volume de 24 horas em cada balde.

A. Proposta de compra, avaliada no último balde fechado:
   - o crescimento do volume de 24 h na janela é pelo menos o mínimo;
   - a alta do preço na janela é pelo menos o mínimo;
   - preço e volume são conhecidos em todos os baldes da janela.

   Parâmetros (padrão, faixa, passo):
   - janela: 15 min (de 10 a 60, passo de 5);
   - crescimento mínimo do volume: 20,0000% (de 5 a 200, passo de 1);
   - alta mínima do preço: 2,0000% (de 0,5 a 20, passo de 0,5).
B. A mesma regra, mas sem a condição de preço: só o crescimento do volume decide a compra.
X. Other (please specify)

[Answer]: A

## Q2. [Produto] Regras da estratégia "rompimento de faixa"

A estratégia compra quando o preço sai acima da faixa recente.

A. Proposta de compra:
   - a faixa é a máxima e a mínima dos preços na janela de observação, sem contar o último balde;
   - compra quando o último preço passa da máxima mais a margem de rompimento;
   - todos os baldes da janela precisam ser conhecidos.

   Parâmetros (padrão, faixa, passo):
   - janela: 120 min (de 30 a 720, passo de 30);
   - margem de rompimento: 1,0000% (de 0,1 a 10, passo de 0,1).
B. A mesma regra, mas exigindo também que o volume de 24 h tenha crescido na janela.
X. Other (please specify)

[Answer]: A

## Q3. [Produto] Quando o bot vende (saída da posição)

As duas estratégias precisam de uma regra de saída. A venda é sempre da posição inteira daquele token.

A. Saídas comuns às duas estratégias, como parâmetros do bot:
   - alvo de lucro: 10,0000% (de 1 a 100);
   - stop: 5,0000% (de 1 a 50), sobre o preço médio da posição;
   - tempo máximo da posição: 240 min (de 15 a 1440).

   A primeira que acontecer gera SELL. No rompimento de faixa, voltar abaixo da mínima da faixa também gera SELL.
B. Só alvo e stop, sem tempo máximo.
X. Other (please specify)

[Answer]: A

## Q4. [Produto] Quanto o bot compra

A. Cada compra usa o tamanho máximo por ordem do bot (em USDC), limitado ao saldo disponível. A quantidade de token é esse valor dividido pelo preço de referência, truncada nas casas do mint.
B. Cada compra usa uma fração do saldo disponível, parâmetro do bot com padrão de 10% (de 1 a 100), limitada ao tamanho máximo por ordem.
X. Other (please specify)

[Answer]: B

## Q5. [Produto] Vários tokens no mesmo bot

O ciclo produz no máximo uma decisão. Com vários tokens selecionados, é preciso escolher qual deles vira a decisão.

A. Prioridade fixa:
   1. primeiro, a saída de uma posição aberta (a mais antiga, se houver mais de uma);
   2. depois, a compra do token com o sinal mais forte (maior folga sobre o limiar);
   3. sem sinal, HOLD quando há posição aberta e NO_TRADE quando não há, com o motivo do primeiro token avaliado.

   Todos os tokens avaliados ficam listados nos fatores.
B. Um token por ciclo, em rodízio.
X. Other (please specify)

[Answer]: A

## Q6. [Desenvolvimento] Com que frequência o ciclo de cada bot roda

Os dados de mercado chegam em baldes de 5 minutos. O intervalo mínimo entre operações é um limite do bot, conferido pela barreira da U6.

A. Um ciclo a cada 30 s para todo bot ativo, que é a base do teste de carga das Units anteriores. A decisão muda só quando um balde novo fecha, ou quando o preço de referência atual dispara uma saída.
B. Um ciclo a cada 5 minutos, logo depois de cada coleta, alinhado aos baldes.
X. Other (please specify)

[Answer]: B

## Q7. [Produto] Saldo virtual: teto, padrão e mínimo (FR7.4)

A. Teto de 10.000,000000 USDC, padrão de 1.000,000000 e mínimo de 100,000000.
B. Teto de 1.000,000000 USDC, padrão de 1.000,000000 e mínimo de 100,000000.
X. Other (please specify)

[Answer]: A

## Q8. [Produto] Limites padrão no assistente (AC4.2.1)

A. Padrões proporcionais ao saldo virtual informado, recalculados quando o saldo muda e editáveis um a um:
   - tamanho por ordem: 10% do saldo;
   - exposição por token: 20%;
   - exposição total: 50%;
   - perda diária: 5%.

   Valores fixos: drawdown de 10,0000%, slippage de 2,0000%, liquidez mínima de 10.000,000000 USDC e intervalo de 300 s.
B. Padrões fixos, iguais ao exemplo da barreira (ordem 100, token 200, total 500, perda diária 50 USDC, os demais iguais a A), qualquer que seja o saldo.
X. Other (please specify)

[Answer]: A

## Q9. [Produto] Retomar um bot pausado por drawdown

As histórias só tratam da retomada depois da perda diária. O maior patrimônio do bot é mantido pela U7 e não volta atrás.

A. A retomada só é aceita com uma nova versão de limites cujo drawdown máximo seja maior que o drawdown atual, como na perda diária.
B. A retomada é livre. Se o drawdown continuar acima do limite, o bot volta a pausar na primeira ordem.
X. Other (please specify)

[Answer]: B

## Q10. [Produto] Regras de seleção de tokens (etapa 1)

A. Lista explícita de até 10 tokens, mais filtros opcionais com os campos do scanner (liquidez, volume de 24 h, holders, idade e indicador de risco), avaliados a cada ciclo. O resultado é limitado aos 10 primeiros por liquidez. Um token com campo do filtro `unknown` fica fora.
B. Só a lista explícita de até 10 tokens, sem filtros.
X. Other (please specify)

[Answer]: A

## Q11. [Produto] Avaliação de risco na compra

A avaliação indisponível já leva a NO_TRADE.

A. A compra só acontece com a avaliação na faixa LOW ou MEDIUM. Com HIGH, a decisão é NO_TRADE com o motivo.
B. Qualquer faixa disponível permite a compra; a faixa só aparece nos fatores.
X. Other (please specify)

[Answer]: A

## Q12. [Desenvolvimento] O que guardar das decisões

Com um ciclo a cada 30 s, cada bot gera 2.880 decisões por dia, a maioria HOLD ou NO_TRADE.

A. Guardar todas, sem expurgo no beta.
B. Guardar BUY e SELL sempre. HOLD e NO_TRADE ficam guardados por 30 dias e depois são apagados por uma tarefa diária. Uma decisão citada por uma recusa da U6 ou por uma ordem da U7 nunca é apagada.
X. Other (please specify)

[Answer]: B

## Q13. [Produto] Intervalo padrão entre operações com o ciclo de 5 minutos

O ciclo roda a cada 5 minutos (Q6). O intervalo padrão entre operações é de 300 s (Q8). A barreira mede o intervalo a partir do horário do preenchimento da última operação, que acontece alguns milissegundos depois do início do ciclo. Assim, no ciclo seguinte, a nova ordem pode chegar um instante antes de 300 s e ser recusada por intervalo. Na prática, o bot operaria no máximo a cada 10 minutos.

A. Intervalo padrão de 240 s. Duas operações seguidas ficam possíveis em ciclos consecutivos.
B. Manter 300 s e aceitar que operações seguidas aconteçam a cada 10 minutos.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
