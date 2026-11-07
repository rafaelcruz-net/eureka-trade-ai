# Plano de geração de código — U2 Trilha de auditoria

## Summary

- Builds: trilha de auditoria completa — validação no publicador, consulta administrativa, retenção de 12 meses com papel de banco próprio, limpeza da tabela de saída, papéis e migrações fora da API, medidas e varredura
- Touches: `apps/api/` (módulo `audit`, `shared`, `identity/web`, migrações, testes), `infra/postgres/`, `docker-compose.yml`, `infra/docker-compose.test.yml`, `.env.example`, `scripts/`, `.github/workflows/ci.yml`, `docs/`
- Tests: cerca de 150 testes de backend (unitários e de integração com JUnit 5, Testcontainers e ArchUnit), smoke ampliado e um script de desempenho local, com o piso de 80% de linhas do backend mantido
- Volta 1 (Build and Test, 2026-11-06): só os Passos 19 e 20. `JdbcAuditEventReaderIT` passa a ler o plano num banco próprio, e `traceability.json` é corrigido. Nenhum código de produção muda, e os Passos 1 a 18 ficam como estão.

## Entradas

Plano da Unit `u2-audit-trail` ([unit-of-work.md](../../../inception/units-generation/unit-of-work.md)), que implementa a história US7.3 e os requisitos FR2.3, FR3.3 e NFR7 de [requirements.md](../../../inception/requirements-analysis/requirements.md), além dos NFR detalhados da Unit.

| Documento | O que dá ao código |
|---|---|
| [functional-spec.md](../functional-design/functional-spec.md) | Fluxos F1 a F6, rota `GET /api/v1/admin/audit-events`, máquinas de estado da tabela de saída e do evento |
| [rules.md](../functional-design/rules.md) | BR1.1 a BR1.4, BR2.1 a BR2.5, BR3.1 a BR3.4, BR4.1 e BR4.2 |
| [entities.md](../functional-design/entities.md) | `AuditEvent` (com `recordedAt`), `AuditOutboxEntry`, `AuditEventQuery`, `AuditRetentionPolicy`, `AuditRetentionRun` |
| [contract-summary.md](../../../inception/contract-design/contract-summary.md) | C8 (operação nova de consulta), C9 (publicador e tabela de saída), C10 (contexto de autenticação), regras comuns da API |
| [performance-design.md](../nfr-design/performance-design.md) | Índices com `event_id`, busca por cursor sem `OFFSET`, lotes de 1000, autovacuum próprio |
| [security-design.md](../nfr-design/security-design.md) | Passo de preparo dos papéis, três logins, gatilho com três ramos, migrações fora da API, regras ArchUnit, logs do PostgreSQL, varredura |
| [infrastructure-specification.md](../infrastructure-design/infrastructure-specification.md) | Serviços `db-bootstrap` e `db-migrate`, healthcheck TCP, variáveis do `.env`, script de desempenho local |

## Desvios do desenho

- **Agregação de `REQUEST_FORBIDDEN` removida** (decisão do responsável do projeto, 29/10/2026, antes deste plano). Continua valendo a regra BR2.1 do design funcional: todo `403` grava um `REQUEST_FORBIDDEN`. Saem do desenho não funcional e da infraestrutura:
  - o decorador agregador da porta `ForbiddenRequestRecorder`;
  - a ação `REQUEST_FORBIDDEN_REPEATED`, o teto de 500 chaves e o resumo de estouro;
  - a tarefa de resumo a cada 10 s;
  - a métrica `audit_forbidden_aggregated_total` e o alerta de recusas repetidas.

  Ficam a métrica `audit_events_recorded_today` e o alerta de volume diário acima de 80 mil. A decisão vira um ADR no Passo 18. Os documentos de NFR Design e de Infrastructure Design da U2 ainda descrevem a agregação e precisam ser corrigidos numa revisão dessas etapas.
- **Smoke sem consulta de administrador.** A entrada do administrador só existe na U8, e o contexto de administrador fica só no código de teste (NFR6.1). O smoke confere o `403` auditado de um convidado na rota da trilha e a saúde da trilha. A consulta com papel de administrador é coberta pelos testes de integração (Passo 7).

## Decisões de implementação

