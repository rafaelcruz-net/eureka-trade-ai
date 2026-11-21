# Relatório de validação da infraestrutura — intent `261113-trading-real-bot`

Data: 2026-11-20. Conversa: português (Brasil). Commit de referência: `3497b2bc49052f01adb93de7094a05880532222b` (`main`), mais as mudanças deste estágio em `infra/terraform/`, `infra/postgres/bootstrap/Dockerfile` e `.gitignore` (sem commit).

**Resumo.** Não existe conta AWS, então nada foi provisionado (Q1). Este estágio fechou as lacunas da IaC que o `cd-config.md` apontou e mais seis que apareceram na leitura (seção 2). Depois, a revisão de segurança (`security-posture-review.md`) deu "aprovado com ressalvas", e o responsável pediu mudanças: **SP-01, SP-02, SP-03, SP-05, SP-08 e SP-11 foram corrigidos na IaC** (seção 10), e o resto virou a tabela de pendências obrigatórias (seção 11). A validação foi toda feita sem nuvem, numa cópia de `infra/terraform`, com Docker:
- `terraform fmt -check`: sem diferença;
- `init -backend=false` e `validate`: válidos nas duas raízes;
- `terraform test` com provedor simulado: 10 cenários na raiz (os 5 de antes e 5 novos, do papel de implantação, das políticas das chaves, da confiança do papel do TxSigner, dos endpoints e da detecção) e 1 novo na raiz `org` (SCP): todos verdes;
- Checkov: 0 falhas (517 aprovados, 77 supressões, todas com justificativa na linha), contra 41 falhas antes do estágio;
- 7 classes de teste Java que leem `infra/`: verdes, inclusive o `KmsPolicyDocumentsTest` com os modelos de política alterados.

Os 103 recursos anteriores passaram a 227 (203 antes da correção da revisão). As 7 metas de ambiente continuam `Unverified`; a seção 8 diz o que fecha cada uma.

**Regras seguidas:** nenhum `terraform plan` ou `apply` contra uma conta, nenhuma credencial usada, nenhum valor de segredo escrito, nenhum arquivo em `.github/workflows/` nem o `scripts/verify.sh` editado, nenhum commit. A revisão de segurança do `devsecops` vem a seguir, numa passada de revisor. A seção 4 só registra as implicações de cada mudança, como pede a regra da fase Operation.

## 1. Decisões aplicadas

| Decisão | Origem | Onde |
|---|---|---|
| Nada provisionado; IaC completa e validada offline | Q1 | este relatório |
| Contas `devnet` e `mainnet` separadas num AWS Organizations; SCP de região e de chave numa raiz própria, não aplicada | Q2 | `infra/terraform/org/` |
| VPC sem NAT na subrede do TxSigner; endpoints para KMS, Secrets Manager, ECR, logs, monitoring e STS; gateway do S3; grupos com saída explícita; NACLs padrão | Q3 | `modules/network` |
| Saída para a internet (RPC, Jupiter, CoinGecko, GitHub) por **um NAT gateway gerenciado**, só na subrede B. O desenho da U1 previa uma instância NAT `t4g.nano`. A troca custa cerca de US$ 26 por mês a mais por conta e evita uma instância para corrigir e manter de pé. Um caminho restrito por nome de domínio (AWS Network Firewall) custaria cerca de US$ 290 por mês e ficou de fora | Q3 e este estágio | `modules/network` |
| Segredos no Secrets Manager, injetados por `secrets` da definição de tarefa; cada contêiner só recebe os seus; parâmetros sem segredo versionados no Terraform | Q4 | `modules/services` |
| **URLs de RPC como segredo** (desvio da letra da Q4, que as listava como parâmetro): a URL de um provedor de RPC costuma levar a chave de API dele. A guarda do `deploy.yml` aceita o nome em `secrets` (ela lê `environment` e `secrets`) | Q4 (espírito: nenhum segredo em variável simples) | `secrets.tf`, `api-worker.tf` |
| Contas isoladas; o TxSigner só é alcançado dentro da VPC | Q5 | seção 3 |
| Web e admin no ECS, atrás do mesmo balanceador (`WEB_ADMIN_ON_ECS=true`), numa subrede privada **sem internet** | brief e este estágio | `web-admin.tf` |

## 2. Lacunas fechadas na IaC

| Lacuna | Fonte | Resolução | Arquivos |
|---|---|---|---|
| Serviço `api-worker` com load balancer, target group e health checks | `cd-config.md` 2 e 5.2 | Família `${name}-api-worker` com os contêineres `api` e `worker`, 1 vCPU e 2 GB. Rolling 100/200 com circuit breaker. O target group da API checa a readiness em `:8081`. O worker usa `HealthProbe` em `:8082`, porque divide a rede da tarefa com a API (ver os desvios abaixo). | `modules/services/api-worker.tf`, `alb.tf` |
| Famílias avulsas `-txsigner-migrate` e `-db-migrate` | `cd-config.md` 5.3 | Cada uma com o login dono do próprio esquema, que nenhuma outra família recebe. Log em `/ecs/${name}/migrate`. | `modules/services/one-off.tf` |
| Papéis de execução (o TxSigner não tinha nenhum) | `cd-config.md` 2 | 9 papéis, um por família (o 9º, `txsigner-probe`, veio da correção do SP-01). Cada um faz pull só das próprias imagens, escreve só no próprio grupo de log e lê só os próprios segredos. A confiança exige `aws:SourceAccount`. **Só 4 entram em `passable_role_arns`** do papel de implantação (`api-worker`, `web`, `admin`, `db-migrate`); os outros 5 e o papel de tarefa do TxSigner são exclusivos do fundador (seção 10). | `modules/services/iam.tf`, `main.tf` (raiz) |
| Papéis de tarefa com menor privilégio | brief | Só o TxSigner tem papel de tarefa: o do KMS, mais a leitura do próprio token de serviço (NFR1.5). API, worker, web e admin **não têm papel de tarefa**, então não têm nenhuma permissão AWS. | `iam.tf` |
| Grupos de log com retenção | `cd-config.md` 2 | `/ecs/${name}/{txsigner,api-worker,web,admin,migrate,bootstrap}`, com 90 dias | `modules/services/logs.tf` |
| Saída explícita dos grupos de segurança | `cd-config.md` 2 | Seção 3 | `modules/network`, `modules/data` |
| Onde rodam web e admin | `cd-config.md` 2 e 8 | ECS atrás do balanceador, com regras por host | `web-admin.tf`, `alb.tf` |
| Segredos por tarefa | Q4 | 11 segredos novos e os 2 que já existiam, todos sem valor (seção 5) | `secrets.tf` |
| SCP e organização das contas | Q2 | Raiz separada, não aplicada | `infra/terraform/org/` |
| **Achado:** a subrede pública e a privada B não tinham rota (sem internet gateway, sem NAT) | leitura de `modules/network` | Internet gateway, rota pública e NAT gateway com rota só na subrede B | `modules/network` |
| **Achado:** o balanceador e o subnet group do RDS exigem duas zonas; o desenho tinha uma | regras da AWS | `zone_b` com só a segunda perna do balanceador e a segunda subrede dos subnet groups, sem nenhuma carga | `modules/network`, `modules/data` |
| **Achado:** os RDS não tinham `db_name`, mas as aplicações conectam em `memetrade` e no `txsigner` | `application.yml` | `db_name` por banco, `availability_zone` fixa e um subnet group por banco | `modules/data` |
| **Achado:** o `AuditStartup` exige `track_commit_timestamp` (ERROR sem ele); o Compose liga isso e desliga SQL e parâmetros no log | `docker-compose.yml`, `AuditStartup` | Grupo de parâmetros `postgres17` com os mesmos valores e `rds.force_ssl=1` | `modules/data` |
| **Achado:** nenhum login das aplicações existiria no RDS | `infra/postgres/bootstrap` | Famílias `-db-bootstrap` e `-txsigner-db-bootstrap`, rodadas só pelo fundador, com o segredo mestre do RDS; imagem em `infra/postgres/bootstrap/Dockerfile` | `one-off.tf`, Dockerfile |
| **Achado:** o CloudTrail não existia, só o bucket e o grupo de log; os filtros e alarmes do KMS nunca disparariam | `modules/observability` | `aws_cloudtrail` multirregião com validação de arquivo, política do bucket e papel de entrega | `modules/observability/trail.tf` |
| **Achado:** o nome do bucket da trilha (`${name}-cloudtrail`) colidiria entre as duas contas, porque o nome de bucket é global | `modules/observability` | Sufixo com o ID da conta | `modules/observability/main.tf` |
| **Achado:** faltava a cópia diária de 30 dias do U1:NFR4.8 | `reliability-requirements.md` da U1 | AWS Backup com plano diário e retenção de 30 dias | `modules/data/backup.tf` |
| Tópico dos alarmes e orçamento | FinOps | `aws_sns_topic.alarms` (ou um tópico informado) e `aws_budgets_budget` com alerta em 80% previsto e 100% real | raiz |
| Variáveis do TxSigner que o perfil de produção exige e que faltavam | `ProductionChecks` | `TXSIGNER_AWS_ACCOUNT_ID`, `TXSIGNER_CREDENTIAL_SECRET_ID`, `AWS_REGION`, `TXSIGNER_DB_NAME`, `TXSIGNER_DB_USER` e `TXSIGNER_REAL_ENABLED=false` explícito | raiz, `modules/services/main.tf` |

**Outros arquivos:**
- `infra/terraform/environments/{devnet,mainnet}.tfvars.example`: valores de exemplo, sem segredo.
- `infra/terraform/tests/*.tftest.hcl`: testes offline.
- `.gitignore`: `.terraform/`, estado, planos e `*.tfvars` de `infra/terraform`.
- `modules/kms/variables_outputs.tf`: saída `txsigner_role_name`.

**Desvios registrados:**
- `api-worker` com 1 vCPU e 2 GB, não 0,5 vCPU e 1 GB: as duas JVMs com `-Xmx512m` não cabem em 1 GB.
- Worker em `:8082`, não `:8081` como no `cd-config.md` 5.1: os dois contêineres dividem a rede da tarefa, e a API já usa a 8081. O worker recebe `SERVER_PORT` e `MANAGEMENT_SERVER_PORT=8082`.
- Chave da Jupiter **só no worker**, e só com `inject_jupiter_api_key=true`. O U7-NFR1.5 diz "só do worker", mas o `infrastructure-specification.md` da U7 e o `.env.example` dizem "API e worker". Hoje nenhum código lê a propriedade `jupiter-api-key`. Vale o requisito; o fundador decide se corrige a especificação.

