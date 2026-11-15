# Especificação de infraestrutura — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes:

- `construction/u1-bot-wallet/nfr-design/`: security-design, reliability-design, scalability-design e logical-components.
- `construction/u1-bot-wallet/nfr-requirements/`.
- `construction/u1-bot-wallet/functional-design/`.
- `inception/contract-design/contract-summary.md` e `inception/domain-design/components.md`.
- A seção Deployment de `team.md`.
- `infrastructure-design-questions.md` (Q1 a Q8).

Hoje o único ambiente é o local. A nuvem é desenhada agora e aplicada quando a conta AWS existir. Esta é a revisão depois da primeira revisão do estágio: política do KMS, mTLS, rede, privacidade da âncora e custo.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Compute (local) | Docker Compose. Já existem `api`, `worker`, `db-migrate` e `web`. Entram `txsigner` e `txsigner-migrate`. Só no teste: `kms-sim` e `solana-validator` [Q1] | mesmo modelo do projeto; o TxSigner é um processo próprio (ADR-010) |
| Compute (nuvem) | ECS Fargate com três serviços: API, worker e TxSigner, numa zona de disponibilidade no piloto [Q2] | contêineres sem servidor para gerenciar |
| Descoberta (nuvem) | ECS Service Connect com namespace privado do Cloud Map. A API e o worker chamam `txsigner.internal:8443` | nome estável sem load balancer interno |
| Rede (local) | `txsigner` só na rede interna do Compose, sem porta publicada | isolamento (NFR1.2) |
| Rede (nuvem) | Ver "Rede na nuvem" | — |
| Armazenamento | Local: PostgreSQL atual, com esquema e usuário `txsigner` separados. Nuvem: dois RDS PostgreSQL, um da aplicação e um do TxSigner | credenciais e raio de impacto separados |
| Ambientes | Local, com validador e `kms-sim`; devnet, com KMS real e RPC devnet; mainnet, só depois dos marcos do bolt-plan | FR10.4 |
| IaC | Terraform em `infra/terraform/` (módulos `network`, `data`, `kms`, `services`, `observability`). Escrito na U1 e aplicado quando a conta existir. O estado fica num bucket S3 da própria conta, com versão, criptografia e trava nativa do S3. Quem aplica é o fundador, da máquina dele, com o papel de administração e MFA [Q2] | IaC versionada (`team.md`); nenhum segredo de nuvem na CI |
| Dimensionamento (piloto) | API e worker na mesma tarefa Fargate, como dois contêineres, com 0,5 vCPU e 1 GB. TxSigner com 0,5 vCPU e 1 GB, uma instância. Os dois RDS são `db.t4g.micro`, com 20 GB, numa zona | custo; o TxSigner com 0,5 vCPU cumpre a ativação em 3 s (NFR3.4), a medir na devnet |
| Implantação do TxSigner | `minimumHealthyPercent` 100 e `maximumPercent` 200: a nova tarefa sobe e fica pronta antes de a antiga sair | sem janela sem assinatura |
| Saúde | Liveness em `/actuator/health/liveness` (processo vivo). Readiness em `/actuator/health/readiness` (banco e `ListKeys` do KMS). O ECS usa a liveness e o Service Connect usa a readiness | um KMS lento não reinicia a tarefa, só a tira do tráfego |

## Rede na nuvem

| Subrede | Serviços | Rota de saída | Grupo de segurança: saída |
|---|---|---|---|
| Pública | load balancer da API; instância NAT `t4g.nano` | internet | — |
| Privada A (sem rota para internet) | TxSigner, RDS do TxSigner | só endpoints de VPC | TxSigner: 443 para os endpoints, 5432 para o RDS do TxSigner |
| Privada B (com rota para a NAT) | tarefa API e worker, RDS da aplicação | NAT, só para o worker sair ao RPC | API e worker: 443 para a internet (RPC); 8443 para o TxSigner; 5432 para o RDS da aplicação; 443 para os endpoints |

