# Requisitos de segurança — U9 Painel e navegação

Base: [respostas](nfr-requirements-questions.md) Q3 e Q4, [regras](../functional-design/rules.md) BR2, BR5, BR6 e BR8, [especificação funcional](../functional-design/functional-spec.md), NFR4 a NFR7 de [requirements.md](../../../inception/requirements-analysis/requirements.md), o canal C7 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e as regras do `project.md`.

A U9 não tem rota, tabela nem segredo próprios: os controles abaixo valem para o código do navegador e para o uso que ele faz das rotas de U1, U3, U4, U5 e U7. A autorização continua no servidor, em cada rota.

## Classificação dos dados que o painel toca

| Dado | Classe | Tratamento no painel |
|---|---|---|
| Token de acesso | Segredo de sessão | Só em memória; vai no cabeçalho `Authorization`; nunca em URL, armazenamento do navegador, log ou console |
| Cookie de renovação | Segredo de sessão | `HttpOnly`; o app nunca o lê; só a rota de renovação o recebe |
| Endereço da carteira | Pessoal | Só a forma abreviada `walletAddressMasked` (4 + 4) |
| Bots, ordens, P&L e reconciliação da pessoa | Pessoal, financeiro simulado | Só os da pessoa do token; nunca em log ou console |
| `id` dos eventos do canal (`b…l…s…`) | Interno | Só devolvido em `Last-Event-ID`; nunca em log |
| Estado das fontes | Interno, igual para todos | Sem identificador de pessoa |

## Requisitos

| ID | Requisito | Meta e verificação | Origem |
|---|---|---|---|
| NFR6.1 | Token só em memória e só no cabeçalho | O canal e as rotas mandam o token só em `Authorization`; nenhum caminho grava o token em `localStorage`, `sessionStorage`, `IndexedDB`, cookie legível ou URL. Teste do cliente do canal que inspeciona a requisição; teste que confere os armazenamentos vazios depois de abrir o painel | BR5.1, project.md (Corrections, Forbidden) |
| NFR6.2 | Canal sem `EventSource` | O código do app não usa `EventSource`. Teste estático (busca no código-fonte do `apps/web/src`) que falha se achar | BR5.1, project.md (Corrections) |
| NFR6.3 | Nada sensível no console | Os avisos do painel no console (evento fora do formato, falha de rede) levam só o tipo do evento e o motivo, nunca `data`, `id`, token, carteira ou valores. Teste que captura o console nos testes do painel e procura token, `Bearer`, carteira e o `id` do evento | BR5.2, project.md (Forbidden), NFR6 |
| NFR6.4 | Mesma política de conteúdo | O canal usa o mesmo `connect-src` que já permite só o app e a API (U1); a U9 não acrescenta origem, script de terceiro nem `unsafe-eval`. O teste de cabeçalho existente continua passando | NFR6, security-design da U1 |
| NFR6.5 | Leitor de eventos sem execução de conteúdo | O leitor próprio do `text/event-stream` só faz `JSON.parse` do `data` e confere o envelope; nenhum texto recebido vira HTML sem escape nem código. Testes de unidade com envelope inválido, campos a mais, texto com marcação e pedaços partidos | Q4, BR5.2 |
| NFR6.6 | Token vencido não fica em laço | `401` na abertura do canal leva a uma única renovação; falha ou novo `401` fecha o canal e leva à entrada, sem novas tentativas com o token velho. Teste do cliente | BR5.5 |
| NFR5.1 | Nenhum identificador de pessoa ou tenant sai do painel | Nenhuma chamada do painel manda `tenantId` ou `userId` em caminho, consulta ou corpo. Teste do cliente que inspeciona todas as requisições da abertura e do "Pausar" | BR8.1, NFR5, team.md (Tenant no código) |
| NFR5.2 | Bot de outra pessoa não aparece | `404` em "Pausar" ou na última ordem tira o bot da tela e não mostra dado. Os testes negativos entre tenants das rotas usadas já existem na U5 e na U7 e continuam verdes | BR2.2, DP-1 |
| NFR4.1 | "Pausar" sem transição repetida | Clique repetido não gera segunda chamada; "Tentar de novo" reusa a mesma `Idempotency-Key`. Teste de componente | BR2.2, NFR4 |
| NFR4.2 | Valores sem ponto flutuante | Valores de P&L e quantidades são formatados a partir do texto decimal, sem `Number`/`parseFloat`. Teste com valores de 18 casas e teste estático no módulo de formatação do painel | BR3.5, project.md (Forbidden) |
| NFR7.1 | Carteira só abreviada | A casca mostra só `walletAddressMasked`; o endereço completo não aparece em tela, `title`, atributo nem console. Teste de componente | BR6.4, NFR7 |
| NFR2.1 | Canais por pessoa limitados | Teto de 5 canais abertos por pessoa no servidor (detalhe em [performance-requirements.md](performance-requirements.md)), para que uma conta não abra canais sem fim | Q3 |

## Ameaças consideradas

| Ameaça (STRIDE) | Controle |
|---|---|
| Roubo do token por script ou extensão que lê armazenamento do navegador (I) | NFR6.1, NFR6.4 |
| Token vazado em log, console ou URL de servidor intermediário (I) | NFR6.1, NFR6.3 |
| Evento malformado ou com marcação injetando conteúdo na tela (T) | NFR6.5 |
| Pessoa vê bot ou P&L de outra (I, E) | NFR5.1, NFR5.2; autorização no servidor de cada rota |
| Clique repetido criando transições a mais (T) | NFR4.1 |
| Muitas abas ou reconexões esgotando o servidor (D) | NFR2.1, NFR6.6; espera crescente de BR5.3 |
| Laço de reconexão com token vencido (D) | NFR6.6 |

## Conformidade

A U9 não muda a classificação nem a retenção de nenhum dado: só mostra o que as rotas de U3, U4, U5 e U7 já devolvem para a própria pessoa. Os textos continuam sem promessa de lucro (BR8.2, C-16) e com "MODO PAPER (simulado)" (DP-4). Nada do REAL existe nesta Unit.
