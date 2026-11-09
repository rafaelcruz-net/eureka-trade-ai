# Retroalimentação para o próximo ciclo

Conversa: português (Brasil). Respostas 4A e 5A. Entradas do próximo Ideation, a partir da release `v2026.11.08-1` (`operation/deployment-execution/deployment-log.md`), dos resultados de `operation/performance-validation/test-results.md`, do plano de `operation/incident-response/incident-plan.md` e da observabilidade de `operation/observability-setup/` (`dashboards.md`, `alarms.md`, `slo-config.md`).

## O que coletar no beta PAPER

| Sinal | Fonte | Pergunta que responde |
|---|---|---|
| Feedback dos convidados | U8 (`feedback_received_total` e textos, só na tela do administrador; nunca em log) | o que incomoda, o que falta |
| Funil convite → conta → primeiro bot PAPER → primeiro P&L | trilha de auditoria (U2) e contagens agregadas | onde as pessoas param |
| Uso dos filtros do scanner e proporção de `unknown` vista | métricas da U4 (`scanner_excluded_by_unknown`) | a cobertura dos dados basta? (critério prioritário da pesquisa de mercado) |
| Pausas por bot e uso do kill switch | U5 e U8 | confiança no bot e na contenção |
| Recusas de risco por motivo | U6 | os limites fazem sentido para quem usa? |
| Divergências da reconciliação | U7 | confiabilidade do P&L |
| Incidentes e consumo do orçamento de erro | `incident-plan.md`, `slo-config.md` | estabilidade do beta |

Nada disso promete resultado ao usuário. As métricas são agregadas e sem dado pessoal.

## Pendências levadas ao próximo ciclo

| # | Pendência | Origem | Quando |
|---|---|---|---|
| P1 | Escala da U4 com réplica de leitura e nova medição do `growth` (U4-NFR3.5, Not Met) | Build and Test, Performance Validation | antes do G1 |
| P2 | `perf-audit.sh` da U2 (U2-NFR3.1, 3.3 e 4.2, Unverified) | Build and Test | antes do G1 |
| P3 | Mudanças do `ci.yml` (D1) | CI Pipeline | próximo commit do responsável do projeto |
| P4 | Revisão do Contract Design (D2) | este relatório | início do próximo ciclo |
| P5 | FR14.x e FR15.1 sem critério de aceite | Requirements | próximo Inception |
| P6 | NFRs adiados (`Deferred`) | NFR Requirements | revisar com a hospedagem |
| P7 | Achados Menores da U6 (caso extremo de rollback no commit) | revisão da U6 | próximo ciclo |
| P8 | Teste instável da U9 (`DashboardOpenLatencyIT`, 2,03 s uma vez) | Build and Test | tratar como defeito |
| P9 | Achados Baixos do `verify.sh` | revisão da U1 | próximo ciclo |
| P10 | Conta na AWS, região, orçamento; staging e produção (C-10, C-12) | Environment Provisioning | decisão do responsável do projeto |
| P11 | Revisor independente para G2/G3 (C-14); plantão secundário; canal com os convidados; plano de suporte da AWS | Incident Response | antes do G1 (canal e suporte) e do G2 (revisor) |
| P12 | Cota de minutos do GitHub Actions | Sprint 0 | próximo ciclo |

## Automação de trabalho repetitivo

| Ordem | Tarefa | Ganho |
|---|---|---|
| 1 | Script de release local: cria a tag, roda o ensaio de rollback e os três smokes, grava o resultado na tag | a release desta vez teve passos manuais e um tropeço de terminal; vira um comando |
| 2 | `perf-audit.sh` num workflow manual da CI, em executor com mais memória | fecha P2 sem depender do notebook |
| 3 | Aplicar o `ci.yml` do `ci-config.md` | fecha P3 |
| 4 | Atualização automática de dependências, com espera para versões novas do frontend (`team.md`) | menos trabalho de segurança manual |
| 5 | Limpeza periódica de imagens e cache do Docker local | evita disco cheio (RB-02) |

Nenhuma dessas tarefas muda código agora. Todas entram no planejamento do próximo ciclo.
