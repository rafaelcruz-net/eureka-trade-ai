# Desenho de monitoramento — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

Este arquivo leva o `observability-design.md` do NFR Design para a plataforma. A U1 acrescenta dois contadores no endpoint de métricas que já existe e logs JSON com `correlationId`. Ela não acrescenta alarme, painel, consulta salva nem regra do Prometheus, então `infra/observability/` e os alarmes de `infra/terraform/modules/observability/` não mudam [Q3].

## Métricas e KPIs

| Métrica | Fonte | Limite | Por que importa |
|---|---|---|---|
| `bots_real_create_refusals_total{reason}` (Micrometer `bots.real.create.refusals`; `reason` ∈ `KILL_SWITCH`, `REAL_DISABLED`, `WALLET_INACTIVE`, `NOT_RELEASED`, `QUARANTINE`) | `/actuator/prometheus` na porta de gestão 8081 (api); no worker, porta 8082, as 5 séries ficam sempre em 0 | Sem limite e sem alarme (NFR8.5) | Mostra por que criações REAL são recusadas (NFR3.10). |
| `pnl_mode_block_unavailable_total{market}` (Micrometer `pnl.mode_block.unavailable`; `market` ∈ `PAPER`, `REAL`) | `/actuator/prometheus` na porta 8081 (só api) | Sem limite e sem alarme (NFR8.5) | Mostra blocos `UNAVAILABLE` da visão por modo (NFR4.6). |
| `http_server_requests_seconds{uri,status}` (histograma que já existe) | `/actuator/prometheus` na porta 8081 | Sem limite novo | SLI de latência das rotas C1 a C4 e contagem de 503 de C1 a C3 (NFR1.14). |
| Razão `pnl_mode_block_unavailable_total` / `http_server_requests_seconds_count{uri="/api/v1/pnl/by-mode"}` | consulta sobre as duas séries | Sem meta | Indicador auxiliar de qualidade de C4. |

### Coleta

| Ambiente | Como a métrica é lida | O que muda na U1 |
|---|---|---|
| Local e CI | `/actuator/prometheus` direto; o teste de integração confere as 7 séries e as etiquetas | Nada na infraestrutura |
| Nuvem (devnet; mainnet só com a U2) | O sidecar `metrics-agent` (CloudWatch Agent) lê `/actuator/prometheus` a cada **120 s** (`scrape_interval = "120s"` em `modules/services/metrics-agent.tf`), no padrão da correção do `project.md` | Nada: a configuração de leitura não muda |
| Nuvem, publicação no CloudWatch | O agente publica só a lista de permissão de `modules/observability/app-metrics.tf` (`app_metric_declarations`); o resto é descartado logo depois da leitura (`metric_relabel_configs` com `keep`) | A U1 não acrescenta entradas na lista. Os dois contadores são lidos a cada 120 s e descartados; na nuvem, a evidência das recusas e dos blocos `UNAVAILABLE` vem dos logs (abaixo) |

Restrições das métricas: nenhuma etiqueta com tenant, pessoa, bot, carteira, endereço ou `correlationId`; 7 séries novas na API e 5 no worker; todas registradas com zero na subida (NFR3.6, NFR3.10, NFR4.6).

## Alertas

| Alerta | Condição | Severidade | Destino |
|---|---|---|---|
| Nenhum alerta novo na U1 | — | — | — |
| Candidato futuro (só registrado, fora da U1): blocos indisponíveis | `increase(pnl_mode_block_unavailable_total[15m]) > 0` por mais de uma janela | a decidir | tópico SNS de alarmes que já existe |
| Candidato futuro (só registrado, fora da U1): recusas por kill switch | salto de `bots_real_create_refusals_total{reason="KILL_SWITCH"}` fora de um acionamento conhecido | a decidir | tópico SNS de alarmes que já existe |

Se um candidato entrar num estágio futuro: a métrica entra antes na lista de permissão (`app_metric_declarations`), com custo de cerca de US$ 0,30 por métrica por mês, e o alarme segue a correção do `project.md` (janelas de 240 s para alarme com dado ausente, pelo menos 180 s nos demais). O teste `infra/terraform/tests/observability_metrics.tftest.hcl` já recusa um alarme que lê métrica fora da lista.

Os alarmes que já existem continuam cobrindo a U1 de forma indireta: `metrics-absent-api` e `metrics-absent-worker` (processo ou caminho de métricas caído) e os alarmes de 5xx e do SLO da API no balanceador.

## SLIs e SLOs