- **Pacotes:** tudo no módulo `audit`, que tem `api` (interfaces públicas), `domain` (consulta, cursor, política de retenção, validação), `infra` (JDBC, tarefas, métricas, saúde) e `web` (rota administrativa). Nenhum outro módulo lê as tabelas da trilha.
- **Logins de banco:** o pool principal usa `DB_USER`. O pool de retenção é um segundo `DataSource` HikariCP (`maximumPoolSize=1`, `minimumIdle=0`) com `DB_RETENTION_USER` e `DB_RETENTION_PASSWORD`, injetado só no expurgo. A credencial do dono só existe no perfil `migrate`.
- **Perfil `migrate`:** aplica o Liquibase com `spring.liquibase.user` e `spring.liquibase.password` do dono, sem servidor web e sem agendadores, e termina. No perfil padrão, `spring.liquibase.enabled=false`.
- **Execução única:** ShedLock (`shedlock-spring` e `shedlock-provider-jdbc-template`) com versão fixa no `pom.xml` e tabela `shedlock` criada por migração.
- **Mudança registrada no contrato C8:** a operação `GET /api/v1/admin/audit-events` entra num grupo OpenAPI administrativo gerado do código (`apps/api/openapi/openapi-admin.yaml`). O grupo do convidado continua sem rotas `/api/v1/admin`.

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

## Como a ordem de testes se aplica nesta Unit

- **Testes antes do código:** nenhum. A trilha de auditoria não faz parte do núcleo financeiro (limites de risco, ledger, P&L e deduplicação). A deduplicação por `event_id` já existe desde a U1 e só tem os testes mantidos verdes.
- **Testes depois de cada camada (todos os passos):** cada passo implementa a camada e, no mesmo passo, escreve e executa os testes dela antes de seguir.
- **Executores prontos antes do primeiro teste:** o Passo 2 deixa a base de integração com os três logins e o comando da U2 rodando verde antes do Passo 3.
- **Volume:** 10 a 15 testes por componente, com unitários, integração, o smoke de ponta a ponta e testes de desempenho e de segurança, porque os NFRs pedem. O desempenho com a trilha cheia roda pelo script local do Passo 17.
- **Suíte da U1:** precisa continuar verde depois da troca da base de integração (Passo 2) e da troca de posse no banco (Passo 3).

## Passos

Cada passo indica a história (US7.3), as regras (BR) e os requisitos detalhados (NFR) que implementa. "Fundação" marca o que vem das responsabilidades da Unit sem história própria.

### Fundação e executores

- [x] **Passo 1 — Dependências e configuração.**
  - `pom.xml`: ShedLock (`shedlock-spring`, `shedlock-provider-jdbc-template`) com versão fixa.
  - `AuditProperties` ampliado e validado na partida: `retentionMonths` (padrão 12, mínimo 12), `outboxDeliveredRetention` (7 dias), horários `0 0 3 * * *` e `0 30 3 * * *` com fuso `UTC`, limite de execução de 30 minutos, lote de 1000.
  - Segundo `DataSource` de retenção (`maximumPoolSize=1`, `minimumIdle=0`) a partir de `DB_RETENTION_USER` e `DB_RETENTION_PASSWORD`.
  - Perfil `migrate`: Liquibase com o login do dono, `web-application-type=none`, agendadores desligados, saída ao terminar.
  - Perfil padrão com `spring.liquibase.enabled=false`; pool do agendador com 3 linhas de execução.
  - Nada da credencial do dono no perfil padrão.
  - Fundação; NFR6.2, NFR6.3, NFR4.6, NFR13.1.
- [x] **Passo 2 — Base de testes com os três logins.**
  - `AbstractPostgresIT` passa a:
    - subir o PostgreSQL com os parâmetros da U2 (`track_commit_timestamp=on` e os parâmetros de log);
    - rodar o script de `infra/postgres/bootstrap/` como superusuário do contêiner;
    - aplicar as migrações com o login do dono;
    - conectar o pool principal com o login de aplicação e o de retenção com o login de retenção.
  - Marcador JUnit `u2` para os testes desta Unit.
  - Apoio de teste para uma sessão de administrador, criada só no código de teste.
  - Um teste de subida do contexto roda verde com o comando da U2 registrado em [unit-test-instructions.md](unit-test-instructions.md). A suíte da U1 roda verde.
  - Fundação; NFR12.3.

### Banco de dados

