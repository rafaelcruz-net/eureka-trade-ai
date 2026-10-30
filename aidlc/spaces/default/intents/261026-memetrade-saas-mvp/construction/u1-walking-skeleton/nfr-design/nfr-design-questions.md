# Perguntas — NFR Design da U1 (esqueleto de ponta a ponta)

Objetivo: escolher os padrões que cumprem os requisitos não funcionais da U1 ([performance-requirements.md](../nfr-requirements/performance-requirements.md), [security-requirements.md](../nfr-requirements/security-requirements.md), [scalability-requirements.md](../nfr-requirements/scalability-requirements.md), [reliability-requirements.md](../nfr-requirements/reliability-requirements.md), [observability-requirements.md](../nfr-requirements/observability-requirements.md), [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md)), a partir dos fluxos de [functional-spec.md](../functional-design/functional-spec.md) e dos contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

Já decidido e que não volta a ser perguntado: as metas de NFR3.1 (p95 300 ms na API) e NFR3.2 (2 s na tela); contadores do limite de tentativas na memória do processo; Ed25519 nativo; logs JSON, saúde e métricas sem rastreamento distribuído; ambiente local sem TLS. A revisão dos requisitos apontou três lacunas que este estágio fecha: a convivência da carga de NFR3.1 com o limite de NFR6.5 e a origem do IP (Q1, Q2), as credenciais de CORS na verificação (Q7) e a entrega da auditoria (Q3).

## Q1. [Segurança] De onde o limitador de tentativas tira o IP do cliente?

O navegador chama a API direto (no ambiente local, `localhost` em outra porta), sem proxy no meio. Confiar em cabeçalhos encaminhados sem proxy conhecido deixa qualquer cliente trocar de IP a cada pedido.

A. Do endereço da conexão direta; cabeçalhos `X-Forwarded-For` são ignorados. Quando houver proxy ou balanceador na hospedagem, passa a valer só o cabeçalho escrito por proxies de uma lista configurada.
B. Do `X-Forwarded-For` desde já, pegando o primeiro endereço.
X. Other (please specify)

[Answer]: A

## Q2. [Desempenho] Como o teste de carga de NFR3.1 convive com o limite de 10 pedidos por minuto por IP?

Os 20 usuários simulados saem do mesmo IP. Com o limite ligado, desafio e verificação recebem `429` antes de medir o tempo.

A. O limite é uma propriedade de configuração com valor padrão 10; só o perfil de teste de carga o eleva. Um teste falha o build se o perfil padrão tiver outro valor, e os testes de `429` rodam no perfil padrão.
B. O teste de carga faz o login de cada usuário uma vez, espaçado para ficar abaixo do limite, e mede a carga contínua só na lista; o tempo do login é medido à parte, com poucos pedidos.
C. Cada usuário simulado usa um IP de loopback diferente (127.0.0.x).
X. Other (please specify)

[Answer]: A

## Q3. [Confiabilidade] Como o relay leva a tabela de saída até a trilha de auditoria?

O contrato C9 grava o evento na mesma transação da ação. O relay precisa entregar sem perder e sem duplicar na trilha.

A. Relay agendado a cada 1 s, lotes de até 100 entradas reservadas com bloqueio que pula linhas já reservadas; entrega ao menos uma vez, e a trilha ignora o evento repetido pelo identificador do evento (chave única).
B. Notificação do banco a cada inserção, com varredura a cada 10 s como reserva; mesma desduplicação pelo identificador.
X. Other (please specify)

[Answer]: A

## Q4. [Desempenho] Com que intervalo a fonte simulada coleta?

O NFR1.2 pede coleta a cada 30 s ou menos, para a idade exibida ficar até 60 s.

A. A cada 15 s, com folga para uma coleta falhar sem passar de 60 s.
B. A cada 30 s, o limite do requisito.
X. Other (please specify)

[Answer]: A

## Q5. [Desempenho] Como a lista do scanner chega a p95 de 150 ms na consulta?

São até 500 tokens com 5 campos cada (NFR3.4), uma única instância de API e 20 usuários.

A. Consulta direta ao PostgreSQL com índice pela ordem da lista e paginação por cursor; pool de conexões padrão do Spring com 10 conexões; sem cache na aplicação na U1.
B. Cache em memória da primeira página por 5 s, além do índice.
X. Other (please specify)

