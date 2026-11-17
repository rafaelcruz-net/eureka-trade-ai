# Requisitos de escala e retenção — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q5) e o padrão de retenção da U4.

| ID | Requisito | Verificação |
|---|---|---|
| U7-NFR3.5 | Piloto de até 21 carteiras, com no máximo uma condicional não terminal por posição | índice único parcial; teste |
| U7-NFR3.6 | Índices por (`bot_wallet_address`, `state`) e por (`user_id`, `created_at`) | teste de migração |
| U7-NFR3.7 | As condicionais finais ficam 5 anos, pelo prazo fiscal, e saem pelo expurgo do pool `risk_retention`, com gatilho de idade | teste de privilégios e de idade |
| U7-NFR3.8 | A exportação lê o ledger REAL por pessoa e período com o índice que já existe (`real_ledger_entry_tenant_created_idx`), nunca mais de 1 ano por pedido | teste do período: 366 dias recusado e 365 aceito; a consulta filtra por tenant, pessoa e `created_at`, conferida no repositório; sem teste de `EXPLAIN`, que fica instável com poucos dados |
| U7-NFR3.9 | Limite de 10 exportações por pessoa por hora, pelo limitador que já existe | teste da 11ª exportação |
