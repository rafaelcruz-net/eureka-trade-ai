# Code Summary — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Intent `261113-trading-real-bot`, data 2026-11-16. Plano: `code-generation-plan.md` (Steps 1 a 17, todos marcados). Instruções de teste: `unit-test-instructions.md`. Código, identificadores, logs e comentários em inglês; textos de interface e deste resumo em português.

## O que foi construído, por etapa

| Etapa | Ordem | O que entrou |
|---|---|---|
| 1 — Estrutura e configuração | depois | Pastas `features/real`, `features/live`, `lib/time` e `lib/encoding` no web; `features/real` no admin. Bloco do ESLint para `src/{app,features}/real/**` nos dois apps: `no-restricted-imports` (`@solana/web3.js`, `@solana/kit`, `@solana/spl-token`, `@coral-xyz/anchor`), `no-restricted-syntax` (`sendTransaction`, `sendRawTransaction`, `Number(`, `toFixed`, `+` unário), `no-restricted-globals` (`parseFloat`, `parseInt`, `localStorage`, `sessionStorage`, `console`), `no-restricted-properties` (`Math.round/floor/ceil`, `Number.parseFloat/parseInt`, armazenamento e console por `window`/`globalThis`/`self`, `document.cookie`), `no-console` e `react/no-danger`; no web o bloco repete a proibição de `EventSource`. `money.ts` é a exceção nomeada (só a regra de conversão). Os arquivos de teste e o `test-support.tsx` do REAL ficam fora do bloco. Stubs de `HTMLDialogElement.prototype.showModal` e `close` no `jest.setup.ts` dos dois apps. Testes `real-lint.test.ts` (web e admin) com as fixtures que falham, a lista permitida de dependências e a busca de `NEXT_PUBLIC_*`. |
| 2 — Runner | — | Comandos da Unit conferidos nos dois apps e na API (seção "Execuções"). Linha de base do web antes da mudança: 45 suítes, 550 testes, 95,17% de linhas. |
| 3 — Adendo do servidor | testes antes | `GET /api/v1/real/release` (`BotWalletController.release`, `403 REAL_DISABLED` com o REAL desligado, `no-store`); `RealStateQuery.readRelease` na M1 (`RealStateService`, `RealReleaseInfo`); `RealReleases.current` (liberação aberta da rede, senão a última revogada, senão `NONE`); `BotWalletView` com `capSetBy` e `release` (`RealReleaseStatus`); `BotWalletRepository.lastCapLoweredByAdmin`; `serverTime` em `RealSigningSession` pelo `Clock` injetado no `RealOrderController`; `PATCH` no ramo com token do `GuestCorsPolicy`; `DELETE` no ramo com token do `AdminCorsPolicy` (desvio, abaixo); entrada no `route-registry.yml` (`scope: CLIENT`, `negativeTest: RealReleaseRouteIT#anotherAccountNeverSeesTheReleaseOfSomeoneElse`) e no `RouteRegistryTest`; `GuestOpenApiSlice` com o mock da M1; `openapi-guest.yaml` regenerado (`-Dopenapi.write=true`) e `schema.d.ts` do web regenerado (`gen:api`). |
| 4 — Dinheiro | testes antes | `features/real/money.ts` (web; a mesma implementação copiada em `apps/admin`): `parseSol` (vírgula ou ponto, até 9 casas, espaços em volta, recusa de sinal, `max`, `positive`), `formatSol` (9 casas, milhar com ponto), `formatSolAmount`, `formatSignedSol`, `formatToken` (casas do mint; sem casas, "unidades base"), `toBaseUnits` (texto inteiro ou número inteiro seguro → `bigint`) e o tipo explícito `Unknown`. Só `bigint`. |
| 5 — Carteira e assinatura | testes antes | `WalletPort.canSignTransaction` e `signTransaction(bytes)` pela feature `solana:signTransaction` (`legacy` ou `0`), `WalletCannotSignTransaction`; `lib/encoding/base64.ts` próprio e estrito; `features/real/sign-transaction.ts` (`signServerTransaction`, `messageOf`, `SignedTransactionMismatch`): os bytes do servidor vão sem mudança à carteira e a mensagem devolvida (a partir do primeiro byte depois das assinaturas) precisa ser igual, senão nada é enviado. Carteira de teste do e2e (`e2e/support/test-wallet.ts`) com `solana:signTransaction` (`signTransactionBytes`: acha a própria chave entre os assinantes, legacy ou v0, assina com Ed25519 e devolve a transação inteira) e o teste `e2e/support/test-wallet.test.ts`, que roda no Jest do web. |
| 6 — Canal ao vivo no layout raiz | depois | `features/live/LiveChannelProvider.tsx` dentro do `SessionProvider` no `app/layout.tsx`: uma conexão por pessoa, `status`, `retryNow`, `subscribe(tipo, handler)`, `onReconnected`, `termsPending` e `markTermsPending`; não abre sem sessão, em `/entrar`, `/convite`, `/termos`, `/privacidade` e `/novo-aceite`, nem com o novo aceite pendente; um `403 TERMS_REACCEPTANCE_REQUIRED` na abertura fecha o canal e só chama o tratador global fora de `/real/deposito-saque`. `LiveChannelAnnouncer` (anúncios de perda e de volta, no `AppShell`). `warn.ts` com `real.signing.requested` e `real.order.state` em `EVENT_KINDS` (o `stream-client` passa a entregá-los, sem mudança no arquivo) e `envelopes.ts` com a validação dos dois. `DashboardProvider` assina os cinco tipos do painel pelo provedor e lê o `status` dele; o reducer não mudou. Testes do painel montam o `LiveChannelProvider` com o mesmo `fetchImpl`. |
| 7 — Base das telas | depois | `messages.ts` (tabela de BR8.8 e textos de `pausedReason`), `api.ts` (conversões para `bigint`, `RealError` com `correlationId` e o máximo do `detail`, `realReads`), `RealErrorNotice`, `RealBadge` (texto, ícone e âmbar `#f59e0b`), `PauseBanner`, `SolAmountInput`, `ModalDialog` (`<dialog>` nativo com `showModal`), `MoneyConfirmDialog` (foco inicial em "Cancelar", "Enviando…", "Tentar de novo" com o mesmo corpo e chave), `OrderStateBadge`, `ReleaseStatus`, `RealPnlCard`; hooks `useMoneyAction` (chave por corpo exato), `useTracking` (a cada 3 s, até 2 min, uma leitura por vez), `useLoad`; `lib/time/countdown.ts`. `ApiError` ganhou `detail`; o cliente aceita `PATCH`; `guestApi` ganhou as rotas do REAL e a segunda instância (no `ApiProvider`, com `useQuietApi`) que compartilha a renovação única da sessão e não tem o tratador do novo aceite. Token `real` e CSS das telas do REAL (grade, cartões abaixo de 768 px, cabeçalho que quebra linha). |
| 8 — Menu e cabeçalho | depois | `ShellSection` com `'real'`, `REAL_NAV_ITEM` "Carteira do bot" com o `RealBadge`, mostrado só com o REAL ligado (`features/real/session-real.ts`: uma leitura por sessão, pela instância sem o tratador, de `/real/release` e da carteira); tag "PAPER" com o `RealBadge` quando há carteira ativa; `SigningBanner` dentro do `AppShell`, logo abaixo do cabeçalho, só no modo Assinada. |
| 9 — Ativação R1 | depois | `/real/ativacao` (`ActivationScreen`): estado da liberação quando não está `ACTIVE`; passo 1 (texto dos termos vigentes, caixa, "Continuar" desabilitado com o motivo, aceite pela rota existente) quando a leitura da liberação encontra o novo aceite; passo 2 (teto inicial, confirmação, desafio `ACTIVATE`, `signMessage` sem mudança, ativação com `Idempotency-Key` do corpo); passo 3 (endereço, "Copiar", link para depósito e saque). `/real/carteira` redireciona para `/real`; a tela mínima da U1 (`BotWalletScreen`, `lamports.ts` e o teste dela) saiu. |
| 10 — Visão geral e teto R2 | depois | `/real` (`OverviewScreen` dentro do `RealArea`): saldo em partes, pausa, liberação fora de vigor, modo, teto (baixar com `PATCH`, subir com `RAISE_CAP`, "Ajustado pelo administrador", explicação da exposição), `RealPnlCard`. `DashboardRealPnl` no painel, em cartão próprio, só com carteira ativa. `RealArea`: REAL desligado, sem carteira (vai à ativação), erros com "Tentar de novo", e mantém a última carteira lida durante uma releitura. |
| 11 — Modo R3 | depois | `/real/modo` (`ModeScreen`, `ModeSelector` com `fieldset`/`legend`): subir com `RAISE_MODE`, voltar para Assinada com o aviso de cancelamento, "Automática nas saídas" desabilitada com "Em breve", recusas em texto; atualiza o modo da sessão (faixa). |
| 12 — Faixa e diálogo R4 | testes antes | `SigningBanner`, `SigningDialog` e `useSigningSession`: faixa no mesmo ciclo do evento; releitura (ordem `QUOTED` e sessão, duas chamadas no máximo) na carga, a cada troca de rota e depois de cada reconexão; contagem com a diferença de relógio por `serverTime`, atualizada a cada 250 ms; `aria-live` só aos 10 s e 5 s; expiração com o texto por 5 s; desfechos aceita, `SIGNING_DEADLINE_EXPIRED`, recusa na carteira (chama `reject`), falha do `reject`, `SIGNED_MESSAGE_MISMATCH`, `SIGNING_POLICY_REFUSED`, ordem fora de `QUOTED`; carteira sem `signTransaction` não abre o diálogo; com o novo aceite pendente nada é lido e o `403` da própria faixa só marca o estado. |
| 13 — Ordens R5 | depois | `/real/ordens` (`OrdersScreen`): `limit=20` por cursor ("Carregar mais"), selo de estado, assinatura com link do Solscan por rede (sem link na rede local), datas no fuso, quantidades com as casas do mint, tabela e lista de cartões. |
| 14 — Depósito e saque R6 | depois | `/real/deposito-saque` (`DepositWithdrawScreen`, `RealArea` com `withdrawal`): endereço com "Copiar"; destino abreviado sem campo; saque, "Sacar tudo" e "Vender tudo e sacar" sempre habilitados, com confirmação, chave por corpo e acompanhamento; faixa do kill switch; "Disponível para saque agora" com divergência ou restauração; faixa "Aceite os termos novos…" pela leitura sem o tratador; lista das posições que ficaram numa venda total parcial. |
| 15 — Administrador A1 | depois | `apps/admin` `/real`, `/real/usuarios/[id]`, `/real/tokens`, `/real/kill-switch`, `/real/divergencias`, `/real/restauracao`; `AdminActionDialog` (motivo de 1 a 500 caracteres contados por code point, confirmação, foco inicial em "Cancelar", erro com `correlationId`); `useAdminAction` (chave por corpo), `useAdminLoad`, `errors.ts` (BR9.3), `messages.ts`, `RealSection`; rotas R4 no `admin-api.ts`; `DELETE` no cliente; `ApiError` com `detail` e `correlationId`; item "REAL" no menu, separado de "Kill switch"; token `real` e CSS. |
| 16 — Ponta a ponta | depois | `e2e/support/login-limit.ts` (`LOGIN_WINDOW_MS`, `postWithinLoginLimit`, `signInWithinLoginLimit`), usado pelo `real-bot-wallet` e pelo `real-ui`. O `e2e/real-bot-wallet` ativa por `/real/ativacao` com o teclado e deposita e saca por `/real/deposito-saque`. Spec novo `e2e/real-ui/real-ui.spec.ts`: estado de entrada conferido no `beforeAll` (falha com o que encontrou), cenários 1 a 6, kill switch REAL dentro de `try/finally`, estado de saída conferido no `afterAll`. |
| 17 — Documentação | — | `docs/runbooks/real-ui.md`; adendo da U6 em `inception/contract-design/contract-summary.md`; este resumo, `traceability.json` e `source-manifest.json`. |