| Grupo de segurança | Entrada |
|---|---|
| Load balancer | 443 da internet |
| API e worker | 8080 só do load balancer |
| TxSigner | 8443 só do grupo da API e do worker |
| RDS da aplicação | 5432 só do grupo da API e do worker |
| RDS do TxSigner | 5432 só do grupo do TxSigner |

**Endpoints de VPC:**
- Gateway: S3 (sem custo).
- Interface, na subrede A: KMS, Secrets Manager, STS, CloudWatch Logs, ECR API e ECR DKR.

Assim o TxSigner nunca sai para a internet, e a API e o worker usam os mesmos endpoints.

**RDS:**
- Criptografia em repouso com a chave gerenciada da AWS.
- Proteção contra exclusão ligada.
- Recuperação a um ponto no tempo com retenção de 7 dias, mais uma cópia diária guardada 30 dias.
- Sem acesso público.

**Espera de 150 blocos na recuperação:** o TxSigner não fala com o RPC. Por isso a espera é por tempo: 5 minutos de relógio, o que cobre 150 blocos mesmo com slots de 2 s.

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| RDS da aplicação | database | `db.t4g.micro`, criptografado, proteção contra exclusão, recuperação a um ponto no tempo | NFR4.8 |
| RDS do TxSigner | database | igual ao da aplicação, com usuário próprio. A API e o worker não têm credencial | NFR1.2, NFR4.8 |
| AWS KMS: chaves de carteira | key management | uma chave Ed25519 assimétrica por carteira (`KeySpec` Ed25519, `KeyUsage` SIGN_VERIFY), não exportável | NFR1.1 |
| AWS KMS: chave HMAC da âncora | key management | uma chave `HMAC_256` (`KeyUsage` GENERATE_VERIFY_MAC), usada só pelo TxSigner | ver "Âncora sem o endereço" |
| Secrets Manager | secrets | credencial de serviço do TxSigner, certificados do mTLS, chave do HMAC da auditoria e senhas dos bancos | NFR1.5, NFR1.12 |
| CloudTrail | audit | trilha da conta para um bucket S3 próprio, criptografado, com bloqueio de objeto e retenção de 1 ano. Eventos de gerenciamento do KMS | NFR1.2 |
| RPC Solana | external | dois provedores por ambiente, com `rpcMaxRps` por provedor | NFR3.7 |
| `kms-sim` | test double | contêiner só de teste em `infra/kms-sim/`. Guarda etiquetas e recusa alterá-las; suporta MAC | NFR1.6 |
| `solana-validator` | test double | `solana-test-validator`, com a carteira de teste carregada pelo código de teste | NFR8.4 |
| Load balancer | load-balancer | só na frente da API | — |

## Âncora sem o endereço (correção da revisão)

A etiqueta da chave não guarda mais o endereço da carteira principal. Na criação, o TxSigner calcula um MAC com a chave HMAC do KMS sobre o endereço e grava a etiqueta `mainWalletMac`. Assim, nem o CloudTrail nem as etiquetas guardam o endereço, como pede o NFR1.10 e a regra de anonimização.

Na recuperação (`security-design.md`), a API envia o endereço da carteira principal de cada carteira. O TxSigner confere com `VerifyMac` contra a etiqueta e só recria a política se o MAC bater. Uma API invadida não consegue trocar o endereço, porque não tem a chave HMAC.

Se a carteira se perdeu nos dois bancos (desastre dentro do RPO), quando o usuário volta a entrar pela carteira principal, o login prova o endereço. A API pede ao TxSigner a reconstrução com esse endereço, que é conferido pelo MAC. A carteira volta com teto 0 e modo Assinada.

## Política do KMS (correção da revisão, Q3)

