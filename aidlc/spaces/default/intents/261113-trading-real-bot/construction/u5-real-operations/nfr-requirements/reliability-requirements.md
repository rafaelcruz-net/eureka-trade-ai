# Requisitos de confiabilidade — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/functional-design/rules.md` (BR1.4, BR2.1 a BR2.5, BR3.1, BR4.1, BR6.2), `inception/requirements-analysis/requirements.md` (NFR4) e `nfr-requirements-questions.md` (Q3).

| ID | Requisito | Verificação |
|---|---|---|
| U5-NFR4.1 | Liberação, kill switch, divergência e comandos pendentes mudam por compare-and-set e chave de idempotência | testes de corrida: dois pedidos de liberação no limite de 20; ligar e desligar o kill switch ao mesmo tempo |
| U5-NFR4.2 | Os trabalhos (portões, reconciliação, cancelamento do kill switch, reenvio ao TxSigner, conferência de restauração) usam ShedLock, são idempotentes e retomam depois de reinício | testes com duas instâncias e com reinício |
| U5-NFR4.3 | Falha da CI ou do RPC nunca libera nem abre divergência: portão vermelho ou reconciliação `UNAVAILABLE` | testes com cada dependência fora |
| U5-NFR4.4 | A conferência de restauração bloqueia ordens novas até terminar e nunca bloqueia o saque | teste com RPC fora na subida |
| U5-NFR4.5 | O saque nunca depende da U5; a única regra que a U5 acrescenta a ele é o limite pelo menor saldo com divergência ou restauração | teste de saque com cada trabalho da U5 parado |
| U5-NFR4.6 | A compensação nunca edita o ledger: só acrescenta lançamentos, iguais à diferença medida | teste do ledger só de inclusão e da recusa de valor diferente |