[Answer]: A

## Q6. [Confiabilidade] Por quanto tempo guardar as respostas de idempotência e como limpar?

A OQ-6 do Contract Design deixou 24 horas como provisório. Na U1 só o desafio guarda resposta, e ele expira em 5 minutos.

A. 24 horas para todas as rotas, com limpeza agendada a cada hora que apaga em lotes os registros vencidos.
B. Prazo por rota: o desafio guarda só até expirar (5 min); as demais rotas, 24 horas. Mesma limpeza agendada.
X. Other (please specify)

[Answer]: B

## Q7. [Segurança] A verificação do login precisa de credenciais no CORS?

O contrato permite credenciais só na renovação e na saída. A verificação devolve o cookie de renovação em `Set-Cookie`, e o navegador só guarda esse cookie numa resposta de outra origem quando o pedido vai com credenciais e a API as permite.

A. Sim: credenciais também na verificação, e o contrato C1 é atualizado com o motivo.
B. Não: a verificação devolve só o token de acesso, e o app chama a renovação logo em seguida para receber o cookie.
X. Other (please specify)

[Answer]: A

## Q8. [Segurança] Como montar a política de conteúdo (CSP) do app do convidado?

O Next.js gera scripts embutidos na página. A política precisa permitir esses scripts sem abrir para qualquer script embutido.

A. CSP com nonce gerado a cada requisição pelo middleware do Next.js; `script-src` só com o nonce e `'strict-dynamic'`; `connect-src` só com a API.
B. CSP estática que permite scripts embutidos (`'unsafe-inline'`), mais simples, revista antes do beta multiusuário.
X. Other (please specify)

[Answer]: A

## Q9. [Segurança] Onde o endereço de carteira é mascarado nos logs e métricas?

O NFR7.1 exige 4 + 4 caracteres fora da trilha, e o NFR7.3 varre os logs dos testes.

A. Na origem: um tipo próprio para o endereço que, ao virar texto, já sai mascarado; o valor completo só sai por um método explícito usado na trilha e no banco.
B. Na saída: um filtro no formatador de logs que procura endereços por padrão de texto e mascara.
C. Os dois: tipo próprio na origem e filtro na saída como segunda barreira.
X. Other (please specify)

[Answer]: C

## Q10. [Observabilidade] Como as métricas ficam visíveis no ambiente local?

O NFR9 pede métricas expostas; alertas com notificação ficam para a hospedagem.

A. Endpoint de métricas no formato Prometheus numa porta de gestão separada, sem coletor no Docker Compose; o script de verificação lê a saúde por essa porta.
B. Endpoint de métricas mais Prometheus e Grafana no Docker Compose, com um painel da U1.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- O limitador usa o endereço da conexão direta e ignora `X-Forwarded-For`; com proxy na hospedagem, vale só o cabeçalho de proxies de uma lista configurada (Q1).
- O limite de 10 por minuto é uma propriedade com valor padrão 10; só o perfil de teste de carga o eleva, um teste falha o build se o padrão mudar e os testes de `429` rodam no perfil padrão (Q2).
- Relay da auditoria a cada 1 s, em lotes de até 100 com bloqueio que pula linhas reservadas; entrega ao menos uma vez e a trilha ignora o evento repetido pelo identificador (Q3).
- A fonte simulada coleta a cada 15 s (Q4).
- A lista consulta o PostgreSQL direto, com índice pela ordem e cursor, pool de 10 conexões e sem cache na aplicação (Q5).
- Idempotência por rota: o desafio guarda a resposta só até expirar (5 min), as demais rotas 24 horas, com limpeza agendada a cada hora em lotes (Q6).
- CORS com credenciais também na verificação, porque ela devolve o cookie de renovação; o contrato C1 é atualizado com o motivo (Q7).
- CSP do app do convidado com nonce por requisição no middleware do Next.js, `script-src` com o nonce e `'strict-dynamic'`, `connect-src` só com a API (Q8).
- Endereço de carteira mascarado na origem por um tipo próprio e, como segunda barreira, por um filtro no formatador de logs (Q9).
- Métricas no formato Prometheus numa porta de gestão separada, sem coletor no Docker Compose; o script de verificação lê a saúde por essa porta (Q10).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
