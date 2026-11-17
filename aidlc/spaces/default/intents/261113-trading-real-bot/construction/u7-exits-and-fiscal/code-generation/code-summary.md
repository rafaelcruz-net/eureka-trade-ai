# Code Summary — U7 `u7-exits-and-fiscal`

Intent `261113-trading-real-bot`. Plano: `code-generation-plan.md` (Steps 1 a 17). Testing Contract: `custom`, `Comprehensive`, sha `sha256:98df078ab1ee44bde9eaa1e5c7f5f121064c1ad1d933fdf12d5563b3111d6400`. Instruções de teste: `unit-test-instructions.md`.

**Escopo do portão BR0.** Esta Unit **não** tem a `TriggerPolicy` completa nem o `JupiterTriggerAdapter` real que envia transações. O TxSigner ganhou só a carga do `trigger-program.json`, com a conferência do hash, e a recusa `POLICY_NOT_RECORDED`. O flag `memetrade.real.exits.enabled` fica `false` por padrão. Ele só é ligado no perfil de teste local, com o adaptador simulado.

## O que foi construído, por etapa

| Etapa | O que foi feito | Arquivos principais |
|---|---|---|
| 1. Estrutura e configuração | Criado o módulo `realexits`, com `api`, `domain`, `application`, `infra`, `infra.jupiter` (vazio de propósito), `fiscal` e `web`. O `RealProperties.Exits` tem `enabled=false` por padrão. O adaptador `simulated` é recusado fora da `LOCALNET`, e a chave da Jupiter fica mascarada no `toString`. O `RuntimeSettingsGuard` passou a exigir mínimo de 23 na API e 28/36 no worker; `application.yml`, `application-worker.yml`, `docker-compose.yml`, `.env.example` e a Javadoc mudaram junto. Há 15 regras ArchUnit novas, cada uma com uma fixture que falha. | `realexits/**/package-info.java`, `botwallet/infra/RealProperties.java`, `shared/config/RuntimeSettingsGuard.java`, `realexits/RealExitsArchitectureTest.java` |
| 2. Runner de testes | Os comandos de `unit-test-instructions.md` foram confirmados rodando a suíte real de cada parte, não um teste vazio (ver "Desvios"). | — |
| 3. Migração 0022 [testes antes] | Tabela `real_conditional_order`, chaveada por `bot_wallet_id`. Tem CHECKs de preço, estado, lado e motivo; um índice único parcial por posição não final; um índice único para `(user_id, idempotency_key)`; e os índices dos trabalhos, da lista e do expurgo. Os gatilhos guardam a identidade, o estado final e as transições, e há gatilho de idade de 5 anos e um que impede `TRUNCATE`. A matriz de privilégios dá à aplicação `SELECT`/`INSERT`/`UPDATE` e ao `risk_retention` `SELECT`/`DELETE`. O adiamento da decisão ganhou `not_before`, `attempts` e os motivos `EXIT_CANCEL_TIMEOUT` e `NOTHING_TO_SELL`. Todo rollback é protegido pela tabela vazia. | `db/changelog/changes/0022-real-exits.sql`, `RealExitsMigrationIT` |
| 4. Domínio [testes antes] | `ConditionalStateMachine`, `TriggerValidation` (ordem das checagens, limites de 0,5 e 10 vezes, no máximo 18 casas, piso da saída mínima), `CancellationWatch` (15 min), `CurrentPrice` selado e `TriggerPort`. | `realexits/domain/*` |
| 5. Interfaces de extensão e venda dupla [testes antes] | No `realexecution.api`: `PositionExitGuard` e `RealExitChanges`. No `botwallet.api`: `EscrowBalances`, `OpenExitsCheck` e `ExitsAvailability`. Cada módulo dono tem um padrão neutro. Com condicional aberta, a venda do `DecisionConsumer` é adiada 30 s; na 10ª tentativa é descartada com `EXIT_CANCEL_TIMEOUT`, e com a posição zerada com `NOTHING_TO_SELL`. A `LiquidationJob` não trava as outras posições. A assinatura de venda da pessoa responde `409 EXIT_CANCELLING` ou `409 NOTHING_TO_SELL`. A reconciliação soma a custódia, e a anonimização recusa com `OPEN_CONDITIONALS`. | `realexits/application/ExitGuardService.java`, `ExitsExtensions.java`, `realexecution/infra/*`, `botwallet/*` |
| 6. Criação, assinatura e cancelamento [testes antes] | O `ConditionalService` cria passando pela barreira M5, com idempotência e uma sessão `MESSAGE` de 60 s com o consentimento em pt-BR. Ele também cuida da assinatura e do cancelamento pela pessoa. As rotas estão no `ConditionalController`. | `realexits/application/ConditionalService.java`, `ConsentMessage.java`, `realexits/web/*` |
| 7. Preenchimento [testes antes] | O `ConditionalReadJob` lê a cada 30 s e confere a rede (`finalized`). O `FillPosting` faz o compare-and-set para `FILLED` e grava no ledger na mesma transação: `SWAP_FILL_OUT`, mais `NETWORK_FEE` só quando o bot pagou, com a chave `cond:<assinatura>:fill`/`:fee`. | `realexits/infra/ConditionalReadJob.java`, `realexits/application/FillPosting.java` |
| 8. Cancelamento automático [testes antes] | O `ConditionalCancelJob` roda em duas fases. A primeira tem os gatilhos: kill switch, liberação revogada ou ausente, saída do modo e expiração da sessão. A segunda trata a `CANCELLING`, que é tentada de novo a cada minuto e nunca volta para `OPEN`. | `realexits/infra/ConditionalCancelJob.java` |
| 9. Exportação fiscal [testes antes] | `FiscalRowMapper` (agrupa por referência), `FiscalCsvWriter` (BOM, `;`, vírgula decimal, CRLF, proteção contra fórmula), `FiscalBalance` (BR7.6), `FiscalPeriod` (até 365 dias, `tz`), `FiscalExportService` (em fluxo, auditado sem o conteúdo) e `FiscalExportLimiter` (10 por hora). Para isso, `RealLedger.streamEntries` (fetchSize 500, transação só de leitura), `MarketDataQuery.symbolsOf` e `RealStateQuery.botWalletIds`. | `realexits/fiscal/*`, `ledger/infra/JdbcRealLedger.java` |
| 10. Rotas, contrato e canal ao vivo | Seis rotas `CLIENT` no `route-registry.yml`. O `openapi-guest.yaml` foi regenerado. O evento `real.conditional.state` sai pelo `DashboardMarkPoller`, e `exitsEnabled` foi adicionado a `/real/release`. | `tenancy/route-registry.yml`, `openapi/openapi-guest.yaml`, `bots/web/*` |
| 11. TxSigner [testes antes] | `OperationKind.TRIGGER_CREATE`/`TRIGGER_CANCEL` são recusados com `POLICY_NOT_RECORDED` antes do KMS e sem registro. O `TriggerProgramLoader` confere o `.sha256` e o hash configurado (obrigatório em produção). Contador `txsigner_trigger_refusals{reason}`. | `apps/txsigner/.../domain/SigningService.java`, `infra/TriggerProgramLoader.java`, `policy/TriggerProgram.java` |
| 12. Expurgo [testes antes] | `RealExitsPurgeJob` na API: 05:30 UTC, lock `real-exits-purge`, pool `risk_retention`, só condicionais finais com mais de 5 anos. | `realexits/infra/RealExitsPurgeJob.java` |
| 13. Métricas, alertas e Terraform | `RealExitsMetrics` com contadores, resumo do atraso e medidores, e o `RealExitsGaugesJob`. Há 7 regras em `real-exits-alerts.yml` e os alarmes iguais em `real-exits.tf` (`Eureka/RealExits`). A paridade é conferida pelo `RealExitsAlarmParityTest`. | `realexits/infra/RealExitsMetrics.java`, `infra/observability/real-exits-alerts.yml`, `infra/terraform/modules/observability/real-exits.tf` |
| 14. Telas | O modo "Automática nas saídas" passa a depender de `exitsEnabled`. Os outros itens: o diálogo "Definir stop e alvo", ao lado de cada posição do P&L REAL; a aba "Condicionais", com os estados em texto, o aviso de cancelamento parado e o cancelamento com confirmação; e o painel "Exportar histórico (CSV)", com o período. O evento entrou em `warn.ts` e `envelopes.ts`, e o cliente HTTP aceita `DELETE` e respostas em arquivo. Os preços são texto decimal e `bigint`, nunca `number`. | `apps/web/src/features/real/components/ConditionalDialog.tsx`, `ConditionalsTab.tsx`, `FiscalExportPanel.tsx`, `conditionals.ts`, `screens/*` |
| 15. Gravação | O `record.sh --trigger --mint --maker [--rpc]` pede só transações sem assinatura e grava tudo em `infra/solana-fixtures/trigger/`. Ele não assina nem envia nada, e para com código 3 se a Jupiter exigir chave. **Não foi rodado** (`bash -n` OK). | `infra/solana-fixtures/record.sh` |
| 16. Ponta a ponta | Dois specs novos. O `e2e/real-exports` só lê. O `e2e/real-exits` é sempre o último: tem comentário dizendo isso, faz o kill switch dentro de `try/finally` e, na saída, volta para AUTO_FULL e desliga o kill switch. O perfil de teste local ganhou as variáveis do flag e do adaptador simulado na API e no worker. **Não foram rodados** (`verify.sh` fica com o responsável). O `playwright test --list` lista os 6 testes. | `apps/web/e2e/real-v1-exports/real-exports.spec.ts`, `apps/web/e2e/real-v2-exits/real-exits.spec.ts`, `infra/docker-compose.real.yml` |
| 17. Documentação e rastreabilidade | Runbook novo, adendo da U7 no `contract-summary.md`, este resumo, `traceability.json` e `source-manifest.json`. | `docs/runbooks/real-exits.md`, `inception/contract-design/contract-summary.md` |