## 3. Verificação da rede pela leitura das regras

### 3.1 Nada fora da VPC alcança o TxSigner

| Caminho | Por que não passa |
|---|---|
| Internet → TxSigner | A tarefa não tem IP público (`assign_public_ip = false`). A subrede A não tem rota para a internet (`aws_route_table.private_a` só tem a rota local e a do endpoint do S3). O grupo do TxSigner aceita só 8443 do grupo `api-worker` e 8444 do próprio grupo (o probe). Nenhuma regra usa CIDR. |
| Balanceador → TxSigner | O balanceador só sai para 8080 e 8081 do `api-worker` e para 3000 do `web-admin`. O TxSigner não está em nenhum target group. |
| Web e admin → TxSigner | O `web-admin` só sai para 443 dos endpoints, para o S3 e para 8080 do `api-worker`. |
| Outra conta ou outra VPC → TxSigner | Não há peering, Transit Gateway nem PrivateLink do TxSigner (Q5). O namespace do Service Connect é desta VPC. |
| TxSigner → internet | O grupo só sai para 443 dos endpoints, 443 do S3 pela lista de prefixos, 5432 do RDS dele e 8444 do próprio grupo. A subrede não tem rota. |

A prova na conta, depois do apply, é o Reachability Analyzer (seção 6, comando H4).

### 3.2 Regras de entrada

| Grupo | Porta | Origem | Recurso |
|---|---|---|---|
| `alb` | 443, 80 | `0.0.0.0/0` (80 só redireciona) | `module.network.aws_vpc_security_group_ingress_rule.alb_https[*]` |
| `api-worker` | 8080, 8081 | `alb` | `app_from_alb[*]` |
| `api-worker` | 8080 | `web-admin` | `app_from_web_admin` |
| `web-admin` | 3000 | `alb` | `web_admin_from_alb` |
| `txsigner` | 8443 | `api-worker` | `txsigner_from_app` |
| `txsigner` | 8444 | `txsigner` (probe) | `module.delivery...txsigner_management_from_probe` |
| `vpc-endpoints` | 443 | `txsigner`, `api-worker`, `web-admin` | `endpoints_from_*` |
| `db-app` | 5432 | `api-worker` | `module.data...db["app"]` |
| `db-txsigner` | 5432 | `txsigner` | `module.data...db["txsigner"]` |

### 3.3 Regras de saída (uma linha por regra)

| Grupo | Destino | Porta | Para quê | Recurso |
|---|---|---|---|---|
| `txsigner` | `vpc-endpoints` | 443 | KMS, Secrets Manager, STS, logs, ECR, métricas | `txsigner_to_endpoints` |
| `txsigner` | lista de prefixos do S3 | 443 | camadas das imagens do ECR | `to_s3["txsigner"]` |
| `txsigner` | `db-txsigner` | 5432 | banco do TxSigner | `module.data...to_db["txsigner"]` |
| `txsigner` | `txsigner` | 8444 | probe de prontidão do deploy | `module.delivery...probe_to_txsigner_management` |
| `api-worker` | `vpc-endpoints` | 443 | Secrets Manager, logs, ECR, métricas | `app_to_endpoints` |
| `api-worker` | lista de prefixos do S3 | 443 | camadas do ECR | `to_s3["api"]` |
| `api-worker` | `db-app` | 5432 | banco da aplicação (nunca o do TxSigner) | `module.data...to_db["app"]` |
| `api-worker` | `txsigner` | 8443 | API de assinatura (Service Connect) | `app_to_txsigner` |
| `api-worker` | `0.0.0.0/0` | 443 | RPC, Jupiter, CoinGecko, GitHub, só pelo NAT da subrede B | `app_to_internet` |
| `web-admin` | `vpc-endpoints` | 443 | ECR e logs | `web_admin_to_endpoints` |
| `web-admin` | lista de prefixos do S3 | 443 | camadas do ECR | `to_s3["web_admin"]` |
| `web-admin` | `api-worker` | 8080 | leitura do lado do servidor (`API_INTERNAL_ORIGIN`) | `web_admin_to_api` |
| `alb` | `api-worker` | 8080, 8081 | requisições e health check | `alb_to_api[*]` |
| `alb` | `web-admin` | 3000 | requisições e health check | `alb_to_web_admin` |
| RDS, endpoints, padrão da VPC | — | — | nenhuma saída | — |

A única saída para a internet de toda a VPC é a da tarefa api+worker em 443. O grupo de segurança não filtra por nome. As listas fixas de hosts estão no código: domínios da CoinGecko (U2-NFR1.2), Jupiter, RPC configurado e `api.github.com`. Como a API divide a tarefa e o grupo com o worker, a rede não impede a API de sair; o código impede, porque a API não tem os adaptadores nem as chaves. Essa limitação do piloto já foi aceita na U2.

## 4. Implicações de segurança das mudanças (regra da fase Operation)

A revisão de segurança é do `devsecops`, na passada seguinte. Aqui ficam as implicações e o risco que sobra, para essa revisão ler.

| Mudança | Implicação | Mitigação | Risco que sobra |
|---|---|---|---|
| NAT gateway e saída 443 para `0.0.0.0/0` do api+worker | primeira saída da VPC para a internet | só a subrede B; o TxSigner e o web/admin ficam sem rota; hosts fixos no código; IP fixo do NAT (`nat_public_ip`) para lista de permissão no provedor de RPC | exfiltração em 443 por uma API ou um worker invadidos; o TxSigner continua recusando transação fora da política (ADR-004) |
| Balanceador público com 443 e 80 | entrada pública do produto | TLS 1.2+ (`ELBSecurityPolicy-TLS13-1-2-2021-06`), `drop_invalid_header_fields`, host desconhecido recebe 404, logs de acesso | sem WAF no piloto (candidato do G1) |
| ~~8 papéis de execução e `iam:PassRole` deles pelo papel de implantação~~ **corrigido (SP-01, seção 10)** | o deploy podia registrar e rodar revisões com o papel do TxSigner e com os papéis de bootstrap (segredo mestre do RDS) | o deploy só passa os papéis de `api-worker`, `web`, `admin` e `db-migrate`; Deny de `PassRole` dos outros; Deny de `RunTask`, `StartTask` e `UpdateService` das famílias e do serviço do TxSigner e do bootstrap; SCP com MFA; alarmes do CloudTrail | o deploy ainda lê, por uma revisão própria, os segredos das quatro famílias que implanta (inclusive o dono do esquema da aplicação, pelo `db-migrate`); o IAM não limita `RegisterTaskDefinition` por família (seção 10, riscos que sobram) |
| Papel de execução único para `api` e `worker` | o papel lê os segredos dos dois contêineres | cada definição de contêiner só injeta os seus | separação por papel só quando o worker ganhar tarefa própria |
| Leitura do token de serviço pelo papel de tarefa do TxSigner | o papel do KMS ganha um `GetSecretValue` | só no ARN do token; sem `kms:Decrypt` (chave gerenciada) | nenhum novo acesso a chave |
| Famílias de bootstrap com o segredo mestre do RDS | primeira família com credencial de superusuário do RDS | só o fundador roda, com MFA, a revisão do Terraform (`founder_task_definition_arns`); o deploy não passa os papéis de execução delas nem as roda (Deny explícito); log em grupo próprio que o deploy não lê | o papel de implantação ainda registra uma revisão dessas famílias, sem o papel que lê o segredo mestre e sem poder rodá-la; o alarme `-security-founder-family-registered` avisa |
| SCP | bloqueia regiões; exclusão, desligamento, política e etiquetas de chave sem MFA; mudança, `PassRole` e `AssumeRole` do papel do TxSigner e dos papéis de execução do TxSigner e do bootstrap sem MFA; `PassRole` do papel do TxSigner fora do ECS; saída da organização; desligamento da trilha e exclusão do grupo de log dela sem MFA | — | todo `terraform apply` e todo passo do fundador precisam de sessão com MFA; um erro na SCP pode travar a conta-membro (testar primeiro na devnet, H2 e H19) |
| Grupo de parâmetros do RDS | `rds.force_ssl=1` obriga TLS do cliente | o pgjdbc usa `sslmode=prefer` por padrão, então negocia TLS | conexão sem verificação do certificado do servidor (só criptografia); `sslmode=verify-full` fica em aberto |
| Trilha do CloudTrail | passa a existir | bucket com object lock COMPLIANCE de 1 ano, agora com SSE-S3 (SP-08) | o bucket da devnet não pode ser apagado por 1 ano (o teardown deixa ele) |
| Endpoints de VPC com política (SP-05) | um endpoint só aceita principais e recursos da conta; o S3 só aceita o bucket de camadas do ECR e os buckets da conta | condição `IfExists` para chamadas sem recurso | uma imagem ou um agente da AWS buscado de uma conta da AWS pelo endpoint seria recusado; conferir na devnet (H6, H10) |
| Chave e política do tópico de alarmes | o tópico passa a usar uma chave própria que aceita o CloudWatch e o EventBridge | rotação anual; política do tópico só com a conta e as duas origens da conta | com `alarm_topic_arn` externo, o dono desse tópico precisa dar as mesmas permissões |

## 5. Auditoria de segredos

### 5.1 Segredos que o código lê

