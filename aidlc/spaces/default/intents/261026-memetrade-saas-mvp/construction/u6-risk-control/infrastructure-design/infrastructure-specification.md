# Especificação de infraestrutura — U6 Barreira de limites

Esta é a infraestrutura da Unit `u6-risk-control` no ambiente local do beta: Docker Compose no Windows e CI no GitHub Actions. Ela parte do desenho não funcional em [../nfr-design/](../nfr-design/) e das decisões de [infrastructure-design-questions.md](infrastructure-design-questions.md).

Não há nuvem nem hospedagem nesta etapa, e a U6 não cria serviço novo no Compose. Os IDs `NFRx.y` são os da U6.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Modelo de computação | Módulo `riskcontrol` dentro do processo `api` existente: rota C21, expurgo e indicador de saúde. A barreira (`OrderGate`) é chamada no processo do ciclo da U5, o worker, quando a U5 o criar. Até lá, só os testes e o ciclo de teste do perfil `loadtest` a chamam | Monólito modular; nada novo para operar |
| Rede | Sem mudança; nenhum acesso de saída | NFR6.1 |
| Armazenamento | PostgreSQL 17 existente; tabelas novas `order_evaluation`, `order_rejection`, `rejection_reason_detail` e `risk_purge_run` | logical-components.md |
| Migrações | `0009-risk-control.sql`, aplicada pelo `db-migrate` (Liquibase) com o dono do esquema. Detalhe abaixo. Só acrescenta | NFR4.3, NFR5.3 |
| Preparo dos papéis | `infra/postgres/bootstrap/10-roles.sql` ganha o papel de grupo `risk_retention`. Detalhe abaixo | security-design.md, Q1 do NFR Design |
| Ambientes | Só local e CI | `team.md` (Deployment) |
| IaC | Docker Compose versionado; sem IaC de nuvem | Decisão da U2 |
| Dimensionamento | Pool principal sem mudança (20 conexões). Pool de retenção próprio da U6 com 1 conexão (detalhe abaixo); o PostgreSQL passa a ter no máximo 1 conexão a mais da `api`. Agendador: o expurgo é a 12ª tarefa `@Scheduled` (hoje são 11, com pool e piso em 11), então o pool sobe para **12**. Mudam `RuntimeSettingsGuard.MIN_SCHEDULER_POOL` (11 → 12) e o comentário dele, o padrão de `spring.task.scheduling.pool.size` em `application.yml`, o padrão de `SCHEDULER_POOL_SIZE` no `docker-compose.yml` e os testes do guard e do `ComposeFilesTest`. Memória da `api` sem mudança | NFR2.5 |

### Migração `0009-risk-control.sql`

- **Tabelas:**
  - valores em `numeric(38,6)`, preços em `numeric(38,12)`, percentuais em `numeric(9,4)` e quantidades em `numeric(38,18)`;
  - `CHECK (mode = 'PAPER')`;
  - chave estrangeira `order_rejection.evaluation_id` → `order_evaluation`, sem `ON DELETE`.
- **Índices:** os de scalability-design.md, inclusive o parcial do expurgo.
- **Gatilhos:**
  - recusam `UPDATE` nas quatro tabelas;
  - recusam `DELETE` em `order_rejection` e `rejection_reason_detail`;
  - em `order_evaluation`, aceitam `DELETE` só de quem é membro de `risk_retention`, de `APPROVED` e com `evaluated_at < now() - interval '30 days'`. A seleção do expurgo usa `evaluated_at < now() - interval '31 days'`, com o mesmo `now()` do banco na mesma instrução. A folga de 1 dia garante que toda linha selecionada passe no gatilho, mesmo com o lote rodando perto da meia-noite;
  - em `risk_purge_run`, aceitam `DELETE` só de membro de `risk_retention` e de linhas com mais de 90 dias.
- **Privilégios:** os da matriz abaixo.

### Matriz de privilégios (fecha o achado Major da revisão do NFR Design)

| Tabela | Papel da aplicação | `risk_retention` |
|---|---|---|
| `order_evaluation` | `INSERT`, `SELECT` | `SELECT`, `DELETE` |
| `order_rejection` | `INSERT`, `SELECT` | `SELECT (evaluation_id)` |
| `rejection_reason_detail` | `INSERT`, `SELECT` | nada |
| `risk_purge_run` | `SELECT` | `INSERT`, `SELECT`, `DELETE` |

A migração revoga do papel da aplicação o que os privilégios padrão do `10-roles.sql` concedem a mais (`UPDATE`, `DELETE`, `INSERT` em `risk_purge_run`). Ela também revoga `TRUNCATE` de todos nas quatro tabelas.

`risk_purge_run` é só de inserção, com uma única exceção: a limpeza das linhas com mais de 90 dias pela própria rodada do expurgo.

### Preparo dos papéis (`10-roles.sql`)

