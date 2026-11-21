# Rastreabilidade entre Units — portão final de cobertura (Step 10)

Comando: `aidlc engine sensor-traceability --stage build-and-test`, executado da raiz do repositório em 2026-11-20 (commit `3497b2b`), código de saída 0, saída JSON de uma linha. Neste modo o sensor lê o registro inteiro e não aceita `--output-path`. Reexecução depois da volta 2: a tabela `traceability.json` da U6 passou a marcar o AC1.1.3 como `OK` (alvo `apps/api/src/main/resources/terms/v3/terms.txt`), e as sete tabelas passaram de novo no sensor da Code Generation (`pass: true`, 0 achados).

## Veredito

**FAIL** (`"pass": false`). 6 de 134 IDs sem cobertura, todos NFR de nível de requisito com partes que a Code Generation adiou com motivo e dono (portão BR0 da U7, `performance-validation`, `observability-setup`, `environment-provisioning`). Nenhum ID ficou sem cobertura por falta de código ou de formato.

| Medida | Valor |
|---|---|
| IDs avaliados (FR e NFR de `requirements.md`, AC de `stories.md`) | 134 |
| Cobertos | 128 (FR 48/48, NFR 4/10, AC 76/76) |
| Não cobertos | 6: NFR1, NFR3, NFR4, NFR5, NFR8, NFR9 |
| No run anterior (2026-11-20, depois da volta 1) | 125 de 134; não cobertos FR9.1, FR9.2, NFR1, NFR3, NFR4, NFR5, NFR8, NFR9 e AC1.1.3 |
| No primeiro run (2026-11-18) | 0 de 134 cobertos, por formato das tabelas |

Todo ID não coberto é um achado do Build and Test e precisa aparecer no portão de aprovação.

**AC1.1.3 agora está cumprido.** O sensor o dá como construído na U6 (`apps/api/src/main/resources/terms/v3/terms.txt`), e a matriz o julga `Met`. Os termos v3 dizem que a pessoa pode perder todo o valor depositado na carteira do bot (seção "Riscos do REAL"), têm a seção "Responsabilidade compartilhada na carteira do bot" (a nossa parte e a sua parte) e dizem que não há promessa de lucro. Nenhum arquivo da v3 diz que tudo é simulado ou que não movimentamos fundos. A v3 vale desde 2026-11-20 (`effectiveAt=2026-11-20T00:00:00Z`, `current=true`), com um registro genérico de revisão jurídica interna (`legalReviewedAt=2026-11-20`, `legalReviewedBy=Assessoria jurídica do projeto`), por decisão do responsável. A v2 ficou `current=false` e nunca ativa. Testes no CI 44765239890, job `backend`: `TermsV3ContentTest` (13), `TermsV3ReacceptanceTest` (14), `TermsV3RealRoutesTest` (13), `RetentionConformanceTest` (16), `RetentionCatalogTest` (11), todos verdes. Com isso, FR9.1 e FR9.2 também passaram a cobertos.

**Diferença entre o sensor e a matriz.** O sensor conta como construídos AC3.4.1, AC3.4.2 e AC8.1.3, porque as tabelas da U5 e da U7 apontam para arquivos que existem. A matriz os julga `Unverified`: a parte da Jupiter real (ordem condicional, preenchimento na rede real e cancelamento assinado pelo TxSigner) espera o portão BR0. Por isso FR8.1 a FR8.3 e FR13.1 estão cobertos no sensor, mas dependem desses AC na matriz.

## IDs não cobertos

