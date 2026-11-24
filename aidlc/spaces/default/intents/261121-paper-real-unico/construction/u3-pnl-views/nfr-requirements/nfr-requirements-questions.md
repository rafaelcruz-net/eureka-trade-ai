# Perguntas do NFR Requirements — U3 `u3-pnl-views`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/u3-pnl-views/functional-design/` (BR1.1 a BR12.x, com a decisão do fundador BR2.9);
- `inception/requirements-analysis/requirements.md` (NFR1 a NFR8);
- `construction/u1-mode-skeleton/nfr-requirements/` e `construction/u2-real-bot-pnl/nfr-requirements/`, como modelo;
- `team.md` e `project.md`, nas regras de 360 px e de `zz-`.

## Q1. Desempenho das rotas e telas da U3

Quais metas de desempenho valem para a U3?

A. As rotas novas ou alteradas usam o cálculo da U2 e herdam a meta dela, p95 ≤ 1 s. São elas:
   - C4 com `market`;
   - `GET /bots?market=` com as contagens;
   - C5 (linhas do bot, 50 por página);
   - C6 (comparação).

   Na U3, o p95 é medido no teste de integração e só registrado no log, como na U1. Não entra portão novo de carga. Na tela, a releitura do cartão do painel acontece no máximo uma vez a cada 30 s, e trocar o filtro ou o período não faz mais de um pedido por bloco. (recomendado)
B. Portão de carga novo para a U3
X. Other (please specify)

[Answer]: A

## Q2. Testes

Que testes a U3 precisa ter?

A. A U3 segue a Test Strategy `Standard`, com o piso de 80% no `apps/web` e no backend. Os testes obrigatórios por risco que a U3 toca são:
   - isolamento entre contas e 404 em C5 e C6 (o 404 vem antes do 422);
   - nunca somar os modos no painel, na comparação e no filtro "Todos";
   - `unknown` como estado visível.

   Além disso:
   - o teste de contrato dos 3 casos de C4;
   - os testes de período (fuso, intervalo inclusivo, horário de verão, intervalo no futuro);
   - o spec `zz-paper-real-unico` ampliado: 360 px no painel e no detalhe, e as parcelas e a paginação com interceptação.

   (recomendado)
B. Só testes de tela
X. Other (please specify)

[Answer]: A

## Q3. Acessibilidade e telas

Que requisitos de acessibilidade e de tela valem para a U3?

A. As mesmas regras da U1, agora também no painel, no detalhe e na comparação:
   - contraste mínimo de 4,5:1;
   - texto sempre junto da cor;
   - rótulos com o modo e a moeda;
   - foco visível e ordem de foco definida;
   - `aria-live` nos totais;
   - 360 px sem rolagem lateral, com `TableScroll`;
   - troca de layout em 768 px.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. Segurança, observabilidade e escala

O que muda em segurança, observabilidade e escala?

A. Nada de novo além disto:
   - o tenant vem da autenticação, e bot de outra pessoa devolve 404;
   - nenhum dado de carteira no log;
   - um contador novo de recusas `TEMPLATE_MISMATCH`, sem dado pessoal;
   - nenhum alarme novo;
   - nenhuma migração, nenhuma configuração nova e nenhuma infraestrutura nova.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Desempenho:** as rotas da U3 herdam o p95 ≤ 1 s da U2. O p95 só é registrado no teste de integração e não há portão novo de carga. Na tela, o painel relê no máximo a cada 30 s, e cada mudança faz um pedido por bloco [Q1].
- **Testes:**
  - `Standard` com piso de 80%;
  - isolamento e 404 antes do 422;
  - nunca somar os modos;
  - `unknown` visível;
  - contrato dos 3 casos de C4;
  - casos de borda do período;
  - spec de ponta a ponta ampliado para 360 px, parcelas e paginação [Q2].
- **Acessibilidade e telas:** as mesmas regras da U1 também no painel, no detalhe e na comparação [Q3].
- **Segurança, observabilidade e escala:** tenant vindo da autenticação, um contador novo para `TEMPLATE_MISMATCH` e nada mais [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
