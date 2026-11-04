# Plano de geração de código — U3 Convites, contas e sessões

## Summary

- Builds: convites de uso único (emissão, lista, revogação, consulta sem sessão), criação da conta na verificação com convite, termos e aviso de risco versionados, tela Conta, sessão expirada com retorno à origem e Sair
- Touches: `apps/api/` (módulo `identity`, `shared/web`, migração `0007`, termos `v1`, OpenAPI do convidado e do administrador, testes), `apps/web/src/app/convite/`, `apps/web/src/app/conta/`, `apps/web/src/features/auth/`, `apps/web/src/features/account/`, `apps/web/e2e/u3-identity-access/`, `infra/docker-compose.test.yml`, `.github/workflows/load-test.yml`, `scripts/load/`, `docs/`
- Tests: cerca de 160 testes de backend (unitários, integração com Testcontainers, concorrência, ArchUnit, contrato), cerca de 60 de frontend (Jest), um smoke Playwright e o cenário de carga `u3`, com o piso de 80% de linhas mantido

## Entradas

Unit `u3-identity-access` de [unit-of-work.md](../../../inception/units-generation/unit-of-work.md), histórias US7.1 (regras e rotas), US1.1, US1.3 e US1.5 de [stories.md](../../../inception/user-stories/stories.md).

| Documento | O que dá ao código |
|---|---|
| [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), [entities.md](../functional-design/entities.md) | Fluxos F1 a F9, regras BR1.1 a BR6.2, entidades e correções de contrato |
| [nfr-requirements/](../nfr-requirements/) e [nfr-design/](../nfr-design/) | Metas NFR2.1 a NFR14.1 da U3, regra pública estreita, registro de rotas, limitador, idempotência sem o código |
| [infrastructure-specification.md](../infrastructure-design/infrastructure-specification.md), [cicd-pipeline.md](../infrastructure-design/cicd-pipeline.md), [monitoring-design.md](../infrastructure-design/monitoring-design.md) | Migração `0007`, termos `v1`, semeador do smoke, script de carga, rollback testado |
| [contract-summary.md](../../../inception/contract-design/contract-summary.md) | C1, C3, C8, C9, C17 e regras comuns |

## Decisões de implementação

- **Pacotes:** `identity.domain.invite` (novo), `identity.domain.terms` (novo), `identity.domain.account` (novo), `identity.domain.login` e `identity.domain.ratelimit` (U1, ampliados), `identity.api` (interface `IdentityAdministration`), `identity.infra`, `identity.web`. Brownfield: os arquivos da U1 são alterados no lugar.
- **Instantes:** sempre `Instant` UTC do `Clock` injetável; a tela converte para o fuso do navegador.
- **Código do convite na emissão (observação da revisão do NFR Design):** a operação de emissão devolve um resultado tipado `IssuedInvite(Invite invite, String code)`. O corpo guardado no mecanismo de idempotência é a serialização JSON só do `Invite` (sem `code`); a resposta HTTP é serializada de novo, a partir de um DTO com `code`, apenas quando `replayed` é falso. Nunca há concatenação de texto JSON.
- **Correções e acréscimos de contrato** gerados no OpenAPI e registrados no `contract-summary.md` pelo Passo 23: C1 (`409` só `WALLET_ALREADY_LINKED`; `reason` com `EXPIRED`, `REVOKED`, `USED`, `TENANT_INACTIVE`; `404 INVITE_NOT_FOUND`), C3 (`404`, `429`), C8 (`Invite` com `codeSuffix`, `createdAt`, `revokedAt`, `code` só no `201`; `404` e `409 INVITE_NOT_REVOCABLE` na revogação; `422` na emissão), C17 (`activeTenants()`), C9 (ações `INVITE_ISSUED`, `INVITE_REVOKED`, `ACCOUNT_CREATED`).
- **Achados da revisão da Infrastructure Design resolvidos aqui:**
  - o overlay `infra/docker-compose.test.yml` passa `SMOKE_INVITE_CODE`, `SMOKE_INVITE_CODES` (padrão vazio) e `INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE` (padrão `10`, então só o `loadtest` o eleva); o `ComposeFilesTest` confere as três; o `load-test.yml` passa `INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE` como já passa `LOGIN_RATE_LIMIT_PER_MINUTE` (Passo 21);
  - a restrição única de `wallet.address`, hoje sem nome (`wallet_address_key`, gerado pelo PostgreSQL), é renomeada para `uq_wallet_address` pela `0007` de forma condicionada (só se o nome antigo existir e o novo não), sem criar restrição duplicada (Passo 3);
  - o script de carga ganha o cenário `LOAD_SCENARIO=u3`, separado do `u1`, com limites próprios: verificação com convite 500 ms, consulta do convite e tela Conta 300 ms; o limite de 300 ms do `verify` sem convite no cenário `u1` não muda (Passo 21);
  - o código do convite do smoke é gerado pela mesma ferramenta de teste que já gera a carteira de smoke (`SmokeWalletTool`, código de teste), chamada pelo `scripts/verify.sh`; o trace do Playwright fica desligado no teste da U3, para o relatório da CI não guardar o link com o código (Passos 20 e 22);
  - o smoke da U3 só é evidência depois que o responsável do projeto aplicar as mudanças no `scripts/verify.sh`. Por isso a rastreabilidade marca NFR3.4 e a parte de log do NFR6.4 como dependentes dessa ação, e o ponto de controle da U3 roda o comando de verificação com o smoke da U3 antes da aprovação (Passo 24).
