# Instruções dos testes de segurança — intent `261121-paper-real-unico`

Os NFRs de segurança das quatro Units (isolamento entre contas, nenhum endereço nem assinatura em resposta ou log, REAL desligado por padrão, nenhum caminho novo que assine, ledger só de inserção, papel do banco mínimo, auditoria limitada) pedem este arquivo. A revisão de segurança do `aidlc-devsecops-agent` é o apoio desta etapa.

## 1. Portões automáticos

| Verificação | Onde roda | Regra de bloqueio | Comando |
|---|---|---|---|
| Segredos (Gitleaks, histórico e árvore) | workflow `security`, job `secrets`; hook `.githooks/pre-commit` | qualquer achado bloqueia | `gitleaks detect --redact` e `gitleaks dir . --redact` |
| SAST (CodeQL Java/Kotlin e JavaScript/TypeScript) | workflow `security`, jobs `sast` | Crítico ou Alto bloqueia; Médio avisa | CodeQL na CI |
| Dependências (OWASP Dependency-Check) | workflow `security`, job `dependencies` | `-DfailBuildOnCVSS=7` | `mvnw org.owasp:dependency-check-maven:check` |
| Dependências npm | workflow `security`, job `dependencies` | `--audit-level high` | `pnpm --dir apps/web audit --audit-level high`; o mesmo no `apps/admin` |
| Exceções | `security/exceptions.yml` | cada exceção com justificativa, validade de até 90 dias e aprovação do responsável; vencida volta a bloquear | lida pelo job `dependencies` |
| Integridade da CI | `WorkflowsTest` (backend) | ação de terceiros sem SHA fixo, ou `permissions` acima do mínimo, falha o build | `mvnw verify` |
| Proibições de produção | enforcer (`BannedDependencies`), ArchUnit | SDK Solana, APIs de chave ou mnemônico, ponto flutuante em dinheiro, rede fora dos adaptadores | `mvnw verify` |

## 2. Testes de segurança do intent (dentro do `mvnw verify` e do `verify.sh`)

| Tema | Testes |
|---|---|
| Tenant da autenticação, 404 sem dado, contagens só da pessoa | `RealPnlTenantIsolationIT`, `BotPnlLinesTenantIT`, `PnlCompareTenantIT`, `PnlBotCountsTenantIT`, `RealBotHoldingsTenantIT`, `AttributedCostTenantIT`, `OwnHoldingsSqlTenantTest` |
| Consulta fora do tenant só no caminho do 404 (exceção do `team.md`) | `BotDirectoryOwnedByAnotherTenantIT` e a regra ArchUnit que restringe `BotDirectory.ownedByAnotherTenant` |
| Tempo do 404 não revela bot de outra conta | `BotNotFoundTimingParityIT` |
| Login e novo aceite dos termos nas rotas novas | `BotPnlLinesRouteIT`, `PnlByModeMarketRouteIT`, `RealEligibilityRouteIT`, `RealAccountLimitsRouteIT`, `TermsReacceptanceRoutesIT` |
| Entrada malformada e parâmetros fechados (`tenantId` dá 400) | `PnlByModeMarketRouteIT`, `BotPnlLinesRouteIT` |
| Nenhum endereço, assinatura, token ou nonce em resposta, log ou métrica | `GuestOpenApiContractTest`, `U2LogCaptureTest`, `PnlViewsLogCaptureTest`, `OwnQuantityLogRedactionTest`, `OwnQuantityMetricsTest`; varredura de logs no fim do `verify.sh` (NFR7.3 do 261113) |
| Auditoria de erros de domínio com volume limitado | `ReconciliationAuditLimitIT`, `ReadRefusalAudit*`, `SlotConflictAuditIT` (assinatura abreviada com HMAC) |
| Só inserção e papel mínimo no banco | `RealEntrySlotAppendOnlyIT`, `RealPositionEpochAppendOnlyIT`, `RealEntrySlotGrantsIT`, `U2DatabaseRolesIT`, `Migration0026IT` (sem `GRANT`, `UPDATE` ou `DELETE`) |
| REAL desligado por padrão e nenhum caminho novo de assinatura | `RealOffByDefaultIT`, `RealOffByDefaultRoutesIT`, `OwnQuantitySellDefaultIT`, `RealNeverPaperIT`, `OwnQuantityOneTransactionIT` |
| Saque e saídas do dinheiro independentes das leituras novas | `OwnQuantityExitsIT`, `AttributionFailureGateIT`, `WithdrawalPolicyTest` (TxSigner) |
| Flag só por variável de ambiente | `OwnQuantitySellProperties` e os testes da flag; sem rota, actuator gravável nem tabela |

## 3. O que não é automático nesta etapa

- **DAST, varredura de imagens e SBOM por release:** candidatos antes do beta multiusuário (G1), ainda a definir (`team.md`, Deployment). Não há varredura dinâmica para este intent.
- **Checkov e `terraform validate`/`test`:** rodam à mão e não estão instalados nesta máquina (ver `build-and-test-summary.md`, `TGT-IAC-1` e `TGT-IAC-2`). Levar para a CI é pendência do responsável.

## 4. Como rodar localmente

```bash
gitleaks detect --redact                      # Gitleaks está instalado nesta máquina (winget)
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealPnlTenantIsolationIT,BotNotFoundTimingParityIT' \
  -Dtest='U2LogCaptureTest,OwnQuantityLogRedactionTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
corepack pnpm@10.18.3 --dir apps/web audit --audit-level high
```

A prova oficial é o workflow `security` do mesmo commit, que roda em todo push para `main` e em todo PR.
