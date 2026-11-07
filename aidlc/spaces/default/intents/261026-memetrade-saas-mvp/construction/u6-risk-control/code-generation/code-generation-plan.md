# Plano de geração de código — U6 Barreira de limites

## Summary

- Builds: a barreira de limites `OrderGate` (C14) com as 11 verificações, recusas gravadas com detalhes e evento, deduplicação por decisão; a rota de recusas (C21); o expurgo diário das aprovadas antigas com papel de banco próprio; saúde, arquitetura e o cenário de carga `u6`
- Touches: `apps/api/` (módulo novo `riskcontrol`, migração `0009`, OpenAPI do convidado, configuração, testes), `infra/postgres/bootstrap/10-roles.sql`, `docker-compose.yml`, `infra/docker-compose.test.yml`, `.github/workflows/load-test.yml`, `scripts/load-test.sh`, `scripts/load/`, `docs/`, `contract-summary.md`
- Tests: cerca de 170 testes de backend, com o núcleo escrito antes do código: catálogo E1 a E21, propriedade, integração com Testcontainers, concorrência, privilégios, expurgo, ArchUnit e contrato. Há ainda o cenário de carga `u6`. O piso de 80% de linhas é mantido. Não há tela nem smoke novo (Q1 da Infrastructure Design, opção B)
- Volta 1 de Build and Test: corrige a conferência de plano do cenário `u6` em `scripts/load/load-test.mjs`, que reprovava o `Seq Scan` da própria subconsulta de amostra, com um teste `node --test`. Nenhum código de produção muda, e a nova execução do cenário fica para o Build and Test

## Entradas

A Unit é a `u6-risk-control` de [unit-of-work.md](../../../inception/units-generation/unit-of-work.md). As histórias são US4.6 (barreira e recusas) e US4.7 (perda diária e drawdown), de [stories.md](../../../inception/user-stories/stories.md).

| Documento | O que dá ao código |
|---|---|
| [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), [entities.md](../functional-design/entities.md) | Fluxos F1 a F4, regras BR1.1 a BR4.3, entidades, catálogo E1 a E21, mudanças de contrato |
| [nfr-requirements/](../nfr-requirements/) e [nfr-design/](../nfr-design/) | Metas NFR2.1 a NFR13.1 da U6, papéis e gatilhos, alteração formal da BR4.3, índices, falha fechada, saúde |
| [infrastructure-specification.md](../infrastructure-design/infrastructure-specification.md), [cicd-pipeline.md](../infrastructure-design/cicd-pipeline.md), [monitoring-design.md](../infrastructure-design/monitoring-design.md) | Migração `0009`, matriz de privilégios, `risk_retention`, pool de retenção da U6, pool do agendador, cenário `u6`, rollback |
| [contract-summary.md](../../../inception/contract-design/contract-summary.md) | C9, C11, C13, C14, C21 e regras comuns |

## Decisões de implementação

- **Pacotes.** `riskcontrol` tem as subpastas `api`, `domain`, `infra`, `web` e `loadtest`. É dono de `order_evaluation`, `order_rejection`, `rejection_reason_detail` e `risk_purge_run`. Só depende de `pnl.api.PortfolioRiskView`, de `marketdata.api`, de `identity.api` (contexto de autenticação), da interface publicadora da trilha e de `shared`.
- **Aritmética.** `BigDecimal` com `HALF_EVEN`: 6 casas para USDC, 12 para preço unitário e 4 para pontos percentuais. Nenhum `double` ou `float`.
- **BR4.3.** A versão do `security-design.md` do NFR Design prevalece sobre a do `rules.md`: expurgo só de aprovadas com mais de 30 dias sem recusa que as cite. Isso fica registrado em `traceability.json` e no resumo do código.
- **Achados abertos das revisões, resolvidos aqui:**
  - **Agendador.** A contagem de métodos `@Scheduled` existentes é conferida no código antes do Passo 1. O pool vai para o menor valor que cabe todos mais o expurgo, 12 se a conta de hoje for 11. `RuntimeSettingsGuard`, `application.yml`, `docker-compose.yml` e os testes acompanham, e o comentário do guarda lista as tarefas por Unit.
  - **Cenário `u6`.** O ramo `u6` do `load-test.sh` define `LOADTEST_LEDGER_PEOPLE`, `LOADTEST_FILLS_PER_PERSON=0` e `LOADTEST_CYCLE_BOTS=60` e gera os convites `SMOKE_INVITE_CODES` como o `u7`. A variável `LOADTEST_U6_PEOPLE` não é criada, porque o número de pessoas vem de `LOADTEST_LEDGER_PEOPLE`.
  - **Transação.** A transação do ciclo é `READ COMMITTED`. O E20 confere isso.
  - **Diagrama do F4.** Ele mostra `Rejected(reasons, pauseTriggers)`. O código segue a assinatura completa `Rejected(rejectionId, reasons, limitsVersion, pauseTriggers)`, e o resumo do código registra a diferença.
  - **Queda do patrimônio.** A queda estimada (custo da compra menos o líquido de saída, 3,600000 no caso-base) refina a letra da Q7 do Functional Design. Isso fica registrado no resumo para o ponto de controle.
