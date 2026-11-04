# Plano de geração de código — U4 Descoberta e avaliação

## Summary

- Builds: MarketData completo (duas fontes simuladas, histórico de 24 h, frescor, divergência, estado das fontes), Scanner com filtros e foto da lista, RiskAssessment (indicador, versões de pesos, recálculo), tela S3 com painel lateral
- Touches: `apps/api/` (módulos `marketdata`, `scanner`, `riskassessment`, `shared/web`, migração `0006`, testes), `apps/web/src/features/scanner/`, `apps/web/e2e/u4-discovery/`, `apps/api/openapi/`, `docker-compose.yml`, `.env.example`, `scripts/load/`, `docs/`
- Tests: cerca de 170 testes de backend (unitários, integração com Testcontainers, ArchUnit, propriedade), cerca de 60 de frontend (Jest), um smoke Playwright e o cenário de carga local, com o piso de 80% de linhas mantido

## Entradas

Unit `u4-discovery` de [unit-of-work.md](../../../inception/units-generation/unit-of-work.md), histórias US2.2, US2.3, US2.4, US3.1 e US3.2 de [stories.md](../../../inception/user-stories/stories.md).

| Documento | O que dá ao código |
|---|---|
| [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), [entities.md](../functional-design/entities.md) | Fluxos F1 a F7, regras BR1.1 a BR5.7, entidades e acréscimos de contrato |
| [nfr-requirements/](../nfr-requirements/) e [nfr-design/](../nfr-design/) | Metas NFR1.1 a NFR14.1, consulta da lista, limitador, saúde, Resilience4j, métricas |
| [infrastructure-specification.md](../infrastructure-design/infrastructure-specification.md), [cicd-pipeline.md](../infrastructure-design/cicd-pipeline.md) | Variáveis e Compose, pool de 20, migração `0006`, script de carga, rollback testado |
| [contract-summary.md](../../../inception/contract-design/contract-summary.md) | C2, C9, C11, C12, C18, C19 e regras comuns |

## Decisões de implementação

- **Pacotes:** `marketdata` (`api`, `domain`, `infra`, `infra.simulated`), `scanner` (`domain`, `infra`, `web`), `riskassessment` novo (`api`, `domain`, `infra`), `shared.web` (limitador). Brownfield: os arquivos da U1 são alterados no lugar.
- **Valores:** `MarketValue` selado da U1 de ponta a ponta; `BigDecimal` com `HALF_UP` onde as regras dizem; percentuais em pontos com escala 4.
- **Acréscimos ao contrato C2** gerados do código no OpenAPI do convidado: `PENDING`, `lastUpdatedAt` opcional, `computedAt`, `bandRaisedByUnknown`, extensão `sources` no `503`, código `RATE_LIMITED` (429). Registrados também no `contract-summary.md` pela documentação do Passo 21.
- **`RiskWeightAdministration`:** interface pública para a U8, sem rota REST na U4.
- **Fora do agente:** a linha do Playwright em `scripts/verify.sh` (incluir `e2e/u4-discovery`) é mudança do responsável do projeto; o texto é preparado no Passo 20.

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

- **Testes antes do código:** nenhum. A U4 não faz parte do núcleo financeiro (limites de risco, ledger, P&L e deduplicação).
- **Testes depois de cada camada (todos os passos):** cada passo implementa a camada e, no mesmo passo, escreve e executa os testes dela antes de seguir.
- **Executores prontos antes do primeiro teste:** o Passo 2 deixa o comando da U4 (marcador `u4`) rodando verde antes do Passo 3.
- **Volume:** 10 a 15 testes por componente; unitários, integração, ponta a ponta, desempenho (script de carga) e segurança (limite, papéis, validação, termos proibidos).
- **Suítes da U1 e da U2:** continuam verdes depois da migração `0006`, da troca do indicador de saúde e do pool de 20.

## Passos

Cada passo indica as histórias (US), regras (BR) e requisitos detalhados (NFR) que implementa. "Fundação" marca o que vem das responsabilidades da Unit sem história própria.

### Fundação e executores

