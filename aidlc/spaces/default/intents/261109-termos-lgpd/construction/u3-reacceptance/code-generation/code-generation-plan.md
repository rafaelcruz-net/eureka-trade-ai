# Plano de código — U3 `u3-reacceptance`

## Summary

- Builds: trava de novo aceite para o convidado, rota de aceite idempotente com `409` para versão superada, situação dos termos na Conta, tela de novo aceite e convite com resumo e estados.
- Touches: `apps/api` (`identity` filtro, serviço, controlador, porta, seeder do smoke, Conta; `shared/error` e `shared/web`; migração `0013`; `application.yml`; OpenAPI), `apps/web` (cliente da API, `/novo-aceite`, convite, Conta, tipos gerados, spec `e2e/reacceptance`), `scripts/load-test.sh`, `scripts/load/` e o contrato L2.
- Tests: cerca de 100 testes novos (JUnit 5 e Testcontainers, Jest e Playwright, e o cenário de carga `reacceptance`), com os testes da trava e do aceite escritos antes do código e o piso de 80% de linhas.

Conversa: português (Brasil); código, nomes e logs em inglês. Fontes:
- `construction/u3-reacceptance/functional-design/` (BR3.1 a BR3.11);
- `nfr-requirements/` e `nfr-design/` (NFR1.1 a NFR9.2);
- `infrastructure-design/`;
- `inception/contract-design/contract-summary.md` (L2, L3);
- `inception/user-stories/stories.md` (US2.3, US4.1, US4.2).

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
  - migração `0013-reacceptance.sql`, só aditiva ou relaxando, com o rollback de cada changeset: `invite_id` aceita nulo, coluna `source` com padrão `INVITE`, `CHECK` dos valores, `CHECK` cruzado e restrição única `uq_terms_acceptance_user_version`;
  - códigos `TERMS_REACCEPTANCE_REQUIRED` (403) e `TERMS_VERSION_OUTDATED` (409) em `ErrorCode`;
  - campo opcional `currentVersion` em `ProblemResponse`, mapa de extensões em `ApiException` e o mesmo valor no `ProblemWriter`;
  - `FilterOrder.TERMS_REACCEPTANCE` = +16;
  - `ReacceptanceProperties` com os prefixos liberados no `application.yml`;
  - `AuditActions.TERMS_ACCEPTED`;
  - confirmar os comandos da Unit.

  (todas; NFR1.1, NFR5.1)

### Trava e aceite (testes antes do código: novo aceite dos termos)

- [x] **Passo 2** — Testes escritos antes, que falham (Red) antes do Passo 3:
  - **`TermsReacceptanceFilterTest`** (BR3.1, BR3.2):
    - convidado sem aceite da vigente recebe `403` com `currentVersion`;
    - conta sem nenhum aceite recebe `403`;
    - vigente igual a uma versão já aceita passa;
    - cada prefixo liberado passa;
    - token de administrador e requisição sem principal passam;
    - banco indisponível dá `503` pelo `ProblemWriter` e nunca segue.
  - **`TermsAcceptanceServiceTest`** (BR3.4 a BR3.6): vigente grava um aceite `REACCEPTANCE` e publica o evento; versão já aceita devolve o mesmo resultado sem gravar; versão superada dá `409` com `currentVersion`; tenant e pessoa vêm do principal.

  (US4.1, US4.2)
- [x] **Passo 3** — Implementar no Identity:
  - porta `TermsAcceptanceLookup` e `JdbcTermsAcceptanceLookup` (consulta `EXISTS`, com o timer de histograma);
  - `TermsReacceptanceFilter` com `@ProcessRole(API)`, que só age em `BETA_USER` e captura `DataAccessException` para escrever o `503` pelo `ProblemWriter`;
  - `TermsAcceptanceService` (`INSERT ... ON CONFLICT DO NOTHING`, leitura do existente, evento só na inserção);
  - `TermsAcceptanceController` (`POST /api/v1/terms/acceptances` com a idempotência comum);
  - métricas e log só com a versão e o `source`.

  Os testes do Passo 2 ficam verdes. (US4.1, US4.2; NFR2.1, NFR2.2, NFR2.5, NFR3.2, NFR8.x)