| Política | Regra |
|---|---|
| Papel do TxSigner (IAM): criação | Permite `CreateKey` e `TagResource` com cinco condições: `kms:KeySpec` Ed25519; `kms:KeyUsage = SIGN_VERIFY`; `aws:RequestTag/app = eureka-botwallet`; `ForAllValues:StringEquals aws:TagKeys` em {`app`, `mainWalletMac`, `creationRequestId`, `network`}; e `Null = false` para cada uma das quatro etiquetas, de modo que nenhuma pode faltar |
| Papel do TxSigner (IAM): uso | Permite `Sign`, `GetPublicKey`, `DescribeKey` e `ListResourceTags` com `aws:ResourceTag/app = eureka-botwallet`. Permite `GenerateMac` e `VerifyMac` só na chave HMAC da âncora. Permite `ListKeys` |
| Papel do TxSigner (IAM): negações | Nega `TagResource` e `UntagResource` quando o recurso já tem `aws:ResourceTag/app`, ou seja, depois da criação: a etiqueta não muda mais. Nega `ScheduleKeyDeletion`, `DisableKey`, `PutKeyPolicy`, `CreateGrant` e `Decrypt` |
| Política de cada chave (passada no `CreateKey`) | Dá administração à conta (`arn:aws:iam::<conta>:root`), o que delega ao IAM e satisfaz a checagem de bloqueio do KMS sem `BypassPolicyLockoutSafetyCheck`. Permite `Sign`, `GetPublicKey`, `DescribeKey` e `ListResourceTags` ao papel do TxSigner. A política da chave não nega etiquetas; a imutabilidade vem da negação no IAM |
| Administração | Papel separado com MFA. Só ações administrativas, nunca `Sign`. Uso só em emergência, e qualquer ação dele gera alerta pelo CloudTrail |
| Plano B | Se a prova técnica do B1 mostrar que a negação por `aws:ResourceTag` também barra as etiquetas no `CreateKey`, a imutabilidade passa a ser uma negação na política da chave para todos os principais exceto a conta, e a conta só age com MFA e alerta |
| Verificação | A prova técnica do B1 cria a chave com etiquetas, assina, tenta alterar uma etiqueta e tenta criar uma chave sem `mainWalletMac`, e espera recusa nas duas. Um teste do JSON das políticas roda na CI. O simulador de políticas da AWS roda antes da mainnet |
| Busca de chaves | `ListKeys` traz também chaves gerenciadas pela AWS. O TxSigner chama `DescribeKey` e só lê as etiquetas das chaves com `KeyManager = CUSTOMER`. O AccessDenied em chaves da AWS não conta como erro do KMS |

## mTLS e credencial de serviço (NFR1.5)

| Item | Desenho |
|---|---|
| Autoridade certificadora | CA interna própria, criada pelo fundador com uma chave que fica fora da nuvem, no cofre pessoal dele. Não usa a AWS Private CA, por custo |
| Certificados | Um por serviço: `txsigner` (servidor), `api` e `worker` (cliente, com `CN` igual à identidade). Validade de 90 dias, guardados no Secrets Manager |
| Identidade do chamador | O TxSigner lê o `CN` do certificado do cliente (`api` ou `worker`), usado nas métricas (`caller`) e na auditoria. Certificado de outra CA é recusado na conexão |
| Credencial de serviço | Token por cima do mTLS, no Secrets Manager. O TxSigner aceita as versões `AWSCURRENT` e `AWSPREVIOUS` e relê o segredo a cada 5 min. Assim a sobreposição de 24 h funciona sem reiniciar o TxSigner |
| Giro | Manual, pelo fundador, com um script do runbook a cada 90 dias e alerta aos 80. Gera o novo token e os novos certificados e força uma nova implantação da API e do worker (`force-new-deployment`), que leem o segredo ao subir. Depois de 24 h, o script retira a versão anterior |
| Local | Sem mTLS: só o token por cabeçalho, na rede interna do Compose |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| TxSigner (processo e banco) | U1 | U4 (swaps, pela BotWallet) | só API e worker, com mTLS e credencial; as rotas de swap entram na U4 |
| ChainWatcher e limitador do RPC | U1 | U4, U5 | só o worker chama o RPC |
| Ledger REAL | U1 | U3, U4, U5, U7 | interface M2 |