| SLI | Meta de SLO | Janela de medição |
|---|---|---|
| p95 de `http.server.requests` por `uri` em `/api/v1/bots`, `/api/v1/bots/real-eligibility`, `/api/v1/real/account-limits` e `/api/v1/pnl/by-mode` | Nenhum SLO operacional novo na U1 (NFR1.14) | — |
| p95 de C1 a C4 com a semente de referência (`ModePnlLatencyIT`) | Só registrado no log do teste; falha se um bloco vier `UNAVAILABLE` (NFR1.5) | 10 aquecimentos e 100 medições por execução do job `backend` |
| p95 de C4 no cenário de carga | 1.000 ms, portão da U2 (NFR1.6) | cenário de carga da U2 (`load-test.yml`, manual) |
| SLO da API no balanceador (já existe, intent `261113-trading-real-bot`) | sem mudança | sem mudança |

Num ambiente com usuários, a latência das rotas da U1 só é observada depois que a U2 for implantada junto (NFR8.4).

## Logs e rastreamento

| Item | Decisão |
|---|---|
| Formato | JSON pelo `LogstashEncoder` de `logback-spring.xml`, com `timestamp` (UTC), `level`, `component`, `message` e `correlationId` do MDC; máscara Base58 em todo texto (NFR3.11). |
| Destino na nuvem | Os grupos de log que já existem do `api-worker` no CloudWatch Logs, com a retenção atual (`log_retention_days`). Nenhum grupo novo. |
| Eventos da U1 | `INFO` `REAL bot creation refused: reason={}`; `WARN` `P&L block unavailable: market={}, cause={}`; `WARN` `REAL eligibility unavailable: operation={}, cause={}`; `WARN` `REAL refusal audit failed: reason={}, cause={}`. |
| Nunca no log | Endereço ou mint de carteira, token de sessão, `Authorization`, nonce, assinatura, corpo do pedido, valores de P&L e mensagem de exceção (NFR3.6, NFR3.7). |
| Consulta na nuvem | Ad hoc no CloudWatch Logs Insights, pelo texto das mensagens acima e pelo `correlationId`. Nenhuma consulta salva nova (as 5 de `modules/observability/` não mudam). |
| Correlação | `correlationId` do `CorrelationIdFilter`, copiado para as threads do pool `pnl-mode-block` pelo `ModeBlockExecutor` (NFR3.12). |
| Rastreamento distribuído | Nenhuma biblioteca de tracing nova; a U1 não chama outro processo (NFR3.13). |

## Painéis

| Painel | Mudança |
|---|---|
| Os 3 painéis que já existem (`modules/observability/`) | Nenhuma; nenhum widget novo |
| Painel novo | Nenhum (NFR8.5) [Q3] |

## Sources

- `infrastructure-design-questions.md`: Q3 e o resumo confirmado [Q3].
- `construction/u1-mode-skeleton/nfr-design/observability-design.md`: métricas, logs, correlação, alarmes candidatos, SLI e SLO.
- `construction/u1-mode-skeleton/nfr-design/performance-design.md`: `ModePnlLatencyIT` (NFR1.5) e portão da U2 (NFR1.6).
- `construction/u1-mode-skeleton/nfr-requirements/observability-requirements.md`: NFR1.14, NFR3.10 a NFR3.13, NFR4.6, NFR8.5.
- `inception/contract-design/contract-summary.md`: C1 a C4.
- Infraestrutura lida: `infra/terraform/modules/services/metrics-agent.tf` (120 s, filtro `keep`), `infra/terraform/modules/observability/app-metrics.tf` (lista de permissão, `metrics_absent`), `infra/terraform/tests/observability_metrics.tftest.hcl`, `infra/observability/*.yml`.
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/alarms.md` e `dashboards.md`.
- `aidlc/spaces/default/memory/project.md`: correção do CloudWatch Agent (120 s, janelas de 240 s e 180 s, US$ 350 por conta).

## Assumptions & Open Questions

1. **Esclarecimento para o fundador:** a Q3 diz que o CloudWatch Agent lê o endpoint a cada 120 s. Isso continua verdade, mas o agente publica no CloudWatch só a lista de permissão, e a Q3 não acrescenta entradas nela. Este desenho mantém a lista sem mudança, porque acrescentar as 7 séries criaria custo recorrente (cerca de US$ 2,10 por conta por mês), contra o NFR8.2 e a Q5. Na nuvem, as recusas e os blocos indisponíveis aparecem pelos logs. Se o fundador quiser os contadores no CloudWatch já na U1, a mudança é acrescentar `bots_real_create_refusals_total` com `dimensions = [["reason"]]` e `pnl_mode_block_unavailable_total` com `dimensions = [["market"]]` em `app_metric_declarations`, aceitando esse custo.
2. [assumption] Os alarmes candidatos ficam fora da U1 por decisão da Q3; nenhum entra sem um estágio que o decida.