| Variável | Quem lê (contêiner) | Origem na nuvem | Segredo (Terraform) |
|---|---|---|---|
| `DB_PASSWORD` | `api`, `worker`, `db-bootstrap` | Secrets Manager (`secrets`) | `aws_secretsmanager_secret.this["app-db-app-password"]` |
| `DB_RETENTION_PASSWORD` | `api`, `db-bootstrap` | Secrets Manager | `this["app-db-retention-password"]` |
| `DB_OWNER_PASSWORD` | `db-migrate`, `db-bootstrap` | Secrets Manager | `this["app-db-owner-password"]` |
| `TXSIGNER_DB_PASSWORD` | `txsigner`, `txsigner-db-bootstrap` | Secrets Manager | `this["txsigner-db-app-password"]` |
| `TXSIGNER_DB_OWNER_PASSWORD` | `txsigner-migrate`, `txsigner-db-bootstrap` | Secrets Manager | `this["txsigner-db-owner-password"]` |
| `ACCESS_TOKEN_SIGNING_KEY` | `api` | Secrets Manager | `this["access-token-signing-key"]` |
| `MEMETRADE_AUDIT_WALLET_HMAC_KEY` | `api`, `worker` | Secrets Manager | `this["audit-wallet-hmac-key"]` |
| `MEMETRADE_TXSIGNER_TOKEN` | `api`, `worker` | Secrets Manager (valor `AWSCURRENT` na subida) | `this["txsigner-service-token"]` |
| token de serviço (`AWSCURRENT` e `AWSPREVIOUS`) | `txsigner` | lido pelo próprio TxSigner com o papel de tarefa a cada 5 min (`TXSIGNER_CREDENTIAL_SECRET_ID`) | `this["txsigner-service-token"]` |
| `MEMETRADE_RPC_PRIMARY_URL`, `MEMETRADE_RPC_SECONDARY_URL` | `worker` | Secrets Manager | `this["rpc-primary-url"]`, `this["rpc-secondary-url"]` |
| `COINGECKO_API_KEY` | `worker` | Secrets Manager | `coingecko_api_key` (U2) |
| `MEMETRADE_GITHUB_ACTIONS_TOKEN` | `worker` | Secrets Manager | `github_actions_read_token` (U5) |
| `MEMETRADE_JUPITER_API_KEY` | `worker`, só com `inject_jupiter_api_key=true` | Secrets Manager | `this["jupiter-api-key"]` |
| `POSTGRES_USER` e `POSTGRES_PASSWORD` (mestre) | `db-bootstrap`, `txsigner-db-bootstrap` | segredo gerenciado pelo RDS (chaves JSON `username` e `password`) | `module.data` (`master_secret_arns`) |
| `web`, `admin` | nenhum segredo | — | — |

### 5.2 Parâmetros sem segredo (variáveis versionadas no Terraform)

Flags e rede:
- `MEMETRADE_REAL_ENABLED=false`, `TXSIGNER_REAL_ENABLED=false` e `MEMETRADE_REAL_EXITS_ENABLED=false`;
- `MEMETRADE_REAL_NETWORK` e `TXSIGNER_NETWORK` iguais a `DEVNET` ou `MAINNET`;
- `MEMETRADE_REAL_FOUNDER_USER_ID`, só quando preenchida;
- `SPRING_PROFILES_ACTIVE` igual a `production`, ou `worker,production` no worker;
- `MARKET_SOURCES=coingecko`.

Banco, origens e endereços:
- `DB_HOST`, `DB_NAME` e os nomes de login;
- `GUEST_APP_ORIGIN`, `ADMIN_APP_ORIGIN`, `API_ORIGIN` e `API_INTERNAL_ORIGIN`;
- `ADMIN_WALLET_ADDRESSES`, que são endereços públicos;
- `MEMETRADE_TXSIGNER_URL`.

Campos jurídicos: `MEMETRADE_LEGAL_*`.

TxSigner:
- `TXSIGNER_KMS_HMAC_KEY_ID`, o ID da chave, não o material;
- `TXSIGNER_KMS_KEY_POLICY`, a política JSON;
- `TXSIGNER_ALLOWED_KMS_ENDPOINTS`, `TXSIGNER_AWS_ACCOUNT_ID` e `TXSIGNER_CREDENTIAL_SECRET_ID`, que é o ARN e não o valor;
- `TXSIGNER_CREDENTIAL_SOURCE=secrets-manager`.

Tamanhos de pool.

### 5.3 Confirmações

| Verificação | Como | Resultado |
|---|---|---|
| Nenhum segredo em variável simples | teste offline `services_secrets.tftest.hcl` (`secrets_per_container`): nenhum nome de `environment` das 6 famílias casa com `PASSWORD`, `SECRET_KEY`, `SIGNING_KEY`, `HMAC_KEY`, `API_KEY`, `_TOKEN`, `RPC_*_URL` ou `NEXT_PUBLIC_`; `grep` nos mapas de ambiente do Terraform só achou `TXSIGNER_KMS_HMAC_KEY_ID`, `TXSIGNER_KMS_KEY_POLICY` e `TXSIGNER_CREDENTIAL_SECRET_ID`, que são identificadores | confirmado |
| Cada contêiner só recebe os seus segredos | o mesmo teste compara a lista exata de `secrets` da `api` e do `worker`; outro cenário confirma que a chave da Jupiter vai só para o worker | confirmado |
| Nenhum segredo em `NEXT_PUBLIC_*` | `grep` em `apps/web/src`, `apps/admin/src` e nos `next.config.*`: nenhuma variável `NEXT_PUBLIC_` em código (só comentários que proíbem); os apps leem só `API_ORIGIN`, `API_INTERNAL_ORIGIN`, `GUEST_APP_ORIGIN` e `NODE_ENV` do lado do servidor; o web e o admin não têm `secrets` | confirmado |
| `.env.example` só com nomes | `grep -E '^[A-Z_]+=.+'`: nenhuma linha com valor; as linhas comentadas também não têm valor | confirmado |
| Nenhum valor de segredo no Terraform nem no estado | só `aws_secretsmanager_secret`, nunca `aws_secretsmanager_secret_version`; senhas mestres pelo `manage_master_user_password` | confirmado |
| NFR1.12 (chave HMAC separada da credencial do TxSigner) | `audit-wallet-hmac-key` e `txsigner-service-token` são segredos distintos; a âncora da carteira é uma chave KMS `HMAC_256` | confirmado na IaC; a inspeção na conta fecha a meta (seção 8) |

## 6. Saúde do ambiente depois do apply (comandos exatos)

Rodar na ordem, com o perfil de administração da conta (sessão com MFA). Nenhum comando lê o valor de um segredo.

```bash
export AWS_PROFILE=eureka-devnet-admin   # eureka-mainnet-admin na mainnet
export NAME=eureka-real REGION=<região> ENV=devnet
cd infra/terraform
VPC=$(aws ec2 describe-vpcs --filters Name=tag:Name,Values=$NAME --query 'Vpcs[0].VpcId' --output text)
TXSIGNER_SG=$(terraform output -json github_environment_variables | jq -r .TXSIGNER_SECURITY_GROUP)
APP_SG=$(terraform output -json github_environment_variables | jq -r .APP_SECURITY_GROUP)
TG=$(terraform output -json github_environment_variables | jq -r .API_TARGET_GROUP_ARN)
```

