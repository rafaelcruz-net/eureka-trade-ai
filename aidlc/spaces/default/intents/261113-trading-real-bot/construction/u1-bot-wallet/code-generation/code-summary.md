# Resumo do código — U1 `u1-bot-wallet` (esqueleto, Bolt B1)

Conversa: português (Brasil). Fontes:
- `code-generation-plan.md`: Steps 1 a 27, todos marcados, e Steps 28 a 30 da Volta 1 do Build and Test (seção "Volta 1" abaixo).
- `unit-test-instructions.md`.
- O desenho da Unit em `construction/u1-bot-wallet/`.

A lista completa de caminhos está em `source-manifest.json`.

## Arquivos

| Área | Criados ou alterados |
|---|---|
| TxSigner (novo processo) | `apps/txsigner/`: Maven próprio, codec Solana mínimo, `ConsentVerifier`, `WithdrawalPolicy`, `KeyService`, `SigningService`, `RecoveryService`, rotas internas R1, migrações próprias, adaptador AWS KMS, verificação de produção, Dockerfile |
| Ledger REAL | `ledger/api` (`RealEntry`, `RealEntryKind`, `RealBalance`, `RealLedger`), `ledger/domain` (`RealBalances`, `RealEntryRules`, `RealLedgerRow`), `ledger/infra/JdbcRealLedger` |
| Auditoria | `audit/api/RealReferences`, validador e codec com campos REAL tipados, `shared/logging/LogMasking` |
| Identidade | `identity/api/RealIdentity`, `identity/domain/realterms/`, exceção do novo aceite em `TermsReacceptanceFilter` |
| BotWallet | `botwallet/domain` (cerca de 35 classes), `application`, `infra` (repositórios JDBC, cliente HTTP do TxSigner, ChainWatcher com dois provedores, trabalhos, métricas, semente de teste), `web` (rotas R2) |
| Banco | migração `0016-real-bot-wallet.sql`; `infra/postgres/bootstrap/20-txsigner.sql` |
| Web | `apps/web/src/app/real/carteira/` (tela mínima), tipos regenerados do OpenAPI |
| Ponta a ponta | `apps/web/e2e/real-bot-wallet/` (4 cenários) |
| Infraestrutura | `infra/kms-sim/`, `infra/docker-compose.real.yml`, `infra/terraform/`, `infra/observability/real-alerts.yml`, `.env.example` (só nomes) |
| Documentos | `docs/proofs/kms-ed25519.md`, `docs/proofs/jupiter-tx.md`, `docs/adr/ADR-0006-...`, `docs/runbooks/txsigner-recovery.md`, adendo da U1 em `inception/contract-design/contract-summary.md` |

## Decisões de implementação

- **REAL desligado por padrão** em todos os processos (`memetrade.real.enabled=false`, `txsigner.real.enabled=false`), com testes.
- **Provas técnicas:** passaram contra o KMS simulado. O SDK da AWS confirma a chave `ECC_NIST_EDWARDS25519` e a assinatura `ED25519_SHA_512` em modo RAW. A transação de swap sintética é decodificada e assinada sem o SDK Solana. O Bolt segue.
- **Sem SDK Solana no TxSigner,** nem mesmo no código de teste. As transações de teste são montadas byte a byte.
- **Etiqueta `mainWalletMac`:** a âncora da carteira principal é um MAC calculado pelo KMS, e nunca o endereço.

## Testes e cobertura

| Artefato | Testes | Cobertura de linhas | Piso de 80% |
|---|---|---|---|
| TxSigner (`verify` completo) | 162 (149 de unidade e 13 de integração), todos passando | 92,2% | cumprido; build verde |
| Monólito, execução completa | 1.424 de unidade passando; 972 de integração com 11 falhas (ver abaixo) | 93,3% (unidade e integração combinadas) | cumprido, mas o build completo não fechou |
| Monólito, só o módulo `botwallet` | — | 83,0% | — |
| Web (Jest completo) | 542 em 44 suítes, todos passando | 95,17% | cumprido |