- [x] **Passo 3 — Papéis, posse e migração da U2.**
  - `infra/postgres/bootstrap/10-roles.sql`, idempotente:
    - cria os logins do dono, da aplicação e da retenção e o papel `audit_retention` (`NOLOGIN`);
    - concede `audit_retention` só ao login de retenção;
    - roda `REASSIGN OWNED` da aplicação para o dono;
    - revoga `CREATE` no schema de `PUBLIC` e da aplicação;
    - registra os privilégios padrão do dono para a aplicação (tabelas e sequências) e concede os privilégios nas tabelas existentes.
  - `infra/postgres/bootstrap/bootstrap.sh`: tenta conectar por TCP a cada 2 s por até 60 s e só então roda o SQL.
  - `infra/postgres/init/01-create-app-user.sh` deixa de dar a posse do schema à aplicação.
  - Migração `0005-audit-trail` com o login do dono:
    - coluna `recorded_at` em `audit_event`: acréscimo nulo, preenchida com `occurred_at` nas linhas existentes;
    - índices `(occurred_at DESC, event_id DESC)`, `(action, occurred_at DESC, event_id DESC)` e `(recorded_at)`, e remoção de `audit_event_action_time_idx`;
    - índice parcial `audit_outbox (delivered_at) WHERE delivered_at IS NOT NULL`;
    - função do gatilho com os ramos `UPDATE`, `TRUNCATE` e `DELETE` (membro de `audit_retention` e antes do corte de 12 meses), sem `SECURITY DEFINER`;
    - revogação de `UPDATE` e `DELETE` em `audit_event` da aplicação; leitura e exclusão para `audit_retention`;
    - tabelas `audit_job_run` e `shedlock`;
    - autovacuum próprio de `audit_event`.
  - Depois, os testes da camada:
    - privilégios de cada login;
    - gatilho: `UPDATE` recusado para todos, `TRUNCATE` recusado inclusive para o dono, `DELETE` recusado para a aplicação e o dono, retenção recusada em evento de 11 meses e aceita em evento de 13 meses;
    - transição de um banco no formato da U1;
    - preparo rodado duas vezes sem erro;
    - parâmetros do PostgreSQL lidos do banco.
  - US7.3; BR1.3, BR3.1, BR3.2; NFR6.2, NFR6.3, NFR6.4, NFR7.1, NFR2.1, NFR2.2, NFR3.1.

### Gravação e entrega (C9)

- [x] **Passo 4 — Validação no publicador.**
  - O publicador confere campos obrigatórios, formato de `action` e a regra do tenant (BR1.2).
  - Recusa chaves proibidas de `details` e valores em forma de JWT, `Bearer` ou assinatura Base58 de 64 bytes em `details` e `targetRef` (BR1.4).
  - Expressões pré-compiladas; exceção com campo e motivo, nunca o valor; contador `audit_publish_rejected_total{reason}`.
  - Depois, os testes:
    - um por chave e por forma proibida;
    - campos faltando e formato inválido;
    - ação desfeita sem evento;
    - mesmo `eventId` duas vezes com uma entrada e um evento;
    - publicação fora de transação recusada;
    - `tenantId` do evento vindo do `Principal` mesmo com outro `tenantId` na requisição.
  - US7.3; BR1.1, BR1.2, BR1.4; NFR4.1, NFR4.7, NFR5.1, NFR7.2.
- [x] **Passo 5 — Relay com `recorded_at` e medida da entrega.**
  - O relay grava `recorded_at` com o relógio injetável.
  - Ao reservar o lote, lê `pg_xact_commit_timestamp(xmin)` e registra `audit_delivery_seconds` do commit até a gravação. Horário nulo vai para `audit_delivery_unmeasured_total`.
  - `audit_events_recorded_today`: contador em memória que zera na virada do dia UTC, com contagem inicial por `recorded_at` na partida.
  - Na partida, confere `track_commit_timestamp` e registra `ERROR` se estiver desligado.
  - Depois, os testes: histograma com horário de commit, amostra sem horário, virada do dia, contagem inicial, mensagem de desligado. Os testes da U1 do relay continuam verdes.
  - US7.3; BR1.1; NFR4.2, NFR9.2, NFR9.5, NFR2.1.

### Consulta (F3)

- [x] **Passo 6 — Domínio da consulta.**
  - `AuditEventQuery` com a validação de BR2.2: período obrigatório de até 31 dias, `to` maior que `from`, `limit` de 1 a 100, `action` em maiúsculas com sublinhado e parâmetro desconhecido recusado.
  - Cursor opaco em Base64 URL com `occurredAt` e `eventId`.
  - Repositório com busca por cursor sem `OFFSET` e consultas preparadas com e sem `action`.
  - Depois, os testes unitários: cursor, fronteiras de 31 dias, `limit` 0, 1, 100 e 101, ação inválida. E os de integração:
    - ordem com desempate por `eventId` estável entre páginas;
    - todos os tenants e eventos sem tenant;
    - endereço completo em `details`;
    - `EXPLAIN` sem ordenação à parte nas duas consultas.
  - US7.3; BR2.2, BR2.3, BR2.4; NFR6.6, NFR3.1.
