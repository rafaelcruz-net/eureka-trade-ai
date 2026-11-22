# Perguntas do Contract Design — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes:
- `inception/units-generation/unit-of-work.md` e `unit-of-work-dependency.md`, com U1 a U4;
- `inception/domain-design/components.md` e `decisions.md`;
- `inception/requirements-analysis/requirements.md`;
- o contrato atual em `openapi-guest.yaml`;
- a correção do projeto que manda tratar as interfaces Java públicas como contratos de esquema compartilhado.

## Q1. API pública

Que API pública muda?

A. Só o contrato guest (`openapi-guest.yaml`), que o `apps/web` consome. As mudanças:
   - `POST /api/v1/bots` aceita `market` (`PAPER` | `REAL`), que é obrigatório no app novo e vale `PAPER` quando ausente, para os clientes antigos.
   - `GET /api/v1/bots/real-eligibility` devolve a elegibilidade com o motivo mais forte.
   - `GET /api/v1/real/account-limits` devolve os limites REAL da conta, só leitura e em lamports.
   - `GET /api/v1/pnl/by-mode?period=` devolve os dois blocos, que nunca são somados.
   - `GET /api/v1/bots/{id}/pnl/lines` devolve as linhas de um bot com a origem de cada uma.
   - `GET /api/v1/pnl/compare` devolve a comparação.

   O campo `mode` do resumo do bot passa a valer o mesmo que `market` e fica `deprecated`. O contrato admin não muda. (recomendado)
B. Um contrato novo separado
X. Other (please specify)

[Answer]: A

## Q2. Mecanismo em cada fronteira

Que mecanismo cada fronteira usa?

A. Assim:
   - entre os módulos (Units), interfaces Java públicas tratadas como contrato de esquema compartilhado: `pnl.api` (`RealBotPnlQuery`, `RealBotHoldings`, `BotDirectory`), `ledger.api` (`RealCostMethod` e a leitura em fluxo), `riskcontrol.api` (`RealAccountLimits`) e `realexecution.api` (vendas em aberto do bot);
   - entre o navegador e a API, REST/OpenAPI;
   - nenhum evento novo, então nenhuma mudança no AsyncAPI;
   - no banco, só a migração aditiva do `slot`, que é dono do módulo de cada tabela.

   (recomendado)
B. Eventos para o P&L
X. Other (please specify)

[Answer]: A

## Q3. Dono de cada contrato

Quem é dono de cada contrato?

A. A Unit provedora é dona do contrato:
   - **U1:** as rotas de criação, elegibilidade, limites e `by-mode`, e o `BotDirectory`.
   - **U2:** o `RealCostMethod`, o `RealBotPnlQuery` e o formato das linhas rateadas no `by-mode`.
   - **U3:** as rotas de linhas e de comparação, e o parâmetro `period`.
   - **U4:** o `RealBotHoldings` e a leitura das vendas em aberto.

   (recomendado)
B. Todos com a U1
X. Other (please specify)

[Answer]: A

## Q4. Versões e mudanças que quebram

Como tratar versões e mudanças que quebram?

A. Só mudanças aditivas em `/api/v1/`: os campos novos são opcionais e os clientes ignoram os campos que não conhecem. O `mode` fica `deprecated`, nunca é removido neste intent, e um teste de contrato confere `mode == market`. A forma da resposta `by-mode` é a definitiva desde a U1, e a U2 só preenche os valores. Uma quebra exige uma rota `/v2` aprovada pelo responsável. A CI já falha na quebra (`check:api` e os testes de contrato). (recomendado)
B. Versão nova da API
X. Other (please specify)

[Answer]: A

## Q5. Erros, tempo e repetição

Como tratar erros, tempo limite e repetição?

A. Assim:
   - **Formato:** os erros seguem o RFC 7807, com `code` e `correlationId`.
   - **Criação REAL recusada:** volta como 409 `REAL_NOT_ELIGIBLE`, com o motivo mais forte em `reason`. É um erro de domínio, sem repetição; a tela relê a elegibilidade.
   - **Bot de outra pessoa:** devolve 404.
   - **Modo indisponível:** quando um modo falha, o `by-mode` devolve 200 com o bloco que funcionou e, no outro, `status: UNAVAILABLE` e `correlationId`. Assim o estado parcial aparece na tela.
   - **Leituras:** podem ser repetidas, porque são idempotentes. A criação usa `Idempotency-Key`.
   - **`RealBotHoldings`:** tem um orçamento de tempo dentro do ciclo e falha fechada; o número exato fica para o NFR Design.

   (recomendado)
B. Erro 500 inteiro quando um modo falha
X. Other (please specify)

[Answer]: A

## Q6. Valores e unidades no contrato

Como os valores aparecem no contrato?

A. Assim:
   - os valores REAL vão como texto inteiro em lamports (`"12345"`) e as quantidades em unidades base, como texto;
   - os valores PAPER seguem o formato decimal em texto que já existe;
   - nunca há número de ponto flutuante;
   - o não realizado com preço desconhecido vai como `{ "kind": "UNKNOWN" }`, numa união discriminada;
   - cada bloco traz a sua `currency` (`USD` | `SOL`), e não existe um campo de total geral.

   (recomendado)
B. Números JSON
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **API pública:** só o contrato guest muda [Q1]:
  - `POST /bots` aceita `market`, que vale `PAPER` quando ausente;
  - entram as rotas de elegibilidade REAL, de limites da conta, de P&L por modo, de linhas do bot e de comparação;
  - `mode` passa a ser igual a `market` e fica obsoleto.
- **Entre módulos:** interfaces Java públicas tratadas como contrato de esquema compartilhado. Nenhum evento novo. No banco, só a coluna `slot` aditiva [Q2].
- **Donos:** a Unit provedora é dona do contrato. U1 fica com a criação, a elegibilidade, os limites, o `by-mode` e o `BotDirectory`; U2 com o custo e o P&L REAL por bot; U3 com as linhas, a comparação e o período; U4 com a quantidade própria e as vendas em aberto [Q3].
- **Versões:** só mudanças aditivas em `/api/v1/`. A forma do `by-mode` é a definitiva desde a U1. Uma quebra exige `/v2` aprovada pelo responsável [Q4].
- **Erros:** RFC 7807; 409 `REAL_NOT_ELIGIBLE` com o motivo; 404 para bot de outra pessoa; o bloco que falhou aparece como `UNAVAILABLE` sem derrubar o outro; leituras repetíveis; criação com `Idempotency-Key`; falha fechada na quantidade própria [Q5].
- **Valores:** lamports e unidades base em texto inteiro, nunca ponto flutuante. `UNKNOWN` é uma união discriminada. Cada bloco traz a sua moeda, e não há total geral [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
