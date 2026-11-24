# Especificação de infraestrutura — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` (R-01, R-05) e da decisão do fundador D-INF3 (`monitoring-design.md`): na nuvem, a evidência dos contadores são os logs e a auditoria; o `terraform plan` continua vazio.

A U3 não traz infraestrutura nova [Q1]. Ela entra na imagem `api` que já existe (contêiner `api` da tarefa `api-worker`) e na atualização rolling do ECS já desenhada. Não há:

- migração (0 tabelas, colunas, índices ou changesets);
- variável de ambiente nova, nem chave nova no `application.yml`;
- mudança na definição de tarefa: os três contadores novos ficam fora da lista de permissão do CloudWatch, então o sidecar `metrics-agent` não muda;
- segredo, serviço AWS, pool de conexões, réplica, fila ou cache novos.

O único elemento de execução novo é o executor `pnl-read-audit` (1 thread, fila de 16), com o filtro em memória de 60 minutos na frente dele. Os dois usam constantes do código, validadas por teste, e gravam pelo pool `pnl-mismatch-audit` da U2, que não muda. Por isso a conta de conexões não muda e o rollback é só da imagem.

Este estágio não altera nenhum arquivo. Hoje nenhum ambiente na nuvem está provisionado; o que vale para a nuvem vale quando o Terraform de `infra/terraform/` for aplicado (`team.md`, Deployment).

## Implantação

| Faceta | Escolha | Justificativa |
|---|---|---|
| Modelo de computação | Contêineres no ECS Fargate, na tarefa `api-worker` que já existe. C4 com `market` e `RANGE`, `listBots` com contagens, C5, C6 e a auditoria das recusas rodam no contêiner `api`. O `worker` não ganha uso novo. Nenhuma família, serviço ou contêiner novo. | NFR1.13 [Q1]; `logical-components.md`, "Processos e recursos compartilhados". |
| Definições de tarefa | Sem revisão nova. Não mudam: `api_environment`, `worker_environment` (`infra/terraform/main.tf`), imagens além da tag do SHA, segredos, CPU, memória, contêineres e o sidecar `metrics-agent` (`CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT` são gerados de `app_metric_declarations`, que não muda). | NFR8.1, NFR8.4 [Q1] [Q2]. Diferente da U2 (D-INF1), aqui o `terraform plan` sai vazio. |
| Dimensionamento | Sem mudança: tarefa `api-worker` com `cpu = 1024` e `memory = 3072`, `api` e `worker` com 1024 MiB cada, `desired_count` atual. O executor `pnl-read-audit` acrescenta 1 thread e uma fila limitada de 16 tarefas; o filtro em memória fica limitado a 10.000 chaves. | NFR1.13, NFR3.14; `scalability-design.md`. |
| Rede | Sem mudança de VPC, sub-redes, grupos de segurança, balanceador, listener ou grupo de destino. As rotas novas (`/api/v1/bots/{botId}/pnl/lines`, `/api/v1/pnl/compare`) ficam sob o mesmo caminho `/api/v1/` que o ALB já encaminha. | A U3 não chama outro processo nem rede externa; 0 chamadas ao TxSigner, ao KMS ou ao RPC (NFR3.8). |
| Armazenamento | O mesmo PostgreSQL (RDS `app`), sem mudança de instância, armazenamento, parâmetros ou réplicas. 0 migrações. C5 e C6 leem pelo índice `real_ledger_entry_wallet_created_idx` da `0023` (U2). A única gravação é o evento de auditoria na trilha que já existe, limitado a 1 por hora por pessoa e ação. | NFR1.15 [Q1]. |
| Ambientes | Local (Docker Compose, Windows) e CI (Linux). Na nuvem, a devnet só para os testes do fundador e a pré-produção do mesmo SHA; a mainnet só depois da devnet. Nenhuma tela, rota ou configuração da U3 abre a devnet a usuários. | NFR8.3; `project.md`, Forbidden (devnet fechada). |
| Abordagem de IaC | Terraform em `infra/terraform/`, **sem mudança**. Nenhum recurso, variável, saída ou módulo novo. | NFR8.1 [Q1]. |
| Configuração da aplicação | Nenhuma chave nova em `application.yml`. Os tamanhos do executor e do filtro são constantes do código (abaixo). As chaves que a U3 usa ficam como estão: `memetrade.pnl.by-mode.*` (U1) e `memetrade.pnl.mismatch-audit.*` (U2). | NFR8.1 [Q1]. |
| Segredos | Nenhum segredo novo. A gravação da auditoria usa o login da aplicação do pool `pnl-mismatch-audit` (`DB_USER`, `DB_PASSWORD`). As listas `api_secrets` e `worker_secrets` de `modules/services/api-worker.tf` não mudam. | NFR3.7 [Q1]. |
| Escala | A mesma regra de escala da tarefa `api-worker`. Nenhum gatilho de autoescala novo. Com mais de uma tarefa, cada uma tem o próprio executor e o próprio filtro; o limite de 1 evento por hora é garantido pela trilha, sob trava consultiva. | `scalability-design.md`, "Escala horizontal". |
| Custo | US$ 0 a mais por mês: 0 métricas no CloudWatch, 0 alarmes, 0 recursos. O orçamento de US$ 350 por conta não muda. O cenário de carga ampliado roda nos minutos do Actions do repositório privado. | NFR8.2 [Q1] [Q2]. |
| Rollback | Só a imagem volta. Não há migração, flag nem configuração a desfazer. | `team.md`, Deployment (Rollback) [Q1]. |