- **Contratos.** Antes do código da U6, as mudanças de C14 e C21 entram no `contract-summary.md` (Passo 3). Elas são: `Set<PauseTrigger>`, `rejectionId`, os tipos definidos, os campos aditivos e o 404 para bot sem avaliação.
- **Sem tela e sem smoke.** O `scripts/verify.sh` não muda.

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

## Como a ordem dos testes se aplica à U6

- **Núcleo financeiro (testes antes do código).** A U6 é a barreira de limites de risco e a deduplicação por decisão. Em todo passo de banco, repositório, lógica de negócio e API do `riskcontrol`, segue-se esta ordem:
  1. os testes são escritos primeiro, a partir das regras e do catálogo;
  2. eles rodam e falham (vermelho), e a saída fica registrada no resumo;
  3. o código é escrito até ficarem verdes;
  4. a refatoração acontece com os testes verdes.

  O teste valida o requisito, não a implementação.
- **Demais camadas (depois de cada camada).** Configuração, saúde, carga e documentação seguem implementar e depois testar, como manda o `team.md`.
- **Executores prontos antes do primeiro teste.** O Passo 2 deixa o comando da U6 (marcador `u6`) rodando antes do primeiro teste vermelho (Passo 4).
- **Volume.** São 10 a 15 testes por componente. Os tipos são:
  - unitários, propriedade, integração e concorrência;
  - desempenho, no cenário `u6`;
  - segurança: privilégios, isolamento, só PAPER, sem rede e sem ponto flutuante.

  A ponta a ponta fica com a U5 (Q1 da Infrastructure Design).
- **Suítes das Units anteriores.** Continuam verdes depois da `0009`, do papel `risk_retention` e do pool do agendador.

## Passos

Cada passo indica as histórias (US), as regras (BR) e os requisitos detalhados (NFR, da U6) que implementa. "Fundação" marca o que vem das responsabilidades da Unit sem história própria. "Vermelho → verde" marca os passos com testes antes do código.

### Fundação, executores e contratos

- [x] **Passo 1 — Configuração.**
  - Contar os métodos `@Scheduled` existentes e registrar a conta no resumo.
  - Configurar o pool do agendador para a conta mais 1 (12 se a conta for 11), com `RuntimeSettingsGuard.MIN_SCHEDULER_POOL` e o comentário dele por Unit. Atualizar o padrão de `spring.task.scheduling.pool.size` e o padrão de `SCHEDULER_POOL_SIZE` no `docker-compose.yml`.
  - Criar as propriedades fixas de `memetrade.riskcontrol`: cron do expurgo, tamanho do lote, duração máxima, `selection-age` 31d, `trigger-age` 30d e frescor de 60 s.
  - Depois, os testes: o guarda recusa um valor abaixo do mínimo e aceita o mínimo; o `ComposeFilesTest` confere o novo padrão.
  - Fundação; NFR2.5, NFR10.6.
- [x] **Passo 2 — Base de testes e comando da Unit.**
  - Criar o marcador JUnit `u6` e a `AbstractU6IT` sobre a base de integração da U2, com conexões extras pelo papel da aplicação e pelo login de retenção para os testes de privilégio.
  - Criar os dublês e ajudantes:
    - `MutableClock`;
    - dublê da C11 com preço, liquidez, idade e `unknown` sob comando;
    - ajudante que abre a transação do ciclo como a U5 fará (`TransactionTemplate` com `READ COMMITTED`) e liga o contexto do dono;
    - construtor de contas PAPER pela `PaperExecution.fund` real da U7.
  - Um teste de subida do contexto com o marcador `u6` roda verde com o comando de [unit-test-instructions.md](unit-test-instructions.md), e as suítes anteriores continuam verdes.
  - Fundação; NFR12.1.
