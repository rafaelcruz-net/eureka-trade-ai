# Plano de Code Generation — U1 `u1-bot-wallet` (esqueleto, Bolt B1)

## Summary

- Builds: carteira do bot de ponta a ponta no validador local — TxSigner (processo novo), módulo `botwallet`, ledger REAL, auditoria tipada, depósito, saque, tela mínima e o spec de ponta a ponta, começando pelas duas provas técnicas
- Touches: `apps/txsigner/` (novo), `apps/api/src/main/java/com/memetrade/botwallet/` (novo), `ledger`, `audit`, `identity`, migrações Liquibase, `apps/web` (tela mínima e spec), `infra/` (kms-sim, Compose de teste, Terraform), `docs/`
- Tests: cerca de 160 testes — unidade e integração (JUnit 5, Testcontainers) no TxSigner e no monólito, com os testes de ataque e de carteira/saque escritos antes do código, Jest na tela, um spec Playwright de ponta a ponta, e piso de 80% em cada artefato

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/functional-design/` (entities, rules BR1.1–BR7.1, functional-spec F1–F6), `construction/u1-bot-wallet/nfr-requirements/`, `construction/u1-bot-wallet/nfr-design/`, `construction/u1-bot-wallet/infrastructure-design/` (inclusive o adendo), `inception/contract-design/contract-summary.md` (R1, R2, M1–M3, M8, M9), `inception/units-generation/unit-of-work.md`, `inception/requirements-analysis/requirements.md` e as regras de `team.md` e `project.md`.

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

O contrato manda escrever os testes **antes** do código na lógica da carteira do bot, do saque, do ledger REAL (núcleo financeiro), da política de assinatura do TxSigner e da idempotência; nas demais camadas (adaptadores, web, configuração, tela) a ordem é implementar e em seguida testar. As etapas abaixo marcam cada caso como **[testes antes]** ou **[depois]**.

## Etapas

### Fase A — Estrutura e provas técnicas

- [x] **Step 1 — Estrutura do projeto.** Criar `apps/txsigner/` (Maven próprio, Java 21, Spring Boot mínimo, sem SDK Solana, enforcer e ArchUnit próprios, JaCoCo com piso de 80%); criar o pacote `com.memetrade.botwallet` (`api`, `domain`, `infra`, `web`) e a regra ArchUnit dele; acrescentar o flag `memetrade.real.enabled=false` e `txsigner.real.enabled=false` nas configurações padrão. *(US1.2, NFR1.3, NFR8.2)*
- [x] **Step 2 — Runner de testes.** Confirmar os comandos de teste por escopo da Unit (ver `unit-test-instructions.md`) rodando um teste vazio de cada artefato antes do primeiro teste de regra. *(Testing Contract)*
- [x] **Step 3 — Prova técnica 1: KMS Ed25519 [testes antes].** Em código de teste do TxSigner, contra o `kms-sim` e, quando a conta AWS existir, contra o KMS real com um teste marcado `@Tag("aws-proof")` fora da CI: a chave nasce com as quatro etiquetas, a assinatura é Ed25519 de 64 bytes aceita pelo validador local numa transferência, alterar etiqueta é recusado, criar chave sem `mainWalletMac` é recusado. Resultado registrado em `docs/proofs/kms-ed25519.md`. Se falhar, o Bolt para (bolt-plan). *(P0, NFR1.1, infra Q3)*
- [x] **Step 4 — Prova técnica 2: transação da Jupiter sem SDK [testes antes].** Em código de teste: uma transação de swap gravada da Jupiter (fixture) é decodificada pelo decodificador próprio, recebe a assinatura e roda no validador com as contas clonadas. Resultado em `docs/proofs/jupiter-tx.md`. *(P0, TR-C03, TR-C05)*

### Fase B — TxSigner (processo próprio)

- [x] **Step 5 — Codec Solana mínimo [testes antes].** Base58, leitura e escrita de mensagem legada e v0 (cabeçalho, contas, blockhash, instruções, tabelas de endereço), compact-u16; testes com vetores conhecidos e entradas malformadas (tamanho, alfabeto, estouro). *(NFR1.3, BR5.1)*
- [x] **Step 6 — `ConsentVerifier` [testes antes].** Mensagem canônica de 10 linhas, Ed25519 nativo, `purpose`, `bot-wallet`, teto ≤ 10 SOL, rede, validade e nonce de uso único no banco do TxSigner; testes de mensagem de login, nonce repetido, vencido, rede errada, campos trocados. *(BR1.5, BR1.8, BR5.3, AC1.2.3)*
- [x] **Step 7 — `WithdrawalPolicy` [testes antes].** Forma exata de BR5.1 com todos os códigos de recusa; testes de ataque: Assign/Allocate/CreateAccount/TransferWithSeed escondidos, duas transferências, nonce durável, ALT, preço acima do teto de taxa, segundo assinante, outro pagador, destino estranho, valor ou taxa diferentes, contexto ausente, tipo SWAP antes da U4. *(BR5.1, BR5.2, NFR1.4, NFR4.3, AC5.1.1)*
- [x] **Step 8 — `SigningService`, `KeyService` e repositórios [testes antes nas regras; depois no adaptador].** Idempotência por `signingRequestId` + `messageDigest` (REQUEST_MISMATCH), criação idempotente por `creationRequestId`, etiquetas na criação com `mainWalletMac` (HMAC do KMS), flag desligado só assina WITHDRAWAL, SigningRecord e evento por decisão; adaptador do KMS (AWS SDK v2, só o cliente KMS) e verificação de produção (endpoint padrão ou VPC da conta, conta do STS, profile `kms-sim` ausente). *(BR1.6, BR1.10, BR5.2, NFR1.6, NFR1.11)*
- [x] **Step 9 — Rotas internas R1 [depois].** `POST /internal/v1/wallets`, `POST /internal/v1/wallets/{addr}/consents`, `POST /internal/v1/signatures`, `GET /internal/v1/wallets`, `GET /internal/v1/signatures`, `POST /internal/v1/recovery/wallets`; credencial de serviço com AWSCURRENT/AWSPREVIOUS e releitura a cada 5 min; 401/403; nenhuma resposta com `kmsKeyRef`. Testes de integração com Testcontainers. *(R1, NFR1.5, NFR1.7)*
- [x] **Step 10 — Recuperação e trabalhos do TxSigner [testes antes].** Modo de recuperação (5 min, reconstrução pelas etiquetas e `VerifyMac`, teto 0 e modo Assinada, bloqueio por rede ou MAC divergentes, `recoveryCompletedAt`), limpeza de nonces e órfãs "chave sem política" com ShedLock no banco do TxSigner; métricas do TxSigner. *(NFR4.13, NFR5.5, NFR5.6, NFR5.15)*
- [x] **Step 11 — Migrações do TxSigner [depois].** Liquibase próprio (`apps/txsigner/src/main/resources/db/`): WalletPolicy, SignerUsedNonce, SigningRecord, shedlock; usuário e esquema `txsigner`. *(entities)*

### Fase C — Monólito: ledger REAL, auditoria, identidade

- [x] **Step 12 — Migrações da aplicação [depois].** Relaxar o `CHECK (mode='PAPER')` só nas tabelas do ledger para aceitar REAL (expand-contract), novos `entry_kind`, `bot_wallet_id`, chave única de `idempotency_key`; tabelas `bot_wallet_account`, `real_preferences`, `consent_challenge`, `wallet_consent`, `used_nonce`, `real_release`, `observed_deposit`, `withdrawal` (com `attempts`, `next_attempt_at`, `signed_transaction`, `message_digest`), `reconciliation_mark`; índices de `performance-design.md`. *(entities, NFR3.1)*
- [x] **Step 13 — Ledger REAL (M2) [testes antes].** Convenção de sinal e de tipos (BR3.5), cálculo de total, reservado, livre, operável e acima do teto (BR3.3, BR3.4), posições e ordens em andamento lidas do próprio ledger; UPDATE e DELETE falham; separação REAL/PAPER. *(BR3.3–BR3.5, BR6.3, AC2.2.2, AC2.2.3, NFR2.1)*
- [x] **Step 14 — Auditoria tipada (M8) [testes antes].** Validador aceita `txSignature`, `mint`, `botWalletAddress` só nas chaves tipadas, carteira principal só como HMAC, `details` mantém a regra atual; máscara de log. *(BR6.1, BR6.2, AC8.5.1, AC8.5.2, NFR1.10)*
- [x] **Step 15 — Identidade: termos v3 e consentimento (M9) [testes antes].** Versão v3 no catálogo de termos (texto do REAL), `termsV3Accepted`, `verifyConsent` (Ed25519 nativo), exceção do novo aceite para `GET /api/v1/real/bot-wallet` e saques. *(BR1.2, BR1.5, AC1.1.1, AC1.1.2, regra de team.md)*

### Fase D — Monólito: BotWallet

- [x] **Step 16 — Domínio da BotWallet [testes antes].** Desafio e ativação (BR1.1–BR1.10, estado IN_USE e volta a PENDING em 30 s), preferências e pausa (BR2.1–BR2.3), liberação mínima (BR7.1), saque com estados, taxa calculada, reserva, trava por carteira, mínimo isento, divergência, idempotência pelo mecanismo comum, FAILED só com os dois provedores (BR4.1–BR4.9, NFR4.2–NFR4.4, NFR4.10), isolamento (BR6.4). *(US1.2, US5.1, AC1.2.1–AC1.2.5, AC5.1.1–AC5.1.4)*
- [x] **Step 17 — ChainWatcher (M3) [depois].** Porta da rede com dois provedores, limitador por provedor (80/20), circuito por provedor, `getMultipleAccounts`, `getSignatureStatuses` em lote, entradas por endereço, envio, altura em `finalized`; dublê para os testes. *(NFR3.7, NFR4.11, reliability-design)*
- [x] **Step 18 — Cliente do TxSigner e trabalhos do worker [testes antes nas regras; depois na infraestrutura].** Cliente HTTP com circuito (só falhas técnicas), varredura de depósitos por cursor com prioridade (BR3.1, BR3.2), acompanhamento de saques com `SKIP LOCKED`, desafio preso, órfãs "política sem carteira", reconciliação depois de restaurar o banco (SigningRecords mais a rede). *(AC2.2.1, NFR4.6, NFR4.13)*
- [x] **Step 19 — Rotas REST R2 [depois].** `/api/v1/real/consents/challenge`, `/api/v1/real/bot-wallet` (GET, POST), `/preferences`, `/resume`, `/deposits`, `/withdrawals` (GET, POST, GET por id); `REAL_DISABLED` só no que abre risco; `REAL_NOT_RELEASED`; limites de uso; ProblemDetail; OpenAPI gerado e `check:api`. Testes de integração, inclusive isolamento (404) e as duas metades da exceção dos termos. *(R2, NFR1.9, NFR6.1)*
- [x] **Step 20 — Métricas do monólito [depois].** Métricas de `observability-design.md` sem rótulos de endereço, tenant ou usuário. *(NFR5.1–NFR5.4, NFR5.16)*

### Fase E — Tela mínima e ponta a ponta

- [x] **Step 21 — Tela mínima no `apps/web` [depois].** Página `/real/carteira` com ativação (assinatura pela extensão), endereço, saldo (livre, reservado, reserva para taxas, acima do teto) e saque; estados de carregando, erro e indisponível em texto; `data-testid`; tipos gerados do OpenAPI; testes Jest. *(US1.2, US2.2, US5.1, NFR7.1)*
- [x] **Step 22 — Infra de teste [depois].** `infra/kms-sim/` (contêiner com criação, assinatura, etiquetas imutáveis, MAC), serviços `txsigner`, `txsigner-migrate`, `kms-sim`, `solana-validator` no Compose (TxSigner sem porta publicada), semente do fundador de teste com liberação vencida pelo relógio de teste, `.env.example` com os nomes sem valores. *(infra Q1, BR7.1)*
- [x] **Step 23 — Spec de ponta a ponta [depois].** `apps/web/e2e/real-bot-wallet/`: ativar, depositar 1 SOL da carteira principal de teste (gerada no código de teste), ver o saldo, sacar de volta, confirmar que o saque para outro endereço é recusado. O fundador acrescenta o spec e o validador ao `scripts/verify.sh`; o texto pronto vai no `code-summary.md`. *(team.md Walking Skeleton)*

### Fase F — Infraestrutura futura, pipeline e documentação

- [x] **Step 24 — Terraform [depois].** `infra/terraform/` com os módulos `network`, `data`, `kms`, `services`, `observability`, incluindo as duas correções da revisão do Infrastructure Design: a política de cada chave nega `Sign` (e `GenerateMac` na chave HMAC) a todo principal que não seja o papel do TxSigner, e o endpoint de VPC `monitoring` com o grupo de segurança dos endpoints; teste do JSON das políticas; `terraform fmt` e `validate` locais. Não é aplicado. *(infrastructure-specification, NFR1.2)*
- [x] **Step 25 — Regras de alerta [depois].** `infra/observability/` com as regras do `monitoring-design.md` e `promtool check rules`. *(NFR5.7–NFR5.19)*
- [x] **Step 26 — Texto pronto para o fundador [depois].** Mudanças propostas para `.github/workflows/ci.yml` (job do TxSigner, Checkov, `terraform validate`, `promtool`, ações por SHA, `permissions: contents: read`) e para `scripts/verify.sh`, entregues em `code-summary.md`, sem editar esses arquivos. *(cicd-pipeline, team.md)*
- [x] **Step 27 — Documentação e rastreabilidade.** `docs/adr/` com as decisões novas do TxSigner, `docs/runbooks/txsigner-recovery.md` (restauração, recuperação, giro da credencial), atualização do contrato (R1 interno e X4) no `contract-summary` por adendo, `code-summary.md`, `traceability.json` e `source-manifest.json`. *(Documentation and traceability)*

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US1.1 (parte do servidor) | 15, 19 |
| US1.2 Ativar a carteira do bot | 3, 6, 8, 9, 16, 19, 21, 23 |
| US2.2 Depositar SOL | 13, 17, 18, 21, 23 |
| US5.1 Sacar para a carteira principal | 5, 7, 8, 13, 16, 17, 18, 19, 21, 23 |
| US8.5 Auditar o REAL | 14, 8, 16 |

## Fora desta Unit

Swaps e o tipo SWAP no TxSigner (U4), reconciliação periódica e compensação (U5), telas completas e o texto final dos termos v3 (U6), dados de mercado reais (U2).
