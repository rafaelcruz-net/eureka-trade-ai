# Requisitos de confiabilidade e integridade — U7 Ledger e P&L

Requisitos de integridade, reprodutibilidade, tolerância a falhas e recuperação da Unit `u7-ledger-pnl`, derivados do NFR4, NFR8 e NFR10 de [requirements.md](../../../inception/requirements-analysis/requirements.md), das regras BR2 a BR8 de [rules.md](../functional-design/rules.md) e do catálogo de cenários de [functional-spec.md](../functional-design/functional-spec.md).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR4.1 | Resultados exatos | 100% dos cenários C1 a C15 do catálogo dão exatamente os valores da tabela, escritos como testes antes do código (teste obrigatório de P&L determinístico) | NFR4, AC5.1.2, BR6.7 |
| NFR4.2 | Toda operação com chave e versão | 100% das ordens gravadas têm chave de idempotência e versão dos limites (`NOT NULL`); teste lê todas as ordens geradas pela suíte | NFR4, AC4.6.5 |
| NFR4.3 | Gravação tudo ou nada | Teste com falha injetada em cada gravação (preenchimento, cada lançamento, posição, maior patrimônio, evento): nada da operação fica, e a nova tentativa com a mesma chave grava tudo uma vez | BR2.1, AC4.11.2 |
| NFR4.4 | Soma exata | Com três tokens e dois bots, a soma das linhas é igual ao total, sem tolerância; teste de propriedade com semente fixa (500 sequências de operações): saldo = soma dos lançamentos, posição = soma dos preenchimentos, e a reconciliação sempre `RECONCILED` | BR6.5, BR8.2 |
| NFR4.5 | Maior patrimônio sem pico falso | Teste de concorrência entre marcação e preenchimento (barreira de início, 50 repetições): o maior patrimônio gravado é sempre um patrimônio que existiu num instante travado | BR7.3, BR2.8 |
| NFR8.1 | Preenchimento reproduzível | Cada `Fill` guarda referência, liquidez, `decimals`, fonte, horário e versão da política; recalcular só com esses dados dá o mesmo preço, valor bruto, taxa e lançamentos (teste com todos os cenários do catálogo) | NFR8, FR9.1 |
| NFR10.1 | Sem meta numérica de disponibilidade no beta local | A meta vem com a hospedagem, como no NFR10 | NFR10 |
| NFR10.2 | Falha técnica sem estado parcial | Falha de banco na execução desfaz o ciclo inteiro e a U7 não repete sozinha; erros de domínio são `NotFilled` gravados e confirmados, sem desfazer o ciclo | BR2.1, C15 |
| NFR10.3 | Fechamento pendente sobrevive a reinício | Depois de reiniciar a API com posições `CLOSE_PENDING`, a tarefa roda na partida e conclui cada uma uma única vez quando há preço; teste com duas instâncias da tarefa em paralelo | BR5.3, AC4.12.4 |
| NFR10.4 | Tarefas de execução única | Marcação, fechamento pendente e reconciliação usam ShedLock com nomes próprios; uma rodada em andamento faz a próxima ser pulada, sem acúmulo | BR8.1, padrão da U2 |
| NFR10.5 | Sem deadlock | Todas as operações travam conta, posições em ordem e maior patrimônio, nessa ordem; teste com execução, fechamento, fechamento pendente e marcação concorrentes (50 repetições) termina sem deadlock | BR2.8 |
| NFR10.8 | Espera por trava limitada | `execute`, `fund` e `closePositions` rodam com `lock_timeout` de 2 s e `statement_timeout` de 5 s na transação (`SET LOCAL`); estourar é falha técnica que desfaz o ciclo (NFR10.2), e o próximo ciclo tenta de novo. As tarefas usam `lock_timeout` de 1 s por conta: estourar pula aquela conta na rodada e registra o fato, sem falhar a rodada. Teste com uma transação que segura a trava da conta por 3 s: `execute` falha em cerca de 2 s e nada é gravado; a tarefa pula a conta | NFR3.1, NFR10.2 |
| NFR10.6 | Divergência nunca escondida | Com divergência injetada por fixture (lançamento faltando, valor diferente, órfão, posição divergente), a reconciliação dá `DIVERGENT` com a quantidade e o detalhe, e a tela mostra o aviso | BR8.2, BR8.4, AC5.2.4 |
| NFR10.7 | Dado de mercado indisponível | Com a C11 respondendo `unknown` ou desatualizado, `execute` dá `NotFilled(PRICE_UNAVAILABLE)`, o P&L marca `unknown` e parcial, e o maior patrimônio não muda; nada vira zero (teste obrigatório de `unknown`) | BR1.2, BR6.3, BR7.3 |
