# Plano de Code Generation — U6 `u6-real-modes-ui` (Bolt B6)

## Summary

- Builds: as telas do REAL (app do convidado: ativação em três passos, visão geral com o teto e o P&L REAL, modo, faixa e diálogo de assinatura, ordens, depósito e saque; app do administrador: área REAL com usuários, liberação, teto, tokens, kill switch REAL, divergências e restauração), o canal ao vivo no layout raiz, `signTransaction` no `WalletPort` e o adendo de leitura do servidor (`GET /api/v1/real/release`, `serverTime`, `capSetBy`, `release`, `PATCH` no CORS do convidado)
- Touches: `apps/web` (`app/real/`, `app/layout.tsx`, `features/real/`, `features/live/`, `features/dashboard/`, `features/auth/wallets.ts`, `components/shell/`, `lib/time/`, `lib/encoding/`, `eslint.config.mjs`, `jest.setup.ts`, `e2e/real-bot-wallet/`, `e2e/real-ui/`, `e2e/support/`), `apps/admin` (`app/real/`, `features/real/`, menu, `eslint.config.mjs`, `jest.setup.ts`), `apps/api` (`botwallet.web`, `RealStateQuery`, `realexecution.web`, `identity.web.GuestCorsPolicy`, `route-registry.yml`, OpenAPI), `docs/runbooks/real-ui.md`, contrato (adendo da U6)
- Tests: cerca de 260 testes — dinheiro, assinatura byte a byte com todos os desfechos e adendo do servidor escritos antes do código; componentes e cliente com piso de 80% no `apps/web` e no `apps/admin`; teste das regras de lint com fixtures que falham; ativação pela tela nova e spec `real-ui` no validador local

