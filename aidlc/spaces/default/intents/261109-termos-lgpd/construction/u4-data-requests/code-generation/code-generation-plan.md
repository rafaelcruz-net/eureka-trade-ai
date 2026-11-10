# Plano de código — U4 `u4-data-requests`

## Summary

- Builds: pedidos de titular de ponta a ponta. Inclui criação e lista na Conta, atendimento no app do administrador, registro por e-mail com verificação por assinatura, resumo de Acesso, aviso de vencimento, expurgo de 12 meses, métricas e saúde.
- Touches: `apps/api` (`privacy` domínio, infra e web; interfaces J1 a J4 em `identity`, `bots`, `ledger` e `feedback`; migração `0014`; `application.yml`; OpenAPI), `apps/web` (seção "Meus dados (LGPD)"), `apps/admin` (telas de pedidos), `docker-compose.yml`, `.env.example`, `scripts/load-test.sh`, `scripts/load/` e os contratos P1, P2 e J1.
- Tests: cerca de 160 testes novos (JUnit 5 e Testcontainers, Jest nos dois apps, Playwright e o cenário de carga `data-requests`), com os testes das regras de dados pessoais escritos antes do código e o piso de 80% de linhas.

Conversa: português (Brasil); código, nomes e logs em inglês. Fontes:
- `construction/u4-data-requests/functional-design/` (BR4.1 a BR4.20);
- `nfr-requirements/` e `nfr-design/`;
- `infrastructure-design/`;
- `inception/contract-design/contract-summary.md` (P1, P2, J1 a J4);
- `inception/user-stories/stories.md` (US5.1 a US5.9).

## Testing Contract

