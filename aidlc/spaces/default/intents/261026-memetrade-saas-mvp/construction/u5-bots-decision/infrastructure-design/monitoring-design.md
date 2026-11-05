# Desenho de monitoramento — U5 Bots e decisão

Implementa o [observability-design.md](../nfr-design/observability-design.md) no ambiente local. A U5 só tem métricas de saúde e de erro, e não há alertas no beta.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| Duração do ciclo e de cada etapa | Ciclo do perfil `loadtest`, gravado no relatório | Ciclo com p95 ≤ 2 s; barreira mais execução com p95 ≤ 500 ms | NFR2.1, NFR2.3 |
| Fim da fatia | Relatório `u5` | ≤ 120 s depois de `slotStart + 60 s`; zero recusas por frescor | NFR2.2 |
| `http_server_requests_seconds` das rotas C4 | Micrometer | p95 ≤ 1 s | NFR3.1 |
| Pico de conexões do worker | Métrica do Hikari, só lida no teste de carga | ≤ 14; nenhuma espera acima de 1 s | NFR2.4 |
| `bots.cycle.errors{cause}` | Micrometer (worker) | Zero no cenário sem falha injetada | NFR9.2 |
| `health` `botCycles` | Actuator do worker | `UP`; `DOWN` com `NO_SLOT_COMPLETED` | NFR9.1 |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Nenhum no beta local | — | — | Definidos com a hospedagem |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| p95 do ciclo | ≤ 2 s | Cenário `u5`, 6 fatias |
| p95 das rotas C4 | ≤ 1 s | Cenário `u5`, 20 usuários |
| Tela S5 | p95 ≤ 2 s | Cenário `u5`, chamadas em paralelo |
| Evento no canal ao vivo | ≤ 4 s depois do commit | Teste de integração e cenário `u5` |

## Logs & Tracing

| Item | Configuração |
|---|---|
| Correlação | `cycleId` no MDC do worker; filtro da U1 nas rotas |
| Formato | JSON, igual nos dois processos |
| Conteúdo | Só identificadores; `LogScanIT` cobre um ciclo e os comandos |
| Tracing distribuído | Não usado no beta |
