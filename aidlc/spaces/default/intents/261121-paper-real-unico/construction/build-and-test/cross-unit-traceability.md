# Rastreabilidade entre Units — Build and Test, intent `261121-paper-real-unico`

Data: 2026-11-25. Comando (Step 10): `aidlc engine sensor-traceability --stage build-and-test`, rodado da raiz do repositório.

## Veredito

**FAIL** (`"pass": false`).

- Requisitos funcionais: **23 de 31** cobertos; 8 descobertos (FR2.3, FR3.2, FR3.5, FR3.6, FR4.1, FR4.3, FR7.1, FR8.1).
- Critérios de aceite: **111 de 138** cobertos; 27 descobertos.
- NFRs do intent: **0 de 8** cobertos (NFR1 a NFR8), porque cada um tem pelo menos um ID detalhado (`NFRx.y`) não construído ou `Deferred` na rastreabilidade da Code Generation.
- Total: **134 de 177** IDs cobertos; **43 descobertos**.

Motivo devolvido pelo sensor, literal:

> u1-mode-skeleton: AC3.2.5: target file does not exist: apps/api/src/main/java/com/memetrade/pnl/application/RealBotPnlQueryV1.java; u1-mode-skeleton: AC3.2.6: target file does not exist: apps/api/src/main/java/com/memetrade/pnl/application/RealBotPnlQueryV1.java; u1-mode-skeleton: NFR2.1: target file does not exist: apps/api/src/main/java/com/memetrade/pnl/application/RealBotPnlQueryV1.java; u3-pnl-views Code Generation traceability.json: coverage[9].target must be a string when present

**Leitura do resultado.** A maior parte das falhas vem dos arquivos `traceability.json` da Code Generation e não de código que falta: os 24 critérios de aceite da U3 apontam para arquivos que existem e cujos testes passaram na CI, mas o sensor rejeita o arquivo inteiro da U3 por causa de alvos `null`. O que está de fato pendente são os 2 critérios de carga e de devnet da U2 (AC3.11.1 e AC3.11.2), que dependem do workflow manual `load-test` e da devnet. Toda ID descoberta é um achado desta etapa e vai para o gate de aprovação.

## Achados

