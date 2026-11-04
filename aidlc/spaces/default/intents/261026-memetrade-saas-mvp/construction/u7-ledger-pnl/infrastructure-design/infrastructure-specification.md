# Especificação de infraestrutura — U7 Ledger e P&L

Infraestrutura da Unit `u7-ledger-pnl` no ambiente local do beta (Docker Compose no Windows, CI no GitHub Actions), a partir do desenho não funcional em [../nfr-design/](../nfr-design/) e das decisões de [infrastructure-design-questions.md](infrastructure-design-questions.md). Não há nuvem nem hospedagem nesta etapa; a U7 não cria serviço novo no Compose. Os IDs `NFRx.y` são os da U7.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Modelo de computação | Módulos `ledger` e `pnl` dentro do processo `api` existente; telas no contêiner `web` existente | Monólito modular; nada novo para operar |
| Rede | Sem mudança; nenhum acesso de saída (PAPER nunca envia transação) | NFR6.1 |
| Armazenamento | PostgreSQL 17 existente; tabelas novas `paper_account`, `paper_order`, `fill`, `ledger_entry`, `position`, `pnl_daily`, `simulation_policy` (dono `ledger`), `equity_peak`, `reconciliation_check` (dono `pnl`) | logical-components.md |
| Migrações | `0008-ledger-pnl.sql` aplicado pelo `db-migrate` com o dono do esquema: tabelas com `numeric(38,6)` para valores, `numeric(38,12)` para preços e `numeric(38,18)` para quantidades; `CHECK mode = 'PAPER'`; índices do scalability-design.md; gatilhos de imutabilidade e de transição; privilégios da matriz do security-design.md para o papel da aplicação; política v1 semeada. Só acrescenta | NFR6.2, NFR5.3 |
| Ambientes | Só local e CI | `team.md` (Deployment) |
| IaC | Docker Compose versionado; sem IaC de nuvem | Decisão da U2 |
| Dimensionamento | Pool principal sem mudança (20 conexões); pool do agendador de 7 para **10** linhas (marcação, fechamento pendente, reconciliação); `mem_limit` da `api` sem mudança (1 GB). Acréscimo de memória da U7: lotes de marcação e reconciliação por pessoa (menos de 30 MB com o volume do NFR2.1) | NFR3.5, NFR3.6 |

## Configuração da API

| Propriedade | Variável | Padrão | Uso |
|---|---|---|---|
| `spring.task.scheduling.pool.size` | `SCHEDULER_POOL_SIZE` | `10` (hoje `7`); o `RuntimeSettingsGuard` passa a recusar menos de 10 | Três tarefas da U7 |
| `memetrade.ledger.marking-fallback-interval` | — | `60s` fixo | Rodada de reserva da marcação e do fechamento pendente |
| `memetrade.ledger.reconciliation-interval` | — | `5m` fixo | BR8.1 |
| `memetrade.ledger.reconciliation-run-limit-per-minute` | — | `6` fixo | NFR6.5 |
| `memetrade.ledger.lock-timeout`, `statement-timeout`, `job-lock-timeout` | — | `2s`, `5s`, `1s` fixos | NFR10.8 |

Só `SCHEDULER_POOL_SIZE` passa pelo Compose (já está na lista da `api`, com o novo padrão `10`); o `ComposeFilesTest` confere o padrão. As demais propriedades são fixas no `application.yml`, para o uso diário não mudar regras de integridade por variável.

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL 17 (`postgres`) | database | Tabelas, índices, gatilhos e privilégios da `0008`; `SET LOCAL lock_timeout` e `statement_timeout` nas transações da U7 | Volume da U7 estimado abaixo de 500 MB com o NFR2.1 |
| ShedLock (tabela `shedlock`) | coordenação de tarefas | Bloqueios `ledger-pending-close`, `pnl-equity-marking`, `pnl-reconciliation`, `pnl-reconciliation-run-limiter-eviction` | Tabela da U2 |
| Agendador (na `api`) | tarefas | 10 linhas | — |
| Actuator (8081) | saúde | Indicador novo `ledgerJobs`, fora da prontidão, `DEGRADED` mapeado para 200 | O `scripts/verify.sh` continua sem mudança |
| Cache, fila, CDN, DNS, balanceador | — | Não usados | — |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| PostgreSQL e volume `pgdata` | U1 | Todas | A U7 só nas próprias tabelas; ninguém lê as tabelas da U7 fora da `LedgerReadModel` e da C13/C15 |
| Interfaces `PaperExecution` (C15) e `PortfolioRiskView` (C13) | U7 | U5, U6 | Só por interface, dentro da transação do ciclo |
| `MarketDataQuery` (C11) | U4 | U7 | Acréscimo de `decimals` |
| `IdempotencyGuard` | U1 | U7 | Operações `PAPER_FUND` e `PAPER_CLOSE_POSITIONS` |
| Publicador da trilha (C9) | U2 | U7 | Ações da U7 |
| Tabela `shedlock` e agendador | U2 | U2, U4, U7 | Nomes de bloqueio próprios |

## Para a hospedagem futura

- Os privilégios da matriz valem para o papel da aplicação em qualquer ambiente; nenhum ambiente dá ao papel da aplicação `UPDATE` ou `DELETE` em `ledger_entry` ou `fill`.
- O limitador de "verificar agora" passa para armazenamento compartilhado antes de rodar mais de uma instância.
