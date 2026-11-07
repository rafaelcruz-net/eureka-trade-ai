# Plano de geração de código — U9 Painel e navegação

## Summary

- Builds: o painel S2 (rota `/`) com quadros de bots, P&L e avisos, o cliente do canal ao vivo, a casca única com o menu geral e o teto de 5 canais por pessoa no canal da U5.
- Touches: `apps/web/src/features/dashboard/`, `apps/web/src/components/` (casca), `apps/web/src/app/page.tsx` e as telas existentes que passam a usar a casca, `apps/web/eslint.config.mjs`, `apps/web/e2e/u9-dashboard/`, `apps/api/.../bots/web/` (teto e esquemas dos eventos) e o `contract-summary.md`.
- Tests: cerca de 120 testes — unidade e componente no Jest (leitor do canal, cliente, redutor, quadros, casca), 3 testes de integração Java (teto de canais, abertura do painel e atraso do canal) e 1 ponta a ponta no Playwright.

## Entradas

- Desenho funcional: [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md) (BR1.1 a BR8.2), [frontend-components.md](../functional-design/frontend-components.md).
- Requisitos não funcionais: [performance-requirements.md](../nfr-requirements/performance-requirements.md), [security-requirements.md](../nfr-requirements/security-requirements.md), [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md).
- Design não funcional: [performance-design.md](../nfr-design/performance-design.md), [security-design.md](../nfr-design/security-design.md), [logical-components.md](../nfr-design/logical-components.md).
- Infrastructure Design não se aplica a esta Unit (nenhuma infraestrutura nova).
- Unit: U9 em [unit-of-work.md](../../../inception/units-generation/unit-of-work.md); histórias US5.3 e US5.4; requisitos FR12.1, FR12.2 e NFR1 a NFR14 de [requirements.md](../../../inception/requirements-analysis/requirements.md); contratos C1 a C5, C7, C16 e C18 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

## Decisões de implementação

- **Organização:** o painel fica em `apps/web/src/features/dashboard/`; a casca em `apps/web/src/components/shell/`. O leitor do `text/event-stream` e o cliente do canal são TypeScript puro, sem React, para teste de unidade.
- **Sem dependência nova** no `apps/web` (Q4 dos requisitos). O estado do painel é um `useReducer` com contexto.
- **Tipos gerados:** os seis envelopes do canal ganham esquemas no OpenAPI gerado por anotações de documentação no controlador do canal da U5; depois `pnpm gen:api` regenera `schema.d.ts`. Nenhum tipo do contrato escrito à mão.
- **Teto de canais (Q1 do design):** contagem e registro do canal numa única operação atômica por pessoa (contador por pessoa com incremento condicional dentro de `compute` do mapa), liberação em todos os caminhos de fim do emissor. Limite em `memetrade.bots.stream.max-channels-per-person` (padrão 5). Recusa `429 RATE_LIMITED` em `ProblemDetail` com `Retry-After: 30`, antes de abrir o fluxo.
- **Regras de lint:** a proibição de `EventSource` entra num bloco `src/**` E é repetida em cada bloco existente que já define `no-restricted-globals` ou `no-restricted-syntax` (pnl, bots, app/pnl, app/bots), porque no flat config o bloco mais específico substitui as opções; a proibição de `Number(`, `parseFloat(` e `+valor` vale para `src/features/dashboard/**`.
- **Destino da entrada:** o destino padrão depois da entrada sem origem passa a ser `/` (painel); a origem pendente da U3 continua valendo.
- **Registros no contrato:** C4 ganha a ordem de `GET /bots` (última alteração primeiro); C7 ganha os envelopes dos eventos e a resposta `429`.

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

## Como a ordem dos testes se aplica à U9

A U9 não toca o núcleo financeiro (limites, ledger, P&L calculado ou deduplicação): só mostra o P&L que a U7 calcula e manda o comando `pause` da U5. Por isso toda a Unit segue a parte "demais camadas" da ordem do `team.md`: implementar cada camada testável e, em seguida, escrever e executar os testes dessa camada. Os testes obrigatórios por risco que esta Unit toca (isolamento entre contas nas chamadas do painel, `unknown` nunca zero no P&L, pausa sem transição repetida) entram nos passos da camada correspondente e os testes existentes das Units donas continuam verdes.