Conversa: português (Brasil). Fontes:
- `construction/u6-real-modes-ui/functional-design/`: BR1 a BR9, S1 a S44, adendo de contrato;
- `construction/u6-real-modes-ui/nfr-requirements/`: U6-NFR*;
- `construction/u6-real-modes-ui/nfr-design/`: componentes e os ajustes da revisão (canal no layout raiz, `warn.ts`, `stream-client`, menu, segunda instância do `guestApi`, stubs de `<dialog>`);
- `construction/u6-real-modes-ui/infrastructure-design/`: e2e, estado de entrada e saída, limite da assinatura no modo Assinada;
- `inception/refined-mockups/`;
- `team.md` e `project.md`.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "custom",
  "source": "team",
  "ordering": "No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
  "scope": "classic",
  "test_strategy": "comprehensive",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: custom\n- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.\n- **Methodology evidence**: decisão do responsável do projeto na entrevista\n  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a\n  passar em testes escritos depois sobre o próprio código; com o teste escrito\n  antes, ele valida o requisito e não a implementação. Test-after no restante\n  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo. No\n  intent `261109-termos-lgpd` (Q5, opção A), o responsável do projeto estendeu a\n  ordem de testes antes do código à lógica de dados pessoais (anonimização,\n  exclusão, novo aceite dos termos e expurgos), porque ela toca tabelas\n  append-only como a trilha de auditoria e as ligadas ao ledger. No intent\n  `261113-trading-real-bot`, o NFR8 dos requisitos estende a ordem de testes\n  antes do código à lógica de carteira e saque, e o plano de Bolts pede os testes\n  de ataque da política do TxSigner escritos antes do código (B1) e os testes de\n  estados e idempotência da execução REAL antes do código (B4).\n- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`, também no\n  intent `261109-termos-lgpd` (Q4, opção A); o piso de cobertura abaixo é somado\n  a ela e nunca a substitui.\n- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no\n  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente\n  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam\n  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A\n  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo\n  no Maven e limite de cobertura do executor de testes do frontend). O piso não\n  pode ser reduzido para fazer uma etapa passar. O processo TxSigner também\n  segue o piso de 80%.\n- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou\n  mantém verdes os testes de:\n  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta\n    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que\n    também validam o tenant;\n  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L);\n  - deduplicação por chave de idempotência;\n  - violação de limites autorizados e controles de risco;\n  - dado de mercado ausente vira `unknown`, nunca zero;\n  - pausa do bot impede novas operações;\n  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só\n    acontece por lançamento compensatório;\n  - P&L com cenários determinísticos de resultado conhecido, em aritmética\n    decimal e unidades de token;\n  - REAL desligado por padrão: com a configuração padrão (API, worker e\n    TxSigner), nenhuma carteira do bot é criada, nenhum caminho assina ou envia\n    transação e nenhuma assinatura é pedida ao KMS;\n  - login sem reutilização de assinatura: nonce reutilizado ou assinatura\n    expirada são rejeitados, e a assinatura de login não serve para autorizar\n    trading;\n  - a anonimização não deixa o endereço da carteira em nenhuma tabela nem log\n    consultável;\n  - a versão nova dos termos exige novo aceite antes de qualquer outra rota,\n    **exceto** a leitura da carteira do bot (`GET /api/v1/real/bot-wallet`) e as\n    rotas de saque (`/api/v1/real/bot-wallet/withdrawals` e\n    `/api/v1/real/bot-wallet/withdrawals/{withdrawalId}`), que continuam\n    disponíveis sem o novo aceite para o usuário sempre poder tirar o próprio\n    dinheiro; o teste cobre as duas metades: as rotas da exceção respondem sem o\n    novo aceite e todas as demais respondem `403 TERMS_REACCEPTANCE_REQUIRED`;\n  - saque só para a carteira principal registrada: qualquer outro destino é\n    recusado pela API e pelo TxSigner, e o saque continua disponível com o kill\n    switch acionado, com o REAL desligado e sem a liberação vigente;\n  - a política de conteúdo do TxSigner (ADR-004) recusa toda transação fora da\n    lista: programa fora da lista, assinante ou pagador diferente da carteira do\n    bot, destino fora da carteira do bot ou da carteira principal, saída mínima\n    abaixo da cotação menos a derrapagem, troca de autoridade, fechamento de\n    conta para terceiros e valor acima do teto autorizado; cada recusa registra o\n    motivo;\n  - uma única transação por ordem REAL: repetir a ordem com a mesma\n    `Idempotency-Key`, reprocessar a decisão da fila ou repetir depois de uma\n    ordem ambígua nunca gera segunda transação.\n- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o\n  reproduz.\n- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.\n  Os critérios básicos de acessibilidade do beta definidos em `project.md`\n  continuam valendo.\n- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds\n  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam\n  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação\n  sobre PostgreSQL real em contêiner (por exemplo, Testcontainers) e não dependem\n  de estado compartilhado nem da ordem de execução. Fixtures nunca usam carteira,\n  mint ou dados pessoais reais. No REAL, a rede é o validador Solana local (com\n  contas clonadas para os swaps), o KMS é simulado no ambiente local e as\n  respostas da Jupiter e do RPC usadas em testes de unidade são gravadas; nenhum\n  teste da CI usa devnet, mainnet, KMS real ou chave real.\n- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no\n  Windows. O fim de linha é padronizado em LF por `.gitattributes` e\n  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.\n- **Frequência**: em toda mudança e no merge para `main` rodam os testes de\n  unidade e de integração de todas as partes (backend, `apps/web` e\n  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O\n  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; não há uma\n  suíte maior reservada ao merge.\n- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado\n  em silêncio.\n- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no\n  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.\n- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não\n  funcionais definam metas numéricas."
    }
  ],
  "obligations": {
    "strategy": "comprehensive",
    "strategy_volume": [
      "Ten to fifteen tests per component.",
      "Unit, integration, and E2E tests.",
      "Add performance and security tests when NFRs demand them."
    ],
    "scope_floor": [
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "custom",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Custom ordering - No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
      "Implementation and tests - preserve that exact ordering; do not convert it to layer-local TDD.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:b7f5625e55bdee4b1fae55cf7a9f270603b9853a8e8e627051890f728c613413",
  "contract_sha256": "sha256:98df078ab1ee44bde9eaa1e5c7f5f121064c1ad1d933fdf12d5563b3111d6400"
}
```