| Achado | IDs afetados | Causa | Unit dona | Correção proposta (registro, sem código) |
|---|---|---|---|---|
| **F-T1** | AC2.3.1 a AC2.3.5, AC3.10.1 a AC3.10.4, AC4.3.1 a AC4.3.4, AC7.1.1 a AC7.1.5, AC8.1.1 a AC8.1.4 (22 ACs); FR2.3, FR3.6, FR4.3, FR7.1, FR8.1; todas as linhas `NFRx.y` da U3 | `u3-pnl-views/code-generation/traceability.json` tem 14 linhas `N/A` com `"target": null` e o motivo em `note` (a primeira é `coverage[9]`, AC4.1.1). O formato exige `target` string em `N/A`, então o sensor descarta o arquivo inteiro. Os alvos das linhas `OK` existem (por exemplo, `BotModeFilter.tsx`, `BotPnlLines.tsx`, `DashboardModeCards.tsx`, `PeriodSelector.tsx`, `ComparisonView.tsx`) e os testes deles passaram na CI. | `u3-pnl-views` | Mover o motivo de `note` para `target` nas 14 linhas (AC4.1.1, AC4.1.3, AC4.1.4, AC4.1.5, AC4.2.1, AC4.2.3, AC4.2.7, AC4.4.5, AC4.5.2, AC7.2.1, NFR4.6, NFR7.3, NFR8.2, NFR8.3); NFR7.3 vira `OK` com o piso de cobertura medido (ver matriz). |
| **F-T2** | AC3.2.5, AC3.2.6 (e por eles FR3.2, FR3.5); NFR2.1 da U1; BR5.5 da U1 | `u1-mode-skeleton/code-generation/traceability.json` aponta para `pnl/application/RealBotPnlQueryV1.java`, que a U2 removeu ao trocar o cálculo pelo `RealBotPnl` (C10 V2). A U2 marca os mesmos ACs `N/A` "entregue pela u1-mode-skeleton", então nenhuma Unit tem hoje um alvo válido para eles. | `u1-mode-skeleton` (o código atual é da U2) | Reapontar para o código atual: `pnl/application/RealBotPnl.java` e `pnl/web/RealPnlController.java`, com os testes `GuestOpenApiContractTest`, `RealPnlTenantIsolationIT` e `RealBotPnlTest`; NFR2.1 para a regra ArchUnit `theRealLedgerAndThePnlNeverUseFloatingPoint`. |
| **F-T3** | AC4.1.5 (FR4.1), BR10.1, NFR6.1, NFR7.7, NFR7.12 da U1; NFR1.4, NFR1.5, NFR1.8, NFR1.26, NFR7.2, NFR7.4, NFR7.6 da U2; NFR7.3, NFR7.5 da U4 | Linhas `Deferred` na Code Generation que esta etapa verificou: o spec `zz-paper-real-unico` rodou verde no `smoke` e no `e2e` (20 casos); as medidas da U2 estão dentro das metas; os pisos de cobertura foram cumpridos na CI. | U1, U2, U4 | Atualizar para `OK` com o alvo citado na matriz de `build-and-test-summary.md`. |
| **F-T4** | AC3.11.1, AC3.11.2; NFR1.1, NFR1.9, NFR1.25 da U2 | Pendência real: portão de carga `pnl-by-mode` (workflow manual `load-test`, sem a opção no `load-test.yml`) e repetição na devnet. | `u2-real-bot-pnl` | Nenhuma correção de registro; fica com `performance-validation` (Unverified na matriz; U2-NFR1.9 Not Met). |
| **F-T5** | "u4: NFR1.15", "u4: NFR1.18", "u4: NFR3.11", "u3: NFR1.25" | Falso positivo do sensor: são citações de IDs da U2 dentro das seções `## Sources` de `u4-own-quantity-cycle/nfr-requirements/scalability-requirements.md`, `u4-own-quantity-cycle/nfr-requirements/security-requirements.md` e `u3-pnl-views/nfr-requirements/performance-requirements.md`, lidas como IDs daquelas Units. Esses IDs não existem na U3 nem na U4. | — | Nenhuma no código; se o responsável quiser silenciar, citar como "NFR1.15 da U2" fora do padrão de ID. |
| **F-T6** | NFR1.1 e NFR1.13 da U4 | O sensor só confere se o alvo existe; a rastreabilidade da U4 marca NFR1.1 `OK` (o `RealBotHoldingsReader.java` existe). A meta de tempo, porém, está `Not Met` nesta etapa. A cobertura de rastreabilidade não substitui o veredito da matriz. | `u4-own-quantity-cycle` | Decisão do fundador (`test-results.md`, Halt-and-ask). |

Estas correções de registro não foram aplicadas aqui: elas mudam artefatos da Code Generation já aprovados, e o coordenador pediu para não reabrir a Code Generation. Ficam para o gate.

## Cobertura por requisito funcional