| # | Verifica | Comando | Esperado |
|---|---|---|---|
| H1 | conta certa | `aws sts get-caller-identity --query Account --output text` | o `account_id` do tfvars |
| H2 | SCP de região | `aws ec2 describe-vpcs --region us-west-2` (ou outra região fora da permitida) | `AccessDenied` com "explicit deny in a service control policy" |
| H3 | subrede A sem internet | `aws ec2 describe-route-tables --filters Name=tag:Name,Values=$NAME-private-a-no-internet --query 'RouteTables[0].Routes[].[DestinationCidrBlock,DestinationPrefixListId,GatewayId,NatGatewayId]' --output table` | só `local` e a lista de prefixos do S3; nenhum `0.0.0.0/0` |
| H4 | nada da internet chega ao TxSigner | `ENI=$(aws ecs describe-tasks --cluster $NAME --tasks $(aws ecs list-tasks --cluster $NAME --service-name txsigner --query 'taskArns[0]' --output text) --query "tasks[0].attachments[0].details[?name=='networkInterfaceId'].value" --output text)`; `IGW=$(aws ec2 describe-internet-gateways --filters Name=attachment.vpc-id,Values=$VPC --query 'InternetGateways[0].InternetGatewayId' --output text)`; `P=$(aws ec2 create-network-insights-path --source $IGW --destination $ENI --protocol tcp --destination-port 8443 --query NetworkInsightsPath.NetworkInsightsPathId --output text)`; `A=$(aws ec2 start-network-insights-analysis --network-insights-path-id $P --query NetworkInsightsAnalysis.NetworkInsightsAnalysisId --output text)`; depois de 1 a 2 min: `aws ec2 describe-network-insights-analyses --network-insights-analysis-ids $A --query 'NetworkInsightsAnalyses[0].[Status,NetworkPathFound]'` | `succeeded`, `false` (cerca de US$ 0,10 por análise; repetir com `--destination-port 8444`) |
| H5 | regras do TxSigner | `aws ec2 describe-security-group-rules --filters Name=group-id,Values=$TXSIGNER_SG --query 'SecurityGroupRules[].[IsEgress,FromPort,ToPort,CidrIpv4,PrefixListId,ReferencedGroupInfo.GroupId]' --output table` | exatamente as linhas `txsigner` das tabelas 3.2 e 3.3; nenhum `CidrIpv4` |
| H6 | endpoints | `aws ec2 describe-vpc-endpoints --filters Name=vpc-id,Values=$VPC --query 'VpcEndpoints[].[ServiceName,State]' --output table` | 8 linhas `available` |
| H7 | RDS | `for db in app txsigner; do aws rds describe-db-instances --db-instance-identifier $NAME-$db --query 'DBInstances[0].[DBInstanceStatus,StorageEncrypted,PubliclyAccessible,DeletionProtection,BackupRetentionPeriod,DBParameterGroups[0].ParameterApplyStatus,LatestRestorableTime]' --output text; done` | `available True False True 7 in-sync` e `LatestRestorableTime` a menos de 5 min do relógio |
| H8 | segredos preenchidos | `aws secretsmanager list-secrets --filters Key=name,Values=$NAME/ --query 'SecretList[].Name' --output text \| tr '\t' '\n' \| while read s; do printf '%s ' "$s"; aws secretsmanager describe-secret --secret-id "$s" --query VersionIdsToStages --output json \| jq 'length'; done` | todo segredo com pelo menos 1 versão, exceto `jupiter-api-key` enquanto `inject_jupiter_api_key=false` |
| H9 | bootstrap e migrações | `aws logs filter-log-events --log-group-name /ecs/$NAME/bootstrap --limit 50 --query 'events[].message'` e o código de saída de cada tarefa (`aws ecs describe-tasks ... --query 'tasks[0].containers[0].exitCode'`) | saída 0 nas 4 tarefas avulsas |
| H10 | serviços | `aws ecs describe-services --cluster $NAME --services txsigner api-worker web admin --query 'services[].[serviceName,runningCount,desiredCount,deployments[0].rolloutState]' --output table` | 1/1 `COMPLETED` em todos |
| H11 | alvos do balanceador | `aws elbv2 describe-target-health --target-group-arn $TG --query 'TargetHealthDescriptions[].TargetHealth.State'` | `["healthy"]` |
| H12 | `track_commit_timestamp` | `aws logs filter-log-events --log-group-name /ecs/$NAME/api-worker --filter-pattern '"track_commit_timestamp"' --query 'events[].message'` | nenhuma mensagem ERROR |
| H13 | leitura pública de ponta a ponta | `curl -fsS https://<api_domain>/api/v1/legal/current \| jq '.version, .publishable'` e `curl -fsS -o /dev/null -w '%{http_code}\n' https://<web_domain>/privacidade` | versão vigente, `true`, `200` |
| H14 | API e worker sem papel AWS | `aws ecs describe-task-definition --task-definition $NAME-api-worker --query 'taskDefinition.taskRoleArn'` | `null` |
| H15 | trilha | `aws cloudtrail get-trail-status --name $NAME --query '[IsLogging,LatestDeliveryError,LatestCloudWatchLogsDeliveryError]'` | `true`, sem erro |
| H16 | notificação dos alarmes | `aws cloudwatch set-alarm-state --alarm-name $NAME-kms-admin-action --state-value ALARM --state-reason "teste de notificação (environment-provisioning)"` | e-mail recebido; o alarme volta ao estado normal sozinho. Com a chave própria do tópico (seção 10), o CloudWatch consegue publicar; com a `aws/sns` de antes, não conseguiria |
| H17 | backup diário | depois de 24 h: `aws backup list-recovery-points-by-backup-vault --backup-vault-name $NAME-rds --query 'RecoveryPoints[].[ResourceArn,Status,CreationDate]'` | um ponto `COMPLETED` por banco |
| H18 | orçamento | `aws budgets describe-budgets --account-id $(aws sts get-caller-identity --query Account --output text) --query 'Budgets[].[BudgetName,BudgetLimit.Amount]'` | `eureka-real-<env>-monthly` |
| H19 | papel de implantação sem caminho para assinar (SP-01) | `DEPLOY=$(terraform output -raw deploy_role_arn)`; `aws iam simulate-principal-policy --policy-source-arn $DEPLOY --action-names iam:PassRole --resource-arns arn:aws:iam::<conta>:role/$NAME-txsigner arn:aws:iam::<conta>:role/$NAME-txsigner-db-bootstrap-exec --query 'EvaluationResults[].[EvalResourceName,EvalDecision]'` e o mesmo com `ecs:UpdateService` em `arn:aws:ecs:$REGION:<conta>:service/$NAME/txsigner` e `ecs:RunTask` em `arn:aws:ecs:$REGION:<conta>:task-definition/$NAME-txsigner-migrate:1` | `explicitDeny` em todos |
| H20 | detecção (SP-11) | `aws guardduty list-detectors`; `aws accessanalyzer list-analyzers --query 'analyzers[].[name,status]'`; `aws cloudwatch describe-alarms --alarm-name-prefix $NAME-security- --query 'MetricAlarms[].AlarmName'` | 1 detector; `eureka-real-account ACTIVE`; 4 alarmes |
| H21 | notificação pelo EventBridge | `aws guardduty create-sample-findings --detector-id $(aws guardduty list-detectors --query 'DetectorIds[0]' --output text) --finding-types Backdoor:EC2/C&CActivity.B!DNS` | e-mail do achado de exemplo (severidade alta) |
| H22 | política dos endpoints (SP-05) | `aws ec2 describe-vpc-endpoints --filters Name=vpc-id,Values=$VPC --query 'VpcEndpoints[].[ServiceName,PolicyDocument]' --output text \| grep -c PrincipalAccount` | 7; e H10 com 1/1 em todos os serviços, que prova o pull das imagens e a leitura dos segredos pelos endpoints com política |

O smoke e a janela de observação do `deploy.yml` (`cd-config.md` 4.4) repetem H10, H11 e H13 em toda implantação.

## 7. Roteiro de aplicação do fundador

Pré-condições:
- região escolhida, por exemplo `sa-east-1`;
- domínio registrado e controle do DNS;
- orçamento aceito: cerca de US$ 513 por mês nas três contas, com preços de referência da `us-east-1`; recalcular na região;
- dois provedores de RPC por ambiente;
- plano da CoinGecko escolhido;
- e-mail dos alarmes.

Toda sessão administrativa é feita com MFA.

### 7.1 Organização e contas (uma vez)

1. Criar a conta de gerenciamento, com MFA de hardware no usuário raiz, e usar o raiz só para os passos abaixo.
2. `aws organizations create-organization --feature-set ALL`
3. `aws organizations create-account --email <e-mail-devnet> --account-name eureka-devnet` e, igual, `eureka-mainnet`. Esperar `aws organizations describe-create-account-status ...` dar `SUCCEEDED`.
4. `aws organizations enable-policy-type --root-id $(aws organizations list-roots --query 'Roots[0].Id' --output text) --policy-type SERVICE_CONTROL_POLICY`
5. Perfis locais com MFA para assumir `OrganizationAccountAccessRole` em cada conta-membro (`role_arn`, `source_profile`, `mfa_serial` no `~/.aws/config`): `eureka-devnet-admin` e `eureka-mainnet-admin`.

### 7.2 Buckets de estado (um por conta)

```bash
for P in eureka-mgmt-admin eureka-devnet-admin eureka-mainnet-admin; do
  ACC=$(aws --profile $P sts get-caller-identity --query Account --output text)
  B=eureka-tfstate-$ACC
  aws --profile $P s3api create-bucket --bucket $B --region $REGION --create-bucket-configuration LocationConstraint=$REGION
  aws --profile $P s3api put-bucket-versioning --bucket $B --versioning-configuration Status=Enabled
  aws --profile $P s3api put-bucket-encryption --bucket $B --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
  aws --profile $P s3api put-public-access-block --bucket $B --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
done
```

Na `us-east-1`, omitir `--create-bucket-configuration`. Esses três buckets são a única peça criada fora do Terraform, porque guardam o próprio estado.

### 7.3 SCP (raiz `infra/terraform/org/`)

```bash
cd infra/terraform/org
AWS_PROFILE=eureka-mgmt-admin terraform init -backend-config="bucket=eureka-tfstate-<conta mgmt>" -backend-config="key=org.tfstate" -backend-config="region=$REGION"
AWS_PROFILE=eureka-mgmt-admin terraform plan -var region=$REGION -var management_account_id=<mgmt> -var devnet_account_id=<devnet> -var mainnet_account_id=<mainnet> -out=org.plan
# revisar: 1 política e 2 anexos; nada mais
AWS_PROFILE=eureka-mgmt-admin terraform apply org.plan
```

Conferir com H2 em cada conta-membro.

### 7.4 Cada ambiente (devnet primeiro; a mainnet só depois da etapa 2 da matriz do `cd-config.md` 6)

1. **Certificado:** `aws acm request-certificate --domain-name api.<env-domínio> --subject-alternative-names app.<env-domínio> admin.<env-domínio> --validation-method DNS`. Criar os CNAMEs de validação no DNS. Esperar `ISSUED` com `aws acm describe-certificate --certificate-arn <arn> --query Certificate.Status`.
2. **Variáveis:** `cp environments/devnet.tfvars.example environments/devnet.tfvars` e preencher. O arquivo fica fora do Git pelo `.gitignore` e não leva segredo. Começar com `service_desired_count = 0`.
3. **Início:** `terraform init -backend-config="bucket=eureka-tfstate-<conta>" -backend-config="key=eureka-real/devnet.tfstate" -backend-config="region=$REGION"`
4. **Repositórios primeiro:** `terraform apply -var-file=environments/devnet.tfvars -target=module.delivery.aws_ecr_repository.app -target=module.services.aws_ecr_repository.db_bootstrap`
5. **Primeiras imagens** (SHA verde em `ci` e `security`):
   ```bash
   SHA=<sha>; REG=<conta>.dkr.ecr.$REGION.amazonaws.com
   aws ecr get-login-password | docker login --username AWS --password-stdin $REG
   for app in api txsigner web admin; do docker build --build-arg BUILD_SHA=$SHA -t $REG/$NAME/$app:$SHA apps/$app && docker push $REG/$NAME/$app:$SHA; done
   docker build -t $REG/$NAME/db-bootstrap:$SHA infra/postgres/bootstrap && docker push $REG/$NAME/db-bootstrap:$SHA
   ```
6. **Plano e revisão:** `terraform plan -var-file=environments/devnet.tfvars -out=devnet.plan`, com `terraform show devnet.plan | less`. Conferir:
   - nenhum IP público em tarefa;
   - nenhuma entrada com CIDR fora do grupo `alb`;
   - nenhum `aws_secretsmanager_secret_version`;
   - `TXSIGNER_NETWORK` e `MEMETRADE_REAL_NETWORK` iguais ao ambiente;
   - as três flags do REAL em `false`;
   - `desired_count = 0`.
7. **Aplicar:** `terraform apply devnet.plan`. Confirmar a inscrição do e-mail do SNS.
8. **Segredos** (valores digitados ou gerados na hora, nunca no histórico do shell):
   ```bash
   gen() { aws secretsmanager put-secret-value --secret-id "$NAME/$1" --secret-string "$(openssl rand -hex 32)" > /dev/null; }
   for s in app-db-owner-password app-db-app-password app-db-retention-password txsigner-db-owner-password \
            txsigner-db-app-password audit-wallet-hmac-key txsigner-service-token; do gen $s; done
   aws secretsmanager put-secret-value --secret-id "$NAME/access-token-signing-key" --secret-string "$(openssl rand -base64 48)" > /dev/null
   for s in coingecko-api-key github-actions-read-token rpc-primary-url rpc-secondary-url; do
     read -rs -p "$s: " V; echo; aws secretsmanager put-secret-value --secret-id "$NAME/$s" --secret-string "$V" > /dev/null; unset V
   done
   ```
   O `jupiter-api-key` fica vazio até a gravação real da Trigger (U7 BR0). Conferir com H8.