A lista completa dos arquivos de código criados, alterados ou removidos está em `source-manifest.json` (138 caminhos; três deles são as remoções da tela mínima da U1).

## Testes

### Execuções

| Comando | Resultado |
|---|---|
| `corepack pnpm@10.18.3 --dir apps/web test:coverage` (linha de base, antes da mudança) | 45 suítes, 550 testes, linhas 95,17% |
| `corepack pnpm@10.18.3 --dir apps/web test:coverage` (final) | **60 suítes, 758 testes, 0 falhas; linhas 94,6%** (statements 94,09%, branches 87,98%, functions 91,33%) |
| `corepack pnpm@10.18.3 --dir apps/admin test:coverage` (final) | **20 suítes, 221 testes, 0 falhas; linhas 89,81%** (statements 89,35%, branches 84,82%, functions 79,07%) |
| `eslint`, `tsc --noEmit` e `prettier --check` em `apps/web` (inclusive `e2e`) e em `apps/admin` | sem erros |
| `check:api` nos dois apps | `schema.d.ts matches openapi-guest.yaml`; `schema.d.ts matches openapi-admin.yaml` |
| `npx playwright test --list e2e/real-ui e2e/real-bot-wallet` | 10 testes listados (4 + 6) |
| `sh apps/api/mvnw ... test -Dtest=GuestOpenApiContractTest -Dopenapi.write=true` | 13 testes, 0 falhas; OpenAPI do convidado regenerado |
| Comando de testes da Unit na API (`unit-test-instructions.md`) | **77 testes, 0 falhas** (11 classes, inclusive `ArchitectureTest`, `SigningSessionIT` e `ReleaseAndGatesIT`) |
| Testes vizinhos da API (registro de rotas, `RealApiWiringIT`, `BotWalletRoutesIT`, `RealOffByDefaultRoutesIT`, testes de `botwallet.application`, contratos OpenAPI, `CapAndCommandsIT`, `RealRoutesIT`, `TermsReacceptanceRoutesIT`, `identity.web`) | 153 testes; 1 falha no `RouteRegistryTest` (a lista esperada não tinha a rota nova), corrigida e repetida com sucesso |
| Todas as regras ArchUnit (`*ArchitectureTest`), `RealStateQueryIT` e os ITs da administração | **164 testes, 0 falhas** |
| `spotless:apply` e `spotless:check` | aplicado; sem diferenças |