- **Fora do agente:** as mudanças no `scripts/verify.sh` (gerar e exportar `SMOKE_INVITE_CODE`, incluir `e2e/u3-identity-access`, procurar o código no log da `api`) são do responsável do projeto; o texto é preparado no Passo 24.

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

## Como a ordem dos testes se aplica à U3

- **Fora do núcleo financeiro:** a U3 não implementa limites de risco, ledger, P&L nem a deduplicação de ordens; ela só acrescenta duas operações ao mecanismo comum de idempotência que já existe. Todos os passos seguem a ordem test-after do `team.md`.
- **Testes depois de cada camada (todos os passos):** cada passo implementa a camada e, no mesmo passo, escreve e executa os testes dela antes de seguir.
- **Executores prontos antes do primeiro teste:** o Passo 2 deixa o comando da U3 (marcador `u3`) rodando verde antes do Passo 3.
- **Volume:** 10 a 15 testes por componente; unitários, integração, concorrência, ponta a ponta, desempenho (cenário de carga `u3`) e segurança (rotas públicas, papéis, limite, formato, cabeçalhos, origem, termos proibidos).
- **Suítes da U1, da U2 e da U4:** continuam verdes depois da migração `0007`, da regra pública nova e da mudança no `LoginLimiterEvictionJob`.

## Passos

Cada passo indica as histórias (US), regras (BR) e requisitos detalhados (NFR, da U3) que implementa. "Fundação" marca o que vem das responsabilidades da Unit sem história própria.

### Fundação e executores

- [x] **Passo 1 — Configuração.**
  - `application.yml`: `memetrade.auth.invite-lookup-rate-limit-per-minute: ${INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE:10}`, `memetrade.terms.location: classpath:terms/`; `application-loadtest.yml`: `${INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE:1000}`; `application-test.yml`: `memetrade.smoke.invite-code` e `memetrade.smoke.invite-codes` com padrão vazio.
  - `IdentityProperties` com as propriedades novas e validação de faixa na partida.
  - Fundação; NFR6.6, NFR2.3.
- [x] **Passo 2 — Base de testes e comando da Unit.**
  - Marcador JUnit `u3`; apoio de teste: `MutableClock` existente, fábrica de carteiras de teste (código de teste, nunca de produção) e um construtor de convites para os testes de integração.
  - Um teste de subida do contexto com o marcador `u3` roda verde com o comando de [unit-test-instructions.md](unit-test-instructions.md). As suítes da U1, da U2 e da U4 rodam verdes.
  - Fundação; NFR12.1.

### Banco de dados e termos

- [x] **Passo 3 — Migração `0007-identity-access`.**
  - Tabela `invite` (`invite_id`, `tenant_id`, `code_digest` com `uq_invite_code_digest`, `code_suffix`, `status` com `CHECK`, `issued_by`, `created_at`, `expires_at`, `used_at`, `used_by_user_id` com `uq_invite_used_by`, `revoked_at`) e as restrições de coerência entre estado e datas; índice `(created_at desc, invite_id)`.
  - Tabela `terms_acceptance` (`acceptance_id`, `user_id`, `tenant_id`, `terms_version`, `accepted_at`, `invite_id`), só inserção para a aplicação (sem `UPDATE` nem `DELETE` concedidos).
  - Renomear `wallet_address_key` para `uq_wallet_address` num bloco condicionado (só se o antigo existir e o novo não); índice `app_user(role, status)`.
  - Semeadura do tenant `Beta` com identificador fixo e `INSERT … WHERE NOT EXISTS`.
  - Depois, os testes: restrições de estado e datas; unicidades; nome `uq_wallet_address` presente e uma única restrição única em `wallet.address`; `terms_acceptance` recusa `UPDATE` e `DELETE` da aplicação; tenant `Beta` uma vez só ao aplicar duas vezes; rollback automático (`MigrationsIT` roda as consultas de entrada e sessão da U1 sobre o esquema novo).
  - US7.1, US1.1; BR1.7, BR3.3, BR3.4; NFR4.2, NFR4.3, NFR10.4.