## Ordem aplicada

A U6 é quase toda interface: o padrão é implementar e depois testar (**[depois]**). Três partes tocam dinheiro, carteira e assinatura e têm os testes escritos **antes** do código (**[testes antes]**):
- o analisador e o formatador de dinheiro;
- a passagem da transação pela carteira sem alteração e os desfechos da assinatura;
- o adendo do servidor (leitura da liberação, `serverTime`, CORS).

## Etapas

- [x] **Step 1 — Estrutura e configuração [depois].**
  - Pastas `features/real`, `features/live`, `lib/time` e `lib/encoding` no web; `features/real` no admin.
  - Bloco do ESLint para `src/{app,features}/real/**` nos dois apps, que repete as restrições atuais (inclusive a de `EventSource`) e acrescenta:
    - `no-restricted-imports` das bibliotecas de transação;
    - `no-restricted-syntax` de `sendTransaction`, `sendRawTransaction` e das conversões numéricas;
    - `no-restricted-globals` e `no-restricted-properties` de armazenamento e `console`;
    - `react/no-danger`.
  - `money.ts` como exceção nomeada. Teste `real-lint.test.ts` com as fixtures que precisam falhar.
  - Stubs de `HTMLDialogElement.prototype.showModal` e `close` no `jest.setup.ts` dos dois apps.
  - Teste de dependências (lista permitida) e teste da lista de `NEXT_PUBLIC_*`.

  *(U6-NFR1.1b, U6-NFR1.2, U6-NFR1.3, U6-NFR1.5, U6-NFR1.9, U6-NFR7.3, U6-NFR8.6)*
- [x] **Step 2 — Runner de testes.** Confirmar os comandos por escopo (`unit-test-instructions.md`) nos dois apps e na API com um teste vazio. *(Testing Contract)*
- [x] **Step 3 — Adendo do servidor [testes antes].**
  - Testes primeiro:
    - `GET /api/v1/real/release`: liberação em cada estado, outra conta, REAL desligado (`403`) e sem o novo aceite;
    - `serverTime` com relógio fixo;
    - `capSetBy` e `release` no `BotWalletView`;
    - `GuestCorsPolicy` com `PATCH`: aceito com token; recusado no ramo por cookie; administrador sem mudança.
  - Depois:
    - `readRelease` em `RealStateQuery` e o controlador no `botwallet.web`;
    - a entrada no `route-registry.yml` (`scope: CLIENT`, `negativeTest`);
    - `RealSigningSession` no `realexecution.web`;
    - o CORS em `identity.web`.
  - OpenAPI regenerado; `gen:api` e `check:api` nos dois apps.

  *(adendo, U6-NFR1.6, U6-NFR1.7, U6-NFR5.2, U6-NFR8.3, U6-NFR8.7)*
- [x] **Step 4 — Dinheiro [testes antes].**
  - Testes primeiro de `parseSol`, `formatSol`, `formatToken` e `toBaseUnits`:
    - 0, 9 casas e 10 casas;
    - vírgula, ponto, espaços e sinal;
    - o máximo de 10 SOL;
    - valores acima de 2^53;
    - o tipo `Unknown`.
  - Depois `features/real/money.ts` e `api.ts`, só com `bigint`; a mesma implementação vai copiada e testada no admin.

  *(BR5.1, BR5.2, BR8.2, BR8.7, U6-NFR7.1, U6-NFR7.2, U6-NFR7.5)*