Nenhum Maven rodou em paralelo com outro. Nenhuma execução foi interrompida por falta de memória. O `scripts/verify.sh` não foi rodado (regra do trabalho).

### Testes novos da U6

| Onde | Arquivo | Testes | Etapa |
|---|---|---|---|
| API | `RealReleaseCurrentTest` | 6 | 3 |
| API | `BotWalletViewCapSetByTest` | 3 | 3 |
| API | `RealReleaseRouteIT` | 9 | 3 |
| API | `RealReleaseRouteOffIT` | 1 | 3 |
| API | `SigningSessionServerTimeIT` | 2 | 3 |
| API | `GuestCorsPolicyTest` (3 casos novos) | 3 | 3 |
| web | `real-lint.test.ts` | 15 | 1 |
| web | `money.test.ts` | 18 | 4 |
| web | `base64.test.ts`, `sign-transaction.test.ts`, `e2e/support/test-wallet.test.ts` | 3, 9, 3 | 5 |
| web | `LiveChannelProvider.test.tsx` | 18 | 6 |
| web | `components/base.test.tsx`, `lib/time/countdown.test.ts` | 30, 7 | 7 |
| web | `AppShell.test.tsx` (7 casos novos) | 7 | 8 |
| web | `ActivationScreen.test.tsx` | 16 | 9 |
| web | `OverviewScreen.test.tsx`, `DashboardRealPnl.test.tsx` | 15, 3 | 10 |
| web | `ModeScreen.test.tsx` | 11 | 11 |
| web | `SigningBanner.test.tsx`, `lib/api/quiet-api.test.ts` | 29, 3 | 12 |
| web | `OrdersScreen.test.tsx` | 11 | 13 |
| web | `DepositWithdrawScreen.test.tsx` | 15 | 14 |
| admin | `real-lint.test.ts`, `money.test.ts`, `real-admin.test.tsx` | 14, 18, 24 | 1, 4, 15 |
| e2e | `real-bot-wallet` (adaptado, 4) e `real-ui` (novo, 6) | 10 | 16 |