| ID | Coberto | Pelos ACs | ACs não construídos |
|---|---|---|---|
| FR1.1 | sim | AC1.1.1, AC1.1.2, AC1.1.3, AC1.1.4 | — |
| FR1.2 | sim | AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5, AC1.2.6, AC1.2.7, AC1.2.8, AC1.2.9 | — |
| FR1.3 | sim | AC1.3.1, AC1.3.2, AC1.3.3, AC1.3.4, AC1.3.5 | — |
| FR1.4 | sim | AC1.4.1, AC1.4.2, AC1.4.3 | — |
| FR2.1 | sim | AC2.1.1, AC2.1.2, AC2.1.3, AC2.1.4, AC2.1.5 | — |
| FR2.2 | sim | AC2.2.1, AC2.2.2, AC2.2.3, AC2.2.4 | — |
| FR2.3 | **não** | AC2.3.1, AC2.3.2, AC2.3.3, AC2.3.4, AC2.3.5 | AC2.3.1, AC2.3.2, AC2.3.3, AC2.3.4, AC2.3.5 (F-T1) |
| FR2.4 | sim | AC2.4.1, AC2.4.2, AC2.4.3, AC2.4.4 | — |
| FR3.1 | sim | AC3.1.1, AC3.1.2 | — |
| FR3.2 | **não** | AC3.2.1, AC3.2.2, AC3.2.3, AC3.2.4, AC3.2.5, AC3.2.6 | AC3.2.5, AC3.2.6 (F-T2) |
| FR3.3 | sim | AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.4.1, AC3.4.2, AC3.4.3, AC3.4.4 | — |
| FR3.4 | sim | AC3.5.1, AC3.5.2, AC3.5.3 | — |
| FR3.5 | **não** | AC3.2.1 a AC3.2.6, AC3.8.1 a AC3.8.4 | AC3.2.5, AC3.2.6 (F-T2) |
| FR3.6 | **não** | AC3.10.1, AC3.10.2, AC3.10.3, AC3.10.4 | AC3.10.1 a AC3.10.4 (F-T1) |
| FR3.7 | sim | AC3.3.1 a AC3.3.4, AC3.4.1 a AC3.4.4, AC3.5.1 a AC3.5.3 | — |
| FR3.8 | sim | AC3.6.1, AC3.6.2, AC3.6.3 | — |
| FR3.9 | sim | AC3.7.1, AC3.7.2, AC3.7.3, AC3.7.4 | — |
| FR3.10 | sim | AC3.8.1, AC3.8.2, AC3.8.3, AC3.8.4 | — |
| FR3.11 | sim | AC3.9.1, AC3.9.2, AC3.9.3 | — |
| FR4.1 | **não** | AC4.1.1 a AC4.1.5 | AC4.1.5 (F-T3) |
| FR4.2 | sim | AC4.2.1 a AC4.2.7 | — |
| FR4.3 | **não** | AC4.3.1, AC4.3.2, AC4.3.3, AC4.3.4 | AC4.3.1 a AC4.3.4 (F-T1) |
| FR5.1 | sim | AC5.1.1, AC5.1.2, AC5.1.3 | — |
| FR5.2 | sim | AC5.2.1, AC5.2.2 | — |
| FR5.3 | sim | AC5.3.1 a AC5.3.5, AC5.4.1 a AC5.4.3 | — |
| FR5.4 | sim | AC3.3.1 a AC3.9.3, AC5.1.1 a AC5.1.3 | — |
| FR6.1 | sim | AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4 | — |
| FR6.2 | sim | AC6.2.1, AC6.2.2, AC6.2.3 | — |
| FR7.1 | **não** | AC7.1.1 a AC7.1.5 | AC7.1.1 a AC7.1.5 (F-T1) |
| FR7.2 | sim | AC7.2.1, AC7.2.2, AC7.2.3 | — |
| FR8.1 | **não** | AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4 | AC8.1.1 a AC8.1.4 (F-T1) |

## Cobertura por NFR do intent

