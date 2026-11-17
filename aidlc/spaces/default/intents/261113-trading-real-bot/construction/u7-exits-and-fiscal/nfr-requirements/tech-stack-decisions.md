# Decisões de tecnologia e testes — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q6, Q7) e `team.md` (Testing Posture).

| ID | Decisão | Motivo |
|---|---|---|
| U7-NFR8.1 | Nenhuma dependência nova de produção. O CSV usa um gravador próprio (BOM, `;`, vírgula decimal, escape de BR7). A Jupiter usa o `RestClient` que já existe, e a política do TxSigner usa o leitor de transação que já existe | padrão do projeto |
| U7-NFR8.2 | A gravação real estende o `infra/solana-fixtures/record.sh`. O SDK Solana fica só no escopo de teste | `project.md` |
| U7-NFR8.3 | Testes escritos antes do código: a `TriggerPolicy` (depois da gravação), os estados e a idempotência das condicionais, o lançamento do preenchimento, a prevenção de venda dupla e o CSV com a conferência de BR7.6 | `team.md` |
| U7-NFR8.4 | Testes escritos depois do código: rotas, telas, métricas e adaptador | `team.md` |
| U7-NFR8.5 | E2E no validador local: o spec `e2e/real-exports` (exportação depois do `real-swap`) e as condicionais pela tela com o adaptador simulado (criar, preencher simulado, cancelar pelo kill switch, selo de estado). Com a gravação real, o spec da condicional passa a usar a transação real | `team.md` |
| U7-NFR8.6 | Piso de 80% de linhas na API, no web e no TxSigner | `team.md` |
| U7-NFR10.1 | As datas trafegam em UTC; o CSV traz `data_utc` e `data_local` (fuso da pessoa) | `project.md` |
