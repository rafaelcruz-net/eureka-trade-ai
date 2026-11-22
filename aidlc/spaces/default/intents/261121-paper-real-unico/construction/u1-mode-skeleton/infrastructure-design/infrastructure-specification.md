# Especificação de infraestrutura — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

A U1 não traz infraestrutura nova. Ela entra nas imagens que já existem (a imagem `api`, que roda a API e o worker, e a imagem `web`) e na atualização rolling do ECS já desenhada [Q1]. A única mudança de IaC é acrescentar três variáveis de ambiente, sem segredo, à definição de tarefa `api-worker` que já existe [Q2]. Essa mudança é feita na Code Generation; este estágio não altera nenhum arquivo de `infra/`.

Hoje nenhum ambiente na nuvem está provisionado. Tudo o que está abaixo vale quando o Terraform de `infra/terraform/` for aplicado (`team.md`, Deployment).

## Implantação

| Faceta | Escolha | Justificativa |
|---|---|---|
| Modelo de computação | Contêineres no ECS Fargate, nas tarefas que já existem: `api-worker` (contêineres `api` e `worker` da mesma imagem `api`, mais o sidecar `metrics-agent`) e `web`. Nenhuma família, serviço ou contêiner novo. | As rotas C1 a C4 rodam no processo da API e o ramo REAL do ciclo roda no worker (NFR1.11, NFR8.1) [Q1]. |
| Dimensionamento | Sem mudança: tarefa `api-worker` com `cpu = 1024` e `memory = 3072`; contêineres `api` e `worker` com 1024 MiB cada; `desired_count` atual. O pool `pnl-mode-block` (8 threads, fila de 16) roda dentro do contêiner `api`, sem CPU nem memória a mais. | O pool guarda só threads; o teto de 8 conexões fica dentro dos 20 do Hikari (`DB_POOL_MAX_SIZE = "20"` em `api_environment`) (NFR1.11) [Q2]. |
| Rede | Sem mudança: mesma VPC, sub-redes, grupos de segurança e balanceador. As rotas novas (`/api/v1/bots/real-eligibility`, `/api/v1/real/account-limits`, `/api/v1/pnl/by-mode`) passam pelo mesmo listener e grupo de destino da API. | A U1 não chama outro processo nem rede externa (NFR1.8, NFR3.13). |
| Armazenamento | Sem mudança: o mesmo PostgreSQL (RDS) da aplicação, 0 tabelas, 0 colunas, 0 índices e 0 migrações. | NFR1.13; o rollback fica só na imagem (`cicd-pipeline.md`). |
| Ambientes | Local (Docker Compose, Windows) e CI (Linux) para a verificação do esqueleto. Na nuvem: a devnet só para os testes do fundador; a mainnet só num SHA que já contém a U2 com o Build and Test aprovado. A U1 nunca vai para um ambiente com usuários antes da U2, nem para uma devnet com convidados. | NFR8.3, NFR8.4, BR10.3 [Q1]. |
| Abordagem de IaC | Terraform em `infra/terraform/`. Mudança única: três entradas no mapa `api_environment` do `module "services"` em `infra/terraform/main.tf` (ver "Configuração nova"). Nenhum recurso novo, nenhum módulo novo. | `team.md`, Deployment ("Flags ... versionadas no Terraform") [Q2]. |
| Segredos | Nenhum segredo novo. As listas `api_secrets` e `worker_secrets` de `modules/services/api-worker.tf` e os segredos de `modules/services/secrets.tf` não mudam. | NFR3.7 [Q5]. |
| Escala | A mesma regra de escala das tarefas da API e do worker. Nenhum gatilho de autoescala novo. | NFR1.11 [Q5]. |
| Custo | Nenhum serviço AWS novo e nenhum custo recorrente novo; o orçamento de US$ 350 por conta (`devnet` e `mainnet`) não muda. | NFR8.2 [Q5]. |

## Configuração nova

