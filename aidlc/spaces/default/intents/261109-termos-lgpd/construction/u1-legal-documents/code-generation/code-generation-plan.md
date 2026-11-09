# Plano de código — U1 `u1-legal-documents`

## Summary

- Builds: catálogo de termos v2 com ativação por revisão jurídica, rotas públicas `/api/v1/legal/**` com cache e limite próprio, e páginas `/termos` e `/privacidade` com rodapé nos dois apps (fatia fina).
- Touches: `apps/api` (`identity` catálogo, web, ratelimit; `shared/error`; `resources/terms/v2`; `application*.yml`), `apps/web` e `apps/admin` (páginas legais, `LegalDocument`, `SiteFooter`, tipos gerados), `docker-compose.yml`, `.env.example`, `scripts/load`, `apps/web/e2e/legal-documents`, OpenAPI e contrato L1.
- Tests: cerca de 120 testes novos (JUnit 5 e Testcontainers, Jest, Playwright e o cenário de carga `legal`), com piso de 80% de linhas no backend e nos dois apps.

Conversa: português (Brasil); código, nomes e logs em inglês. Fontes:
- `construction/u1-legal-documents/functional-design/` (BR1.1 a BR1.16);
- `nfr-requirements/` (NFR1.x a NFR9.1);
- `nfr-design/`;
- `infrastructure-design/`;
- `inception/contract-design/contract-summary.md` (L1, J5);
- `inception/user-stories/stories.md` (US1.1–US1.3, US2.1, US2.2, US2.4–US2.6).

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

- [x] **Passo 1** — Prontidão dos executores: confirmar que os comandos da Unit em `unit-test-instructions.md` rodam (Maven com filtro de teste, Jest por caminho, Playwright por spec) antes do primeiro teste. (todas as histórias)

### Backend — catálogo e ativação (testes antes do código para a ativação, NFR9.1)

- [x] **Passo 2** — Testes da ativação, escritos antes (`TermsCatalogActivationTest`), com o relógio injetável. Casos:
  - marcada revisada vira vigente;
  - marcada sem revisão leva à anterior por data de vigência, com `v10` posterior a `v2`, e gera um WARN;
  - vigência futura é ignorada;
  - `require-publishable` com campo faltando ignora a não legacy e mantém a legacy;
  - sem nenhuma ativável, a subida falha;
  - limites de 200 KB e 100 seções;
  - versão não legacy sem política, resumo ou `retention.tsv` falha;
  - resumo com 4 ou 8 linhas falha.

  (US1.3; BR1.1, BR1.2, BR1.3, BR1.6)
- [x] **Passo 3** — Implementar no `identity/infra/TermsCatalog` e no domínio:
  - leitura de `legacy`, `effectiveAt`, `legalReviewedAt` e `legalReviewedBy`;
  - textos `privacy.txt`, `terms-summary.txt`, `privacy-summary.txt` e `retention.tsv`;
  - parser de seções `## ` e limites;
  - `LegalSettings` (`memetrade.legal.*`) e `DeploymentFields`;
  - cálculo de ativáveis e de vigente efetiva, histórico e ignoradas;
  - resposta pronta por versão, com `ETag` por hash do conteúdo.

  Os testes do Passo 2 ficam verdes. (US1.1, US1.2, US1.3)
- [x] **Passo 4** — Ajustar `CurrentTerms` e `find` para usar a vigente efetiva e o histórico (BR1.16), com teste de que o aceite no convite recusa uma versão ignorada e de que `find` não acha ignorada nem futura. (US1.3)
- [x] **Passo 5** — Catálogo real: marcar `terms/v1/version.properties` com `legacy=true` e `effectiveAt`. Criar `terms/v2/` com os textos v2 (termos, política com as seções exigidas, aviso de risco, resumos e `retention.tsv`), redigidos a partir do sistema e marcados como rascunho, **sem** `legalReviewedAt` e `legalReviewedBy`. Assim a v2 não ativa até a revisão da assessoria. Criar um catálogo de teste (`src/test/resources/terms-test/`) com uma v2 revisada para testes. (US1.1)
- [x] **Passo 6** — Teste de conteúdo (`LegalTextContentTest`): a política v2 tem cada seção exigida e o aviso PAPER, e nenhum texto usa termo de `apps/web/src/content/prohibited-terms.json`. (US1.1; BR1.5)