| ID | Motivo (partes não construídas segundo o sensor) | Dono |
|---|---|---|
| NFR1 | U7 NFR1.1 e NFR1.3 `Deferred` pelo portão BR0 — não construídas: u7-exits-and-fiscal: NFR1.1; u7-exits-and-fiscal: NFR1.3 | code-generation (U7) depois do `record.sh --trigger` |
| NFR3 | U1 NFR3.9 (teste de carga REAL informativo) e U2 NFR3.4 (sem piora na leitura do scanner) `Deferred` — não construídas: u1-bot-wallet: NFR3.9; u2-real-market-data: NFR3.4 | performance-validation |
| NFR4 | U1 NFR4.1 (SLO de 99,5% medido com tráfego real em 30 dias) `Deferred` — não construídas: u1-bot-wallet: NFR4.1 | performance-validation |
| NFR5 | U3 NFR5.11 e U4 NFR5.13 (alarmes de p95 na nuvem) `Deferred` — não construídas: u3-real-risk: NFR5.11; u4-real-execution: NFR5.13 | observability-setup |
| NFR8 | U7 NFR8.2 `Deferred` pelo portão BR0 — não construídas: u7-exits-and-fiscal: NFR8.2 | code-generation (U7) depois do `record.sh --trigger` |
| NFR9 | U1 NFR9.1 (custo na fatura da conta AWS) e U2 NFR9.1 (preço do plano da CoinGecko) `Deferred` — não construídas: u1-bot-wallet: NFR9.1; u2-real-market-data: NFR9.1 | environment-provisioning |

## Cobertura por ID

Colunas: o que o sensor diz; as Units donas (das tabelas da Code Generation); os arquivos que constroem o ID (para AC) ou as partes pelas quais ele é coberto (para FR, os AC; para NFR, a quantidade de NFRx.y por Unit); as partes não construídas ou que não se aplicam; e, para AC, o veredito da matriz.

