# Requisitos de confiabilidade — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/functional-design/rules.md` (BR1.3, BR4.3 a BR4.5, BR6.1, BR6.2, BR7.1), `functional-spec.md` (F1, F2), `inception/requirements-analysis/requirements.md` (NFR4) e `nfr-requirements-questions.md` (Q3).

| ID | Requisito | Verificação |
|---|---|---|
| U3-NFR4.1 | Falha fechada. A compra lê a carteira (M1), o ledger (M2), o mercado (M4) e a avaliação; a venda lê só M1 e M4. Um erro técnico em qualquer leitura nunca aprova. A decisão do bot fica para o próximo ciclo. A venda pedida pelo usuário devolve 503 e o usuário tenta de novo; o saque continua disponível | testes com cada dependência falhando |
| U3-NFR4.2 | Uma avaliação repetida com o mesmo `decisionId` devolve o resultado gravado, sem contar de novo nem criar outra reserva. Na venda, que não tem trava, uma violação do `decisionId` único também é tratada como repetição | testes de repetição (S5c), inclusive duas vendas simultâneas com o mesmo `decisionId` |
| U3-NFR4.3 | Duas avaliações simultâneas da mesma carteira nunca passam juntas do teto nem dos limites | teste de concorrência com PostgreSQL real (S5) |
| U3-NFR4.4 | Os trabalhos F3, F4 e F5 usam ShedLock, são idempotentes e retomam depois de reinício | testes dos trabalhos rodando duas vezes |
| U3-NFR4.5 | A foto do dia, as reservas e a pausa `DAILY_LOSS` ficam no banco e sobrevivem a reinício | teste de integração |
| U3-NFR4.6 | Avaliação, auditoria, reserva e pedido de pausa ficam na transação do ciclo (padrão da barreira PAPER). Se o ciclo desfizer a transação, a decisão não existiu e nada fica. A pausa `DAILY_LOSS` perdida assim é refeita pelo trabalho F4 em até 1 min | teste com o ciclo desfeito e o F4 pausando a carteira |
| U3-NFR4.7 | O saque não passa pela barreira e nunca é afetado por ela | teste do saque com a barreira falhando |
| U3-NFR4.8 | A espera pela trava não aborta a transação do ciclo: passado 1 s, `evaluate` lança erro técnico sem erro no banco, e a U4 decide desfazer o ciclo | teste com a trava presa por outra transação |