## Constantes do executor e do filtro

Constantes do código, não configuração. Nenhuma entra no `application.yml`, no `.env.example`, no Compose ou no Terraform [Q1]. Os nomes são propostas do NFR Design; a Code Generation pode ajustá-los mantendo valor e teste.

| Constante | Valor | Onde | Conferida por | NFR |
|---|---|---|---|---|
| `THREADS` do executor `pnl-read-audit` | 1 | `ReadRefusalAudit` (`pnl.application`) | teste de unidade do executor e `ReadRefusalAuditLimitIT` | NFR3.14 |
| `QUEUE` do executor | 16, `ArrayBlockingQueue`, `AbortPolicy` (fila cheia conta como falha, sem espera do pedido) | idem | teste de fila cheia: `pnl_read_audit_failures_total{action}` sobe e a resposta não muda | NFR3.14, NFR3.16 |
| `BUDGET` da gravação | 200 ms | idem | `ReadRefusalAuditLimitIT` | NFR3.14 |
| Janela do filtro em memória | 60 minutos por pessoa e ação | idem | teste de unidade com relógio injetável | NFR3.14, NFR1.15 |
| Teto de chaves do filtro | 10.000; ao chegar ao teto com todas as chaves ainda dentro dos 60 minutos, a entrada mais antiga sai para dar lugar à nova (a nova recusa nunca é perdida pelo filtro; a trilha, sob trava consultiva, continua sendo a fonte do limite de 1 por hora e deduplica uma repetição da chave expulsa) | idem | teste de unidade: o filtro nunca passa do teto; com 10.000 chaves dentro da janela, a 10.001ª entra, a mais antiga sai, e a recusa nova segue ao executor | NFR3.14 |
| Espera por conexão do `pnl-mismatch-audit` | 250 ms (constante da U2 em `MismatchAuditDataSource`) | `audit.infra`, sem mudança | testes da U2 | NFR3.14 |

O `RuntimeSettingsGuard` não muda: ele confere as propriedades de pool (`spring.datasource.hikari.maximum-pool-size`, scheduler e `memetrade.pnl.mismatch-audit.pool-size`), e a U3 não acrescenta propriedade nem pool.

## Conexões do banco

O executor `pnl-read-audit` não abre conexão própria: ele grava pelo pool `pnl-mismatch-audit` da U2 (2 conexões fixas). Os 404 de C5 e C6 usam 1 conexão curta do Hikari principal, na thread do pedido (`botOf` e `ownedByAnotherTenant` pela chave primária). As leituras de C4, C5 e C6 passam pelo pool `pnl-mode-block` (8 threads, fila de 16), no Hikari principal de 20.

| Conta | Antes da U3 (com a U2) | Com a U3 | Limite | Onde é conferido |
|---|---|---|---|---|
| `docker-compose.yml` | 67 | **67** (sem mudança) | `max_connections` 100 | `ComposeFilesTest.theConnectionTallyFitsTheDatabaseLimit`, sem mudança; o comentário do `docker-compose.yml` não muda |
| Overlay do REAL (`infra/docker-compose.real.yml`) | 78 | **78** | 100 | o mesmo teste |
| RDS `app`, regime de uma tarefa `api-worker` | no máximo 66 | **no máximo 66** | cerca de 112 (`db.t4g.micro`) | `infrastructure-specification.md` da U2 |
| RDS `app`, teto teórico da atualização rolling | cerca de 127 | **cerca de 127** | cerca de 112 | pendência D-INF2 do responsável, anterior à U3 e sem mudança por ela |
| Hikari principal da API | 20 (8 no máximo em leituras de P&L pelo `pnl-mode-block`) | 20 (o mesmo teto de 8) | — | `RuntimeSettingsGuard` (10 a 50) |
| `pnl-mismatch-audit` | 2 conexões, 2 threads do `ReconciliationAuditGate` | 2 conexões, 3 threads (2 do `ReconciliationAuditGate` + 1 do `pnl-read-audit`) | 2 | `RuntimeSettingsGuard` (1 a 2); `ReadRefusalAuditLimitIT` mede a espera |