| ID | Coberto | Units donas | Arquivos ou partes | Não construído / não se aplica | Matriz |
|---|---|---|---|---|---|
| FR1.1 | sim | via AC | AC6.3.1, AC6.3.2, AC6.3.3 |  | — |
| FR1.2 | sim | via AC | AC6.3.1, AC6.3.2, AC6.3.3 |  | — |
| FR1.3 | sim | via AC | AC6.3.1, AC6.3.2, AC6.3.3 |  | — |
| FR1.4 | sim | via AC | AC6.3.1, AC6.3.2, AC6.3.3 |  | — |
| FR2.1 | sim | via AC | AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5 |  | — |
| FR2.2 | sim | via AC | AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5 |  | — |
| FR2.3 | sim | via AC | AC2.1.1, AC2.1.2, AC2.1.3, AC2.1.4, AC7.2.1, AC7.2.2 |  | — |
| FR2.4 | sim | via AC | AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5 |  | — |
| FR3.1 | sim | via AC | AC2.2.1, AC2.2.2, AC2.2.3 |  | — |
| FR3.2 | sim | via AC | AC2.2.1, AC2.2.2, AC2.2.3 |  | — |
| FR3.3 | sim | via AC | AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4 |  | — |
| FR3.4 | sim | via AC | AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4, AC5.2.1, AC5.2.2 |  | — |
| FR3.5 | sim | via AC | AC2.2.1, AC2.2.2, AC2.2.3, AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4 |  | — |
| FR4.1 | sim | via AC | AC3.1.1, AC3.1.2, AC3.1.3, AC3.1.4 |  | — |
| FR4.2 | sim | via AC | AC3.1.1, AC3.1.2, AC3.1.3, AC3.1.4 |  | — |
| FR4.3 | sim | via AC | AC3.2.1, AC3.2.2, AC3.2.3, AC3.2.4 |  | — |
| FR5.1 | sim | via AC | AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 |  | — |
| FR5.2 | sim | via AC | AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5, AC6.3.1, AC6.3.2, AC6.3.3 |  | — |
| FR5.3 | sim | via AC | AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 |  | — |
| FR5.4 | sim | via AC | AC3.2.1, AC3.2.2, AC3.2.3, AC3.2.4, AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 |  | — |
| FR5.5 | sim | via AC | AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 |  | — |
| FR5.6 | sim | via AC | AC8.3.1, AC8.3.2, AC8.3.3, AC8.3.4 |  | — |
| FR6.1 | sim | via AC | AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, AC6.1.5, AC6.1.6 |  | — |
| FR6.2 | sim | via AC | AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, AC6.1.5, AC6.1.6 |  | — |
| FR6.3 | sim | via AC | AC6.2.1, AC6.2.2, AC6.2.3 |  | — |
| FR6.4 | sim | via AC | AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, AC6.1.5, AC6.1.6 |  | — |
| FR7.1 | sim | via AC | AC4.2.1, AC4.2.2 |  | — |
| FR7.2 | sim | via AC | AC8.2.1, AC8.2.2 |  | — |
| FR7.3 | sim | via AC | AC8.2.1, AC8.2.2 |  | — |
| FR7.4 | sim | via AC | AC4.2.1, AC4.2.2 |  | — |
| FR8.1 | sim | via AC | AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4 |  | — |
| FR8.2 | sim | via AC | AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4 |  | — |
| FR8.3 | sim | via AC | AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4 |  | — |
| FR9.1 | sim | via AC | AC1.1.1, AC1.1.2, AC1.1.3, AC1.1.4 |  | — |
| FR9.2 | sim | via AC | AC1.1.1, AC1.1.2, AC1.1.3, AC1.1.4 |  | — |
| FR10.1 | sim | via AC | AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 |  | — |
| FR10.2 | sim | via AC | AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 |  | — |
| FR10.3 | sim | via AC | AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 |  | — |
| FR10.4 | sim | via AC | AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 |  | — |
| FR11.1 | sim | via AC | AC8.5.1, AC8.5.2 |  | — |
| FR11.2 | sim | via AC | AC8.5.1, AC8.5.2 |  | — |
| FR11.3 | sim | via AC | AC8.5.1, AC8.5.2 |  | — |
| FR12.1 | sim | via AC | AC8.4.1, AC8.4.2 |  | — |
| FR12.2 | sim | via AC | AC8.4.1, AC8.4.2 |  | — |
| FR13.1 | sim | via AC | AC3.4.1, AC3.4.2 |  | — |
| FR14.1 | sim | via AC | AC4.3.1 |  | — |
| FR15.1 | sim | via AC | AC7.3.1 |  | — |
| FR15.2 | sim | via AC | AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4, AC7.2.1, AC7.2.2 |  | — |
| NFR1 | **não** | u1-bot-wallet, u2-real-market-data, u4-real-execution, u5-real-operations, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 12 NFRx.y; u2-real-market-data: 6 NFRx.y; u4-real-execution: 5 NFRx.y; u5-real-operations: 3 NFRx.y; u6-real-modes-ui: 11 NFRx.y; u7-exits-and-fiscal: 9 NFRx.y | não construído: u7-exits-and-fiscal: NFR1.1; u7-exits-and-fiscal: NFR1.3 | — |
| NFR2 | sim | u1-bot-wallet, u2-real-market-data, u3-real-risk, u4-real-execution, u5-real-operations, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 1 NFRx.y; u2-real-market-data: 1 NFRx.y; u3-real-risk: 1 NFRx.y; u4-real-execution: 1 NFRx.y; u5-real-operations: 1 NFRx.y; u6-real-modes-ui: 1 NFRx.y; u7-exits-and-fiscal: 1 NFRx.y | N/A: NFR2.1: restrição de ausência: a U2 não toca em ledger nem em saldo, e nenhuma classe de com.memetrade.marketdata referencia ledger ou botwallet; a verificação pedida é a revisão de código, não há arquivo que a implemente | — |
| NFR3 | **não** | u1-bot-wallet, u2-real-market-data, u3-real-risk, u4-real-execution, u5-real-operations, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 10 NFRx.y; u2-real-market-data: 7 NFRx.y; u3-real-risk: 7 NFRx.y; u4-real-execution: 9 NFRx.y; u5-real-operations: 9 NFRx.y; u6-real-modes-ui: 6 NFRx.y; u7-exits-and-fiscal: 9 NFRx.y | não construído: u1-bot-wallet: NFR3.9; u2-real-market-data: NFR3.4 | — |
| NFR4 | **não** | u1-bot-wallet, u2-real-market-data, u3-real-risk, u4-real-execution, u5-real-operations, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 13 NFRx.y; u2-real-market-data: 6 NFRx.y; u3-real-risk: 8 NFRx.y; u4-real-execution: 7 NFRx.y; u5-real-operations: 6 NFRx.y; u6-real-modes-ui: 7 NFRx.y; u7-exits-and-fiscal: 12 NFRx.y | não construído: u1-bot-wallet: NFR4.1 | — |
| NFR5 | **não** | u1-bot-wallet, u2-real-market-data, u3-real-risk, u4-real-execution, u5-real-operations, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 19 NFRx.y; u2-real-market-data: 10 NFRx.y; u3-real-risk: 12 NFRx.y; u4-real-execution: 14 NFRx.y; u5-real-operations: 13 NFRx.y; u6-real-modes-ui: 2 NFRx.y; u7-exits-and-fiscal: 11 NFRx.y | não construído: u3-real-risk: NFR5.11; u4-real-execution: NFR5.13 | — |
| NFR6 | sim | u1-bot-wallet, u2-real-market-data, u3-real-risk, u4-real-execution, u5-real-operations, u6-real-modes-ui | u1-bot-wallet: 1 NFRx.y; u2-real-market-data: 1 NFRx.y; u3-real-risk: 3 NFRx.y; u4-real-execution: 2 NFRx.y; u5-real-operations: 3 NFRx.y; u6-real-modes-ui: 6 NFRx.y |  | — |
| NFR7 | sim | u1-bot-wallet, u2-real-market-data, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 1 NFRx.y; u2-real-market-data: 1 NFRx.y; u6-real-modes-ui: 6 NFRx.y; u7-exits-and-fiscal: 1 NFRx.y |  | — |
| NFR8 | **não** | u1-bot-wallet, u2-real-market-data, u3-real-risk, u4-real-execution, u5-real-operations, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 4 NFRx.y; u2-real-market-data: 2 NFRx.y; u3-real-risk: 9 NFRx.y; u4-real-execution: 10 NFRx.y; u5-real-operations: 8 NFRx.y; u6-real-modes-ui: 7 NFRx.y; u7-exits-and-fiscal: 6 NFRx.y | não construído: u7-exits-and-fiscal: NFR8.2 | — |
| NFR9 | **não** | u1-bot-wallet, u2-real-market-data | u1-bot-wallet: 1 NFRx.y; u2-real-market-data: 1 NFRx.y | não construído: u1-bot-wallet: NFR9.1; u2-real-market-data: NFR9.1 | — |
| NFR10 | sim | u1-bot-wallet, u2-real-market-data, u3-real-risk, u4-real-execution, u5-real-operations, u6-real-modes-ui, u7-exits-and-fiscal | u1-bot-wallet: 1 NFRx.y; u2-real-market-data: 1 NFRx.y; u3-real-risk: 1 NFRx.y; u4-real-execution: 1 NFRx.y; u5-real-operations: 1 NFRx.y; u6-real-modes-ui: 1 NFRx.y; u7-exits-and-fiscal: 1 NFRx.y |  | — |
| AC1.1.1 | sim | u1-bot-wallet, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/identity/domain/realterms/RealIdentityService.java`<br>`apps/web/src/features/real/screens/ActivationScreen.tsx` |  | Met |
| AC1.1.2 | sim | u1-bot-wallet, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/botwallet/domain/ActivationGate.java`<br>`apps/web/src/features/real/screens/ActivationScreen.tsx` |  | Met |
| AC1.1.3 | sim | u1-bot-wallet, u6-real-modes-ui | `apps/api/src/main/resources/terms/v3/terms.txt` | N/A na U1: o texto dos termos v3 é da U6; a U1 entrega a versão exigida e a recusa no servidor | Met |
| AC1.1.4 | sim | u6-real-modes-ui | `apps/web/src/features/real/screens/ActivationScreen.tsx` |  | Met |
| AC1.2.1 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/application/BotWalletService.java` |  | Met |
| AC1.2.2 | sim | u1-bot-wallet | `apps/txsigner/src/main/java/com/memetrade/txsigner/infra/aws/AwsKmsAdapter.java` |  | Met |
| AC1.2.3 | sim | u1-bot-wallet | `apps/txsigner/src/main/java/com/memetrade/txsigner/policy/ConsentVerifier.java` |  | Met |
| AC1.2.4 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/domain/ActivationGate.java` |  | Met |
| AC1.2.5 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/domain/RealReleases.java` |  | Met |
| AC2.1.1 | sim | u3-real-risk |  | N/A: o campo do teto no perfil é da U6 (unit-of-work-story-map.md: US2.1, regra na U3, campo do teto na U6); a U3 só aplica o teto salvo | Met |
| AC2.1.2 | sim | u3-real-risk |  | N/A: a recusa de um teto acima do máximo de 10 SOL acontece ao salvar o campo, na U6 (unit-of-work-story-map.md: US2.1, campo do teto na U6); a U3 só aplica o teto salvo | Met |
| AC2.1.3 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC2.1.4 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC2.2.1 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/domain/DepositScanner.java` |  | Met |
| AC2.2.2 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/ledger/domain/RealBalances.java` |  | Met |
| AC2.2.3 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/ledger/domain/RealBalances.java` |  | Met |
| AC3.1.1 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/resources/db/changelog/changes/0016-real-bot-wallet.sql`<br>`apps/web/src/features/real/components/ModeSelector.tsx` |  | Met |
| AC3.1.2 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/infra/RealModeService.java`<br>`apps/web/src/features/real/screens/ModeScreen.tsx` |  | Met |
| AC3.1.3 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/infra/RealModeService.java`<br>`apps/web/src/features/real/screens/ModeScreen.tsx` |  | Met |
| AC3.1.4 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/infra/RealModeService.java`<br>`apps/web/src/features/real/screens/ModeScreen.tsx` |  | Met |
| AC3.2.1 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/web/RealOrderDtos.java`<br>`apps/web/src/features/real/components/SigningDialog.tsx` |  | Met |
| AC3.2.2 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/infra/SigningSessionService.java`<br>`apps/web/src/features/real/sign-transaction.ts` |  | Met |
| AC3.2.3 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/infra/SigningDeadlineJob.java`<br>`apps/web/src/lib/time/countdown.ts` |  | Met |
| AC3.2.4 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/infra/SigningSessionService.java`<br>`apps/web/src/features/real/components/SigningDialog.tsx` |  | Met |
| AC3.3.1 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/infra/OrderDriver.java` |  | Met |
| AC3.3.2 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/domain/RealOrderStateMachine.java` |  | Met |
| AC3.3.3 | sim | u4-real-execution | `apps/api/src/main/resources/db/changelog/changes/0019-real-execution.sql` |  | Met |
| AC3.3.4 | sim | u4-real-execution | `apps/api/src/test/java/com/memetrade/realexecution/infra/OrderDriverIT.java` |  | Met |
| AC3.3.5 | sim | u4-real-execution | `apps/api/src/test/java/com/memetrade/realexecution/infra/ConfirmationAndAmbiguousIT.java` |  | Met |
| AC3.4.1 | sim | u7-exits-and-fiscal | `apps/api/src/main/java/com/memetrade/realexits/application/ConditionalService.java` |  | Unverified |
| AC3.4.2 | sim | u7-exits-and-fiscal | `apps/api/src/main/java/com/memetrade/realexits/application/FillPosting.java` |  | Unverified |
| AC4.1.1 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/web/RealOrderDtos.java`<br>`apps/web/src/features/real/screens/OrdersScreen.tsx` |  | Met |
| AC4.1.2 | sim | u4-real-execution, u6-real-modes-ui | `apps/api/src/main/java/com/memetrade/realexecution/web/RealOrderDtos.java`<br>`apps/web/src/features/real/components/OrderStateBadge.tsx` |  | Met |
| AC4.1.3 | sim | u6-real-modes-ui | `apps/web/src/features/real/hooks/useLoad.ts` |  | Met |
| AC4.2.1 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/pnl/real/RealPnlCalculator.java` |  | Met |
| AC4.2.2 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/infra/OrderSettlement.java` |  | Met |
| AC4.3.1 | sim | u7-exits-and-fiscal | `apps/api/src/main/java/com/memetrade/realexits/fiscal/FiscalCsvWriter.java` |  | Met |
| AC5.1.1 | sim | u1-bot-wallet | `apps/txsigner/src/main/java/com/memetrade/txsigner/policy/WithdrawalPolicy.java` |  | Met |
| AC5.1.2 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/application/BotWalletService.java` |  | Met |
| AC5.1.3 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/domain/WithdrawalLimits.java` |  | Met |
| AC5.1.4 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/domain/WithdrawalCalculator.java` |  | Met |
| AC5.2.1 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/infra/LiquidationJob.java` |  | Met |
| AC5.2.2 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/infra/LiquidationJob.java` |  | Met |
| AC6.1.1 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC6.1.2 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC6.1.3 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/infra/JdbcRealRiskStore.java` |  | Met |
| AC6.1.4 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/infra/WalletLock.java` |  | Met |
| AC6.1.5 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/infra/JdbcRealRiskStore.java` |  | Met |
| AC6.1.6 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/infra/RealGateInputsReader.java` |  | Met |
| AC6.2.1 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC6.2.2 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC6.2.3 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/infra/JdbcRealRiskStore.java` |  | Met |
| AC6.3.1 | sim | u2-real-market-data | `apps/api/src/main/java/com/memetrade/marketdata/infra/coingecko/CoinGeckoAdapter.java`<br>`apps/web/src/features/scanner/DataSourceNotice.tsx` |  | Met |
| AC6.3.2 | sim | u2-real-market-data, u3-real-risk | `apps/api/src/main/java/com/memetrade/marketdata/infra/MarketSourceGuard.java`<br>`apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC6.3.3 | sim | u3-real-risk | `apps/api/src/main/java/com/memetrade/riskcontrol/real/domain/RealRiskPolicy.java` |  | Met |
| AC7.1.1 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/domain/RealReleases.java` |  | Met |
| AC7.1.2 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/application/GatesService.java` |  | Met |
| AC7.1.3 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/domain/ReleaseRules.java` |  | Met |
| AC7.1.4 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/infra/RealOpsStartupChecks.java` |  | Met |
| AC7.2.1 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/application/CapService.java` |  | Met |
| AC7.2.2 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/administration/web/RealAdminController.java` |  | Met |
| AC7.3.1 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/shared/wallet/WalletDisplay.java` |  | Met |
| AC8.1.1 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/application/RealKillSwitchService.java` |  | Met |
| AC8.1.2 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/infra/KillSwitchCancelJob.java` |  | Met |
| AC8.1.3 | sim | u5-real-operations, u7-exits-and-fiscal | `apps/api/src/main/java/com/memetrade/realexits/infra/ConditionalCancelJob.java`<br>`apps/api/src/main/java/com/memetrade/realexits/infra/ConditionalCancelJob.java` |  | Unverified |
| AC8.1.4 | sim | u5-real-operations | `apps/api/src/test/java/com/memetrade/botwallet/KillSwitchIT.java` |  | Met |
| AC8.2.1 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/infra/RealReconciliationJob.java` |  | Met |
| AC8.2.2 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/application/DivergenceService.java` |  | Met |
| AC8.3.1 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/infra/ConfirmationJob.java` |  | Met |
| AC8.3.2 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/infra/AmbiguousJob.java` |  | Met |
| AC8.3.3 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/infra/UpstreamTracker.java` |  | Met |
| AC8.3.4 | sim | u4-real-execution | `apps/api/src/main/java/com/memetrade/realexecution/domain/RealOrderStateMachine.java` |  | Met |
| AC8.4.1 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/application/BotWalletAnonymizationService.java` |  | Met |
| AC8.4.2 | sim | u5-real-operations | `apps/api/src/main/java/com/memetrade/botwallet/application/BotWalletAnonymizationService.java` |  | Met |
| AC8.5.1 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/botwallet/infra/RealAudit.java` |  | Met |
| AC8.5.2 | sim | u1-bot-wallet | `apps/api/src/main/java/com/memetrade/audit/infra/WalletHasher.java` |  | Met |