As três chaves têm valor padrão literal no `apps/api/src/main/resources/application.yml`, sem `${...}`, como `memetrade.real.enabled`. Na nuvem, elas entram como variáveis de ambiente da definição de tarefa, versionadas no Terraform, com o mesmo valor do padrão. O Spring Boot liga a variável à propriedade pela ligação flexível (relaxed binding): pontos viram `_`, hífens somem, tudo em maiúsculas. É o mesmo mecanismo que já liga `MEMETRADE_REAL_ENABLED` a `memetrade.real.enabled` [Q2].

**Ligação obrigatória por `@ConfigurationProperties`.** As três chaves têm hífen. A ligação flexível só casa `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT` com `memetrade.pnl.by-mode.block-timeout` quando a propriedade é lida por uma classe `@ConfigurationProperties` (`PnlByModeProperties` para `memetrade.pnl.by-mode.*` e o campo novo de `BotProperties` para `memetrade.bots.real-eligibility-timeout`). Com `@Value("${memetrade.pnl.by-mode.block-timeout}")`, a variável de ambiente não é garantida e o padrão do `application.yml` venceria em silêncio. Por isso a Code Generation **não usa `@Value`** para nenhuma das três chaves.

**Teste de integração obrigatório da ligação** (Code Generation, job `backend`):

| Caso | Entrada | Resultado esperado |
|---|---|---|
| Valores pelo ambiente | as três variáveis com valores diferentes dos padrões e dentro da faixa (por exemplo, `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT=700ms`, `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT=400ms`, `MEMETRADE_PNL_BYMODE_REALMAXORDERS=60000`) | o contexto sobe e `PnlByModeProperties` e `BotProperties` trazem exatamente esses valores |
| Valor fora da faixa | uma das variáveis fora da faixa (por exemplo, `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT=950ms`) | a subida falha na hora, com mensagem que nomeia a propriedade |

| Chave Spring | Variável de ambiente | Valor | Faixa aceita na subida | Contêiner | Onde fica no Terraform |
|---|---|---|---|---|---|
| `memetrade.pnl.by-mode.block-timeout` | `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT` | `800ms` | 100 ms a 900 ms | `api` | `infra/terraform/main.tf`, `module "services"`, mapa `api_environment` |
| `memetrade.bots.real-eligibility-timeout` | `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT` | `500ms` | 100 ms a 900 ms | `api` | `infra/terraform/main.tf`, `module "services"`, mapa `api_environment` |
| `memetrade.pnl.by-mode.real-max-orders` | `MEMETRADE_PNL_BYMODE_REALMAXORDERS` | `50000` | 15.000 a 200.000 | `api` | `infra/terraform/main.tf`, `module "services"`, mapa `api_environment` |

Regras da configuração:

| Regra | Decisão | Justificativa |
|---|---|---|
| Só no `api_environment` | As três variáveis vão só para o contêiner `api`. O worker não chama C1, C2 nem C4 e usa os padrões do `application.yml` (os mesmos valores). Se um dia o worker passar a chamar a elegibilidade, `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT` entra também em `worker_environment`, no mesmo `infra/terraform/main.tf`. | `ModePnlView` e a elegibilidade são chamados só na API (`logical-components.md`). |
| Valor igual ao padrão | O valor no Terraform é igual ao do `application.yml`. | A ordem entre `terraform apply` e a imagem não importa: uma imagem antiga ignora variáveis que não conhece, e uma imagem nova sem a variável usa o padrão. |
| Valor inválido | Um valor fora da faixa impede o contêiner `api` de subir, com mensagem que nomeia a propriedade (padrão de `BotProperties`). O health check do ECS e o circuit breaker devolvem a revisão anterior. | NFR1.9; `team.md`, Deployment (circuit breaker com volta automática). |
| Chaves que ficam só no `application.yml` | `memetrade.pnl.by-mode.pool-size` (8) e `memetrade.pnl.by-mode.queue-capacity` (16) não entram no Terraform. | Q2 decidiu só as três chaves; o pool fica fixo e testado. |
| Sem segredo | Nenhuma das três é segredo; nenhuma vai para `secrets` nem para o Secrets Manager. | NFR3.7. |
| Não é flag do REAL | As três chaves são parâmetros de ajuste, não flags do REAL. `MEMETRADE_REAL_ENABLED = "false"` e `MEMETRADE_REAL_EXITS_ENABLED = "false"` em `app_environment` não mudam. | NFR3.9; `team.md`, Deployment (mudar flag do REAL é implantação separada). |

