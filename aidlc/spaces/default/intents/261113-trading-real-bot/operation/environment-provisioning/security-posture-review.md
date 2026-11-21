# Revisão da postura de segurança da IaC — intent `261113-trading-real-bot`

Data: 2026-11-20. Revisor: `aidlc-devsecops-agent`, no passo 3 do estágio Environment Provisioning. Conversa: português (Brasil).

Revisei só por leitura o `infra/terraform` da árvore de trabalho atual, sobre o commit `3497b2b` mais as mudanças ainda sem commit. Nada foi aplicado em nenhuma conta. A única ferramenta que rodei foi o Checkov, em Docker, com o diretório montado só para leitura. O resultado bate com o relatório: 520 aprovados, **0 falhas** e 75 supressões. A minha execução contou 210 recursos, contra os 203 do relatório; a diferença é pequena e não muda nada.

## Veredito

**APROVADO COM RESSALVAS.**

- **Devnet:** a IaC pode ser aplicada na devnet como está. Nenhum achado põe em risco dinheiro de verdade nessa conta.
- **Mainnet:** a IaC **não** pode ir para a mainnet antes de corrigir o SP-01 (Crítico), o SP-02 (Alto) e os achados Médios marcados "mainnet" na tabela.

O ponto central é este. O Checkov passa limpo, mas não avalia a combinação de permissões entre papéis. Hoje o papel de implantação do GitHub consegue, por vias indiretas, assinar com a chave da carteira do bot e ler qualquer segredo da conta. Isso contraria o `project.md` (Mandated: só o TxSigner, com credencial própria, assina) e o ADR-010.

## Achados

