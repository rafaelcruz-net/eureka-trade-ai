# Instruções de testes de segurança — intent `261113-trading-real-bot`

O REAL movimenta fundos: as regras de segurança das Units (NFR1 e NFR6 de cada uma), a política de conteúdo do TxSigner (ADR-004) e o modelo de ameaças da carteira do bot (ADR-005) são verificados por testes automatizados e por varreduras na CI.

## 1. Varreduras (workflow `security`)

| Varredura | Ferramenta | Bloqueio | Comando local |
|---|---|---|---|
| Segredos | Gitleaks (versão fixada em `.gitleaks-version`, checksum conferido) | qualquer achado | `gitleaks git --config .gitleaks.toml --redact .` e `gitleaks dir --config .gitleaks.toml --redact .` |
| SAST | CodeQL `java-kotlin` (build manual) e `javascript-typescript` | achado de nível `error` (Crítico/Alto) | — (só na CI) |
| Dependências Java | OWASP Dependency-Check 12.1.0 | CVSS ≥ 7 fora das exceções vigentes | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml org.owasp:dependency-check-maven:12.1.0:check -DfailBuildOnCVSS=7` |
| Dependências do web | `pnpm audit --audit-level high` | Alto ou Crítico | `corepack pnpm@10.18.3 --dir apps/web audit --audit-level high` |
| Dependências do administrativo | `pnpm audit --audit-level high` | Alto ou Crítico | `corepack pnpm@10.18.3 --dir apps/admin audit --audit-level high` |
| Exceções vencidas | `SecurityExceptionsTest` | exceção com mais de 90 dias | `sh apps/api/mvnw … -Dtest=SecurityExceptionsTest test` |

As exceções ficam em `security/exceptions.yml` (justificativa, `approvedBy`, `approvedAt`, `expires` em até 90 dias). Hoje só o Dependency-Check lê esse arquivo: o `pnpm audit` do workflow não tem mecanismo de exceção ligado a ele.

## 2. Testes automatizados de segurança

| Tema | Testes |
|---|---|
| Política de saque do TxSigner (forma exata, ataques) | `WithdrawalPolicyTest`, `ConsentVerifierTest`, `SigningServiceTest` |
| Política de swap (ADR-004) e mensagem sem tabela de endereços | `SwapPolicyTest`, `SigningServiceSwapTest`, `SolanaVectorConformanceTest`, `JupiterTxProofTest` |
| Trigger (portão BR0) | `SigningServiceTriggerTest` (recusa `POLICY_NOT_RECORDED`); os testes de ataque da `TriggerPolicy` só depois da gravação real |
| REAL desligado por padrão | `RealOffByDefaultRoutesIT`, `RealOffByDefaultIT`, `RealReleaseRouteOffIT` |
| Credencial de serviço e KMS | `InternalApiIT` (401/403), `SecretsManagerCredentialsTest`, `ProductionChecksTest`, `KmsPolicyDocumentsTest`, `KmsEd25519ProofTest` |
| Isolamento entre contas | `BotWalletRoutesIT`, `WorkerStoresIT`, `RealRoutesIT`, testes de rota da U5 e da U7 (outra conta → 404) |
| Novo aceite dos termos, com a exceção do saque | `TermsV3RealRoutesTest`, `TermsReacceptanceRoutesIT` |
| Saque só para a carteira principal | `WithdrawalPolicyTest`, e2e `real-bot-wallet` ("a withdrawal to another address is refused") |
| Máscara de log e auditoria | `LogMaskingTest`, `RealAuditValidatorTest`, `WalletHasherTest`, varredura de logs do `verify.sh` |
| Anonimização sem carteira em nenhuma tabela | `BotWalletAnonymizationIT`, `AnonymizationOpenExitsTest` |
| Fronteiras de rede, chave e SDK | `ArchitectureTest`, `TxSignerArchitectureTest`, `RealOpsArchitectureTest`, `RealExitsArchitectureTest`, enforcer `BannedDependencies` |
| Navegador sem transação, sem armazenamento do REAL | `real-lint.test.ts` (regras ESLint com fixtures que falham), `sign-transaction.test.ts`, testes de dependências permitidas |
| CORS e CSP | `GuestCorsPolicyTest`, `AdminCorsIT`, testes do `middleware` |

## 3. Testes que dependem de ambiente

| Meta | Dono | Observação |
|---|---|---|
| U1-NFR1.1 chave Ed25519 no AWS KMS real | `environment-provisioning` | `AwsKmsEd25519ProofTest` (`@Tag("aws-proof")`) roda à mão quando a conta AWS existir |
| U1-NFR1.2 menor privilégio no KMS real | `environment-provisioning` | a credencial da API deve ser recusada pelo KMS real; revisão da política antes da mainnet |
| U1-NFR1.12 chave HMAC no gerenciador de segredos | `environment-provisioning` | inspeção da configuração na nuvem |
| DAST, varredura de imagem e SBOM | a definir (`team.md`, controles do G1) | candidatos para antes do beta multiusuário |

## 4. Resultado de referência

Run `security` 44765240176 do commit `3497b2b`, tentativa 1: os quatro jobs verdes (`secrets`, as duas análises `sast` e `dependencies`). O `pnpm audit` do `apps/web` e do `apps/admin` não tem achado Alto nem Crítico, só 6 Moderados (aviso). O GHSA-fc5q-39xc-vjpf (`picomatch`) foi retirado pelo GitHub em 2026-11-18 e está coberto pela EX-014 e pelo `pnpm.auditConfig.ignoreGhsas`. Diagnóstico e opções em `test-results.md`.
