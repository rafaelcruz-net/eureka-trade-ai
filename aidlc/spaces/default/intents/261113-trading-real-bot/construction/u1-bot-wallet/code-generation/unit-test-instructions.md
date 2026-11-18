# Instruções de teste — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (Testing Contract: custom, comprehensive), `construction/u1-bot-wallet/functional-design/functional-spec.md` (cenários obrigatórios) e `construction/u1-bot-wallet/nfr-requirements/tech-stack-decisions.md`.

## Ferramentas

- **TxSigner (`apps/txsigner`):** JUnit 5, AssertJ, Testcontainers (PostgreSQL) e JaCoCo com piso de 80% de linhas, que falha o build. O KMS dos testes é o `KmsSimulator` em memória, um dublê de teste com a mesma interface do adaptador. O contêiner `kms-sim` é usado só no Compose.
- **Monólito (`apps/api`):** a configuração atual (JUnit 5, Testcontainers, JaCoCo com 80%, ArchUnit), com dublês para a ChainWatcher e o cliente do TxSigner nos testes de unidade, e um TxSigner falso via WireMock nos de integração.
- **Web (`apps/web`):** Jest com o limite de cobertura atual e Playwright para o spec de ponta a ponta.

## Comandos desta Unit

Só os testes da U1. Nunca rodar a suíte inteira por aqui.

| Parte | Comando |
|---|---|
| TxSigner, todos os testes da U1 | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` |
| TxSigner, só a política e o consentimento | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml test -Dtest='WithdrawalPolicyTest,ConsentVerifierTest,SolanaCodecTest'` |
| Monólito, testes da U1 | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.botwallet.**.*Test,com.memetrade.ledger.**.Real*Test,com.memetrade.audit.**.RealAudit*Test,com.memetrade.identity.**.ConsentVerification*Test,com.memetrade.identity.**.TermsV3*Test,com.memetrade.architecture.BotWalletArchitectureTest'` |
| Web, tela mínima | `pnpm --dir apps/web exec jest src/app/real` |
| Ponta a ponta da U1 (com o ambiente de teste no ar) | `pnpm --dir apps/web exec playwright test e2e/real-bot-wallet` |

O primeiro teste de regra (Step 5) só começa depois que os dois primeiros comandos rodam um teste vazio com sucesso (Step 2).

