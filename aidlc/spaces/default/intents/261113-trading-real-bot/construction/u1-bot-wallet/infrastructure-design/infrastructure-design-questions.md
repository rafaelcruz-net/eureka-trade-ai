# Perguntas de Infrastructure Design — U1 `u1-bot-wallet`

Conversa: português (Brasil). Base: `construction/u1-bot-wallet/nfr-design/` (segurança, confiabilidade, escala, observabilidade, componentes lógicos), `construction/u1-bot-wallet/nfr-requirements/`, `construction/u1-bot-wallet/functional-design/`, `inception/contract-design/contract-summary.md`, `inception/domain-design/components.md`, as regras de `team.md` (Deployment: só o ambiente local por enquanto; AWS prevista na Sprint 7) e os achados da segunda revisão do NFR Design (etiquetas do KMS, reconciliação pela rede, consumo do RPC, recuperação, contrato interno).

## Q1. [Local] Como o ambiente local ganha o TxSigner?

A. O Compose ganha quatro serviços:
- `txsigner`, com imagem própria e usuário e esquema próprios no PostgreSQL existente;
- `txsigner-migrate`, com as migrações do TxSigner;
- `kms-sim`;
- `solana-validator` (`solana-test-validator`), só no arquivo de teste `infra/docker-compose.test.yml`.

O `txsigner` só é alcançável pela rede interna do Compose, sem porta publicada no computador. O flag REAL fica desligado por padrão em todos os serviços.
B. TxSigner fora do Compose
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Nuvem futura] Qual topologia na AWS e qual ferramenta de IaC?

A. Terraform, com o código em `infra/terraform/`, escrito agora e aplicado só quando a conta existir. A topologia na AWS:
- API, worker e TxSigner em ECS Fargate, em subredes privadas;
- o TxSigner com grupo de segurança próprio, que só aceita a API e o worker;
- dois RDS PostgreSQL (aplicação e TxSigner), com recuperação a um ponto no tempo;
- KMS, Secrets Manager e endpoints de VPC para os dois;
- saída por NAT só para o worker, que chama o RPC;
- o load balancer público só na frente da API.
B. AWS CDK
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Correção da revisão] Etiquetas da chave no KMS

A. A política do papel permite `CreateKey` e `TagResource` só com `aws:RequestTag/app = eureka-botwallet` e `aws:TagKeys` exatamente iguais a `app`, `mainWallet`, `creationRequestId` e `network`. A política de cada chave, passada no próprio `CreateKey`, nega `TagResource` e `UntagResource` a todos depois da criação. A prova técnica do B1 confirma que a chave nasce com as etiquetas e que alterá-las depois é recusado.
B. Sem etiquetas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Correção da revisão] Reconciliação depois de restaurar o banco da aplicação

A. Além dos SigningRecords do TxSigner, o worker consulta na rede as transações de cada carteira do bot desde o ponto restaurado (assinaturas por endereço) e recria todo saque que tenha saído para a carteira principal e não esteja no banco. Isso cobre o desastre completo, quando os dois bancos voltam ao mesmo ponto.
B. Só os SigningRecords
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Correção da revisão] Consumo do RPC e regra de FAILED

A. O consumo do RPC fica assim:
- o saldo de todas as carteiras é lido em lote (`getMultipleAccounts`, até 100 por chamada), uma vez por ciclo;
- o estado dos saques é consultado em lote a cada 2 s, só quando há saque pendente;
- o resto do orçamento fica para as entradas por endereço.

Regra de FAILED: a altura do bloco é comparada em commitment `finalized`. Se um provedor ficar fora por mais de 30 min, um alerta pede a decisão do fundador. Enquanto isso o saque continua AMBIGUOUS, e há uma opção de operação "um provedor só", registrada na auditoria.

Na CI, o caminho de FAILED por blockhash vencido é testado com um dublê da ChainWatcher. O validador local cobre só o caminho feliz de ponta a ponta.
B. Manter como está
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Correção da revisão] Detalhes da recuperação e do contrato interno

A. A recuperação e o contrato interno ficam assim:
- **Rede:** a etiqueta `network` precisa ser igual à rede do TxSigner; se for diferente, a chave é bloqueada e gera alerta.
- **Fim da recuperação:** se a API não enviar os rebaixamentos em 10 min, a recuperação continua e gera alerta. Os saques seguem permitidos, porque não dependem de teto.
- **Política reconstruída:** a API marca a carteira como "reautorizar teto e modo" e a tela pede um consentimento novo.
- **Contrato R1:** ganha `GET /internal/v1/wallets` (endereço, criação e situação, sem `kmsKeyRef`) e `GET /internal/v1/signatures?since=`.
- **Withdrawal:** ganha `attempts` e `nextAttemptAt`, com índice por (`state`, `nextAttemptAt`).

Tudo isso é registrado como adendo da U1 em `infrastructure-specification.md` e aplicado no Code Generation.
B. Deixar para depois
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [CI/CD] O que muda no pipeline?

A. O pipeline ganha:
- um job da CI que compila e testa o `apps/txsigner`, com piso de 80% e as regras de ArchUnit e do enforcer;
- imagem do TxSigner marcada com o SHA do commit;
- varreduras de segredos, dependências e SAST também no TxSigner.

Nenhum segredo de produção entra na CI. A implantação na nuvem só acontece quando a conta existir: primeiro o TxSigner, depois a API e o worker. O rollback é pela imagem anterior, e as migrações seguem expand-contract. O fundador aplica as mudanças no `ci.yml` e no `verify.sh` (spec novo da carteira e validador).
B. Pipeline separado para o TxSigner
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Monitoramento] Para onde vão os alertas?

A. No local, as métricas ficam em `/actuator/prometheus`, e as regras de alerta ficam versionadas em `infra/observability/`, validadas na CI. Na nuvem, as métricas vão para o Amazon Managed Service for Prometheus ou o CloudWatch, a escolher no Observability Setup, e os alertas vão por e-mail e notificação no celular do fundador. Os alertas críticos (recusa de saque no TxSigner, chave órfã, TxSigner fora do ar) também abrem um registro de incidente.
B. Só logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Compose local com txsigner, txsigner-migrate, kms-sim e solana-validator; TxSigner só na rede interna; REAL desligado [Q1].
- Terraform em infra/terraform, aplicado quando a conta existir; ECS Fargate privado, dois RDS com PITR, KMS e Secrets Manager por endpoints de VPC [Q2].
- Etiquetas só na criação da chave, imutáveis pela política da chave, confirmadas na prova técnica [Q3].
- Reconciliação após restauração também pela rede, por endereço [Q4].
- RPC em lote; FAILED em finalized; alerta e modo de um provedor só; dublê da ChainWatcher na CI [Q5].
- Rede conferida na etiqueta; recuperação com alerta aos 10 min; reautorização na tela; rotas internas e colunas novas como adendo [Q6].
- Job da CI para o TxSigner, imagem por SHA, TxSigner implantado antes; fundador edita ci.yml e verify.sh [Q7].
- Regras de alerta versionadas; e-mail e notificação no celular; incidentes para os críticos [Q8].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
