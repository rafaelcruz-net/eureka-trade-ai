# Requisitos de escala e retenção — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `inception/requirements-analysis/requirements.md` (FR10.3), o `RuntimeSettingsGuard` atual (worker com 19 métodos `@Scheduled`; API com mínimo de 21), o padrão de retenção da U3 e da U4 e `nfr-requirements-questions.md` (Q1, Q6).

| ID | Requisito | Valor |
|---|---|---|
| U5-NFR3.6 | Volume | 21 carteiras no piloto, até 20 liberações de convidados na mainnet. Cerca de 96 reconciliações por carteira por dia: perto de 2 mil linhas por dia e 735 mil por ano |
| U5-NFR3.7 | Pools | **Worker:** 6 trabalhos novos — portões (5 min, cobre também a reconferência das `BLOCKED`), reconciliação (15 min), cancelamento do kill switch (1 min), reenvio ao TxSigner (1 min), conferência de restauração (na subida e a cada 1 min enquanto pendente) e medidores (30 s). Com isso o worker vai a 25 métodos `@Scheduled`: scheduler de 25 a 32 e conexões de 33 a 48 (4 ciclos + 4 de reserva + 25). O executor da reconciliação (4 threads) usa as conexões de reserva. **API:** o expurgo vai a 22 métodos, e o mínimo do scheduler passa de 21 para 22. O teste de contagem acompanha os dois |
| U5-NFR3.8 | Índices | liberação por (usuário, rede, estado) e por (estado, `effectiveAt`); reconciliação por (carteira, início); divergência única aberta por carteira; comandos pendentes por (confirmado, criado) |
| U5-NFR3.9 | Retenção | ver a tabela abaixo |

## Retenção (U5-NFR3.9)

| Dado | Prazo | Base |
|---|---|---|
| Divergências, as reconciliações ligadas a elas, os lançamentos compensatórios e as liberações | 5 anos | Provam a correção do ledger REAL e a autorização de cada pessoa para operar dinheiro real, para disputas e para a obrigação de guardar as operações (a mesma base das ordens da U4: LGPD, art. 7º, II, e art. 16, I) |
| Reconciliações limpas, sem divergência ligada | 13 meses | Operacional |
| Comandos ao TxSigner confirmados | 13 meses | Operacional |

**Anonimização:** o `user_id` continua nas linhas, mas sem ligação com a pessoa, como nas outras tabelas.

**Expurgo:** segue a matriz de privilégios e o gatilho de idade da U3. Uma reconciliação ligada a uma divergência nunca é apagada antes dela.