Não exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` ao rodar o Maven: os testes usam a chave de teste da configuração.

## Volume (Comprehensive: 10 a 15 testes por componente)

| Componente | Testes | Escritos antes do código |
|---|---|---|
| Codec Solana (Base58, mensagens) | 15 | sim |
| `ConsentVerifier` | 14 | sim |
| `WithdrawalPolicy` (ataques) | 18 | sim |
| `SigningService` e `KeyService` | 14 | sim |
| Rotas internas R1 | 12 | não |
| Recuperação e trabalhos do TxSigner | 12 | sim |
| Ledger REAL | 14 | sim |
| Auditoria tipada | 10 | sim |
| Termos v3 e consentimento (identidade) | 10 | sim |
| Domínio da BotWallet (ativação, preferências, saque) | 15 | sim |
| ChainWatcher | 10 | não |
| Worker (depósitos, saques, desafio, reconciliação) | 12 | regras sim; infraestrutura não |
| Rotas R2 | 15 | não |
| Tela mínima (Jest) | 10 | não |
| Ponta a ponta (Playwright) | 1 spec com 4 cenários | não |

Os testes obrigatórios por risco de `team.md` que esta Unit toca estão entre eles:
- REAL desligado por padrão;
- isolamento entre contas;
- separação REAL e PAPER;
- idempotência;
- ledger sem edição;
- saque só para a carteira principal (com kill switch, REAL desligado e sem liberação);
- política do TxSigner;
- exceção do novo aceite dos termos, testando as duas metades.

## Testes da Volta 1 do Build and Test (Steps 28 a 31)

| Step | Teste | O que verifica | Antes do código |
|---|---|---|---|
| 28 | `apps/api/.../botwallet/web/BotWalletRateLimitsTest` | janelas e limites por usuário (10 desafios por minuto, 3 ativações por dia, 10 saques parciais por hora), espera até a saída do pedido mais antigo, contadores separados por rota e por pessoa | sim |
| 28 | `apps/api/.../botwallet/BotWalletRateLimitIT` | 11º desafio, 4ª ativação e 11º saque parcial respondem `429 RATE_LIMITED` com `Retry-After` em RFC 7807; o saque total e as leituras nunca são limitados | sim |
| 29 | `apps/api/.../botwallet/infra/RealMetricsTest` e `BotWalletRoutesIT.theActiveWalletsGaugeCountsTheActivation` | medidor `real_bot_wallets_active` | não |
| 29 | `apps/api/.../botwallet/RealOpsAlarmParityTest.theKmsErrorsAlertOfTheU1HasTheSameNameThresholdAndWindowAsItsAlarm` | paridade da regra `TxSignerKmsErrors` com o alarme `tx-signer-kms-errors` (nome, limiar, janela, dimensões) | não |
| 29 | `apps/txsigner/.../infra/aws/ResilientKmsPortTest` e `AwsKmsAdapterTest` | `txsigner_kms_sign_seconds`, `txsigner_kms_errors_total{kind}` e a tradução de `ThrottlingException` | não |
| 30 | sensor `sensor-traceability` | formato de `traceability.json` (um arquivo existente por alvo `OK`) | não se aplica |
| 31 | `apps/txsigner/.../domain/SigningServiceWalletLimitTest` | o 61º pedido no minuto da mesma carteira é recusado com `WALLET_RATE_LIMITED`, registrado com o motivo e sem chamar o KMS; carteiras independentes; o saque para a carteira principal nunca é bloqueado nem consome o limite (teste obrigatório); repetição não conta | sim |
| 31 | `apps/txsigner/.../domain/WalletRequestLimiterTest` | janela deslizante no relógio injetado, esquecimento das carteiras ociosas | sim |
| 31 | `apps/txsigner/.../web/WalletRateLimitIT` | `429 WALLET_RATE_LIMITED` em RFC 7807 pela rota interna; outra carteira não é afetada | sim |
| 31 | `apps/api/.../botwallet/infra/HttpTxSignerClientTest` (2 testes novos) | `429 WALLET_RATE_LIMITED` é recusa, sem repetição e sem abrir o circuito; outro `429` continua falha técnica | sim |
| 31 | `BotWalletRateLimitIT.aRetryWithTheSameKeyAtTheLimitReplaysTheStoredAnswerInsteadOf429` e `aRateLimitedPartialWithdrawalKeepsNothingUnderItsKey` | o limite do saque parcial conta só pedido novo, depois da idempotência; um 429 não grava nada sob a chave | sim (regressão) |

Comandos (um Maven por vez):

| Parte | Comando |
|---|---|
| TxSigner, limites e métricas | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml test -Dtest='SigningServiceWalletLimitTest,WalletRequestLimiterTest,ResilientKmsPortTest,AwsKmsAdapterTest'` |
| TxSigner, rota interna com o limite | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml test-compile failsafe:integration-test failsafe:verify -Dit.test='WalletRateLimitIT'` |
| Monólito, unidade | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='BotWalletRateLimitsTest,HttpTxSignerClientTest,RealMetricsTest,RealOpsAlarmParityTest'` |
| Monólito, integração | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test-compile failsafe:integration-test failsafe:verify -Dit.test='BotWalletRateLimitIT,BotWalletRoutesIT,WithdrawalLimitIT'` |

## Cobertura

80% de linhas em cada artefato: TxSigner, monólito e web. As exclusões ficam só na configuração do JaCoCo e do Jest: ponto de entrada, classes de configuração e código gerado do OpenAPI. Nenhum piso é reduzido.

## Dublês e dados

- **Relógio:** injetável (`Clock`) em todo domínio. A quarentena de 24 h e as validades são testadas avançando o relógio.
- **Chaves de teste:** Ed25519 geradas só no código de teste (`src/test`), nunca em produção. A carteira principal de teste é gerada a cada execução.
- **Rede:** nenhum teste de unidade ou de integração usa rede real. A ChainWatcher tem um dublê com respostas gravadas, e o FAILED por blockhash vencido é testado nesse dublê. O validador local só entra no ponta a ponta.
- **Bancos:** PostgreSQL em Testcontainers com as mesmas migrações (Liquibase), e cada teste com os próprios dados, sem depender de ordem.
- **Fixtures:** nunca usam carteira, mint ou dado pessoal reais. A transação da Jupiter da prova 2 é uma fixture gravada.