## Testes

### Execuções

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` (1ª execução) | 2035 testes de unidade, 4 falhas: `RiskControlArchitectureTest` (2), `AuditPropertiesTest` e `U9OpenApiContractTest`. Eram testes antigos que precisavam conhecer o expurgo, o pool 23 e o evento novo, e foram corrigidos. |
| A mesma, de novo | 2035 testes de unidade, 0 falhas. Na fase de integração (failsafe), 198 classes e 1247 testes rodaram sem nenhuma falha; aí a execução **foi parada pelo Claude Code por falta de memória na máquina**, antes de terminar as ITs, o relatório e o `jacoco:check`. Ela não foi repetida, como manda a instrução. Antes da parada, passaram nesta execução as ITs da U7 (`ConditionalCreationIT`, `FillPostingIT`, `ConditionalCancelJobIT`, `FiscalExportIT`, `RealExitsMigrationIT` e as demais) e as vizinhas (`DecisionConsumerIT`, `LiquidationIT`, `SigningSessionIT`, `ReconciliationIT`, `BotWalletAnonymizationIT`, `RealReleaseRouteIT`, `DashboardStreamIT`, `RealApiWiringIT`, `RealWorkerWiringIT`). |
| `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml spotless:apply verify` | 215 testes de unidade e 16 de integração, 0 falhas; `All coverage checks have been met`; **91,65%** de linhas |
| `corepack pnpm@10.18.3 --dir apps/web test:coverage` | 66 suítes, 799 testes, 0 falhas; **94,43%** de linhas (antes do teste de rotas, que acrescentou 1) |
| `corepack pnpm@10.18.3 --dir apps/web test -- src/lib/api` | 31 testes, 0 falhas (inclui `real-exits-routes.test.ts`) |
| `lint`, `typecheck`, `prettier --check src` e `check:api` do web | sem erro; `schema.d.ts matches openapi-guest.yaml` |
| `playwright test --list e2e/real-exports e2e/real-exits` | 6 testes em 2 arquivos |
| `promtool check rules /rules/real-exits-alerts.yml` (imagem fixada da CI) | `SUCCESS: 7 rules found` |
| `terraform fmt -check -recursive` e `terraform validate` (`hashicorp/terraform:1.9.8`, cópia dentro do contêiner) | sem diferença; configuração válida |
| `bash -n infra/solana-fixtures/record.sh` | OK |

Nunca rodaram dois Maven ao mesmo tempo. A 2ª execução do `verify` da API foi parada por falta de memória e não foi repetida.

### Testes novos da U7

| Parte | Arquivo | Testes |
|---|---|---|
| API | `realexits/db/RealExitsMigrationIT` | 12 |
| API | `realexits/domain/ConditionalStateMachineTest`, `TriggerValidationTest`, `CancellationWatchTest` | 25, 11, 4 |
| API | `realexits/application/ExitGuardIT`, `ExitsExtensionsIT`, `FillPostingIT` | 7, 4, 11 |
| API | `realexecution/infra/DecisionConsumerExitIT`, `LiquidationExitIT`, `PersonSaleExitIT`, `RealModeExitsTest` | 5, 2, 2, 5 |
| API | `botwallet/domain/ReconciliationPolicyEscrowTest`, `botwallet/application/AnonymizationOpenExitsTest` | 7, 4 |
| API | `realexits/web/ConditionalCreationIT` | 19 |
| API | `realexits/infra/SimulatedTriggerAdapterTest`, `RealExitsSettingsTest`, `ConditionalCancelJobIT`, `RealExitsPurgeJobIT`, `RealExitsAlarmParityTest`, `RealExitsMetricsTest` | 5, 5, 12, 6, 5, 4 |
| API | `realexits/fiscal/FiscalRowMapperTest`, `FiscalCsvWriterTest`, `FiscalBalanceTest`, `FiscalPeriodTest`, `FiscalStreamingTest`, `FiscalExportIT` | 10, 9, 3, 5, 2, 8 |
| API | `shared/web/PersonRouteRateLimiterWindowTest`, `realexits/RealExitsArchitectureTest` | 5, 15 |
| TxSigner | `infra/TriggerProgramLoaderTest`, `domain/SigningServiceTriggerTest`, `web/TriggerSignaturesIT` | 8, 4, 2 |
| Web | `conditionals.test.ts`, `ConditionalDialog.test.tsx`, `ConditionalsTab.test.tsx`, `FiscalExportPanel.test.tsx`, `ModeSelectorExits.test.tsx`, `screens/ExitsScreens.test.tsx`, `lib/api/real-exits-routes.test.ts` | 7, 10, 5, 5, 6, 5, 1 |
| E2E | `e2e/real-exports`, `e2e/real-exits` | 3, 3 (não rodados) |

Também foram ajustados testes antigos que fixavam os números ou listas que a U7 muda: os pools nos `U4/U5/U6/U7/U8SettingsTest`, `U4/U6/U7ContextIT`, `WorkerProcessRoleContextIT`, `RuntimeSettingsGuardWorkerCountTest`, `ComposeFilesTest` e `AuditPropertiesTest`; o expurgo novo em `ProcessRoleContextIT` e `RiskControlArchitectureTest`; as rotas em `RouteRegistryTest`; os controladores em `GuestOpenApiSlice`; o evento em `U9OpenApiContractTest`; o `exitsEnabled` nos mocks do web; e a contagem de changesets em `RealOpsMigrationIT`.

### Ordem dos testes (honestidade sobre o "testes antes")

Nas etapas marcadas [testes antes] (3 a 9, 11 e 12), os testes foram escritos antes do código de produção de cada etapa, a partir das regras. Mas a execução "vermelha" separada **não** foi feita: os testes foram compilados e rodados junto com o código da mesma etapa, na primeira vez que a etapa ficou compilável. Isso vale para a API e para o TxSigner. Nas etapas [depois], os testes vieram depois do código, como manda o contrato.

### Cobertura

| Parte | Linhas | Piso |
|---|---|---|
| API (`apps/api`, JaCoCo) | não medida: o `jacoco:check` não chegou a rodar (execução parada por falta de memória). Nenhuma exclusão nova; a medição fica com a execução da CI ou com uma nova execução local do `verify` | 80% |
| TxSigner (`apps/txsigner`, JaCoCo) | 91,65% | 80% |
| Web (`apps/web`, Jest) | 94,43% | 80% |

Nenhuma exclusão nova de cobertura e nenhum piso alterado.

## Texto para o responsável do projeto

O `ci.yml` e o `verify.sh` não foram editados. Os textos abaixo estão prontos para colar.

### `.github/workflows/ci.yml`, passo `Alert rules (promtool)`

```
          for rules in real-alerts marketdata-alerts real-risk-alerts real-execution-alerts real-ops-alerts real-exits-alerts; do
