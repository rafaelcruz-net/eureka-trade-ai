# Plano de Code Generation — U2 `u2-real-market-data` (Bolt B2)

## Summary

- Builds: fonte real de dados de mercado da CoinGecko no worker — adaptador com cota e orçamento, estados tolerantes da fonte, sinal de fonte real ou simulada e atribuição na API e no scanner
- Touches: `apps/api/src/main/java/com/memetrade/marketdata/` (api, domain, infra, infra/coingecko), migração do contador, regra ArchUnit de rede, `apps/web` (atribuição e aviso no scanner), `infra/observability/`, `infra/terraform/` (segredo e alarmes), `.env.example`
- Tests: cerca de 90 testes — unidade e integração (JUnit 5, WireMock com respostas sintéticas, Testcontainers) e Jest na tela; nenhum teste chama a CoinGecko real; piso de 80%

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/functional-design/` (BR1.1 a BR1.10), `construction/u2-real-market-data/nfr-requirements/`, `construction/u2-real-market-data/nfr-design/`, `construction/u2-real-market-data/infrastructure-design/` (correções do contador, dos estados e do prazo), `inception/contract-design/contract-summary.md` (M4, X2), `inception/units-generation/unit-of-work.md` e as regras de `team.md` e `project.md`.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "custom",
  "source": "team",
  "ordering": "No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
  "scope": "classic",
  "test_strategy": "comprehensive",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: custom\n- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.\n- **Methodology evidence**: decisão do responsável do projeto na entrevista\n  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a\n  passar em testes escritos depois sobre o próprio código; com o teste escrito\n  antes, ele valida o requisito e não a implementação. Test-after no restante\n  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo. No\n  intent `261109-termos-lgpd` (Q5, opção A), o responsável do projeto estendeu a\n  ordem de testes antes do código à lógica de dados pessoais (anonimização,\n  exclusão, novo aceite dos termos e expurgos), porque ela toca tabelas\n  append-only como a trilha de auditoria e as ligadas ao ledger. No intent\n  `261113-trading-real-bot`, o NFR8 dos requisitos estende a ordem de testes\n  antes do código à lógica de carteira e saque, e o plano de Bolts pede os testes\n  de ataque da política do TxSigner escritos antes do código (B1) e os testes de\n  estados e idempotência da execução REAL antes do código (B4).\n- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`, também no\n  intent `261109-termos-lgpd` (Q4, opção A); o piso de cobertura abaixo é somado\n  a ela e nunca a substitui.\n- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no\n  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente\n  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam\n  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A\n  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo\n  no Maven e limite de cobertura do executor de testes do frontend). O piso não\n  pode ser reduzido para fazer uma etapa passar. O processo TxSigner também\n  segue o piso de 80%.\n- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou\n  mantém verdes os testes de:\n  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta\n    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que\n    também validam o tenant;\n  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L);\n  - deduplicação por chave de idempotência;\n  - violação de limites autorizados e controles de risco;\n  - dado de mercado ausente vira `unknown`, nunca zero;\n  - pausa do bot impede novas operações;\n  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só\n    acontece por lançamento compensatório;\n  - P&L com cenários determinísticos de resultado conhecido, em aritmética\n    decimal e unidades de token;\n  - REAL desligado por padrão: com a configuração padrão (API, worker e\n    TxSigner), nenhuma carteira do bot é criada, nenhum caminho assina ou envia\n    transação e nenhuma assinatura é pedida ao KMS;\n  - login sem reutilização de assinatura: nonce reutilizado ou assinatura\n    expirada são rejeitados, e a assinatura de login não serve para autorizar\n    trading;\n  - a anonimização não deixa o endereço da carteira em nenhuma tabela nem log\n    consultável;\n  - a versão nova dos termos exige novo aceite antes de qualquer outra rota,\n    **exceto** a leitura da carteira do bot (`GET /api/v1/real/bot-wallet`) e as\n    rotas de saque (`/api/v1/real/bot-wallet/withdrawals` e\n    `/api/v1/real/bot-wallet/withdrawals/{withdrawalId}`), que continuam\n    disponíveis sem o novo aceite para o usuário sempre poder tirar o próprio\n    dinheiro; o teste cobre as duas metades: as rotas da exceção respondem sem o\n    novo aceite e todas as demais respondem `403 TERMS_REACCEPTANCE_REQUIRED`;\n  - saque só para a carteira principal registrada: qualquer outro destino é\n    recusado pela API e pelo TxSigner, e o saque continua disponível com o kill\n    switch acionado, com o REAL desligado e sem a liberação vigente;\n  - a política de conteúdo do TxSigner (ADR-004) recusa toda transação fora da\n    lista: programa fora da lista, assinante ou pagador diferente da carteira do\n    bot, destino fora da carteira do bot ou da carteira principal, saída mínima\n    abaixo da cotação menos a derrapagem, troca de autoridade, fechamento de\n    conta para terceiros e valor acima do teto autorizado; cada recusa registra o\n    motivo;\n  - uma única transação por ordem REAL: repetir a ordem com a mesma\n    `Idempotency-Key`, reprocessar a decisão da fila ou repetir depois de uma\n    ordem ambígua nunca gera segunda transação.\n- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o\n  reproduz.\n- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.\n  Os critérios básicos de acessibilidade do beta definidos em `project.md`\n  continuam valendo.\n- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds\n  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam\n  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação\n  sobre PostgreSQL real em contêiner (por exemplo, Testcontainers) e não dependem\n  de estado compartilhado nem da ordem de execução. Fixtures nunca usam carteira,\n  mint ou dados pessoais reais. No REAL, a rede é o validador Solana local (com\n  contas clonadas para os swaps), o KMS é simulado no ambiente local e as\n  respostas da Jupiter e do RPC usadas em testes de unidade são gravadas; nenhum\n  teste da CI usa devnet, mainnet, KMS real ou chave real.\n- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no\n  Windows. O fim de linha é padronizado em LF por `.gitattributes` e\n  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.\n- **Frequência**: em toda mudança e no merge para `main` rodam os testes de\n  unidade e de integração de todas as partes (backend, `apps/web` e\n  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O\n  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; não há uma\n  suíte maior reservada ao merge.\n- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado\n  em silêncio.\n- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no\n  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.\n- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não\n  funcionais definam metas numéricas."
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
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
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
      "Custom ordering - No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
      "Implementation and tests - preserve that exact ordering; do not convert it to layer-local TDD.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:b7f5625e55bdee4b1fae55cf7a9f270603b9853a8e8e627051890f728c613413",
  "contract_sha256": "sha256:98df078ab1ee44bde9eaa1e5c7f5f121064c1ad1d933fdf12d5563b3111d6400"
}
```

## Ordem aplicada

A U2 é adaptador e configuração, fora do núcleo financeiro e da carteira. Por isso a ordem do contrato é implementar cada camada e, em seguida, testá-la (**[depois]**). A única exceção é a regra "dado de mercado ausente vira `unknown`, nunca zero", obrigatória por risco no `team.md`: seus testes são escritos **antes** do `ValueParser` (**[testes antes]**).

## Etapas

- [x] **Step 1 — Estrutura e configuração.** Criar o pacote `com.memetrade.marketdata.infra.coingecko`. Acrescentar `coingecko` a `KNOWN_SOURCES` e a configuração própria da fonte (base, rede, limites, prazos, atribuição). Ler `COINGECKO_API_KEY` só no worker. Ajustar a regra ArchUnit de rede: proibir `java.net..`, `java.net.http..`, `org.springframework.web.client..` e `org.springframework.http.client..` fora de `infra.coingecko`, com um teste que comprova a falha. Acrescentar o WireMock como dependência de teste. *(NFR1.4, tech-stack)*
- [x] **Step 2 — Runner de testes.** Confirmar os comandos por escopo da Unit (`unit-test-instructions.md`) com um teste vazio. *(Testing Contract)*
- [x] **Step 3 — `ValueParser` [testes antes].** Ler os valores como decimal exato e aplicar as faixas de cada campo. Ausente, inválido ou ilegível vira `NOT_PROVIDED` e conta em `marketdata_invalid_values_total`; zero informado vira `Known 0`. CREATED_AT em segundos de época, nunca no futuro. *(BR1.2, US6.3)*
- [x] **Step 4 — `CoinGeckoHttp` [depois].** Cliente sobre `JdkClientHttpRequestFactory`, sem seguir redirecionamentos (3xx vira falha), com `Accept-Encoding: identity` e recusa de resposta comprimida. Leitura em fluxo com corte em 2 MB e `StreamReadConstraints`. Mint validado antes de montar a URL. Chave no cabeçalho e removida do log; mensagens de erro só com o nome da rota e o código. Tempo limite de 4 s por chamada e 2 s de conexão. *(NFR1.1 a NFR1.3)*
- [x] **Step 5 — `CallBudget` [depois].** Limites por minuto em janela deslizante (250 no total, 2 para detalhes). Tabela `marketdata_provider_usage`, criada por migração, com upsert **antes** de cada chamada numa transação própria. Prioridade do orçamento: preços até 98%, descoberta até 95%, detalhes até 90%. Modo conservador quando a tabela não puder ser lida. Trabalho de hora em hora (ShedLock) que lê a rota de uso e grava o maior valor. *(BR1.3, NFR3.6, NFR3.7)*
- [x] **Step 6 — `CoinGeckoAdapter` [depois].** Implementa `MarketSourceAdapter` e devolve `tolerantFailure() = true`. A rodada roda em série: preços em lotes de até 30; descoberta a cada 5 min, se restarem pelo menos 8 s; detalhes, se restarem pelo menos 4 s. Prazo interno de 18 s pelo relógio injetável, sem abrir chamada nova depois de 12 s. Conjunto ativo em memória com `WatchedTokens`. Uma repetição só em tempo esgotado ou 5xx, se couber no prazo; o 429 não é repetido. Circuito Resilience4j. Devolve `RoundSummary`. *(BR1.1, BR1.3, BR1.9, NFR4.2, NFR4.6)*
- [x] **Step 7 — Coletor e `SourceCaller` [depois].** O `SourceCaller` passa a aceitar configuração por fonte: `coingecko` com 25 s e 1 tentativa; as simuladas continuam como estão. O coletor usa `RoundSummary` e `tolerantFailure` nos caminhos de sucesso e de `fail()`: na fonte tolerante, a primeira rodada sem preço fica DEGRADED e a segunda, FAILED. Categorias que já existem: `TIMEOUT` e `ERROR_RESPONSE`. Teste de contrato do mapa de estados. *(BR1.9, NFR4.3)*
- [x] **Step 8 — `Freshness` por campo [depois].** Preços com limite de 5 min e HOLDERS e CREATED_AT com 2 h, sem mudar `ResolvedField` nem `MarketValue`. *(BR1.4)*
- [x] **Step 9 — Interface M4 [depois].** `MarketDataQuery` ganha `activeSourceKind()` e `attribution()`; `WatchedTokens` entra em `marketdata.api` com uma implementação padrão vazia. A fonte única é derivada de `ResolvedField`. *(BR1.5, BR1.7)*
- [x] **Step 10 — Trava de subida [depois].** Com `memetrade.real.enabled=true`, fora dos perfis `test` e `local`, a API e o worker não sobem com `sim-a` ou `sim-b`. Teste de que a API sobe com `coingecko` e sem a chave. *(BR1.6, NFR1.5)*
- [x] **Step 11 — API do scanner e tela [depois].** As respostas do scanner e do detalhe trazem a atribuição; o OpenAPI é regenerado, assim como os tipos do web. A tela mostra "Dados: CoinGecko" com o link, ou "fonte simulada ativa", em texto e com `data-testid`. Testes Jest. *(BR1.8, NFR7.1)*
- [x] **Step 12 — Métricas [depois].** Métricas do NFR Design, inclusive `marketdata_observations_total` e o estado da fonte como número por estado. Rótulos sem chave e sem mint. *(NFR5.1 a NFR5.5)*
- [x] **Step 13 — Alertas, Terraform e `.env.example` [depois].** Regras em `infra/observability/` (`promtool`). Segredo `coingecko-api-key` e alarmes no Terraform. Teste que compara os limites dos alarmes com as regras do `promtool`. `COINGECKO_API_KEY` entra no `.env.example` só com o nome. *(monitoring-design, infrastructure-specification)*
- [x] **Step 14 — Documentação e rastreabilidade.** Runbook de giro e vazamento da chave, `code-summary.md`, `traceability.json` e `source-manifest.json`.

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US6.3 (parte de dados) | 3, 4, 5, 6, 7, 8, 9, 10, 11 |

## Fora desta Unit

A recusa de ordem REAL por dado vencido ou fonte simulada fica na U3. A marcação do P&L REAL e as posições observadas ficam na U4.