- [x] **Passo 1 — Dependências e configuração.**
  - `pom.xml`: `resilience4j-retry` e `resilience4j-timelimiter` com versão fixa (publicada há pelo menos duas semanas), sem o módulo de integração com Spring.
  - `application.yml`: propriedades da tabela da infrastructure-specification.md com variáveis e padrões (pool 20, agendador 7, fontes `sim-a,sim-b`, intervalo 30 s, 200 tokens, semente 42, tolerância 5.0000, limite 60); validação de faixa na partida com mensagem que nomeia a variável.
  - Executores `market-source-calls` (2 linhas) e `risk-recalc` (1 linha).
  - Fundação; NFR6.7, NFR2.7, NFR10.3.
- [x] **Passo 2 — Base de testes e comando da Unit.**
  - Marcador JUnit `u4`; apoio de teste com relógio injetável (`MutableClock` existente), `Sleeper` sem espera e fontes simuladas com semente fixa e fixtures gravadas para A e B.
  - Um teste de subida do contexto roda verde com o comando da U4 de [unit-test-instructions.md](unit-test-instructions.md). As suítes da U1 e da U2 rodam verdes.
  - Fundação; NFR12.3.

### Banco de dados

- [x] **Passo 3 — Migração `0006-discovery`.**
  - `data_source`: estado `PENDING` aceito; `PENDING` para fontes sem `last_updated_at`; colunas `failure_category` e `collection_interval_seconds`.
  - Tabelas `observation_sample` (com `bucket_start` e restrição única), `risk_weight_set`, `risk_assessment` (chave `(chain, mint)`, fatores em JSONB com decimais em texto), `source_status_mark` (uma linha).
  - Índices de performance-design.md; `autovacuum_vacuum_scale_factor 0.02` em `observation_sample`.
  - Versão 1 dos pesos `ACTIVE` com os valores da BR4.3.
  - Depois, os testes: privilégios da aplicação nas tabelas novas; restrições (valor xor motivo, escala, uma versão `ACTIVE`); semente da versão 1; migração sobre um banco no formato da U2; rollback automático (`MigrationsIT` roda as consultas da listagem da U1 sobre o esquema novo).
  - US2.3, US3.1; BR1.8, BR4.3; NFR2.4, NFR10.6, NFR4.4.

### MarketData

- [x] **Passo 4 — Duas fontes simuladas e validação na porta.**
  - `SimulatedMarketSource` passa a ter instâncias `sim-a` e `sim-b` com 50 tokens em comum, valores divergentes controláveis por semente e campos ausentes; `simulated = true`.
  - Validação na porta (tamanhos, alfabeto Base58, sem caracteres de controle, decimais não negativos, escala até 18, teto de 5 000 tokens).
  - Depois, os testes: tokens comuns casados por chain + mint; mesmo símbolo com mints diferentes; descarte contado por motivo; teto; nenhum socket aberto na coleta simulada.
  - US2.4; BR1.6, BR1.9; NFR6.5, NFR6.6.
- [x] **Passo 5 — Coleta com tempo limite, novas tentativas e estado.**
  - `SourceCaller` com `TimeLimiter` de 5 s e `Retry` de 2 novas tentativas (1 s e 2 s, aleatoriedade de 20%) por fonte.
  - `MarketCollector` (F1) numa transação: valor atual, amostra do intervalo de 5 minutos, estado da fonte, marca global (C18).
  - Agendamento por fonte com ShedLock; coleta imediata na partida; registro de fontes que não regrava o estado.
  - Depois, os testes: estados `PENDING`, `OK`, `DEGRADED`, `FAILED` e transições; tempo esgotado; sucesso na terceira tentativa; coleta interrompida sem resultado parcial; amostra única por intervalo; marca só em mudança de estado; reinício preserva o estado; sobreposição pulada pelo bloqueio.
  - US2.3; BR1.5, BR1.11; NFR10.2 a NFR10.7, NFR4.3, NFR2.1.