```

### `scripts/verify.sh`, lista do Playwright

O `real-exits` fica sempre por último. Ele deixa linhas novas em `real_conditional_order` e na auditoria, e nenhum spec depende desse estado.

```
    e2e/reacceptance e2e/data-requests e2e/anonymization e2e/real-bot-wallet e2e/real-swap e2e/real-ops e2e/real-ui e2e/real-v1-exports e2e/real-v2-exits
```

Comentário sugerido logo acima da lista:

```
# Playwright runs the spec files in the alphabetical order of their paths: e2e/real-v2-exits is the LAST one (U7).
```

O `infra/docker-compose.real.yml` já liga as saídas com o adaptador simulado na API e no worker, só nessa execução. Nada muda no `verify.sh` para isso.

### Execução do `record.sh --trigger` (uma vez, com rede, pelo responsável)

```
bash infra/solana-fixtures/record.sh --trigger --mint <mint com saldo> --maker <carteira pública com saldo do mint> [--rpc <url de RPC da mainnet>]
```

- O script só pede à Trigger API v2 as transações de criação e de cancelamento **sem assinatura**. Ele não assina, não envia e não usa chave.
- Ele grava em `infra/solana-fixtures/trigger/` as respostas, os vetores, o `trigger-program.json`, o `.sha256` e os clones do programa e das contas.
- Se a Jupiter exigir chave de API ou recusar (401/403), o script para com código 3, e a decisão volta para você antes de qualquer gasto.
- Depois da gravação, a `TriggerPolicy` completa e o adaptador real entram numa entrega própria, com os testes de ataque escritos antes do código. O hash vai em `TXSIGNER_TRIGGER_PROGRAM_SHA256`.

## Correção depois da primeira CI (execução 44113862674)

O Playwright (com `workers: 1`) roda os arquivos na ordem alfabética do caminho, não na ordem da lista do `verify.sh`. Por isso os specs da U7 rodaram antes do `real-swap`, e quatro testes falharam em cascata.

- **Pastas renomeadas:** `e2e/real-v1-exports` e `e2e/real-v2-exits`, para virem depois do `real-ui`.
- **Venda total movida:** o "vender tudo e sacar" saiu do `real-swap` e virou o teste 4 do `real-v2-exits`. Assim a posição da compra gravada fica aberta para o `real-ui`, a exportação e as condicionais. O teste 4 também prova que voltar para AUTO_FULL cancela a condicional aberta (BR6.2) antes da venda.
- **Lista do `verify.sh`:** precisa usar os nomes novos (texto acima).

## Lacuna aceita

Até a gravação real, o **preenchimento** de uma condicional (BR5) não tem teste de ponta a ponta. A prova dele está nos testes de integração (`FillPostingIT`, `ConditionalCancelJobIT`), com RPC sintético (`FakeRealChain`) e uma porta roteirizada (`ScriptedTriggerPort`). O adaptador simulado nunca preenche. Com a gravação, o spec `real-exits` passa a usar a transação real.

## Desvios e decisões

| Desvio | Por quê |
|---|---|
| Condicionais guardadas por `bot_wallet_id`, sem coluna de endereço; o índice é `(bot_wallet_id, state)`, não `(bot_wallet_address, state)` (U7-NFR3.6) | A anonimização (U5) apaga o endereço; a chave interna evita mais um lugar com endereço e segue o ledger REAL |
| Dependências permitidas da `realexits` incluem também `identity.api` e `audit.api` | Idempotência comum (`project.md`) e auditoria das mudanças de estado |
| Código novo `EXITS_MODE_REQUIRED` (403) | Separa "modo errado" de "flag desligado" (`EXITS_DISABLED`) |
| A condicional duplicada na posição responde `409 CONDITIONAL_ALREADY_OPEN` | Código estável, em vez de um `409` genérico |
| Rota nova `GET /api/v1/real/conditional-orders/quote` | O diálogo de BR8.2 precisa do preço atual com fonte e horário e dos limites de BR2.6; nenhuma rota existente devolvia isso |
| Parâmetro `tz` na exportação, padrão `America/Sao_Paulo` | A pessoa não tem fuso guardado; o navegador manda o próprio fuso |
| `RealLedger.streamEntries` recebe as carteiras do bot | O ledger REAL não tem coluna de pessoa |
| A venda da pessoa (BR4.3) é tratada na assinatura dela (`SigningSessionService`), com `409 EXIT_CANCELLING` ou `NOTHING_TO_SELL` | É o ponto em que a venda dos modos `SIGNED`/`AUTO_EXITS` sai |
| Uma condicional `OPEN` que a Jupiter diz cancelada sem pedido vira `EXPIRED` | A máquina não tem `OPEN → CANCELLED`, só via `CANCELLING` |
| As recusas `TRIGGER_*` do TxSigner não são gravadas no registro de assinaturas | BR0.3: nada é registrado antes da gravação; o contador conta |
| RPC sintético pelo `FakeRealChain`, sem transações montadas pelo SDK Solana | Os testes não precisam da transação real antes da gravação |
| A quantidade vendida vem da variação do token na rede, ou do valor da ordem quando a variação é 0 | O adaptador simulado não move o token |
| A tela só oferece a autorização `MESSAGE`; com `TRANSACTION` ela diz que ainda não está disponível | O caminho por transação só existe depois da gravação |
| `ConsentMessage` em pt-BR | É lido e assinado pela pessoa |
| O DELETE de uma condicional já em `CANCELLING` devolve a mesma condicional; uma final devolve `409 INVALID_TRANSITION` | Repetição segura sem código novo |
| Step 2 sem "teste vazio" | Os comandos foram confirmados rodando a suíte real de cada parte |
| Exceções nomeadas em `RiskControlArchitectureTest` para o `RealExitsPurgeJob` | Mesmo precedente dos expurgos da U4 e da U5: só o expurgo recebe o pool `risk_retention` |

## Itens abertos

- Rodar de novo `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` (ou usar a execução da CI no GitHub) para fechar as ITs restantes e o piso de 80% da API.

- Rodar o `scripts/verify.sh` com os specs novos (responsável), depois de colar a lista acima. O spec `real-exports` compara a soma de BR7.6 com o `totalLamports` da carteira do bot, porque ela foi criada na mesma execução.
- Colar a linha do `promtool` no `ci.yml` (responsável).
- Rodar o `record.sh --trigger` (responsável, com rede) e decidir a entrega da `TriggerPolicy` e do adaptador real (portão BR0).
- Antes de ligar o flag na mainnet: a decisão do fundador sobre o risco que sobra de BR2.6 (runbook, seção 5).
- Na nuvem, os alarmes da U7 só disparam quando o caminho comum de métricas do worker e do TxSigner existir (pendência comum de U1 a U7).
- Pontos menores da revisão de código (iteração 1, READY), que ficam para a entrega da gravação real:
  - o `ConditionalService.create` passa a rodar dentro do `withPersonLock` e a recusar quando há venda REAL em andamento, com teste dessa janela entre conferir e usar;
  - com o adaptador real, o `FillPosting` passa a exigir a variação do token na rede, sem cair para o valor da ordem;
  - o `streamEntries` filtra por `bot_wallet_id` antes da função de janela;
  - a auditoria da exportação é gravada no começo do fluxo;
  - o limitador de 10 por hora passa a valer entre instâncias quando a API tiver mais de uma.
