# Perguntas do desenho não funcional — U4 Descoberta e avaliação

Contexto: os requisitos estão em [../nfr-requirements/](../nfr-requirements/) (revisão READY, com achados a resolver neste desenho, em [review-01.md](../nfr-requirements/reviews/review-01.md)). O design funcional está em [functional-spec.md](../functional-design/functional-spec.md). As perguntas abaixo cobrem só o que pede uma escolha sua.

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Achado R-02, ordem e limpeza do limite:** a autenticação vem antes do limite (sem sessão, `401` sem contar); contadores de pessoas sem requisição há mais de 2 minutos são removidos da memória.
- **Achado R-03, coleta lenta:** a meta de 10 s do NFR2.1 vale para a coleta sem falha; uma coleta ainda em andamento faz a próxima do mesmo agendamento ser pulada (bloqueio no banco), sem acúmulo.
- **Achado R-04, volume:** o teto de linhas do histórico passa a considerar 25 horas (300 intervalos de 5 minutos, até 4,8 milhões de linhas).
- **Achado R-05, frescor:** a meta de 60 s vale para o intervalo padrão de 30 s; outro intervalo exige recalcular a meta.
- **Achado R-06, papéis:** sem token, `401`; com token de convidado, `200`; com token de administrador numa rota do convidado, `403 FORBIDDEN_ROLE` (o token de administrador só vale em `/api/v1/admin/...`, regra comum do contrato).
- O recálculo das avaliações é disparado depois do commit de cada coleta, na mesma aplicação, e roda uma vez por vez com os gatilhos juntados.

## Q1. [Confiabilidade] Como a saúde das fontes aparece sem derrubar a saúde geral da API? (achado R-01, Major)

Hoje o healthcheck do Docker Compose e o `scripts/verify.sh` olham a saúde geral (`/actuator/health`). Se o indicador das fontes ficar `DOWN` com todas as fontes fora, a saúde geral vira `503` e a API é tratada como quebrada, embora continue respondendo (a lista dá `503 SOURCE_UNAVAILABLE` com explicação).

- A. O indicador das fontes nunca fica `DOWN`: com todas as fontes fora ele fica `DEGRADED`, que já responde HTTP 200 (como a U2 fez com as tarefas da trilha); o detalhe mostra o estado de cada fonte
- B. O indicador das fontes fica num grupo de saúde próprio (`/actuator/health/market`), fora da saúde geral usada pelo Compose; pode ficar `DOWN` ali sem afetar o resto
- X. Other (please specify)

[Answer]: A

## Q2. [Segurança] O limite de 60 requisições por minuto conta por rota ou no total? (achado R-02)

- A. Por rota: até 60 na lista e mais 60 no detalhe, por pessoa, por minuto
- B. No total: até 60 por pessoa por minuto, somando lista e detalhe
- X. Other (please specify)

[Answer]: A

## Q3. [Desempenho] Como a lista do scanner é montada a cada consulta?

A lista precisa juntar as duas fontes por campo (valor mais recente), filtrar, ordenar, contar `unknown` e paginar, com p95 ≤ 1 s para 2 000 tokens.

- A. Consulta direta: a cada pedido, uma consulta junta as observações atuais das fontes, escolhe o valor de cada campo e aplica filtros e ordem; nada novo é guardado
- B. Tabela de leitura: depois de cada coleta e recálculo, uma linha por token é regravada com o valor escolhido de cada campo, a fonte, o horário e o indicador; a lista filtra e ordena essa tabela com índices; o desatualizado continua calculado na leitura pelo horário
- X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
