# Perguntas do NFR Design — U6 Barreira de limites

Os requisitos estão em [../nfr-requirements/](../nfr-requirements/) e o design funcional em [functional-spec.md](../functional-design/functional-spec.md).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Sem cache.** A avaliação lê tudo uma vez por chamada e não guarda estado em memória (NFR10.5).
- **Índices.** Um índice único (tenant, modo, bot, decisão) atende a procura da decisão. Um índice (tenant, pessoa, modo, bot, horário decrescente, recusa) atende a rota C21. Um índice parcial em `evaluated_at` só das aprovadas atende o expurgo, como pediu a revisão dos requisitos.
- **Lote do expurgo.** O tamanho do lote é fixado pelo cenário de carga `u6`, com o expurgo rodando junto com o ciclo. O ponto de partida é 1.000 linhas por transação.
- **Consequências do expurgo.** São registradas como alteração formal da BR4.3 para a geração de código:
  - uma aprovada citada por uma recusa por duplicidade nunca é apagada;
  - um bot com todas as avaliações apagadas responde 404 na rota de recusas, e a U5 trata isso como "nenhuma recusa";
  - uma decisão antiga reavaliada é contida pela chave de idempotência da U7, com teste.

## Q1. [Segurança] Quem pode apagar as avaliações aprovadas antigas?

A revisão dos requisitos apontou que hoje o papel do banco usado pela aplicação ganharia permissão de apagar qualquer avaliação aprovada com mais de 30 dias, não só a tarefa de expurgo.

A. Um papel de banco próprio para o expurgo, com permissão só de apagar em `order_evaluation`. O papel da aplicação fica sem nenhum DELETE nas tabelas da U6, e um gatilho confere a idade, o resultado e a ausência de recusa.
B. O papel da aplicação ganha o DELETE, limitado pelo mesmo gatilho. Não há papel novo.
X. Other (please specify)

[Answer]: A

## Q2. [Observabilidade] O expurgo deixa registro na trilha de auditoria?

O expurgo é a única tarefa da U6 que atravessa todos os tenants e apaga dados.

A. Cada rodada grava um evento `RISK_EVALUATIONS_PURGED` com ator `system`, sem tenant, com o total apagado e o corte de data. Nenhum identificador de pessoa ou bot vai no evento.
B. Só um log `INFO` com o total, sem evento na trilha.
X. Other (please specify)

[Answer]: B

## Q3. [Confiabilidade] Como o indicador de saúde da U6 funciona?

O indicador depende da última rodada do expurgo, que roda numa única instância por vez.

A. O horário e o resultado da última rodada ficam numa tabela de controle da U6, lida pelas duas instâncias (API e worker). O indicador fica fora dos grupos de liveness e readiness: uma falha de limpeza não derruba a API nem o worker, só aparece como `DOWN` no detalhe da saúde.
B. A mesma tabela de controle, mas o indicador entra no grupo de readiness: o processo é marcado como não pronto depois de 26 horas sem rodada com sucesso.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