- [x] **Passo 4 — Termos e aviso de risco `v1`.**
  - Arquivos `apps/api/src/main/resources/terms/v1/terms.txt`, `risk-notice.txt` e `version.properties` (`version=v1`, `current=true`), em UTF-8 e LF, com o texto provisório marcado "Versão provisória, pendente de revisão jurídica", sem promessa de lucro nem garantia de detecção de fraude.
  - `TermsCatalog`: lê as versões na subida e falha a partida sem versão vigente única ou com texto vazio.
  - Depois, os testes: versão vigente carregada; nenhuma vigente; duas vigentes; texto vazio; o catálogo de termos proibidos da U4 sobre os dois textos.
  - US1.1; BR3.1, BR6.2; NFR10.3, NFR6.13, NFR13.1.

### Convites

- [x] **Passo 5 — Código e estado do convite.**
  - `InviteCodes`: `SecureRandom` de 32 bytes, base64url sem preenchimento, SHA-256 hexadecimal, final de 4 caracteres, validação de formato; o gerador alternativo só no construtor usado pelos testes.
  - `Invite` e `InviteStatus` com o estado derivado (BR1.2) a partir de um instante.
  - Depois, os testes unitários: 10 000 códigos únicos e no alfabeto; resumo conhecido para um código fixo; formato válido e inválido (42, 44, caractere fora do alfabeto); estado em `expiresAt` e `expiresAt + 1 s`; `USED` e `REVOKED` não viram `EXPIRED`.
  - US7.1, US1.1; BR1.1, BR1.2, BR2.3; NFR6.1, NFR6.2, NFR6.7, NFR10.6.
- [x] **Passo 6 — Repositório de convites.**
  - `JdbcInviteRepository`: inserir, buscar por resumo com e sem `FOR UPDATE`, buscar por id com `FOR UPDATE`, marcar `USED` e `REVOKED`, página por cursor, contagens por estado derivado com o instante como parâmetro.
  - Depois, os testes de integração: cada operação; página estável entre chamadas; contagens com as quatro chaves; bloqueio real (uma segunda transação espera).
  - US7.1; BR1.3, BR1.6; NFR2.2, NFR2.4.
- [x] **Passo 7 — Emissão, lista, revogação e contagens.**
  - `InviteService` (F1, F2, F3, F9) e `IdentityAdministrationImpl` (C17) com conferência de `ADMIN` na interface; `activeTenants()`.
  - `IdempotentOperation` ganha `ADMIN_INVITE_ISSUE` e `ADMIN_INVITE_REVOKE` com `Retention.DEFAULT`; emissão devolve `IssuedInvite`; o corpo guardado é o `Invite` sem código, gravado na mesma transação do convite e do `INVITE_ISSUED`.
  - `INVITE_ISSUED` e `INVITE_REVOKED` pelo publicador da U2, com o administrador mascarado e o `inviteId`.
  - Depois, os testes: validade 1 h e 30 dias exatos aceitos, 1 s a menos e a mais recusados; tenant inexistente e suspenso; revogação de pendente, repetida (sem evento), de usado e de expirado (`409`); inexistente (`404`); repetição da emissão com a mesma chave devolve o mesmo convite sem `code` e um único evento; o registro de idempotência guardado não contém o código; chave ausente `400`; não administrador recusado na interface.
  - US7.1; BR1.1 a BR1.8; NFR4.5, NFR6.3, NFR6.10, NFR9.4.

### Consulta do convite e verificação com convite