A disputa de 3 threads por 2 conexões é limitada pelo filtro de 60 minutos (no máximo 2 gravações por pessoa por hora vindas da U3). Se o `ReadRefusalAuditLimitIT` mostrar disputa mesmo assim, o ajuste é do responsável em outro estágio; este desenho não muda o pool (`scalability-design.md`, premissa 2).

## Serviços de infraestrutura

| Serviço | Papel | Configuração | Observações |
|---|---|---|---|
| PostgreSQL da aplicação (RDS `app`, módulo `data`) | banco de dados | Sem mudança de classe (`db.t4g.micro`), armazenamento, grupo de parâmetros ou réplicas. 0 tabelas, 0 índices. | Leituras pelo índice da `0023`; gravação só do evento de auditoria limitado (NFR1.15). |
| Hikari principal (API 20, worker 36) | pool de conexões | Sem mudança. | `listBots` e os 404 usam conexões curtas na thread do pedido. |
| Pool `pnl-mode-block` | executor | Sem mudança (8 threads, fila de 16, prazo de 800 ms por bloco em `memetrade.pnl.by-mode.block-timeout`). Passa a atender também C5 e os lados de C6. | Antepara: 12 das 20 conexões ficam para o resto da API (NFR1.13). |
| Pool `pnl-mismatch-audit` | pool de conexões | Sem mudança (2 conexões, espera de 250 ms, `memetrade.pnl.mismatch-audit.pool-size = 2`). Passa a gravar também `TENANT_MISMATCH` e `PNL_COMPARE_TEMPLATE_MISMATCH`. | Fora da prontidão (não é bean `DataSource`). |
| Executor `pnl-read-audit` | executor (novo, dentro da API) | 1 thread, fila de 16, `AbortPolicy`, orçamento de 200 ms; constantes. | Fora da prontidão; nunca segura a resposta (NFR1.10, NFR3.14). |
| Balanceador da API (ALB, módulo `services`) | load-balancer | Sem mudança de listener, regras, grupo de destino ou health check. | A prontidão (`readinessState,db,migrations`) não muda (NFR4.6). |
| Sidecar `metrics-agent` | coleta de métricas | Sem mudança: leitura a cada 120 s; os três contadores novos são descartados pelo filtro `keep`. | NFR8.4 [Q2]. |
| CloudWatch Logs (grupos `/ecs/...` do `api-worker`) | agregação de logs | Sem mudança de grupo, retenção ou consultas salvas. | Os eventos de log da U3 vão em JSON com `correlationId`, só com ids e enums (NFR3.10, NFR3.11). |
| Cache, fila, busca, CDN, DNS | — | Não usados. | 0 serviços novos (NFR1.13). |

## Infraestrutura compartilhada

| Recurso compartilhado | Unit dona | Units consumidoras | Fronteira de acesso |
|---|---|---|---|
| Pool `pnl-mismatch-audit` | U2 | U2 (`ReconciliationAuditGate`) e U3 (`ReadRefusalAudit`) | Só pelo `MismatchAuditWriter` (ArchUnit `MISMATCH_POOL_ONLY_IN_ITS_WRITER`); o `pnl` chega à auditoria só por `audit.api` (`PNL_USES_ONLY_THE_AUDIT_API`). |
| Pool `pnl-mode-block` | U1 | U1 (C4), U2 (`GET /api/v1/real/pnl`), U3 (C4 com `market` e `RANGE`, C5, C6) | Só pelo `ModeBlockExecutor` e pela `ModePnlView`. |
| PostgreSQL da aplicação | já existente | U1, U2, U3 e o intent `261113-trading-real-bot` | A U3 só lê o ledger pela C10 (`RealBotPnlQuery.botLines`) e os bots pela C8 (`BotDirectory`); a única consulta fora do tenant é `ownedByAnotherTenant`, só booleana e restrita por ArchUnit (`ONLY_THE_404_PATH_ASKS_OTHER_TENANTS`, decisão F-2). |
| Lista de permissão do agente (`app-metrics.tf`) | Observability Setup do `261113` | U2 (1 métrica); a U3 não entra | Entrada nova só com decisão de estágio; a Q2 decide não entrar. |
| Cenário de carga `pnl-by-mode` (`scripts/load/`) e o texto pronto do `load-test.yml` | U2 | U3 (amplia para 9 leituras e 7 portões novos) | O texto completo fica no `code-summary.md` da U3, que diz substituir o da U2; o artefato da U2 não é editado; só o responsável aplica (`cicd-pipeline.md`). |