## Passos

- [x] **Passo 1 — Estrutura e configuração.**
  - Pastas `apps/web/src/features/dashboard/` e `apps/web/src/components/shell/`; pasta `apps/web/e2e/u9-dashboard/`.
  - Propriedade `memetrade.bots.stream.max-channels-per-person` em `BotProperties` com padrão 5 no `application.yml`, validada entre 1 e 20 pela guarda de configuração existente.
- [x] **Passo 2 — Base de testes e comandos da Unit.**
  - Marcador JUnit `u9` para os testes Java desta Unit; base de integração sobre a da U5.
  - Utilitários de teste do painel no Jest: relógio falso, canal falso (fluxo de texto controlável) e respostas das rotas a partir dos tipos gerados.
  - Os comandos de `unit-test-instructions.md` rodam antes do primeiro teste desta Unit.
- [x] **Passo 3 — Teto de canais no canal da U5 (API) e seus testes.** (NFR2.1)
  - `DashboardMarkPoller.open` registra o canal só se a contagem da pessoa estiver abaixo do limite, numa operação atômica; a vaga é liberada em `onCompletion`, `onTimeout`, `onError`, no `auth.expired` e quando uma escrita falha.
  - `DashboardStreamController` responde `429 RATE_LIMITED` em `ProblemDetail` com `Retry-After: 30` e `correlationId`; log `WARN` só com o identificador mascarado e a contagem.
  - Testes: `DashboardChannelLimitIT` (6 canais em sequência, 6 aberturas concorrentes da mesma pessoa com no máximo 5 abertas, fechar libera a vaga, outra pessoa não é afetada, `Retry-After` presente, sem dado de pessoa no corpo) e teste de unidade do contador.
- [x] **Passo 4 — Esquemas dos envelopes do canal e tipos gerados (API → contrato).**
  - Anotações de documentação no controlador do canal com os seis envelopes (`bot.state`, `trade.recorded`, `pnl.updated`, `data.status`, `heartbeat`, `auth.expired`) e a resposta `429`, sem mudar o comportamento.
  - Regenerar `apps/api/openapi/openapi-guest.yaml` e `apps/web/src/lib/api/schema.d.ts` (`pnpm gen:api`); o teste de contrato existente da CI confere os dois.
  - Registrar no `contract-summary.md`: C4 (ordem de `GET /bots` pela última alteração) e C7 (envelopes e `429`).
  - Testes: teste do OpenAPI gerado que confere os seis esquemas e o `429`.
- [x] **Passo 5 — Leitor do `text/event-stream` (frontend, lógica) e seus testes.** (BR5.1, NFR6.5)
  - `sse-reader.ts`: separa blocos por linha em branco (`\n\n` e `\r\n\r\n`), junta várias linhas `data:`, ignora comentários `:`, aceita pedaços partidos no meio de linha e de caractere UTF-8, e fecha com motivo `OVERSIZED_EVENT` acima de 1 MiB sem separador.
  - Testes: 12 casos (bloco simples, várias `data:`, comentário, CRLF, pedaço partido, caractere multibyte partido, `id` vazio, evento sem `event`, linha sem dois-pontos, buffer acima de 1 MiB, fim de fluxo no meio de bloco, vários blocos num pedaço).