| ID | Severidade | Onde (file:line) | Achado | Correção sugerida | Bloqueia antes de |
|---|---|---|---|---|---|
| SP-01 | Crítico | `infra/terraform/modules/delivery/main.tf:158-167`, `:175-184`, `:203-215`; `infra/terraform/main.tf:370-374` | **O papel de implantação equivale a poder assinar e a ler qualquer segredo.** Ele pode: registrar revisão de qualquer família (`RegisterTaskDefinition` em `*`); passar o papel de tarefa do TxSigner e os 8 papéis de execução, inclusive os de bootstrap, que leem o segredo mestre dos dois RDS (`iam:PassRole`); rodar a família `${name}-txsigner-probe`, que não está no Terraform e por isso é registrada pelo próprio deploy; escolher rede e *overrides* no `RunTask` (`taskRoleArn`, `executionRoleArn`, `command`); e ler os logs de `/ecs/${name}/*`. Três caminhos: (a) registrar o probe com o papel do TxSigner, assinar qualquer transação, gravar no log, ler o log e transmitir pelo runner; (b) registrar uma revisão do `api-worker` com o papel do TxSigner, que tem saída pelo NAT, e chamar `UpdateService`; (c) registrar o probe com o papel de execução `txsigner-db-bootstrap`, injetar a senha mestre ou a do dono do esquema `txsigner` e trocar `mainWalletAddress` na WalletPolicy, de modo que um saque legítimo vá para o atacante. O Deny de `kms:*` e `secretsmanager:*` no papel de implantação não impede nada disso, porque o efeito vem dos papéis passados. Hoje o único freio é a aprovação do fundador no Environment `mainnet`. | 1) Tirar de `passable_role_arns` o papel de tarefa do TxSigner e os papéis de execução `txsigner`, `txsigner-migrate`, `db-bootstrap` e `txsigner-db-bootstrap`. As revisões das famílias do TxSigner passam a ser registradas pelo fundador com MFA: `apply` com o digest da imagem, ou um papel `deploy-txsigner` que só se assume com MFA. 2) Negar `ecs:UpdateService` em `service/${cluster}/txsigner` ao papel de implantação, porque o `UpdateService` também muda a rede do serviço. 3) Declarar a família do probe no Terraform, sem papel de tarefa e com um papel de execução sem segredos. 4) No GitHub, Environment `mainnet` com o fundador como revisor obrigatório e implantação só a partir de `main`; se possível, `sub` personalizado com `job_workflow_ref`. 5) Alarme no CloudTrail para `RegisterTaskDefinition` ou `RunTask` que citem o papel do TxSigner. | mainnet |
| SP-02 | Alto | `infra/terraform/modules/kms/main.tf:9-19`; `org/policies/scp-guardrails.json.tftpl:30-37`; `modules/kms/policies/wallet-key-policy.json.tftpl:23-39` | **O papel do TxSigner pode ser alterado ou assumido sem MFA, e a credencial dele assina de fora da VPC.** A SCP só exige MFA para `PutKeyPolicy`, `ScheduleKeyDeletion` e `DisableKey`. Um administrador sem MFA, ou qualquer principal com `iam:*`, pode mudar a confiança ou as políticas do papel do TxSigner, assumi-lo e assinar: a política da chave aceita `aws:PrincipalArn` = papel do TxSigner vindo de qualquer lugar. Pelo mesmo motivo, uma credencial temporária do papel roubada dos metadados da tarefa serve para assinar pela internet. A confiança do papel (`ecs-tasks`) não tem `aws:SourceAccount` nem `aws:SourceArn`, ao contrário dos papéis de execução. | 1) SCP: negar sem MFA `iam:UpdateAssumeRolePolicy`, `PutRolePolicy`, `DeleteRolePolicy`, `AttachRolePolicy`, `DetachRolePolicy`, `PutRolePermissionsBoundary`, `DeleteRolePermissionsBoundary` e `DeleteRole` sobre `role/${name}-txsigner`. 2) SCP: negar `sts:AssumeRole*` desse papel a todo principal da conta; o ECS não é afetado, porque a SCP não se aplica a principal de serviço. 3) Nas políticas da chave da carteira e da chave HMAC, condicionar `Sign` e `GenerateMac`/`VerifyMac` a `aws:SourceVpce` = ID do endpoint do KMS. 4) Confiança com `aws:SourceAccount` e `aws:SourceArn` = `arn:aws:ecs:<região>:<conta>:*`. 5) Alarme para eventos IAM sobre esse papel. | mainnet |
| SP-03 | Médio | `modules/kms/policies/wallet-key-policy.json.tftpl:5-21` (linhas 16-17); `apps/txsigner/.../RecoveryService.java:161` | **As etiquetas da chave podem mudar sem MFA, e o MAC da âncora não está ligado à chave.** A declaração da conta (`root`) na política da chave permite `TagResource` e `UntagResource`, e a SCP não cobre essas ações. Já o `security-design.md` da U1 diz que as etiquetas são imutáveis. O MAC cobre só o endereço da carteira principal. Quem copiar o `mainWalletMac` de outra chave (a do próprio atacante) e tiver a API invadida durante uma recuperação reconstrói a política com saque para a carteira do atacante. E tirar a etiqueta `app` bloqueia o `Sign`, o que nega o saque, contra o Mandated do saque sempre disponível. | Tirar `kms:TagResource` e `kms:UntagResource` da declaração da conta e pôr um Deny explícito depois da criação. Na SCP, negar `kms:TagResource` e `kms:UntagResource` sem MFA. No código, ligar o MAC à chave: `network|botWalletAddress|mainWalletAddress`. Ainda não há chave na mainnet, então a mudança é barata agora. | mainnet |
| SP-04 | Médio | `apps/txsigner/.../infra/aws/AwsKmsAdapter.java:89-90`; `apps/txsigner/src/main/resources/application.yml:111` | **O CreateKey falha aberto.** Com `TXSIGNER_KMS_KEY_POLICY` vazio (o padrão é `${...:}`), o TxSigner cria a chave com a política padrão da AWS: `kms:*` para a conta e sem o Deny `NobodyElseSigns`. Aí qualquer principal com `kms:Sign` na própria política IAM assina. A IaC preenche a variável, mas uma revisão de tarefa sem ela não é recusada. | `ProductionChecks` recusa subir sem política, ou com uma política que não traga as declarações `OnlyTheTxSignerSigns` e `NobodyElseSigns`. Teste de inicialização cobrindo esse caso. | mainnet |
| SP-05 | Médio | `infra/terraform/modules/network/main.tf:438-454` | **Endpoints de VPC sem política.** O gateway do S3 aceita qualquer bucket, de qualquer conta. Os endpoints do KMS, STS e Secrets Manager aceitam principais e recursos de outras contas. Uma tarefa comprometida na subrede do TxSigner, que não tem internet, ainda consegue tirar dados por eles: transações assinadas, credenciais do papel, dados do banco do TxSigner. | Política em cada endpoint de interface com `aws:PrincipalAccount` e `aws:ResourceAccount` iguais à conta. No S3, liberar só `arn:aws:s3:::prod-<região>-starport-layer-bucket/*` (camadas do ECR) e os buckets da conta. Grupo próprio para o endpoint do KMS, que aceite 443 só do grupo `txsigner`. | mainnet |
| SP-06 | Médio | `infra/terraform/main.tf:326`; `modules/services/main.tf:265-275` | **Sem mTLS entre a API/worker e o TxSigner** (item 3 do relatório). O NFR1.5 e o `security-design.md` da U1 tornam o mTLS obrigatório na nuvem, com o certificado identificando o chamador. Hoje é `http://txsigner.internal:8443`, com um único token de portador compartilhado pela API e pelo worker. O token trafega em claro dentro da VPC, e o TxSigner não sabe se quem chamou foi a API ou o worker. | Ligar TLS no Service Connect (AWS Private CA no modo de certificado de vida curta) ou TLS na aplicação com certificado de cliente por chamador. Ou então o fundador registra em ADR a dispensa do requisito, com o risco aceito, antes da mainnet. | mainnet |
| SP-07 | Médio | `infra/terraform/main.tf:346-348`; `apps/api/.../identity/web/ClientIpResolver.java:29` | **Todos os limites por IP contam o IP do nó do balanceador** (item 2 do relatório). Com `TRUSTED_PROXIES` vazio, o `LoginGate`, o `AdminAuthController`, o `InviteLookupController` e o `LegalDocumentsRateLimitFilter` juntam todos os usuários num mesmo balde. Um atacante esgota o limite de login de todos, inclusive do fundador e do admin. Como o saque exige login, isso vira uma negação do saque. Por isso classifico como bloqueio antes da mainnet, não só antes dos convidados. | Suporte a faixa CIDR no `ClientIpResolver` (a regra de pegar o primeiro salto não confiável a partir da direita já está certa) e `TRUSTED_PROXIES` com as faixas das duas subredes públicas (`10.40.0.0/24`, `10.40.4.0/24`), onde só ficam os nós do balanceador. | mainnet |
| SP-08 | Médio | `infra/terraform/modules/observability/main.tf:23-30` | **A entrega da trilha no S3 deve falhar** (item 6 do relatório). A criptografia padrão do bucket é `aws:kms` com a chave gerenciada `aws/s3`, cuja política não aceita o serviço do CloudTrail. Sem essa entrega, a única cópia é o grupo de log `/${name}/cloudtrail`, que pode ser apagado: nenhuma SCP protege `logs:DeleteLogGroup`. Os alarmes do KMS continuam funcionando, mas a cópia imutável de 1 ano se perde. | Trocar já para `AES256` (SSE-S3). O object lock continua sendo o controle, e eu aprovo a troca como revisor de segurança. A alternativa é uma CMK com a declaração do CloudTrail. Na SCP, negar sem MFA `logs:DeleteLogGroup`, `logs:DeleteMetricFilter` e `logs:PutRetentionPolicy` no grupo da trilha. | mainnet |
| SP-09 | Médio | `infra/terraform/modules/services/alb.tf:6-21`, `:97-114` | **O host do admin é público, sem filtro de origem nem WAF.** É por ele que o fundador administra o produto, inclusive as liberações do REAL. A proteção hoje é só o login por carteira de admin. Sem WAF (`CKV2_AWS_28`), também não há limite de taxa na borda. | Condição `source_ip` na regra do host `admin` (IPs do fundador), ou ação `authenticate-oidc` no balanceador. WAF com regras gerenciadas e limite de taxa antes dos convidados. | convidados |
| SP-10 | Médio | `infra/terraform/modules/network/main.tf:404-415` | A API divide com o worker a saída `0.0.0.0/0:443` pelo NAT (`CKV_AWS_382`, aceito na U2). Uma API invadida tira dados pessoais ou faz SSRF para qualquer host. As listas de hosts só existem no código. | Route 53 Resolver DNS Firewall na VPC, com lista de permissão de domínios (provedores de RPC, Jupiter, CoinGecko, `api.github.com`) e bloqueio do resto. Custa pouco e não exige separar a tarefa. | convidados |
| SP-11 | Médio | `infra/terraform/main.tf` (ausente) | **Faltam controles de detecção numa conta que move fundos:** GuardDuty (inclusive o runtime do ECS e o uso de credencial de tarefa fora da AWS), IAM Access Analyzer, e alarmes para mudanças IAM no papel do TxSigner, para `RegisterTaskDefinition`/`RunTask` que o citem, para uso do usuário raiz e para login sem MFA. | GuardDuty e Access Analyzer nas duas contas-membro, com os achados altos indo do EventBridge para o tópico de alarmes. Filtros de métrica na trilha para os eventos acima. | mainnet |
| SP-12 | Médio | `infra/terraform/modules/observability/main.tf:126-164` (e os alarmes das U2 a U7) | Os alarmes de segurança e de operação leem métricas que o código ainda não publica no CloudWatch (item 11 do relatório). Com `treat_missing_data = notBreaching`, eles ficam verdes para sempre, inclusive os erros de KMS do TxSigner (NFR5.19). | Publicar as métricas no CloudWatch (EMF ou um coletor ADOT) e criar um alarme de ausência de dados (*heartbeat*) para cada namespace. | mainnet |
| SP-13 | Baixo | `modules/kms/policies/txsigner-role.json.tftpl:5-25` | O `CreateKey` não exige `kms:KeyOrigin = AWS_KMS` nem `kms:MultiRegion = false`. Sem isso, a política IAM não garante sozinha que a chave nasceu no KMS e que nunca vai ser replicada (NFR1.1). | Acrescentar as duas condições. | mainnet |
| SP-14 | Baixo | `org/policies/scp-guardrails.json.tftpl:30-37` | Com MFA, um administrador agenda a exclusão de uma chave de carteira com 7 dias de espera, e a exclusão perde os fundos para sempre. | Na SCP, negar `kms:ScheduleKeyDeletion` com `kms:ScheduleKeyDeletionPendingWindowInDays` < 30. Melhor ainda: negar sempre para chaves com `aws:ResourceTag/app = eureka-botwallet`, com o "vidro de emergência" sendo mudar a SCP pela conta de gerenciamento. | mainnet |
| SP-15 | Baixo | `infra/terraform/modules/data/backup.tf:188-191` | O cofre do AWS Backup não tem Vault Lock nem política que negue a exclusão de pontos de recuperação. | `aws_backup_vault_lock_configuration` (modo governance na devnet, compliance na mainnet) ou política de cofre que negue `DeleteRecoveryPoint`. | mainnet |
| SP-16 | Baixo | `infra/terraform/modules/services/secrets.tf:185-192` | Os segredos não têm política de recurso e usam a chave gerenciada: qualquer principal da conta com `GetSecretValue` lê o token do TxSigner e as senhas. A IaC também não tem o alerta de idade do token aos 80 dias (NFR1.5). | Política de recurso que negue `GetSecretValue` a quem não seja o papel de execução ou de tarefa dono do segredo, mais o papel de administração com MFA. Alarme de idade do segredo (EventBridge ou Config). | convidados |
| SP-17 | Baixo | `modules/data/main.tf:95-98` | `rds.force_ssl=1` com `sslmode=prefer`: há criptografia, mas o certificado do servidor não é conferido (item 12 do relatório). | `sslmode=verify-full` com o pacote de CAs do RDS na imagem. | convidados |
| SP-18 | Baixo | `modules/services/web-admin.tf:227-228` | O web e o admin rodam com raiz gravável (`CKV_AWS_336`, item 7 do relatório). | Raiz somente leitura, com um volume para `.next/cache`; testar na devnet. | convidados |
| SP-19 | Baixo | `modules/network/main.tf:186-194`; `modules/data/backup.tf:207-215` | As confianças dos papéis do flow log e do AWS Backup não têm `aws:SourceAccount`. | Acrescentar a condição. | — |
| SP-20 | Baixo | `.github/workflows/ci.yml` (item 13 do relatório) | A CI não roda `terraform fmt -check`/`validate`/`test` nem o Checkov. E o Checkov não pegaria os achados SP-01 a SP-04. | Pôr os três comandos e o Checkov na CI. Escrever uma verificação própria (Checkov customizado ou OPA): o papel do TxSigner fora de `passable_role_arns`, a política da chave com `NobodyElseSigns` e `aws:SourceVpce`, a SCP com as negações IAM. Só o responsável muda o `ci.yml`. | mainnet |
| SP-21 | Baixo | `validation-report.md` seção 7.2 | Os buckets de estado, criados fora do Terraform, não têm política só-TLS nem restrição de acesso. O estado traz a topologia e a política das chaves. | Política só-TLS e acesso restrito ao papel de administração. | — |

