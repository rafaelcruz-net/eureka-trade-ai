# Projeto de escala e retenção — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/nfr-requirements/scalability-requirements.md` (U3-NFR3.5 a U3-NFR3.7), `nfr-design-questions.md` (Q6) e o expurgo PAPER atual (`RiskEvaluationPurgeJob`, migração `0009`).

| Requisito | Como o projeto atende |
|---|---|
| U3-NFR3.5 (volume do piloto) | A trava é por carteira, então só as decisões da mesma carteira esperam. Com 4 avaliações por segundo e 150 ms cada, a fila por carteira fica perto de zero. |
| U3-NFR3.6 (índices) | `real_risk_evaluation`: único em `decision_id` e índice em (`bot_id`, `evaluated_day`, `side`, `outcome`). `real_risk_reservation`: único em `decision_id`, índice em (`bot_wallet_id`, `state`) e índice parcial em `state` para as linhas `OPEN` (medidor e vencimento). `real_risk_day`: chave (`bot_wallet_id`, `day`). `real_blocked_token`: chave (`chain`, `mint`). |
| U3-NFR3.7 (retenção) | `RealRiskPurgeJob`, diário às 04:00 UTC, na API (ver o desvio em `logical-components.md`), com nome próprio no ShedLock e o pool `risk_retention`, que já serve o expurgo PAPER das 03:30. O gatilho de idade cobre todas as linhas das reservas, inclusive as `OPEN`. Apaga em lotes de 1.000 as linhas com mais de 13 meses de `real_risk_evaluation`, das reservas fechadas e de `real_risk_day`. A migração dá ao papel o DELETE nessas três tabelas e cria um gatilho `BEFORE DELETE` que recusa linha com menos de 13 meses, como o da `0009`. Isso protege também os dois últimos dias UTC. A declaração de retenção da U3 entra no registro que já existe. |