- [x] **Passo 7 — Rota administrativa.**
  - `GET /api/v1/admin/audit-events`: só o papel `ADMIN` executa a consulta. Sem token, `401 UNAUTHENTICATED`. Token de convidado, `403 FORBIDDEN_ROLE`, gravado pela porta `ForbiddenRequestRecorder` existente, um evento por recusa.
  - Transação de leitura e escrita com `AUDIT_QUERIED` (administrador como ator, `actorType ADMIN`, tenant vazio, `from`, `to` e `action` em `details`). Falha ao gravar devolve `500` sem dados.
  - `Cache-Control: no-store`.
  - Grupo OpenAPI administrativo gerado em `apps/api/openapi/openapi-admin.yaml`.
  - Depois, os testes de integração e de contrato:
    - `200` com administrador;
    - `401`;
    - `403` com o evento `REQUEST_FORBIDDEN`;
    - `400` com a lista de campos;
    - `AUDIT_QUERIED` sem itens da resposta;
    - `500` sem dados quando a gravação falha;
    - cabeçalho `no-store`;
    - OpenAPI administrativo gerado igual ao versionado e convidado sem rotas administrativas.
  - US7.3; BR2.1, BR2.5; NFR6.1, NFR6.7, NFR7.5.

### Retenção e limpeza (F4, F5)

- [x] **Passo 8 — Estado das tarefas e execução única.**
  - Registro `audit_job_run` (última execução, último resultado, última bem-sucedida, último corte).
  - ShedLock com provedor JDBC, `lockAtMostFor` de 35 minutos e `lockAtLeastFor` de 5 minutos.
  - Agendamentos em UTC.
  - Depois, os testes: duas chamadas simultâneas geram uma execução; a outra termina sem apagar e sem erro; o registro sobrevive a reinício do contexto.
  - Fundação; BR3.2; NFR4.5, NFR4.6, NFR2.4.
- [x] **Passo 9 — Expurgo de retenção.**
  - Corte: início do dia UTC menos `retentionMonths`, pelo relógio injetável.
  - Lotes de até 1000 pelo pool de retenção, cada um numa transação; para no primeiro erro ou com 30 minutos decorridos.
  - `AUDIT_RETENTION_PURGED` com corte, quantidade, resultado (`COMPLETED`, `FAILED` ou `TIME_LIMIT`) e `correlationId`, em transação própria pelo pool principal.
  - Atualiza `audit_job_run`; métricas `audit_job_runs_total`, `audit_job_deleted_total`, `audit_job_duration_seconds` e `audit_job_last_success_timestamp_seconds`; logs de início e fim.
  - Depois, os testes:
    - fronteira do corte (no corte e um instante antes);
    - lote com falha para e mantém o que apagou;
    - limite de tempo com limite reduzido no teste;
    - conteúdo do evento;
    - evento gravado também em falha;
    - nada apagado dentro dos 12 meses;
    - corte calculado igual com o relógio em fuso de Brasília.
  - US7.3; BR3.1, BR3.2, BR3.3; NFR3.3, NFR4.4, NFR7.4, NFR13.1.
- [x] **Passo 10 — Limpeza da tabela de saída.**
  - Apaga em lotes de até 1000 as entradas com `delivered_at` anterior a agora menos 7 dias, pelo pool principal.
  - Limite de 30 minutos e mesmo registro e métricas do Passo 9.
  - Depois, os testes: entrega de 8 dias sai; entrega de 6 dias fica; pendente de 30 dias fica; parada por limite.
  - US7.3; BR3.4; NFR4.3, NFR2.2, NFR7.4.
- [x] **Passo 11 — Recuperação ao subir e saúde.**
  - Quando a aplicação fica pronta, cada tarefa roda se não tiver registro ou se a última bem-sucedida tiver mais de 24 h, sempre pelo ShedLock.
  - Indicadores `auditRetention` e `auditOutboxCleanup`: degradados sem registro, com `FAILED` ou `TIME_LIMIT`, ou com a última bem-sucedida há mais de 26 h. Degradado responde HTTP 200, como na U1.
  - Depois, os testes com relógio injetável: 23 h e 25 h (roda ou não ao subir), 25 h e 27 h (saudável ou degradada), banco sem registro (roda e fica degradada até o sucesso), prontidão sem mudança.
  - Fundação; NFR4.5, NFR9.1.