| ID | Coberto | IDs detalhados | Não construídos ou `Deferred` segundo o sensor | `N/A` | Achados |
|---|---|---|---|---|---|
| NFR1 | **não** | 71 | u2: NFR1.25, NFR1.9, NFR1.1, NFR1.4, NFR1.8, NFR1.5, NFR1.26; u3: NFR1.1 a NFR1.15 e "NFR1.25"; u4: "NFR1.15", "NFR1.18" | 2 | F-T1, F-T3, F-T4, F-T5 |
| NFR2 | **não** | 42 | u1: NFR2.1; u3: NFR2.1 a NFR2.4 | 1 | F-T1, F-T2 |
| NFR3 | **não** | 55 | u3: NFR3.1 a NFR3.10, NFR3.13 a NFR3.17; u4: "NFR3.11" | 2 | F-T1; a U4 não tem NFR3.11 nos requisitos (citação, F-T5) |
| NFR4 | **não** | 30 | u3: NFR4.1 a NFR4.13 | 2 | F-T1 |
| NFR5 | **não** | 9 | u3: NFR5.1 a NFR5.4 | 0 | F-T1 |
| NFR6 | **não** | 6 | u1: NFR6.1; u3: NFR6.1, NFR6.2 | 0 | F-T1, F-T3 |
| NFR7 | **não** | 37 | u1: NFR7.7, NFR7.12; u2: NFR7.2, NFR7.4, NFR7.6; u3: NFR7.1 a NFR7.8; u4: NFR7.3, NFR7.5 | 0 | F-T1, F-T3 |
| NFR8 | **não** | 47 | u3: NFR8.1 a NFR8.4 | 21 | F-T1 |

O veredito de cada `NFRx.y` contra a meta está na matriz de `build-and-test-summary.md`; esta tabela só mostra a rastreabilidade.

## Cobertura por critério de aceite

