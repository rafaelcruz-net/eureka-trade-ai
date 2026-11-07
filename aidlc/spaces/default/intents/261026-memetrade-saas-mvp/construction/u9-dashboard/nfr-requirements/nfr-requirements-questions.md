# Perguntas dos requisitos não funcionais — U9 Painel e navegação

Unit `u9-dashboard` (kind `ui`): a tela S2 e a casca única do app do convidado. Base: [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), NFR1 a NFR14 de `requirements.md` e o canal C7 de `contract-summary.md`.

Já decidido nas etapas anteriores, sem pergunta:
- Token de acesso só em memória; canal aberto com `fetch` e `Authorization`; nunca `EventSource` nem token na URL (`project.md`).
- A política de segurança de conteúdo do app (`connect-src` só o app e a API) já existe na U1 e vale para o canal.
- Nada de segredo, token, nonce, assinatura ou endereço completo de carteira em log, inclusive no console do navegador (`project.md`, NFR7).
- Sem portão automático de acessibilidade na CI por enquanto; valem os critérios básicos do beta (`team.md`).
- Cobertura de linhas ≥ 80% no frontend, medida pelo Jest já configurado (`team.md`, NFR12).
- Disponibilidade sem meta numérica enquanto o beta roda só localmente (NFR10).

## Q1. [Desempenho] Qual é a meta de tempo para o painel abrir?

O NFR3 tem a premissa de p95 ≤ 2 s para carregar scanner, painel e P&L com 20 usuários simultâneos. O painel faz várias chamadas ao abrir (bots por estado, última ordem de cada bot, P&L, reconciliação, conta) e cada quadro aparece sozinho.

A. Cada quadro mostra conteúdo (pronto, vazio ou erro) em p95 ≤ 2 s depois da abertura, medido no ambiente local com 20 usuários simultâneos de teste, cada um com 3 bots ativos e 20 encerrados; os marcadores de carregando aparecem em ≤ 200 ms.
B. O painel inteiro (todos os quadros prontos) em p95 ≤ 2 s, nas mesmas condições.
X. Other (please specify)

[Answer]: A

## Q2. [Desempenho] Qual é a meta de atraso entre a gravação de uma operação e a tela?

O AC5.4.1 aceita até 60 s. O canal lê as marcas a cada 2 s.

A. p95 ≤ 5 s e máximo de 60 s entre a gravação da ordem pelo worker e a última operação na tela, medido no ambiente local com relógio real; 60 s continua sendo o limite que reprova.
B. Só o limite de 60 s do critério de aceite.
X. Other (please specify)

[Answer]: A

## Q3. [Confiabilidade] Como fica o canal quando a pessoa abre o painel em várias abas?

Cada aba aberta no painel abre o próprio canal; o servidor mantém um canal por conexão e lê as marcas de todas.

A. Um canal por aba, sem limite no app, com um teto de 5 canais abertos por pessoa no servidor já que o beta tem 20 convidados (registrado para a NFR Design confirmar se o teto precisa existir); abas sem foco mantêm o canal.
B. Compartilhar um único canal entre as abas pelo navegador.
X. Other (please specify)

[Answer]: A

## Q4. [Tecnologia] Como o app lê o fluxo de eventos do canal?

O canal precisa de `fetch` com cabeçalho, então o `EventSource` do navegador não serve. Existem bibliotecas que leem o formato `text/event-stream` sobre `fetch`; o `team.md` pede espera antes de adotar versões novas de pacotes do frontend.

A. Um leitor próprio e pequeno do formato `text/event-stream` (linhas `id`, `event`, `data`, separadas por linha em branco, com pedaços partidos no meio), coberto por testes de unidade, sem dependência nova.
B. Uma biblioteca pronta de leitura de eventos sobre `fetch`, fixada no lockfile.
X. Other (please specify)

[Answer]: A

## Q5. [Qualidade] Como o teste de ponta a ponta comprova a atualização sem recarregar?

Não existe rota de teste que grave uma ordem: hoje as ordens só nascem do ciclo do bot com a fonte simulada, e um ciclo pode terminar em `NO_TRADE`, então esperar uma ordem nova no teste de ponta a ponta seria instável. Uma mudança de estado do bot é determinística.

A. O teste de ponta a ponta entra com a conta de teste, confere os três quadros e o canal aberto (um `heartbeat` em até 20 s), cria e inicia um bot pelo assistente (como o smoke da U5) e, numa segunda aba, pausa esse bot pela tela do bot; a primeira aba mostra o bot pausado em até 60 s sem recarregar, pelo `bot.state`. A ordem nova, a queda e a volta do canal ficam nos testes de componente com canal falso.
B. Só conferir no ponta a ponta que o painel abre e o canal conecta; toda atualização ao vivo fica nos testes de componente.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Q1: cada quadro do painel mostra conteúdo (pronto, vazio ou erro) em p95 ≤ 2 s, medido localmente com 20 usuários de teste com 3 bots ativos e 20 encerrados cada; os marcadores de carregando aparecem em ≤ 200 ms.
- Q2: p95 ≤ 5 s e máximo de 60 s entre a gravação da ordem pelo worker e a tela; passar de 60 s reprova.
- Q3: um canal por aba, sem limite no app, com teto de 5 canais abertos por pessoa no servidor a confirmar na NFR Design; abas sem foco mantêm o canal.
- Q4: leitor próprio e pequeno do formato `text/event-stream`, com testes de unidade e sem dependência nova.
- Q5: o ponta a ponta confere os quadros e o canal aberto, cria e inicia um bot, pausa numa segunda aba e espera a primeira mostrar o bot pausado em até 60 s sem recarregar; ordem nova, queda e volta do canal ficam nos testes de componente.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