9. **DNS:** os três hosts apontam (CNAME ou alias) para `terraform output -raw load_balancer_dns_name`.
10. **Bootstrap dos bancos** (só o fundador, com MFA). Rode a revisão registrada pelo Terraform, nunca a "mais recente" da família: `TD=$(terraform output -json founder_task_definition_arns)` e use `$(jq -r '."db-bootstrap"' <<< "$TD")`, `."txsigner-db-bootstrap"` e `."txsigner-migrate"` no lugar dos nomes das famílias abaixo (correção do SP-01).
    ```bash
    run() { aws ecs run-task --cluster $NAME --task-definition $1 --launch-type FARGATE --started-by founder-bootstrap \
      --network-configuration "awsvpcConfiguration={subnets=[$2],securityGroups=[$3],assignPublicIp=DISABLED}" --query 'tasks[0].taskArn' --output text; }
    T1=$(run $NAME-db-bootstrap <SUBNET_PRIVATE_B> $APP_SG)
    T2=$(run $NAME-txsigner-db-bootstrap <SUBNET_PRIVATE_A> $TXSIGNER_SG)
    aws ecs wait tasks-stopped --cluster $NAME --tasks $T1 $T2
    aws ecs describe-tasks --cluster $NAME --tasks $T1 $T2 --query 'tasks[].containers[0].exitCode'
    ```
    O esperado é `[0, 0]`. Depois, as migrações: `run $NAME-txsigner-migrate <SUBNET_PRIVATE_A> $TXSIGNER_SG` e `run $NAME-db-migrate <SUBNET_PRIVATE_B> $APP_SG`, cada uma com saída 0.
11. **Serviços no ar:** `service_desired_count = 1` no tfvars, `terraform plan -out` revisado (só `desired_count` muda) e `apply`. Conferir H10 a H14.
12. **GitHub:** criar o `deploy.yml` e os Environments (`cd-config.md` 4.1). Cadastrar as variáveis de `terraform output -json github_environment_variables`.
13. **Primeira implantação:** o passo do fundador com MFA (`cd-config.md` 4.6) e depois `gh workflow run deploy.yml -f sha=$SHA -f environment=devnet -f mode=deploy`. O job verde, com smoke e janela de observação, encerra o ambiente. Rodar H15 a H22.
14. **Mainnet:** os mesmos passos com `environments/mainnet.tfvars`, `founder_user_id` preenchido e o SHA já verde na devnet. A promoção segue a matriz do `cd-config.md` 6, e a aprovação do Environment `mainnet` é do fundador.

**Rollback do provisionamento.**
- **Antes de haver fundos:** `terraform destroy -var-file=...` na devnet. Antes, é preciso desligar `deletion_protection` dos RDS num apply. Ficam o bucket da trilha, por causa do object lock de 1 ano, e as chaves KMS em exclusão agendada de 30 dias.
- **Na mainnet, com carteiras:** nunca destruir. O rollback de uma implantação é o `rollback-runbook.md`. O de uma mudança de infraestrutura é reverter o commit do Terraform e fazer `plan` e `apply`, com o kill switch REAL acionado antes, se a mudança tocar o TxSigner ou a rede.

## 8. As 7 metas de ambiente do Build and Test

Todas continuam **`Unverified`**: nenhuma conta existe. A IaC deste estágio entrega o que cada uma precisa, e o que fecha cada meta está abaixo.

| Meta | O que a IaC já entrega | O que fecha a meta |
|---|---|---|
| U1:NFR1.1 (chave Ed25519 não exportável no KMS real) | papel do TxSigner, chave HMAC, política das chaves e endpoint do KMS | `AwsKmsEd25519ProofTest` (`docs/proofs/kms-ed25519.md`) verde na devnet **com a credencial do papel do TxSigner**. Esse papel só confia em tarefas ECS da conta, e as chaves só assinam pelo endpoint do KMS da VPC (SP-02), então a prova roda como tarefa avulsa com o papel do TxSigner e uma imagem de teste, registrada e rodada **pelo fundador com MFA**, só na devnet, com a revisão cancelada depois (opção (b), a preferida da revisão de segurança). A opção (a) (confiança temporária do papel de administração) não funcionaria mais de fora da VPC por causa do `aws:SourceVpce`. Depois, agendar a exclusão das chaves criadas. |
| U1:NFR1.2 (menor privilégio no KMS) | papel do TxSigner com negações; API e worker sem papel de tarefa (H14); trilha e alarme do KMS (H15, H16); SCP de exclusão com MFA | `aws iam simulate-principal-policy --policy-source-arn <papel txsigner> --action-names kms:ScheduleKeyDeletion kms:DisableKey kms:PutKeyPolicy kms:CreateGrant kms:Decrypt --query 'EvaluationResults[].[EvalActionName,EvalDecision]'` com `explicitDeny` em todas; o mesmo simulador sobre cada papel de execução com `kms:Sign` dando `implicitDeny`; H14 `null`; revisão da política pelo fundador antes da mainnet |
| U1:NFR1.12 (chave HMAC da auditoria no gerenciador de segredos, separada da credencial do TxSigner) | segredos `audit-wallet-hmac-key` e `txsigner-service-token` distintos | `aws ecs describe-task-definition --task-definition $NAME-api-worker --output json \| jq -r '.taskDefinition.containerDefinitions[] \| (.secrets[]? \| select(.name == "MEMETRADE_AUDIT_WALLET_HMAC_KEY") \| .valueFrom), (.environment[]? \| select(.name == "MEMETRADE_AUDIT_WALLET_HMAC_KEY") \| "PLAIN " + .name)'` aponta para `audit-wallet-hmac-key`, e a mesma variável não aparece em `environment` |
| U1:NFR3.10 (uma instância do TxSigner; cota do KMS acima de 1 assinatura por segundo) | `desired_count = 1` no TxSigner | H10 com 1/1 e `aws service-quotas list-service-quotas --service-code kms --query "Quotas[?contains(QuotaName, 'ECC')].[QuotaName,Value]" --output table` com a cota de operações ECC acima de 1 por segundo |
| U1:NFR4.8 (RPO 5 min, RTO 4 h, cópia diária de 30 dias) | PITR de 7 dias, AWS Backup diário de 30 dias, proteção contra exclusão | H7 (`LatestRestorableTime` a menos de 5 min), H17 e um ensaio de restauração cronometrado na devnet: `aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier $NAME-app --target-db-instance-identifier $NAME-app-restore-test --use-latest-restorable-time --db-subnet-group-name $NAME-app --db-parameter-group-name $NAME-postgres17 --no-publicly-accessible`, depois `aws rds wait db-instance-available --db-instance-identifier $NAME-app-restore-test` (tempo menor que 4 h) e `aws rds delete-db-instance --db-instance-identifier $NAME-app-restore-test --skip-final-snapshot` |
| U1:NFR9.1 (custo do KMS dentro do teto) | estimativa em `environment-inventory.md` (KMS ≈ US$ 23 na mainnet) e orçamento da conta | fatura de um mês cheio: `aws ce get-cost-and-usage --time-period Start=<AAAA-MM-01>,End=<AAAA-MM-01 seguinte> --granularity MONTHLY --metrics UnblendedCost --filter '{"Dimensions":{"Key":"SERVICE","Values":["AWS Key Management Service"]}}'` |
| U2:NFR9.1 (plano da CoinGecko dentro do teto, somado ao KMS e ao RPC) | segredo `coingecko-api-key` e alarme de orçamento mensal da U2 | o fundador confirma o preço do plano e registra a decisão: KMS + RPC + CoinGecko ≤ US$ 100 por mês, ou a revisão do teto |

## 9. Itens em aberto para o responsável

1. **Orçamento:** cerca de US$ 247 (devnet) e US$ 266 (mainnet) por mês na `us-east-1`, contra os US$ 154 por conta do desenho da U1 (diferença explicada no `environment-inventory.md`, seção 4). Escolher a região e recalcular. Decidir as economias opcionais.
2. **Endereço real do cliente atrás do balanceador:** virou o SP-07 e agora **bloqueia antes da mainnet** (seção 11), porque o limite de login compartilhado vira uma negação do saque.
3. **mTLS entre a API e o TxSigner na nuvem (NFR1.5):** virou o SP-06, **bloqueia antes da mainnet** (seção 11): implementar ou registrar a dispensa em ADR.
4. **SQL do bootstrap no RDS:** o `10-roles.sql` e o `20-txsigner.sql` usam `ALTER ROLE ... NOSUPERUSER ... NOREPLICATION NOBYPASSRLS` e `CREATE SCHEMA ... AUTHORIZATION`. O usuário mestre do RDS não é superusuário, e no PostgreSQL 16 ou mais novo esses atributos podem ser recusados. Confirmar no primeiro bootstrap da devnet. Se falhar, é preciso uma variante dos scripts para o RDS (mudança de código, com o teste de integração).
5. **Service Connect na subrede sem internet:** confirmar no primeiro apply da devnet que o proxy do Service Connect do TxSigner recebe a configuração só com os endpoints atuais (H10 e o smoke). Se não receber, acrescentar os endpoints `ecs`, `ecs-agent` e `ecs-telemetry` (cerca de US$ 22 por mês).
6. ~~**Entrega do CloudTrail com SSE-KMS (`aws/s3`)**~~ **resolvido (SP-08):** o bucket da trilha usa `AES256`, com a aprovação do revisor de segurança. H15 confirma a entrega na conta.
7. **Raiz somente leitura no web e no admin:** suprimida (`CKV_AWS_336`) porque o Next.js escreve em `.next/cache`. Testar na devnet com um volume para o cache.
8. **WAF no balanceador** (cerca de US$ 10 por mês): candidato do G1, antes dos convidados.
9. **Prova do KMS real (U1:NFR1.1):** opção (b) da seção 8, pelo fundador com MFA (a opção (a) deixou de funcionar com o `aws:SourceVpce` do SP-02).
10. **Chave da Jupiter:** corrigir a especificação da U7 ("API e worker") ou o requisito U7-NFR1.5 ("só do worker"). A IaC segue o requisito.
11. **Metrificação do TxSigner e dos apps no CloudWatch:** virou o SP-12, **bloqueia antes da mainnet** (seção 11).
12. **TLS do RDS:** o pgjdbc negocia TLS (`sslmode=prefer`) sem conferir o certificado. `sslmode=verify-full` com o pacote de CAs do RDS fica para decisão.
13. **`ci.yml` sem `terraform fmt -check` e `validate`:** virou o SP-20 (seção 11). Agora há também os testes offline das duas raízes (`infra/terraform/tests/`, `infra/terraform/org/tests/`) para incluir. Quem muda o `ci.yml` é o responsável.