| ID | Coberto | Unit (dona) | Arquivo alvo ou motivo |
|---|---|---|---|
| AC1.1.1 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.1.2 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/application/BotCommandService.java |
| AC1.1.3 | sim | u1-mode-skeleton | web/src/features/bots/RealConfirmDialog.tsx |
| AC1.1.4 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.2.1 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/domain/RealCreationEligibility.java |
| AC1.2.2 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.2.3 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.2.4 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.2.5 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.2.6 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.2.7 | sim | u1-mode-skeleton | web/src/features/bots/ModeStep.tsx |
| AC1.2.8 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/application/BotCommandService.java |
| AC1.2.9 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/application/RealEligibilityReader.java |
| AC1.3.1 | sim | u1-mode-skeleton | web/src/features/bots/RealConfirmDialog.tsx |
| AC1.3.2 | sim | u1-mode-skeleton | web/src/features/bots/RealConfirmDialog.tsx |
| AC1.3.3 | sim | u1-mode-skeleton | web/src/features/bots/RealConfirmDialog.tsx |
| AC1.3.4 | sim | u1-mode-skeleton | web/src/features/bots/RealConfirmDialog.tsx |
| AC1.3.5 | sim | u1-mode-skeleton | api/src/test/java/com/memetrade/bots/application/BotCreateConcurrencyIT.java |
| AC1.4.1 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/web/BotDtos.java |
| AC1.4.2 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/web/BotDtos.java |
| AC1.4.3 | sim | u1-mode-skeleton | web/src/features/bots/BotWizard.tsx |
| AC1.5.1 | sim | u1-mode-skeleton | web/src/features/bots/BotCreate.tsx |
| AC1.5.2 | sim | u1-mode-skeleton | infra/terraform/main.tf |
| AC1.5.3 | sim | u1-mode-skeleton | web/src/features/bots/BotCreate.tsx |
| AC2.1.1 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC2.1.2 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC2.1.3 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC2.1.4 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC2.1.5 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC2.2.1 | sim | u1-mode-skeleton | web/src/features/pnl/money-format.ts |
| AC2.2.2 | sim | u1-mode-skeleton | web/src/features/real/money.ts |
| AC2.2.3 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC2.2.4 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC2.3.1 | **não** | u3-pnl-views (F-T1) | web/src/features/bots/BotModeFilter.tsx existe; arquivo da U3 rejeitado |
| AC2.3.2 | **não** | u3-pnl-views (F-T1) | web/src/features/bots/BotList.tsx existe; arquivo da U3 rejeitado |
| AC2.3.3 | **não** | u3-pnl-views (F-T1) | api/src/main/java/com/memetrade/bots/infra/JdbcBotRepository.java existe; arquivo da U3 rejeitado |
| AC2.3.4 | **não** | u3-pnl-views (F-T1) | web/src/features/bots/BotList.tsx existe; arquivo da U3 rejeitado |
| AC2.3.5 | **não** | u3-pnl-views (F-T1) | web/src/features/bots/BotModeFilter.tsx existe; arquivo da U3 rejeitado |
| AC2.4.1 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/application/BotSummaryView.java |
| AC2.4.2 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/web/BotDtos.java |
| AC2.4.3 | sim | u1-mode-skeleton | api/src/test/java/com/memetrade/contract/GuestOpenApiContractTest.java |
| AC2.4.4 | sim | u1-mode-skeleton | web/src/features/dashboard/envelopes.ts |
| AC3.1.1 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/pnl/application/PaperModeBlockReader.java |
| AC3.1.2 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/bots/application/BotDirectoryImpl.java |
| AC3.2.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealCostBasis.java |
| AC3.2.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC3.2.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealCostBasis.java |
| AC3.2.4 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/PositionValue.java |
| AC3.2.5 | **não** | u1-mode-skeleton (F-T2) | alvo `RealBotPnlQueryV1.java` removido pela U2 |
| AC3.2.6 | **não** | u1-mode-skeleton (F-T2) | alvo `RealBotPnlQueryV1.java` removido pela U2 |
| AC3.3.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.3.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/LargestRemainder.java |
| AC3.3.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/ProrationPart.java |
| AC3.3.4 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.4.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.4.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.4.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/ProrationPart.java |
| AC3.4.4 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.5.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.5.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC3.5.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.6.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/ledger/api/NetworkOrderKey.java |
| AC3.6.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/ledger/infra/JdbcRealLedger.java |
| AC3.6.3 | sim | u2-real-bot-pnl | api/src/test/java/com/memetrade/pnl/real/RealPnlPropertyTest.java |
| AC3.7.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC3.7.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/OrderAttribution.java |
| AC3.7.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC3.7.4 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealProration.java |
| AC3.8.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/PositionValue.java |
| AC3.8.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC3.8.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlResult.java |
| AC3.8.4 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/application/RealLamportPrices.java |
| AC3.9.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/application/RealBotPnl.java |
| AC3.9.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/application/RealBotPnl.java |
| AC3.9.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC3.10.1 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/BotPnlLines.tsx existe; arquivo da U3 rejeitado |
| AC3.10.2 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/ProratedLines.tsx existe; arquivo da U3 rejeitado |
| AC3.10.3 | **não** | u3-pnl-views (F-T1) | api/src/main/java/com/memetrade/pnl/web/PnlController.java existe; arquivo da U3 rejeitado |
| AC3.10.4 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/BotPnlLines.tsx existe; arquivo da U3 rejeitado |
| AC3.11.1 | **não** | u2-real-bot-pnl (F-T4) | `Deferred`: portão p95 ≤ 1 s de C4 no `load-test` (`pnl-by-mode`); dono `performance-validation` |
| AC3.11.2 | **não** | u2-real-bot-pnl (F-T4) | `Deferred`: medição do fundador na devnet; dono `performance-validation` |
| AC3.11.3 | sim | u2-real-bot-pnl | api/src/test/java/com/memetrade/pnl/RealPnlReadOnlyIT.java |
| AC4.1.1 | sim | u1-mode-skeleton | web/src/features/pnl/PnlModeBlock.tsx |
| AC4.1.2 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/pnl/modeview/ModePnlView.java |
| AC4.1.3 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/pnl/web/RealBlockSource.java |
| AC4.1.4 | sim | u1-mode-skeleton | api/src/main/java/com/memetrade/pnl/web/PaperBlockSource.java |
| AC4.1.5 | **não** | u1-mode-skeleton (F-T3) | `Deferred` no registro; o spec `zz-paper-real-unico` rodou verde na CI (smoke e e2e) |
| AC4.2.1 | sim | u1-mode-skeleton | web/src/features/pnl/PnlModeBlock.tsx |
| AC4.2.2 | sim (N/A) | u1-mode-skeleton | entregue pela u3-pnl-views (painel) |
| AC4.2.3 | sim | u1-mode-skeleton | web/src/features/pnl/PnlModeBlock.tsx |
| AC4.2.4 | sim (N/A) | u1-mode-skeleton | entregue pela u3-pnl-views |
| AC4.2.5 | sim (N/A) | u1-mode-skeleton | entregue pela u3-pnl-views |
| AC4.2.6 | sim | u1-mode-skeleton | web/src/features/bots/BotList.tsx |
| AC4.2.7 | sim | u1-mode-skeleton | web/src/features/pnl/mode-messages.ts |
| AC4.3.1 | **não** | u3-pnl-views (F-T1) | web/src/features/dashboard/DashboardModeCards.tsx existe; arquivo da U3 rejeitado |
| AC4.3.2 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/PnlModeBlock.tsx existe; arquivo da U3 rejeitado |
| AC4.3.3 | **não** | u3-pnl-views (F-T1) | web/src/features/dashboard/DashboardModeCards.tsx existe; arquivo da U3 rejeitado |
| AC4.3.4 | **não** | u3-pnl-views (F-T1) | web/src/features/dashboard/DashboardScreen.tsx existe; arquivo da U3 rejeitado |
| AC4.4.1 | sim | u1-mode-skeleton | web/src/components/SimulatedBadge.tsx |
| AC4.4.2 | sim | u1-mode-skeleton | web/src/features/pnl/PnlModeBlock.tsx |
| AC4.4.3 | sim | u1-mode-skeleton | web/src/features/pnl/PnlScreen.tsx |
| AC4.4.4 | sim | u1-mode-skeleton | web/src/features/pnl/PnlModeBlock.tsx |
| AC4.4.5 | sim | u1-mode-skeleton | web/src/features/pnl/PnlScreen.tsx |
| AC4.5.1 | sim | u1-mode-skeleton | web/src/app/globals.css |
| AC4.5.2 | sim | u1-mode-skeleton | web/src/features/pnl/PnlModeBlock.tsx |
| AC4.5.3 | sim | u1-mode-skeleton | web/src/features/pnl/PnlScreen.tsx |
| AC5.1.1 | sim | u2-real-bot-pnl | api/src/test/java/com/memetrade/pnl/real/ConditionalSaleRegressionTest.java |
| AC5.1.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC5.1.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/TradeEvent.java |
| AC5.2.1 | sim | u2-real-bot-pnl | api/src/test/java/com/memetrade/pnl/real/RealCostParityTest.java |
| AC5.2.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/application/AttributedCostReader.java |
| AC5.3.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlReconciliation.java |
| AC5.3.2 | sim | u1-mode-skeleton | api/src/test/java/com/memetrade/pnl/web/ModePnlFailureIT.java |
| AC5.3.3 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/application/ReconciliationAuditGate.java |
| AC5.3.4 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlReconciliation.java |
| AC5.3.5 | sim | u2-real-bot-pnl | api/src/test/java/com/memetrade/pnl/RealPnlTenantIsolationIT.java |
| AC5.4.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC5.4.2 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlReconciliation.java |
| AC5.4.3 | sim | u2-real-bot-pnl | api/src/test/java/com/memetrade/pnl/real/RealPnlPeriodBoundaryTest.java |
| AC6.1.1 | sim | u4-own-quantity-cycle | api/src/main/java/com/memetrade/bots/domain/OwnSellCap.java |
| AC6.1.2 | sim | u4-own-quantity-cycle | api/src/main/java/com/memetrade/pnl/application/RealBotHoldingsReader.java |
| AC6.1.3 | sim | u4-own-quantity-cycle | api/src/main/java/com/memetrade/bots/domain/OwnDecisionInputs.java |
| AC6.1.4 | sim | u4-own-quantity-cycle | api/src/main/java/com/memetrade/bots/worker/RealCycle.java |
| AC6.2.1 | sim | u4-own-quantity-cycle | web/src/features/bots/BotList.tsx |
| AC6.2.2 | sim | u4-own-quantity-cycle | api/src/main/java/com/memetrade/bots/infra/OwnQuantitySellProperties.java |
| AC6.2.3 | sim | u4-own-quantity-cycle | web/src/features/bots/BotList.tsx |
| AC7.1.1 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/PeriodSelector.tsx existe; arquivo da U3 rejeitado |
| AC7.1.2 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/PnlScreen.tsx existe; arquivo da U3 rejeitado |
| AC7.1.3 | **não** | u3-pnl-views (F-T1) | api/src/main/java/com/memetrade/pnl/web/PnlWindows.java existe; arquivo da U3 rejeitado |
| AC7.1.4 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/PeriodSelector.tsx existe; arquivo da U3 rejeitado |
| AC7.1.5 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/PnlScreen.tsx existe; arquivo da U3 rejeitado |
| AC7.2.1 | sim | u2-real-bot-pnl | api/src/main/java/com/memetrade/pnl/real/RealPnlEngine.java |
| AC7.2.2 | sim (N/A) | u2-real-bot-pnl | entregue pela u3-pnl-views (data no fuso da pessoa) |
| AC7.2.3 | sim (N/A) | u2-real-bot-pnl | entregue pela u3-pnl-views (instantes em UTC no parâmetro de período) |
| AC8.1.1 | **não** | u3-pnl-views (F-T1) | web/src/features/pnl/ComparisonView.tsx existe; arquivo da U3 rejeitado |
| AC8.1.2 | **não** | u3-pnl-views (F-T1) | api/src/main/java/com/memetrade/pnl/web/ModePnlDtos.java existe; arquivo da U3 rejeitado |
| AC8.1.3 | **não** | u3-pnl-views (F-T1) | api/src/main/java/com/memetrade/pnl/web/RealBlockSource.java existe; arquivo da U3 rejeitado |
| AC8.1.4 | **não** | u3-pnl-views (F-T1) | api/src/main/java/com/memetrade/pnl/api/BotDirectory.java existe; arquivo da U3 rejeitado |

