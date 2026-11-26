# Perguntas do Environment Provisioning — intent `261121-paper-real-unico`

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes:
- `team.md`, seção Deployment: nenhum ambiente na nuvem foi provisionado. O único ambiente que roda é o local (Windows, Docker Compose). A organização AWS com as contas `devnet` e `mainnet` está desenhada, com orçamento de US$ 350 por conta, e a região ainda está a definir.
- `261113-trading-real-bot/operation/environment-provisioning/`: inventário, validação e revisão de postura de segurança.
- Os `infrastructure-design/` das Units deste intent:
  - U2: D-INF2, o teto de conexões;
  - U3: `terraform plan` vazio;
  - U4: F4, as variáveis da flag, a validação e a saída `api_worker_container_environment`; as 3 métricas; os 2 alarmes; o custo de cerca de US$ 1,90 por conta.
- `operation/deployment-pipeline/`: as condições 1 a 5 e a lacuna IR-G3, que é não haver caminho de SQL na nuvem.

## Q1. Provisionar agora?

Os ambientes na nuvem devem ser provisionados agora?

A. Não provisionar nesta etapa. Não há conta AWS nem região definidas, o Terraform não está instalado aqui, e criar contas e recursos gera custo. Essa é uma decisão do fundador, fora deste intent. A etapa registra:
   - o **inventário planejado** do que este intent acrescenta aos ambientes do 261113: a variável da flag nos `tfvars`, as métricas e alarmes, as migrações, a saída do módulo e o custo;
   - a **validação estática** que dá para fazer sem a nuvem: leitura do Terraform, conferência das variáveis, da validação e dos alarmes contra os desenhos.

   Os testes do Terraform e o Checkov ficam `Unverified`, com o dono e o comando, até o Terraform ser instalado ou entrar na CI. (recomendado)
B. Provisionar a devnet agora
X. Other (please specify)

[Answer]: A

## Q2. Rede, segredos e contas

O que este intent muda em rede, segredos e contas?

A. Nada novo. VPC, sub-redes, security groups, Secrets Manager e KMS seguem o 261113 sem mudança. A flag da U4 não é segredo: é uma variável de ambiente da definição de tarefa, com o valor no `tfvars` fora do repositório. Nenhuma conexão entre a devnet e a mainnet. O inventário diz isso de forma explícita. (recomendado)
B. Revisar a rede inteira
X. Other (please specify)

[Answer]: A

## Q3. Postura de segurança

Como fica a revisão da postura de segurança?

A. Uma revisão curta da postura de segurança, só do que este intent acrescenta:
   - a flag com o padrão desligado e a validação de que o worker não liga sem a API;
   - as métricas e alarmes sem dado pessoal;
   - as migrações só de expansão;
   - o índice criado com `CONCURRENTLY`;
   - nenhum IAM novo.

   A revisão também registra os riscos que vêm de antes e afetam este intent:
   - IR-G3: não há como rodar SQL na nuvem para as conferências da virada nem para o diagnóstico de migração;
   - D-INF2: o teto de conexões no rolling e a falta de alarme de conexões do RDS.

   Cada risco sai com a mitigação proposta e com quem decide. (recomendado)
B. Revisão completa
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Provisionamento:** nada é provisionado agora (não há conta, região nem Terraform aqui, e é custo seu). Ficam o inventário planejado do que este intent acrescenta e uma validação estática do Terraform; os testes do Terraform e o Checkov ficam `Unverified`, com dono e comando [Q1].
- **Rede, segredos e contas:** sem mudança em relação ao 261113; a flag não é segredo [Q2].
- **Segurança:** uma revisão curta do que este intent acrescenta, com os riscos IR-G3 (não há SQL na nuvem) e D-INF2 (conexões no rolling), cada um com a mitigação e com quem decide [Q3].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
