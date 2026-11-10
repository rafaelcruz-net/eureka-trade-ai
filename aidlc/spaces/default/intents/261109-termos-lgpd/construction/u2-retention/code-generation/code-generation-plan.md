# Plano de código — U2 `u2-retention`

## Summary

- Builds: auditoria só com carteira mascarada e HMAC, expurgos de desafios e de sessões com saúde e recuperação, e o catálogo de retenção do módulo novo `privacy`, conferido contra os expurgos.
- Touches: `apps/api` (`audit` publicador, validador, codec e relay; `identity` jobs, login e convite; `shared/retention`; `privacy` novo; declarações em `feedback`, `bots`, `riskcontrol`; migração `0012`; `application.yml`), `docker-compose.yml`, `.env.example`, `scripts/load-test.sh`, `scripts/perf-audit.sh`, `docs/` e o contrato J7.
- Tests: cerca de 110 testes novos de unidade e integração (JUnit 5 e Testcontainers), com os testes das regras de expurgo escritos antes do código, um teste de desempenho com 100 mil linhas e o piso de 80% de linhas.

Conversa: português (Brasil); código, nomes e logs em inglês. Fontes:
- `construction/u2-retention/functional-design/` (BR2.1 a BR2.19);
- `nfr-requirements/` (NFR1.1 a NFR9.1);
- `nfr-design/`;
- `infrastructure-design/`;
- `inception/contract-design/contract-summary.md` (J5, J6);
- `inception/user-stories/stories.md` (US1.4, US3.1, US3.2).

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
  - migração `0012-retention.sql`, só aditiva, com o rollback de cada changeset declarado: coluna `audit_event.wallet_hmac`, índice parcial `audit_event_wallet_hmac_idx`, tabela `identity_job_run` e índice de expressão `user_session_end_idx` sobre `coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at))`;
  - propriedade `memetrade.audit.wallet-hmac-key` validada (32 bytes ou mais) e sanitizada no actuator;
  - pool do agendador 18 em `application.yml` e em `RuntimeSettingsGuard.MIN_SCHEDULER_POOL`;
  - chave fixa de teste em `application-test.yml`;
  - confirmar que o comando Maven da Unit roda.

  (todas as histórias; NFR5.1, NFR6.1, NFR7.5)

### Expurgos do Identity (testes antes do código: lógica de dados pessoais)

- [x] **Passo 2** — Testes escritos antes, com relógio injetável e Testcontainers. O comando deles falha (Red) antes do Passo 3.
  - `LoginChallengePurgeJobTest` (BR2.5): desafio vencido há 24 h ou mais sai em qualquer estado; o mais recente fica; o limite exato de 24 h; lotes de 1000 até um lote menor.
  - `SessionPurgeJobTest` (BR2.6): a sessão revogada sai 30 dias depois de `revoked_at`; a não revogada sai pelo menor vencimento; uma sessão com fim no futuro nunca sai, mesmo com estado `REVOKED` ou `EXPIRED`; os digests saem antes e no mesmo lote; vale para sessões de convidado e de administrador.
  - `PurgeHealthIndicatorTest` (BR2.8): `UP` nas primeiras 48 h depois da subida; `DEGRADED` com o último sucesso há mais de 48 h; `UP` de novo depois de um sucesso.

  (US3.1; AC3.1.1, AC3.1.2, AC3.1.3)
- [x] **Passo 3** — Implementar no Identity:
  - o executor comum dos jobs: trava ShedLock de 35 e 5 minutos, lotes por chave primária, `identity_job_run` e o evento `*_PURGED` na mesma transação curta `REQUIRES_NEW`, métricas, captura de `DataAccessException` da trava, `FAILED` sem propagar;
  - `LoginChallengePurgeJob` às 03:20 UTC e `SessionPurgeJob` às 03:40 UTC, só no processo da API;
  - `JdbcIdentityJobRunStore`;
  - os indicadores `loginChallengePurge` e `sessionPurge`;
  - `IdentityJobRecovery` no `ApplicationReadyEvent` (sem linha, sem sucesso ou sucesso há mais de 24 h);
  - as ações `LOGIN_CHALLENGES_PURGED` e `SESSIONS_PURGED` em `AuditActions`.

  Os testes do Passo 2 ficam verdes. (US3.1; NFR7.1 a NFR7.4, NFR8.1 a NFR8.4)
- [x] **Passo 4** — Testes de integração dos expurgos, escritos depois da implementação:
  - recuperação na subida;
  - duas execuções concorrentes, das quais só uma apaga;
  - falha injetada no segundo lote mantém o primeiro e grava `FAILED`;
  - banco indisponível não derruba a API;
  - métricas com o rótulo `purge` e sem dado pessoal;
  - log só com totais;
  - dois tenants, nenhum exposto no evento.

  Teste de desempenho `SessionPurgePerformanceIT` com 100 mil linhas: até 500 ms por lote, até 5 minutos no total e plano com `user_session_end_idx`. (NFR2.1, NFR3.1, NFR3.2, NFR3.5, NFR3.7)

### Auditoria sem carteira completa (testes antes do código: lógica de dados pessoais)

