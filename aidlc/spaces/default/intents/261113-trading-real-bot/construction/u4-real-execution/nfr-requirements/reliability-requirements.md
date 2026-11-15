# Requisitos de confiabilidade — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/functional-design/rules.md` (BR2.1, BR3.1, BR4.2, BR5.1, BR5.2, BR8.1, BR9.1), `inception/requirements-analysis/requirements.md` (NFR4) e `nfr-requirements-questions.md` (Q3).

| ID | Requisito | Verificação |
|---|---|---|
| U4-NFR4.1 | Uma transação por ordem: idempotencyKey e txSignature únicas; a ordem nunca é assinada de novo; os reenvios mandam a mesma transação | testes de repetição da mesma decisão, do mesmo `Idempotency-Key` e depois de reinício (o teste obrigatório do `team.md`) |
| U4-NFR4.2 | Toda transição é compare-and-set; quem perde a corrida não faz efeito | testes de concorrência entre o trabalho de prazo e o `POST` da sessão, e entre o cancelamento externo e o envio |
| U4-NFR4.3 | Queda em cada transição retoma do estado gravado: nenhuma reserva presa sem ordem viva, nenhuma ordem `CANCELLED` com a transação na rede, nenhuma segunda transação | testes de queda com PostgreSQL real: o trabalho é interrompido logo depois de cada gravação, o contexto Spring é fechado e um contexto novo sobe sobre o mesmo banco e retoma, como numa morte do processo. Isso inclui a queda entre o envio aceito pela rede (validador local) e a gravação do resultado no `OrderAttempt`: a retomada confirma a ordem pela assinatura e não reenvia outra transação |
| U4-NFR4.4 | `SENT` e o `OrderAttempt` são gravados antes da chamada de rede | teste de queda entre a gravação e o envio (S24) |
| U4-NFR4.5 | Ordem ambígua nunca é reenviada nem reassinada antes da conferência na rede | teste com RPC fora (S13, S14) |
| U4-NFR4.6 | Falha da Jupiter, do RPC ou do TxSigner falha a ordem e, repetida, pausa o REAL do usuário; o saque nunca é afetado | testes com cada fornecedor fora e o saque funcionando |
| U4-NFR4.7 | Os trabalhos usam ShedLock ou `SKIP LOCKED` e publicam batimento | testes com duas instâncias |