- [x] **Passo 3 — Contratos e tipos públicos.**
  - No `contract-summary.md`:
    - C14 com `Rejected(rejectionId, reasons, limitsVersion, pauseTriggers)` e `Set<PauseTrigger>`;
    - os tipos `CandidateOrder`, `BotLimitsVersionRef` e `RejectionReason`;
    - a regra de transação e contexto;
    - C21 com os campos aditivos e o 404 para bot sem avaliação da pessoa, inclusive depois do expurgo.
  - Em `riskcontrol.api`, criar a interface `OrderGate` e os registros públicos.
  - Em `tenancy/route-registry.yml`, registrar a rota C21 com o teste negativo.
  - Depois, os testes: registro de rotas, e registros imutáveis com validação de entrada (BR1.2).
  - Fundação; C14, C21; BR1.1, BR1.2.

### Banco de dados (vermelho → verde)

- [x] **Passo 4 — Migração `0009-risk-control` e papel `risk_retention`.**
  - Vermelho:
    - `RiskPrivilegesIT`: uma linha da matriz da infrastructure-specification.md por teste, nas quatro tabelas, com o papel da aplicação e com o login de retenção, mostrando o permitido e o recusado;
    - `UPDATE` recusado em todas as tabelas, e `DELETE` recusado em recusas e detalhes;
    - `DELETE` em `order_evaluation` aceito só de aprovada com mais de 30 dias, sem recusa que a cite e pelo grupo `risk_retention`;
    - `risk_purge_run` só de inserção, com a limpeza de 90 dias;
    - `CHECK mode = 'PAPER'`, unicidade (tenant, modo, bot, decisão) e a chave estrangeira sem `ON DELETE`;
    - `RolesBootstrapIT`: `10-roles.sql` idempotente, com a aplicação e o dono fora de `risk_retention` e o `USAGE` no esquema;
    - `MigrationsIT` roda as consultas das Units anteriores sobre o esquema novo.
  - Verde:
    - a migração com tabelas, escalas `numeric`, índices (inclusive o parcial do expurgo), gatilhos e privilégios;
    - o bloco de `risk_retention` no `10-roles.sql`.
  - US4.6; BR4.3 (versão do NFR Design), BR2.8; NFR4.3, NFR4.4, NFR5.3, NFR4.2.

### Lógica de negócio (vermelho → verde)

- [x] **Passo 5 — Verificações e ordem dos motivos.**
  - Vermelho: `RiskGateCatalogTest` com E1 a E19 e as combinações E14 a E18, cada valor por igualdade exata. Os casos são:
    - fronteira e um incremento além;
    - ordem fixa dos motivos;
    - gatilhos de pausa em conjunto;
    - venda sem as verificações de compra;
    - dado ausente e dado velho, sem nenhum zero no lugar de `unknown`;
    - medido e limite de cada detalhe.
  - Verde: `LimitChecks` puro, que recebe um registro imutável com a ordem, os limites, os dados de mercado, a visão de risco e o instante.
  - US4.6, US4.7; BR2.1 a BR2.7, BR2.9 a BR2.12, BR3.1 a BR3.3; NFR4.1, NFR4.5.
- [x] **Passo 6 — Slippage, custo e queda do patrimônio.**
  - Vermelho: `SlippageEstimateTest` com:
    - consumo e slippage em 4 casas;
    - custo da compra;
    - líquido de saída e queda de 3,600000 no caso-base;
    - queda 0 na venda;
    - consumo acima de 10% sem teto.
  - Verde: `SlippageEstimate` puro com a cópia da política `v1`.
  - US4.6, US4.7; BR1.4 a BR1.6; NFR4.1.
- [x] **Passo 7 — Propriedade com seed fixa.**
  - Vermelho e verde: `RiskGatePropertyTest`, com 1.000 combinações aleatórias de seed fixa. Nenhuma ordem aprovada passa de nenhum limite, e toda recusa tem pelo menos um motivo com detalhe.
  - US4.6; BR3.1; NFR4.1, NFR8.2.