### Observabilidade, arquitetura e varredura

- [x] **Passo 12 — Métricas e logs.**
  - Métricas de [observability-design.md](../nfr-design/observability-design.md), menos `audit_forbidden_aggregated_total`.
  - Logs com os campos e níveis definidos; `correlationId` próprio por execução agendada.
  - Depois, os testes: as métricas existem na saída Prometheus; nenhum rótulo com endereço, `actor`, `tenantId`, `eventId` ou valor de `details`; logs da recusa do publicador sem o valor recusado.
  - Fundação; NFR9.2, NFR9.3, NFR9.4.
- [x] **Passo 13 — Regras de arquitetura.** Regras ArchUnit novas:
  - `Principal` criado só no filtro de sessão;
  - `Role.ADMIN` referenciado só na regra de autorização das rotas administrativas;
  - classes fora de `audit` sem dependência de `audit.infra` nem de `audit.domain`;
  - o `DataSource` de retenção injetado só no expurgo.

  Depois, a execução das regras e um teste de configuração: o perfil padrão não tem credencial do dono e tem o Liquibase desligado.
  - Fundação; NFR6.1, NFR6.2, NFR6.3, NFR5.1.
- [x] **Passo 14 — Varredura ampliada.**
  - `LogScanIT` passa a ler os logs do contêiner do PostgreSQL, a saída de métricas e as colunas `audit_outbox.payload` e `audit_event.details`.
  - Procura os tokens de acesso e de renovação, os nonces e as assinaturas emitidos nos testes, qualquer JWT, `Authorization` e `Bearer`, e o endereço completo fora de `details`.
  - Relatório com local e tipo, nunca o valor.
  - Depois, um teste que planta cada tipo de valor proibido e confere a falha, e um teste que confere que o endereço completo em `details` não é achado.
  - US7.3; BR4.1, BR4.2; NFR7.1, NFR7.3.

### Ambiente local e CI

- [x] **Passo 15 — Docker Compose e `.env`.**
  - `docker-compose.yml`:
    - serviços `db-bootstrap` (imagem do `postgres`, roda `bootstrap.sh`) e `db-migrate` (imagem da `api`, perfil `migrate`);
    - healthcheck TCP do `postgres` e parâmetros na linha de comando;
    - `api` dependendo de `db-migrate` com término bem-sucedido e sem as senhas do dono e do superusuário;
    - cabeçalho com o modo de desenvolvimento `docker compose up postgres db-bootstrap db-migrate`.
  - `infra/docker-compose.test.yml` com as mesmas regras.
  - `.env.example` com as chaves novas sem valores.
  - Depois, `ComposeFilesTest` ampliado para as duas composições: credenciais por serviço, ordem de dependências, healthcheck TCP e subida de `db-bootstrap` e `db-migrate` sem a `api`.
  - Fundação; NFR6.3, NFR6.5, NFR4.2, NFR7.1.
- [x] **Passo 16 — Scripts e CI.**
  - `scripts/verify.sh` e `scripts/load-test.sh`: geram as quatro senhas efêmeras. O `verify.sh` espera a primeira execução bem-sucedida do expurgo e da limpeza e confere o `403` auditado de um convidado na rota da trilha e a saúde da trilha sem indicador degradado.
  - `.github/workflows/ci.yml`: passo `compose-config` no job `backend`, sobre as duas composições.
  - Depois, `bash scripts/verify.sh` roda verde no ambiente local.
  - US7.3; NFR6.3, NFR9.1, NFR12.1.
- [x] **Passo 17 — Script de desempenho local.**
  - `scripts/perf-audit.sh`:
    - confere as portas 5432, 8080, 8081 e 3000 livres;
    - usa o projeto Compose `memetrade-perf` com volume próprio e o overlay `infra/docker-compose.test.yml`;
    - semeia cerca de 37 milhões de eventos dos últimos 12 meses e duas cargas de 100 mil eventos de 13 meses;
    - roda a consulta nos três cenários com `EXPLAIN`, a entrega em 1 hora, o expurgo e a limpeza de 100 mil linhas;
    - grava `docs/perf/u2-audit-trail-<data>.md` e devolve código diferente de zero em qualquer meta falha.
  - Não roda na CI. A execução e o relatório são evidência obrigatória do ponto de controle da U2.
  - NFR3.1, NFR3.3, NFR4.2.