Testes existentes ajustados: `DashboardScreen.test.tsx` e `storage.test.tsx` (montam o `LiveChannelProvider` com o mesmo `fetchImpl`; as asserções sobre a tela não mudaram), `AppShell.test.tsx` (mock de `usePathname`), `layout.test.tsx` do admin (item "REAL" no menu), `RouteRegistryTest` (rota nova), `GuestOpenApiSlice` (mock da M1). Removido: `BotWalletScreen.test.tsx` (a tela mínima da U1 saiu; os casos dela estão cobertos pelas telas novas).

### Ordem dos testes (honestidade sobre o "testes antes")

- **Step 3:** os testes do servidor foram escritos antes do código; a execução vermelha foi a falha de compilação do `test-compile` (`RealReleases.current` e `BotWalletViews.capSetBy` não existiam). Na primeira execução verde, um teste (`withoutTheAcceptanceOfTheCurrentTermsTheReadIsLocked`) falhou porque o teste usava uma pessoa com aceite da versão vigente; o teste foi corrigido para semear a pessoa sem aceite (o código não mudou).
- **Step 4:** testes escritos antes; vermelho por módulo inexistente. Na primeira execução verde, 1 de 18 falhou por erro no próprio teste (a vírgula estava no lugar errado da entrada); a entrada foi corrigida, sem mudar o código.
- **Step 5:** testes escritos antes; vermelho por módulos inexistentes (`base64`, `sign-transaction`, `signTransactionBytes`).
- **Step 12:** testes da faixa e do diálogo escritos antes; vermelho por módulo inexistente (`SigningBanner`). Os 29 passaram na primeira execução depois do código. O `quiet-api.test.ts` foi escrito nesta etapa, mas a segunda instância do `guestApi` já tinha sido feita no Step 7 (base), então ele passou na primeira execução.
- As demais etapas seguiram "implementar e depois testar", como o plano marca.

