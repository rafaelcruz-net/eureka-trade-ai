# Desenho de observabilidade — U8 Administração e feedback

Base: [requisitos de observabilidade](../nfr-requirements/observability-requirements.md).

## Métricas (Micrometer, actuator do processo de API)

| Métrica | Tipo | Rótulos | ID |
|---|---|---|---|
| `admin.killswitch.state` | gauge (0/1) | — | NFR9.1 |
| `admin.killswitch.pending_bots` | gauge | — | NFR9.1 |
| `admin.killswitch.reconcile_needed` | gauge (0/1) | — | NFR9.1 |
| `admin.killswitch.reconcile_divergences_total` | counter | — | NFR9.1 |
| `admin.killswitch.apply_seconds`, `admin.killswitch.lift_seconds` | timer | `outcome` (`OK`, `PARTIAL`, `FAILED`) | NFR9.1 |
| `feedback.received_total` | counter | `screen` | NFR9.2 |
| `feedback.refused_total` | counter | `reason` (`VALIDATION`, `RATE_LIMITED`, `IDEMPOTENCY_CONFLICT`) | NFR9.2 |
| `admin.login.refused_total` | counter | `reason` | NFR9.5 |

Nenhum rótulo tem tenant, pessoa, carteira ou texto.

## Alertas e saúde (NFR9.3, NFR9.4)

O `KillSwitchReconciler` registra quando `reconcileNeeded` e `pendingBots > 0` começaram, pelo relógio injetável. A cada execução, ele grava log ERROR com código estável nos casos abaixo:

| Condição | Código |
|---|---|
| `reconcileNeeded` há mais de 60 s | `KILL_SWITCH_RECONCILE_STALE` |
| `pendingBots > 0` há mais de 2 min | `KILL_SWITCH_PENDING_STALE` |
| Divergência entre U5 e U8 encontrada | `KILL_SWITCH_DIVERGENCE` |

O `HealthIndicator` `adminKillSwitch` fica `DOWN` com o código e a duração enquanto alguma dessas condições durar.

O indicador `feedbackPurge` mostra a data da última execução bem-sucedida. Ele fica `DOWN` se ela tiver mais de 48 h.

## Logs e correlação (NFR9.5, NFR9.6)

- Os logs em JSON usam o padrão das outras Units, com `correlationId` vindo do filtro existente. Os erros saem como `ProblemDetail` com `correlationId`.
- **Campos permitidos por módulo:**
  - `administration`: `state`, `version`, `pendingBots`, `changed`, `failedCount` e a carteira mascarada.
  - `feedback`: `screen`, `length` e `outcome`.
- Os testes de varredura do NFR6.7 cobrem esses dois módulos.

## Painel

Não há painel novo no beta. O app do administrador mostra o estado do kill switch, os pendentes e a reconciliação em andamento (BR7.1), e as métricas ficam no actuator.
