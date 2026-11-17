# Requisitos de desempenho — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q1) e `construction/u7-exits-and-fiscal/functional-design/rules.md` (BR5, BR6, BR7).

| ID | Requisito | Verificação |
|---|---|---|
| U7-NFR3.1 | O preenchimento entra no ledger em até 90 s depois de `finalized` na rede (ciclo de 30 s mais a conferência) | teste de integração com relógio fixo e respostas gravadas |
| U7-NFR3.2 | Com o kill switch ligado, toda condicional `OPEN` fica `CANCELLING` em até 2 min, e o cancelamento confirma em até 5 min com a rede saudável | teste de integração com relógio fixo |
| U7-NFR3.3 | A exportação é gerada em fluxo: o servidor escreve linha a linha e nunca monta o arquivo inteiro na memória. Com 20 mil lançamentos semeados, a resposta termina em até 10 s no ambiente de teste da CI | teste de integração que confere a escrita em fluxo (a primeira linha sai antes de ler a última) e o tempo, com falha acima de 10 s |
| U7-NFR3.4 | A criação até a sessão de assinatura aberta leva no máximo 3 s com a Jupiter gravada | teste de integração com relógio de parede e falha acima de 3 s, rodado 20 vezes (máximo, não p95) |
