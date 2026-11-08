# Plano de geração de código — U8 Administração e feedback

## Summary

- Builds: entrada do administrador por carteira, kill switch com trava e reconciliação sobre a U5, estado do sistema só com contagens, telas de convites, feedback do convidado com limite e expurgo, e o novo app web do administrador
- Touches: `apps/api/` (módulos novos `administration` e `feedback`, acréscimos em `identity`, `riskcontrol/api`, `audit/api`, migração `0011`, `10-roles.sql`, OpenAPI), `apps/admin/` (app novo), `apps/web/` (diálogo de feedback), `docker-compose.yml`, overlay de teste, `ci.yml`, `security.yml`, `dependabot.yml`
- Tests: cerca de 190 testes de backend (kill switch escrito antes do código, integração com quedas e corrida, entrada, CORS, isolamento, ArchUnit, contrato), cerca de 60 do `apps/admin` e 15 do diálogo no `apps/web`, e o smoke Playwright `e2e/u8-admin-feedback`

## Entradas

- [Desenho funcional](../functional-design/functional-spec.md), [entidades](../functional-design/entities.md), [regras](../functional-design/rules.md) e [componentes de frontend](../functional-design/frontend-components.md).
- [Requisitos não funcionais](../nfr-requirements/) e [NFR Design](../nfr-design/). Onde o NFR Design corrige o desenho funcional, vale o NFR Design: reconciliação sempre, trava consultiva, `challenge` sem revelar a lista e 45 s no pior caso.
- [Desenho de infraestrutura](../infrastructure-design/).
- `contract-summary.md` (C6, C8, C9, C16, C17) e `unit-of-work.md`.
- Achados das revisões que este plano fecha:
  - **NFR Design:** R-08 (credenciais do CORS também no `verify`), R-09 (fencing com `reconcile_marked_at`, que prevalece sobre o texto antigo do item 4) e R-10 (testes das três quedas e da disputa na JVM).
  - **Infraestrutura:**
    - R-08: a conta de conexões vai a 43 (inclui o pool de retenção do feedback, com 1 conexão).
    - R-09: entrada docker do `apps/admin` no Dependabot e `MigrationsBackwardCompatIT` na tabela de portões.

## Decisões de implementação

| Tema | Decisão |
|---|---|
| Pacotes | `com.memetrade.administration` (`api`, `domain`, `infra`, `web`) e `com.memetrade.feedback` (mesma divisão). A entrada do administrador fica em `com.memetrade.identity` (`web/AdminAuthController`, `infra/AdminLoginLimiter`), que já é dono de sessões e desafios |
| Interfaces novas de outras Units | `riskcontrol.api.RejectionCounts#countRejectedSince(Instant)` (só o número); `audit.api.AuditBacklog#pending()` (atraso da saída da trilha). As duas são implementadas nos módulos donos e registradas no `contract-summary.md` (Passo 3) |
| Trava de aplicação | `ApplyLock` com `ReentrantLock(true)` mais `pg_try_advisory_lock` num `DataSource` Hikari dedicado (`maximumPoolSize=1`, `minimumIdle=1`), com orçamento único de 10 s |
| Fencing | `reconcile_needed` e `reconcile_marked_at` em `kill_switch_state`. Marcar é um `UPDATE` incondicional, sem mudar `version`. Limpar e gravar `pending_bots` só sob a trava, `WHERE version = :lida AND reconcile_marked_at <= :inicio` |
| Reconciliação | `@Scheduled(fixedDelay = 30 s)` mais `ApplicationReadyEvent`, `@SchedulerLock` e `@ProcessRole(API)`. Sempre chama a operação do estado atual |
| Proteção no banco | Gatilhos (não `REVOKE`): `kill_switch_change` só de acréscimo; `kill_switch_state` com uma linha só; `feedback_entry` sem `UPDATE` e com `DELETE` só para membros de `feedback_retention` |
| Expurgo do feedback | `FeedbackRetentionDataSource` (1 conexão) com o login de retenção, no padrão do `RiskRetentionDataSource` da U6 |
| Rotas públicas da entrada | `challenge`, `verify` e `refresh` em `/api/v1/admin/auth/` entram nas listas do `AuthenticationFilter` e do `AdminRouteFilter`, pelo `RequestPaths` |
| CORS | `AdminCorsPolicy` para `/api/v1/admin/**`, com origem `ADMIN_APP_ORIGIN`, métodos GET, POST, PUT e OPTIONS, e credenciais só em `verify`, `refresh` e `logout` |
| App do administrador | `apps/admin` com o mesmo `package.json` base do `apps/web` (Next.js 15.5.28, React 19.2.9, TypeScript 5.9.3, Jest, Playwright) e lockfile próprio, como o `apps/web` já tem: o repositório não usa workspace pnpm na raiz. Isso ajusta a Q7 dos requisitos ("mesmo workspace") ao layout real. Sem pacote compartilhado |
| CSP | `middleware.ts` com nonce por requisição, `strict-dynamic` e páginas dinâmicas |
| Agendador e conexões | `SCHEDULER_POOL_SIZE` da API de 14 para 16; faixa do guarda da API de 16 a 32; conta de conexões de 41 para 43 |

