# Requisitos de desempenho — U3 `u3-reacceptance`

Conversa: português (Brasil). Fontes: `construction/u3-reacceptance/functional-design/` (BR3.1, BR3.11), `inception/requirements-analysis/requirements.md` (NFR3) e `nfr-requirements-questions.md` (Q1).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.1 | `POST /api/v1/terms/acceptances` responde com p95 ≤ 500 ms com 20 convidados simultâneos | cenário de carga novo `reacceptance` em `scripts/load/` (20 usuários: cada um fica desatualizado, recebe o `403`, aceita e repete uma rota), com reprovação acima de 500 ms; a opção `reacceptance` entra no `load-test.yml` pelo responsável do projeto |
| NFR3.2 | A trava acrescenta no máximo 5 ms (p95) a cada requisição do convidado, com uma consulta pelo índice único | o tempo da própria consulta da trava é medido por um temporizador Micrometer (`memetrade.terms.reacceptance.check`), e o cenário `reacceptance` reprova com p95 desse temporizador acima de 5 ms; um teste de integração confere com `EXPLAIN` que a consulta usa o índice |
| NFR3.3 | A tela `/novo-aceite` carrega com p95 ≤ 2 s | cenário de carga `legal` (páginas) |