### Cobertura

| Parte | Linhas | Piso |
|---|---|---|
| `apps/web` | 94,6% (era 95,17%) | 80% — acima |
| `apps/admin` | 89,81% | 80% — acima |
| `apps/api` | **não medida** | 80% |

Nenhuma exclusão de cobertura nova e nenhum piso mexido. A mudança no `testPathIgnorePatterns` do Jest do web (`/e2e/(?!support/)`) só deixa rodar o teste da carteira de teste do e2e; a cobertura continua coletada só de `src/`. Na API não rodei o `verify` completo com JaCoCo (cuidado com a memória da máquina, como na U5); rodei os testes da Unit e os vizinhos (acima). A medida fica para o `verify` local com memória livre ou o job `backend` da CI.

## Texto para o responsável do projeto

Não editei `scripts/verify.sh` nem `.github/workflows/*`. O `ci.yml` não precisa de mudança.

### `scripts/verify.sh`

Na lista de specs do Playwright, acrescente `e2e/real-ui` **depois** de `e2e/real-ops` (o spec usa a carteira do bot em `AUTO_FULL` deixada pelo `real-swap` e a divergência resolvida pelo `real-ops`):

```bash
    e2e/reacceptance e2e/data-requests e2e/anonymization e2e/real-bot-wallet e2e/real-swap e2e/real-ops e2e/real-ui
```

O spec usa `SMOKE_WALLET_FILE`, `SMOKE_ADMIN_WALLET_FILE`, `ADMIN_BASE_URL` e `API_BASE_URL`, que o `verify.sh` já passa. Nenhuma variável, serviço ou segredo novo.

## Desvios e decisões