### Backend — porta J5 e rotas L1

- [x] **Passo 7** — Porta `identity.api.RetentionTableSource`, com a implementação padrão do `identity` (tabela da versão vigente) registrada só quando não houver outra (`@ConditionalOnMissingBean`). Teste de que, com outra implementação registrada, ela vence. (US1.1; BR1.8)
- [x] **Passo 8** — `LegalDocumentsController` (`identity/web`): `GET /api/v1/legal/current`, `/versions` e `/versions/{version}`.
  - respostas montadas na subida;
  - `Cache-Control: public, max-age=300` e `ETag` definidos antes de `checkNotModified`, só em 200 e 304;
  - versão fora do histórico responde `404 NOT_FOUND`;
  - campos ausentes vão como nulos, inclusive `privacy` e os resumos na legacy (BR1.15).

  (US1.1, US1.2, US2.1, US2.6)
- [x] **Passo 9** — `AuthenticationFilter`: isentar `GET`/`HEAD` em `/api/v1/legal/**` de sessão. Outros métodos sem sessão continuam com `401`, como o resto, e o teste de segurança espera isso. Registrar `/api/v1/legal/**` antes do padrão geral em `GuestCorsPolicy` e `AdminCorsPolicy` (só `GET`/`HEAD`, sem credenciais, expondo `ETag`). (US2.1; NFR6.2)
- [x] **Passo 10** — `LegalDocumentsRateLimiter` (`identity/domain/ratelimit`), no padrão do `InviteLookupRateLimiter`, com contadores próprios:
  - 60 por minuto por IP do cliente (`ClientIpResolver`);
  - isenção por CIDR comparada só com `getRemoteAddr()`, normalizando IPv4 mapeado em IPv6;
  - limpeza periódica no padrão do `LoginLimiterEvictionJob`;
  - filtro ordenado depois do `SecurityHeadersFilter`;
  - excedeu: `429 RATE_LIMITED` com `Retry-After`;
  - propriedades `memetrade.legal.rate-limit-per-minute` e `memetrade.legal.rate-limit.internal-networks`; valor alto em `application-loadtest.yml`.

  (NFR6.3)
- [x] **Passo 11** — Indicador de saúde `legalDocuments` (`currentVersion`, `publishable`): `UP` ou `DEGRADED` (HTTP 200), fora dos grupos de liveness e readiness. Logs `INFO` e `WARN` na subida, sem texto. (NFR7.3, NFR8.1, NFR8.2)
- [x] **Passo 12** — Testes de integração das rotas (`LegalDocumentsControllerIT`, Testcontainers):
  - 200 com corpo completo;
  - 304 com `If-None-Match`;
  - cabeçalhos por status (200 e 304 com cache; 404 e 429 com `no-store`);
  - sem sessão e com sessão;
  - legacy vigente sem política;
  - `publishable` falso;
  - CORS das duas origens;
  - `POST` sem sessão recebe 401.

  Teste do limitador (`LegalDocumentsRateLimiterTest`, `...IT`):
  - IP externo recebe 429;
  - `X-Forwarded-For` interno forjado vindo de IP externo recebe 429;
  - par direto da faixa interna não recebe 429;
  - limpeza dos contadores.

  Teste de binding da lista CIDR, da saúde e dos grupos, e de logs sem texto. (US1.x, US2.1; NFR6.x, NFR7.x, NFR8.x)
- [x] **Passo 13** — OpenAPI gerado e contrato: regenerar `openapi-guest.yaml` e `openapi-admin.yaml` com as rotas L1. No mesmo commit, atualizar o bloco L1 de `inception/contract-design/contract-summary.md`: campos opcionais da legacy, sem o 503 e `ETag` por hash. (contrato e código juntos)

### Apps — páginas e rodapé (implementação e testes por camada)

- [x] **Passo 14** — Tipos gerados nos dois apps (`pnpm check:api`). Cliente de servidor que busca `/api/v1/legal/**` com cache de dados de 300 s e sem cache de erro; a origem da API só no servidor. (US2.1)
- [x] **Passo 15** — Componentes no web e no admin, com textos de interface em pt-BR:
  - `LegalDocument`, com faixa de versão antiga, aviso de não publicável, aviso PAPER, resumo, índice (recolhido abaixo de 768 px) e seções em texto;
  - `RetentionTable`, empilhada abaixo de 768 px;
  - `DeploymentFields`, que mostra "a definir";
  - `RequestButton`, que leva à Conta com sessão e à entrada sem sessão, com o e-mail do encarregado como `mailto:`;
  - `VersionHistory`;
  - `SiteFooter`.

  (US2.1, US2.2, US2.4, US2.5, US2.6)