- [x] **Step 5 — Carteira e assinatura da transação [testes antes].**
  - Testes primeiro:
    - a transação dada e a recebida comparadas byte a byte (a partir do primeiro byte depois das assinaturas);
    - carteira sem `solana:signTransaction`;
    - base64 próprio.
  - Depois:
    - `WalletPort.canSignTransaction` e `signTransaction`;
    - `lib/encoding/base64.ts`;
    - a carteira de teste do e2e com `solana:signTransaction` na lista de features da conta, devolvendo a transação serializada inteira, e o teste `e2e/support/test-wallet.test.ts`.

  *(BR3.1, BR3.6, U6-NFR1.1, U6-NFR8.2)*
- [x] **Step 6 — Canal ao vivo no layout raiz [depois].**
  - `LiveChannelProvider` no `app/layout.tsx`, dentro do `SessionProvider`, com:
    - uma conexão por pessoa;
    - `status`, `outdated`, `retryNow`, `subscribe` e `onReconnected`;
    - anúncios de perda e de volta do canal.

    Ele não abre fora da sessão, em `/novo-aceite` nem com o novo aceite pendente.
  - `warn.ts`: `EVENT_KINDS` com os eventos `real.*`; o `stream-client` deixa de descartá-los; `envelopes.ts` valida os dados.
  - O `DashboardProvider` passa a assinar pelo provedor. Os testes do painel montam o provedor de teste; o de BR5.6 passa a conferir o cancelamento das assinaturas.

  *(BR3.5, U6-NFR4.1)*
- [x] **Step 7 — Base das telas [depois].**
  - Textos: `messages.ts`, com a tabela de BR8.8 e os textos de `pausedReason`.
  - Componentes:
    - `RealErrorNotice`, com o `correlationId`;
    - `RealBadge`, com o contraste testado a partir dos tokens;
    - `PauseBanner`, `SolAmountInput`, `MoneyConfirmDialog` e `OrderStateBadge`.
  - Hooks e tempo:
    - `useMoneyAction`: chave por corpo, botão desabilitado e sem reenvio automático;
    - `useTracking`: a cada 3 s, por até 2 min, uma leitura por vez;
    - `lib/time/countdown.ts`.

  *(BR6.1 a BR6.3, BR7.1, BR8.1, BR8.3, BR8.4, BR8.8, U6-NFR3.3, U6-NFR3.4, U6-NFR4.2, U6-NFR4.6, U6-NFR5.1, U6-NFR6.2, U6-NFR6.4, U6-NFR7.4, U6-NFR7.6)*
- [x] **Step 8 — Menu e cabeçalho [depois].**
  - Seção `'real'` em `PrimaryNav` (`ShellSection`, `NAV_ITEMS`), só com o REAL ligado.
  - Tag "PAPER" com o `RealBadge` no cabeçalho.
  - `SigningBanner` dentro do `AppShell`.
  - `AppShell.test.tsx` cobre o menu, a tag e a faixa.

  *(BR1.1, BR7.1)*
- [x] **Step 9 — Ativação R1 [depois].**
  - `/real/ativacao` com os três passos e o estado da liberação de `/real/release`.
  - Redirecionamento de `/real/carteira` e das rotas sem carteira.

  *(BR1.2, BR1.3, BR2, S2 a S7, S43)*
- [x] **Step 10 — Visão geral e teto R2 [depois].**
  - `/real` com o saldo, a pausa, a liberação e a última reconciliação.
  - Teto: baixar com `PATCH`; subir com `RAISE_CAP`; "Ajustado pelo administrador" quando for o caso.
  - `RealPnlCard` no painel, separado do PAPER.

  *(BR5, BR7.2, BR8.2, S8 a S11, S30 a S32, S36)*