## Chave da semente de teste

| Item | Decisão | Onde é conferido |
|---|---|---|
| `memetrade.real.seed-test-release` | Literal `false` no `application.yml`, sem `${...}`. | `RealSeedProfileGuardTest` (job `backend`) |
| `MEMETRADE_REAL_SEED_TEST_RELEASE` | Nunca aparece em `infra/terraform/**/*.tf`, em `environments/*.tfvars.example`, nos Dockerfiles, nas imagens, em `.github/workflows/` nem em `.env.example`. O único arquivo versionado que liga a semente é `infra/docker-compose.real.yml` (e2e local). | `RealSeedProfileGuardTest` e a guarda da semente no `scripts/verify.sh` (`cicd-pipeline.md`) |
| Linha comentada em `.env.example` | Hoje a linha 133 de `.env.example` tem `# MEMETRADE_REAL_SEED_TEST_RELEASE=`. **Obrigação da Code Generation:** remover essa linha. Uma chave só de teste não tem lugar no arquivo de exemplo. | A guarda é estrita: falha com **qualquer** ocorrência do nome nos arquivos varridos, inclusive em comentário. Sem a remoção, a guarda fica vermelha. |
| `SPRING_PROFILES_ACTIVE` na nuvem | Continua `production` (api), `worker,production` (worker) e `migrate` (tarefas avulsas). Nenhum valor com `test`. | `RealSeedProfileGuardTest` |
| Barreira de execução | O `RealTestSeeder` só age com `memetrade.real.network = LOCALNET`. Na nuvem, `MEMETRADE_REAL_NETWORK` é `DEVNET` ou `MAINNET` (`local.network_upper`), então ele impede o processo de subir se a semente for ligada por engano. | Teste de unidade dos três valores de rede (NFR3.8) |

## Serviços de infraestrutura

| Serviço | Papel | Configuração | Observações |
|---|---|---|---|
| PostgreSQL da aplicação (RDS, módulo `data`) | banco de dados | Sem mudança de instância, armazenamento, parâmetros ou réplicas. Hikari da API com 20 conexões fixas e espera de 2 s; o pool `pnl-mode-block` usa no máximo 8. | Leituras sem `FOR UPDATE`, com `statement_timeout` local por bloco (NFR1.7). 0 migrações (NFR1.13). |
| Balanceador da API (ALB, módulo `services`) | load-balancer | Sem mudança de listener, regras, grupo de destino ou health check. | A verificação de saúde continua em `/actuator/health/readiness` (`readinessState`, `db`, `migrations`); nenhuma rota da U1 entra nela (NFR4.7). |
| Sidecar `metrics-agent` (CloudWatch Agent) | coleta de métricas | Sem mudança: leitura de `/actuator/prometheus` a cada 120 s nas portas 8081 (api) e 8082 (worker); lista de permissão de `modules/observability/app-metrics.tf` sem entradas novas. | Os dois contadores novos são lidos e descartados pelo filtro da lista de permissão na U1 (ver `monitoring-design.md`). |
| CloudWatch Logs (grupos `/ecs/...` do `api-worker`) | agregação de logs | Sem mudança de grupo, retenção (`log_retention_days`) ou consultas salvas. | Os logs JSON da U1 levam `correlationId` (NFR3.11). |
| Cache, fila, busca, CDN, DNS | — | Não usados pela U1. | 0 serviços novos (NFR1.11). |

## Infraestrutura compartilhada

| Recurso compartilhado | Unit dona | Units consumidoras | Fronteira de acesso |
|---|---|---|---|
| Definição de tarefa `api-worker` (`modules/services/api-worker.tf`) e mapa `api_environment` (`infra/terraform/main.tf`) | já existente (intent `261113-trading-real-bot`) | U1 (três variáveis) e U2 (as que ela trouxer) | Só variáveis sem segredo; a U1 não muda CPU, memória, contêineres, papéis nem segredos. |
| PostgreSQL da aplicação | já existente | U1 e U2 | Cada módulo lê só as próprias tabelas pela interface pública (ArchUnit). |
| Endpoint `/actuator/prometheus` e lista de permissão do agente | já existente | U1 (dois contadores, fora da lista) | A U1 não muda a lista; uma entrada nova exige um estágio que a decida. |