Nos pontos marcados "testes antes" (carteira, saque, ledger REAL, política do TxSigner, consentimento, auditoria, termos), os testes foram escritos e rodados antes da implementação, e a falha inicial foi registrada.

`terraform fmt` e `terraform validate` passaram. `promtool check rules` passou com 12 regras. O teste do JSON das políticas do KMS (5 testes) passou.

### Falhas da execução completa do monólito

**Corrigidas** e verdes quando rodadas de novo:
- `CycleIT`, `WorkerProcessRoleContextIT`, `AnonymizationMigrationIT` e `TermsReacceptanceRoutesIT`. Eram regressões em testes existentes, causadas pelas rotas e pelos beans novos.

**Não confirmadas:**
- `RejectionRoutesIT`: 5 testes falharam no conjunto com 500 por `ServletException` e passam quando a classe roda sozinha.
- A execução completa foi encerrada pelo sistema por falta de memória no fim e não foi reiniciada.
- Essas falhas precisam ser conferidas no Build and Test e na CI.

## Testes escritos e não executados

| Teste | Motivo |
|---|---|
| Spec `e2e/real-bot-wallet` (4 cenários) | A máquina não tem a imagem do `solana-test-validator`; o ambiente com o overlay do REAL não foi levantado |
| Contêiner `kms-sim` e overlay `docker-compose.real.yml` | Nada foi construído nem subido; só `docker compose config` foi validado (11 serviços) |
| `AwsKmsEd25519ProofTest` (`@Tag("aws-proof")`) | Ainda não existe conta AWS |
| Checkov | Não está instalado na máquina |

## Desvios do plano e do desenho

1. **`kmsKeyRef`:** a API não guarda essa referência; ela fica só no TxSigner (NFR1.7). É desvio do `entities.md`.
2. **Ledger REAL em tabela própria** (`real_ledger_entry`), com o mesmo trigger de só inclusão e REVOKE de UPDATE e DELETE, em vez de relaxar o CHECK do `ledger_entry`. Motivo: as consultas PAPER existentes não filtram por modo.
3. **Termos v3:** a versão exigida é configurável (`memetrade.real.required-terms-version`). O texto da v3 entra no catálogo na U6, depois da revisão jurídica.
4. **REAL local num overlay próprio** (`infra/docker-compose.real.yml`), porque o `ComposeFilesTest` fixa os serviços dos arquivos atuais.
5. **TxSigner:**
   - o contexto do saque tem `feeLamports`;
   - motivos de recusa novos: `FEE_MISMATCH`, `ACCOUNT_NOT_ALLOWED`, `PROGRAM_NOT_ALLOWED`, `MALFORMED_TRANSACTION`, `WALLET_BLOCKED` e `RECOVERY_IN_PROGRESS`;
   - tabelas `policy_change` e `recovery_run`;
   - o SigningRecord guarda a transação assinada;
   - rota `POST /internal/v1/recovery/complete`.
6. **Auditoria do TxSigner:** vai para log JSON estruturado e para métrica, porque o TxSigner não tem acesso à auditoria do monólito.
7. **Esquemas OpenAPI com prefixo `Real`.** O `BotWalletView` ganhou `mainWalletAddress`, `operableLamports` e `policyResetRequired`.
8. **`ModeGuardFilter`:** as rotas `/api/v1/real/` ficam fora do filtro, porque o campo `mode` delas é o modo de operação.
9. **Métricas e alertas:**
   - `txsigner_orphan_keys` no alerta de chave órfã;
   - `txsigner_recovery_phase` no aviso de recuperação parada;
   - o contador de chaves bloqueadas não foi exportado.
10. **Testes existentes ajustados:** `GuestOpenApiContractTest`, `GuestOpenApiSlice`, `RouteRegistryTest` e os quatro de regressão acima.

## Volta 1 do Build and Test (2026-11-18)