- [x] **Passo 4** — Testes de integração depois da implementação:
  - `TermsAcceptanceIT`: mesma chave grava uma vez; dois aceites concorrentes geram um registro; `409`; outro tenant; `UPDATE` e `DELETE` continuam recusados;
  - `TermsReacceptanceRoutesIT`: percorre o `RouteRegistry` com uma pessoa desatualizada, e o teste obrigatório do time diz que só as rotas liberadas passam;
  - `ReacceptanceWorkerIT`: o bot ativo continua no ciclo do worker, e as rotas de bot recebem `403`;
  - `ReacceptanceMigrationTest`;
  - `EXPLAIN` da consulta da trava;
  - `ProcessRoleContextIT` sem o filtro no worker.

  (NFR2.3, NFR2.6, NFR3.4, NFR3.5, NFR5.1, NFR9.1)
- [x] **Passo 5** — `GET /api/v1/account` com `terms: TermsStatus` (`AccountQueryService`), com testes. `POST /api/v1/terms/acceptances` entra no `route-registry.yml` com escopo `CLIENT` e o teste negativo de tenant. (BR3.10; L2)
- [x] **Passo 6** — OpenAPI: regenerar o `openapi-guest.yaml` com a rota de aceite, o campo `terms` da Conta e `currentVersion` no problema. Atualizar o bloco L2 do resumo de contratos no mesmo commit. (contrato e código juntos)
- [x] **Passo 7** — Seeder do smoke (`SmokeAccountSeeder`, perfil `test`): grava o aceite `REACCEPTANCE` da vigente efetiva fora do retorno antecipado, só quando falta, com teste de dupla execução. (infrastructure-specification, "Dados de teste para a trava")

### App do convidado (implementação e testes por camada)

- [x] **Passo 8** — Tipos gerados (`pnpm check:api`). No `guest-api`:
  - o tratador `onTermsReacceptance` é registrado pelo `ApiProvider` com o `router.replace`;
  - o `withRenewal` e o canal ao vivo usam o mesmo tratador;
  - `safeReturnPath`.

  Testes. (BR3.7; NFR2.4)
- [x] **Passo 9** — Componentes e telas:
  - `ReacceptanceScreen` em `/novo-aceite` (carregando, erro, pronto, enviando e `409`);
  - `TermsConsentBoxes` e `LegalSummary`, com links em outra aba;
  - `InviteScreen` passa a usá-los, com as caixas desabilitadas enquanto carrega ou com falha;
  - `TermsStatusPanel` na Conta.

  Textos em pt-BR, `data-testid` nos elementos interativos e acessibilidade do NFR4.1. (US2.3, US4.1, US4.2)
- [x] **Passo 10** — Testes Jest, de 10 a 15 por componente:
  - ordem de foco por teclado, rótulos, títulos h1 e h2, regiões e `aria-live`;
  - botão desabilitado com o motivo em texto;
  - `409` recarrega os documentos e desmarca as caixas;
  - rota de volta externa recusada;
  - estados do convite;
  - painel da Conta em dia e desatualizado.

  Piso de 80%. (NFR4.1, NFR4.2)

### Ponta a ponta, carga e documentação

- [x] **Passo 11** — Spec `apps/web/e2e/reacceptance/smoke.spec.ts`:
  1. gera uma carteira de teste;
  2. insere pelo `DATABASE_URL` uma conta `BETA_USER` com aceite só da `v1`;
  3. entra, chama uma rota bloqueada e é levada a `/novo-aceite`;
  4. confere teclado e foco, aceita e volta;
  5. confere que a rota bloqueada passa.

  O responsável acrescenta o spec ao `verify.sh`. (US4.1, US4.2)