- [x] **Step 11 — Modo R3 [depois].** `/real/modo` com o `ModeSelector`: subir com `RAISE_MODE`, voltar com confirmação, "Em breve" e as recusas em texto. *(BR4, S17 a S20, S40)*
- [x] **Step 12 — Faixa e diálogo de assinatura R4 [testes antes].**
  - Testes primeiro:
    - faixa no mesmo ciclo do evento e na releitura (carga, troca de rota, reconexão);
    - contagem com relógio defasado em -30 s, 0 s e +30 s;
    - `aria-live` só nos marcos;
    - desfechos: aceita, `SIGNING_DEADLINE_EXPIRED`, recusa na carteira, falha do `reject`, `SIGNED_MESSAGE_MISMATCH`, `SIGNING_POLICY_REFUSED` e ordem fora de `QUOTED`;
    - nada lido nem aberto sem o novo aceite;
    - segunda instância do `guestApi`.
  - Depois o `SigningBanner`, o `SigningDialog` e o `useSigningSession`.

  *(BR3, S12 a S16, S38, S39, S41, U6-NFR3.1, U6-NFR3.2, U6-NFR4.5, U6-NFR6.3)*
- [x] **Step 13 — Ordens R5 [depois].** `/real/ordens` com:
  - paginação por cursor (`limit=20`);
  - selo de estado;
  - assinatura e link por rede;
  - datas no fuso;
  - cartões abaixo de 768 px.

  *(BR8.4 a BR8.7, S26 a S29)*
- [x] **Step 14 — Depósito e saque R6 [depois].**
  - `/real/deposito-saque` com:
    - endereço e "Copiar";
    - saque com confirmação e acompanhamento;
    - vender tudo e sacar, com `PARTIAL`;
    - o saque sempre habilitado.
  - A exceção do novo aceite usa a segunda instância do `guestApi` e a faixa de aviso.

  *(BR1.4, BR6, S21 a S25, S42, S44, U6-NFR4.4)*
- [x] **Step 15 — Administrador A1 [depois].**
  - `apps/admin` `/real` com:
    - usuários e detalhe;
    - tokens;
    - kill switch REAL;
    - divergências, com a compensação preenchida pela medida;
    - restauração.
  - `AdminActionDialog`: motivo de 1 a 500 caracteres, confirmação e chave por corpo.
  - Menu "REAL" separado de "Kill switch", que é o do PAPER.

  *(BR9, S33 a S35, U6-NFR1.8, U6-NFR1.10, U6-NFR1.11, U6-NFR3.6)*
- [x] **Step 16 — Ponta a ponta [depois].**
  - `e2e/support/login-limit.ts`, extraído do `bot-wallet.spec.ts`.
  - O `e2e/real-bot-wallet` passa a ativar pela `/real/ativacao` com o teclado e a sacar pela `/real/deposito-saque`.
  - Spec `e2e/real-ui/`:
    - estado de entrada conferido no `beforeAll`;
    - cenários 1 a 6;
    - kill switch dentro de `try/finally`;
    - estado de saída igual ao de entrada.

  *(U6-NFR6.1, U6-NFR6.5, U6-NFR6.6, U6-NFR8.5, infrastructure-specification)*
- [x] **Step 17 — Documentação e rastreabilidade.**
  - Runbook `docs/runbooks/real-ui.md`: estados da tela, rollback parcial e limite da assinatura no modo Assinada no e2e.
  - Adendo da U6 no `contract-summary.md`.
  - `code-summary.md`, com o texto pronto da linha do `verify.sh` (`e2e/real-ui` depois de `e2e/real-ops`).
  - `traceability.json` e `source-manifest.json`.

## Volta 1 do Build and Test (2026-11-20)

O Build and Test (Loop-back 1 em `construction/build-and-test/test-results.md`) mostrou que o sensor de rastreabilidade não lê o formato da tabela desta Unit. A tabela tem 185 achados: faltam ACs e NFRx.y, e os alvos são texto livre em vez de um arquivo. Todas as metas desta Unit já estão cumpridas. As etapas 1 a 17 continuam como estão.