Origem: "Loop-back 1" de `construction/build-and-test/test-results.md`, com as metas `Not Met` U1:NFR1.9, NFR5.1, NFR5.14 e NFR5.19 e a recusa do formato da tabela de rastreabilidade pelo sensor.

### Step 28 — Limites de uso por usuário (testes antes do código)

- Testes escritos antes do código:
  - `apps/api/src/test/java/com/memetrade/botwallet/web/BotWalletRateLimitsTest.java`: janelas e limites (10 por minuto, 3 por dia, 10 por hora), espera até a saída da requisição mais antiga e contadores separados por rota e por pessoa;
  - `apps/api/src/test/java/com/memetrade/botwallet/BotWalletRateLimitIT.java`: o 11º desafio no minuto, a 4ª ativação no dia e o 11º saque parcial na hora respondem `429 RATE_LIMITED` com `Retry-After`, em RFC 7807 (`application/problem+json`, `code`, `correlationId`); o saque total passa depois do limite dos parciais; a lista e a leitura de um saque nunca são limitadas (teste obrigatório "saque continua disponível").
- Código:
  - `botwallet/web/BotWalletRateLimits.java`: um `PersonRouteRateLimiter` parametrizado (janela e limite, como na U7) por rota; chave = pessoa do token e rota; chaves ociosas descartadas a cada pedido, como o `FiscalExportLimiter`;
  - `botwallet/web/BotWalletController.java`: o limite vale em `POST /consents/challenge`, `POST /bot-wallet` e `POST /bot-wallet/withdrawals` só quando o saque é parcial. O saque total (`all: true`) e todas as leituras ficam fora.
- O limite conta a tentativa, com sucesso ou não, depois da validação do corpo e antes do serviço. Assim, uma ativação com assinatura errada também conta entre as 3 do dia. No saque parcial, o Step 31 mudou o ponto da contagem para depois da conferência de idempotência.

### Step 29 — Métricas e alerta do KMS

- `botwallet/infra/RealMetrics.java`: medidor `real_bot_wallets_active`, que conta as carteiras em `ACTIVE` no banco, sem rótulo.
- No TxSigner:
  - `domain/kms/KmsThrottledException.java`: subclasse de `KmsUnavailableException`. Continua sendo falha técnica (503 e abre o circuito como antes);
  - `infra/aws/AwsKmsAdapter.java`: `ThrottlingException` e `LimitExceededException` viram `KmsThrottledException`;
  - `infra/aws/ResilientKmsPort.java`: `txsigner_kms_sign_seconds` (Timer só do `Sign`) e `txsigner_kms_errors_total{kind}`, com `THROTTLED` e `ERROR` registrados em zero. Não contam como erro do KMS a chamada barrada pelo circuito aberto, que não chegou ao KMS, nem a recusa do KMS (acesso, estado da chave).
- `infra/observability/real-alerts.yml`: regra `TxSignerKmsErrors`, `sum(increase(txsigner_kms_errors_total[5m])) > 3`, severidade alta, com as anotações `threshold` e `window_seconds`.
- `infra/terraform/modules/observability/main.tf`: alarme `tx-signer-kms-errors`, soma por métrica (`FILL(throttled, 0) + FILL(failed, 0)`) das duas dimensões `kind`, período de 300 s, limiar 3, namespace `Eureka/RealBotWallet`.
- Paridade: `RealOpsAlarmParityTest` ganhou o teste `theKmsErrorsAlertOfTheU1HasTheSameNameThresholdAndWindowAsItsAlarm`, que compara a regra e o alarme (nome, limiar, janela, as duas dimensões).
- Testes depois do código:
  - `RealMetricsTest` e `BotWalletRoutesIT.theActiveWalletsGaugeCountsTheActivation` (contra o banco);
  - `ResilientKmsPortTest`, com 4 testes novos;
  - `AwsKmsAdapterTest`, com 2 testes novos pelo protocolo JSON do KMS;
  - dublês `KmsSimulator.throttle()` e `KmsJsonProtocolServer.failWith(...)`.

### Step 30 — Rastreabilidade no formato do sensor

