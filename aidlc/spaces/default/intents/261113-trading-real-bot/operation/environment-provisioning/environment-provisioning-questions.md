# Perguntas do Environment Provisioning — intent 261113-trading-real-bot

Conversa: português (Brasil). As respostas seguem a instrução do responsável "siga sem me perguntar": em cada pergunta foi escolhida a opção recomendada.

Fontes:
- `construction/u*/infrastructure-design/infrastructure-specification.md` das 7 Units;
- `construction/u*/nfr-requirements/security-requirements.md`;
- `operation/deployment-pipeline/cd-config.md`, com as lacunas da seção 5;
- `infra/terraform/`;
- `team.md`, seção Deployment: não há conta de nuvem, e uma conta AWS nova é aberta para o KMS antes da mainnet;
- `project.md`: nenhum fundo real é movimentado sem a aprovação explícita do responsável.

## Pergunta 1 — Provisionar agora?

Ainda não existe conta AWS. O assistente não tem credencial e não deve abrir conta nem gastar. O que esta etapa faz?

A. Completar a IaC: fechar as lacunas que o `cd-config.md` achou, validar sem aplicar (`terraform fmt`, `validate` e Checkov) e entregar o roteiro de aplicação para o responsável executar quando a conta existir. O ambiente fica registrado como "não provisionado", e as 7 metas de ambiente continuam Unverified até a aplicação. (recomendado)
B. O responsável abre a conta agora e o assistente aplica o Terraform com uma credencial temporária
C. Pular a etapa
X. Other (please specify)

[Answer]: A

## Pergunta 2 — Contas da devnet e da mainnet

A devnet e a mainnet ficam em contas AWS separadas ou na mesma conta?

A. Contas separadas, num AWS Organizations com uma SCP que nega a saída da região e o `kms:ScheduleKeyDeletion` sem MFA. Na conta da devnet o KMS é real, mas a rede Solana é a devnet. Nunca há chave da mainnet na conta da devnet. (recomendado: isola a chave da mainnet e o custo)
B. Mesma conta, separada por prefixo e tags
X. Other (please specify)

[Answer]: A

## Pergunta 3 — Rede

Como fica a rede, isto é, VPC, sub-redes, grupos de segurança e NACLs?

A. Conforme o Infrastructure Design:
   - **VPC:** uma por conta, sem NAT, com endpoints de VPC para KMS, Secrets Manager, ECR, logs, monitoring e STS, e endpoint de gateway para o S3.
   - **Sub-redes:** privadas para ECS e RDS, e públicas só para o balanceador.
   - **Grupos de segurança:** com regra de saída explícita em cada um, fechando as lacunas do `cd-config.md`: o TxSigner chega ao RDS dele pela 5432 e ao S3 pela lista de prefixos, e a API e o worker ganham saída.
   - **NACLs:** as padrão.

   A saída para a internet (RPC e Jupiter) passa por um NAT único só se não houver endpoint. Isso fica registrado como custo.

   (recomendado)
B. Rede com NAT em cada zona desde o piloto
X. Other (please specify)

[Answer]: A

## Pergunta 4 — Segredos e parâmetros

Como os segredos chegam aos serviços?

A. Pelo Secrets Manager, injetado pela definição de tarefa (`secrets`):
   - a chave da CoinGecko, a chave da Jupiter, as senhas dos bancos, a chave HMAC de auditoria e a chave de assinatura dos tokens de acesso;
   - cada serviço só lê os seus segredos;
   - nenhum segredo vai em variável de ambiente simples nem em `NEXT_PUBLIC_*`.

   Os parâmetros sem segredo, como as flags e as URLs de RPC, ficam em variáveis versionadas no Terraform. Esta etapa faz a auditoria da lista de segredos contra o código.
   (recomendado)
B. Usar o Parameter Store para tudo
X. Other (please specify)

[Answer]: A

## Pergunta 5 — Conectividade entre contas e VPCs

Existe conexão entre contas ou entre VPCs?

A. Não. Cada conta é isolada, sem peering nem Transit Gateway. O TxSigner só é alcançado pelo Service Connect dentro da própria VPC. A validação confirma que nada fora da VPC alcança o TxSigner. (recomendado)
B. Peering entre a devnet e a mainnet para monitoramento comum
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Nada é provisionado agora: não há conta AWS. A etapa fecha as lacunas da IaC, valida sem aplicar com `fmt`, `validate` e Checkov, e entrega o roteiro de aplicação para o responsável. O ambiente fica "não provisionado", e as 7 metas de ambiente continuam Unverified [Q1].
- A devnet e a mainnet ficam em contas separadas, num Organizations com SCP de região e de exclusão de chave. Nunca há chave da mainnet na conta da devnet [Q2].
- Rede do Infrastructure Design:
  - VPC sem NAT, com endpoints;
  - sub-redes privadas para ECS e RDS;
  - grupos de segurança com saída explícita, fechando as lacunas do `cd-config.md`;
  - NAT único só se faltar endpoint, registrado como custo [Q3].
- Segredos no Secrets Manager, injetados por serviço. Nenhum segredo em variável simples. Os parâmetros sem segredo ficam versionados no Terraform, e a lista de segredos é auditada contra o código [Q4].
- Contas isoladas, sem peering. O TxSigner só é alcançado dentro da VPC, e isso é validado [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