## As 75 supressões do Checkov

As 43 anotações viram 75 supressões por causa dos `for_each`. Nenhuma está sem justificativa. Separo assim:

- **Justificativa boa, risco baixo:**
  - `CKV_AWS_158`, `CKV_AWS_338`: grupos de log;
  - `CKV_AWS_166`: cofre do Backup;
  - `CKV_AWS_157`, `CKV_AWS_118`, `CKV_AWS_353`, `CKV_AWS_161`, `CKV2_AWS_30`: RDS. Deixar o log de SQL desligado é, inclusive, um controle;
  - `CKV2_AWS_5`: falso positivo;
  - `CKV_AWS_260`, `CKV_AWS_2`, `CKV_AWS_103`: a porta 80 só redireciona;
  - `CKV_AWS_378`: HTTP dentro da VPC;
  - `CKV_AWS_144`, `CKV_AWS_145`, `CKV_AWS_18`, `CKV2_AWS_62`: buckets de log;
  - `CKV_AWS_252`: SNS da trilha;
  - `CKV_AWS_136`: ECR com AES256, porque as imagens não têm segredo.
- **Justificativa aceitável no piloto, mas o risco tem de ser fechado:**
  - `CKV2_AWS_28` (sem WAF): SP-09, antes dos convidados;
  - `CKV_AWS_382` (saída 443 para qualquer destino): SP-10, antes dos convidados;
  - `CKV_AWS_336` (raiz gravável): SP-18;
  - `CKV_AWS_149` e `CKV2_AWS_57` (segredos com chave gerenciada e giro manual): SP-16. O giro manual do token do TxSigner só funciona com o alerta de idade, que não está na IaC.