`traceability.json` foi reescrito com 116 linhas:
- 35 BR;
- 18 AC das histórias da Unit (US1.1, US1.2, US2.2, US5.1, US8.5);
- 63 NFRx.y dos arquivos de `nfr-requirements/`, inclusive observabilidade, acessibilidade e pilha.

Cada alvo `OK` é um único arquivo existente. As exceções:
- `N/A`: AC1.1.3 e AC1.1.4 (texto dos termos v3 e tela de aceite da U6);
- `Deferred`: NFR3.9 (teste de carga REAL informativo, ainda sem cenário), NFR4.1 (SLO medido com tráfego real) e NFR9.1 (custo na fatura da conta AWS).

BR4.6, antes `GAP`, aponta para `WithdrawalLimit.java`, que a U5 entregou. Não há linhas FR: com as histórias, a Code Generation traça os critérios de aceite, e os FR desta Unit ficam cobertos pelos AC. NFR1.9 aponta para os limites por usuário; o limite por carteira entre a API e o TxSigner continua nas pendências.

### Step 31 — Limite por carteira entre a API e o TxSigner e ajustes da revisão (testes antes do código)

Origem: achados R-10 (Major), R-11, R-12, R-13 e R-14 de `reviews/review-02.md` e escolha do responsável em 2026-11-18. O desenho (`nfr-design/security-design.md`) não define código de erro nem lugar para o limite por carteira; a escolha está abaixo.

- **Limite por carteira no TxSigner (segunda metade do NFR1.9):**
  - testes escritos antes do código:
    - `apps/txsigner/src/test/java/com/memetrade/txsigner/domain/SigningServiceWalletLimitTest.java` (5 testes): o 61º pedido no minuto da mesma carteira é recusado com `WALLET_RATE_LIMITED`, publicado no evento de auditoria `SIGNING_REFUSED` com o motivo, sem chamar o KMS e sem gravar `SigningRecord`; o mesmo pedido é assinado quando a janela libera uma vaga; a contagem de uma carteira não afeta a de outra; 120 saques para a carteira principal passam com o limite esgotado e não consomem a contagem (teste obrigatório "saque continua disponível"); a repetição de um pedido já decidido não é pedido novo;
    - `apps/txsigner/src/test/java/com/memetrade/txsigner/domain/WalletRequestLimiterTest.java` (4 testes): janela deslizante no relógio injetado, recusa que não conta, esquecimento das carteiras ociosas e parâmetros inválidos;
    - `apps/txsigner/src/test/java/com/memetrade/txsigner/web/WalletRateLimitIT.java` (1 teste): pela rota `/internal/v1/signatures`, o 61º pedido responde `429 WALLET_RATE_LIMITED` em RFC 7807, com `reason` e `correlationId`, e outra carteira continua com a própria contagem;
  - Red registrado:
    - primeiro, falha de compilação (`WalletRequestLimiter` e `WALLET_RATE_LIMITED` não existiam);
    - depois, com o limitador criado e ainda não ligado ao serviço, 4 falhas em `SigningServiceWalletLimitTest` (o 61º pedido era assinado);
  - código:
    - `domain/WalletRequestLimiter.java`: 60 pedidos por minuto por carteira, janela deslizante com `Clock`, em memória do processo;
    - `domain/SigningService.java`: o limite vale depois da consulta do `signingRequestId` (repetição não conta) e antes da recuperação, da política e do KMS, para todo tipo menos `WITHDRAWAL`. A recusa é transitória: vai para o evento de auditoria e para `txsigner_decisions_total{reason="WALLET_RATE_LIMITED"}`, mas não para o `SigningRecord`, para não queimar o `signingRequestId`;
    - `policy/PolicyRefusal.java`: motivo novo `WALLET_RATE_LIMITED`;
    - `web/InternalController.java`: `429` com `code` e `reason` `WALLET_RATE_LIMITED`;
    - `infra/TxSignerConfig.java`: o limitador do NFR1.9 ligado ao `SigningService`.
