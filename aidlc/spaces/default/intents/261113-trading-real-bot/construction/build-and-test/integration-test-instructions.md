# Instruções de testes de integração — intent `261113-trading-real-bot`

Estratégia: `Comprehensive`. Os testes de integração cobrem a interação entre as Units do REAL e o tratamento de dependências externas (RPC Solana, Jupiter, CoinGecko, KMS, TxSigner, GitHub Actions), sempre com dublês ou serviços locais.

## 1. Ferramentas e configuração

- **Backend:** JUnit 5, AssertJ, Spring Boot Test, Testcontainers (PostgreSQL 17, as mesmas migrações Liquibase da aplicação), WireMock para fornecedores HTTP, `FakeTxSigner`, `RpcStub`, `ScriptedTriggerPort`. Classes `*IT` rodam no Failsafe (`integration-test`), com JaCoCo em `jacoco-it.exec` e relatório mesclado.
- **TxSigner:** `AbstractTxSignerIT` com PostgreSQL próprio e `kms-sim` (`KmsJsonProtocolServer`); `InternalApiIT`, `RealOffByDefaultIT`, `TriggerSignaturesIT`.
- **Relógio:** sempre injetável (`Clock` fixo); nenhum `now()` no domínio.
- **Isolamento:** cada teste cria os seus dados; nada depende da ordem de execução nem de estado compartilhado.

## 2. Como rodar

```bash
# Todos (Surefire + Failsafe), como na CI
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify
sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify

# Só os de integração de um módulo (exemplo)
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify -Dit.test='com.memetrade.botwallet.**.*IT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

Nesta máquina o `verify` completo morre por memória; vale o run `ci` do mesmo commit (ver `build-instructions.md`, seção 6).

## 3. Fronteiras entre Units cobertas

| Fronteira | Units | Testes de integração principais |
|---|---|---|
| API → TxSigner (credencial, idempotência, recusa) | U1, U4, U7 | `InternalApiIT`, `HttpTxSignerClientTest`, `TriggerSignaturesIT`, `RealOffByDefaultIT` |
| BotWallet → Ledger REAL (append-only, saldos) | U1, U4, U5 | `RealLedgerIT`, `RealLedgerPositionsIT`, `LedgerPrivilegesIT`, `RealSettlementIT` |
| Barreira REAL → BotWallet/Ledger/Mercado (M1, M2, M4) | U3 | `WalletLockIT`, `RealRiskPurgeJobIT`, `RealRiskMeasurementIT` e os ITs dos cenários S1–S17 |
| Execução REAL → Jupiter, RPC, TxSigner | U4 | `UpstreamFailuresIT`, `RealRoutesIT`, `RealExecutionPurgeIT`, `RealExecutionMeasureIT` |
| Operações → GitHub CI, RPC, kill switch, restauração | U5 | `ReleaseAndGatesIT`, `ReconciliationIT`, `KillSwitchIT`, `RestoreIT`, `WithdrawalLimitIT`, `CapAndCommandsIT`, `RealOpsGaugesIT` |
| Rotas do REAL usadas pelas telas | U6 | `RealReleaseRouteIT`, `RealReleaseRouteOffIT`, `RealStateQueryIT`, `AdminCorsIT`, `TermsReacceptanceRoutesIT` |
| Saídas condicionais e exportação fiscal | U7 | `ConditionalCreationIT`, `ConditionalCancelJobIT`, `FillPostingIT`, `FiscalExportIT`, `RealExitsPurgeJobIT`, `RealExitsMigrationIT` |
| Dados de mercado reais | U2 | `CoinGeckoApiStartupIT`, `CoinGeckoWorkerIT`, `CoinGeckoBudgetIT` |
| Anonimização com dados do REAL | U1, U5, U7 | `BotWalletAnonymizationIT`, `AnonymizationOpenExitsTest`, `WalletAuditIT` |

## 4. Dependências externas

| Dependência | Dublê | Casos cobertos |
|---|---|---|
| RPC Solana | `RpcStub`, validador local no e2e | dois provedores, troca, 429, tempo esgotado, altura `finalized` |
| Jupiter (swap) | WireMock fixado por digest e respostas gravadas | rota recusada (`NO_ROUTE`), 5xx/429/tempo esgotado contam como indisponível |
| Jupiter (Trigger) | `ScriptedTriggerPort` | até a gravação real (portão BR0) o TxSigner recusa com `POLICY_NOT_RECORDED` |
| CoinGecko | WireMock com respostas sintéticas | corpo grande, redirecionamento, JSON malformado, orçamento mensal |
| KMS | `kms-sim` | Ed25519 de 64 bytes, etiquetas imutáveis, MAC, throttling simulado |
| GitHub Actions (portões) | `ConfiguredCiStatus`, WireMock | CI vermelha, sem resposta, token mascarado |

## 5. Metas de cobertura

- Piso de 80% de linhas, medido no relatório mesclado (unidade + integração) da API e no TxSigner; regra `check` do JaCoCo falha o build abaixo disso. O piso nunca é reduzido.
- Testes obrigatórios por risco (`team.md`) presentes e verdes em toda mudança.

## 6. Resultado de referência

Run `ci` 44765239890 (commit `3497b2b`): Failsafe da API com 1.394 testes e TxSigner com 17 testes de integração, 0 falhas, 0 pulados. Detalhes em `test-results.md`.
