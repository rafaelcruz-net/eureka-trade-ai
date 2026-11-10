# Requisitos de observabilidade — U2 `u2-retention`

Conversa: português (Brasil). Fontes: `construction/u2-retention/functional-design/` (BR2.7, BR2.8, BR2.16), `inception/requirements-analysis/requirements.md` (NFR1, NFR8) e `nfr-requirements-questions.md` (Q3).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR8.1 | O `/actuator/prometheus` publica três métricas, só com os rótulos `purge` e `result`, nunca com dado pessoal: `memetrade_purge_deleted_total{purge}`, `memetrade_purge_runs_total{purge,result}` e `memetrade_purge_last_success_seconds{purge}` | teste de integração das métricas |
| NFR8.2 | Indicadores de saúde `loginChallengePurge` e `sessionPurge`, com `lastSuccessAt` e `lastResult` nos detalhes (NFR7.2) | teste do indicador |
| NFR8.3 | Cada execução gera um log `INFO` com o expurgo, a contagem, o número de lotes e a duração. Uma falha gera `ERROR` com a classe do erro e os totais até ali. Nenhum log traz dado pessoal (NFR1.4) | teste de log |
| NFR8.4 | Cada execução publica um evento de auditoria pelo `system` com a contagem (`LOGIN_CHALLENGES_PURGED`, `SESSIONS_PURGED`) | teste de integração |
| NFR8.5 | Alerta `PURGE_STALE` (Média) quando um dos indicadores fica `DEGRADED`, no mesmo canal dos expurgos existentes | passa quando a tabela de alertas do Infrastructure Design da U2 tem a linha `PURGE_STALE` com a condição e o canal (verificação manual na revisão daquela etapa) |

## SLI

Fração de dias com execução bem-sucedida de cada expurgo novo. A meta é 100% em 30 dias corridos, com tolerância de um dia perdido recuperado na subida.
