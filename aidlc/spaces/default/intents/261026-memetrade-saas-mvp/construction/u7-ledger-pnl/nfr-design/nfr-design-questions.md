# Perguntas do desenho não funcional — U7 Ledger e P&L

Contexto: os requisitos estão em [../nfr-requirements/](../nfr-requirements/) (revisão READY, com uma observação menor sobre o NFR3.5 em [review-02.md](../nfr-requirements/reviews/review-02.md)). O design funcional está em [functional-spec.md](../functional-design/functional-spec.md). A pergunta abaixo cobre só o que pede uma escolha sua.

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Observação do NFR3.5:** a meta vira "nenhuma rodada acima de 10 s em 60 rodadas seguidas", para não depender de um p95 sobre poucas amostras.
- **Saldo disponível:** calculado pela soma dos lançamentos do bot, com índice por (bot, mode); sem coluna de saldo na conta, para não abrir `UPDATE` além da matriz de privilégios.
- **Gatilho das tarefas:** marcação e fechamento pendente rodam depois de cada coleta de mercado (evento de coleta concluída da U4, já existente) e também a cada 60 s como reserva; reconciliação a cada 5 minutos.
- **Travas:** `SELECT ... FOR UPDATE` com `lock_timeout` 2 s e `statement_timeout` 5 s (`SET LOCAL`) nas operações do ciclo; 1 s nas tarefas, pulando a conta (NFR10.8).
- **Imutabilidade:** privilégios e gatilhos da matriz do NFR6.2, criados pela migração com o dono do esquema.

## Q1. [Desempenho] Como o P&L de um período é calculado a cada consulta?

O P&L precisa somar realizado e taxas por token e período e marcar as posições abertas, com p95 ≤ 1 s para até 10 000 preenchimentos por pessoa e períodos de até 366 dias.

- A. Consulta direta: a cada pedido, uma consulta agregada soma `fill` e os lançamentos `FEE` (com as compensações) por token no período, com índice por (pessoa, bot, `filled_at`); as posições abertas são lidas e marcadas na hora. Nada novo é guardado
- B. Tabela de resumo diário: cada preenchimento soma, na mesma transação, realizado e taxas numa linha por (bot, token, dia UTC); o P&L soma as linhas do período e corrige as pontas do período com os preenchimentos do primeiro e do último dia
- X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