- [x] **Passo 16** — Páginas `/termos` e `/privacidade` nos dois apps, com `?versao=`, erro com "tentar de novo", "Versão não encontrada" e legacy sem política. As páginas ficam fora da exigência de sessão de cada app e sem o aviso "só para computador". O `SiteFooter` entra no `AppShell` dos dois apps, com a versão aceita da sessão. No admin, o `no-store` do middleware fica mantido. (US2.1, US2.2)
- [x] **Passo 17** — Testes Jest (10 a 15 por componente):
  - texto com `<script>` aparece literalmente;
  - índice e âncoras;
  - estados de erro, versão antiga e inexistente;
  - legacy sem política;
  - "a definir";
  - destinos do botão;
  - rodapé com e sem sessão;
  - teclado e foco.

  Piso de 80% nos dois apps. (US2.x; NFR4.1, NFR6.4)

### Infraestrutura, ponta a ponta e carga

- [x] **Passo 18** — `docker-compose.yml`:
  - rede com `ipam` em `${COMPOSE_SUBNET:-172.28.0.0/16}`, gateway em `.1`;
  - `web` e `admin` com `ipv4_address` em `${COMPOSE_APPS_SUBNET:-172.28.1.0/24}`;
  - `api` com as variáveis `MEMETRADE_LEGAL_*` repassadas.

  `.env.example` com os nomes, sem valores. Atualizar o `ComposeFilesTest` e conferir o `infra/docker-compose.test.yml`. (NFR6.3)
- [x] **Passo 19** — Spec de ponta a ponta `apps/web/e2e/legal-documents/smoke.spec.ts`:
  - `/termos` e `/privacidade` sem sessão;
  - versão, resumo, aviso PAPER e "a definir";
  - rodapé e destino do botão;
  - "pular para o conteúdo" e foco por teclado;
  - largura de 320 px sem rolagem horizontal;
  - cabeçalhos de segurança;
  - `429` ao passar do limite pela porta publicada (o gateway não fica isento).

  O ambiente do `verify.sh` usa o catálogo de teste com a v2 revisada (perfil `test`). O responsável do projeto acrescenta o spec ao `verify.sh`. (US2.1, US2.2, US2.4, US2.5; NFR4.1, NFR6.3, NFR6.5)
- [x] **Passo 20** — Cenário de carga `legal` em `scripts/load/` (`legal.mjs`, com teste do script):
  - 20 usuários por 5 minutos, metade nas páginas e metade na API;
  - reprova com p95 de página acima de 2 s, p95 da API acima de 300 ms ou qualquer 5xx;
  - relatório em `docs/perf/`.

  O responsável do projeto acrescenta `legal` às opções do `load-test.yml`. (NFR3.1 a NFR3.3)
- [x] **Passo 21** — Documentação: `docs/api/` com as rotas de documentos, o checklist de abertura do beta (revisão jurídica registrada na v2, campos de implantação e banco novo) e a nota de redação da v2 como rascunho. (US1.3)
- [x] **Passo 22** — Rodar os comandos da Unit (`unit-test-instructions.md`), cobertura dos três projetos e `bash scripts/verify.sh` pelo ponto de controle do esqueleto. (todas)

## Rastreabilidade história → passos

| História | Passos |
|---|---|
| US1.1 | 3, 5, 6, 7, 8, 13 |
| US1.2 | 3, 8 |
| US1.3 | 2, 3, 4, 21 |
| US2.1 | 8, 9, 12, 14, 15, 16, 17, 19 |
| US2.2 | 15, 16, 17, 19 |
| US2.4 | 15, 17, 19 |
| US2.5 | 15, 17, 19 |
| US2.6 | 8, 15, 17 |

## Pré-requisitos do responsável do projeto

1. `ci.yml` com as mudanças do `ci-config.md` antes do primeiro commit deste trabalho.
2. `e2e/legal-documents` no `verify.sh` antes do ponto de controle da U1.
3. Opção `legal` no `load-test.yml` antes do Build and Test.
