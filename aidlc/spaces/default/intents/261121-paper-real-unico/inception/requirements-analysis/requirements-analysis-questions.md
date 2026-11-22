# Perguntas da Requirements Analysis — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável: "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes:
- `ideation/` (intenção, viabilidade e escopo, com as revisões);
- o mapa do código em `aidlc/spaces/default/codekb/eureka-trader-ai/`;
- `inception/practices-discovery/team-practices.md`.

Achados da varredura que viram requisitos:
- o app web não cria bot REAL, porque `BotCreate` não envia `market`;
- `BotSummary.mode` volta sempre `PAPER`;
- existem dois custos médios REAL que divergem: `RealPositions` e `RealPnlCalculator`;
- as vendas das saídas condicionais ficam fora do P&L REAL.

## Q1. Criar bot REAL pelo app

Hoje o app web só cria bots PAPER. A API aceita REAL quando a carteira do bot está ativa. Como o usuário escolhe o modo ao criar o bot?

A. A tela de criação ganha a escolha "Simulado" ou "Real", com "Simulado" como padrão. A opção "Real" fica disponível só quando a carteira do bot está ativa, a liberação está vigente e o REAL está ligado. Se não estiver, a opção aparece desabilitada, com o motivo em texto e o link para ativar a carteira. Antes de criar um bot REAL, a tela pede uma confirmação: "Este bot opera com dinheiro real". (recomendado)
B. Uma tela separada para criar bot REAL
X. Other (please specify)

[Answer]: A

## Q2. Fonte única do custo médio REAL

Existem dois cálculos de custo médio REAL que divergem: `RealPositions` e `RealPnlCalculator`. Qual vale?

A. Um só cálculo, o do P&L REAL, com as entradas do ledger REAL: compras, vendas, saídas condicionais e taxas. Ele passa a ser a única fonte. O outro passa a usá-lo, e um teste confere que os dois dão o mesmo resultado em cenários determinísticos. A correção entra neste intent, coordenada com o intent 261113. (recomendado)
B. Manter os dois e explicar a diferença
X. Other (please specify)

[Answer]: A

## Q3. Saídas condicionais no P&L REAL

As vendas das saídas condicionais entram no ledger REAL, mas hoje não entram no P&L REAL.

A. O P&L REAL passa a incluir as vendas das saídas condicionais a partir do ledger. A correção é feita neste intent, primeiro com um teste de regressão que reproduz o erro, e é avisada ao intent 261113 como um defeito já corrigido aqui. (recomendado: o P&L por bot depende disso)
B. Corrigir no intent 261113 antes de começar este
X. Other (please specify)

[Answer]: A

## Q4. Tolerância da conferência com o ledger

O que significa o P&L "fechar com o ledger"?

A. É igualdade exata nas unidades base: lamports no REAL e a menor unidade decimal no PAPER. A soma do P&L de todos os bots, mais a linha "Sem bot" (que deve ser zero depois do rateio), é igual ao P&L do modo calculado a partir do ledger. Nenhuma diferença é aceita. O cálculo não usa ponto flutuante. (recomendado)
B. Tolerância de 1 lamport por bot
X. Other (please specify)

[Answer]: A

## Q5. Período

Que períodos o P&L mostra?

A. Desde o início (padrão), últimos 7 dias, últimos 30 dias e um intervalo escolhido. O realizado conta as vendas dentro do período. O não realizado é sempre o de agora. Os instantes ficam em UTC e são mostrados no fuso da pessoa. (recomendado)
B. Só desde o início
X. Other (please specify)

[Answer]: A

## Q6. Campo `mode` do resumo do bot

O campo `mode` do resumo do bot volta sempre `PAPER`.

A. O contrato passa a usar `market` (PAPER ou REAL) como o modo do bot em todas as respostas e telas. O campo `mode` é corrigido para refletir o mercado e marcado como obsoleto, sem quebrar os clientes atuais. (recomendado)
B. Remover `mode` já
X. Other (please specify)

[Answer]: A

## Q7. Métrica do objetivo de custo

A revisão apontou que o objetivo "evitar o custo de dois ambientes" não tem métrica.

A. Medir pela quantidade de ambientes de usuário em produção: ela passa de dois planejados para um, a mainnet. A conta da devnet continua só para os testes do fundador. O custo dela já está no orçamento de US$ 350 e não cresce com usuários. (recomendado)
B. Medir em dólares economizados por mês
X. Other (please specify)

[Answer]: A

## Q8. Desempenho e escala

Que metas de desempenho valem?

A. O P&L por bot e por modo responde com p95 de até 1 s para uma pessoa com até 10 bots e 2 anos de histórico, calculado na leitura. Isso usa os índices que já existem e, se preciso, um índice novo em `real_order(bot_id)`. O cálculo nunca trava a execução de ordens. (recomendado)
B. Pré-calcular o P&L em tabela
X. Other (please specify)

[Answer]: A

## Q9. Isolamento e segurança

Que regras de isolamento e segurança valem?

A. As regras que já existem:
   - o tenant sempre vem do token;
   - o bot de outra pessoa responde 404;
   - nenhum endereço de carteira aparece no P&L;
   - o P&L nunca soma os modos, em nenhuma API;
   - um teste obrigatório confere que nenhuma resposta traz um total geral.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. Resto do arredondamento no rateio (dinheiro)

O rateio em lamports pode deixar resto. Como distribuir o resto?

A. Pelo método do maior resto. Cada bot recebe a parte inteira, e os lamports que sobram vão, um a um, para os bots com a maior fração. O empate é decidido pela data de criação do bot e depois pelo id. A soma das parcelas é sempre exatamente o valor da venda. (recomendado)
B. O resto inteiro vai para o bot com a maior parte
X. Other (please specify)

