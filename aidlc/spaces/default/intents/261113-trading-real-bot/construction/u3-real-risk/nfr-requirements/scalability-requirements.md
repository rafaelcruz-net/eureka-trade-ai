# Requisitos de escala e retenção — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes:
- `inception/requirements-analysis/requirements.md` (FR10.3: fundador e até 20 convidados; FR12, anonimização);
- `team.md` (Desempenho);
- o expurgo atual da barreira PAPER (`RiskEvaluationPurgeJob`, migração `0009`);
- `nfr-requirements-questions.md` (Q4).

| ID | Requisito | Valor |
|---|---|---|
| U3-NFR3.5 | Volume do piloto | 21 carteiras, até 5 bots por usuário e um ciclo a cada 30 s: até cerca de 4 avaliações por segundo no pico |
| U3-NFR3.6 | Índices | `real_risk_evaluation` por (bot, dia de `evaluatedAt`) e por `decisionId` único; `real_risk_reservation` por (carteira, estado); `real_risk_day` único por (carteira, dia); `real_blocked_token` único por (rede, mint) |
| U3-NFR3.7 | Retenção | ver a tabela abaixo |

## Retenção (U3-NFR3.7)

A barreira PAPER apaga só as avaliações aprovadas, com 30 dias. A REAL tem regra própria, porque as avaliações servem de prova do controle de risco sobre dinheiro real.

| Tabela | Prazo | Regra |
|---|---|---|
| `real_risk_evaluation` (aprovadas e recusadas) | 13 meses | trabalho de expurgo diário da U3 (ShedLock), com o pool de retenção que já existe; a migração da U3 dá ao papel `risk_retention` o DELETE nas tabelas REAL e cria um gatilho de idade como o da migração `0009`, para o papel só apagar linhas com mais de 13 meses; nunca apaga linha do dia UTC corrente nem do anterior, porque a contagem de BR4.2 e a repetição por `decisionId` dependem delas |
| `real_risk_reservation` em `HANDED_OVER` ou `EXPIRED` | 13 meses | mesmo trabalho |
| `real_risk_day` | 13 meses | mesmo trabalho |
| `real_blocked_token` | sem prazo | só sai por `unblockToken` |

**Dados pessoais:** as tabelas guardam só ids internos (tenant, usuário, bot, carteira), mint e valores, nunca endereço nem nome. Na anonimização (U5), os ids continuam sem ligação com a pessoa e não há dado a apagar. A declaração de retenção da U3 entra no registro de retenção que já existe, como a da barreira PAPER.

**Regras:**
- A trava é por carteira, então carteiras diferentes não esperam umas pelas outras.
- Não há meta de carga. Uma meta numérica só entra quando os requisitos definirem uma (`team.md`).