- [x] **Passo 8 — Limitador da consulta e limpeza.**
  - `InviteLookupRateLimiter` por IP, janela de 1 minuto, limite da propriedade do Passo 1; o `LoginLimiterEvictionJob` passa a limpar os dois limitadores.
  - Depois, os testes: 10ª passa e 11ª recebe `Retry-After`; janela de 1 minuto; contador separado do da entrada; limpeza dos dois limitadores; perfil `loadtest` com o limite elevado.
  - US1.1; BR2.2; NFR6.6, NFR2.3.
- [x] **Passo 9 — Rota pública estreita e consulta do convite.**
  - `AuthenticationFilter`: além das rotas públicas da U1, libera sem token só `GET` com caminho normalizado inteiro em `^/api/v1/invites/[^/]{1,128}$`.
  - `InviteLookupController` (F4): limite, formato, busca, resposta com estado derivado, validade e termos vigentes; `404 INVITE_NOT_FOUND` igual para mal formado e desconhecido; `429 RATE_LIMITED`.
  - Depois, os testes: `GET` sem token chega ao controlador; `POST /api/v1/invites/abc`, `GET /api/v1/invites/abc/def`, `GET /api/v1/invites` e `GET /api/v1/invites/` sem token recebem `401`; cada estado; mal formado e desconhecido com a mesma resposta; mal formado conta no limite; resposta sem tenant nem `inviteId`; `Cache-Control: no-store` e `Referrer-Policy: no-referrer`.
  - US1.1; BR2.1, BR2.2, BR2.3; NFR6.5, NFR6.6, NFR6.7.
- [x] **Passo 10 — Criação da conta na verificação com convite.**
  - DTO da verificação com `inviteCode` e `acceptedTermsVersion` validados; campo extra (inclusive `tenantId`) recusado com `400`.
  - `InviteAccountCreation` (F5) na transação da verificação da U1: desafio `CONSUMED`, bloqueio do convite, conferências na ordem da BR3.2 (convite, estado, papel, carteira, tenant, termos), criação de pessoa, carteira, aceite, convite `USED`, `ACCOUNT_CREATED`, sessão.
  - Recusa como resultado confirmado com um `LOGIN_REJECTED`; violação de `uq_wallet_address` reconhecida pelo nome: rollback e segunda transação com desafio `CONSUMED` e `LOGIN_REJECTED`, resposta `409 WALLET_ALREADY_LINKED`; outra violação é falha técnica.
  - Depois, os testes de integração: conta criada com tudo junto; cada recusa da BR3.2 com o motivo e nada criado; código desconhecido e termos ausentes ao mesmo tempo dá o motivo do convite; termos de versão antiga; carteira de administrador `403`; nonce reutilizado depois de cada recusa é recusado (teste obrigatório de risco); falha técnica injetada desfaz tudo e não consome o desafio; verificação sem convite segue a U1 sem mudança.
  - US1.1, US7.1; BR3.1 a BR3.9; NFR4.1, NFR5.1, NFR6.8, NFR6.9.
- [x] **Passo 11 — Concorrência.**
  - Testes com barreira de início e conexões próprias, 50 repetições cada: mesmo convite em duas verificações (uma conta, outra `USED`); mesma carteira com dois convites (uma conta, outra `409`, convite continua `PENDING`); revogação contra verificação (sempre um dos dois resultados válidos).
  - US1.1, US7.1; BR1.4, BR3.4, BR3.7; NFR4.2, NFR4.3, NFR4.4.

### Conta e rotas administrativas

- [x] **Passo 12 — Tela Conta na API.**
  - `AccountQueryService` e `AccountController` (`GET /api/v1/account`): pessoa e tenant do `AuthContext`, carteira mascarada, provedor, `sessionExpiresAt` = fim absoluto da sessão, aceite (versão e `acceptedAt`), `realMode: UNAVAILABLE`.
  - Depois, os testes: dados da própria conta; sem sessão `401`; `AccountRouteIT#anotherTenantsSessionNeverSeesThisAccount` com pessoas de dois tenants; endereço nunca completo; `realMode` sempre `UNAVAILABLE` (teste obrigatório de REAL desligado); `no-store`.
  - US1.5; BR5.1 a BR5.4; NFR5.1, NFR6.12, NFR7.1.
- [x] **Passo 13 — Rotas administrativas de convite.**
  - `AdminInviteController`: `GET` e `POST /api/v1/admin/invites`, `POST /api/v1/admin/invites/{inviteId}/revoke`, compondo a resposta da emissão com o código só quando `replayed` é falso.
  - Depois, os testes: `AdminInviteRouteIT#aGuestTokenIsRefusedWith403AndOneRequestForbiddenEvent`; sem token `401`; `201` com `code` e repetição sem `code`; lista sem `code` nem `issuedBy`; `limit` fora de 1 a 100 dá `400`; `404` e `409` na revogação.
  - US7.1; BR1.1 a BR1.5, BR1.8; NFR6.3, NFR6.10, NFR2.2.