| Item | Decisão e motivo |
|---|---|
| CORS do administrador com `DELETE` | O app administrativo chama, de outra origem (3001 → 8080), as rotas `DELETE` da R4 da U5 (revogar liberação, desligar o kill switch REAL, desbloquear token). O `AdminCorsPolicy` só aceitava `GET`, `POST` e `PUT`, então o navegador recusaria o preflight e o cenário 5 do e2e não funcionaria. Acrescentei `DELETE` só no ramo com token; o ramo por cookie continua sem, e a política continua sem `PATCH` (o que o U6-NFR1.7 pede). Teste em `GuestCorsPolicyTest` e registro no adendo do contrato. |
| `capSetBy` sem migração | Não existe coluna de autoria do teto. O valor vem do último comando `LOWER_CAP` da carteira (só o administrador grava esse comando, U5 BR4.1): `ADMIN` enquanto o teto atual for igual ao valor desse comando; qualquer mudança da pessoa volta a `USER`. Caso raro: se a pessoa puser de volta exatamente o valor que o administrador definiu, a tela mostra `ADMIN`. |
| Acompanhamento da venda total | Não há rota de leitura da liquidação. A tela acompanha pela lista de saques (o fim da venda total cria um saque `TOTAL` novo) e, ao terminar, lê o P&L REAL: sem posições é "concluída"; com posições é `PARTIAL`, com a lista. Item aberto: uma rota `GET /api/v1/real/liquidations/{id}` deixaria isso exato. |
| "Última reconciliação" na visão geral | O contrato do convidado não traz a reconciliação da carteira do bot. A visão geral mostra "Saldo conferido em <hora>" (`balance.asOf`) e a divergência aparece pela pausa `DIVERGENCE`. |
| Contraste "nos dois temas" | Os dois apps só têm o tema escuro. O teste confere os dois usos do âmbar: texto âmbar sobre os fundos e texto escuro sobre o preenchimento âmbar do selo (todos acima de 4,5:1). |
| `RESTORE_NOT_CLEAN` | O `409` não traz a lista de carteiras. A tela explica que ainda há carteiras sem conferência limpa e manda ver as divergências. |
| Desbloqueio de token | A rota `DELETE /token-blocks/{mint}` não tem corpo; a tela pede só a confirmação, sem motivo (as demais ações pedem motivo de 1 a 500 caracteres, BR9.2). |
| Passo 1 da ativação | O bloqueio do novo aceite vale para todas as rotas, então o passo 1 aparece quando a leitura da liberação (pela instância sem o tratador) devolve `TERMS_REACCEPTANCE_REQUIRED`. Se o ambiente exigir a v3 e ela não for a versão vigente, a ativação mostra "Aceite os termos do REAL para continuar" (`TERMS_V3_MISSING`). |
| "Disponível para saque agora" | A visão da carteira não traz o máximo devolvido com divergência; a tela mostra o saldo livre com esse rótulo e, se o servidor limitar, o texto de `WITHDRAWAL_LIMITED_BY_DIVERGENCE` com o máximo do `detail`. |
| Anúncios do canal | Os anúncios de perda e de volta do canal saíram do `DashboardProvider` e ficaram no `LiveChannelAnnouncer`, no `AppShell`: agora valem em todas as telas com sessão. |
| Estado do REAL no cabeçalho | Menu, tag "PAPER" com o selo e faixa usam uma leitura por sessão (liberação e carteira) pela instância sem o tratador do novo aceite; a tela do modo atualiza o modo guardado em memória. São duas chamadas a mais por sessão. |
| `stream-client.ts` | Não precisou mudar: ele já descarta só os tipos fora de `EVENT_KINDS`, e os dois eventos do REAL entraram nessa lista. |
| Lista de usuários do administrador | O contrato não traz nome; a coluna "Usuário" mostra o `userId` abreviado. |
| Tela mínima da U1 | `BotWalletScreen.tsx`, `lamports.ts` e o teste deles foram removidos; `/real/carteira` redireciona para `/real`. |
| Correção depois da revisão | Na venda total, se a leitura dos saques de antes da venda falhar, o acompanhamento não dá mais a venda como terminada. Antes, um saque `TOTAL` antigo contava como novo. Agora a tela termina com "Ainda processando; acompanhe em Ordens". Coberto por um teste de regressão no `DepositWithdrawScreen.test.tsx`; as 16 verificações da tela, o lint e os tipos passaram. |
| `capSetBy` depois do expurgo | O `RealOpsPurgeJob` apaga comandos com mais de 13 meses. Depois disso, um teto definido pelo administrador aparece como `USER`. A correção é guardar a autoria numa coluna própria, na próxima migração do REAL. |
| Arquivo solto | Rodar `pnpm` dentro de `apps/web` deixa `apps/web/aidlc/` (arquivo de hook do AI-DLC); não é código da Unit e não foi apagado. |

