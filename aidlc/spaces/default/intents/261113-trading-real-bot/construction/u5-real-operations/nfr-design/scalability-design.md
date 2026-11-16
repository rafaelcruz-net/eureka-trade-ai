# Projeto de escala e retenção — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/nfr-requirements/scalability-requirements.md` (U5-NFR3.6 a U5-NFR3.9) e o padrão da U3.

| Requisito | Como o projeto atende |
|---|---|
| U5-NFR3.6 | Com cerca de 735 mil reconciliações por ano, os índices por (carteira, início) mantêm as leituras constantes. |
| U5-NFR3.7 | O `RuntimeSettingsGuard` passa a aceitar, no worker, scheduler de 25 a 32 e conexões de 33 a 48, e na API mínimo de 22. O teste de contagem dos métodos `@Scheduled` cobre os dois papéis. O `docker-compose.yml` e o `ComposeFilesTest` mudam junto. |
| U5-NFR3.8 | Índices da migração 0021, mais um índice único parcial (usuário, rede) para liberações não revogadas. |
| U5-NFR3.9 | Migração 0021 (a seguinte à 0020): tabelas novas, colunas anuláveis, `PauseReason` e `RealRejectionDetail` ampliados (com os CHECKs de 0018 e 0019), `decision.mode` com `REAL`, gatilho da trilha substituído e função de redação. O `RealOpsPurgeJob`, na API, roda às 05:00 UTC com nome próprio no ShedLock e o pool `risk_retention`. O gatilho de idade recusa apagar linha mais nova que o prazo, e a reconciliação ligada a uma divergência nunca é apagada antes dela (chave estrangeira com `ON DELETE RESTRICT`). |
