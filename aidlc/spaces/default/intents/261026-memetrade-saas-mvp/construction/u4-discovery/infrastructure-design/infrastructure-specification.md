# Especificação de infraestrutura — U4 Descoberta e avaliação

Infraestrutura da Unit `u4-discovery` no ambiente local do beta (Docker Compose no Windows, CI no GitHub Actions), a partir do desenho não funcional em [../nfr-design/](../nfr-design/) e das decisões de [infrastructure-design-questions.md](infrastructure-design-questions.md). Não há nuvem nem hospedagem nesta etapa (C-10, C-12); a U4 não cria serviço novo no Compose.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Modelo de computação | Módulos `marketdata`, `scanner` e `riskassessment` dentro do processo `api` existente (contêiner Spring Boot da U1) | Monólito modular (ADR da U1); nada novo para operar |
| Rede | Sem mudança: `api` em `127.0.0.1:8080` (aplicação) e `127.0.0.1:8081` (gestão); nenhum acesso de saída, porque as fontes são simuladas | NFR6.6; C-06 |
| Armazenamento | PostgreSQL 17 existente; tabelas novas `observation_sample`, `risk_weight_set`, `risk_assessment`, `source_status_mark`; colunas novas em `data_source` | logical-components.md; NFR2.5 (histórico < 2 GB) |
| Migrações | `0006-discovery.sql` aplicado pelo `db-migrate` com o dono do esquema (padrão da U2); privilégios de leitura e escrita da aplicação nas tabelas novas pelos privilégios padrão do `10-roles.sql`; a versão 1 dos pesos semeada na migração; estado `PENDING` só para fontes sem `last_updated_at` | BR4.3, NFR10.6 |
| Ambientes | Só local (uso diário e script de carga) e CI; staging e produção ficam para a hospedagem | `team.md` (Deployment) |
| IaC | Docker Compose versionado (`docker-compose.yml`, `infra/docker-compose.test.yml`); sem IaC de nuvem | Decisão da U2 (só local) |
| Dimensionamento | Pool principal HikariCP de 10 para **20** conexões (Q1); pool do agendador de 3 para 7 linhas de execução; executor `market-source-calls` de 2 linhas; executor `risk-recalc` de 1 linha; PostgreSQL local com `max_connections` 100 (sobra para o pool de retenção da U2 e o `db-migrate`); orçamento detalhado abaixo | NFR3.1 (achado R-01 da NFR Design), reliability-design.md (achado R-05) |

## Configuração da API: propriedade, variável e passagem pelo Compose

O `docker-compose.yml` lista o ambiente da `api` de forma explícita; cada variável abaixo entra nessa lista como `NOME: ${NOME:-padrão}`, e o `application.yml` lê a propriedade com o mesmo padrão. Assim o valor chega ao contêiner no uso diário, no `scripts/verify.sh` e no `scripts/load-test.sh` (que exporta os valores da carga antes de subir o Compose). O overlay `infra/docker-compose.test.yml` não precisa repetir as chaves.

| Propriedade no `application.yml` | Hoje (U1) | Variável | Padrão novo | Uso |
|---|---|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | `10` fixo | `DB_POOL_MAX_SIZE` | `20` | Pool principal (Q1) |
| `spring.task.scheduling.pool.size` | `3` | `SCHEDULER_POOL_SIZE` | `7` | 3 da U1/U2 mais 4 da U4 |
| `memetrade.market.sources` | — (uma fonte fixa) | `MARKET_SOURCES` | `sim-a,sim-b` | Fontes ativas; só adaptadores simulados no beta |
| `memetrade.market.collect-interval` | `15s` | `MARKET_COLLECTION_INTERVAL` | `30s` | Intervalo por fonte, de 5 s a 60 s (NFR1.2 vale para 30 s) |
| `memetrade.market.simulated.token-count` | `30` | `MARKET_SIMULATED_TOKENS_PER_SOURCE` | `200`; `2000` no script de carga | 50 tokens em comum entre A e B |
| `memetrade.market.simulated.seed` | `42` | `MARKET_SIMULATED_SEED` | `42` (fixo) | Determinismo do smoke e da CI; nunca aleatório por padrão |
| `memetrade.market.divergence-tolerance-percent` | — | `MARKET_DIVERGENCE_TOLERANCE_PERCENT` | `5.0000` | Tolerância por campo |
| `memetrade.scanner.rate-limit-per-minute` | — | `SCANNER_RATE_LIMIT_PER_MINUTE` | `60` | Limite por pessoa e rota |