## Implicações de segurança

| Mudança | Implicação | Risco |
|---|---|---|
| Executor `pnl-read-audit` | Sem credencial nova; grava pelo login da aplicação do pool da U2; descarta (e conta) quando a fila enche, sem afetar a resposta. | Baixo (NFR3.14, NFR3.16). |
| Consulta `ownedByAnotherTenant` | Única consulta fora do tenant de quem pede; devolve só um booleano, nunca dado do bot; só o caminho dos 404 a chama. | Baixo, com a regra ArchUnit nova e o `BotNotFoundTimingParityIT` (NFR3.1, NFR3.17). |
| Contadores no Prometheus | Etiquetas só de enum (`market`, `cause`, `action`), sem tenant, pessoa, bot, carteira ou `correlationId`. `/actuator/prometheus` fica na porta de gestão 8081, como hoje. | Baixo (NFR3.7). |
| Infraestrutura, IAM, rede, criptografia | Nada muda. Nenhum controle de segurança é removido ou contornado. | Nenhum. |
| REAL e PAPER | Nenhum total soma os modos; nenhuma flag do REAL muda; o saque não depende de nada da U3. | Coberto por NFR4.1 a NFR4.5; `project.md`, Mandated e Forbidden. |

## Sources

- `infrastructure-design-questions.md` desta pasta: Q1 a Q3 e o resumo confirmado [Q1] [Q2] [Q3].
- `construction/u3-pnl-views/nfr-design/`: `scalability-design.md` (pools, conexões, constantes, custo), `logical-components.md` (recursos compartilhados, componentes, ArchUnit), `security-design.md` (NFR3.14, F-1, F-2), `observability-design.md`, `traceability.json`.
- `construction/u3-pnl-views/nfr-requirements/`: `scalability-requirements.md` (NFR1.12 a NFR1.15, NFR8.1 a NFR8.3), `security-requirements.md` (NFR3.14, NFR3.17).
- `construction/u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md`: formato, conta de conexões (67, 78, cerca de 112, D-INF2), pool `pnl-mismatch-audit`.
- Arquivos lidos: `infra/terraform/modules/observability/app-metrics.tf` (`app_metric_declarations`, sem as métricas da U3), `infra/terraform/main.tf` (`MEMETRADE_PNL_BYMODE_*`), `apps/api/src/main/resources/application.yml` (`memetrade.pnl.by-mode`, `memetrade.pnl.mismatch-audit`, prontidão), `apps/api/src/main/java/com/memetrade/shared/config/RuntimeSettingsGuard.java`, `apps/api/src/main/java/com/memetrade/pnl/application/ReconciliationAuditGate.java`, `apps/api/src/main/java/com/memetrade/audit/infra/MismatchAuditDataSource.java`, `docker-compose.yml` (comentário da conta, 67 e 78).
- `aidlc/spaces/default/memory/team.md`: Deployment (ambientes, rollback só da imagem, orçamento); `project.md`: Forbidden (devnet fechada, sem soma dos modos), Corrections (CloudWatch Agent a 120 s, US$ 350).

## Assumptions & Open Questions

1. [assumption] Os nomes `ReadRefusalAudit`, `THREADS`, `QUEUE` e `BUDGET` são propostas do NFR Design; a Code Generation pode trocá-los mantendo valor e teste.
2. A pendência D-INF2 do responsável (teto teórico do RDS na atualização rolling, cerca de 127 contra cerca de 112) continua aberta; a U3 não a muda, porque soma 0 conexões.
3. [assumption] Se o `ReadRefusalAuditLimitIT` mostrar disputa no `pnl-mismatch-audit`, um pool próprio para a U3 volta ao responsável em outro estágio; a Q1 rejeitou essa opção agora.