- [x] **Passo 12** — Cenário de carga `reacceptance`:
  - **`scripts/load-test.sh`:** caso novo, com a mensagem de erro atualizada e `DATABASE_URL` exportado;
  - **despacho:** `scripts/load/load-test.mjs` despacha para o cenário;
  - **cenário:** `scripts/load/reacceptance.mjs` com 20 pessoas desatualizadas; reprova com p95 do aceite acima de 500 ms ou da trava acima de 5 ms, lido do histograma;
  - **teste:** `scripts/load/load-test-reacceptance.test.mjs`.

  O responsável acrescenta a opção ao `load-test.yml`. (NFR3.1, NFR3.2)
- [x] **Passo 13** — Documentação em `docs/api/`: rota de aceite, trava e lista de rotas liberadas, e como publicar uma versão nova dos termos (a trava passa a valer para todos).
- [x] **Passo 14** — Rodar:
  - os comandos da Unit (`unit-test-instructions.md`);
  - o `mvnw verify` completo com JaCoCo;
  - Jest com cobertura no web;
  - `bash scripts/verify.sh` pelo ponto de controle da Unit, depois do pré-requisito do responsável.

  (todas)

### Volta do Build and Test (Loop-back 1, 2026-11-11)

O Build and Test mediu a trava com p95 de 16,8, 8,4 e 7,0 ms contra 5 ms (NFR3.2), numa rajada de 20 pessoas logo depois da subida da API. O pool de tamanho fixo (`minimum-idle` = 20 no `application.yml`) já entrou dentro daquele estágio. A meta de 5 ms não muda.

- [x] **Passo 15** — Aquecer o caminho da consulta da trava na subida da API, sem guardar estado em memória (NFR3.4):
  - **componente:** `TermsReacceptanceWarmup` no `identity/infra`, disparado no `ApplicationReadyEvent` só no processo da API (não no worker nem no `migrate`);
  - **o que faz:** abre ao mesmo tempo as conexões do pool (até `maximum-pool-size`) e roda em cada uma, algumas vezes, a mesma `JdbcTermsAcceptanceLookup.SQL` com UUIDs aleatórios e a versão vigente, para o driver preparar a consulta no servidor (o pgjdbc prepara depois de 5 execuções por conexão) e a JVM compilar o caminho; o resultado é descartado;
  - **medição:** as execuções do aquecimento NÃO passam pelo temporizador `memetrade.terms.reacceptance.check`, que continua medindo só a checagem das requisições;
  - **falhas:** uma falha do aquecimento vira `WARN` com a classe da exceção e nunca impede a API de subir (a trava continua falhando fechada pelo NFR2.5);
  - **testes (depois do código, é infraestrutura):** o aquecimento roda a consulta nas conexões, não registra amostra no temporizador, não roda no worker e não derruba a subida quando o banco falha.
  (NFR3.2, NFR3.4, NFR2.5)
- [x] **Passo 16** — Medir separadamente a espera por conexão e a consulta no cenário `reacceptance`:
  - `scripts/load/reacceptance.mjs` lê também o histograma `hikaricp_connections_acquire_seconds` do pool `memetrade-pool` entre os dois instantâneos e grava no relatório `acquireP95Ms` ao lado de `checkP95Ms`, só como informação (a reprovação continua sendo `checkP95Ms` acima de 5 ms);
  - teste em `scripts/load/load-test-reacceptance.test.mjs` para a leitura do novo histograma;
  - rodar a Unit (`unit-test-instructions.md`) e o `mvnw verify` completo.
  (NFR3.2)

## Rastreabilidade história → passos

| História | Passos |
|---|---|
| US2.3 | 9, 10 |
| US4.1 | 2, 3, 4, 8, 9, 10, 11 |
| US4.2 | 2, 3, 4, 5, 6, 9, 10, 11, 12 |

## Pré-requisitos do responsável do projeto

1. `scripts/verify.sh`: acrescentar `e2e/reacceptance` ao fim da lista de specs do Playwright, antes do ponto de controle da U3.
2. `.github/workflows/load-test.yml`: acrescentar a opção `reacceptance` e trocar o fim da descrição por `..., u5, legal or reacceptance)`, antes do Build and Test.
