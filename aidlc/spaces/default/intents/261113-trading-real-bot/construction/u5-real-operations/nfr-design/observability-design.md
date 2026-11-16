# Projeto de observabilidade — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/nfr-requirements/observability-requirements.md` (U5-NFR5.1 a U5-NFR5.13) e o padrão da U4.

| Requisito | Como o projeto atende |
|---|---|
| U5-NFR5.1 a U5-NFR5.6 | `RealOpsMetrics` com Micrometer. Os rótulos vêm dos enums da tabela do requisito, e os contadores são registrados com zero na subida. O `RealOpsGaugesJob` (worker, a cada 30 s) lê do banco os medidores de liberações, divergências, adiadas, kill switch, restauração e comandos pendentes. O histograma da reconciliação tem os buckets do requisito. |
| U5-NFR5.7 a U5-NFR5.13 | Arquivo `infra/observability/real-ops-alerts.yml`, conferido pelo passo do `promtool` que já existe na CI, que precisa ganhar o nome do arquivo novo (texto para o responsável). Alarmes em `infra/terraform/modules/observability/real-ops.tf` (`Eureka/RealOps`), com `RealOpsAlarmParityTest`. O `restore-check` só alerta com a conferência pendente. |
| Auditoria | Cada mudança de liberação, kill switch, divergência, teto, bloqueio e anonimização gera evento REAL pela saída que já existe, na mesma transação. |