- [x] **Passo 6 — Cliente do canal (frontend, lógica) e seus testes.** (BR4.2, BR5.1 a BR5.6, NFR1.3, NFR2.1, NFR2.2, NFR6.1, NFR6.3, NFR6.6)
  - `stream-client.ts`: abre com `fetch`, `Authorization` do `tokenStore`, `Last-Event-ID` e `X-Correlation-Id`; estados `CONNECTING`, `OPEN` (na resposta `200`), `STALE` (60 s sem evento), `WAITING` (espera 2, 4, 8, 16, 30 s; respeita `Retry-After` no `429`), `RENEWING` (uma renovação por tentativa em `auth.expired` ou `401`) e `CLOSED`; "Tentar de novo" reabre na hora; `close()` cancela tudo; nenhuma reação a `visibilitychange`.
  - Validador de envelope a partir dos tipos gerados; descarte com `dashboardWarn(kind, reason)` de lista fechada.
  - Testes: 15 casos com relógio falso e `fetch` falso (abertura com cabeçalhos certos e sem token na URL, `OPEN` na resposta `200`, `STALE` em 60 s, volta para `OPEN` no evento, espera crescente e reinício em 2 s, `429` com `Retry-After`, `auth.expired` com renovação, `401` na abertura com renovação, renovação que falha, `401` repetido depois da renovação, envelope inválido descartado sem fechar, console sem token, carteira nem `id`, `close()` sem tentativas agendadas, aba oculta sem reabrir, `X-Correlation-Id` em toda abertura).
- [x] **Passo 7 — Estado do painel (frontend, lógica) e seus testes.** (BR1.2 a BR1.5, BR2.1, BR2.3, BR3.1 a BR3.4, BR4.1, BR5.2, NFR3.3, NFR3.4)
  - `dashboard-reducer.ts` e `DashboardProvider`: fatias `bots`, `lastTrade`, `pnl`, `reconciliation`, `sources`, `channel`, `pendingPauses`; fila de eventos durante `LOADING`; carga completa por estado com `nextCursor`; última ordem com limitador de 3; última ordem de bot `STOPPED` guardada; P&L e reconciliação com "uma em andamento, no máximo uma pendente"; período do dia UTC e virada à meia-noite UTC; idade das fontes a cada 30 s.
  - Testes: 15 casos (vazio com e sem rascunho, contagens só da carga completa, `bot.state` truncado sem mudar contagens, `bot.state` antes da resposta REST, última ordem pelo maior `createdAt`, `trade.recorded` mais velho ignorado, ordem completada depois de `trade.recorded`, limitador de 3, encerrado não buscado de novo, 10 `pnl.updated` geram no máximo 2 buscas, período do dia UTC, virada à meia-noite, fonte velha por idade e por `status`, rascunho a continuar pelo primeiro de `state=DRAFT`, nenhuma requisição com `tenantId` ou `userId`).
- [x] **Passo 8 — Quadros do painel (frontend, componentes) e seus testes.** (BR1.1 a BR3.5, BR6.5, BR6.6, BR7.1 a BR7.3, BR8.1, BR8.2, NFR3.2, NFR4.1, NFR4.2, NFR5.2)
  - `DashboardScreen`, `DashboardHeader`, `DataNoticesBanner`, `BotsCard` (com `BotsCounts`, `BotRow`, `PauseButton`, `LastTradeLine`, `BotsEmptyState`), `PnlCard` (com `PnlTotals`, `ReconciliationLine`), `CardError`; textos em pt-BR em `messages.ts`; `data-testid` da lista do desenho; `app/page.tsx` passa a mostrar o painel.
  - Testes: 30 casos de componente (carregando em até 200 ms, erro isolado por quadro, vazio com atalhos, linha por estado com motivos em texto, "Pausar" só em `ACTIVE`, pausa com sucesso e foco no estado, `409`, `404`, falha técnica com a mesma chave, clique repetido, `unknown` nunca zero com motivo, parcial, vazio do dia, 18 casas sem perda, sinais escritos, reconciliação nos três estados e com falha local, avisos de fonte, aviso de desatualizado com "Tentar de novo", anúncios só das mudanças e junção em 2 s, foco inicial no título "Bots PAPER", "MODO PAPER (simulado)" nos títulos, nenhum texto de promessa de lucro).
