# Relatório de SLOs

Conversa: português (Brasil). Resposta 1A. Período: até a release local `v2026.11.08-1` (`operation/deployment-execution/deployment-log.md`). Definições em `operation/observability-setup/slo-config.md`; evidência em `operation/performance-validation/test-results.md`. A leitura contínua usaria `operation/observability-setup/dashboards.md` e os alarmes de `operation/observability-setup/alarms.md`, que ainda não têm coletor.

## Nível 1: metas das Units (SLIs medidos em teste)

| Unit | Metas | Cumprimento | Observação |
|---|---|---|---|
| U1 | p95 de desafio, verificação e lista ≤ 300 ms; frescor ≤ 60 s | cumpridas | 45/224/45 ms; smokes ok |
| U2 | entregas em 5 s em 99%; trilha p95 ≤ 2 s; expurgo ≤ 30 min | **sem medição** | `perf-audit.sh` interrompido por falta de memória |
| U2 | pendência do outbox < 60 s | cumprida | smoke e integração |
| U3 | verificação ≤ 500 ms; convite e Conta ≤ 300 ms | cumpridas | 259/4/7 ms |
| U4 | lista ≤ 1 s com 2 000 tokens; detalhe ≤ 500 ms | cumpridas | 500/11 ms |
| U4 | crescimento para 10 000 tokens | **não cumprida** | 10,02 s; réplica de leitura antes do G1 |
| U5 | ciclo ≤ 2 s; canal ≤ 4 s; C4 ≤ 1 s | cumpridas | 112 ms; 1,9 s |
| U6 | `evaluate` ≤ 250 ms; lote ≤ 100 ms | cumpridas | ≤ 21,93 ms; 58 ms |
| U7 | `execute` ≤ 250 ms; P&L e ledger ≤ 1 s; rodadas ≤ 10 s; reconciliação ≤ 60 s | cumpridas | 34 ms; 201 ms; 317 ms; 25,4 s |
| U8 | kill switch ≤ 5 s; feedback ≤ 500 ms; telas ≤ 2 s | cumpridas | testes de integração |
| U9 | quadros ≤ 2 s | cumprida (registrada sem reprovar) | um caso de 2,03 s numa suíte completa |

## Nível 2: SLOs de produção do beta PAPER

| SLO | Meta | Situação | Orçamento de erro | Taxa de consumo |
|---|---|---|---|---|
| Disponibilidade da API | 99,5% em 30 dias | **sem dados**: não há produção | não aplicável | não aplicável |
| Latência de leitura | 95% ≤ 1 s em 28 dias | sem dados | não aplicável | não aplicável |
| `paper.execute` | 95% ≤ 250 ms em 28 dias | sem dados | não aplicável | não aplicável |
| Frescor da fonte | 99% dos minutos ≤ 60 s em 30 dias | sem dados | não aplicável | não aplicável |
| Atraso da auditoria | 99,9% dos minutos < 60 s em 30 dias | sem dados | não aplicável | não aplicável |

**Próxima medição:** 30 dias depois da abertura do beta PAPER na hospedagem, junto com a revisão das metas propostas.

## Incidentes

Nenhum incidente registrado (`operation/incident-response/incident-plan.md`). Na release, só houve o tropeço na execução dos smokes, sem efeito no ambiente.