- [x] **Passo 14 — Registro de rotas e varredura de tenant.**
  - `route-registry.yml`: as cinco entradas novas com escopo e `negativeTest`, `tenantFromClient: true` só na emissão; `RouteRegistry` lê o campo; `RouteRegistryTest` recusa o campo fora de `ADMIN`.
  - Teste novo que percorre os OpenAPI do convidado e do administrador e falha se uma operação com `tenantId` em parâmetro ou corpo não tiver `tenantFromClient: true`.
  - Depois, os testes: registro atual passa; uma entrada `GLOBAL` com a marca falha; uma operação fictícia com `tenantId` sem a marca falha.
  - Fundação; NFR5.2.
- [x] **Passo 15 — Arquitetura e varreduras.**
  - ArchUnit: `IdentityAdministration` não usada por controladores fora de `/api/v1/admin/`; semeadores (`SmokeAccountSeeder`, `SmokeInviteSeeder`) só com `@Profile("test")`; nenhuma API de geração ou importação de chave em produção.
  - `LogScanIT` e `MetricsLabelsIT` ampliados com um fluxo completo da U3 (emitir, consultar, verificar, conta): nenhum código, endereço completo, `inviteId` em rótulo, nonce, assinatura ou token.
  - NFR6.4, NFR6.8, NFR6.10, NFR7.1, NFR9.3, NFR9.5.
- [x] **Passo 16 — Semeador do smoke.**
  - `SmokeInviteSeeder` com `@Profile("test")`: cria no tenant `Beta` convites `PENDING` de 1 hora para `memetrade.smoke.invite-code` e cada código de `memetrade.smoke.invite-codes`; ignora resumo já existente; log só com o final do código.
  - Depois, os testes: cria e não duplica; código mal formado falha a partida com mensagem; vazio não cria nada; sem o perfil `test` o bean não existe.
  - Fundação (Q1 da Infrastructure Design); NFR3.4.

### Contrato

- [x] **Passo 17 — OpenAPI e tipos.**
  - OpenAPI do convidado (C1, C3) e do administrador (C8) com as correções e acréscimos; tipos do frontend regenerados; `GuestOpenApiContractTest` e as fatias de contrato atualizadas.
  - Depois, os testes: contrato gerado igual ao versionado; códigos de erro e `reason` nos exemplos.
  - US1.1, US1.5, US7.1; NFR6.8.

### Frontend

- [x] **Passo 18 — Página do convite `/convite/[codigo]`.**
  - Consulta do convite; termos e aviso de risco como texto simples em parágrafos; duas marcações guardadas em `sessionStorage`; "Conectar carteira" indisponível com o motivo escrito até as duas marcações; Phantom e Solflare detectadas ou links para instalar; campo para colar o código quando não houver; estados de convite inválido, desconhecido, limite, carteira já vinculada, recusa depois da assinatura (pede novo desafio) e erro técnico; conta criada leva à tela inicial; `data-testid` nos elementos interativos.
  - `middleware.ts`: `Cache-Control: no-store` em `/convite/*` e `/conta`; a rota não registra a URL.
  - Depois, os testes (Jest): cada estado da tabela da functional-spec.md; aceites preservados ao voltar; botão com motivo; nenhum `dangerouslySetInnerHTML`; cabeçalhos do middleware; acessibilidade básica (rótulos visíveis, foco, estado em texto).
  - US1.1; BR3.1, BR6.1, BR6.2; NFR6.5, NFR11.1, NFR14.1.