- [x] **Passo 5** — Testes escritos antes (Red):
  - `WalletHasherTest`: mesmo HMAC para a mesma carteira; HMAC diferente para carteiras diferentes; hexadecimal de 64 caracteres; chave curta recusada (BR2.2, BR2.4);
  - `AuditEventValidatorTest` ampliado: endereço que decodifica para 32 bytes é recusado com `FULL_WALLET` e sem o valor, inclusive dentro de listas e mapas; UUID sem hífens, hash hexadecimal, código de convite e digest passam (BR2.3);
  - `WalletAuditIT`: cria conta, faz login aceito e recusado de convidado e de administrador, e procura o endereço completo em `audit_event` e `audit_outbox`, sem encontrar; o HMAC é igual entre eventos da mesma carteira (AC3.2.1, AC3.2.2);
  - teste do login recusado com texto que não é carteira: `actor=anonymous`, sem `walletHmac`, `addressFormat=INVALID` (BR2.15).

  (US3.2)
- [x] **Passo 6** — Implementar no Audit:
  - `WalletHasher` com `Mac` por thread;
  - campo `wallet` e fábrica nova no `AuditEventCommand`, mantendo as fábricas existentes;
  - publicador com máscara e `walletHmac` no payload;
  - `AuditPayloadCodec` com `DecodedAuditEvent`;
  - relay gravando `wallet_hmac`, nula quando o payload não tem o campo (BR2.18);
  - checagem `FULL_WALLET` no validador.

  Chamadores no Identity: `LoginVerificationService`, `AdminLoginService`, `InviteAccountCreation` e os eventos de login aceito passam a usar o campo `wallet`, e o endereço inválido segue BR2.15. Os testes do Passo 5 ficam verdes. (US3.2; NFR1.1 a NFR1.3, NFR3.3)

### Retenção e conformidade

- [x] **Passo 7** — Contrato `com.memetrade.shared.retention.RetentionEnforcerDeclaration` (`purgeId`, prazo e âncora) e um bean de declaração por expurgo, lendo a propriedade do próprio expurgo:
  - Audit: `auditRetention`, `auditOutboxCleanup`;
  - Feedback: `feedbackPurge`;
  - Bots: `decisionPurge`, presente também no contexto da API;
  - RiskControl: `riskcontrol-purge`;
  - Identity: `idempotencyCleanup`, `loginChallengePurge`, `sessionPurge`.

  Bloco `J7-RetentionEnforcerDeclaration` no resumo de contratos, no mesmo commit. (BR2.17)
- [x] **Passo 8** — Módulo novo `com.memetrade.privacy`:
  - `RetentionCatalog` com as 12 linhas na ordem da política, com `GUARD`, `PURGE` e `PLANNED`;
  - `PendingRetention` com `DATA_SUBJECT_REQUESTS`;
  - implementação da porta J5 (`RetentionTableSource`), que substitui a padrão da U1;
  - regra ArchUnit para o Privacy depender só de `shared` e das APIs públicas.

  Testes depois: `RetentionCatalogTest` e `RetentionTableSourceTest` (a do Privacy vence). (US1.4; BR2.9)
- [x] **Passo 9** — `RetentionConformanceTest`:
  - cada linha `PURGE` tem uma declaração com o mesmo prazo e a mesma âncora;
  - `GUARD` vale só para "enquanto a conta existir";
  - `PLANNED` só pela lista de pendências e sem expurgo existente;
  - o `retention.tsv` de cada versão não legacy é igual ao catálogo.

  Uma divergência falha apontando a linha, o prazo declarado e o prazo aplicado. Inclui um teste que altera um prazo de propósito e espera a falha. (AC1.4.1, AC1.4.2; BR2.10 a BR2.12)

### Infraestrutura, scripts e documentação

- [ ] **Passo 10** — `docker-compose.yml`:
  - `MEMETRADE_AUDIT_WALLET_HMAC_KEY: ${MEMETRADE_AUDIT_WALLET_HMAC_KEY:?}` em `api` e `worker`;
  - `SCHEDULER_POOL_SIZE:-18`.

  O `.env.example` recebe o nome da chave, sem valor, e o intervalo "18 to 32 [18]". A geração efêmera da chave entra em `scripts/load-test.sh` e em `scripts/perf-audit.sh`. Ajustar `ComposeFilesTest` e os testes que fixavam 16 (`U4ContextIT`, `U5SettingsTest`, `AuditPropertiesTest`). Teste que lê a migração `0012` e falha com `UPDATE`, `DELETE` ou `DROP` fora do rollback. (NFR5.1, NFR6.2)
- [x] **Passo 11** — Documentação:
  - checklist de privacidade em `docs/`: banco novo, chave no gerenciador de segredos e duas consultas de conferência sem endereço completo em `audit_event.details` e no payload de `audit_outbox` (BR2.13);
  - passo "troca da chave HMAC da auditoria" no runbook de incidentes (BR2.19);
  - nota dos pré-requisitos do responsável: `verify.sh`, `ci.yml` e `.env`.

  (AC3.2.3)
- [ ] **Passo 12** — Rodar os comandos da Unit (`unit-test-instructions.md`), o `mvnw verify` completo com JaCoCo e o `bash scripts/verify.sh` pelo ponto de controle da Unit, depois dos pré-requisitos do responsável. (todas)

## Rastreabilidade história → passos

| História | Passos |
|---|---|
| US1.4 | 7, 8, 9 |
| US3.1 | 1, 2, 3, 4, 10 |
| US3.2 | 1, 5, 6, 10, 11 |

## Pré-requisitos do responsável do projeto

1. `scripts/verify.sh`: gerar e exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` fora do bloco `if [ ! -f .env ]`, antes do ponto de controle da U2.
2. `.github/workflows/ci.yml`: valor de exemplo da chave no `env` do passo `Compose config`, antes do primeiro commit com a U2.
3. `.env` local: acrescentar a chave uma vez.
