# Plano de código — U5 `u5-anonymization`

## Summary

- Builds: a anonimização da conta a partir de um pedido de Exclusão em andamento. São uma transação única no Privacy, com as interfaces J1 a J3, a conta e a carteira sem endereço, os bots parados e renomeados, o feedback apagado pela função `SECURITY DEFINER`, as sessões revogadas e os desafios apagados. Inclui o evento, as métricas, o diálogo T8 no app do administrador e o spec de ponta a ponta com conta descartável.
- Touches: `apps/api` (`privacy` domínio, infra e web; J1 em `identity`, J2 e `LockOrder` em `bots`, J3 em `feedback`; login e renovação; leitores de `wallet`; migração `0015`; `SmokeInviteSeeder`; OpenAPI admin), `infra/postgres/bootstrap/10-roles.sql`, `apps/admin` (diálogo T8 e listagem de contas), `apps/web/e2e/anonymization` e o resumo de contratos (P2, J1 a J3, J6).
- Tests: cerca de 120 testes novos (JUnit 5 e Testcontainers, Jest no admin, Playwright), com os testes da anonimização escritos antes do código e o piso de 80% de linhas.

Conversa: português (Brasil); código, nomes e logs em inglês. Fontes:
- `construction/u5-anonymization/functional-design/` (BR5.1 a BR5.9);
- `nfr-requirements/` e `nfr-design/`;
- `infrastructure-design/`;
- `inception/contract-design/contract-summary.md` (P2, J1 a J3, J6);
- `inception/user-stories/stories.md` (US6.1 a US6.3).

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
  - migração `0015-anonymization.sql`, registrada no `db.changelog-master.yaml`, um changeset por mudança, cada um com `--rollback` e o comentário de que o rollback do `NOT NULL` só vale antes da primeira anonimização:
    - `app_user_status_ck` recriado com `ANONYMIZED`;
    - `wallet.address` e `wallet.provider` sem `NOT NULL`, coluna `wallet.anonymized_at`;
    - `wallet_anonymized_ck`: `(anonymized_at IS NULL) = (address IS NOT NULL) AND (address IS NULL) = (provider IS NULL)`;
    - `wallet_address_base58_ck` recriado como `address IS NULL OR address ~ ...`;
    - índice `login_challenge_wallet_idx ON login_challenge (wallet_address)`;
    - função `erase_feedback_of_user(p_tenant uuid, p_user uuid) RETURNS integer`, `SECURITY DEFINER`, `SET search_path = public, pg_temp`, `REVOKE ALL ... FROM PUBLIC`, `GRANT EXECUTE` ao login da aplicação; o gatilho `feedback_entry_guard` não muda;
  - `infra/postgres/bootstrap/10-roles.sql`: `GRANT feedback_retention TO` o dono (idempotente), revogação só do login da aplicação, comentário do bloco U8 reescrito;
  - códigos novos em `ErrorCode` (`CONFIRMATION_REQUIRED`, `REQUEST_NOT_ELIGIBLE`, `NO_LINKED_ACCOUNT`, `ADMIN_ACCOUNT_NOT_ANONYMIZABLE`, reaproveitando `LOCK_TIMEOUT` se já existir);
  - ação `ACCOUNT_ANONYMIZED` em `AuditActions`, com o validador recusando outra chave em `details`;
  - entrada de idempotência `DATA_REQUEST_ANONYMIZE`;
  - confirmar os comandos da Unit.

  (todas; NFR5.3, NFR2.3)

### Anonimização (testes antes do código: dados pessoais)