```json
{
  "version": 1,
  "methodology": "custom",
  "source": "team",
  "ordering": "No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) e na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
  "scope": "feature",
  "test_strategy": "comprehensive",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: custom\n- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) e na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.\n- **Methodology evidence**: decisão do responsável do projeto na entrevista\n  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a\n  passar em testes escritos depois sobre o próprio código; com o teste escrito\n  antes, ele valida o requisito e não a implementação. Test-after no restante\n  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo. No\n  intent `261109-termos-lgpd` (Q5, opção A), o responsável do projeto estendeu a\n  ordem de testes antes do código à lógica de dados pessoais (anonimização,\n  exclusão, novo aceite dos termos e expurgos), porque ela toca tabelas\n  append-only como a trilha de auditoria e as ligadas ao ledger.\n- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`, também no\n  intent `261109-termos-lgpd` (Q4, opção A); o piso de cobertura abaixo é somado\n  a ela e nunca a substitui.\n- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no\n  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente\n  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam\n  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A\n  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo\n  no Maven e limite de cobertura do executor de testes do frontend). O piso não\n  pode ser reduzido para fazer uma etapa passar.\n- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou\n  mantém verdes os testes de:\n  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta\n    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que\n    também validam o tenant;\n  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L);\n  - deduplicação por chave de idempotência;\n  - violação de limites autorizados e controles de risco;\n  - dado de mercado ausente vira `unknown`, nunca zero;\n  - pausa do bot impede novas operações;\n  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só\n    acontece por lançamento compensatório;\n  - P&L com cenários determinísticos de resultado conhecido, em aritmética\n    decimal e unidades de token;\n  - REAL desligado por padrão: nenhum caminho assina ou envia transação com a\n    configuração padrão;\n  - login sem reutilização de assinatura: nonce reutilizado ou assinatura\n    expirada são rejeitados, e a assinatura de login não serve para autorizar\n    trading;\n  - a anonimização não deixa o endereço da carteira em nenhuma tabela nem log\n    consultável;\n  - a versão nova dos termos exige novo aceite antes de qualquer outra rota.\n- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o\n  reproduz.\n- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.\n  Os critérios básicos de acessibilidade do beta definidos em `project.md`\n  continuam valendo.\n- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds\n  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam\n  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação\n  sobre PostgreSQL real em contêiner (por exemplo, Testcontainers) e não dependem\n  de estado compartilhado nem da ordem de execução. Fixtures nunca usam carteira,\n  mint ou dados pessoais reais.\n- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no\n  Windows. O fim de linha é padronizado em LF por `.gitattributes` e\n  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.\n- **Frequência**: em toda mudança e no merge para `main` rodam os testes de\n  unidade e de integração de todas as partes (backend, `apps/web` e\n  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O\n  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; não há uma\n  suíte maior reservada ao merge.\n- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado\n  em silêncio.\n- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no\n  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.\n- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não\n  funcionais definam metas numéricas."
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
      "Meet an 80% line-coverage floor.",
      "Run the selected tests in CI before merge."
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
      "Custom ordering - No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) e na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
      "Implementation and tests - preserve that exact ordering; do not convert it to layer-local TDD.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:8467b706291a932a74a1bf0c04b264fb1b9c8c0cf733a43b7b50c1a1ab9dce39",
  "contract_sha256": "sha256:2aa78796ba27ef05340ab72a99bccac6e4215a7a0d7e5c2e09be6e543a6df9e4"
}
```

## Passos

### Preparação

- [x] **Passo 1** — Estrutura e prontidão:
  - migração `0014-data-requests.sql`, só aditiva, com rollback e registrada no `db.changelog-master.yaml`. Ela traz as tabelas `data_subject_request`, `data_subject_verification` e `privacy_job_run`, os índices (inclusive o único parcial `uq_open_request_per_type`) e as concessões;
  - códigos novos em `ErrorCode`;
  - ações `DATA_REQUEST_*` em `AuditActions`;
  - entradas novas de idempotência;
  - propriedades `memetrade.privacy.*`;
  - prefixo `/api/v1/privacy/` na trava da U3;
  - pool do agendador 21 (`application.yml`, `RuntimeSettingsGuard`, Compose, `.env.example` e os testes que fixam o valor);
  - confirmar os comandos da Unit.

  (todas; NFR5.1)
- [x] **Passo 2** — Interfaces públicas novas, com testes depois de cada uma:
  - **J1, `identity.api.PersonDataPort`:** `personSummary(tenantId, userId)`, `findAccount(userId)` e `verifyAccountSignature(userId, message, signature)`. A conferência recusa toda mensagem que não comece pela linha de verificação e usa Ed25519 nativo;
  - **J2:** `BotOwnerDataPort.countOwnerBots`;
  - **J3:** `FeedbackOwnerDataPort.countByUser`;
  - **J4:** `ledger.api.PaperOrderCountPort.countPaperOrders`.

  Os blocos J1 a J4 do resumo de contratos são atualizados no mesmo commit. (BR4.8, BR4.10, BR4.12)

### Pedidos (testes antes do código: dados pessoais)

- [x] **Passo 3** — Testes escritos antes, que falham (Red) antes do Passo 4:
  - **`DataRequestServiceTest`** (BR4.1, BR4.2, BR4.4, BR4.5, BR4.9, BR4.10):
    - criação com prazo de 15 dias pelo relógio;
    - `400` sem gravar; `409` com aberto do mesmo tipo, também no registro por e-mail;
    - isolamento com `404`;
    - todas as transições permitidas e proibidas, inclusive `RECEIVED` para `IN_PROGRESS` recusado em EMAIL de ACCESS ou DELETION com conta ativa;
    - recusa sem motivo dá `422`; encerrado dá `409`;
    - liberação sem identidade em EMAIL dá `409 IDENTITY_NOT_VERIFIED`;
    - conta ANONYMIZED aceita no registro, sem verificação.
  - **`IdentityVerificationServiceTest`** (BR4.11 a BR4.13): mensagem com a primeira linha e a validade; confirmação válida; inválida (`422`, continua aguardando); repetida (`409`); vencida (`409`); precedência de `DATA_REQUEST_CLOSED`; recusa manual marca `EXPIRED`.
  - **`DataRequestTimersTest`** (BR4.13, BR4.14): verificação vencida recusa com "identidade não confirmada"; vencido gera um único aviso; rodar duas vezes não duplica.
  - **`DataSubjectRequestPurgeJobTest`** (BR4.15): encerrado há 12 meses ou mais sai com a verificação; um mais recente fica.

  (US5.1 a US5.8)
- [x] **Passo 4** — Implementar no Privacy:
  - **domínio:** `DataSubjectRequest`, `IdentityVerification`, `VerificationMessage`, `AccessSummary`, `DataRequestService` e `IdentityVerificationService`;
  - **infraestrutura:** repositórios JDBC com filtro por tenant e pessoa, `DataRequestRateLimit` (trava de transação e contagem), `AccessSummaryAssembler` pelas J1 a J4, `PrivacyJobRunner` com `privacy_job_run`, `DataRequestTimersJob` (5/1 min), `DataSubjectRequestPurgeJob` (04:00 UTC) com `DATA_REQUESTS_PURGED`, `PrivacyJobRecovery`, declaração de retenção do expurgo e saída de `DATA_SUBJECT_REQUESTS` da `PendingRetention`;
  - **observabilidade:** `DataRequestMetrics` com o refresher de 60 s (abertos, vencidos e a vencer em 3 dias), `DataRequestsHealthIndicator` e o indicador do expurgo.

  Os testes do Passo 3 ficam verdes. (US5.1 a US5.8; NFR2.x, NFR3.x, NFR7.x, NFR8.x)
- [x] **Passo 5** — Rotas:
  - **`DataRequestController` (P1):** criar, listar e abrir, com idempotência e `429`;
  - **`AdminDataRequestController` (P2, sem a anonimização):** listar com filtro e cursor, registrar por e-mail, detalhe com prévia do resumo, transição, iniciar e conferir verificação, e `reply-sent`.

  As rotas entram no `route-registry.yml` (`CLIENT` com teste negativo de tenant, `ADMIN`). O OpenAPI guest e admin é regenerado, e os blocos P1 e P2 do resumo de contratos são atualizados (409 e 429 aditivos). (US5.1 a US5.6, US5.9)
- [x] **Passo 6** — Testes de integração depois da implementação:
  - `DataRequestIT`: fluxo do convidado, isolamento entre tenants, idempotência, `429` com relógio, concorrência do índice único;
  - `AdminDataRequestIT`: papel, filtro, transições, resumo e `reply-sent`;
  - `IdentityVerificationIT`: assinatura da carteira da conta gerada em teste, outra carteira, repetida, vencida, e a assinatura de verificação enviada ao login recusada;
  - `DataRequestTimersIT` e `DataSubjectRequestPurgeIT`: concorrência, recuperação e `EXPLAIN` com seqscan desligado;
  - `DataRequestsMigrationTest`, `RetentionConformanceTest` sem a pendência, métricas e saúde;
  - `LogScanIT` com as agulhas novas.

  (NFR1.x, NFR2.x, NFR3.x, NFR9.1)

### Apps

- [x] **Passo 7** — Tipos gerados nos dois apps (`check:api`). No web, `DataRequestsSection` (T5) com `DataRequestForm`, `DataRequestList`, `DataRequestDetail` e `AccessSummaryView` na Conta. (US5.1, US5.2)
- [x] **Passo 8** — Admin:
  - `AdminDataRequestList` (T6, filtro e alerta com ícone e texto);
  - `AdminDataRequestDetail` (T6a, com as respostas modelo);
  - `RegisterEmailRequestForm` (T6b);
  - `VerificationPanel` (T6c);
  - `ReplyToContact`.

  As rotas são `/pedidos`, `/pedidos/[id]` e `/pedidos/novo`, com link no menu. (US5.3 a US5.6, US5.9)
- [x] **Passo 9** — Testes Jest nos dois apps, de 10 a 15 por componente:
  - texto com `<script>` aparece literal;
  - contador de 1000;
  - `409` e `429` com mensagem;
  - estados de carregando, erro e vazio;
  - teclado e foco;
  - alerta com ícone e texto;
  - respostas modelo;
  - liberar o resumo.

  Piso de 80%. (NFR4.x, NFR2.5)

### Ponta a ponta, carga e documentação

- [x] **Passo 10** — Spec `apps/web/e2e/data-requests/smoke.spec.ts`:
  1. com a conta do smoke, cria um pedido na Conta e vê na lista;
  2. com a carteira de administrador do smoke, abre o pedido no app do administrador e conclui com resposta;
  3. de volta à Conta, vê a resposta;
  4. confere teclado e foco.

  O responsável acrescenta o spec ao `verify.sh`. (US5.1 a US5.3)
- [x] **Passo 11** — Cenário de carga `data-requests`:
  - **`scripts/load-test.sh`:** caso novo, com convites, `USERS`, duração, carteira de administrador efêmera e `LOADTEST_DATA_REQUESTS_SEED`;
  - **despacho:** em `load-test.mjs`;
  - **cenário:** `scripts/load/data-requests.mjs`, com o teste `node --test`;
  - **semeadura:** `privacy/loadtest/DataRequestLoadSeeder` (perfil `loadtest`, padrão `0`).

  O responsável acrescenta a opção ao `load-test.yml`. (NFR3.1 a NFR3.3)
- [x] **Passo 12** — Documentação em `docs/api/u4-data-requests.md`: rotas, estados, verificação por assinatura e passo a passo do administrador para pedido por e-mail.
- [ ] **Passo 13** — Rodar:
  - os comandos da Unit;
  - o `mvnw clean verify` completo com JaCoCo, sem exportar a chave HMAC;
  - lint, tipos, formatação, `check:api` e `test:coverage` no web e no admin;
  - `bash scripts/verify.sh` pelo ponto de controle, depois do pré-requisito do responsável.

  (todas)

## Rastreabilidade história → passos

| História | Passos |
|---|---|
| US5.1 | 1, 3, 4, 5, 6, 7, 9, 10, 11 |
| US5.2 | 3, 4, 5, 6, 7, 9, 10 |
| US5.3 | 3, 4, 5, 6, 8, 9, 10 |
| US5.4 | 2, 3, 4, 5, 6, 8, 9 |
| US5.5 | 8, 9 |
| US5.6 | 2, 3, 4, 5, 6, 8, 9 |
| US5.7 | 3, 4, 6 |
| US5.8 | 3, 4, 6 |
| US5.9 | 5, 8, 9 |

## Pré-requisitos do responsável do projeto

1. `scripts/verify.sh`: a lista de specs termina em `... e2e/legal-documents e2e/reacceptance e2e/data-requests`, antes do ponto de controle da U4.
2. `.github/workflows/load-test.yml`: opções `reacceptance` e `data-requests`, e a descrição terminando em `..., u5, legal, reacceptance or data-requests)`, antes do Build and Test.
