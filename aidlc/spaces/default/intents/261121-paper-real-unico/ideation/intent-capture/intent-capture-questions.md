# Perguntas da Intent Capture — paper-real-unico

Conversa: português (Brasil).

## Sources

- [desc] Initial description: "Faça a configuração para ter somente um ambiente aonde o usuário possa criar bots com trade real e trade simulado. Com P&L categorizado por bot e geral entre devnet e mainnet"
- [scope] Workflow-selected scope: `feature`.
- [memory:M1] `aidlc/spaces/default/memory/project.md#Mandated`: "ALWAYS manter REAL e PAPER estritamente separados em fundos, ordens, ledger, posições e P&L. (origem: C-03, PRD §15 US12) (affirmed 2026-10-26)"

## Q1. Problema de negócio

Que problema este trabalho resolve?

A. O usuário precisa de um único lugar onde cria bots simulados e reais lado a lado e acompanha o resultado de cada um, sem trocar de ambiente nem de conta
B. O fundador quer reduzir o custo de manter dois ambientes (devnet e mainnet) para os usuários
C. As duas coisas (A e B)
D. Not yet defined
X. Other (please specify)

[Answer]: C

## Q2. O que é "trade simulado"

O pedido fala em P&L "entre devnet e mainnet". A Jupiter só opera na mainnet: na devnet não há liquidez nem tokens de meme reais. O que o usuário deve entender por "simulado"?

A. O simulado é o PAPER que já existe: saldo virtual, preços reais da mainnet, nenhuma ordem na rede. "Devnet" no pedido quer dizer "simulado", e o único ambiente fica na mainnet
B. O simulado é uma terceira opção por bot, com SOL de teste na devnet, além do PAPER. Depende de um roteador de swap que funcione na devnet (a confirmar)
C. Trocar o PAPER por ordens na devnet
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. Quem é o cliente e qual a dor

Quem usa e o que dói hoje?

A. Os convidados do beta (e o fundador). Hoje o bot nasce PAPER ou REAL e não muda, e o resultado do PAPER e do REAL fica em telas separadas, sem uma visão por bot e total
B. Só o fundador, para testar a estratégia antes de pôr dinheiro
C. Not identified
X. Other (please specify)

[Answer]: A

## Q4. Como medir o sucesso

O que mostra que deu certo?

A. O usuário cria um bot simulado e um real no mesmo ambiente, vê o P&L de cada bot e o total separado por simulado e real, e o P&L fecha com o ledger de cada modo
B. Além de A: o usuário consegue criar um bot real a partir da configuração de um bot simulado ("promover para REAL")
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. Por que agora

O que dispara este trabalho?

A. O REAL está ficando pronto (intent 261113-trading-real-bot) e o fundador quer que o usuário teste no simulado e passe para o real no mesmo produto
B. Pressão de custo da infraestrutura
C. Not identified
X. Other (please specify)

[Answer]: A

## Q6. Partes interessadas e decisão

Quem decide o escopo e a prioridade, e quem influencia?

A. O fundador decide sozinho (responsável único). Os convidados influenciam pelo feedback do beta
B. O fundador decide; o assessor jurídico influencia no que toca dinheiro real e termos
C. Not identified
X. Other (please specify)

[Answer]: A

## Q7. Comunicação

Há exigência de comunicação ou de relatório?

A. Nenhuma além das aprovações do AI-DLC; os convidados são avisados das mudanças na tela e nos termos quando o comportamento muda
B. Um relatório semanal de progresso
C. None
X. Other (please specify)

[Answer]: A

## Q8. O que fica fora desta primeira versão

O escopo do fluxo já foi escolhido. O que esta primeira versão do produto NÃO faz?

A. Não mistura saldos, ordens ou P&L entre simulado e real em nenhum momento; não converte um bot existente de um modo para o outro; não cria nova rede de execução além do que já existe
B. Além de A: não inclui a exportação fiscal do simulado
C. Not yet defined
X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

- O problema tem dois lados. O usuário precisa de um único lugar para criar bots simulados e reais lado a lado e acompanhar o resultado de cada um, sem trocar de ambiente nem de conta. E o fundador quer evitar o custo de manter dois ambientes para os usuários [Q1].
- "Simulado" é o PAPER que já existe: saldo virtual, preços reais da mainnet e nenhuma ordem na rede. O "devnet" do pedido quer dizer simulado, e o ambiente único dos usuários fica na mainnet [Q2].
- O cliente são os convidados do beta e o fundador. Hoje o bot nasce PAPER ou REAL e não muda, e os resultados ficam em telas separadas, sem visão por bot nem total [Q3].
- Sucesso é criar um bot simulado e um real no mesmo ambiente e ver o P&L de cada bot e o total separado por simulado e real, fechando com o ledger de cada modo [Q4].
- O gatilho é o REAL ficando pronto: o fundador quer que o usuário teste no simulado e passe para o real no mesmo produto [Q5].
- O fundador decide sozinho, e os convidados influenciam pelo feedback do beta [Q6].
- Não há relatório além das aprovações do AI-DLC. Os convidados são avisados na tela e nos termos quando o comportamento muda [Q7].
- Fora desta versão [Q8]:
  - misturar saldos, ordens ou P&L entre simulado e real;
  - converter um bot existente de um modo para o outro;
  - criar nova rede de execução;
  - a exportação fiscal do simulado.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct

## Assumption Confirmation

- [assumption] O ambiente de devnet continua existindo só para os testes do produto pelo fundador, e não para os usuários.

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions
