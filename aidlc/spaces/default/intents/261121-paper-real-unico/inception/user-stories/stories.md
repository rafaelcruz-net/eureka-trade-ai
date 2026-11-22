# Histórias de usuário — paper-real-unico

Fontes:
- `inception/requirements-analysis/requirements.md` (FR1 a FR8 e NFR1 a NFR8), a autoridade das histórias;
- `user-stories-questions.md` (Q1 a Q4);
- `personas.md`;
- `ideation/intent-capture/stakeholder-map.md`;
- `ideation/rough-mockups/wireframes.md` e `user-flow.md`;
- o mapa do código (`business-overview.md` e `component-inventory.md`);
- `inception/practices-discovery/team-practices.md`.

Formato: "Como [persona], quero [objetivo], para que [benefício]". Os critérios de aceite usam Dado/Quando/Então. As histórias são pequenas e cada uma pode ser testada sozinha [Q3]. A prioridade segue o MoSCoW do escopo [Q4]. Nenhuma história cria requisito além do `requirements.md`.

## Convenções dos exemplos de dinheiro

Os exemplos usam números inteiros e nunca ponto flutuante.

- **Unidades.** Valores REAL em lamports (1 SOL = 1.000.000.000 lamports). Quantidades de token em unidades base. Valores PAPER em US$ com decimal exato.
- **Token.** Todos os exemplos usam um token fictício `XYZ`, que nunca é um mint real.
- **Bots.** A, B e C são bots REAL da mesma pessoa, que dividem a carteira do bot. Ordem de criação: A em 2026-11-01T10:00Z; B e C no mesmo instante, 2026-11-02T10:00Z, com o id de B menor que o de C. O desempate do maior resto (FR3.7) fica então: A, depois B, depois C.
- **Custo.** O custo de uma compra é o total de lamports efetivamente pagos pelo token (FR3.10). O custo médio é sempre do próprio bot (FR3.2).
- **Recebido e resultado.** Numa venda rateada, o valor recebido (lamports) é rateado pela quantidade que cada bot tinha logo antes da venda. O resultado realizado de cada bot é a parcela recebida menos o custo médio do próprio bot sobre a quantidade dele que foi vendida (FR3.2, FR3.3). Ver a pergunta em aberto 1.
- **Taxas.** A taxa da rede aparece numa coluna própria e é subtraída no total: total = realizado + não realizado − taxas, como nos rascunhos (`wireframes.md`, tela P&L).
- **Maior resto (FR3.7).** Cada bot recebe a parte inteira de `valor × quantidade do bot ÷ quantidade total`. Os lamports que sobram vão, um a um, para os bots com a maior fração; o empate é decidido pela data de criação e, depois, pelo id.
- Os números dos exemplos foram escolhidos para que as quantidades e os custos sejam divisíveis. O arredondamento da quantidade de token rateada e do custo médio fracionário fica para o Functional Design (pergunta em aberto 2).

## Definição de pronto comum a todas as histórias

- Volume de testes `Comprehensive` e piso de 80% de linhas em cada parte (backend, `apps/web`); a ferramenta falha o build abaixo do piso (NFR7) [team-practices].
- Os testes do cálculo de P&L (US3.2 a US3.9, US5.1 a US5.4, US6.1) são escritos antes do código, a partir dos exemplos deste arquivo (NFR2, FR5.4) [team-practices].
- O tenant vem sempre do token, nunca do corpo da requisição (NFR3).
- REAL e PAPER continuam separados em fundos, ordens, ledger, posições e P&L (NFR4).

## Mapa das histórias

| Grupo | Histórias | Prioridade |
|---|---|---|
| US1 Criar bot no modo | US1.1 a US1.5 | Must |
| US2 Lista única | US2.1, US2.2, US2.4 | Must |
| | US2.3 (filtro) | Should |
| US3 P&L por bot e rateio | US3.1 a US3.9, US3.11 | Must |
| | US3.10 (detalhe) | Should |
| US4 Totais por modo e aviso | US4.1 a US4.5 | Must |
| US5 Correção do P&L REAL e conferência com o ledger | US5.1 a US5.4 | Must |
| US6 Venda só da própria quantidade | US6.1, US6.2 | Must, **depois da Performance Validation do intent 261113** |
| US7 Períodos | US7.1, US7.2 | Should |
| US8 Comparação | US8.1 | Could |

---

## US1 — Criar bot no modo

### US1.1 — Escolher Simulado ou Real na criação

**Como** convidada do beta, **quero** escolher "Simulado" ou "Real" ao criar um bot, **para que** eu teste a estratégia no simulado e depois crie o mesmo bot com dinheiro real no mesmo app.

- **Prioridade:** Must.
- **Requisitos:** FR1.1.
- **Dependências:** nenhuma. A API de criação com `market` já existe (mapa do código).
- **INVEST:** independente da lista e do P&L; pequena (um campo na tela e o envio de `market`); testável por tela e por API.

**Critérios de aceite**
- **AC1.1.1** Dado que a convidada abre a tela de criação de bot, quando a tela carrega, então ela mostra a escolha "Simulado" ou "Real", com "Simulado" já selecionado.
- **AC1.1.2** Dado que a convidada mantém "Simulado" e conclui a criação, quando a API recebe o pedido, então o bot nasce com `market = PAPER`.
- **AC1.1.3** Dado que a opção "Real" está disponível e a convidada escolhe "Real" e confirma (US1.3), quando a API recebe o pedido, então o bot nasce com `market = REAL`.
- **AC1.1.4** Dado que a convidada usa só o teclado, quando ela navega pela escolha do modo, então consegue selecionar cada opção, o foco fica visível e cada opção tem rótulo em texto (NFR5).

### US1.2 — Ver por que "Real" está desabilitado

**Como** convidada do beta, **quero** ver, com um texto próprio, por que a opção "Real" está desabilitada, **para que** eu saiba o que falta para criar um bot real.

- **Prioridade:** Must.
- **Requisitos:** FR1.2.
- **Dependências:** US1.1 (o lugar da escolha). A carteira do bot, a liberação, o flag do REAL e o kill switch já existem (intent 261113).
- **INVEST:** cada motivo pode ser testado sozinho com um estado montado; os textos abaixo são uma proposta negociável. O que o teste confere é que cada motivo tem o seu texto, diferente dos outros.

**Critérios de aceite**
- **AC1.2.1** Dado que a carteira do bot está ativa, a liberação está vigente e o REAL está ligado, sem kill switch, quando a convidada abre a criação, então a opção "Real" fica habilitada e nenhum motivo aparece.
- **AC1.2.2** Dado que a carteira do bot está inativa, quando a convidada abre a criação, então "Real" aparece desabilitada com o texto próprio desse motivo (proposta: "A carteira do bot não está ativa.") e com o link "Ativar a carteira do bot".
- **AC1.2.3** Dado que a convidada não tem liberação para o REAL, quando ela abre a criação, então "Real" aparece desabilitada com o texto próprio desse motivo (proposta: "Sua conta ainda não foi liberada para o modo real."), sem o link de ativação.
- **AC1.2.4** Dado que a liberação da convidada está em quarentena de 24 horas, quando ela abre a criação, então "Real" aparece desabilitada com o texto próprio desse motivo (proposta: "Sua liberação para o modo real está em quarentena e ainda não vale.").
- **AC1.2.5** Dado que o REAL está desligado (`memetrade.real.enabled` falso), quando a convidada abre a criação, então "Real" aparece desabilitada com o texto próprio desse motivo (proposta: "O modo real está desligado no momento.").
- **AC1.2.6** Dado que o kill switch do REAL está acionado, quando a convidada abre a criação, então "Real" aparece desabilitada com o texto próprio desse motivo (proposta: "As operações reais estão suspensas pelo sistema no momento.").
- **AC1.2.7** Dado cada um dos cinco motivos acima, quando o teste compara os textos, então os cinco textos são diferentes entre si e nenhum depende só de cor.
- **AC1.2.8** Dado qualquer um dos cinco motivos, quando um cliente pede à API a criação de um bot com `market = REAL`, então a API recusa o pedido com erro de domínio 4xx (RFC 7807, com código legível por máquina) e nenhum bot é criado: a regra vale no servidor e não só na tela.
- **AC1.2.9** Dado qualquer um dos cinco motivos, quando a convidada abre a criação, então a opção "Simulado" continua disponível.

### US1.3 — Confirmar que o bot opera com dinheiro real

**Como** convidada do beta, **quero** confirmar explicitamente que o bot opera com dinheiro real antes de criá-lo, **para que** eu nunca crie um bot real por engano.

- **Prioridade:** Must.
- **Requisitos:** FR1.3.
- **Dependências:** US1.1.
- **INVEST:** pequena (uma confirmação); testável na tela e na API.

**Critérios de aceite**
- **AC1.3.1** Dado que a convidada escolheu "Real", quando ela avança para criar o bot, então a tela mostra a frase "Este bot opera com dinheiro real" e pede uma confirmação explícita.
- **AC1.3.2** Dado que a convidada não confirmou a frase, quando ela tenta concluir, então nenhum bot é criado e a tela diz que a confirmação falta.
- **AC1.3.3** Dado que a convidada confirmou a frase, quando ela conclui, então o bot nasce com `market = REAL`.
- **AC1.3.4** Dado que a convidada escolheu "Simulado", quando ela conclui, então a confirmação do dinheiro real não aparece.
- **AC1.3.5** Dado que a convidada clica duas vezes em concluir, quando os dois pedidos chegam com a mesma chave de idempotência, então só um bot é criado.

### US1.4 — Modo fixo depois da criação

**Como** convidada do beta, **quero** que o modo de um bot nunca mude depois de criado, **para que** um bot simulado nunca passe a usar dinheiro real e um bot real nunca pareça simulado.

- **Prioridade:** Must.
- **Requisitos:** FR1.4.
- **Dependências:** nenhuma.
- **INVEST:** testável só pela API e pela tela de edição.

**Critérios de aceite**
- **AC1.4.1** Dado um bot com `market = PAPER`, quando qualquer rota de edição recebe `market = REAL`, então a API recusa com 4xx e o bot continua PAPER.
- **AC1.4.2** Dado um bot com `market = REAL`, quando qualquer rota de edição recebe `market = PAPER`, então a API recusa com 4xx e o bot continua REAL.
- **AC1.4.3** Dado um bot já criado, quando a convidada abre a edição, então a tela mostra o modo só em texto e não oferece como trocá-lo, nem uma ação de "converter".

### US1.5 — Um único ambiente para os dois modos

**Como** fundador, **quero** que os convidados criem bots simulados e reais no mesmo ambiente da mainnet, sem serviço AWS novo, **para que** eu não pague nem mantenha dois ambientes para os usuários.

- **Prioridade:** Must.
- **Requisitos:** NFR8.
- **Dependências:** US1.1.
- **INVEST:** testável pela configuração da mainnet e pela diferença da IaC.

**Critérios de aceite**
- **AC1.5.1** Dado o ambiente da mainnet, quando a convidada liberada abre a criação de bot, então as duas opções, "Simulado" e "Real", estão no mesmo app, sem trocar de rede nem de endereço.
- **AC1.5.2** Dado as mudanças deste intent em `infra/terraform/`, quando o fundador revisa a diferença, então nenhum serviço AWS novo foi acrescentado e o orçamento continua em US$ 350 por conta.
- **AC1.5.3** Dado o ambiente da devnet, quando o fundador confere o acesso, então ela continua fechada aos convidados.

---

## US2 — Lista única

### US2.1 — Ver bots simulados e reais juntos, com o modo em texto

**Como** convidada do beta, **quero** ver meus bots simulados e reais numa lista só, com o modo escrito, **para que** eu acompanhe tudo num lugar sem confundir os modos.

- **Prioridade:** Must.
- **Requisitos:** FR2.1.
- **Dependências:** nenhuma (a API já devolve os dois modos).
- **INVEST:** testável só pela tela, com dados montados.

**Critérios de aceite**
- **AC2.1.1** Dado que a convidada tem os bots "Momentum A" (PAPER), "Momentum A" (REAL) e "Reversão B" (PAPER), quando ela abre `/bots`, então os três aparecem na mesma lista.
- **AC2.1.2** Dado a mesma lista, quando o teste lê cada linha, então o modo aparece em texto, `SIMULADO` ou `REAL`, além de qualquer selo ou cor.
- **AC2.1.3** Dado que a convidada não tem nenhum bot, quando ela abre `/bots`, então a tela mostra "Você ainda não tem bots" e o botão "+ Novo bot".
- **AC2.1.4** Dado que a lista está carregando, falhou ou tem um bot com P&L `unknown`, quando a convidada olha a tela, então carregando, erro (com "tentar de novo") e `unknown` aparecem como estados visíveis e diferentes (NFR6).
- **AC2.1.5** Dado a lista com os dois modos, quando ela é ordenada, então nenhuma ordenação mistura os valores de P&L dos dois modos num ranking único.

### US2.2 — P&L de cada bot na moeda do próprio modo

**Como** convidada do beta, **quero** ver o P&L de cada bot na moeda do modo dele, **para que** eu nunca compare dólares com SOL.

- **Prioridade:** Must.
- **Requisitos:** FR2.2, NFR4.
- **Dependências:** US2.1; o valor REAL vem de US3.2.
- **INVEST:** testável com valores montados por bot.

**Critérios de aceite**
- **AC2.2.1** Dado o bot PAPER "Momentum A" com P&L de US$ 12,40, quando a lista aparece, então a linha dele mostra "+US$ 12,40".
- **AC2.2.2** Dado o bot REAL "Momentum A" com P&L de 31.200.000 lamports, quando a lista aparece, então a linha dele mostra "+0,0312 SOL".
- **AC2.2.3** Dado um bot REAL, quando a lista aparece, então o P&L dele nunca é mostrado em US$, e o de um bot PAPER nunca em SOL.
- **AC2.2.4** Dado que a lista mostra P&L de bot simulado, quando a convidada olha a tela, então o aviso "o resultado simulado não garante o resultado real" aparece (FR4.2, ver US4.2).

### US2.3 — Filtrar a lista por modo

**Como** convidada do beta, **quero** filtrar a lista por Todos, Simulado ou Real, **para que** eu olhe só um modo quando quiser.

- **Prioridade:** Should.
- **Requisitos:** FR2.3.
- **Dependências:** US2.1.
- **INVEST:** pequena; testável só na tela.

**Critérios de aceite**
- **AC2.3.1** Dado a lista com 2 bots PAPER e 1 REAL, quando a convidada abre `/bots`, então o filtro está em "Todos" e os 3 aparecem.
- **AC2.3.2** Dado a mesma lista, quando ela escolhe "Simulado", então aparecem só os 2 bots PAPER.
- **AC2.3.3** Dado a mesma lista, quando ela escolhe "Real", então aparece só o bot REAL.
- **AC2.3.4** Dado o filtro "Real" e nenhum bot REAL, quando a lista aparece, então a tela diz que não há bots reais, diferente da tela sem nenhum bot.
- **AC2.3.5** Dado que a convidada usa só o teclado, quando ela entra em `/bots`, então o foco começa no filtro e cada opção pode ser escolhida pelo teclado.

### US2.4 — Um único campo diz o modo do bot no contrato

**Como** fundador, **quero** que todas as respostas usem `market` como o modo do bot e que `mode` valha o mesmo, marcado como obsoleto, **para que** os clientes atuais não quebrem e os novos leiam um campo só.

- **Prioridade:** Must.
- **Requisitos:** FR2.4.
- **Dependências:** nenhuma.
- **INVEST:** testável só pelo teste de contrato.

**Critérios de aceite**
- **AC2.4.1** Dado um bot com `market = PAPER`, quando a API devolve o resumo dele, então `mode == market`, os dois iguais a `PAPER`.
- **AC2.4.2** Dado um bot com `market = REAL`, quando a API devolve o resumo dele, então `mode == market`, os dois iguais a `REAL`.
- **AC2.4.3** Dado o contrato OpenAPI (`openapi-guest.yaml`), quando o teste de contrato roda, então `mode` está marcado como obsoleto, continua presente e `market` aparece em todas as respostas que trazem um bot.
- **AC2.4.4** Dado um cliente atual que lê `mode`, quando ele recebe a resposta nova, então nenhum campo que ele lia foi removido nem renomeado.

---

## US3 — P&L por bot e rateio

### US3.1 — P&L simulado por bot como hoje

**Como** convidada do beta, **quero** continuar vendo o P&L de cada bot simulado como hoje, **para que** nada mude no que já funciona.

- **Prioridade:** Must.
- **Requisitos:** FR3.1, NFR3.
- **Dependências:** nenhuma.
- **INVEST:** testável pelos testes atuais de `GET /api/v1/pnl?botId=`.

**Critérios de aceite**
- **AC3.1.1** Dado um bot PAPER com os lançamentos atuais de teste, quando a convidada pede o P&L dele, então o realizado, o não realizado e as taxas são os mesmos de antes deste intent, em US$ com decimal exato.
- **AC3.1.2** Dado um bot PAPER de outra pessoa, quando a convidada pede o P&L dele pelo id, então a API responde 404 e não devolve nenhum dado do bot.

### US3.2 — P&L real por bot com custo médio do próprio bot

**Como** convidada do beta, **quero** ver o P&L de cada bot real, calculado com o custo médio daquele bot, **para que** eu saiba qual bot real ganhou e qual perdeu.

- **Prioridade:** Must.
- **Requisitos:** FR3.2, FR3.5, NFR2, NFR3.
- **Dependências:** US5.2 (um único custo médio REAL a partir do ledger).
- **INVEST:** o núcleo do intent; testável por cenário determinístico no cálculo.

**Critérios de aceite**
- **AC3.2.1** Dado que o bot A comprou 100 `XYZ` por 100.000.000 lamports e depois mais 100 `XYZ` por 300.000.000 lamports, quando o P&L de A é calculado, então A tem 200 `XYZ` com custo de 400.000.000 lamports (custo médio de 2.000.000 lamports por unidade).
- **AC3.2.2** Dado o estado de AC3.2.1, quando o próprio A vende 50 `XYZ` e recebe 150.000.000 lamports, então o realizado de A é 150.000.000 − 50 × 2.000.000 = +50.000.000 lamports, e A fica com 150 `XYZ` e custo de 300.000.000 lamports.
- **AC3.2.3** Dado que o bot B, da mesma pessoa, comprou 100 `XYZ` por 500.000.000 lamports, quando o P&L de A é calculado, então nem a quantidade nem o custo de B entram no custo médio de A.
- **AC3.2.4** Dado o estado de AC3.2.2 e o preço atual de 2.500.000 lamports por `XYZ`, quando o P&L de A é calculado, então o não realizado de A é 150 × 2.500.000 − 300.000.000 = +75.000.000 lamports.
- **AC3.2.5** Dado o P&L de um bot REAL, quando a API o devolve, então ele traz o realizado, o não realizado e as taxas em lamports, em inteiros, e nenhum endereço de carteira.
- **AC3.2.6** Dado um bot REAL de outra pessoa, quando a convidada pede o P&L dele pelo id, então a API responde 404 e não devolve nenhum dado do bot.

### US3.3 — Rateio do "vender tudo e sacar"

**Como** convidada do beta, **quero** que o resultado e a taxa do "vender tudo e sacar" sejam divididos entre os bots donos do que foi vendido, **para que** o P&L de cada bot real inclua a venda que fechou a posição dele.

- **Prioridade:** Must.
- **Requisitos:** FR3.3, FR3.7, FR5.4, NFR2.
- **Dependências:** US3.2.
- **INVEST:** testável só com o cálculo e cenários montados.

**Critérios de aceite**
- **AC3.3.1 (dois bots donos)** Dado que, logo antes da liquidação, A tem 100 `XYZ` com custo de 200.000.000 lamports e B tem 300 `XYZ` com custo de 900.000.000 lamports, quando a liquidação vende as 400 `XYZ`, recebe 1.200.000.000 lamports e paga 10.000 lamports de taxa, então:
  - A recebe 1.200.000.000 × 100 ÷ 400 = 300.000.000 lamports e a taxa de 2.500 lamports; o realizado de A é +100.000.000 lamports;
  - B recebe 900.000.000 lamports e a taxa de 7.500 lamports; o realizado de B é 0 lamport;
  - as parcelas somam 1.200.000.000 recebidos e 10.000 de taxa, e o realizado somado (+100.000.000) é igual a 1.200.000.000 − 1.100.000.000.
- **AC3.3.2 (três bots donos, com resto)** Dado que, logo antes da liquidação, A tem 50 `XYZ` com custo de 400.000.000, B tem 30 `XYZ` com custo de 210.000.000 e C tem 20 `XYZ` com custo de 160.000.000 lamports, quando a liquidação vende as 100 `XYZ`, recebe 777.777.777 lamports e paga 7.000 lamports de taxa, então:
  - as partes inteiras são A 388.888.888 (fração 0,5), B 233.333.333 (fração 0,1) e C 155.555.555 (fração 0,4), que somam 777.777.776;
  - o lamport que sobra vai para A, que tem a maior fração: A 388.888.889, B 233.333.333, C 155.555.555, soma 777.777.777;
  - a taxa fica A 3.500, B 2.100 e C 1.400, soma 7.000;
  - o realizado fica A −11.111.111, B +23.333.333 e C −4.444.445 lamports, soma +7.777.777 = 777.777.777 − 770.000.000.
- **AC3.3.3** Dado cada parcela de AC3.3.1 e AC3.3.2, quando o detalhe do bot é lido, então a parcela fica marcada como "fechado por liquidação".
- **AC3.3.4** Dado que um bot não tinha quantidade do token vendido logo antes da liquidação, quando o rateio é calculado, então esse bot não recebe nenhuma parcela.

### US3.4 — Rateio da saída condicional, inteira ou em parte

**Como** convidada do beta, **quero** que a venda de uma saída condicional seja dividida entre os bots donos do token, só sobre a quantidade executada, **para que** o P&L de cada bot reflita o stop ou o alvo que vendeu a posição dele.

- **Prioridade:** Must.
- **Requisitos:** FR3.3, FR3.7, FR5.4, NFR2.
- **Dependências:** US3.2, US5.1 (a venda condicional entra no P&L REAL).
- **INVEST:** testável só com o cálculo e cenários montados.

**Critérios de aceite**
- **AC3.4.1 (executada por inteiro, com empate no resto)** Dado que A, B e C têm 100 `XYZ` cada, com custos de 300.000.000, 450.000.000 e 600.000.000 lamports, quando uma condicional vende as 300 `XYZ`, recebe 1.000.000.000 lamports e paga 5.000 lamports de taxa, então:
  - cada bot tem a parte inteira de 333.333.333 lamports, com a mesma fração (1/3); sobra 1 lamport, e o empate é decidido pela data de criação: A fica com 333.333.334, B com 333.333.333 e C com 333.333.333, soma 1.000.000.000;
  - na taxa, cada bot tem 1.666 com a mesma fração (2/3); sobram 2 lamports, o primeiro vai para A pela data de criação e o segundo para B, que tem a mesma data de C e o id menor: A 1.667, B 1.667 e C 1.666, soma 5.000;
  - o realizado fica A +33.333.334, B −116.666.667 e C −266.666.667 lamports, soma −350.000.000 = 1.000.000.000 − 1.350.000.000.
- **AC3.4.2 (executada em parte)** Dado que A tem 600 `XYZ` com custo de 1.200.000.000 lamports (2.000.000 por unidade) e B tem 400 `XYZ` com custo de 1.000.000.000 lamports (2.500.000 por unidade), quando uma condicional sobre as 1.000 `XYZ` executa só 500 `XYZ`, recebe 1.500.000.000 lamports e paga 5.000 lamports de taxa, então:
  - o rateio usa a proporção de antes da venda (600 : 400) só sobre as 500 executadas: A vende 300 e B vende 200;
  - A recebe 900.000.000 e a taxa de 3.000; B recebe 600.000.000 e a taxa de 2.000;
  - o realizado de A é 900.000.000 − 300 × 2.000.000 = +300.000.000 e o de B é 600.000.000 − 200 × 2.500.000 = +100.000.000, soma +400.000.000 = 1.500.000.000 − 1.100.000.000;
  - depois da venda, A fica com 300 `XYZ` e custo de 600.000.000, e B com 200 `XYZ` e custo de 500.000.000 lamports.
- **AC3.4.3** Dado cada parcela de AC3.4.1 e AC3.4.2, quando o detalhe do bot é lido, então a parcela fica marcada como "fechado por condicional".
- **AC3.4.4** Dado que a parte não executada da condicional continua aberta, quando o P&L é calculado, então ela não gera nenhuma parcela de resultado nem de taxa.

### US3.5 — Excedente vendido por um bot

**Como** convidada do beta, **quero** que, quando um bot vende mais do que comprou, o excedente vá para os outros bots donos do token, ou para a linha "Sem bot" se não houver dono, **para que** nenhum bot fique com o resultado de uma quantidade que não é dele.

- **Prioridade:** Must.
- **Requisitos:** FR3.4, FR3.7, FR5.4, NFR2.
- **Dependências:** US3.2. Vale enquanto o ciclo não muda (US6.1).
- **INVEST:** testável só com o cálculo e cenários montados.

**Critérios de aceite**
- **AC3.5.1 (com outros donos)** Dado que A comprou 100 `XYZ` por 100.000.000, B comprou 200 `XYZ` por 300.000.000 e C comprou 100 `XYZ` por 100.000.000 lamports, quando o ciclo de A vende as 400 `XYZ` da carteira, recebe 800.000.000 lamports e paga 4.000 lamports de taxa, então:
  - A fica com a própria quantidade (100): recebe 200.000.000, taxa de 1.000, realizado +100.000.000;
  - o excedente de 300 é rateado entre B e C (200 : 100): B recebe 400.000.000, taxa de 2.000, realizado +100.000.000; C recebe 200.000.000, taxa de 1.000, realizado +100.000.000;
  - os valores somam 800.000.000 recebidos, 4.000 de taxa e +300.000.000 de realizado = 800.000.000 − 500.000.000;
  - as parcelas de B e C ficam marcadas como "vendido por outro bot".
- **AC3.5.2 (sem outro dono)** Dado que a carteira do bot tem 150 `XYZ`, das quais 100 foram compradas por A por 100.000.000 lamports e 50 vêm de uma posição herdada de antes deste intent, sem bot dono, e que o custo das 150 `XYZ` no ledger REAL é de 175.000.000 lamports, quando o ciclo de A vende as 150 `XYZ`, recebe 300.000.000 lamports e paga 3.000 lamports de taxa, então:
  - A recebe 200.000.000, taxa de 2.000, realizado +100.000.000;
  - as 50 `XYZ` de excedente vão para a linha "Sem bot", com o motivo "excedente sem dono": recebe 100.000.000, taxa de 1.000, custo de 175.000.000 − 100.000.000 = 75.000.000, realizado +25.000.000;
  - o realizado somado (+125.000.000) é igual a 300.000.000 − 175.000.000.
- **AC3.5.3** Dado um bot que vendeu só o que comprou, quando o P&L é calculado, então nenhuma parcela "vendido por outro bot" nem "excedente sem dono" aparece.

### US3.6 — Ordem das operações no mesmo slot

**Como** fundador, **quero** que as operações REAL sejam aplicadas na ordem da confirmação na rede (slot e depois assinatura), **para que** o rateio use as quantidades certas mesmo quando duas operações caem no mesmo instante.

- **Prioridade:** Must.
- **Requisitos:** FR3.8, FR5.4.
- **Dependências:** US3.5.
- **INVEST:** testável com dois lançamentos gravados fora de ordem.

**Critérios de aceite**
- **AC3.6.1 (duas operações no mesmo slot)** Dado que A tem 100 `XYZ` com custo de 100.000.000 lamports e que, no mesmo slot, (1) B compra 100 `XYZ` por 300.000.000 lamports com 5.000 de taxa e (2) o ciclo de A vende as 200 `XYZ` da carteira por 400.000.000 lamports com 5.000 de taxa, sendo a assinatura da compra de B menor que a da venda de A na ordem de comparação das assinaturas, quando o P&L é calculado, então a compra de B é aplicada antes da venda, e:
  - A recebe 200.000.000, taxa de 2.500, realizado +100.000.000;
  - B, dono de 100 `XYZ` no momento da venda, recebe 200.000.000 marcados como "vendido por outro bot", taxa de 2.500 da venda mais 5.000 da compra (7.500), realizado −100.000.000;
  - o realizado somado é 0 = 400.000.000 − 400.000.000, e as taxas somam 10.000.
- **AC3.6.2** Dado o cenário de AC3.6.1 com a venda gravada no ledger antes da compra, quando o P&L é calculado, então o resultado é exatamente o mesmo de AC3.6.1.
- **AC3.6.3** Dado o mesmo cenário, quando o P&L é calculado duas vezes, então os dois resultados são idênticos.

### US3.7 — Taxas no bot certo

**Como** convidada do beta, **quero** que cada taxa da rede entre no P&L do bot certo, **para que** o custo de operar apareça no bot que o causou.

- **Prioridade:** Must.
- **Requisitos:** FR3.9, FR5.4.
- **Dependências:** US3.2. A taxa das vendas sem bot está em US3.3 e US3.4.
- **INVEST:** cada caso de taxa pode ser testado sozinho.

**Critérios de aceite**
- **AC3.7.1 (ordem que falhou)** Dado que o bot A envia uma compra que falha na rede e paga 5.000 lamports de taxa, quando o P&L é calculado, então as taxas de A aumentam 5.000 lamports, o realizado de A não muda, a quantidade e o custo de A não mudam e nenhum outro bot recebe essa taxa.
- **AC3.7.2 (modo Assinada)** Dado que, no modo Assinada, o bot B decide uma compra de 100 `XYZ` por 200.000.000 lamports e a carteira principal paga a taxa de 5.000 lamports, quando o P&L é calculado, então B tem 100 `XYZ` com custo de 200.000.000 lamports e as taxas de B aumentam 5.000 lamports.
- **AC3.7.3** Dado o cenário de AC3.7.2, quando o P&L é calculado, então a taxa de 5.000 não aparece em nenhum outro bot nem na linha "Sem bot".
- **AC3.7.4 (venda sem bot)** Dado as vendas de AC3.3.1, AC3.3.2 e AC3.4.1, quando o P&L é calculado, então a taxa de cada venda é rateada junto com o recebido, e as parcelas somam a taxa total.

### US3.8 — Preço desconhecido não vira zero

**Como** convidada do beta, **quero** que, sem preço, só o não realizado apareça como desconhecido, **para que** eu não leia um resultado falso e continue vendo o que já é certo.

- **Prioridade:** Must.
- **Requisitos:** FR3.10, FR3.5, FR5.4.
- **Dependências:** US3.2.
- **INVEST:** testável com o adaptador de preço simulado devolvendo `unknown`.

**Critérios de aceite**
- **AC3.8.1** Dado que o bot A tem 100 `XYZ` com custo de 200.000.000 lamports, realizado de +50.000.000 e taxas de 10.000 lamports, e o preço de `XYZ` está `unknown`, quando o P&L de A é calculado, então o realizado é +50.000.000, o custo é 200.000.000 e as taxas são 10.000 lamports, todos exatos.
- **AC3.8.2** Dado o mesmo cenário, quando o P&L é mostrado, então o não realizado de A aparece como "desconhecido" (`unknown`), nunca como zero.
- **AC3.8.3** Dado o mesmo cenário, quando o total do bot e o total do modo REAL são mostrados, então a parte que depende do não realizado aparece como desconhecida e nunca é somada como zero; o realizado e as taxas do modo continuam visíveis.
- **AC3.8.4** Dado o mesmo cenário, quando o preço volta a ser conhecido (2.500.000 lamports por `XYZ`), então o não realizado de A passa a +50.000.000 lamports (100 × 2.500.000 − 200.000.000).

### US3.9 — Bot pausado ou removido continua dono do P&L

**Como** convidada do beta, **quero** que um bot pausado ou removido continue com os lotes e o P&L dele, **para que** o resultado não desapareça nem passe para outro bot.

- **Prioridade:** Must.
- **Requisitos:** FR3.11, FR5.4.
- **Dependências:** US3.3.
- **INVEST:** testável com um bot removido no cenário.

**Critérios de aceite**
- **AC3.9.1 (bot removido)** Dado que A tem 100 `XYZ` com custo de 100.000.000 lamports e C tem 100 `XYZ` com custo de 120.000.000 lamports, e C foi removido pela convidada, quando a liquidação vende as 200 `XYZ`, recebe 300.000.000 lamports e paga 2.000 lamports de taxa, então:
  - A recebe 150.000.000, taxa de 1.000, realizado +50.000.000;
  - C, mesmo removido, recebe 150.000.000, taxa de 1.000, realizado +30.000.000;
  - o realizado somado (+80.000.000) é igual a 300.000.000 − 220.000.000.
- **AC3.9.2** Dado o cenário de AC3.9.1, quando a tela de P&L aparece, então C aparece com o estado "removido" e com o P&L dele.
- **AC3.9.3** Dado um bot pausado com lotes, quando a tela de P&L aparece, então ele aparece com o estado "pausado" e continua dono dos lotes e do P&L.

### US3.10 — Detalhe do bot com a origem de cada linha

**Como** convidada do beta, **quero** ver as ordens e as linhas que formam o P&L de um bot, com a origem de cada uma, **para que** eu entenda de onde veio o resultado.

- **Prioridade:** Should.
- **Requisitos:** FR3.6.
- **Dependências:** US3.2 a US3.5.
- **INVEST:** testável só na tela de detalhe com dados montados.

**Critérios de aceite**
- **AC3.10.1** Dado um bot REAL com uma compra própria, uma venda própria e uma parcela de liquidação, quando a convidada abre `/bots/{id}`, então cada linha mostra a data no fuso dela, o lado, o token, o resultado em SOL e a origem.
- **AC3.10.2** Dado as parcelas de US3.3, US3.4 e US3.5, quando o detalhe aparece, então a origem aparece em texto: "Bot", "Fechado por liquidação", "Fechado por condicional" ou "Vendido por outro bot".
- **AC3.10.3** Dado um bot de outra pessoa, quando a convidada abre o detalhe pelo id, então a API responde 404 e a tela não mostra nenhum dado do bot.
- **AC3.10.4** Dado a tabela do detalhe, quando um leitor de tela a percorre, então cada coluna tem cabeçalho e a origem é lida em texto.

### US3.11 — P&L rápido sem travar as ordens

**Como** convidada do beta, **quero** que o P&L por bot e por modo apareça em até 1 segundo, **para que** eu acompanhe os bots sem esperar e sem atrasar as ordens deles.

- **Prioridade:** Must.
- **Requisitos:** NFR1.
- **Dependências:** US3.2, US4.1.
- **INVEST:** testável no cenário de carga; a medição na devnet usa o mesmo cenário.

**Critérios de aceite**
- **AC3.11.1** Dado uma pessoa com 10 bots e 2 anos de histórico nos dois modos, quando o cenário de carga da CI pede o P&L por bot e por modo, então o p95 do tempo de resposta é de até 1 s.
- **AC3.11.2** Dado o mesmo volume de dados na devnet, quando o fundador repete a medição, então o p95 é de até 1 s.
- **AC3.11.3** Dado leituras de P&L em carga, quando o ciclo de um bot envia uma ordem, então a ordem não espera nenhum bloqueio do cálculo de P&L.

---

## US4 — Totais por modo e aviso

### US4.1 — Totais separados por modo, nunca somados

**Como** convidada do beta, **quero** ver o total do simulado em dólar e o total do real em SOL, sempre separados, **para que** eu nunca leia uma soma sem sentido entre os dois modos.

- **Prioridade:** Must.
- **Requisitos:** FR4.1, NFR3, NFR4, NFR7.
- **Dependências:** US3.1, US3.2.
- **INVEST:** é a fatia fina deste intent; testável pela API, pela tela e pelo spec de ponta a ponta.

**Critérios de aceite**
- **AC4.1.1** Dado que a convidada tem o total simulado de +US$ 9,30 (realizado +US$ 7,00, não realizado +US$ 2,30, taxas US$ 0,00) e o total real de +31.200.000 lamports (realizado +20.000.000, não realizado +13.000.000, taxas 1.800.000), quando ela abre `/pnl`, então aparecem dois blocos, "SIMULADO (US$)" com +US$ 9,30 e "REAL (SOL)" com +0,0312 SOL, cada um com o seu realizado, não realizado e taxas.
- **AC4.1.2 (teste de nunca somar)** Dado os totais de AC4.1.1, quando o teste lê todas as respostas da API de P&L, a tela `/pnl`, o painel e a lista de bots, então nenhum campo, linha ou texto traz um total geral que junte os dois modos, nem um valor que dependa de US$ 9,30 e de 0,0312 SOL ao mesmo tempo.
- **AC4.1.3** Dado a resposta da API de P&L por modo, quando o teste de contrato a lê, então cada total está dentro do próprio modo, com a sua moeda, e o contrato não tem nenhum campo de total geral.
- **AC4.1.4** Dado um bot REAL, quando o total PAPER é calculado, então nada do bot REAL entra nele, e o contrário também vale.
- **AC4.1.5 (spec de ponta a ponta)** Dado o validador Solana local, quando o spec novo do `scripts/verify.sh` cria um bot PAPER e um bot REAL e faz uma compra em cada modo, então o bloco Simulado mostra só o bot PAPER, o bloco Real mostra só o bot REAL e nenhum total soma os modos.

### US4.2 — Aviso do simulado em toda tela com P&L simulado

**Como** convidada do beta, **quero** ver o aviso de que o resultado simulado não garante o real sempre que aparece um resultado simulado, **para que** eu não tome o simulado como promessa.

- **Prioridade:** Must.
- **Requisitos:** FR4.2.
- **Dependências:** nenhuma.
- **INVEST:** pequena; testável por tela.

**Critérios de aceite**
- **AC4.2.1** Dado que a convidada tem P&L simulado, quando ela abre `/pnl`, então o bloco Simulado mostra o aviso "o resultado simulado não garante o resultado real".
- **AC4.2.2** Dado o mesmo P&L, quando ela abre o painel, então o lado Simulado mostra o mesmo aviso.
- **AC4.2.3** Dado bots simulados com P&L, quando ela abre a lista `/bots`, então o aviso aparece.
- **AC4.2.4** Dado um bot simulado, quando ela abre o detalhe dele, então o aviso aparece.
- **AC4.2.5** Dado a comparação de US8.1, quando ela é aberta, então o aviso aparece no lado simulado.
- **AC4.2.6** Dado uma tela que mostra só P&L real, quando ela aparece, então o aviso do simulado não aparece nela.
- **AC4.2.7** Dado qualquer tela com o aviso, quando o teste lê os textos, então nenhum texto promete lucro.

### US4.3 — Painel com os dois totais lado a lado

**Como** convidada do beta, **quero** ver no painel o resultado simulado e o real lado a lado, **para que** eu tenha a visão dos dois modos logo ao entrar.

- **Prioridade:** Must.
- **Requisitos:** FR4.3.
- **Dependências:** US4.1, US4.2.
- **INVEST:** testável só no painel.

**Critérios de aceite**
- **AC4.3.1** Dado os totais de AC4.1.1, quando a convidada abre o painel, então ele mostra "Resultado simulado: +US$ 9,30" e "Resultado real: +0,0312 SOL" lado a lado, com o aviso no lado simulado.
- **AC4.3.2** Dado que a convidada não tem bot REAL, quando ela abre o painel, então o lado Real mostra "Sem bots reais".
- **AC4.3.3** Dado que a carteira do bot está inativa, quando ela abre o painel, então o lado Real mostra o link "Ativar a carteira do bot".
- **AC4.3.4** Dado o painel, quando a convidada clica num dos totais, então vai para a tela `/pnl`.

### US4.4 — Totais acessíveis

**Como** convidada do beta que usa leitor de tela ou só o teclado, **quero** ler e navegar os totais de cada modo, **para que** eu saiba qual valor é de qual modo sem depender da cor.

- **Prioridade:** Must.
- **Requisitos:** NFR5.
- **Dependências:** US4.1, US4.3.
- **INVEST:** testável por verificação de contraste, teclado e rótulos.

**Critérios de aceite**
- **AC4.4.1** Dado as telas `/bots`, `/pnl`, o painel e o detalhe do bot, quando o teste mede o contraste no tema escuro, então todo texto tem contraste mínimo de 4,5:1.
- **AC4.4.2** Dado um valor positivo ou negativo, ou um selo de modo, quando ele aparece com cor, então também aparece com texto ou sinal (por exemplo "+", "−", `SIMULADO`, `REAL`).
- **AC4.4.3** Dado o total de cada bloco em `/pnl` e no painel, quando um leitor de tela o lê, então o rótulo diz o modo e a moeda (por exemplo "Total simulado, em dólares" e "Total real, em SOL").
- **AC4.4.4** Dado a tela `/pnl`, quando a convidada navega só pelo teclado, então o foco começa no primeiro bloco, passa por todos os elementos ativos e fica sempre visível.
- **AC4.4.5** Dado a tela `/pnl`, quando o teste lê a estrutura, então há um h1 "Resultado" e um h2 por modo ("Simulado, em dólares" e "Real, em SOL").

### US4.5 — Telas no celular e estados distintos

**Como** convidada do beta, **quero** acompanhar os totais e os bots no celular, com estados claros, **para que** eu veja tudo sem rolar para o lado e saiba quando um valor está carregando, falhou ou é desconhecido.

- **Prioridade:** Must.
- **Requisitos:** NFR6.
- **Dependências:** US4.1, US2.1.
- **INVEST:** testável por viewport e por estados montados.

**Critérios de aceite**
- **AC4.5.1** Dado as telas `/bots`, `/pnl`, o painel e o detalhe do bot numa tela de 360 px de largura, quando elas aparecem, então não há rolagem horizontal e todos os valores ficam legíveis, sem cortes.
- **AC4.5.2** Dado a tela `/pnl` em 360 px, quando ela aparece, então os blocos viram cartões empilhados, Simulado e depois Real, e o foco do teclado anda de cartão em cartão.
- **AC4.5.3** Dado cada tela com P&L, quando o dado está carregando, falhou, é `unknown` ou está vazio, então cada estado tem a sua aparência e o seu texto, diferentes dos outros três.

---

## US5 — Correção do P&L REAL e conferência com o ledger

### US5.1 — A venda condicional entra no P&L REAL

**Como** convidada do beta, **quero** que as vendas das saídas condicionais entrem no meu P&L real, **para que** o resultado mostrado inclua o stop e o alvo que já venderam.

- **Prioridade:** Must.
- **Requisitos:** FR5.1, FR5.4.
- **Dependências:** nenhuma (corrige um defeito atual).
- **INVEST:** testável pelo teste de regressão escrito antes do código.

**Critérios de aceite**
- **AC5.1.1 (regressão)** Dado que a pessoa tem 100 `XYZ` com custo de 100.000.000 lamports e uma condicional vende as 100 `XYZ`, recebe 180.000.000 lamports e paga 5.000 de taxa, quando o P&L REAL é calculado, então o realizado inclui +80.000.000 lamports e as taxas incluem 5.000 lamports dessa venda.
- **AC5.1.2** Dado o mesmo cenário, quando o teste roda contra o cálculo anterior a este intent, então ele falha (prova o defeito), e passa com o cálculo novo.
- **AC5.1.3** Dado a venda condicional, quando o P&L é calculado, então ela é lida a partir do ledger REAL (referência `conditional:<id>`).

### US5.2 — Um único custo médio REAL

**Como** fundador, **quero** um único cálculo de custo médio REAL, a partir do ledger REAL, usado pelo P&L e pelas posições, **para que** os dois nunca discordem.

- **Prioridade:** Must.
- **Requisitos:** FR5.2.
- **Dependências:** nenhuma.
- **INVEST:** testável comparando as duas leituras sobre o mesmo ledger.

**Critérios de aceite**
- **AC5.2.1** Dado o ledger REAL de AC3.2.1 e AC3.2.2, quando o P&L e as posições (`RealPositions`) são lidos, então os dois mostram 150 `XYZ` com custo de 300.000.000 lamports.
- **AC5.2.2** Dado um ledger REAL com compras, vendas, liquidação e condicional, quando o P&L e as posições são lidos, então a quantidade e o custo de cada token são iguais nos dois, lamport a lamport.

### US5.3 — O P&L de cada modo fecha com o ledger

**Como** fundador, **quero** que a soma do P&L dos bots mais a linha "Sem bot" seja exatamente igual ao P&L do modo calculado a partir do ledger, **para que** eu confie no número antes de abrir o REAL aos convidados.

- **Prioridade:** Must.
- **Requisitos:** FR5.3, NFR2.
- **Dependências:** US3.2 a US3.9.
- **INVEST:** testável com um ledger montado; igualdade exata, sem tolerância.

**Critérios de aceite**
- **AC5.3.1 (REAL, desde o início)** Dado que, desde o início, o bot A tem realizado +100.000.000, não realizado +20.000.000 e taxas 5.000; o bot B tem realizado −30.000.000, não realizado −4.000.000 e taxas 2.000; e a linha "Sem bot" tem realizado +25.000.000 e taxas 1.000 com o motivo "excedente sem dono", quando o fundador confere com o ledger REAL, então:
  - o realizado do modo pelo ledger é +95.000.000, o não realizado é +16.000.000 e as taxas são 8.000 lamports;
  - os totais por bot são A +119.995.000, B −34.002.000 e "Sem bot" +24.999.000, que somam +110.992.000 = 95.000.000 + 16.000.000 − 8.000.
- **AC5.3.2 (PAPER)** Dado os bots PAPER "Momentum A" (+US$ 12,40) e "Reversão B" (−US$ 3,10), quando o fundador confere com o ledger PAPER, então o P&L do modo pelo ledger é +US$ 9,30, igual à soma, e a linha "Sem bot" do PAPER é US$ 0,00.
- **AC5.3.3** Dado que a soma dos bots mais "Sem bot" difere em 1 lamport do valor do ledger, quando a conferência roda, então ela falha e mostra a diferença.
- **AC5.3.4** Dado uma linha "Sem bot" diferente de zero sem motivo registrado, quando a conferência roda, então ela falha.
- **AC5.3.5** Dado a conferência, quando ela roda para uma pessoa, então lê só o ledger e os bots daquela pessoa (tenant).

### US5.4 — O realizado de um período fecha com o ledger

**Como** fundador, **quero** que o realizado dos bots num período, mais a linha "Sem bot", seja igual ao realizado do modo calculado pelos lançamentos do ledger com data no período, **para que** a conferência valha em qualquer recorte de tempo.

- **Prioridade:** Must.
- **Requisitos:** FR5.3, NFR2.
- **Dependências:** US5.3. Usa o período no cálculo; a escolha do período na tela é a US7.1 (Should).
- **INVEST:** testável com um ledger montado e um período fixo.

**Critérios de aceite**
- **AC5.4.1** Dado os lançamentos REAL:
  - 2026-11-05T14:00Z, venda própria de A, realizado +40.000.000;
  - 2026-11-12T09:00Z, venda própria de A, realizado +100.000.000;
  - 2026-11-14T16:00Z, venda de B com excedente sem dono: B +30.000.000 e "Sem bot" +5.000.000 com o motivo "excedente sem dono";
  - 2026-11-15T11:00Z, liquidação com a parcela de C, realizado −10.000.000;

  quando o fundador confere o período de 2026-11-10T00:00Z a 2026-11-17T00:00Z, então o realizado dos bots no período é A +100.000.000, B +30.000.000 e C −10.000.000, a linha "Sem bot" é +5.000.000 e a soma, +125.000.000, é igual ao realizado do modo pelo ledger no período; a venda de 2026-11-05 não entra.
- **AC5.4.2** Dado o mesmo ledger no período "desde o início", quando o fundador confere, então o realizado somado é +165.000.000 (A +140.000.000, B +30.000.000, C −10.000.000, "Sem bot" +5.000.000).
- **AC5.4.3** Dado um período diferente de "desde o início", quando a conferência roda, então ela não confere o não realizado; o não realizado é conferido só em "desde o início".

---

## US6 — O bot real vende só a própria quantidade

Grupo Must, marcado **depois da Performance Validation do intent 261113**. Ele só entra quando o intent 261113-trading-real-bot terminar a Performance Validation (restrição de ordem de entrega do `requirements.md`).

### US6.1 — O bot real vende só o que comprou

**Como** convidada do beta, **quero** que cada bot real venda só a quantidade que ele mesmo comprou, **para que** um bot não feche a posição de outro bot meu.

- **Prioridade:** Must — depois da Performance Validation do intent 261113.
- **Requisitos:** FR6.1.
- **Dependências:** US3.2; Performance Validation do intent 261113 concluída.
- **INVEST:** testável no ciclo com a carteira do bot no validador local.

**Critérios de aceite**
- **AC6.1.1** Dado que A comprou 100 `XYZ` por 100.000.000 lamports e B comprou 300 `XYZ` por 450.000.000 lamports, quando o ciclo de A decide vender `XYZ` e a venda recebe 200.000.000 lamports com 5.000 de taxa, então a ordem vende só 100 `XYZ`, o realizado de A é +100.000.000 e as taxas de A aumentam 5.000.
- **AC6.1.2** Dado o mesmo cenário, quando a venda de A termina, então B continua com 300 `XYZ` e custo de 450.000.000 lamports, e nenhuma parcela "vendido por outro bot" aparece.
- **AC6.1.3** Dado que A não tem quantidade própria de `XYZ`, quando o ciclo de A avalia `XYZ`, então ele não envia venda de `XYZ`.
- **AC6.1.4** Dado a mesma decisão de venda reprocessada pela fila ou repetida com a mesma `Idempotency-Key`, quando o ciclo a processa, então só uma transação é enviada.

### US6.2 — Aviso de que cada bot passa a vender só o que comprou

**Como** convidada do beta, **quero** ser avisada na tela de bots reais quando o comportamento mudar, **para que** eu entenda por que um bot não vende mais a posição inteira.

- **Prioridade:** Must — depois da Performance Validation do intent 261113.
- **Requisitos:** FR6.2.
- **Dependências:** US6.1.
- **INVEST:** pequena; testável na tela.

**Critérios de aceite**
- **AC6.2.1** Dado que a venda só da própria quantidade está ligada, quando a convidada abre a lista com bots reais, então aparece o aviso de que cada bot passa a vender só o que ele comprou.
- **AC6.2.2** Dado que a mudança ainda não está ligada, quando a convidada abre a lista, então o aviso não aparece.
- **AC6.2.3** Dado o aviso, quando um leitor de tela chega a ele, então ele é lido como texto, sem depender de cor ou ícone.

---

## US7 — Períodos

### US7.1 — Escolher o período do P&L

**Como** convidada do beta, **quero** ver o P&L desde o início, nos últimos 7 dias, nos últimos 30 dias ou num intervalo que eu escolho, **para que** eu acompanhe o resultado recente dos bots.

- **Prioridade:** Should.
- **Requisitos:** FR7.1.
- **Dependências:** US3.1, US3.2, US5.4.
- **INVEST:** testável na tela e na API com o relógio injetável.

**Critérios de aceite**
- **AC7.1.1** Dado que a convidada abre `/pnl`, quando a tela carrega, então o período "desde o início" já está selecionado.
- **AC7.1.2** Dado a escolha de período, quando a convidada a abre, então as opções são "desde o início", "últimos 7 dias", "últimos 30 dias" e "intervalo".
- **AC7.1.3** Dado o ledger de AC5.4.1 e o intervalo de 2026-11-10 a 2026-11-16 no fuso da convidada, quando ela o aplica, então o realizado do bloco Real é a soma dos lançamentos do período e a venda de 2026-11-05 não entra.
- **AC7.1.4** Dado um intervalo com o fim antes do início, quando a convidada tenta aplicá-lo, então a tela diz qual campo corrigir e mantém o que ela digitou.
- **AC7.1.5** Dado qualquer período, quando o P&L aparece, então os dois modos continuam em blocos separados, sem total geral.

### US7.2 — Realizado do período, não realizado de agora, no meu fuso

**Como** convidada do beta, **quero** que o realizado conte só as vendas do período e que o não realizado seja sempre o de agora, com as datas no meu fuso, **para que** eu leia o período sem dupla contagem.

- **Prioridade:** Should.
- **Requisitos:** FR7.2.
- **Dependências:** US7.1.
- **INVEST:** testável com o relógio injetável e um fuso fixo.

**Critérios de aceite**
- **AC7.2.1** Dado o período "últimos 7 dias" e o bot A com não realizado atual de +20.000.000 lamports, quando o P&L aparece, então o não realizado mostrado é +20.000.000, o mesmo de "desde o início".
- **AC7.2.2** Dado uma venda gravada em 2026-11-20T02:30Z e a convidada no fuso America/Sao_Paulo (UTC−3), quando o detalhe aparece, então a data mostrada é 19/11 23:30.
- **AC7.2.3** Dado qualquer período, quando a API recebe ou devolve instantes, então eles estão em UTC.

---

## US8 — Comparação

### US8.1 — Comparar um bot simulado com um bot real da mesma estratégia

**Como** convidada do beta, **quero** comparar lado a lado um bot simulado e um bot real da mesma estratégia, **para que** eu veja como o real se comporta perto do simulado.

- **Prioridade:** Could.
- **Requisitos:** FR8.1.
- **Dependências:** US3.1, US3.2, US4.2.
- **INVEST:** testável só na vista de comparação.

**Critérios de aceite**
- **AC8.1.1** Dado o bot PAPER "Momentum A" (+US$ 12,40) e o bot REAL "Momentum A" (+0,0312 SOL), quando a convidada os compara, então cada um aparece no seu lado, na sua moeda.
- **AC8.1.2** Dado a comparação, quando ela aparece, então não há ranking nem ordenação que misture os dois modos, nem uma diferença calculada entre US$ e SOL.
- **AC8.1.3** Dado a comparação, quando ela aparece, então o lado simulado mostra o aviso "o resultado simulado não garante o resultado real".
- **AC8.1.4** Dado um bot de outra pessoa, quando a convidada tenta compará-lo pelo id, então a API responde 404.

---

## Assumptions & Open Questions

1. **Rateio do recebido ou do resultado (FR3.3).** O `requirements.md` diz que "o resultado e a taxa" das vendas sem bot são rateados pela quantidade. Os exemplos rateiam o valor recebido e a taxa pela quantidade, e o resultado de cada bot é a parcela recebida menos o custo médio do próprio bot. Assim, o resultado somado sempre fecha com o ledger e cada bot fica com o próprio custo. Ratear o resultado em si daria o mesmo valor só quando todos os bots têm o mesmo custo médio. **Aprovado pelo responsável em 2026-11-21.**
2. **Arredondamento da quantidade de token e do custo médio.** O FR3.7 fixa o maior resto para os lamports. O rateio da quantidade de token (unidades base) e o custo de uma venda parcial quando o custo médio não é inteiro ficam para o Functional Design; os exemplos usam números divisíveis.
3. **Taxa do excedente (FR3.4).** Os exemplos rateiam a taxa da venda com excedente pela quantidade, como no FR3.3 ("rateado ... como no FR3.3"), e não deixam a taxa inteira no bot que enviou a ordem. **Aprovado pelo responsável em 2026-11-21.**
4. **Custo da parte "excedente sem dono".** No AC3.5.2, o custo da parte sem dono é o custo do token na carteira pelo ledger REAL (FR5.2) menos o custo dos bots donos. A confirmar no Functional Design.
5. **Liquidação de uma quantidade sem bot dono.** O `requirements.md` não diz para onde vai a parte da liquidação ou da condicional que não tem bot dono (por exemplo, uma posição herdada). A regra é a linha "Sem bot" com um motivo registrado, como no FR3.4. **Aprovado pelo responsável em 2026-11-21.**
6. **Mais de um motivo para "Real" desabilitado.** O FR1.2 dá um texto por motivo, mas não diz o que mostrar quando dois motivos valem ao mesmo tempo. A confirmar no Refined Mockups ou no Functional Design.
7. **Limites dos períodos.** "Últimos 7 dias" e "últimos 30 dias" não dizem se contam dias inteiros no fuso da pessoa ou 7 × 24 horas até agora, nem se o fim do intervalo é inclusivo. Os exemplos usam datas longe das bordas. A confirmar no Functional Design.
8. **Textos dos motivos.** Os textos dos motivos em US1.2 são uma proposta negociável; o critério confere que cada motivo tem o seu texto.