## Implicações de segurança

| Mudança | Implicação | Risco |
|---|---|---|
| Três variáveis novas em `api_environment` | Sem segredo, sem permissão IAM nova, sem porta nova. O papel de implantação do GitHub não muda. | Baixo: um valor errado só impede a subida, e o circuit breaker devolve a revisão anterior. |
| Rotas novas atrás do mesmo ALB | Mesma autenticação, mesmo filtro de novo aceite dos termos e mesmo tenant do token (NFR3.1 a NFR3.3). | Coberto pelos testes do NFR3.x; nenhum controle de segurança é removido. |
| Chave da semente | Continua fora da nuvem; barreira por teste de configuração e por checagem de rede na subida. | Baixo (NFR3.8). |

## Sources

- `infrastructure-design-questions.md`: Q1 a Q5 e o resumo confirmado [Q1] [Q2] [Q3] [Q4] [Q5].
- `construction/u1-mode-skeleton/nfr-design/`: `performance-design.md` (tabela de configuração), `scalability-design.md` (processos, conexões, ambientes), `security-design.md` (guarda da semente, NFR3.8), `reliability-design.md` (NFR4.7), `observability-design.md`, `logical-components.md`, `traceability.json`.
- `construction/u1-mode-skeleton/nfr-requirements/scalability-requirements.md`: NFR8.1 a NFR8.4.
- `construction/u1-mode-skeleton/functional-design/functional-spec.md` e `rules.md` (BR10.3, BR10.4).
- `inception/domain-design/components.md` e `inception/contract-design/contract-summary.md` (C1 a C4, C8 a C10).
- Infraestrutura lida: `infra/terraform/main.tf` (`module "services"`, `app_environment`, `api_environment`, `worker_environment`), `infra/terraform/modules/services/api-worker.tf` (contêineres, `cpu`, `memory`, segredos, health check), `modules/services/metrics-agent.tf` (120 s, filtro da lista de permissão), `modules/observability/app-metrics.tf`, `infra/docker-compose.real.yml`, `infra/docker-compose.test.yml`, `apps/api/src/main/resources/application.yml`.
- `aidlc/spaces/default/memory/team.md`: Deployment e Way of Working; `project.md`: Forbidden e Mandated (devnet fechada, REAL e PAPER separados).

## Assumptions & Open Questions

1. [assumption] Os nomes das variáveis seguem a ligação flexível do Spring Boot (`MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT` etc.), com as chaves lidas por `@ConfigurationProperties` e literais no `application.yml`, sem `${...}`. O teste de integração da ligação prova os nomes.
2. **Aceito: NFR8.1 contra a Q2.** O critério do NFR8.1 diz "0 mudanças nas definições de tarefa do ECS"; a Q2, confirmada depois, coloca as três chaves como variáveis da definição de tarefa. O desvio é aceito porque:
   - não há recurso novo nem mudança de CPU, memória, contêineres ou segredos;
   - os valores no Terraform são iguais aos padrões do `application.yml`;
   - o rollback só da imagem é seguro, porque a imagem anterior ignora as variáveis.
3. **Pendência para a Code Generation (alinhar textos, sem editar aqui):** o `performance-design.md` do NFR Design diz que os valores ficam "fixos e nunca lidos do ambiente", e o critério do NFR8.1 no `nfr-requirements/scalability-requirements.md` diz "0 mudanças nas definições de tarefa do ECS". Os dois textos precisam ser alinhados a este desenho: as três chaves são lidas do ambiente por `@ConfigurationProperties`, e a definição `api-worker` ganha três variáveis sem segredo. A Code Generation lê todos esses arquivos e segue este desenho.
4. [assumption] Como os valores do Terraform são iguais aos padrões, aplicar o Terraform antes ou depois da imagem não muda o comportamento.
