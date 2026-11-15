# Resumo do código — U1 `u1-bot-wallet` (esqueleto, Bolt B1)

Conversa: português (Brasil). Fontes:
- `code-generation-plan.md`: Steps 1 a 27, todos marcados.
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

## Pendências (não implementadas nesta Unit)

| Pendência | Referência |
|---|---|
| Sacável limitado pelo saldo da rede com a pausa DIVERGENCE | BR4.6, GAP na rastreabilidade |
| Limites de uso: desafios, ativações e saques por hora | NFR1.9 |
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
