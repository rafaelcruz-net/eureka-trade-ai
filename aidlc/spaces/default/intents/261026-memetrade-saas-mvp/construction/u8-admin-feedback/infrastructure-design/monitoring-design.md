# Monitoramento — U8 Administração e feedback

Implementa o [desenho de observabilidade](../nfr-design/observability-design.md) na infraestrutura local: o actuator do processo de API, os logs JSON dos contêineres e o healthcheck do Compose. Não há agregador de logs nem sistema de alertas externo no beta local. Os alertas são logs ERROR com código estável e indicadores de saúde `DOWN`.

## Métricas e indicadores

| Métrica | Origem | Limite | Por que importa |
|---|---|---|---|
| `admin.killswitch.state` | actuator da `api` | — | Mostra se a simulação está parada |
| `admin.killswitch.pending_bots` | actuator da `api` | > 0 por mais de 2 min | Bots ainda sem a pausa de sistema |
| `admin.killswitch.reconcile_needed` | actuator da `api` | 1 por mais de 60 s | Aplicação incerta depois de exceção ou queda |
| `admin.killswitch.reconcile_divergences_total` | actuator da `api` | qualquer aumento | A U5 e a U8 discordaram |
| `admin.killswitch.apply_seconds` p95 | actuator da `api` | > 5 s | Meta do NFR2.1 |
| `feedback.refused_total{reason}` | actuator da `api` | — | Abuso ou erro de validação |
| `admin.login.refused_total{reason}` | actuator da `api` | aumento de `RATE_LIMITED` | Tentativas na entrada do administrador |
| `http.server.requests` (rotas `/api/v1/admin/**` e `/api/v1/feedback`) | actuator da `api` | taxa de 5xx > 0 | Erro por rota (NFR9.5) |
| Saúde do contêiner `admin` | healthcheck do Compose | `unhealthy` | App do administrador fora do ar |

## Alertas

| Alerta | Condição | Severidade | Destino |
|---|---|---|---|
| `KILL_SWITCH_RECONCILE_STALE` | `reconcileNeeded` há mais de 60 s | Alta | Log ERROR da `api` e saúde `adminKillSwitch` `DOWN` |
| `KILL_SWITCH_PENDING_STALE` | `pendingBots > 0` há mais de 2 min | Alta | Log ERROR e saúde `DOWN` |
| `KILL_SWITCH_DIVERGENCE` | Reconciliação com `changed > 0` em estado estável ou `systemPaused()` diferente | Alta | Log ERROR e contador |
| `FEEDBACK_PURGE_STALE` | Última execução bem-sucedida do expurgo há mais de 48 h | Média | Saúde `feedbackPurge` `DOWN` |
| `KILL_SWITCH_APPLY_FAILED` | Exceção na chamada à U5 | Média | Log ERROR (a reconciliação corrige) |

O destino é o responsável do projeto, pelo log e pelo `/actuator/health`. Com hospedagem, os alertas passam para a plataforma de monitoramento escolhida no Deployment.

## SLIs e SLOs

| SLI | Meta | Janela de medição |
|---|---|---|
| Tempo do `PUT ON` com 60 bots | p95 ≤ 5 s | 20 execuções no teste de integração |
| Tempo até todos os bots pausados | ≤ 45 s no pior caso, ≤ 5 s no normal | Cada execução do `KillSwitchCrashIT` |
| Tempo do `system-status` | p95 ≤ 1 s | Teste de integração e smoke |
| Tempo das telas do administrador | p95 ≤ 2 s com 20 leituras simultâneas | `AdminReadLatencyIT` |
| Tempo do envio de feedback | p95 ≤ 500 ms | `FeedbackConcurrencyIT` |

Não há SLO de disponibilidade no beta local (NFR10.6).

## Logs e rastreamento

- **Logs:** JSON no stdout de cada contêiner, com `correlationId`. Os módulos da U8 seguem a lista de campos permitidos do desenho de observabilidade. O `admin` (Next.js) registra só a rota, o status e o tempo, nunca token nem corpo.
- **Varredura:** hoje a varredura de logs do `verify.sh` lê só o `api`. Ela passa a cobrir o `admin`, o `worker`, o endereço da carteira do administrador e o marcador de feedback depois das mudanças do responsável do projeto descritas no [pipeline](cicd-pipeline.md). Os testes de integração da U8 procuram o marcador de feedback e o motivo do kill switch nos logs capturados, sem depender do `verify.sh`.
- **Rastreamento:** não há rastreamento distribuído no beta; o `correlationId` liga a requisição aos eventos e aos logs.
- **Painel:** a tela do administrador mostra o estado do kill switch, os pendentes e a reconciliação; não há painel separado.
