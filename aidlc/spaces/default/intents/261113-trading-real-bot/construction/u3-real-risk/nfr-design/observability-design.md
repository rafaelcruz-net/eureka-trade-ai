# Projeto de observabilidade — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/nfr-requirements/observability-requirements.md` (U3-NFR5.1 a U3-NFR5.12) e as regras da U1 em `infra/observability/real-alerts.yml`.

| Requisito | Como o projeto atende |
|---|---|
| U3-NFR5.1 a U3-NFR5.7 | `RealRiskMetrics` registra os contadores, o medidor de reservas (consulta por estado a cada 30 s) e os histogramas com os buckets definidos, usando o Micrometer que já existe. Os rótulos vêm de enums: código, detalhe, lado, resultado e dependência. Nenhum texto livre. |
| U3-NFR5.8 | Cada trabalho publica `real_risk_job_last_success_seconds{job}` ao terminar bem e mede `real_risk_job_seconds{job}`. Nomes dos trabalhos: `day`, `daily-loss`, `reservation-expiry`, `purge`. |
| U3-NFR5.9 a U3-NFR5.12 | Arquivo novo `infra/observability/real-risk-alerts.yml`, no mesmo formato do da U1, com as severidades do requisito e `absent()` para o trabalho que nunca publicou. É conferido pelo `promtool` na mesma etapa que já confere os outros arquivos. |

**Nuvem:** os alarmes equivalentes ficam para o Infrastructure Design, junto da decisão de como as métricas chegam ao CloudWatch.

**Auditoria:** cada recusa e cada mudança da lista de bloqueio geram evento REAL pela saída que já existe, com o mint no campo tipado.
