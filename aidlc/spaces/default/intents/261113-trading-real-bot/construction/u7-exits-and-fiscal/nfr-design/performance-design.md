# Projeto de desempenho e escala — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `construction/u7-exits-and-fiscal/nfr-requirements/performance-requirements.md` e `scalability-requirements.md` (U7-NFR3.x) e `nfr-design-questions.md` (Q5).

| Requisito | Como o projeto atende |
|---|---|
| U7-NFR3.1 | Ciclo de 30 s. A conferência na rede é uma única chamada `getTransaction` (`finalized`) por preenchimento. |
| U7-NFR3.2 | O `ConditionalCancelJob` roda a cada 1 min e marca `CANCELLING` em lote na primeira execução depois do kill switch. A confirmação acompanha o blockhash, que vale cerca de 60 a 90 s. |
| U7-NFR3.3 | `StreamingResponseBody` lê a M2 com `fetchSize` 500 e escreve cada linha no `OutputStream`, sem lista intermediária. |
| U7-NFR3.4 | Na criação, as chamadas à Jupiter e ao TxSigner usam tempos limite de 1 s cada. A barreira e a validação rodam em memória. |
| U7-NFR3.5 a U7-NFR3.7 | Migração 0022: tabela `real_conditional_order`, índice único parcial, índices por (carteira, estado) e (pessoa, criação), matriz de privilégios e gatilho de idade de 5 anos. |
| U7-NFR3.8 | A consulta de `streamEntries` filtra por tenant, pessoa e `created_at`, o que usa o `real_ledger_entry_tenant_created_idx`. |
| U7-NFR3.9 | O limitador por pessoa que já existe, com a chave `fiscal-export` e 10 por hora. |