Nenhuma variável nova é segredo; o `.env.example` ganha as chaves com os padrões comentados. Valor fora da faixa falha a partida com mensagem que nomeia a variável. Um teste de arquivo (`ComposeFilesTest`, existente) confere que cada variável da tabela está no ambiente da `api`.

## Orçamento de conexões e memória

| Consumidor | Conexões no pico | Observação |
|---|---|---|
| Consultas da lista e do detalhe (20 usuários) | até 12 | Consultas curtas; o restante espera até 2 s (`connection-timeout`) |
| Coleta (2 fontes) | 2 | Uma transação por coleta |
| Recálculo (`risk-recalc`) | 1 | Lotes de 500 |
| Limpeza do histórico da U4 | 1 | Lotes de 10 000 |
| Relay da trilha, limpeza de idempotência e tarefas diárias da U2 | até 3 | Já existentes |
| Folga | 1 | — |
| **Total** | **20** | Pool de retenção da U2 (1) fica fora; PostgreSQL com `max_connections` 100 |

Memória: o contêiner `api` continua com `mem_limit: 1g`. Acréscimo estimado da U4: recálculo de 2 000 tokens em memória (menos de 50 MB), limitador com até 10 000 chaves (menos de 5 MB) e 20 conexões a mais no pool (menos de 20 MB). A JVM usa `MaxRAMPercentage` já configurado na imagem; o script de carga registra o uso de memória do contêiner (`docker stats`) e reprova acima de 900 MB.

## Limite por instância (NFR2.8)

O contador de requisições fica na memória do único contêiner `api`. Antes de rodar mais de uma instância (decisão da hospedagem), o contador passa para um armazenamento compartilhado; até lá, é risco aceito, registrado também em [security-design.md](../nfr-design/security-design.md).

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL 17 (`postgres`) | database | Mesmo contêiner e volume `pgdata`; índices de performance-design.md; `observation_sample` com restrição única `(chain, mint, source_id, field, bucket_start)` e índices `(chain, mint, field, observed_at)` e `(observed_at)`; `autovacuum_vacuum_scale_factor 0.02` em `observation_sample` (apagamento horário) | Volume estimado do histórico < 2 GB |
| HikariCP (na `api`) | pool de conexões | `maximumPoolSize` 20, `minimumIdle` 5, `connectionTimeout` 2 s (a espera entra no orçamento do NFR3.1) | Métrica `hikaricp_connections_pending` lida no script de carga |
| ShedLock (tabela `shedlock`) | coordenação de trabalhos | Bloqueios `market-collect-sim-a`, `market-collect-sim-b`, `risk-recalculate`, `market-history-cleanup` | Tabela já criada pela U2 |
| Adaptadores simulados A e B | fontes de mercado | Gerados em memória a partir da semente; `simulated = true`; nenhuma chamada de rede | Fixtures gravadas nos testes |
| Actuator (porta 8081) | saúde e métricas | `marketSources` substitui `marketSource` e mantém `lastUpdatedAt` no detalhe; `riskRecalculation`; ambos fora da prontidão; `DEGRADED` mapeado para 200 (já existente) | O `scripts/verify.sh` continua sem mudança (achado R-03) |
| Cache, fila, CDN, DNS, balanceador | — | Não usados | Uma instância local; revistos com a hospedagem |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| PostgreSQL e volume `pgdata` | U1 | Todas | Cada Unit só nas próprias tabelas; a U4 não lê tabelas de outras Units |
| Pool principal (20 conexões) | U1 | Todas | Tamanho decidido aqui (Q1) |
| Tabela `shedlock` e pool do agendador | U2 | U2, U4 | Nomes de bloqueio próprios; 4 linhas de execução da U4 |
| Publicador da trilha (C9) | U2 | Todas | A U4 só publica `RISK_WEIGHTS_ACTIVATED` |
| Filtros da U1 (autenticação, caminho, papel) | U1 | Todas as rotas | Sem mudança |
| Indicador de saúde `marketSource` | U1 | Compose, `scripts/verify.sh` | Substituído por `marketSources` com o mesmo campo `lastUpdatedAt` |
