# Relatório de SLOs

Conversa: português (Brasil). Resposta 1A. SLOs de `operation/observability-setup/slo-config.md`; evidências de `operation/deployment-execution/deployment-log.md` e `operation/performance-validation/test-results.md`.

## Conformidade com as evidências de hoje

| SLO ou indicador | Meta | Evidência | Situação |
|---|---|---|---|
| Disponibilidade da API (intent anterior) | o do intent anterior | `verify.sh` 19 de 19 na CI do commit da release; ensaio de rollback com a API `UP` nas quatro etapas | sem tráfego real para medir; nenhuma queda vista |
| Prazo dos pedidos do titular (novo) | 99,5% do tempo sem pedido vencido, em 30 dias | nenhum pedido real; o job de 15 minutos e o gauge estão testados (`DataRequestTimersIT`, `DataRequestMetricsTest`) | sem pedidos para medir |
| Latência das rotas deste intent | metas dos `performance-design.md` | cargas locais: todas abaixo de 25% da meta, menos a trava (84%) | atendida no perfil do beta |

Nenhum orçamento de erro foi consumido, porque não houve tráfego de usuário.

## Para medir de verdade

1. Abrir o beta PAPER num ambiente com coleta de métricas (Environment Provisioning, pendente).
2. Ligar os alarmes `SLO_BURN_FAST`, `SLO_BURN_SLOW` e `DATA_REQUEST_OVERDUE` (`operation/observability-setup/alarms.md`).
3. Revisar este relatório depois dos primeiros 30 dias de beta, com os números do painel "Privacidade e termos" (`operation/observability-setup/dashboards.md`).