- **Mapeamento na API:** `botwallet/infra/HttpTxSignerClient.java` trata `429 WALLET_RATE_LIMITED` como recusa (`SignerRefusedException`), sem repetição e sem contar no circuito, que é único para todas as carteiras. Assim, a rajada de uma carteira não abre o circuito nem bloqueia os saques de todos. Outro `429` do TxSigner (por exemplo `KEY_RATE_LIMITED`) continua falha técnica. Na execução REAL, a recusa leva a ordem a `FAILED` com `SIGNING_REFUSED`, como as outras recusas. Nenhuma resposta pública mudou de formato, então o OpenAPI e o `check:api` não mudam. Testes em `HttpTxSignerClientTest` (2 novos, o primeiro escrito antes do código; Red: `SignerUnavailableException: TxSigner answered 429`).
- **Saque parcial depois da idempotência (R-11):**
  - teste de regressão escrito antes: `BotWalletRateLimitIT.aRetryWithTheSameKeyAtTheLimitReplaysTheStoredAnswerInsteadOf429`. Red: a repetição com a mesma `Idempotency-Key` no limite respondia `429 RATE_LIMITED`;
  - `aRateLimitedPartialWithdrawalKeepsNothingUnderItsKey`: um 429 não grava nada sob a chave;
  - código: `BotWalletController` passa o limite ao `BotWalletService.requestWithdrawal(..., admitNew)`, que só o executa dentro da ação de idempotência, para pedido novo e antes de qualquer efeito. Se o limite recusa, a chave é liberada. O saque total continua sem limite.
- **Rastreabilidade:** a linha NFR1.9 de `traceability.json` aponta para `BotWalletRateLimits.java` (por usuário) e `WalletRequestLimiter.java` (por carteira), um arquivo existente por alvo `OK`.

### Verificação desta volta

| Comando | Resultado |
|---|---|
| `apps/api` `verify -Dtest=BotWalletRateLimitsTest,BotWalletArchitectureTest -Dit.test=BotWalletRateLimitIT,BotWalletRoutesIT,RealOffByDefaultRoutesIT,TermsReacceptanceRoutesIT,WithdrawalLimitIT` (sem JaCoCo) | 12 de unidade e 24 de integração, verdes |
| `apps/api` `verify -Dtest=RealMetricsTest,RealOpsAlarmParityTest,BotWalletRateLimitsTest -Dit.test=BotWalletRoutesIT` (sem JaCoCo) | 13 de unidade e 14 de integração, verdes |
| `apps/txsigner` `verify` (módulo inteiro, com JaCoCo) | 221 de unidade e 16 de integração, verdes; piso de 80% atendido |
| `promtool check rules real-alerts.yml` (imagem `prom/prometheus:v3.5.0`) | SUCCESS, 13 regras |
| `terraform fmt -check -recursive` e `terraform validate` (imagem `hashicorp/terraform:1.9.8`) | sem diferença; configuração válida |
| `aidlc engine sensor-traceability --stage code-generation` (cópia em pasta temporária) | `pass: true`, 0 achados |
| Step 31: `apps/txsigner` `verify` (módulo inteiro, com JaCoCo) | 230 de unidade e 17 de integração, verdes (inclui `SigningServiceWalletLimitTest`, `WalletRequestLimiterTest` e `WalletRateLimitIT`); piso de 80% atendido |
| Step 31: `apps/api` `test -Dtest='com.memetrade.botwallet.**.*Test,com.memetrade.architecture.*Test'` e `failsafe -Dit.test=BotWalletRateLimitIT,BotWalletRoutesIT,WithdrawalLimitIT` (sem JaCoCo) | 274 de unidade e 23 de integração, verdes |
| Step 31: `aidlc engine sensor-traceability --stage code-generation` (cópia em pasta temporária, NFR1.9 com as duas metades) | `pass: true`, 0 achados |

O piso de 80% da API não foi medido nesta volta, porque o `verify` completo não foi executado (máquina com pouca memória). O código novo tem testes de unidade e de integração e não ganhou exclusão de cobertura.

