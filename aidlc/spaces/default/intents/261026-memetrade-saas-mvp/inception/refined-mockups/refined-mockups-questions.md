# Perguntas dos mockups refinados — MemeTrade Analytics

## Contexto já decidido (não será perguntado de novo)

- Telas S1 a S7 e fluxos F1 a F6 (`../../ideation/rough-mockups/wireframes.md`, `user-flow.md`); só computador, só tema escuro, português, painel como primeira tela, scanner em tabela com painel lateral, assistente em 4 etapas.
- Estados obrigatórios (vazio, carregando, erro, parcial) e critérios transversais CT-UX-1 a CT-UX-9 e DP-1 a DP-4 em `../user-stories/stories.md`.
- Acessibilidade básica no beta, contraste de texto ≥ 4,5:1, WCAG 2.1 AA antes do público (`../requirements-analysis/requirements.md` NFR11).
- Sem guia visual existente; telas novas necessárias: lista de bots, acompanhamento do bot (S5 refinada), área administrativa, formulário de feedback.

## Q1. [Design] Qual base de componentes de interface usamos?
Não existe guia visual; a escolha define o quanto de acessibilidade já vem pronto.

A. Uma biblioteca de componentes acessíveis pronta, sem visual imposto, com nossos próprios tokens de cor, tipografia e espaçamento para o tema escuro (recomendado: teclado, foco e leitores de tela já resolvidos)
B. Componentes próprios do zero
X. Other (please specify)

[Answer]: A. Biblioteca acessível com tokens próprios

## Q2. [Design] Qual a largura mínima de tela suportada?
O beta é só para computador; abaixo da largura mínima mostramos um aviso.

A. 1280 px; abaixo disso, aviso de que o beta é só para computador, sem quebrar a página (recomendado: a tabela do scanner com painel lateral precisa de espaço)
B. 1024 px, com o painel lateral sobrepondo a tabela
X. Other (please specify)

[Answer]: A. 1280 px

## Q3. [Design] Onde fica a área administrativa?

A. No mesmo menu, com o item "Administração" visível só para o administrador (recomendado)
B. Em um endereço separado, com layout próprio
X. Other (please specify)

[Answer]: B. Endereço separado

## Q4. [Design] Como exibimos números nas tabelas (preços, liquidez, P&L)?

A. Alinhados à direita, com fonte de largura fixa para os algarismos, separador decimal brasileiro (vírgula) e abreviações (k, M) com o valor exato ao passar o mouse ou focar (recomendado)
B. Formato padrão, sem tratamento especial
X. Other (please specify)

[Answer]: A. Números formatados

## Q5. [Produto] Como a lista do scanner se atualiza enquanto o usuário navega?
Linhas que mudam de lugar sozinhas atrapalham a leitura e o foco do teclado.

A. Valores das linhas visíveis atualizam no lugar; novos tokens e reordenação só entram quando a pessoa clica em "Atualizar lista (N novidades)" (recomendado)
B. Tudo atualiza automaticamente, inclusive a ordem
X. Other (please specify)

[Answer]: A. Valores no lugar + "Atualizar lista"

## Q6. [Produto] Como o indicador de risco aparece visualmente?
O indicador vai de 0 a 100 (maior = mais risco) e é rotulado como "em validação".

A. Número mais faixa escrita: Baixo (0–33), Médio (34–66), Alto (67–100) e Indisponível; faixas ajustáveis na calibração (recomendado)
B. Só o número
X. Other (please specify)

[Answer]: A. Número + faixa escrita

## Consolidated Summary Confirmation

- Base de componentes: biblioteca de componentes acessíveis, sem visual imposto, com tokens próprios de cor, tipografia e espaçamento para o tema escuro.
- Largura mínima de 1280 px; abaixo disso, aviso de que o beta é só para computador, sem quebrar a página.
- Área administrativa em um endereço separado, com layout próprio.
- Números alinhados à direita, algarismos de largura fixa, vírgula decimal e abreviações (k, M) com o valor exato ao passar o mouse ou focar.
- Scanner: valores das linhas visíveis atualizam no lugar; novos tokens e reordenação entram só ao clicar em "Atualizar lista (N novidades)".
- Indicador de risco com número e faixa escrita: Baixo (0–33), Médio (34–66), Alto (67–100) e Indisponível; faixas ajustáveis na calibração.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