- **Justificativa que esconde um problema:**
  - `CKV_AWS_35` (trilha sem CMK) parte do princípio de que "o bucket está criptografado". Só que a criptografia escolhida (`aws/s3`) provavelmente impede a entrega do CloudTrail. Ver SP-08.

**Atenção:** "0 falhas" no Checkov não quer dizer que a IaC está segura. Os quatro achados mais graves (SP-01 a SP-04) estão em combinações de IAM, na política da chave e no código, e nenhuma verificação padrão do Checkov olha para isso.

## Itens em aberto do `validation-report.md` (seção 9)

| # | Item | Classificação |
|---|---|---|
| 1 | Orçamento | aceitável (decisão do fundador; sem efeito na segurança) |
| 2 | `ClientIpResolver` atrás do balanceador | **bloqueia antes da mainnet** (SP-07); o relatório dizia "antes dos convidados" |
| 3 | mTLS entre a API e o TxSigner | **bloqueia antes da mainnet** (SP-06): implementar, ou registrar a dispensa do NFR1.5 em ADR |
| 4 | SQL do bootstrap no RDS | antes da devnet, só como verificação funcional. Se falhar, a variante para o RDS não pode dar ao login da aplicação nenhum atributo a mais (`BYPASSRLS`, `CREATEROLE`) |
| 5 | Service Connect na subrede sem internet | antes da devnet, só verificação. Se faltar endpoint, acrescentar `ecs`, `ecs-agent` e `ecs-telemetry`; **nunca** dar rota de NAT à subrede A |
| 6 | CloudTrail com SSE-KMS `aws/s3` | **bloqueia antes da mainnet** (SP-08). Recomendo trocar para AES256 já, antes da devnet, e essa troca fica aprovada por esta revisão |
| 7 | Raiz somente leitura no web e no admin | antes dos convidados (SP-18) |
| 8 | WAF no balanceador | antes dos convidados (SP-09) |
| 9 | Prova do KMS real (NFR1.1) | **bloqueia antes da mainnet**. Prefiro a opção (b): uma tarefa avulsa com o papel do TxSigner, registrada e rodada **pelo fundador com MFA**, só na devnet, com a revisão cancelada depois. A opção (a) alarga a confiança do papel que assina, justamente a superfície do SP-02. |
| 10 | Chave da Jupiter (especificação contra requisito) | aceitável: a IaC segue o requisito "só o worker", que é o mais restrito |
| 11 | Métricas do TxSigner e dos apps no CloudWatch | **bloqueia antes da mainnet** (SP-12) |
| 12 | TLS do RDS com `verify-full` | antes dos convidados (SP-17) |
| 13 | `terraform fmt`/`validate`/`test` na CI | antes da mainnet (SP-20) |