- [x] **Passo 6 — Leitura de campos, frescor e divergência.**
  - `FieldResolver` (F2): valor `KNOWN` mais recente entre fontes não `FAILED`; empate pelo menor `sourceId`; desatualizado acima de 5:00 calculado na leitura; divergência por comparação cruzada com tolerância por campo.
  - Variação de preço de 1 hora pela amostra com `observed_at` entre agora − 65 min e agora − 60 min.
  - `MarketDataQuery` (C11) com `referencePrice`, `liquidity`, `window` e `sourceStatuses`.
  - Depois, os testes: 5:00 e 5:01; 0 e `unknown`; motivos de `unknown`; divergência em exatamente 5% e um incremento acima; valor exibido zero; fonte `FAILED` fora; janela de 1 hora em 60:00, 65:00 e agora alinhado a 5 minutos; denominador zero; falha técnica vira `Unknown(SOURCE_UNAVAILABLE)`.
  - US2.3, US2.4; BR1.1, BR1.2, BR1.3, BR1.7, BR1.9, BR1.10; NFR1.1, NFR4.2, NFR10.9.
- [x] **Passo 7 — Limpeza do histórico.**
  - `MarketHistoryCleanup` (F6) por hora, ShedLock, lotes de 10 000.
  - Depois, os testes: 24 h exatas fica, 24 h e 1 s sai; repetição sem efeito; tokens sem observação recente fora da lista.
  - BR1.8; NFR2.3, NFR4.4.

### RiskAssessment

- [x] **Passo 8 — Cálculo do indicador.**
  - `RiskCalculator`: curvas por interpolação linear com arredondamento meio para cima; contribuição com escala 4; soma arredondada; cobertura; faixas; `unknown` com risco 100, contribuição `UNKNOWN` e nunca `LOW` (`bandRaisedByUnknown`); `UNAVAILABLE` por cobertura ou por fontes fora, com os fatores guardados.
  - Depois, os testes unitários com tabelas de valores conhecidos (versão 1): cada ponto de curva e entre pontos; cobertura em 80% exatos e logo abaixo; fronteiras 39/40 e 69/70; fator `unknown` que sobe de Baixo para Médio; fontes fora; e o teste de propriedade com semente fixa (1 000 entradas: 0 a 100, igual em duas execuções, nunca `LOW` com `unknown`).
  - US3.1, US3.2; BR3.1 a BR3.6, BR3.8; NFR4.1, NFR4.2, NFR8.1.
- [x] **Passo 9 — Versões de pesos.**
  - `RiskWeightAdministration` (F7): validar (quatro fatores, soma 100.0000, curvas, faixas, cobertura), criar `DRAFT`, ativar com trava da versão ativa e `RISK_WEIGHTS_ACTIVATED` pelo publicador da U2; `AdminAccess` para o papel.
  - Depois, os testes: cada regra de validação; não administrador recusado com `REQUEST_FORBIDDEN`; ativação com um evento; reativar a mesma versão sem evento; `RETIRED` e alteração de ativada com `WEIGHT_SET_NOT_EDITABLE`; versão inexistente; duas ativações simultâneas em sequência.
  - US3.1; BR4.1, BR4.2; NFR6.4.
- [x] **Passo 10 — Recálculo e guarda.**
  - `RiskRecalculator` (F5) no executor `risk-recalc`, disparado após o commit de cada coleta e da ativação; gatilhos juntados (repete enquanto há pendente); leitura em lote; grava em lotes de 500 só o que mudou; guarda de versão no `ON CONFLICT`.
  - `RiskAssessmentQuery` (C12).
  - Depois, os testes: dois gatilhos durante uma execução geram uma execução extra; fingerprint igual não regrava; versão menor não sobrescreve maior; falha num lote mantém as anteriores; 2 000 avaliações em até 10 s; recálculo reproduzível a partir do guardado.
  - US3.1, US3.2; BR3.7; NFR2.2, NFR10.8, NFR8.1.

### Scanner e API

- [x] **Passo 11 — Consulta da lista.**
  - `JdbcMarketDataQuery` com a consulta única (CTEs) de performance-design.md: foto, fonte não `FAILED`, valor por campo, pivô, avaliação, filtros com "passa" e "unknown", contadores, ordem com `unknown` no fim e desempate por mint e chain, continuação por tupla sem `OFFSET`.
  - Cursor opaco com `snapshotAt`, `filtersDigest`, ordenação e posição.
  - Depois, os testes de integração: cada filtro na fronteira e um incremento além; combinação de filtros; `excludedByUnknown` (contado uma vez, só quando passaria nos filtros conhecidos); risco indisponível fora do filtro e no fim da ordem; `pendingNewTokens`; ordem estável entre páginas; cursor de outra consulta recusado; fonte `PENDING` dá lista vazia; todas `FAILED` dá `503`; uma `FAILED` mantém os tokens da outra; plano sem varredura completa do histórico.
  - US2.2, US2.3, US3.2; BR2.1 a BR2.8, BR1.3, BR1.4; NFR3.4, NFR2.6.