## Testing Contract

```json
{
  "version": 1,
  "methodology": "custom",
  "source": "team",
  "ordering": "No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
  "scope": "enterprise",
  "test_strategy": "comprehensive",
  "project_type": "greenfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: custom\n- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.\n- **Methodology evidence**: decisão do responsável do projeto na entrevista\n  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a\n  passar em testes escritos depois sobre o próprio código; com o teste escrito\n  antes, ele valida o requisito e não a implementação. Test-after no restante\n  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo.\n- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`; o piso de\n  cobertura abaixo é somado a ela e nunca a substitui.\n- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no\n  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente\n  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam\n  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A\n  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo\n  no Maven e limite de cobertura do executor de testes do frontend). O piso não\n  pode ser reduzido para fazer uma etapa passar.\n- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou\n  mantém verdes os testes de:\n  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta\n    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que\n    também validam o tenant;\n  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L);\n  - deduplicação por chave de idempotência;\n  - violação de limites autorizados e controles de risco;\n  - dado de mercado ausente vira `unknown`, nunca zero;\n  - pausa do bot impede novas operações;\n  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só\n    acontece por lançamento compensatório;\n  - P&L com cenários determinísticos de resultado conhecido, em aritmética\n    decimal e unidades de token;\n  - REAL desligado por padrão: nenhum caminho assina ou envia transação com a\n    configuração padrão;\n  - login sem reutilização de assinatura: nonce reutilizado ou assinatura\n    expirada são rejeitados, e a assinatura de login não serve para autorizar\n    trading.\n- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o\n  reproduz.\n- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.\n  Os critérios básicos de acessibilidade do beta definidos em `project.md`\n  continuam valendo.\n- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds\n  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam\n  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação\n  sobre PostgreSQL real em contêiner (por exemplo, Testcontainers) e não dependem\n  de estado compartilhado nem da ordem de execução. Fixtures nunca usam carteira,\n  mint ou dados pessoais reais.\n- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no\n  Windows. O fim de linha é padronizado em LF por `.gitattributes` e\n  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.\n- **Frequência**: o smoke de ponta a ponta roda em toda mudança; a suíte\n  completa roda no merge para `main`.\n- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado\n  em silêncio.\n- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no\n  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.\n- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não\n  funcionais definam metas numéricas."
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
    "runner_step": "Bootstrap the minimal test runner/configuration and record the exact unit-scoped command.",
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
      "Bootstrap the minimal test runner/configuration and record the exact unit-scoped command.",
      "Custom ordering - No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
      "Implementation and tests - preserve that exact ordering; do not convert it to layer-local TDD.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:f1cb93725f957e70a8f4c222cf42d92297f54716d55a23775d600834a2d717d9",
  "contract_sha256": "sha256:2d166aad550ef9b43828422fd7cf817706d0f3d0e98b32e96e1d485322f4ec16"
}
```

## Como a ordem dos testes se aplica à U8

O núcleo financeiro do `team.md` (limites de risco, ledger, P&L e deduplicação) aparece na U8 em duas partes, que têm testes escritos antes do código:
- **Kill switch:** é o controle de risco que impede novas operações. Entram aqui a gravação do estado, a idempotência, a trava, o fencing e a reconciliação (BR2.1 a BR2.9).
- **Deduplicação e limite do feedback:** BR6.3 e BR6.4.

O resto (entrada, estado do sistema, telas, configuração, CI e documentação) é implementado camada por camada, com os testes escritos e executados logo depois de cada camada. A saída vermelha dos testes escritos antes do código fica registrada no resumo.

## Passos

- [x] **Passo 1 — Estrutura e configuração.**
  - **Pacotes:** `administration` e `feedback`.
  - **Configuração:**
    - Propriedades `memetrade.admin.*`: `app-origin`, intervalo da reconciliação, espera da trava, limites de entrada e retenção do feedback.
    - Guarda da API: `ADMIN_APP_ORIGIN` sem curinga, agendador de 16 a 32.
    - `SCHEDULER_POOL_SIZE` padrão 16.
    - `.env.example` ganha `ADMIN_APP_ORIGIN`.
  - **Testes:** os testes de configuração das Units anteriores que fixam 14 threads passam a 16.
- [x] **Passo 2 — Base de testes e comando da Unit.**
  - `AbstractU8IT` sobre a base da U5, com o dublê controlável da `BotAdministration` (falha por bot, queda no meio dos lotes, exceção) e o uso da U5 real nos testes de corrida.
  - Marcador `u8`.
  - O comando da Unit precisa rodar antes do primeiro teste vermelho (ver `unit-test-instructions.md`).
- [x] **Passo 3 — Contratos e interfaces públicas.**
  - **Interfaces novas:** `RejectionCounts` (U6) e `AuditBacklog` (U2), com implementação nos módulos donos e testes de integração.
  - **Registro no `contract-summary.md`:**
    - C8: união `OK`/`UNKNOWN` por parte do `system-status`, `pendingBots` e `reconcileNeeded`, `tenantName` no feedback, rota `GET /api/v1/admin/tenants` e rotas `admin/auth/*`.
    - C6: `429 RATE_LIMITED`.
    - Ressalva da ordem das marcas C18, vinda da U5.
- [x] **Passo 4 — Migração `0011-admin-feedback.sql` e papéis.**
  - **Tabelas:** `kill_switch_state` (linha OFF; `pending_bots`, `reconcile_needed`, `reconcile_marked_at`, `version`), `kill_switch_change` e `feedback_entry` com os índices.
  - **Gatilhos:**
    - `kill_switch_change` só de acréscimo.
    - `kill_switch_state` com uma linha só.
    - `feedback_entry` sem `UPDATE` e com `DELETE` só para `feedback_retention`.
  - **Restrição:** `user_session.role` passa a aceitar `ADMIN`.
  - **`10-roles.sql`:** papel de grupo `feedback_retention`, só para o login de retenção.
  - **Testes:** `MigrationsIT` até a `0011`, `MigrationsBackwardCompatIT` e `U8PrivilegesIT` com o login da aplicação.
- [x] **Passo 5 — Kill switch: testes antes do código (vermelho).** Os testes são escritos a partir das regras, antes de qualquer código:
  - `KillSwitchServiceTest`: transições, repetição sem histórico, motivo de 1 a 500 caracteres e `Idempotency-Key`.
  - `ApplyLockIT`: disputa dentro da JVM, trava presa por outra conexão e resposta em 11 s ou menos com `reconcileNeeded`.
  - `KillSwitchFencingIT`: marcação durante a aplicação não é apagada; 3 repetições com `version` mudando.
  - `KillSwitchCrashIT`, três quedas:
    - antes da chamada à U5;
    - no meio dos lotes (30 de 60 bots);
    - perda da conexão da trava.
  - `KillSwitchRaceIT`: ON e OFF em paralelo, 50 repetições, com a U5 real.
  - `KillSwitchPerformanceIT`: 60 bots, p95 ≤ 5 s.
  - `KillSwitchReconcilerTest`: alertas de 60 s e 2 min com relógio controlado.

  A saída vermelha fica registrada.
- [x] **Passo 6 — Kill switch: código (verde).** `KillSwitchService`, `ApplyLock`, `KillSwitchApplier`, `KillSwitchReconciler`, repositórios JDBC e eventos `KILL_SWITCH_ON` e `KILL_SWITCH_OFF`. Todos os testes do Passo 5 ficam verdes; depois vem a refatoração, ainda com os testes verdes.
- [x] **Passo 7 — Feedback: testes antes do código (vermelho).**
  - `FeedbackServiceTest`: 1 a 2.000 pontos de código, tela S1 a S9 e tenant do token.
  - `FeedbackDedupIT`: mesma chave e mesmo corpo dão uma linha; corpo diferente dá 409; 400 e 429 não são guardados.
  - `FeedbackRateLimitIT`: 20 envios simultâneos da mesma pessoa dão exatamente 10 aceitos, em 50 repetições.
  - `FeedbackConcurrencyIT`: 20 pessoas, p95 ≤ 500 ms.
- [x] **Passo 8 — Feedback: código (verde).** `FeedbackService` (trava na linha da pessoa), repositório, `POST /api/v1/feedback`, `GET /api/v1/admin/feedback` (com nome do tenant, sem `userId`) e métricas. Depois a refatoração.
- [x] **Passo 9 — Expurgo do feedback.** `FeedbackRetentionDataSource`, `FeedbackPurgeJob` (03:00 UTC, lotes de 1.000, `FEEDBACK_PURGED`, indicador de saúde). Testes depois do código: `FeedbackPurgeIT` com relógio controlado, o login da aplicação sem `DELETE` e o indicador vencido.
- [x] **Passo 10 — Entrada do administrador.**
  - **Código:**
    - `AdminAuthController` (`challenge` responde 200 para qualquer endereço; `verify` com 403 genérico e motivo só no evento; `refresh` com rotação; `logout`).
    - Escopo de desafio `ADMIN`.
    - Criação da conta ADMIN no tenant do sistema.
    - Sessão de 15 min e 8 h.
    - Cookie `HttpOnly; Secure; SameSite=Strict`.
    - `AdminLoginLimiter` (5 por minuto por IP e 5 por (endereço, IP)).
    - Rotas públicas no `AuthenticationFilter` e no `AdminRouteFilter`.
  - **Testes depois do código:** `AdminAuthIT`:
    - nonce reutilizado ou vencido;
    - assinatura inválida;
    - carteira fora da lista;
    - carteira de convidado;
    - carteira retirada da lista na renovação;
    - cookie reutilizado;
    - os dois limites;
    - assinatura de entrada que não autoriza mais nada.
- [x] **Passo 11 — CORS e separação de papéis.**
  - **Código:** `AdminCorsPolicy`, com credenciais em `verify`, `refresh` e `logout`.
  - **Testes depois do código:**
    - `AdminCorsIT`: preflight do `PUT`, outra origem recusada, credenciais só nas três rotas e política do convidado sem mudança.
    - `AdminGuestRoutesIT`: token ADMIN em cada rota do convidado dá 403 com um `REQUEST_FORBIDDEN`.
    - Token de convidado em cada rota administrativa dá 403.
- [x] **Passo 12 — Estado do sistema e tenants.** `SystemStatusAssembler` (6 partes em paralelo, 500 ms por parte, `UNKNOWN` com motivo), `GET /api/v1/admin/system-status`, `GET /api/v1/admin/kill-switch` e `GET /api/v1/admin/tenants`. Testes depois do código: `SystemStatusIT` (cada parte lenta ou fora vira `UNKNOWN`, nunca zero; a rota responde em 1 s ou menos) e `AdminReadLatencyIT` (20 leituras simultâneas, p95 ≤ 2 s).
- [x] **Passo 13 — OpenAPI e tipos.** `openapi-admin.yaml` e `openapi-guest.yaml` regenerados. Teste de contrato da U8 e conferência de que o guest não tem rotas administrativas.
- [x] **Passo 14 — Saúde, métricas, logs e trilha.** Métricas `admin.killswitch.*`, `feedback.*` e `admin.login.refused_total`, indicadores `adminKillSwitch` e `feedbackPurge`, e campos de log permitidos. Teste de varredura com o marcador de feedback e o motivo do kill switch.
- [x] **Passo 15 — Arquitetura.** ArchUnit:
  - `administration` e `feedback` sem acesso a `ledger`, `pnl`, `paper` nem a repositórios de outros módulos;
  - só `KillSwitchApplier` chama a `BotAdministration`;
  - sem `double`;
  - sem SDK Solana fora do escopo de teste;
  - jobs com `@ProcessRole(API)`.
- [x] **Passo 16 — Diálogo de feedback no `apps/web`.**
  - **Componentes:** `FeedbackButton` no cabeçalho atual e `FeedbackDialog` (S9: contador, tela pela rota, texto mantido em erro, foco devolvido).
  - **`GuestBundleTest`:** depois do build, nenhuma ocorrência de `/api/v1/admin` nem de `openapi-admin`.
  - **Testes depois do código:** Jest (15).
- [x] **Passo 17 — App `apps/admin`.**
  - **Estrutura:** `package.json`, lockfile, `tsconfig` estrito, ESLint, Prettier, Jest com piso de 80%, Playwright e tipos gerados de `openapi-admin.yaml`.
  - **Segurança e saúde:** `middleware.ts` com CSP com nonce e os outros cabeçalhos; rota `/api/health`.
  - **Telas:**
    - `AdminSignIn`;
    - `AdminLayout` com `KillSwitchBanner`;
    - `SystemStatusPage`;
    - `InvitesPage`, `IssueInviteDialog` e `RevokeInviteDialog`;
    - `KillSwitchPage` e `KillSwitchDialog`;
    - `FeedbackListPage`;
    - `RestrictedAccess`.
  - **Textos e tema:** pt-BR, tema escuro e contraste de 4,5:1 ou mais.
  - **Testes depois do código:** cerca de 60 testes Jest (estados, diálogos, validação, teclado, contraste) e o teste dos cabeçalhos de segurança.
- [x] **Passo 18 — Contêiner e Compose.**
  - **Imagem:** `apps/admin/Dockerfile` no padrão do `web`.
  - **Serviço `admin`:** `127.0.0.1:3001`, depende do `api` saudável, healthcheck `/api/health`, `API_ORIGIN` e 512 MB.
  - **Overlay de teste:** `ADMIN_WALLET_ADDRESSES: ${ADMIN_WALLET_ADDRESSES:-}`.
  - **`ComposeFilesTest`:** serviço `admin`, 16 threads e 43 conexões.
- [x] **Passo 19 — Smoke `e2e/u8-admin-feedback`.**
  - **Pré-condições:** usa `SMOKE_ADMIN_WALLET_FILE` e `ADMIN_BASE_URL`; se faltar alguma, o teste falha com uma mensagem clara.
  - **Roteiro:**
    1. Entrar como administrador.
    2. Ver o estado do sistema em 2 s ou menos.
    3. Emitir e revogar um convite.
    4. Ligar e desligar o kill switch, conferindo a faixa.
    5. Enviar um feedback com marcador como convidado do smoke e lê-lo como administrador.
    6. Conferir o 403 com token de convidado.
  - **Execução:** depende das mudanças do responsável do projeto no `verify.sh`.
- [x] **Passo 20 — CI.**
  - **`ci.yml`:**
    - job `admin` (ações por SHA, `contents: read`, `--frozen-lockfile`, lint, formato, tipos, Jest 80%, build e teste de cabeçalhos);
    - `contract` instala o `apps/admin` e compara os tipos;
    - `frontend` roda o `GuestBundleTest`;
    - `e2e` com `needs: [..., admin]`.
  - **`security.yml`:** audit do `apps/admin` e SAST.
  - **`dependabot.yml`:** entradas `npm` e `docker` de `/apps/admin`.
  - **`WorkflowsTest`:** atualizado para as mudanças acima.
- [x] **Passo 21 — Execução das suítes e cobertura.**
  - **Backend:** `apps/api/mvnw -f apps/api/pom.xml verify`, piso de 80%.
  - **`apps/web`:** Jest com cobertura.
  - **`apps/admin`:** Jest com cobertura.
  - **Ponta a ponta:** `bash scripts/verify.sh`, se o hook permitir; senão, fica para o ponto de controle.
- [x] **Passo 22 — Documentação e ação pendente.**
  - **Documento da Unit:** `docs/api/u8-admin-feedback.md` com as rotas, o kill switch, o rollback e o risco aceito.
  - **Mudanças no `verify.sh` para o responsável do projeto:** as linhas exatas dos 7 passos (carteira descartável, espera, variáveis, varredura dos logs, limpeza e linha do Playwright).
  - **Saídas da Unit:** `code-summary.md`, `source-manifest.json` e `traceability.json`.

## Rastreio das histórias

| História | Critérios | Passos |
|---|---|---|
| US7.2 (kill switch) | AC7.2.1, AC7.2.2, AC7.2.4, AC7.2.5 | 5, 6, 11, 12, 14, 17, 19 |
| US7.4 (estado do sistema) | AC7.4.1, AC7.4.2 | 3, 11, 12, 17, 19 |
| US7.1 (telas de convite) | AC7.1.1 a AC7.1.4 | 11, 12, 17, 19 |
| US6.1 (feedback) | AC6.1.1 a AC6.1.4 | 7, 8, 9, 16, 17, 19 |
| US1.2 (entrada do administrador) | AC1.2.6 | 10, 11, 17, 19 |
