# Perguntas de NFR Requirements — U5 `u5-anonymization`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Desempenho da anonimização

A. A anonimização de uma conta com 20 bots, 100 sessões e 50 feedbacks termina em até 2 s (p95), medida em teste de integração com 20 repetições. Ela é rara e feita pelo administrador, então não há cenário de carga próprio. (recomendado)
B. Sem meta.
X. Other (please specify)

[Answer]: A

## Q2 — Observabilidade

A. Itens:
- o contador `memetrade_accounts_anonymized_total`, sem rótulo de pessoa;
- o evento `ACCOUNT_ANONYMIZED` na trilha, só com contagens;
- um log `INFO`, só com as contagens e o identificador do pedido;
- uma falha registra `ERROR` com a classe da exceção, e o pedido fica `IN_PROGRESS`.

(recomendado)
B. Só o evento.
X. Other (please specify)

[Answer]: A