- [x] **Step 18 — Rastreabilidade no formato do sensor.**
  - Reescrever `traceability.json` no formato que o `aidlc engine sensor-traceability` lê: uma linha por AC, BR e NFRx.y da Unit, com os IDs como no requisito (sem o prefixo `U6-`) e um arquivo existente por alvo `OK`. Os outros arquivos e testes ficam numa nota.
  - Gravar o arquivo em UTF-8.
  - Rodar o sensor até passar e atualizar o `code-summary.md`.

## Volta 2 do Build and Test (2026-11-20)

O Build and Test (Loop-back 2 em `construction/build-and-test/test-results.md`) deixou o AC1.1.3 como não cumprido. Os termos não dizem que a pessoa pode perder todo o valor depositado, que a carteira do bot tem responsabilidade compartilhada e que não há promessa de lucro. Além disso, o rascunho v2 ainda diz que tudo é simulado e que não movimentamos fundos.

O responsável pediu que os textos fossem escritos aqui e informou que o advogado deu autorização. As etapas 1 a 18 continuam como estão.

- [x] **Step 19 — Versão v3 dos termos com o REAL [testes antes].**
  - **Testes primeiro.** Escrever os testes de conteúdo e de catálogo antes dos textos:
    - os termos da v3 contêm as três frases do AC1.1.3: perda de todo o valor depositado, responsabilidade compartilhada da carteira do bot e nenhuma promessa de lucro;
    - nenhum arquivo da v3 diz que tudo é simulado, que o REAL está indisponível ou que não movimentamos fundos;
    - a v3 é a versão vigente e a única com `current=true`;
    - ativar a v3 exige novo aceite em todas as rotas, menos na leitura da carteira do bot e nas rotas de saque (teste obrigatório por risco);
    - os termos proibidos do web continuam fora dos textos.
  - **Textos.** Criar a pasta `apps/api/src/main/resources/terms/v3/` com termos, aviso de risco, política de privacidade, os dois resumos e a tabela de retenção, partindo da v2. Os textos devem:
    - descrever o PAPER e o REAL como existem hoje: a carteira do bot com teto, a chave no KMS, o saque só para a carteira principal, os três modos, a execução pela Jupiter, os limites e o kill switch;
    - trazer as três frases do AC1.1.3;
    - manter na tabela de retenção as linhas da v2, inclusive a do REAL;
    - ser escritos em português simples, sem prometer lucro nem garantia de detecção de fraude.
  - **Versões.**
    - A v2 passa a `current=false`. Ela fica como rascunho nunca publicado, sem revisão e sem efeito.
    - O `version.properties` da v3 recebe `effectiveAt` e `legalReviewedAt`/`legalReviewedBy`, com o nome e a data que o responsável informar na aprovação deste plano. Sem esses dois campos, a v3 não ativa.
  - **Fechamento.** Atualizar `docs/api/u1-legal-documents.md`, o `code-summary.md`, o `traceability.json` (AC1.1.3 `OK`) e o `source-manifest.json`.

  *(AC1.1.3, BR2.1)*

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US1.1 | 9, 16 |
| US1.2 (tela) | 9, 16 |
| US2.1 (campo do teto) | 4, 10 |
| US3.1 | 11, 16 |
| US3.2 | 5, 12 |
| US4.1 | 13, 16 |
| US4.2 (cartão) | 10 |
| US5.1, US5.2 (tela) | 14 |
| US7.1 a US7.3, US8.1, US8.2 (administrador) | 15, 16 |

## Fora desta Unit

| Item | Onde fica |
|---|---|
| Ordem no modo Assinada assinada pela faixa no e2e | precisa de uma nova gravação de rota; provada por testes de componente e pelos ITs da U4 |
| Saídas automáticas (modo "Automática nas saídas") | U7 |
| `verify.sh` | o responsável do projeto, com o texto pronto |