### Repositório e barreira (vermelho → verde)

- [x] **Passo 8 — Repositórios.**
  - Vermelho:
    - inserção da avaliação com `ON CONFLICT DO NOTHING`, que informa se inseriu e não aborta a transação;
    - leitura da avaliação vencedora;
    - recusa com detalhes em lote;
    - página da C21 por índice e cursor;
    - `EXISTS` por pessoa;
    - todas as consultas filtradas por tenant, pessoa e `PAPER`.
  - Verde: `JdbcEvaluationRepository` e `JdbcRejectionRepository`.
  - US4.6; BR2.8, BR4.1, BR4.2; NFR2.4, NFR5.1.
- [x] **Passo 9 — `OrderGate.evaluate` (F1 e F2).**
  - Vermelho:
    - `OrderGateIT` com:
      - transação exigida;
      - tenant e pessoa só do contexto;
      - uma leitura da C11 e uma da C13;
      - um `ORDER_REJECTED` por recusa e nenhum por aprovação;
      - catálogo E1 a E19 de ponta a ponta com a U7 real;
    - `EvaluationDuplicateIT` com:
      - E8;
      - E20, duas transações em paralelo com barreira, 50 repetições, em `READ COMMITTED`, mais uma tentativa em `REPEATABLE READ` que mostra o erro esperado;
      - E21, depois de rollback;
    - `RiskGateFailClosedIT` com falha injetada em cada leitura e em cada gravação, mais o `lock_timeout`: nada gravado e a U7 não é chamada;
    - `RejectionReplayTest`, que reavalia a partir dos `MarketInputs` e dá os mesmos motivos;
    - um teste com ordem de outra pessoa no contexto: a exceção da C13 desfaz tudo.
  - Verde: `OrderGateService`.
  - US4.6, US4.7; BR1.1, BR1.3, BR2.8, BR3.4, BR3.5; NFR4.4, NFR4.6, NFR5.2, NFR8.1, NFR10.2 a NFR10.5.
- [x] **Passo 10 — Conferência com a U7.**
  - Vermelho e verde: `SlippageConformanceIT`:
    - lê a política vigente da U7 e compara spread, impacto e taxa da cópia;
    - para E1, E4 e E16, compara o slippage estimado com o `slippagePct` do preenchimento real da U7;
    - num bot sem posição no token, compara a queda estimada com a diferença do patrimônio da U7 antes e depois do preenchimento.
  - US4.6, US4.7; BR1.5, BR1.6.
- [x] **Passo 11 — Expurgo.**
  - Vermelho:
    - `RiskPurgeIT`:
      - linhas gravadas com `evaluated_at` de 29, 30,5 e 32 dias: só a de 32 sai;
      - `DELETE` direto da linha de 30,5 dias pelo login de retenção passa no gatilho;
      - aprovada citada por `DUPLICATE` nunca sai;
      - lotes em transações separadas, com falha no meio sem perda e com a rodada seguinte continuando;
      - `risk_purge_run` com sucesso e com falha, e a limpeza de 90 dias;
      - ShedLock com execução única;
    - `RiskRetentionPoolIT`: duas rodadas seguidas e uma rodada com a purga da trilha da U2 em andamento no mesmo login;
    - `PurgedDecisionReplayIT`: apaga a aprovada, reavalia a decisão e confirma que a U7 devolve `Duplicate`, sem segunda ordem;
    - rota C21 de um bot com todas as avaliações apagadas: 404.
  - Verde: `RiskRetentionDataSource` (Hikari de 1, `connectionTimeout` de 30 s) e `RiskEvaluationPurgeJob`, com o cron das 03:30 UTC, o ShedLock `riskcontrol-purge` e o corte calculado no SQL.
  - US4.6; BR4.3 (versão do NFR Design); NFR2.5, NFR4.3, NFR10.6, NFR10.7.

### API (vermelho → verde)