## Pendências (não implementadas nesta Unit)

| Pendência | Referência |
|---|---|
| Antes da mainnet: caminho das métricas do TxSigner até o CloudWatch. Sem ele, o alarme `tx-signer-kms-errors` nunca dispara, porque `treat_missing_data = notBreaching` | NFR5 (KMS), review-02 R-12 |
| Antes da mainnet: os limitadores (por usuário na API e por carteira no TxSigner) ficam na memória do processo. A janela diária da ativação recomeça quando a API reinicia, e a janela por carteira recomeça quando o TxSigner reinicia. Serve para o beta com uma instância; com mais de uma instância, o limite precisa de um armazenamento comum | NFR1.9, review-02 R-14 |
| Órfãs "política sem carteira" e reconciliação depois de restaurar o banco da aplicação | NFR4.13, runbook passo 2 |
| Reenvio pendente do LOWER_CAP quando o TxSigner está fora (hoje a API aplica o teto menor e só registra aviso) | BR2.2 |
| Anonimização: limpar `bot_wallet_account.main_wallet_address` e `consent_challenge.message` | U5 |

## Texto pronto para o fundador aplicar

### `.github/workflows/ci.yml`: job novo

Usa as mesmas ações fixadas por SHA que o arquivo já usa.

```yaml
  txsigner:
    runs-on: ubuntu-24.04
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 # v4.7.1
        with: { distribution: temurin, java-version: '21', cache: maven }
      - name: Verify TxSigner
        run: sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify
      - name: Terraform fmt and validate
        run: |
          docker run --rm -v "$PWD/infra/terraform:/tf" -w /tf hashicorp/terraform:1.9.8 fmt -check -recursive
          docker run --rm -v "$PWD/infra/terraform:/tf" -w /tf --entrypoint sh hashicorp/terraform:1.9.8 -c "terraform init -backend=false -input=false && terraform validate"
      - name: Checkov
        run: docker run --rm -v "$PWD/infra/terraform:/tf" bridgecrew/checkov:3 -d /tf --quiet
      - name: Alert rules
        run: docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-alerts.yml
```

No passo "Compose config" do job `backend`, acrescentar:
- as variáveis `TXSIGNER_DB_OWNER_PASSWORD: ci-placeholder`, `TXSIGNER_DB_PASSWORD: ci-placeholder` e `TXSIGNER_SERVICE_TOKEN: ci-placeholder`;
- a linha `docker compose -f docker-compose.yml -f infra/docker-compose.test.yml -f infra/docker-compose.real.yml config --quiet`.

Antes de aplicar, é preciso fixar as imagens dos passos de Terraform, Checkov e `promtool` pelo digest.

### `scripts/verify.sh`

1. Depois das exportações de segredos que já existem, acrescentar:

   ```sh
   export TXSIGNER_DB_OWNER_PASSWORD="${TXSIGNER_DB_OWNER_PASSWORD:-$(random_secret)}"
   export TXSIGNER_DB_PASSWORD="${TXSIGNER_DB_PASSWORD:-$(random_secret)}"
   export TXSIGNER_SERVICE_TOKEN="${TXSIGNER_SERVICE_TOKEN:-$(openssl rand -hex 32)}"
   docker build -t "memetrade-txsigner:${IMAGE_TAG:-local}" apps/txsigner
   ```

2. Trocar `COMPOSE=(...)` por `COMPOSE=(docker compose -f docker-compose.yml -f infra/docker-compose.test.yml -f infra/docker-compose.real.yml)`.
3. Na espera pelos serviços saudáveis, acrescentar `txsigner` e `solana-validator`.
4. Na lista de specs do Playwright, acrescentar `e2e/real-bot-wallet`, com `SOLANA_RPC_URL="http://127.0.0.1:8899"` no ambiente da chamada.
5. Na varredura dos logs, acrescentar o serviço `txsigner` a `"${COMPOSE[@]}" logs`.
