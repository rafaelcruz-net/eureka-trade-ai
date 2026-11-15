# Projeto de desempenho — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/nfr-requirements/performance-requirements.md` (U4-NFR3.1 a U4-NFR3.5) e `nfr-design-questions.md` (Q1, Q3, Q6).

| Requisito | Como o projeto atende |
|---|---|
| U4-NFR3.1 (3 s) | O `OrderDriver` encadeia cotação, instruções de swap, blockhash, assinatura e envio, renovando a posse em cada etapa. Grava o estado entre as etapas em transações curtas e não espera o próximo ciclo do trabalho. São cerca de 6 transações de banco de até 10 ms cada, perto de 60 ms no total, que cabem na folga do orçamento da fila (0,5 s para um p95 medido de 200 ms). Cada etapa tem um temporizador próprio. |
| U4-NFR3.2 (500 ms) | O `DecisionConsumer` roda a cada 200 ms com lote de até 20 decisões; o `OrderDriver` é acordado logo depois da criação da ordem. |
| U4-NFR3.3 (tempos limite) | `RestClient` com tempo limite de conexão e de leitura; RPC com troca de provedor da U1; TxSigner com 2 s. |
| U4-NFR3.4 (300 ms) | Listagem paginada por (usuário, criado em); P&L calculado em memória com uma leitura da M2 e preços em lote da M4. |
| U4-NFR3.5 (60 s) | O `ConfirmationJob` passa a ordem a `AMBIGUOUS` aos 60 s; o medidor da `SENT` mais antiga sai de uma consulta por índice parcial. |