## Custo do piloto (NFR9.1)

O NFR9 limita a US$ 100 por mês o custo de KMS, RPC e dados. Hospedagem fica fora desse teto, porque ainda não tem orçamento definido (C-10, C-12 em `team.md`).

| Item | Estimativa por mês (US$) | Dentro do NFR9 |
|---|---|---|
| KMS: 21 chaves de carteira + 1 HMAC | cerca de 22 | sim |
| KMS: assinaturas assimétricas (cerca de US$ 0,15 a cada 10 mil; menos de 10 mil por mês) | menos de 1 | sim |
| RPC: plano gratuito no piloto | 0 | sim |
| **Subtotal NFR9** | **cerca de 23** | teto de 100 |
| Fargate: tarefa API e worker (0,5 vCPU, 1 GB) mais TxSigner (0,5 vCPU, 1 GB) | cerca de 36 | não |
| Dois RDS `db.t4g.micro` com 20 GB cada | cerca de 30 | não |
| Load balancer | cerca de 18 | não |
| Instância NAT `t4g.nano` e IPs públicos | cerca de 11 | não |
| Seis endpoints de VPC de interface, numa zona | cerca de 44 | não |
| CloudWatch (métricas, logs, alarmes), Secrets Manager e CloudTrail | cerca de 15 | não |
| **Hospedagem** | **cerca de 154** | decisão do fundador |

O NFR9.1 tinha usado o preço de assinatura de chave simétrica. O preço correto da assinatura assimétrica está na tabela acima, e o subtotal continua bem abaixo do teto.

A hospedagem de cerca de US$ 154 por mês é uma decisão de orçamento do fundador antes de criar a conta. A maior economia possível seria tirar os endpoints de interface (cerca de US$ 44) e deixar o TxSigner sair pela NAT só para os serviços da AWS. Isso enfraquece o isolamento e fica registrado como alternativa, não como escolha.

## Adendo da U1 ao desenho funcional e ao contrato (Q6)

Itens aplicados no Code Generation da U1:

| Item | Mudança |
|---|---|
| Withdrawal | colunas técnicas `attempts` e `nextAttemptAt`, com índice por (`state`, `nextAttemptAt`) |
| BotWalletAccount | marca `policyResetRequired`, ligada quando a política foi reconstruída na recuperação; a tela pede um consentimento novo |
| Contrato R1 | `GET /internal/v1/wallets?createdBefore=` devolve endereço, `creationRequestId`, criação e situação, nunca `kmsKeyRef`. `GET /internal/v1/signatures?since=` devolve `signingRequestId`, assinatura, tipo e data. `POST /internal/v1/recovery/wallets` reconstrói com o endereço da carteira principal conferido pelo MAC. Todas exigem mTLS e credencial |
| Contrato X4 | O KMS simulado é o contêiner `kms-sim`, não código dentro do TxSigner |
| Recuperação | A etiqueta `network` precisa ser igual à rede do TxSigner, e o MAC precisa bater com o endereço enviado; senão a chave é bloqueada e gera alerta. Sem os rebaixamentos da API em 10 min, a recuperação continua com alerta, e o saque segue permitido |
| Restauração do banco da aplicação | Além dos SigningRecords, o worker lê na rede as assinaturas de cada carteira do bot desde o ponto restaurado e recria os saques para a carteira principal que faltam [Q4] |
| RPC | Saldos em lote (`getMultipleAccounts`, até 100) uma vez por ciclo; estado dos saques em lote a cada 2 s, só com saque pendente; altura comparada em `finalized` [Q5] |
| Modo "um provedor só" | Ligado só pelo fundador, por comando administrativo auditado, e vale no máximo 24 h. Nesse modo, um saque vira FAILED só se o provedor que responde informar altura `finalized` acima de `lastValidHeight` mais 300 slots e não encontrar a assinatura. Fora desse modo, vale a regra dos dois provedores |