Os caminhos começam em `apps/`, exceto `infra/terraform/main.tf`. Para os ACs da U3, o arquivo indicado é o alvo `OK` da própria rastreabilidade da U3, que existe no repositório; o sensor não o contou por causa de F-T1.

## IDs descobertos (43)

FR2.3, FR3.2, FR3.5, FR3.6, FR4.1, FR4.3, FR7.1, FR8.1, NFR1, NFR2, NFR3, NFR4, NFR5, NFR6, NFR7, NFR8, AC2.3.1, AC2.3.2, AC2.3.3, AC2.3.4, AC2.3.5, AC3.2.5, AC3.2.6, AC3.10.1, AC3.10.2, AC3.10.3, AC3.10.4, AC3.11.1, AC3.11.2, AC4.1.5, AC4.3.1, AC4.3.2, AC4.3.3, AC4.3.4, AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4, AC7.1.5, AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4.

Por causa: F-T1 (registro da U3) 22 ACs e 5 FRs; F-T2 (alvo removido na U1) 2 ACs e 2 FRs; F-T3 (`Deferred` já verificado) 1 AC e 1 FR; F-T4 (carga e devnet, pendência real) 2 ACs; os 8 NFRs do intent caem por uma mistura de F-T1, F-T3, F-T4 e F-T5. Com F-T1 a F-T3 corrigidos no registro, restariam descobertos AC3.11.1 e AC3.11.2 (até o `performance-validation`), a linha NFR1 (F-T4 e as citações de F-T5) e a linha NFR3 (só a citação "u4: NFR3.11" de F-T5, que vem do `## Sources` de `u4-own-quantity-cycle/nfr-requirements/security-requirements.md`).