## 10. Correções da revisão de segurança

Pedido do responsável (Request Changes, 2026-11-20): corrigir na IaC o SP-01, o SP-02, o SP-03, o SP-05, o SP-08 e o SP-11; nada de código de aplicação; as demais ressalvas viram a tabela da seção 11. A revisão (`security-posture-review.md`) não foi editada. Nenhuma conta foi tocada.

### 10.1 O que mudou, achado por achado

| ID | Correção | Onde (file:line) | Teste offline |
|---|---|---|---|
| SP-01 (Crítico) | **PassRole:** o papel de implantação só passa os papéis de execução de `api-worker`, `web`, `admin` e `db-migrate`. Saíram o papel de tarefa do TxSigner e os papéis de execução `txsigner`, `txsigner-migrate`, `db-bootstrap` e `txsigner-db-bootstrap` (e o novo `txsigner-probe`), agora exclusivos do fundador, com Deny explícito de `PassRole` e uma pré-condição que faz o plano falhar se um deles for posto na lista. | `modules/services/iam.tf:87-88` (separação), `main.tf:452-459` (raiz), `modules/delivery/main.tf:181` (`PassOnlyTheDeployRolesToEcs`), `:248` (`NeverPassTheFounderOnlyRoles`), `:328` (pré-condição) | `security_delivery.tftest.hcl` (2 cenários), `offline_plan.tftest.hcl` (`devnet_plan`) |
| SP-01 | **Famílias e serviço do TxSigner e do bootstrap:** `UpdateService` só em `api-worker`, `web` e `admin`; `RunTask` só no `db-migrate`; Deny de `RunTask`/`StartTask` das famílias `${name}-txsigner*` e `${name}-db-bootstrap` (e das famílias contínuas); Deny de qualquer mudança no serviço `txsigner`; Deny de `CreateService`/`RunTask`/`StartTask`/`UpdateService` com `ecs:task-definition` de uma família do fundador (impede pôr a revisão do TxSigner no serviço `api-worker`). O `RegisterTaskDefinition` continua em `*`: o IAM não tem condição de família nessa ação (risco que sobra, 10.3). | `modules/delivery/main.tf:155`, `:161`, `:222`, `:228`, `:235` | `security_delivery.tftest.hcl` |
| SP-01 | **Sobrescritas (*overrides*):** o IAM não tem condição para proibir *overrides* de contêiner no `RunTask`. Por isso o `RunTask` do deploy ficou só no `db-migrate`, que o deploy já controla por inteiro, e o `deploy.yml` não usa *override*. O probe, que precisava de *override*, saiu do deploy. | `modules/delivery/main.tf:161`; `cd-config.md` 4.4 (`run_one_off` sem *override*) | — |
| SP-01 | **Imagens e logs:** o deploy não grava no repositório `txsigner` (Deny), só lê o digest; lê só o grupo de log `/ecs/${name}/migrate`. | `modules/delivery/main.tf:119`, `:134`, `:209`, `:242` | `security_delivery.tftest.hcl` |
| SP-01 | **Probe declarado no Terraform**, sem papel de tarefa e com papel de execução sem segredo; o IP vem por `TXSIGNER_PROBE_IP`. Saída `founder_task_definition_arns`: o fundador parte da revisão do Terraform, nunca da "mais recente", e confere `registeredBy`. | `modules/services/one-off.tf:66`, `modules/services/iam.tf:30`, `modules/services/outputs.tf:33`, `main.tf:469` | `offline_plan.tftest.hcl` (9 papéis) |
| SP-01 | **Procedimento do fundador com MFA** para o TxSigner (imagem, `txsigner-migrate`, revisão, prontidão) e o rollback dele. | `deployment-pipeline/cd-config.md` 4.6; `rollback-runbook.md` passo 4, A2 | actionlint e shellcheck (Anexo A) |
| SP-02 (Alto) | **SCP:** sem MFA, nega `UpdateAssumeRolePolicy`, `Put/Delete/Attach/DetachRolePolicy`, limites de permissão, `DeleteRole`, `UpdateRole*`, `Tag/UntagRole` e `PassRole` do papel do TxSigner e dos papéis `${name}-txsigner-*` e `${name}-db-bootstrap-exec`; nega sempre `PassRole` do papel do TxSigner fora de `ecs-tasks`; nega `sts:AssumeRole*` desse papel sem MFA a todo principal que não seja serviço da AWS (a SCP nunca se aplica ao ECS). A política sai minificada (2911 de 5120 caracteres). | `org/policies/scp-guardrails.json.tftpl:49`, `:75`, `:88`, `:97`; `org/main.tf` | `org/tests/scp.tftest.hcl` |
| SP-02 | **Assinatura só de dentro da VPC:** na política das chaves de carteira, Deny de `kms:Sign` para todo principal quando `aws:SourceVpce` não é o endpoint do KMS; na chave HMAC, o mesmo para `GenerateMac` e `VerifyMac`. As declarações entram em HCL depois do modelo versionado, porque o ID do endpoint só existe no plano. | `modules/kms/main.tf:35`, `:46`; `modules/network/main.tf:570` (saída `kms_endpoint_id`); `main.tf:335` | `security_kms_network.tftest.hcl` |
| SP-02 | **Confiança do papel do TxSigner** com `aws:SourceAccount` e `aws:SourceArn` = `arn:aws:ecs:<região>:<conta>:*`. | `modules/kms/main.tf:66-67` | `security_kms_network.tftest.hcl` |
| SP-03 (Médio) | **Etiquetas só com MFA:** `TagResource` e `UntagResource` saíram da declaração da conta nas duas políticas de chave e foram para `TagChangesOnlyWithMfa` (`aws:MultiFactorAuthPresent = true`). A SCP também nega mudança de etiqueta de chave sem MFA, exceto o papel do TxSigner, que etiqueta no `CreateKey` e cuja política própria já nega mudanças depois. **Limitação documentada:** o MAC da âncora cobre só o endereço da carteira principal, não a chave; ligar o MAC a `network|botWalletAddress|mainWalletAddress` é código (seção 11). | `modules/kms/policies/wallet-key-policy.json.tftpl:21`, `hmac-key-policy.json.tftpl:21`, `org/policies/scp-guardrails.json.tftpl:39` | `security_kms_network.tftest.hcl`, `org/tests/scp.tftest.hcl`, `KmsPolicyDocumentsTest` |
| SP-05 (Médio) | **Política nos 7 endpoints de interface:** só principais da conta (`aws:PrincipalAccount`) e só recursos da conta (`aws:ResourceAccount`, com `IfExists` para as chamadas sem recurso), e só as ações do próprio serviço. **Gateway do S3:** só `s3:GetObject` no `prod-<região>-starport-layer-bucket` (camadas do ECR) e os buckets da conta por principais da conta. | `modules/network/main.tf:469`, `:494` | `security_kms_network.tftest.hcl` |
| SP-08 (Médio) | Bucket da trilha com `AES256` (SSE-S3), aprovado pelo revisor de segurança; supressão `CKV_AWS_145` justificada na linha. Complemento da revisão: a SCP nega sem MFA apagar o grupo de log da trilha, os filtros e a retenção. | `modules/observability/main.tf:17`, `:30`; `org/policies/scp-guardrails.json.tftpl:129` | `security_observability.tftest.hcl` |
| SP-11 (Médio) | **GuardDuty** (detector, eventos S3 e login do RDS; Runtime Monitoring desligado e pendente, 10.3), **IAM Access Analyzer** da conta, regras do EventBridge (achados do GuardDuty com severidade 7 ou mais e achados ativos do Access Analyzer) para o tópico, e **4 filtros de métrica com alarme** na trilha: mudança IAM nos papéis do fundador; `RegisterTaskDefinition` das famílias do TxSigner ou do bootstrap, ou com o papel do TxSigner; `RunTask`/`StartTask` dessas famílias, ou com *override* do papel do TxSigner; `Create/Update/DeleteService` do `txsigner`. Mudança de política ou de etiqueta de chave KMS: já coberta pelo alarme `kms-admin-action`. | `modules/observability/detection.tf:74`, `:95`, `:100`, `:115`, `:130`, `:142` | `security_observability.tftest.hcl` |
| SP-11 (achado da correção) | **O tópico dos alarmes não recebia nada:** com a chave gerenciada `aws/sns`, o CloudWatch e o EventBridge não conseguem publicar num tópico criptografado. O tópico passou a usar uma chave própria (`aws_kms_key.alarms`, rotação anual) que aceita os dois, e ganhou uma política que só aceita a conta e as duas origens da conta. Sem isso, nenhum alarme do piloto chegaria ao e-mail. | `main.tf:210`, `:244` | Checkov (`CKV_AWS_7`, `CKV2_AWS_64` aprovados) |

### 10.2 O `deploy.yml` mudou

O papel restrito não consegue mais rodar o `txsigner-migrate`, trocar o serviço `txsigner` nem rodar o probe. Por isso o texto do `deploy.yml` em `deployment-pipeline/cd-config.md` 4.4 mudou: o job não publica a imagem do TxSigner (só lê o digest), não roda o `txsigner-migrate` e não troca o TxSigner. Ele confere, só por leitura, que o serviço `txsigner` está `COMPLETED`, com todas as tarefas `HEALTHY` e com a imagem do SHA, e para antes de qualquer troca se o passo do fundador não foi feito. O passo manual do fundador com MFA está no `cd-config.md` 4.6, e o rollback do TxSigner no `rollback-runbook.md` (passo 4, A2). O `deployment-strategy.md` ganhou uma nota: a ordem é a mesma, muda quem executa. Nenhum arquivo em `.github/workflows/` foi criado nem editado; o texto passou no `actionlint` 1.7.7 (com `shellcheck`).