### Documentação e rastreabilidade

- [x] **Passo 18 — Documentação.**
  - ADR de papéis de banco e migrações fora da API.
  - ADR da remoção da agregação de recusas, com a decisão de 29/10/2026.
  - `README`: modo de desenvolvimento, limite do rollback para antes da U2 e script de desempenho.
  - `docs/` com a operação nova de C8.
  - Javadoc das interfaces públicas do módulo `audit`.
  - Fundação; NFR6.9, NFR7.6.

### Volta 1 de Build and Test (2026-11-06)

Origem: `construction/build-and-test/test-results.md`, Loop-back 1 (falha F1 e achado T3 de `cross-unit-traceability.md`).

- [x] **Passo 19 — Plano do índice sem depender do banco compartilhado.**
  - **Falha:** na suíte completa, `JdbcAuditEventReaderIT.bothPreparedFormsReadFromTheIndexWithoutASeparateSort` recebeu `audit_event_time_id_idx` com `Filter` em vez de `audit_event_action_time_id_idx`. Sozinho, o teste passa.
  - **Causa:** o `EXPLAIN` roda no banco do Failsafe, compartilhado. As estatísticas do planner sobre `audit_event` dependem do que as outras classes gravaram, e a auditoria só aceita remoção pela retenção. Isso viola a regra "não dependem de estado compartilhado nem da ordem de execução".
  - **Correção:**
    - O teste do plano sai para uma classe própria, `AuditEventReaderPlanIT`.
    - Ela roda num banco só dela (`u2_plan_*`) no mesmo contêiner, criado e migrado pelo mesmo caminho do `AbstractU9MeasurementIT`/`U7VolumeDatabase`.
    - A base comum desse padrão passa a ficar em `testsupport` (por exemplo, `AbstractOwnDatabaseIT` com o prefixo do banco como parâmetro), e `AbstractU9MeasurementIT` passa a estendê-la, sem mudar o comportamento.
    - A classe semeia um volume fixo (seed fixa) com ações variadas e roda `ANALYZE audit_event` antes do `EXPLAIN`.
    - **A asserção não muda:** as duas formas preparadas, com e sem `action`, usam o índice esperado e não têm `Sort` à parte.
    - Os demais testes de `JdbcAuditEventReaderIT` ficam no banco compartilhado, como hoje.
  - **Testes:**
    - `AuditEventReaderPlanIT` verde sozinho e na suíte completa (`mvnw verify`), rodada duas vezes;
    - `-Dgroups=u9` e `-Dgroups=u2` verdes, para confirmar que a base extraída não muda as medições da U9.
  - **Regressão:** a semente da classe nova inclui um grande volume de uma ação dominante, ao lado das ações consultadas. Esse é o cenário que, no banco compartilhado, levou o planner ao índice errado. Assim o teste reproduz a condição da falha dentro do próprio banco.
  - BR2.3, BR2.4; NFR3.1.
- [x] **Passo 20 — Rastreabilidade legível.**
  - Em `code-generation/traceability.json`, `target` passa a ter só caminhos separados por `; `. As notas, por exemplo "p95 com a trilha cheia em `scripts/perf-audit.sh`, ainda não executado", vão para um campo `note`.
  - Entram as linhas que faltam: NFR2.3, NFR3.2, NFR6.8 e NFR10.1, com os alvos que já existem no código e nos testes, ou `N/A` com o motivo do desenho.
  - Conferência: `aidlc engine sensor-traceability --stage build-and-test` não lista mais a U2.
  - Nenhum código muda.

A medição de `scripts/perf-audit.sh` (NFR3.1, 3.3, 4.2 e 9.5) não faz parte desta volta. A proteção do ambiente recusa o script quando o agente tenta rodá-lo, então a execução fica com a pessoa.

## Rastreabilidade com a história

| História e critérios | Passos |
|---|---|
| US7.3 — rastrear ações sensíveis (AC7.3.1 a AC7.3.3) | 3, 4, 5, 6, 7, 9, 10, 14, 16 |
| FR2.3 — exatamente um evento por ação sensível | 4, 5 |
| FR3.3 — ação administrativa auditada | 7 |
| NFR7 — mascaramento, acesso restrito e retenção | 3, 7, 9, 10, 14 |
