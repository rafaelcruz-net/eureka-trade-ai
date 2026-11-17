# Projeto de escala e retenção — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `construction/u7-exits-and-fiscal/nfr-requirements/scalability-requirements.md` (U7-NFR3.5 a U7-NFR3.9) e o padrão de retenção da U4 e da U5.

| Requisito | Como o projeto atende |
|---|---|
| U7-NFR3.5 | Índice único parcial em (`bot_wallet_address`, `position_mint`) `WHERE state IN ('PENDING_SIGNATURE','OPEN','CANCELLING')`. Para 21 carteiras, os trabalhos leem poucas dezenas de linhas por ciclo. |
| U7-NFR3.6 | Índices em (`bot_wallet_address`, `state`) e (`user_id`, `created_at`), criados na migração 0022. |
| U7-NFR3.7 | O `RealExitsPurgeJob`, na API e com nome próprio no ShedLock, roda pelo pool `risk_retention` e apaga as condicionais finais com mais de 5 anos. O gatilho de idade recusa apagar linhas mais novas, e a matriz de privilégios segue a da U4. |
| U7-NFR3.8 | A consulta da exportação usa o `real_ledger_entry_tenant_created_idx` e o período limitado a 1 ano (`performance-design.md`). |
| U7-NFR3.9 | O limitador por pessoa que já existe, com a chave `fiscal-export` e 10 por hora. |
| Pools | Os dois trabalhos novos do worker (leitura e cancelamento) e o de medidores entram no `RuntimeSettingsGuard`: o scheduler do worker vai de 25 para 28, e o pool de conexões, de 33 para 36. O expurgo entra na contagem da API. O `docker-compose.yml` e o `ComposeFilesTest` mudam junto. |