### 10.3 Riscos que sobram depois da correção

| Risco | Por que sobra | Mitigação | Quem aceita |
|---|---|---|---|
| O papel de implantação registra uma revisão de qualquer família, inclusive `${name}-txsigner` | o IAM não aceita condição de família no `RegisterTaskDefinition` | a revisão só leva papéis que o deploy pode passar (sem KMS e sem segredo do TxSigner); o deploy não consegue pô-la em serviço nem rodá-la; o passo do fundador nunca parte da "mais recente" e confere `registeredBy`; o alarme `-security-founder-family-registered` avisa | fundador (registrado aqui) |
| O deploy lê os segredos das 4 famílias que implanta, inclusive o dono do esquema da aplicação (`db-migrate`) e o token do TxSigner (`api-worker`) | é o que o deploy precisa para implantar; o IAM não proíbe *overrides* no `RunTask` | imagem por digest, aprovação do fundador na mainnet, CloudTrail. O token do TxSigner só chama a API de assinatura, e o TxSigner recusa tudo fora da política (ADR-004) | já aceito no `cd-config.md` 3.3; sem caminho até a chave nem até o banco do TxSigner |
| `ecs:task-definition` no `UpdateService`, `CreateService`, `RunTask` e `StartTask` | se a AWS não preencher essa chave numa dessas ações, o Deny `NeverUseAFounderOnlyRevision` não pega; o `PassRole` dos papéis do fundador continua negado | H19 na devnet com o simulador e um teste real (ensaio, `rollback-runbook.md` seção 8, linha 13) | conferir na devnet |
| Cada implantação exige o passo manual do fundador | consequência direta do SP-01 | procedimento escrito e ensaiado; o workflow para cedo se faltar | fundador |
| GuardDuty Runtime Monitoring do Fargate desligado | o agente é um contêiner auxiliar puxado de um repositório da AWS, que os papéis de execução (só os próprios repositórios) e a política dos endpoints (só a conta) recusam, e a subrede do TxSigner não tem internet | os achados de CloudTrail, VPC e DNS continuam; pendência na seção 11 | fundador |
| `RDS_LOGIN_EVENTS` pode não cobrir o RDS PostgreSQL desta versão | a cobertura do GuardDuty RDS Protection depende do motor | ligado sem custo se não cobrir; conferir no console na devnet | conferir na devnet |
| Os endpoints com política podem recusar algo da AWS que não foi previsto | o `IfExists` cobre as chamadas sem recurso; uma chamada a um recurso de uma conta da AWS seria recusada | H6, H10 e H22 na devnet antes de qualquer outra coisa; se algo falhar, abrir só o recurso exato, com justificativa | conferir na devnet |
| Um tópico de alarmes externo (`alarm_topic_arn`) | o Terraform não gerencia a chave nem a política dele | usar o tópico criado aqui, ou dar ao externo as mesmas permissões | fundador |

## 11. Pendências obrigatórias

Nenhuma destas foi feita neste estágio: são código de aplicação, mudança no `ci.yml` ou itens da IaC que a revisão marcou para depois. "Bloqueia antes de" segue a revisão de segurança. A mainnet só abre com todas as linhas "mainnet" fechadas, e os convidados com todas as linhas "convidados".

| ID | O quê | Estágio dono | Bloqueia antes de |
|---|---|---|---|
| SP-04 | `ProductionChecks` recusa subir sem `TXSIGNER_KMS_KEY_POLICY`, ou com uma política sem `OnlyTheTxSignerSigns`, `NobodyElseSigns` e `SignOnlyThroughTheKmsVpcEndpoint`; teste de inicialização desse caso | Code Generation (U1, volta da Construction) | mainnet |
| SP-06 | mTLS entre `api-worker` e TxSigner (Service Connect com AWS Private CA, ou TLS na aplicação com certificado por chamador), ou ADR do fundador dispensando o NFR1.5 com o risco aceito | Code Generation (U1) e Infrastructure Design; ou decisão do fundador em ADR | mainnet |
| SP-07 | `ClientIpResolver` com faixas CIDR e `TRUSTED_PROXIES` com as duas subredes públicas (`10.40.0.0/24`, `10.40.4.0/24`) no Terraform | Code Generation (identidade); depois este estágio liga a variável | mainnet |
| SP-12 | Métricas do TxSigner e dos apps no CloudWatch (EMF ou coletor ADOT) e um alarme de ausência de dados por namespace. **Resolvido na IaC pelo Observability Setup (20/11/2026), pendente de verificação na devnet.** O contêiner auxiliar do CloudWatch Agent nas tarefas `api-worker` e `txsigner` publica por EMF, em `Eureka/App`, só as métricas da lista de permissão. Há um alarme `metrics-absent-*` por processo, com `breaching`. O teste offline `observability_metrics.tftest.hcl` impede alarme sem fonte. Detalhes, lacunas OBS-G1 a OBS-G4 e comandos de verificação em `operation/observability-setup/alarms.md` | Observability Setup (verificação no primeiro apply da devnet); Code Generation só para as lacunas OBS-G1 e OBS-G2, que não bloqueiam | mainnet |
| SP-03 (código) | MAC da âncora ligado à chave: `network\|botWalletAddress\|mainWalletAddress` | Code Generation (U1) | mainnet |
| SP-09 | Host `admin`: condição `source_ip` (IPs do fundador) ou `authenticate-oidc`; WAF com regras gerenciadas e limite de taxa | Environment Provisioning (próxima volta) | convidados |
| SP-10 | Route 53 Resolver DNS Firewall com lista de permissão (RPC, Jupiter, CoinGecko, `api.github.com`) | Environment Provisioning (próxima volta) | convidados |
| SP-11 (resto) | GuardDuty Runtime Monitoring do Fargate (permissão de pull do agente nos papéis de execução e exceção no endpoint do ECR, testadas na devnet); alarmes de uso do usuário raiz e de login no console sem MFA | Environment Provisioning (próxima volta) | mainnet |
| SP-13 | `CreateKey` com `kms:KeyOrigin = AWS_KMS` e `kms:MultiRegion = false` na política do papel do TxSigner | Environment Provisioning (próxima volta) | mainnet |
| SP-14 | SCP: negar `kms:ScheduleKeyDeletion` com janela menor que 30 dias, ou sempre para chaves `app = eureka-botwallet` | Environment Provisioning (raiz `org`) | mainnet |
| SP-15 | Vault Lock no cofre do AWS Backup (governance na devnet, compliance na mainnet) ou política que negue `DeleteRecoveryPoint` | Environment Provisioning (próxima volta) | mainnet |
| SP-16 | Política de recurso nos segredos (só o papel dono e a administração com MFA) e alarme de idade do token do TxSigner aos 80 dias | Environment Provisioning (próxima volta) | convidados |
| SP-17 | `sslmode=verify-full` com o pacote de CAs do RDS nas imagens | Code Generation e Environment Provisioning | convidados |
| SP-18 | Raiz somente leitura no web e no admin, com volume para `.next/cache`, testada na devnet | Environment Provisioning (próxima volta) | convidados |
| SP-19 | `aws:SourceAccount` na confiança dos papéis do flow log e do AWS Backup | Environment Provisioning (próxima volta) | — (não bloqueia) |
| SP-20 | `terraform fmt -check`, `validate`, `test` das duas raízes e Checkov na CI, mais uma verificação própria (papel do TxSigner fora de `passable_role_arns`, `NobodyElseSigns` e `aws:SourceVpce` nas chaves, negações IAM na SCP). Só o responsável muda o `ci.yml` | responsável do projeto (`ci.yml`) | mainnet |
| SP-21 | Buckets de estado com política só-TLS e acesso restrito ao papel de administração | responsável, no roteiro 7.2 | — (não bloqueia) |
| VR-1 | Conferir na devnet os riscos da seção 10.3 marcados "conferir na devnet" (H19 a H22) | Environment Provisioning (primeiro apply da devnet) | mainnet |

## 12. Mudança de custo da correção

Preços de referência da `us-east-1`, por conta. O GuardDuty tem 30 dias de teste gratuito; depois, o custo real aparece no próprio console de uso do GuardDuty e deve substituir esta estimativa.

| Item | Devnet (US$/mês) | Mainnet (US$/mês) | Base da estimativa |
|---|---|---|---|
| GuardDuty (eventos de gestão do CloudTrail, flow logs e DNS, eventos S3, login do RDS) | ≈ 5 | ≈ 5 | volume baixo de um piloto: algumas centenas de milhares de eventos e poucos GB de logs analisados por mês |
| IAM Access Analyzer (acesso externo, da conta) | 0 | 0 | sem custo para o analisador de acesso externo |
| 4 alarmes novos (`-security-*`) | 0,40 | 0,40 | US$ 0,10 por alarme |
| Filtros de métrica, regras do EventBridge e publicações no SNS | ≈ 0 | ≈ 0 | eventos de serviços da AWS no barramento padrão não são cobrados |
| Chave própria do tópico de alarmes | 1 | 1 | US$ 1 por chave por mês, mais centavos de chamadas |
| Políticas dos endpoints, SCP, papel do probe | 0 | 0 | — |
| **Acréscimo** | **≈ 7** | **≈ 7** | |
| **Total da conta** | **≈ 254** (antes ≈ 247) | **≈ 273** (antes ≈ 266) | |
| **Total das três contas** | | **≈ 527** (antes ≈ 513) | |
| Observability Setup (20/11/2026): agente com leitura a cada 120 s, +1 GB no `api-worker`, ≈ 105 métricas customizadas, alarmes de batimento, SLO e anomalia, 3 painéis | ≈ 39 a 48 | ≈ 39 a 48 | `operation/observability-setup/alarms.md`, seção 9 |
| **Total da conta com a observabilidade** | **≈ 293 a 302** | **≈ 312 a 321** | |
| **Total das três contas com a observabilidade** | | **≈ 605 a 623** | |