- [x] **Passo 12 — Rota C21.**
  - Vermelho: `RejectionRoutesIT` com:
    - a própria pessoa: página, ordem, cursor, `limit` de 1 a 100, detalhes e `market`;
    - os cinco casos de 404: bot de outra pessoa avaliado, nunca avaliado, de outro tenant, inexistente e expurgado;
    - `400` para cursor adulterado ou parâmetro desconhecido;
    - `mode` diferente de `PAPER` responde `403`;
    - nenhum outro método.
  - Verde: `RejectionController` em `riskcontrol.web`.
  - US4.6; BR4.1, BR4.2; NFR5.1, NFR6.2, NFR6.3, NFR6.4.
- [x] **Passo 13 — OpenAPI e tipos.**
  - OpenAPI do convidado com a rota C21 e os campos aditivos; tipos do frontend regenerados.
  - Depois, os testes: o gerado é igual ao versionado; um teste proíbe outro método na rota.
  - Fundação; NFR6.2.

### Saúde, observabilidade e arquitetura (depois de cada camada)

- [x] **Passo 14 — Saúde, métricas e logs.**
  - `RiskControlHealthIndicator` sobre `risk_purge_run`, fora de liveness e readiness.
  - Contadores `riskcontrol.evaluation.errors` (rótulo `cause`) e `riskcontrol.purge.errors`.
  - Logs com os campos e níveis do observability-design.md.
  - Depois, os testes:
    - saúde em `UP`, `PURGE_STALE` e `PURGE_FAILED`, e o grupo de readiness sem ela;
    - contadores sem rótulo de pessoa;
    - `LogScanIT` e `MetricsLabelsIT` ampliados com um ciclo de teste com recusa.
  - NFR9.1 a NFR9.5, NFR7.1.
- [x] **Passo 15 — Arquitetura.**
  - `RiskControlArchitectureTest` confere que:
    - o `riskcontrol` depende só das interfaces públicas listadas;
    - o domínio não tem infraestrutura nem web;
    - não há rede, assinatura nem SDK Solana;
    - não há `double` nem `float`;
    - não há `BigDecimal.ZERO` como substituto de `Unknown`;
    - só o `RiskEvaluationPurgeJob` recebe o `RiskRetentionDataSource`;
    - os beans de carga só existem com `@Profile("loadtest")`.
  - Um teste confirma que, sem o perfil `loadtest`, esses beans não existem. O `AuditArchitectureTest` da U2 fica sem mudança.
  - NFR4.2, NFR4.5, NFR6.1.

### Ambiente, carga e documentação

- [x] **Passo 16 — Cenário de carga `u6`.**
  - Em `riskcontrol.loadtest`, só com `@Profile("loadtest")`: `LoadtestRiskCycleDriver`, `LoadtestRiskSeed` e `LoadtestRiskPurgeTrigger`.
  - No `infra/docker-compose.test.yml`, as variáveis `LOADTEST_U6_ENABLED`, `LOADTEST_U6_REJECTIONS_PER_BOT`, `LOADTEST_U6_HEAVY_BOT_REJECTIONS`, `LOADTEST_U6_OLD_APPROVED` e `LOADTEST_U6_PURGE_AT`.
  - `scripts/load-test.sh` com o ramo `u6`, que define `LOADTEST_LEDGER_PEOPLE`, `LOADTEST_FILLS_PER_PERSON=0` e `LOADTEST_CYCLE_BOTS=60` e gera os convites como o `u7`.
  - `scripts/load/load-test.mjs` com as duas janelas, o log das rodadas, os `EXPLAIN` e o relatório em `docs/perf/u6-risk-control-<data>.md`.
  - `.github/workflows/load-test.yml` aceita `u6`, com ações fixadas por SHA.
  - Depois, os testes: os beans não existem sem `loadtest`; `ComposeFilesTest` e `WorkflowsTest` atualizados; `node --check` e `bash -n`.
  - NFR2.1, NFR2.4, NFR2.5, NFR2.6, NFR3.1.
- [x] **Passo 17 — Execução das suítes e cobertura.**
  - Rodar o comando da U6, a suíte inteira do backend (`verify` com JaCoCo a 80%) e o `bash scripts/verify.sh`.
  - Corrigir sem reduzir o piso.
  - Anotar no resumo a cobertura e as saídas vermelhas dos passos com testes antes do código.
  - NFR12.1, NFR13.1.