- [x] **Passo 12 — Limitador por pessoa e rota.**
  - `PersonRouteRateLimiter` em `shared.web`: janela deslizante de 60 s, 60 por pessoa e rota, depois da autenticação; `429` com `Retry-After` e `RATE_LIMITED`; remoção de ociosos após 2 minutos; teto de 10 000 chaves.
  - Depois, os testes: 60ª passa, 61ª recebe `429`; `Retry-After` correto; rotas contadas separadamente; sem sessão dá `401` sem contar; remoção com relógio injetável; teto.
  - BR2.7; NFR6.3, NFR2.8.
- [x] **Passo 13 — Rotas da lista e do detalhe.**
  - `ScannerController` (F3) e `AssessmentController` (F4) com validação de parâmetros (BR2.7) e respostas do C2 com os acréscimos.
  - Detalhe: avaliação guardada com desatualizado calculado na leitura; cálculo na hora no primeiro acesso; `404` para token sem observação recente.
  - Depois, os testes de integração e de contrato: `401` sem token, `200` com convidado, `403 FORBIDDEN_ROLE` com administrador em cada rota; `400` com a lista de campos; detalhe com todos os campos da BR3.9; indisponível com `NO_TRADE`; OpenAPI do convidado gerado igual ao versionado; tipos do frontend regenerados.
  - US2.2, US3.1, US3.2; BR3.9, BR2.7; NFR6.1, NFR6.2, NFR3.2.
- [x] **Passo 14 — Saúde e métricas.**
  - `MarketSourcesHealth` substitui `MarketSourceHealthIndicator` com `lastUpdatedAt` no detalhe: `UP`, `DEGRADED` com todas `FAILED`, `UNKNOWN` com todas `PENDING`, nunca `DOWN`; `RiskRecalculationHealth`; ambos fora da prontidão.
  - Métricas e logs de observability-design.md.
  - Depois, os testes: saúde geral `UP` com as duas fontes `OK`; HTTP 200 com corpo `DEGRADED` e todas `FAILED`; `scripts/verify.sh` continua achando `lastUpdatedAt`; `MetricsLabelsIT` (sem mint, chain, carteira, pessoa ou tenant em rótulos); logs da falha só com classe e categoria; `LogScanIT` cobre a U4.
  - BR1.4; NFR9.1 a NFR9.8, NFR7.1.
- [x] **Passo 15 — Regras de arquitetura.**
  - ArchUnit: sem `double`/`float` nos pacotes da U4; `scanner` só pelas interfaces públicas de `marketdata` e `riskassessment`; nenhum dos três depende de bots, ordens, ledger ou carteira; nenhum outro módulo lê as tabelas da U4; nenhum `Principal` de administrador criado em código de produção da U4.
  - Fundação; NFR4.1, NFR6.9, NFR5.1.

### Frontend

- [x] **Passo 16 — Tela S3 com filtros.**
  - Filtros (idade com atalhos e personalizado, liquidez, volume, holders, indicador máximo, origem), ordenação, paginação "Exibindo X–Y de N", estados carregando, erro, parcial, vazio com "Limpar filtros", "Aguardando a primeira coleta", aviso de fontes com falha, contador de excluídos por dado ausente, "N novos tokens — Atualizar lista", marca de desatualizado e de divergência com as origens, coluna Risco com `--` e motivo; região `aria-live`; dicas de termos; `data-testid` nos controles.
  - Depois, os testes Jest: cada estado; filtros na URL; atualização sem trocar a seleção; teclado; marcas em texto e não só por cor.
  - US2.2, US2.3, US2.4, US3.2; BR2.4, BR5.4 a BR5.7; NFR11.1 a NFR11.3, NFR14.1.