## O que passou

- **KMS:**
  - na política da chave da carteira, só o papel do TxSigner tem `Sign` (Allow mais um Deny por `aws:PrincipalArn`);
  - na chave HMAC da âncora, só o TxSigner gera o MAC;
  - o papel do TxSigner tem Deny explícito de `ScheduleKeyDeletion`, `DisableKey`, `PutKeyPolicy`, `CreateGrant` e `Decrypt`;
  - o `CreateKey` exige `ECC_NIST_EDWARDS25519`/`SIGN_VERIFY` e as quatro etiquetas;
  - as chaves das carteiras nascem dentro do KMS, pelo TxSigner, nunca pelo Terraform, e a chave privada assimétrica não pode ser exportada;
  - nenhum segredo nem material de chave entra no estado.
- **IAM:**
  - a API, o worker, o web e o admin não têm papel de tarefa, então não têm nenhuma permissão AWS;
  - há um papel de execução por família, limitado aos próprios repositórios, ao próprio grupo de log e aos próprios ARNs de segredo, com confiança restrita por `aws:SourceAccount`;
  - o `iam:PassRole` está limitado a `ecs-tasks.amazonaws.com`;
  - o papel de implantação usa OIDC, sem chave longa, e confia só no Environment de mesmo nome;
  - `ecs:ExecuteCommand` está negado e fica desligado nos serviços.