- [x] **Passo 18 — Documentação e avisos à U5.**
  - `docs/api/u6-risk-control.md` com:
    - as verificações e a ordem dos motivos;
    - o catálogo;
    - a matriz de privilégios e o papel `risk_retention`;
    - o expurgo e o procedimento quando a saúde vai a `DOWN`.
  - Avisos à U5:
    - o C14 mudou para `Set<PauseTrigger>` e ganhou `rejectionId`;
    - a tela trata o 404 da C21 como "nenhuma recusa";
    - DUPLICATE de decisão já executada aparece como "decisão já avaliada";
    - a transação do ciclo é `READ COMMITTED`;
    - o NFR2.2 e o NFR3.2 serão medidos no estágio da U5.
  - Fundação; NFR2.2, NFR3.2 (pendências para a U5).

### Volta 1 de Build and Test (2026-11-06)

No Build and Test, o cenário de carga `u6` reprovou só na conferência de plano (falha F4 em [test-results.md](../../build-and-test/test-results.md)). Todas as medidas passaram: avaliação com p95 de 22 ms, C21 com p95 de 13 ms, maior lote do expurgo com 90 ms, pool sem pendentes, api com 347 MB e postgres com 582 MB.

A causa está em `scripts/load/load-test.mjs`. A função `u6Plans()` põe dentro do `EXPLAIN` a subconsulta de amostra `(select ... from order_rejection r limit 1)`. Essa subconsulta gera sozinha um `Seq Scan on order_rejection`, e a expressão regular conta isso como falha. Com isso, o plano das consultas reais nunca foi conferido, e o NFR2.6 da U6 ficou `Unverified`.

A correção fica só no script de carga. Nenhum código de produção, migração ou teste Java de comportamento muda. Os Passos 1 a 18 ficam como estão.

- [x] **Passo 19 — Teste de regressão da conferência de plano (vermelho).**
  - Separar em `scripts/load/load-test.mjs` a montagem das consultas de `u6Plans()` numa função pura e exportável. Ela recebe a amostra já lida (`tenantId`, `ownerUserId`, `botId`) e devolve o texto de cada `EXPLAIN`.
  - Teste com o executor nativo do Node (`node --test`) em `scripts/load/load-test-u6-plans.test.mjs`. Ele confere que nenhuma consulta enviada ao `EXPLAIN` contém subconsulta sobre `order_rejection` ou `order_evaluation` e que os valores da amostra entram como literais com aspas escapadas.
  - Um segundo caso confere a classificação: um plano com `Seq Scan on order_rejection` continua reprovado, e um plano só com `Index Scan` passa.
  - Hoje, o primeiro caso falha, porque a consulta tem a subconsulta. A falha é registrada no resumo.
  - Regra da equipe: todo defeito corrigido ganha um teste de regressão que o reproduz.
- [x] **Passo 20 — Correção do script (verde).**
  - `u6Plans()` lê a amostra numa consulta separada, antes dos `EXPLAIN`, e passa os valores à função do Passo 19.
  - Os valores são ids sintéticos do próprio banco de carga. A montagem escapa aspas simples e recusa valores fora do formato de UUID, para não abrir caminho a injeção.
  - Sem amostra (tabela vazia), a conferência fica "não conferido" com o motivo, em vez de aprovar ou reprovar em silêncio.
  - A expressão regular de `Seq Scan` não muda, para a conferência não ficar mais fraca.
  - Depois, `node --test scripts/load/load-test-u6-plans.test.mjs` e `node --check scripts/load/load-test.mjs` ficam verdes.
  - NFR2.6.
- [x] **Passo 21 — Conferência e registro.**
  - Rodar o `WorkflowsTest` e o `ComposeFilesTest` (comandos da volta 1 em [unit-test-instructions.md](unit-test-instructions.md)), para confirmar que o script segue com o que eles exigem.
  - `code-summary.md`: seção "Volta 1" com a causa, a correção, o teste novo e os resultados.
  - `traceability.json`: acrescentar o teste novo como alvo do NFR2.6. O NFR2.6 continua `Deferred` até a nova execução do cenário `u6`, que acontece no Build and Test.
  - Fundação; NFR12.1.

## Rastreio das histórias

| História | Passos |
|---|---|
| US4.6 (barreira, recusas, saldo, duplicidade da decisão) | 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 |
| US4.7 (perda diária e drawdown) | 5, 6, 9, 10 |