- [x] **Passo 17 — Painel lateral da avaliação.**
  - Abre com clique ou Enter sem recarregar a lista; "Carregando avaliação" sem indicador provisório; indicador, nível por escrito, "maior significa mais risco", "Indicador em validação", versão, cobertura, horário do cálculo, explicação de nível elevado, fatores com peso, entrada, fonte, horário, marca e contribuição; indisponível com motivo e `NO_TRADE`; Esc devolve o foco à mesma linha.
  - Depois, os testes Jest: cada estado; foco; nenhum número durante o carregamento.
  - US3.1, US3.2; BR3.9, BR5.2, BR5.3; NFR11.1, NFR3.3.
- [x] **Passo 18 — Termos proibidos.**
  - `apps/web/src/content/prohibited-terms.json` (versão, revisor, termos) e teste que percorre o catálogo de textos do app do convidado sem diferença de maiúsculas e acentos; regra de lint contra `dangerouslySetInnerHTML` nas telas da U4.
  - Depois, o teste com um texto plantado que contém um termo (falha esperada) e o catálogo real (passa).
  - US3.2; BR5.1; NFR6.8.

### Ambiente, carga e smoke

- [x] **Passo 19 — Compose, `.env` e script de carga.**
  - `docker-compose.yml`: variáveis da infrastructure-specification.md no ambiente da `api` com padrões; `.env.example` com as chaves; `ComposeFilesTest` confere cada variável.
  - `scripts/load-test.sh` e `scripts/load/load-test.mjs`: cenários U1 e U4 (e crescimento de 10 000 tokens sem reprovação), limites de p95 por rota (U1 300 ms mantido; lista com filtros 1 s; detalhe 500 ms), leitura de `hikaricp_connections_pending`, memória de `api` e `postgres` (900 MB), `EXPLAIN` sem `Seq Scan` em `observation_sample`, relatório em `docs/perf/u4-discovery-<data>.md`.
  - Depois, `ComposeFilesTest` verde; o script de carga é escrito e conferido com `bash -n`, sem rodar nesta etapa (evidência do ponto de controle).
  - Fundação; NFR3.1, NFR3.2, NFR3.5, NFR2.6, NFR12.3.
- [x] **Passo 20 — Smoke da U4.**
  - `apps/web/e2e/u4-discovery/smoke.spec.ts`: entrar, abrir o scanner, aplicar um filtro, ver o contador de excluídos, abrir o painel, ver o indicador ou a indisponibilidade com motivo, fechar com Esc e voltar o foco à linha.
  - Texto pronto da mudança em `scripts/verify.sh` (acrescentar `e2e/u4-discovery` à linha do Playwright), para o responsável do projeto aplicar.
  - Depois, `bash scripts/verify.sh` (sem a U4 ainda) roda verde; o smoke da U4 roda pelo comando do Playwright direto com o ambiente de pé.
  - US2.2, US3.1; NFR11.1.

### Documentação

- [x] **Passo 21 — Documentação e contrato.**
  - `contract-summary.md` não é editado pelo agente; os acréscimos ao C2, a interface `RiskWeightAdministration` e os códigos `RATE_LIMITED` e `WEIGHT_SET_NOT_EDITABLE` ficam em `docs/api/c2-market-scanner-assessment.md`.
  - ADR de duas fontes simuladas e valor mais recente; ADR do indicador em validação (fatores e pesos da versão 1 como hipótese).
  - README: fontes simuladas, variáveis novas, cenário de carga da U4, rollback para a U2 (procedimento manual de cicd-pipeline.md).
  - Javadoc das interfaces públicas de `marketdata.api` e `riskassessment.api`.
  - Fundação; NFR6.6, NFR10.6.

## Rastreabilidade com as histórias

| História e critérios | Passos |
|---|---|
| US2.2 — filtros combinados (AC2.2.1 a AC2.2.3) | 11, 13, 16, 20 |
| US2.3 — desatualizado e falha de fonte (AC2.3.1 a AC2.3.4) | 3, 5, 6, 11, 14, 16 |
| US2.4 — divergência (AC2.4.1, AC2.4.2) | 4, 6, 16 |
| US3.1 — indicador e painel (AC3.1.1 a AC3.1.5) | 8, 9, 10, 13, 17, 20 |
| US3.2 — cobertura insuficiente e textos proibidos (AC3.2.1 a AC3.2.3) | 8, 10, 11, 13, 16, 17, 18 |