- [x] **Passo 19 — Sessão expirada, retorno à origem, Sair e tela Conta.**
  - `safeOrigin()` em `features/auth`, usada pela entrada; 401 depois de renovação falha leva à entrada com `origem` e o aviso "Sua sessão expirou. Entre de novo para continuar."; depois de entrar, volta à origem validada.
  - Página `/conta` em `features/account`: carteira abreviada só para leitura, provedor, "Sua sessão termina às {hora local}, ou antes, depois de 8 horas sem uso", termos aceitos com "ver" (versão, data no fuso do navegador, texto), "Modo REAL: indisponível no beta" sem controle, botão "Sair"; "Sair" também no menu da conta.
  - Depois, os testes (Jest): `safeOrigin` com `//exemplo.com`, `https://exemplo.com`, `/\exemplo.com`, `/%2F%2Fexemplo.com`, tabulação e quebra de linha no início, `javascript:`, `/admin`, rota inexistente e uma rota válida; aviso de sessão expirada em vez de erro técnico; retorno à origem; Sair descarta o token e volta à entrada; tela Conta em cada estado; nenhum controle de REAL; catálogo de termos proibidos sobre os textos da U3.
  - US1.3, US1.5; BR4.1 a BR4.4, BR5.1 a BR5.4; NFR6.11, NFR6.12, NFR11.1.
- [x] **Passo 20 — Smoke Playwright `e2e/u3-identity-access`.**
  - Abre `/convite/<SMOKE_INVITE_CODE>`, marca os aceites, conecta uma carteira de teste gerada no próprio teste, cria a conta, abre a tela Conta, confere REAL indisponível e sai; trace desligado neste teste; marcação de tempo até os termos e os dados da conta ficarem visíveis.
  - `SmokeWalletTool` (código de teste) ganha a geração do código do convite (32 bytes em base64url) usada pelo `verify.sh`.
  - US1.1, US1.3, US1.5; NFR3.4.

### Ambiente, carga e documentação

- [x] **Passo 21 — Compose, workflow de carga e script de carga.**
  - `infra/docker-compose.test.yml`: `SMOKE_INVITE_CODE: ${SMOKE_INVITE_CODE:-}`, `SMOKE_INVITE_CODES: ${SMOKE_INVITE_CODES:-}`, `INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE: ${INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE:-10}`; `ComposeFilesTest` confere as três.
  - `.github/workflows/load-test.yml`: passa `INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE` e aceita o cenário `u3`, ações fixadas por SHA e `permissions` mínimas mantidas; `WorkflowsTest` atualizado.
  - `scripts/load-test.sh` e `scripts/load/load-test.mjs`: `LOAD_SCENARIO=u3` gera 20 códigos e 20 carteiras de teste, exporta `SMOKE_INVITE_CODES` e o limite elevado antes de subir o Compose; roda 20 verificações com convite simultâneas, depois 5 minutos de consulta do convite e tela Conta com 20 usuários; limites de p95 500 ms (verificação com convite) e 300 ms (consulta e Conta); cenário `u1` sem mudança; relatório em `docs/perf/u3-identity-access-<data>.md`.
  - NFR3.1, NFR3.2, NFR6.6.
- [x] **Passo 22 — Execução das suítes e cobertura.**
  - Rodar o comando da U3, depois a suíte inteira do backend (`verify` com JaCoCo 80%) e do frontend (Jest com cobertura); corrigir o que falhar sem reduzir o piso; anotar a cobertura no resumo do código.
  - NFR12.1.
- [x] **Passo 23 — Documentação e contrato.**
  - `contract-summary.md`: correções e acréscimos de C1, C3, C8, C9 e C17 listados nas decisões acima.
  - `docs/`: nota da U3 com o procedimento do administrador para código perdido (revogar e emitir outro), a troca do texto provisório dos termos por uma versão nova antes do primeiro convite real, e a conferência do SP-3 no checklist de liberação.
  - Fundação; NFR7.2.
- [x] **Passo 24 — Mudança preparada para o `scripts/verify.sh` e condição do ponto de controle.**
  - Texto da mudança para o responsável do projeto: gerar `SMOKE_INVITE_CODE` com a ferramenta de teste e exportá-lo; incluir `e2e/u3-identity-access` na linha do Playwright; procurar o código do convite no log da `api`, como já é feito com o endereço da carteira.
  - O ponto de controle da U3 só é aprovado depois de o comando de verificação (`bash scripts/verify.sh`) passar com o smoke da U3 incluído; até lá, a rastreabilidade marca NFR3.4 e a parte de log do NFR6.4 como dependentes dessa ação.
  - NFR3.4, NFR6.4.

## Rastreio das histórias

| História | Passos |
|---|---|
| US7.1 (regras e rotas de convite) | 3, 5, 6, 7, 10, 11, 13, 17 |
| US1.1 | 3, 4, 5, 8, 9, 10, 11, 17, 18, 20 |
| US1.3 | 19, 20 |
| US1.5 | 12, 17, 19, 20 |
