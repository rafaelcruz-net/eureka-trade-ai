# SLOs e SLIs

Conversa: português (Brasil). Resposta 2A. Os SLOs do intent anterior continuam (`261026-memetrade-saas-mvp/operation/observability-setup/slo-config.md`), com os mesmos alarmes de consumo do orçamento (`SLO_BURN_FAST` e `SLO_BURN_SLOW`). As rotas novas deste intent entram no SLO de disponibilidade da API.

## SLO novo

| SLO | SLI | Meta | Janela | Medição | Origem |
|---|---|---|---|---|---|
| Prazo dos pedidos do titular | fração do tempo com `memetrade_data_requests_overdue == 0` | 99,5% | 30 dias corridos | gauge atualizado a cada 60 s | LGPD (15 dias), U4 NFR8.3 |

O alarme `DATA_REQUEST_OVERDUE` (Alta) dispara antes de o SLO quebrar: basta um pedido vencido por 15 minutos.

## Indicadores de latência (sem orçamento próprio)

Metas dos `performance-design.md` das Units, acompanhadas pelo alarme `LATENCY_P95` (Média) e medidas nos cenários de carga:

| Indicador | Meta (p95) | Último valor medido (Build and Test) |
|---|---|---|
| Páginas `/termos`, `/privacidade`, `/novo-aceite` | 2 s | 146 ms |
| Rotas `/api/v1/legal/**` | 300 ms | 14 ms |
| `POST /api/v1/terms/acceptances` | 500 ms | 45 ms |
| Checagem da trava de novo aceite | 5 ms | 4,2 ms |
| Criar, listar e abrir pedido | 500 ms | 23 ms |
| Rotas administrativas dos pedidos | 1 s | 16 ms |
| Anonimização de uma conta | 2 s | dentro da meta (`AnonymizationLatencyIT`) |

A trava de novo aceite está com pouca folga (4,2 ms contra 5 ms): o alarme `TERMS_CHECK_SLOW` acompanha.