- [x] **Passo 2** — Testes escritos antes, que falham (Red) antes dos Passos 3 a 5:
  - **`AnonymizationServiceTest`** (BR5.1, BR5.5): cada recusa na ordem do desenho (`422 CONFIRMATION_REQUIRED`, `409 REQUEST_NOT_ELIGIBLE`, `409 NO_LINKED_ACCOUNT`, `409 ADMIN_ACCOUNT_NOT_ANONYMIZABLE`, `409 IDENTITY_NOT_VERIFIED` na origem `EMAIL`); sucesso chama J2, J3, J1, J6 e a conclusão nessa ordem; repetição numa conta `ANONYMIZED` devolve o mesmo resultado com `alreadyAnonymized = true`, sem escrita nem evento;
  - **`BotAnonymizeStopTest`** (BR5.3): `ACTIVE`, `PAUSED` e `CLOSING_PENDING` vão para `STOPPED` sem fechamento; `DRAFT` e `VALIDATED` ficam; todos renomeados para "Bot anonimizado";
  - **`AnonymizationTransactionIT`** (BR5.2): conta `ANONYMIZED`, carteira sem endereço e provedor com `anonymized_at`, sessões revogadas, desafios do endereço apagados, feedback da pessoa apagado, evento e pedido `DONE`;
  - **`AnonymizationRollbackIT`** (NFR5.2): falha injetada em J2, J3, J1, J6 e na conclusão; tudo volta e nenhum evento sai;
  - **`AnonymizationKeepsHistoryIT`** (NFR5.1): ledger, ordens, fills, recusas, aceites e trilha iguais, reconciliação com 0 divergência;
  - **`AnonymizationNoWalletLeftIT`** (BR5.4, teste obrigatório do time): varre todas as colunas `text`, `varchar`, `char` e `jsonb` do esquema e o log capturado, sem o endereço completo;
  - **`AnonymizationConcurrencyIT`** (BR5.6, NFR5.5, NFR5.6): duas anonimizações do mesmo pedido; login segurado entre a leitura e a abertura da sessão; renovação; ciclo de bot. Nenhuma sessão `ACTIVE` nem ordem nova depois do commit. O caso de login falha antes do Passo 4;
  - **`AnonymizationLockTimeoutIT`** (NFR5.4): trava de bot segura por 3 s dá `503 LOCK_TIMEOUT`, nada muda, a repetição com a mesma chave conclui depois;
  - **`AnonymizedAccessIT`** (BR5.7, NFR2.2): token emitido antes recebe `401` na requisição seguinte; login e renovação recusados; convite novo com a mesma carteira cria conta nova sem histórico;
  - **`FeedbackEraseFunctionIT`** (BR5.8, NFR2.3): o preparo de papéis roda duas vezes; o `DELETE` direto da aplicação falha; a função apaga só a pessoa pedida.

  (US6.1 a US6.3)
- [x] **Passo 3** — Interfaces públicas, todas com `Propagation.MANDATORY`:
  - **J2, `BotOwnerDataPort.anonymizeOwnerBots(tenantId, userId)`:** `LockOrder` ganha o passo de vários bots (`FOR UPDATE ORDER BY bot_id`); regra `anonymizeStop`; renomeação;
  - **J3, `FeedbackOwnerDataPort.deleteByUser(tenantId, userId)`:** chama `erase_feedback_of_user` e devolve a contagem;
  - **J1, `AccountAnonymizationPort.anonymizeAccount(tenantId, userId, now)`:** sessões `FOR UPDATE` por `session_id`, conta `FOR UPDATE`, revogação das sessões depois da trava da conta, desafios do endereço, carteira, status; devolve `AnonymizationResult`.

  Os blocos J1 a J3 e J6 do resumo de contratos são atualizados no mesmo commit. (BR5.2, BR5.3, BR5.6, BR5.8)
- [x] **Passo 4** — Identity:
  - login: `JdbcAccountRepository.lockActiveForSession(userId)` (`SELECT status ... FOR SHARE`) antes de `sessions.open`, recusando como conta inexistente se não for `ACTIVE`;
  - renovação: o mesmo `FOR SHARE` depois da trava da sessão, recusando com `401 SESSION_REVOKED`;
  - leitores de `wallet` com carteira nula: `JdbcAccountDetailsRepository` (`Optional`), `JdbcIdentityDirectory.walletOf` (`Optional.empty()`), listagem administrativa ("Conta anonimizada"), J1 `personSummary` (`Optional.empty()`); `JdbcPersonDataRepository` já usa `left join`. Cada leitor ganha teste com carteira nula.

  (BR5.6, BR5.7; NFR2.2, NFR5.6)
- [x] **Passo 5** — Privacy:
  - `AnonymizationService`: trava do pedido `FOR UPDATE`, `set local lock_timeout` com `memetrade.bots.lock-timeout`, J2, J3, J1, J6 e conclusão com a resposta modelo de exclusão e `DATA_REQUEST_TRANSITIONED`;
  - `AnonymizationMetrics`: `memetrade_accounts_anonymized_total` depois do commit; `memetrade_account_anonymization_failures_total{reason}` pré-registrado em zero para `LOCK_TIMEOUT` e `ROLLED_BACK`, incrementado no `afterCompletion` de rollback; timer `memetrade_account_anonymization_seconds`;
  - logs `INFO` e `ERROR` só com `requestId`, contagens e classe da exceção.

  Os testes do Passo 2 ficam verdes. (US6.2; NFR3.x, NFR8.x)
- [x] **Passo 6** — Rota `AdminAnonymizationController` (`POST /api/v1/admin/privacy/requests/{id}/anonymization`, idempotência, `ADMIN` no `route-registry.yml`). OpenAPI admin regenerado e bloco P2 do resumo de contratos atualizado. (US6.1; NFR2.1)
- [x] **Passo 7** — Testes de integração depois da implementação:
  - `AdminAnonymizationIT`: papel, recusas, sucesso, repetição com a mesma chave;
  - `AnonymizationLatencyIT`: 20 contas com 20 bots, 100 sessões e 50 feedbacks, p95 ≤ 2 s;
  - `AnonymizationIndexIT`: `EXPLAIN` dos cinco comandos com `enable_seqscan = off`;
  - `AnonymizationMigrationIT`: aplicar, rollback de cada changeset sem conta anonimizada, os dois `CHECK` com linhas válidas e inválidas;
  - `MigrationsBackwardCompatIT` estendido para a `0015` (relaxamento de `NOT NULL` permitido; login, renovação e listagem da imagem anterior sem conta anonimizada);
  - `U8PrivilegesIT` e `RolesBootstrapIT` com o dono em `feedback_retention` e a aplicação fora;
  - métricas, auditoria (`ACCOUNT_ANONYMIZED` só com contagens) e `LogScanIT` com a agulha do endereço anonimizado.

  (NFR1.x, NFR3.x, NFR5.3, NFR8.x, NFR9.1)

### App do administrador

- [x] **Passo 8** — Tipos gerados no admin (`check:api`). `AnonymizeAccountButton` no detalhe de um pedido de Exclusão `IN_PROGRESS` com conta ligada (T6a) e `AnonymizeAccountDialog` (T8): o que sai e o que fica, campo com rótulo visível, botão habilitado só com `ANONIMIZAR` e motivo em texto, laço de foco, Esc, `aria-live` com ícone e texto; listagem de contas com "Conta anonimizada". (US6.1; NFR4.1, NFR4.2)
- [x] **Passo 9** — Testes Jest no admin, de 10 a 15 por componente: visibilidade do botão, habilitação, `409` e `422` em texto, sucesso fecha e atualiza, teclado e foco, Esc devolve o foco, listagem anonimizada. Piso de 80%. (NFR4.1)

### Ponta a ponta e documentação

- [x] **Passo 10** — `SmokeInviteSeeder` (perfil `test`) semeia um segundo convite com `SMOKE_ANONYMIZATION_INVITE_CODE`. Spec `apps/web/e2e/anonymization/smoke.spec.ts`, com conta descartável e carteira efêmera de teste:
  1. entra pelo convite, aceita os termos e cria um pedido de Exclusão na Conta;
  2. com a carteira de administrador do smoke, abre o pedido, passa para `IN_PROGRESS`, anonimiza pelo diálogo T8;
  3. confere o pedido `DONE` e a listagem com "Conta anonimizada";
  4. confere que o token antigo recebe `401` e que um novo login com a mesma carteira é recusado.

  O responsável acrescenta o spec e a variável ao `verify.sh`. (US6.1 a US6.3)
- [x] **Passo 11** — Documentação em `docs/api/u5-anonymization.md`: rota, recusas, o que sai e o que fica, ordem de travas, rollback (correção para frente depois da primeira anonimização) e o risco aceito do papel `feedback_retention`.
- [x] **Passo 12** — Rodar:
  - os comandos da Unit;
  - o `mvnw clean verify` completo com JaCoCo, sem exportar a chave HMAC;
  - lint, tipos, formatação, `check:api` e `test:coverage` no web e no admin;
  - `bash scripts/verify.sh` pelo ponto de controle.

  (todas)

## Rastreabilidade história → passos

| História | Passos |
|---|---|
| US6.1 | 1, 2, 5, 6, 7, 8, 9, 10 |
| US6.2 | 1, 2, 3, 4, 5, 7, 10 |
| US6.3 | 2, 4, 7, 10 |

## Pré-requisitos do responsável do projeto

1. `scripts/verify.sh`: gerar e exportar `SMOKE_ANONYMIZATION_INVITE_CODE`; a lista de specs termina em `... e2e/legal-documents e2e/reacceptance e2e/data-requests e2e/anonymization`; e o pendente da U2 (exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` fora do bloco do `.env`).
2. Os pendentes de `ci.yml` e `load-test.yml` das Units anteriores.