- [x] **Passo 9 — Casca única e migração das telas (frontend, componentes) e seus testes.** (BR1.1, BR6.1 a BR6.4, NFR7.1)
  - `AppShell`, `PrimaryNav`, `WalletBadge`, `NoticesRegion` em `components/shell/`; as telas de scanner (U4), bots (U5, inclusive `BotsShell`), P&L (U7) e conta (U3) passam a usar a casca; `AccountMenu` some para dentro dela, mantendo "Feedback" e "Sair"; destino padrão da entrada passa a ser `/`.
  - Testes: 12 casos (ordem do menu, `aria-current` em cada rota, S4 e S5 marcam "Bots PAPER", regiões nomeadas, carteira só abreviada, falha da conta mostra "Carteira", h1 igual ao item, "Feedback" e "Sair" presentes, destino padrão `/`, origem pendente respeitada) e ajuste dos testes existentes das telas que mudaram de cabeçalho, sem perder nenhum caso.
- [x] **Passo 10 — Regras de lint e verificação do armazenamento.** (NFR6.1, NFR6.2, NFR4.2)
  - `eslint.config.mjs`: `EventSource` proibido em `src/**` e repetido em cada bloco que já define `no-restricted-globals` ou `no-restricted-syntax`; `Number(`, `parseFloat(` e `+valor` proibidos em `src/features/dashboard/**`.
  - Testes: teste que roda o ESLint sobre trechos de exemplo e confere as duas proibições em `src/features/dashboard`, `src/features/pnl` e `src/features/bots`; teste do painel que percorre `localStorage`, `sessionStorage` e IndexedDB e falha se aparecer o token ou `Bearer`.
- [x] **Passo 11 — Medições de desempenho (integração Java) e seus testes.** (NFR3.1, NFR1.1)
  - `DashboardOpenLatencyIT`: 20 contas do perfil `loadtest` (3 ativos e 20 encerrados cada), 20 clientes HTTP do JDK fazendo as chamadas de abertura de cada grupo e abrindo o canal; p95 por grupo ≤ 2 s.
  - `DashboardStreamDelayIT`: mudanças de estado pela rota de comando e ordens pelos acionadores do `ledger/loadtest`; p95 ≤ 5 s e máximo ≤ 60 s até o evento chegar ao cliente.
  - Ambos com marcador `u9` e só na suíte completa do merge para `main`.
- [ ] **Passo 12 — Ponta a ponta do painel e seu teste.** (US5.3, US5.4, Q5 dos requisitos)
  - `e2e/u9-dashboard/smoke.spec.ts`: entra com a conta de smoke, confere os três quadros, o canal aberto (`heartbeat` em até 20 s), cria e inicia um bot pelo assistente e, numa segunda aba, pausa o bot pela tela do bot; a primeira aba mostra o bot pausado em até 60 s sem recarregar.
  - O script de verificação existente passa a rodar também esse smoke.
- [x] **Passo 13 — Cobertura e suíte completa.**
  - Rodar o Jest do `apps/web` com cobertura (piso de 80% de linhas, sem mudar exclusões) e a suíte Java com JaCoCo; confirmar os testes existentes das telas migradas verdes.
- [x] **Passo 14 — Documentação e rastreabilidade.**
  - Comentários nos módulos novos explicando as regras (BR) que cada um cumpre; `code-summary.md`, `traceability.json` e `source-manifest.json` desta Unit.

## Rastreio das histórias

| História / critério | Passos |
|---|---|
| US5.3 — AC5.3.1 (vazio com atalhos) | 7, 8 |
| US5.3 — AC5.3.2 (ativos, pausados, última operação, "Pausar") | 7, 8, 12 |
| US5.3 — AC5.3.3 (falha isolada por quadro) | 7, 8 |
| US5.4 — AC5.4.1 (mudança sem recarregar em até 60 s) | 3, 4, 5, 6, 7, 11, 12 |
| US5.4 — AC5.4.2 (fonte atrasada ou indisponível) | 7, 8 |
| US5.4 — AC5.4.3 (falha da atualização automática) | 6, 8 |
| Casca e navegação (CT-UX-9, BR6.x) | 9 |
| Segurança do canal e do armazenamento (NFR6.x) | 3, 6, 10 |
