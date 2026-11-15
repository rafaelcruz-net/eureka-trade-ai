# Perguntas de Mockups Refinados — Trading REAL

Conversa: português (Brasil). Base: `inception/user-stories/stories.md` e `inception/requirements-analysis/requirements.md`. Não houve mockups rascunho nesta intent; as telas partem das existentes (`apps/web`, `apps/admin`) e dos mockups do intent `261026-memetrade-saas-mvp`.

## Q1. [Design] Onde ficam as telas do REAL no app do convidado?

A. Uma área nova "Carteira do bot" no menu, com abas Visão geral (saldo, teto, reserva, modo), Ordens, Depósito e saque, e Configurações; o painel atual ganha um cartão REAL separado do PAPER
B. Dentro da tela Conta existente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Design] Como o convidado assina uma ordem no modo Assinada?

A. Uma faixa fixa no topo, em todas as telas, avisa que há ordem para assinar, com contagem regressiva de 10 s; ao clicar, abre um diálogo com token, valor, preço estimado, derrapagem, taxa e a razão do bot, e o botão "Assinar na carteira"
B. Só uma lista na aba Ordens
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Design] Como é a escolha do modo?

A. Três cartões de opção (Assinada, Automática nas saídas, Automática total) com uma frase do que cada um faz; o modo "Automática nas saídas" aparece marcado como "em breve" até ser entregue; subir de modo abre um diálogo de consentimento com assinatura na carteira
B. Uma lista suspensa simples
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Design] Que confirmações valem para ações com dinheiro?

A. Ativar a carteira do bot, subir de modo, sacar e "vender tudo e sacar" pedem um diálogo de confirmação que nomeia o valor e o destino; baixar o teto e voltar para Assinada valem sem confirmação, porque reduzem risco
B. Confirmação em tudo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Design] O que entra no app do administrador?

A. Uma área "REAL" com a lista de usuários (modo, teto, saldo REAL, estado da liberação com a contagem da quarentena, divergências), o detalhe do usuário com liberar/desligar e baixar teto, a lista de bloqueio de tokens e o kill switch existente mostrando o efeito no REAL
B. Só a lista de usuários
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Design] Qual nível de acessibilidade e quais tamanhos de tela?

A. A acessibilidade básica do beta (teclado, foco visível, rótulos visíveis, contraste no tema escuro, cor sempre com texto ou ícone), com a contagem regressiva anunciada por `aria-live` e o diálogo de assinatura acessível por teclado; telas de 320 px até desktop, sem rolagem lateral
B. WCAG 2.1 AA completo já agora
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Design] Como mostrar estados de ordem e de saldo?

A. Cada estado da ordem tem rótulo em texto e ícone (proposta, cotada, assinada, enviada, confirmada, falhou, em conferência); valores reservados aparecem separados do saldo livre; todo valor em SOL com até 9 casas e nunca arredondado para zero
B. Só cores
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