Segue o padrão de `audit_retention`:
- cria `risk_retention` como grupo sem login, de forma idempotente;
- concede o grupo só ao login de retenção (`DB_RETENTION_USER`);
- revoga o grupo do dono e da aplicação;
- dá `USAGE` no esquema ao grupo.

Os nomes `risk_retention` e `audit_retention` são fixos, porque a migração e os gatilhos os usam.

## Configuração da API

| Propriedade | Variável | Padrão | Uso |
|---|---|---|---|
| `spring.task.scheduling.pool.size` | `SCHEDULER_POOL_SIZE` | `12` (hoje `11`); o `RuntimeSettingsGuard` passa a recusar menos de 12 | 12ª tarefa: expurgo da U6 |
| `memetrade.riskcontrol.purge.cron` | — | `0 30 3 * * *` (UTC) | Expurgo diário, depois da purga da trilha às 03:00 |
| `memetrade.riskcontrol.purge.selection-age` e `trigger-age` | — | `31d` e `30d` fixos (o segundo é a constante do gatilho, conferida por teste) | Corte da seleção com folga de 1 dia sobre o gatilho |
| `memetrade.riskcontrol.purge.batch-size` | — | `1000` até o relatório do cenário `u6` fixar o valor | NFR2.5 |
| `memetrade.riskcontrol.purge.max-run` | — | `30m`, com bloqueio do ShedLock de 45 min | NFR10.6 |
| `memetrade.riskcontrol.freshness-max-age` | — | `60s` fixo | BR2.6 |
| Parâmetros da cópia da política (spread 0,5000, impacto 1,0000, taxa 0,3000) | — | Fixos no código | BR1.5; o teste de conferência com a política vigente da U7 garante a igualdade |

Nenhuma propriedade da U6 passa pelo Compose. Regras de risco não mudam por variável de ambiente.

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL 17 (`postgres`) | database | Tabelas, índices, gatilhos e privilégios da `0009`. As instruções da barreira correm sob o `lock_timeout` e o `statement_timeout` que a U7 define na transação do ciclo | Volume estimado abaixo de 2 GB em 8 semanas com a projeção de scalability-design.md |
| ShedLock (tabela `shedlock`) | coordenação de tarefas | Bloqueio `riskcontrol-purge` | Tabela da U2 |
| Pool de retenção da U6 (`RiskRetentionDataSource`) | database | Pool Hikari próprio do módulo `riskcontrol` com tamanho 1 e `connectionTimeout` de 30 s, aberto só durante a rodada. Usa o mesmo login de retenção da U2 (`DB_RETENTION_USER` e `DB_RETENTION_PASSWORD`), agora também membro de `risk_retention` | Sem variável nova. O bean da U2 (`AuditRetentionDataSource`) e a regra do `AuditArchitectureTest` não mudam. Uma regra ArchUnit da U6 permite só ao `RiskEvaluationPurgeJob` receber o pool da U6. Como cada Unit tem o seu pool, a purga da trilha (03:00, até 30 min) e o expurgo da U6 (03:30) nunca disputam a mesma conexão; o PostgreSQL aceita as duas sessões do mesmo login. Teste: duas rodadas seguidas e uma rodada da U6 com a purga da trilha em andamento, no mesmo login |
| Actuator (8081) | saúde | Indicador novo `riskcontrol`, fora de liveness e readiness | O `scripts/verify.sh` não muda |
| Cache, fila, CDN, DNS, balanceador | — | Não usados | — |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| PostgreSQL e volume `pgdata` | U1 | Todas | A U6 acessa só as próprias tabelas, e ninguém lê as tabelas da U6 |
| Login de retenção | U2 | U2, U6 | Cada Unit com o seu pool. O login herda os dois grupos (`audit_retention` e `risk_retention`), então o banco não impede a conexão da U6 de apagar `audit_event` nem a da U2 de apagar `order_evaluation`. Isso é uma convenção do código, sustentada por regras de ArchUnit: só o `AuditRetentionPurge` recebe o pool da U2 e só o `RiskEvaluationPurgeJob` recebe o da U6, e cada job só tem SQL das próprias tabelas. Na hospedagem futura, cada Unit ganha um login de retenção próprio |
| `OrderGate` (C14) | U6 | U5 | Só pela interface, dentro da transação do ciclo |
| `PortfolioRiskView` (C13) | U7 | U6 | Só leitura, dentro da transação do ciclo |
| `MarketDataQuery` (C11) | U4 | U6 | Preço de referência e liquidez |
| Publicador da trilha (C9) | U2 | U6 | Evento `ORDER_REJECTED` |
| Tabela `shedlock` e agendador | U2 | U2, U4, U7, U6 | Nomes de bloqueio próprios |

## Para a hospedagem futura

- Os privilégios da matriz valem em qualquer ambiente. O papel da aplicação nunca apaga nem altera registros de risco.
- O expurgo precisa da conexão de retenção em todo ambiente onde houver dados de beta.