## Itens abertos

- Aplicar no `verify.sh` a linha acima (responsável do projeto). Até lá o `real-ui` não roda na CI.
- Os specs `real-bot-wallet` (adaptado) e `real-ui` (novo) foram validados só por `tsc`, `eslint`, `prettier` e `playwright --list`; não rodei o `verify.sh`. A primeira execução real será na CI ou no `verify.sh` do responsável.
- Rodar o `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` completo para medir a cobertura da API (não medida nesta Unit).
- Avaliar uma rota de leitura da liquidação (`GET /api/v1/real/liquidations/{id}`) para o acompanhamento exato da venda total.
- A ordem no modo Assinada assinada pela faixa continua fora do e2e (limite do validador local; ver `docs/runbooks/real-ui.md`, seção 3).
- Os specs `real-swap` e `real-ops` mantêm as cópias locais das esperas do login; podem passar a usar `e2e/support/login-limit.ts`.

## Volta 1 do Build and Test (2026-11-20)

O Build and Test (Loop-back 1 em `construction/build-and-test/test-results.md`) mostrou que o sensor de rastreabilidade não lia o `traceability.json` desta Unit: 185 achados, porque os alvos eram texto livre (listas de arquivos, classes e testes) em vez de um arquivo, os IDs de NFR levavam o prefixo `U6-` e faltavam os critérios de aceite. O código de produto não mudou nesta volta, e as etapas 1 a 17 continuam como estão.

### Step 18 — Rastreabilidade no formato do sensor

- `traceability.json` reescrito em UTF-8, sem U+FFFD, no mesmo formato da U1 à U5: 108 linhas, uma por AC (15), BR (46) e NFRx.y (47) da Unit, com os IDs de NFR sem o prefixo `U6-`. Cada alvo `OK` é um único arquivo existente que implementa ou testa o ID; os demais arquivos e testes ficam na nota, com o mesmo mapeamento da tabela anterior.
- As linhas de cenário (`S1` a `S44`) saíram da tabela: o sensor não as pede, e os testes que as cobrem continuam citados nas notas das regras e dos critérios, com o número do cenário.
- **U6-NFR1.1b** e **U6-NFR3.5b** não viraram linhas próprias (o sensor não as pede): ficam na nota de NFR1.1 e de NFR3.5, com os arquivos e testes delas. **BR5.2b** continua como linha.
- Critérios de aceite das histórias da U6 (US1.1, US3.1, US3.2, US4.1): os alvos são as telas e os componentes desta Unit; a nota diz quando a regra no servidor é da U1 ou da U4 (recusa sem a v3, cancelamento no rebaixamento, envio da transação assinada).
- **AC1.1.3** fica `Deferred`: o texto final dos termos v3 (perda de todo o valor, responsabilidade compartilhada, sem promessa de lucro) não existe no catálogo (`apps/api/src/main/resources/terms` só tem v1 e v2) e depende da redação jurídica. A U6 mostra o texto vigente do catálogo no passo 1 da ativação, com a caixa "posso perder todo o valor depositado".
- `aidlc engine sensor-traceability --stage code-generation` passou sem achados (`pass: true`, 0 achados) no caminho temporário e no caminho do registro.
- `source-manifest.json` desta volta tem `writes` vazio, porque nenhum arquivo de código mudou.
- As etapas 1 a 17 estavam sem marca no plano; foram marcadas, porque estão no HEAD e verdes na CI 44228007586.

### Itens abertos atualizados

- O primeiro item aberto acima (linha do `e2e/real-ui` no `verify.sh`) está fechado: o responsável aplicou a linha, e o spec rodou na CI 44228007586.
- Novo item: o texto dos termos v3 com as três frases do AC1.1.3, para a redação jurídica.
