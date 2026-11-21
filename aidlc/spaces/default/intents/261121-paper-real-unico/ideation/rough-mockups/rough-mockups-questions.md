# Perguntas dos Rough Mockups — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes: `ideation/scope-definition/scope-document.md`, `intent-backlog.md` e as telas que já existem em `apps/web/src/app/`: `bots`, `pnl`, `real` e o painel.

## Q1. Entradas e telas principais

Por onde o usuário entra e quais são as telas principais?

A. Três telas que já existem, ajustadas:
   - **Bots (`/bots`):** a lista única, com PAPER e REAL lado a lado e o modo escrito em texto;
   - **P&L (`/pnl`):** o P&L por bot e o total de cada modo, em dois blocos separados (Simulado e Real);
   - **Painel:** os dois totais, separados.

   O detalhe do bot (`/bots/{id}`) mostra o P&L do bot, com as linhas rateadas marcadas. Não entra tela nova. (recomendado)
B. Uma tela nova "Carteira geral", que junta tudo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. Fluxo principal

Qual é o fluxo principal, no caminho feliz?

A. O usuário:
   1. cria um bot PAPER e acompanha o P&L dele;
   2. cria um bot REAL com a mesma estratégia;
   3. vê os dois na lista;
   4. abre o P&L e compara cada bot dentro do próprio modo e os totais separados;
   5. no detalhe do bot REAL, vê as linhas "fechado por liquidação" e "fechado por condicional".

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. Hierarquia da informação

Como a informação fica organizada na tela?

A. Na ordem abaixo (recomendado):
   1. o modo vem primeiro: um bloco Simulado (US$) e um bloco Real (SOL), cada um com o próprio total no topo e o rótulo em texto;
   2. dentro do bloco, cada bot com o P&L realizado, o não realizado e as taxas;
   3. o aviso "o resultado simulado não garante o resultado real" fica fixo no bloco Simulado.

   Nunca aparece uma linha "total geral" somando os dois.
B. Uma lista única ordenada por P&L, misturando os modos
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. Padrões visuais

Que padrões de interface seguir?

A. Os componentes e os tokens que já existem no `apps/web`, no tema escuro. A cor do REAL é `real` e já existe; o selo REAL e o selo PAPER (o `RealBadge` e o equivalente) acompanham sempre o texto. Os estados de carregando, erro e `unknown` são distintos. (recomendado)
B. Um novo sistema visual
X. Other (please specify)

[Answer]: A

## Q5. Dispositivos

Que tamanhos de tela atender?

A. Computador e celular, a partir de 360 px. No celular, os blocos viram cartões empilhados, como na tela de ordens do REAL. (recomendado)
B. Só computador
X. Other (please specify)

[Answer]: A

## Q6. Acessibilidade

Que requisitos de acessibilidade valem?

A. Os do beta, que já estão em `project.md`: navegação por teclado, foco visível, rótulos visíveis, contraste legível no tema escuro e cor sempre acompanhada de texto ou ícone. Os totais têm um rótulo legível por leitor de tela que diz o modo e a moeda. (recomendado)
B. WCAG 2.1 AA completo já
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Telas.** Ajustamos as três telas que já existem: Bots, com a lista única e o modo em texto; P&L, com os blocos Simulado e Real separados; e o Painel, com os dois totais. O detalhe do bot mostra as linhas rateadas. Não entra tela nova [Q1].
- **Fluxo principal.** O usuário cria um bot PAPER, cria um bot REAL com a mesma estratégia, vê os dois na lista, compara cada bot dentro do seu modo e vê os totais separados. No detalhe do bot REAL, aparecem as linhas de liquidação e de condicional [Q2].
- **Hierarquia.** O modo vem primeiro: um bloco Simulado em US$ e um bloco Real em SOL, cada um com o total, os bots, o realizado, o não realizado e as taxas. O aviso fica fixo no bloco Simulado. Nunca há uma linha de total geral [Q3].
- **Visual.** Usamos os componentes e os tokens que já existem, no tema escuro, com selos acompanhados de texto e com estados distintos para carregando, erro e `unknown` [Q4].
- **Telas suportadas.** Computador e celular a partir de 360 px, com cartões empilhados no celular [Q5].
- **Acessibilidade.** Valem as regras do beta, e os totais ganham um rótulo para leitor de tela com o modo e a moeda [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
