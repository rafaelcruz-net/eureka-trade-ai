# Requisitos de confiabilidade — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q3) e `construction/u7-exits-and-fiscal/functional-design/rules.md` (BR4, BR5, BR6).

| ID | Requisito | Verificação |
|---|---|---|
| U7-NFR4.1 | Toda mudança de estado da condicional é compare-and-set | teste de integração com dois workers |
| U7-NFR4.2 | O preenchimento é idempotente pela assinatura e grava na mesma transação do `FILLED` | teste de integração: a mesma leitura duas vezes gera um lançamento; uma queda entre o lançamento e o estado não deixa metade gravada |
| U7-NFR4.3 | Uma venda a mercado da mesma posição só segue depois de `CANCELLED` ou `FILLED` e da releitura da posição no ledger; com a posição zero, termina com `NOTHING_TO_SELL` | testes de integração das corridas: preenchimento × cancelamento; venda a mercado × preenchimento |
| U7-NFR4.4 | Os trabalhos de leitura (30 s) e de cancelamento (1 min) usam ShedLock com nome próprio, retomam do estado gravado e publicam batimento | teste de integração |
| U7-NFR4.5 | O cancelamento é tentado de novo a cada minuto até confirmar; nunca volta sozinho para `OPEN` | teste de integração com relógio fixo |
| U7-NFR4.6 | Com a Jupiter fora, nada muda de estado; a leitura retoma no próximo ciclo | teste com a Jupiter gravada respondendo 5xx e tempo esgotado |
| U7-NFR4.7 | O saque não depende de nenhum trabalho nem do estado da U7 | teste do saque com os trabalhos parados |
| U7-NFR4.8 | A reconciliação da U5 soma ao saldo do mint o saldo da conta de custódia de cada condicional `OPEN` ou `CANCELLING` desse mint (BR5.3b). Se a leitura da custódia falhar, a reconciliação daquela carteira fica `UNAVAILABLE` (rede não lida, como no `ReconciliationResult` da U5), nunca `DIVERGENT` | testes antes do código na `ReconciliationPolicy` (com custódia, sem custódia, leitura falhando) e teste de integração com o RPC gravado |
| U7-NFR4.9 | A anonimização é recusada com `409 OPEN_CONDITIONALS` enquanto a pessoa tem condicional não terminal (BR6.9), e nada é cancelado nem apagado por ela | teste antes do código no `closeForAnonymization`, por ser lógica de dados pessoais (`team.md`) |
| U7-NFR4.10 | O flag `memetrade.real.exits.enabled` vem desligado por padrão em todos os perfis (BR8.1, BR9.3). Desligado:<br>• a criação devolve `403 EXITS_DISABLED`;<br>• a subida para `AUTO_EXITS` é recusada com o mesmo código;<br>• quem já está em `AUTO_EXITS` continua nele;<br>• leituras, cancelamento, exportação e saque funcionam normalmente. | teste de configuração padrão e testes de rota com o flag desligado e ligado |
| U7-NFR4.12 | A exportação funciona com o kill switch ligado, sem liberação e com o REAL pausado (BR7.11) | teste de rota nos três estados |
| U7-NFR4.11 | `needsUserCancellation` fica verdadeiro com exatamente 15 minutos em `CANCELLING` e falso antes disso (BR6.7) | teste com relógio fixo em 14:59 e 15:00 |