O orçamento mensal de cada conta (`monthly_budget_usd`, `aws_budgets_budget`) passou de US$ 300 para **US$ 350**, por decisão do responsável (20/11/2026).

## Sources

- `operation/environment-provisioning/environment-provisioning-questions.md` (Q1 a Q5 e o resumo confirmado)
- `construction/u1-bot-wallet/infrastructure-design/infrastructure-specification.md` a `construction/u7-exits-and-fiscal/infrastructure-design/infrastructure-specification.md`
- `construction/u*/nfr-requirements/security-requirements.md` das 7 Units; `construction/u1-bot-wallet/nfr-requirements/reliability-requirements.md` (NFR4.8)
- `operation/deployment-pipeline/cd-config.md` (seções 2, 3, 4.3, 4.4, 5, 6, 7 e 8), `deployment-strategy.md`, `rollback-runbook.md`
- `construction/build-and-test/test-results.md` e `build-and-test-summary.md` (as 7 metas de `environment-provisioning`)
- `infra/terraform/` (todos os módulos), `docker-compose.yml`, `infra/docker-compose.real.yml`, `infra/docker-compose.test.yml`, `infra/postgres/bootstrap/`, `.env.example`
- `apps/api/src/main/resources/application.yml`, `application-worker.yml`, `application-migrate.yml`; `apps/txsigner/src/main/resources/application.yml`, `application-migrate.yml`; `ProductionChecks`, `SecretsManagerCredentials`, `ClientIpResolver`, `AuditStartup`, `RealOpsStartupChecks`, `RealProperties`, `CoinGeckoConfig`; `apps/web/src`, `apps/admin/src`; Dockerfiles dos quatro apps
- `team.md` (Way of Working, Deployment), `project.md` (Forbidden, Mandated), regras da fase Operation
- Execução local de 2026-11-20: `hashicorp/terraform:1.9.8`, `bridgecrew/checkov:3` (3.4.6), Maven (`apps/api`, `apps/txsigner`), `rhysd/actionlint:1.7.7`
- `operation/environment-provisioning/security-posture-review.md` (SP-01 a SP-21, achados e correções sugeridas) e a decisão do responsável (Request Changes) que pediu as correções da seção 10
- `operation/deployment-pipeline/cd-config.md` 4.4 e 4.6, `rollback-runbook.md` passo 4 A2, `deployment-strategy.md` (ajustados nesta correção)

## Anexo A — Resultados da validação offline

Tudo rodou numa cópia de `infra/terraform` em `%TEMP%`, com o diretório `.terraform` limpo, nunca no repositório.

**Depois da correção da revisão de segurança (2026-11-20):**

| Comando | Resultado |
|---|---|
| `docker run hashicorp/terraform:1.9.8 fmt -check -recursive -diff` | saída 0, sem diferença |
| `terraform init -backend=false` e `validate` na raiz `infra/terraform` | "Success! The configuration is valid." |
| o mesmo na raiz `infra/terraform/org` | "Success! The configuration is valid." |
| `terraform test` na raiz | **10 de 10 verdes**: os 5 de antes (com `devnet_plan` agora conferindo 9 papéis, os 4 passáveis e os 5 do fundador) e 5 novos: `security_delivery` (`deploy_role_scope`, `a_founder_only_role_in_passable_fails_the_plan`), `security_kms_network` (`key_policies_and_role_trust`, `vpc_endpoint_policies`), `security_observability` (`detection_and_trail`) |
| `terraform test` na raiz `org` | **1 de 1 verde**: `scp.tftest.hcl` (`guardrails`: tamanho até 5120, negações IAM, `PassRole`, `AssumeRole`, etiquetas de chave e as negações de antes) |
| `bridgecrew/checkov:3 -d infra/terraform --framework terraform` (montagem só leitura) | 517 aprovados, **0 falhas**, 77 supressões, 227 recursos. As duas supressões novas: `CKV_AWS_145` no bucket da trilha (SP-08, SSE-S3 aprovado) e `CKV2_AWS_3` no detector do GuardDuty (um detector por conta-membro na única região da SCP, sem administrador delegado no piloto) |
| Checkov em `infra/terraform/org` | 1 aprovado, 0 falhas |
| `rhysd/actionlint:1.7.7` (com `shellcheck`) no texto do `deploy.yml` do `cd-config.md` 4.4 | saída 0, sem aviso |
| `shellcheck -S warning` no procedimento do fundador do `cd-config.md` 4.6 | saída 0 (os avisos informativos SC2016 são as crases do JMESPath, de propósito) |
| `apps/txsigner`: `KmsPolicyDocumentsTest` | 5 testes, 0 falhas, com os modelos de política alterados |
| `apps/api`: `RealOpsAlarmParityTest`, `CoinGeckoAlarmParityTest`, `RealExecutionAlarmParityTest`, `RealExitsAlarmParityTest`, `RealRiskAlarmParityTest`, `ComposeFilesTest` | 5 + 4 + 5 + 5 + 4 + 28 testes, 0 falhas |

**Antes da correção:**

| Comando | Resultado |
|---|---|
| `docker run hashicorp/terraform:1.9.8 fmt -check -recursive -diff` | saída 0, sem diferença (depois de `fmt` aplicado ao repositório) |
| `terraform init -backend=false` e `terraform validate` na raiz `infra/terraform` | "Success! The configuration is valid." (provedor resolvido: `hashicorp/aws` 5.100.0, restrição `~> 5.81`) |
| o mesmo na raiz `infra/terraform/org` | "Success! The configuration is valid." |
| `terraform test` (`tests/offline_plan.tftest.hcl`, `tests/services_secrets.tftest.hcl`, `mock_provider "aws"`: sem credencial, sem conta, sem rede) | 5 de 5 verdes: plano da devnet (8 papéis de execução, 13 segredos); a mainnet sem `founder_user_id` dispara o `check`; a mainnet com o fundador planeja; segredos por contêiner e nenhum segredo em variável simples; chave da Jupiter só no worker |
| `docker run bridgecrew/checkov:3 -d infra/terraform --framework terraform` antes do estágio | 216 aprovados, **41 falhas**, 4 suprimidos, 103 recursos |
| o mesmo depois do estágio | 520 aprovados, **0 falhas**, 75 suprimidos, 203 recursos |
| Checkov em `infra/terraform/org` (terraform e json) | 1 aprovado, 0 falhas |

**Como as 41 falhas anteriores caíram para zero:**
- `CKV_AWS_23` (21): descrição em todos os grupos e regras.
- `CKV_AWS_226`, `CKV_AWS_129`, `CKV2_AWS_11`, `CKV2_AWS_12` e `CKV_AWS_65`: corrigidos (atualização menor automática, export do log do PostgreSQL, flow logs, grupo padrão sem regras e Container Insights).
- `CKV_AWS_21` e `CKV2_AWS_61`: o bucket da trilha ganhou versionamento declarado e ciclo de vida.
- O resto foi suprimido com justificativa na linha (abaixo).

**Supressões na própria linha (`# checkov:skip=<ID>:<motivo>`):**

| Verificação | Onde | Motivo |
|---|---|---|
| `CKV_AWS_158` (4) | grupos de log | o CloudWatch Logs já criptografa; chave própria custa e não traz controle que o piloto use |
| `CKV_AWS_338` (3) | grupos de log de 90 dias | minimização da LGPD; a trilha de auditoria fica no banco |
| `CKV_AWS_149` (3), `CKV2_AWS_57` (3) | segredos | chave gerenciada do Secrets Manager; giro manual a cada 90 dias pelo runbook |
| `CKV_AWS_157`, `CKV_AWS_118`, `CKV_AWS_161`, `CKV_AWS_353`, `CKV2_AWS_30` | RDS | uma zona no piloto (custo; PITR cobre o RPO); sem monitoramento avançado e Performance Insights no `db.t4g.micro`; logins por senha do bootstrap; log de SQL **desligado de propósito** (nenhum endereço, valor ou segredo em log) |
| `CKV_AWS_166` | cofre do AWS Backup | chave gerenciada do AWS Backup |
| `CKV2_AWS_5` (5) | grupos de segurança | ligados em outro módulo; o Checkov não segue saídas de módulo (falso positivo) |
| `CKV_AWS_260` | 80 no balanceador | só redireciona para 443 |
| `CKV_AWS_382` | saída 443 da tarefa api+worker | RPC, Jupiter, CoinGecko e GitHub sem IPs fixos; só pelo NAT da subrede B |
| `CKV2_AWS_28` | balanceador | sem WAF no piloto; candidato do G1 |
| `CKV_AWS_378` (2) | target groups HTTP | o TLS termina no balanceador; o salto até a tarefa fica na VPC, limitado por grupos |
| `CKV_AWS_2`, `CKV_AWS_103` | listener 80 | só redireciona |
| `CKV_AWS_144` (2), `CKV_AWS_145`, `CKV_AWS_18` (2), `CKV2_AWS_62` (2) | buckets dos logs do balanceador e da trilha | uma região por desenho (SCP); os logs do balanceador só aceitam SSE-S3; o bucket já é destino de log; nenhum consumidor de eventos |
| `CKV_AWS_35`, `CKV_AWS_252` | trilha | bucket criptografado e com object lock; os alarmes notificam pelo tópico |
| `CKV_AWS_336` | web e admin | o Next.js escreve em `.next/cache`; testar na devnet (item 7 da seção 9) |
| `CKV_AWS_136` (2) | ECR (`delivery` e `db-bootstrap`) | AES256; o Deny `kms:*` do papel de implantação quebraria o push com KMS |

**Testes Java que leem `infra/`** (um Maven por vez, `-Djacoco.skip=true`):

| Comando | Resultado |
|---|---|
| `apps/api`: `./mvnw -q -Djacoco.skip=true -Dtest='RealOpsAlarmParityTest,CoinGeckoAlarmParityTest,RealExecutionAlarmParityTest,RealExitsAlarmParityTest,RealRiskAlarmParityTest,ComposeFilesTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 5 + 4 + 5 + 5 + 4 + 28 testes, 0 falhas |
| `apps/txsigner`: `./mvnw -q -Djacoco.skip=true -Dtest='KmsPolicyDocumentsTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 5 testes, 0 falhas (as políticas do KMS não mudaram nessa rodada; na correção da revisão, mudaram e o teste continuou verde) |
