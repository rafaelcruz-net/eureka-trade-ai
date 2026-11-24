# Perguntas do Functional Design — U3 `u3-pnl-views`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `inception/units-generation/unit-of-work-story-map.md`: US2.3, US3.10, US4.1 a US4.5 (partes da U3), US7.1, US7.2 (parte da U3) e US8.1.
- `inception/user-stories/stories.md`.
- `inception/refined-mockups/`: M4 a M7, a especificação de interação e as perguntas abertas OQ4 e OQ5.
- `inception/contract-design/contract-summary.md`: C4 a C6, PQ-6 e PQ-8.
- `construction/u1-mode-skeleton/` e `construction/u2-real-bot-pnl/`: o que já existe em C4 e C10, `TableScroll` e `money-format.ts`.

## Q1. Releitura de um bloco só (PQ-6)

O "Tentar de novo" de um bloco indisponível relê a rota inteira ou só aquele bloco?

A. Só aquele bloco. A rota `GET /api/v1/pnl/by-mode` ganha o parâmetro opcional `market=PAPER|REAL`, uma mudança aditiva. Com ele, a resposta traz só o bloco pedido, e o outro bloco da tela continua como estava. Sem o parâmetro, a rota devolve os dois blocos, como hoje. A regra de nunca somar os modos continua valendo. (recomendado)
B. Reler a rota inteira
X. Other (please specify)

[Answer]: A

## Q2. Filtro por modo na lista (US2.3)

Como funciona o filtro por modo na lista de bots?

A. O filtro tem três opções: "Todos", "Simulado" e "Real". Ele fica na URL (`/bots?mode=PAPER`), filtra a lista no servidor pelo `market` e o foco inicial vai para ele (AC2.3.5). Com "Todos", a lista mostra os dois modos, cada linha com o seu selo, e nenhuma soma. (recomendado)
B. Filtro só no navegador
X. Other (please specify)

[Answer]: A

## Q3. Detalhe do bot com a origem das linhas (US3.10, C5)

Como o detalhe do bot mostra de onde vem cada linha de P&L?

A. A rota `GET /api/v1/bots/{id}/pnl/lines` devolve as linhas do bot, cada uma com a origem:
   - "operação do bot";
   - "fechado por liquidação";
   - "fechado por condicional";
   - "vendido por outro bot".

   Cada linha traz também a parcela rateada e a taxa. A rota usa o mesmo cálculo da U2 e o mesmo período da tela. As linhas são paginadas por cursor, 50 por página, na ordem da rede. Um bot de outra pessoa devolve 404. (recomendado)
B. Sem paginação
X. Other (please specify)

[Answer]: A

## Q4. Comparação (US8.1, C6, OQ4 e PQ-8)

O que define "a mesma estratégia" e como a comparação é mostrada?

A. "A mesma estratégia" é o mesmo `templateId`. A comparação mostra um bot simulado e um bot real, lado a lado, cada um na própria moeda e com o próprio resultado. Ela nunca soma nem subtrai os dois e nunca converte US$ em SOL. Se os dois bots têm `templateId` diferente, a rota devolve 422 `TEMPLATE_MISMATCH` e a tela explica o motivo. O aviso do simulado aparece no lado simulado. (recomendado)
B. Comparar quaisquer dois bots
X. Other (please specify)

[Answer]: A

## Q5. Painel com os dois totais (US4.3, AC4.1.2)

O que o painel mostra e como os totais aparecem?

A. O painel mostra dois cartões, "Simulado (US$)" e "Real (SOL)", com o resultado "desde o início" e o período escrito na tela. Não há total geral em nenhum campo, linha ou texto. Cada cartão tem o próprio estado (carregando, desconhecido, indisponível), e o simulado traz o aviso fixo. As mudanças de total são anunciadas por `aria-live`. (recomendado)
B. Um cartão único com os dois valores
X. Other (please specify)

[Answer]: A

## Q6. Período e UTC (US7.1, US7.2)

Como funciona a escolha do período?

A. As opções são "Hoje", "7 dias", "30 dias", "Desde o início" e um intervalo personalizado.
   - Os dias são inteiros no fuso da pessoa, como decidido nos mockups (Q7).
   - O intervalo inclui o dia inicial e o dia final inteiros.
   - A tela converte cada período para instantes em UTC antes de chamar a API, aproveitando o `period.ts` que já existe.
   - O período escolhido fica na URL.
   - O não realizado é sempre o de agora, e a tela diz isso.

   (recomendado)
B. Períodos em UTC na tela
X. Other (please specify)

[Answer]: A

## Q7. Acessibilidade e celular no painel e no detalhe

Que regras de acessibilidade e de celular valem para o painel e o detalhe do bot?

A. As mesmas da U1:
   - contraste de 4,5:1;
   - texto sempre junto da cor;
   - rótulos com o modo e a moeda;
   - foco visível;
   - cabeçalhos;
   - 360 px sem rolagem lateral, com `TableScroll` nas tabelas, como manda a regra do projeto.

   O spec de ponta a ponta `zz-paper-real-unico` passa a conferir também o painel e o detalhe em 360 px. (recomendado)
B. Sem conferência no spec
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Releitura:** o "Tentar de novo" relê só o bloco que falhou, pelo parâmetro opcional `market` em C4, que é uma mudança aditiva [Q1].
- **Filtro:** "Todos", "Simulado" e "Real", guardado na URL e aplicado no servidor. Nunca soma [Q2].
- **Detalhe do bot:** as linhas trazem a origem, a parcela rateada e a taxa. A lista é paginada de 50 em 50, na ordem da rede. Bot de outra pessoa devolve 404 [Q3].
- **Comparação:** dois bots com o mesmo `templateId`, lado a lado, cada um na própria moeda, sem somar, subtrair ou converter. Estratégias diferentes devolvem 422 `TEMPLATE_MISMATCH` [Q4].
- **Painel:** dois cartões, Simulado (US$) e Real (SOL), "desde o início" e sem total geral. Cada cartão tem os próprios estados, e o simulado traz o aviso [Q5].
- **Período:** dias inteiros no fuso da pessoa, com o intervalo inclusivo. A tela envia instantes em UTC à API e guarda o período na URL [Q6].
- **Acessibilidade e celular:** as regras da U1 valem também no painel e no detalhe. O spec de ponta a ponta confere os 360 px [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