[Answer]: A

## Q11. Ordem e excedente sem dono (dinheiro)

Como ordenar as operações, e o que fazer com o excedente quando não há outro bot dono?

A. Pelas regras abaixo:
   - **Ordem.** As operações REAL seguem a confirmação na rede: primeiro o slot e depois a assinatura. A proporção usada no rateio é a das quantidades que cada bot tinha logo antes da venda.
   - **Excedente sem dono.** Se não houver outro bot dono, por exemplo numa posição herdada de antes deste intent, o excedente vai para a linha "Sem bot" com o motivo "excedente sem dono".
   - **Conferência.** A igualdade com o ledger passa a ser: soma dos bots mais a linha "Sem bot" igual ao P&L do modo. A linha "Sem bot" só pode ser diferente de zero com um motivo registrado.

   (recomendado)
B. O excedente sem dono é sempre atribuído ao bot que vendeu
X. Other (please specify)

[Answer]: A

## Q12. Taxas e casos especiais (dinheiro)

Como tratar as taxas e os casos especiais?

A. Assim:
   - **Taxa de ordem que falhou na rede:** fica no bot que enviou a ordem.
   - **Taxa no modo Assinada:** é paga pela carteira principal e entra no P&L do bot que decidiu a ordem.
   - **Taxa de venda sem bot:** é rateada junto com o resultado.
   - **Custo:** usa sempre os lamports efetivamente pagos, nunca um preço. Com preço `unknown`, só o não realizado fica desconhecido.
   - **Venda parcial de uma condicional:** é rateada sobre a quantidade preenchida.
   - **Bot pausado ou apagado:** continua dono dos seus lotes e aparece como "pausado" ou "removido".

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q13. Conferência com o ledger por período (dinheiro)

Como a conferência com o ledger funciona quando há filtro de período?

A. A igualdade exata vale em todo período. A soma do realizado de todos os bots no período, mais a linha "Sem bot" do período, é igual ao realizado do modo a partir dos lançamentos do ledger com data no período. O não realizado é conferido só no período "desde o início". (recomendado)
B. Conferir só no período "desde o início"
X. Other (please specify)

[Answer]: A

## Q14. Ajustes de medida e de contrato

Como fechar as medidas de desempenho, acessibilidade e celular, e os detalhes do contrato?

A. Os valores abaixo (recomendado):
   - **Desempenho:** o p95 de 1 s é medido no cenário de carga da CI e repetido na devnet.
   - **Contraste:** mínimo de 4,5:1.
   - **Celular (360 px):** sem rolagem lateral e com todos os valores legíveis.
   - **Opção "Real" desabilitada:** os motivos são carteira inativa, liberação ausente ou em quarentena, REAL desligado e kill switch acionado, cada um com o seu texto.
   - **Campo `mode`:** passa a valer `PAPER` ou `REAL`, igual ao `market`, com um teste de contrato.
   - **Mudança de ciclo (FR6):** entra com um aviso na tela quando o comportamento mudar.
   - **Termos v3:** não mudam.
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Criar bot REAL pelo app.** Escolha entre "Simulado" (o padrão) e "Real". O "Real" só fica disponível com a carteira ativa, a liberação vigente e o REAL ligado. Quando não está, a opção mostra o motivo: carteira inativa, liberação ausente ou em quarentena, REAL desligado ou kill switch. Antes de criar, a pessoa confirma "Este bot opera com dinheiro real" [Q1] [Q14].
- **Custo médio REAL.** Um único cálculo, feito a partir do ledger, e o cálculo duplicado passa a usá-lo [Q2].
- **Condicionais no P&L REAL.** Passam a entrar, com um teste de regressão escrito antes da correção [Q3].
- **Conferência com o ledger.** Igualdade exata: a soma dos bots mais a linha "Sem bot" é igual ao P&L do modo, e a linha "Sem bot" só fica diferente de zero com um motivo. A igualdade vale em todo período; o não realizado só é conferido "desde o início" [Q4] [Q11] [Q13].
- **Rateio.** Usa o maior resto, com desempate pela criação do bot e depois pelo id. A soma das parcelas é sempre exata [Q10].
- **Ordem.** Segue a confirmação na rede, e a proporção é a das quantidades logo antes da venda. O excedente sem nenhum dono vai para "Sem bot", com o motivo [Q11].
- **Taxas e casos especiais.**
  - A ordem que falhou fica no bot.
  - No modo Assinada, a taxa entra no bot que decidiu.
  - A taxa da venda sem bot é rateada junto com o resultado.
  - O custo usa os lamports pagos.
  - Uma condicional parcial é rateada pela quantidade preenchida.
  - Um bot pausado ou apagado continua dono do que tem e aparece como tal.

  [Q12]
- **Períodos.** Desde o início, 7 dias, 30 dias ou um intervalo, em UTC e mostrados no fuso da pessoa [Q5].
- **Modo do bot.** `market` passa a ser o modo. `mode` vale `PAPER` ou `REAL`, igual a `market`, fica obsoleto e tem teste de contrato [Q6] [Q14].
- **Custo do projeto.** Um ambiente de usuário em vez de dois [Q7].
- **Desempenho.** p95 de até 1 s com 10 bots e 2 anos de histórico, medido na carga da CI e na devnet [Q8] [Q14].
- **Isolamento.** O bot de outra pessoa dá 404, nenhum endereço aparece e não existe total geral, com teste obrigatório [Q9].
- **Acessibilidade e celular.** Contraste de 4,5:1 e 360 px sem rolagem lateral. A mudança de ciclo vem com aviso, e os termos v3 não mudam [Q14].

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
