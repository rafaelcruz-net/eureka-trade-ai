# Detecção de anomalias

Conversa: português (Brasil). Sem serviço de detecção de anomalias no beta, como no intent anterior (`261026-memetrade-saas-mvp/operation/observability-setup/anomaly-config.md`). O volume é pequeno (até 20 convidados) e as metas são fixas, então limites estáticos bastam. Na AWS, a detecção de anomalias do CloudWatch pode entrar nos sinais abaixo.

## Sinais candidatos à detecção de anomalias (AWS, futuro)

| Sinal | Por que | Base |
|---|---|---|
| `memetrade_terms_reacceptance_blocked_total` | um pico sem versão nova publicada indica trava barrando quem já aceitou | `monitoring-design.md` da U3 |
| `memetrade_data_requests_created_total` | um pico de pedidos pode indicar abuso (o limite por pessoa é 10 por hora) | `monitoring-design.md` e `security-design.md` da U4 |
| `memetrade_purge_deleted_total{purge}` | queda a zero ou salto fora do volume diário | `monitoring-design.md` da U2 |
| `http_server_requests_seconds` nas rotas `/api/v1/legal/**` com `429` | volume anormal de recusas do limitador | `security-design.md` da U1 |

Até lá, esses sinais ficam no painel "Privacidade e termos" (`dashboards.md`) para leitura do responsável do projeto.

## Hoje: limites estáticos

Os alarmes de `alarms.md` usam limites fixos, tirados das metas dos `performance-design.md` e das regras de saúde dos `reliability-design.md` das Units. Com o volume do beta, um limite fixo dá menos falso alarme do que um modelo de anomalia sem histórico.