- **Rede:**
  - o TxSigner não tem IP público, a subrede dele não tem rota para a internet e a porta 8443 só aceita o grupo `api-worker`;
  - o balanceador só chega na API (8080/8081) e no web/admin (3000);
  - host desconhecido recebe 404; TLS pela `ELBSecurityPolicy-TLS13-1-2-2021-06`; `drop_invalid_header_fields`; HTTP redirecionado para HTTPS;
  - o web e o admin não têm saída para a internet;
  - toda saída de grupo é explícita, e o grupo padrão da VPC não tem regras;
  - flow logs `ALL` com 90 dias;
  - o único NAT atende só a subrede B.
- **Segredos:**
  - nenhum segredo em variável simples, e o teste offline confere isso;
  - nenhum `aws_secretsmanager_secret_version`; as senhas mestras ficam com `manage_master_user_password`;
  - as URLs de RPC são tratadas como segredo e só vão para o worker;
  - cada contêiner recebe só os próprios segredos; o web e o admin não recebem nenhum;
  - nenhuma variável `NEXT_PUBLIC_*`;
  - o `.gitignore` cobre estado, planos e `*.tfvars`.
- **RDS:** criptografado, `rds.force_ssl=1`, sem acesso público, proteção contra exclusão, PITR de 7 dias mais o Backup diário de 30, snapshot final, log de SQL e de parâmetros desligado, e cada banco aceita só o grupo da própria tarefa.
- **CloudTrail e S3:**
  - trilha multirregião com validação de arquivo;
  - bucket com object lock COMPLIANCE de 1 ano, versionamento, bloqueio de acesso público e política só-TLS;
  - alarmes para ação administrativa no KMS e para mais de 10 chaves criadas por hora;
  - o bucket de logs do balanceador tem SSE-S3, versionamento, bloqueio de acesso público e política só-TLS.
- **Cadeia de entrega:** ECR com tags imutáveis e varredura no push, imagens pelo SHA, raiz somente leitura nos contêineres JVM, circuit breaker com rollback.
- **SCP:** trava de região, exclusão, desligamento e mudança de política de chave só com MFA, proibição de sair da organização, e trilha só desligável com MFA. Contas devnet e mainnet separadas.

## Sources

- `infra/terraform/` (todos os módulos, `org/`, `environments/*.tfvars.example`) e `git diff` de `infra/` e `.gitignore` em 2026-11-20
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/environment-provisioning/validation-report.md` (seções 2 a 9 e Anexo A)
- `aidlc/spaces/default/intents/261113-trading-real-bot/construction/u1-bot-wallet/nfr-requirements/security-requirements.md` (NFR1.1, NFR1.2, NFR1.5, NFR1.12) e `construction/u1-bot-wallet/nfr-design/security-design.md`
- `aidlc/spaces/default/intents/261113-trading-real-bot/inception/domain-design/decisions.md` (ADR-004, ADR-005, ADR-008, ADR-010)
- `aidlc/spaces/default/memory/project.md` (Forbidden e Mandated) e `team.md` (Deployment)
- Código: `apps/txsigner/.../infra/aws/AwsKmsAdapter.java`, `apps/txsigner/.../domain/RecoveryService.java`, `apps/txsigner/src/main/resources/application.yml`, `apps/api/.../identity/web/ClientIpResolver.java`
- Execução local: `bridgecrew/checkov:3` (3.4.6), `-d infra/terraform --framework terraform` com montagem somente leitura → 520 aprovados, 0 falhas, 75 supressões, 210 recursos
