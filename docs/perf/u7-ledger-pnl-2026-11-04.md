# Carga local — U7 Ledger e P&L (2026-11-04)

Gerado por `scripts/load-test.sh` (`LOAD_SCENARIO=u7` e `u7-jobs`), no Windows com Docker Desktop, perfis `test,loadtest`, com o gerador de ciclos de `com.memetrade.ledger.loadtest`. Os p95 vêm dos histogramas do Prometheus (limite superior do balde); os limites são os do cicd-pipeline.md e não foram alterados.

## Resumo contra os NFRs da U7

| NFR | Meta | Medido | Situação |
|---|---|---|---|
| NFR3.1 | p95 ≤ 250 ms para `execute` e `closePositions` no ciclo | `execute`: 39 ms (`u7`, 60 bots, 20 pessoas com 10 000 preenchimentos cada) e 22 ms (`u7-jobs`). `closePositions`: **não medido** (o gerador não encerra bots) | Atendido para `execute`; `closePositions` pendente |
| NFR3.2 | p95 das rotas de P&L e ledger ≤ 1 s | P&L 39 ms (30 e 366 dias), ledger 224 ms | Atendido |
| NFR3.3 | p95 das demais rotas C5 ≤ 1 s | operações 39 ms, uso do dia 179 ms, reconciliação 15 ms | Atendido |
| NFR3.5 | p95 ≤ 10 s por rodada de marcação e de fechamento pendente, 60 bots com 5 posições (o roteiro reprova qualquer rodada acima de 10 s e menos de 60 rodadas) | 64 rodadas de marcação (maior 288 ms) e 155 de fechamento pendente (maior 5 ms) em 30 minutos | Atendido |
| NFR3.6 | rodada de reconciliação ≤ 60 s e "verificar agora" ≤ 5 s com o volume do NFR2.1 | rodada 6,4 a 6,7 s; "verificar agora" 301 a 349 ms (`ReconciliationVolumeIT`, seção abaixo) | Atendido |
| NFR2.2 | planos de P&L, ledger e saldo sem `Seq Scan` em `ledger_entry` ou `fill` | sem `Seq Scan` com 200 mil preenchimentos | Atendido |

**Guarda de memória do PostgreSQL no `u7` (decisão do responsável do projeto, 2026-11-04, revisão 1 da geração de código, R-02):** a primeira execução do `u7` saiu reprovada (código 1) só pela guarda de memória do roteiro (900 MB por contêiner, herdada da U4): o PostgreSQL chegou a **902 MB** em `docker stats` com 200 311 preenchimentos e seus lançamentos, contra 98 MB no `u7-jobs`. O `docker stats` conta o cache de páginas do contêiner. O responsável do projeto decidiu **subir a guarda do PostgreSQL para 1 GB (1024 MiB) só no cenário `u7`**; a API continua com 900 MB e todos os outros cenários e limites ficam como estavam (`scripts/load/load-test.mjs`, `U7_POSTGRES_MEMORY_LIMIT_MB`; `WorkflowsTest` confere os dois valores).

**Repetição do `u7` com a guarda nova (2026-11-04): não concluída.** A execução repetida, que também encerra 10 bots com `closePositions` e mede "verificar agora", foi interrompida pelo sistema durante a preparação (194 150 de 200 000 preenchimentos gravados), por falta de memória na máquina. Nenhuma medição da repetição foi gravada; a seção `u7` abaixo continua sendo a da primeira execução. O p95 de `closePositions` sob carga segue sem medição, e o NFR3.1 continua `Deferred`. Para repetir: `LOAD_SCENARIO=u7 SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` com memória livre suficiente; a seção `u7` é regravada pelo roteiro.

## NFR3.6 — reconciliação com o volume do NFR2.1

Medido pelo teste de integração `ReconciliationVolumeIT` (Testcontainers, PostgreSQL 17, mesmas migrações, banco próprio), com 20 pessoas, 60 bots, 300 posições, 200 000 preenchimentos, 600 060 lançamentos e 25 200 linhas de `pnl_daily`, coerentes com o ledger (a rodada não acha divergência). Tempos de relógio desta máquina (Windows, Docker Desktop), duas execuções em 2026-11-04:

| Medida | Execução 1 | Execução 2 | Limite |
|---|---|---|---|
| Rodada agendada das 20 pessoas (`ReconciliationJob.runOnce`) | 6 726 ms | 6 434 ms | 60 000 ms |
| "Verificar agora" de uma pessoa (`POST /api/v1/reconciliation/run`, 5 pessoas) | 301 a 349 ms | 303 a 321 ms | 5 000 ms |
| Semeadura do volume (só preparação) | 76,6 s | 70,6 s | — |

`ReconciliationJob` agora registra `durationMs` ao fim de cada rodada (só contagens e duração), e o roteiro de carga lê essas linhas no cenário `u7` e reprova rodada acima de 60 s.

<!-- section:u7 -->
## Cenário `u7`

- Pessoas: 20 · bots: 60 · preenchimentos gravados antes da medição: 200311 · preparação: 733 s
- Usuários lendo as rotas C5: 20 · duração da medição: 600 s
- Ciclos do gerador ao fim: 1227 (falhas técnicas: 0)
- Resultado: **reprovado**

| Medida | p95 | Limite |
|---|---|---|
| execute | 39 ms | 250 ms |
| pnl | 39 ms | 1000 ms |
| ledger | 224 ms | 1000 ms |
| trades | 39 ms | 1000 ms |
| dayUsage | 179 ms | 1000 ms |
| reconciliation | 15 ms | 1000 ms |

- Planos sem `Seq Scan` em `ledger_entry` ou `fill`: sim
- Espera do pool: máximo de 1 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 341 MB, postgres 902 MB (limite 900 MB)
- Erros de cliente: 0 · novos logins por token vencido: 0
- Falhas: memory postgres
<!-- /section:u7 -->

<!-- section:u7-jobs -->
## Cenário `u7-jobs`

- Pessoas: 20 · bots: 60 · preenchimentos gravados antes da medição: 317 · preparação: 11 s
- Usuários lendo as rotas C5: 0 · duração da medição: 1800 s
- Ciclos do gerador ao fim: 3653 (falhas técnicas: 0)
- Resultado: **aprovado**

| Medida | p95 | Limite |
|---|---|---|
| execute | 22 ms | 250 ms |

- Rodadas de marcação: 64, maior 288 ms (limite 10000 ms)
- Rodadas de fechamento pendente: 155, maior 5 ms (limite 10000 ms)
- Planos sem `Seq Scan` em `ledger_entry` ou `fill`: não conferido neste cenário
- Espera do pool: máximo de 0 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 314 MB, postgres 98 MB (limite 900 MB)
- Erros de cliente: 0 · novos logins por token vencido: 0
- Falhas: nenhuma
<!-- /section:u7-jobs -->
