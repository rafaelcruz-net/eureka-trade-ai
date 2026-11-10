# Resumo do código — U1 `u1-legal-documents`

Conversa: português (Brasil). Código, nomes e logs em inglês. Este resumo cobre os 22 passos de `code-generation-plan.md`, todos marcados. A lista completa de arquivos está em `source-manifest.json`, e a ligação com os requisitos está em `traceability.json`.

## Arquivos criados e alterados

**API (`apps/api`)**
- **Domínio `identity/domain/legal/`:** `LegalVersion`, `LegalText`, `LegalSection`, `LegalTextParser`, `LegalActivation`, `ActiveLegalDocuments`, `IgnoredVersion`, `LegalSettings`, `DeploymentFields` e `InvalidLegalCatalogException`.
- **Porta J5:** `identity/api/RetentionTableSource` e `RetentionItem`. A implementação padrão é `identity/infra/CatalogRetentionTableSource`, registrada com `@ConditionalOnMissingBean` em `LegalConfig`.
- **Catálogo:** `LegalCatalogReader` é novo. Foram alterados `TermsCatalog` (ativação, histórico e versões ignoradas), `LegalProperties` e `IdentityConfig`. `CurrentTerms` agora usa a vigente efetiva (BR1.16).
- **Rotas L1:** `LegalDocumentsController`, `LegalDocumentsView` (respostas montadas na subida, `ETag` por SHA-256 do corpo) e `LegalDtos` (campos ausentes vão como nulos).
- **Acesso:**
  - `AuthenticationFilter` deixa `GET` e `HEAD` em `/api/v1/legal/**` passarem sem sessão; os outros métodos sem sessão continuam recebendo 401.
  - `GuestCorsPolicy` e `AdminCorsPolicy` ganharam a regra legal antes da regra geral.
- **Limite de acesso:**
  - `LegalDocumentsRateLimiter` e `InternalNetworks` (CIDR comparado só com o par direto, IPv4 mapeado normalizado);
  - `LegalDocumentsRateLimitFilter`, posicionado depois de `SecurityHeadersFilter` (`FilterOrder`);
  - `RateLimitConfig` e a limpeza em `LoginLimiterEvictionJob`.
- **Saúde:** `LegalDocumentsHealthIndicator` responde sempre `UP`, com `currentVersion` e `publishable` nos detalhes, fora dos grupos de liveness e readiness (ver o desvio 8).
- **Recursos:**
  - `terms/v1/version.properties` marcada `legacy=true`;
  - `terms/v2/` em rascunho, sem `legalReviewedAt` nem `legalReviewedBy`;
  - catálogos de teste em `src/test/resources/terms-test/`;
  - `application.yml` e `application-loadtest.yml` (limite alto);
  - `route-registry.yml` com as três rotas L1 em `GLOBAL`.
- **Contrato:** `openapi-guest.yaml` e `openapi-admin.yaml` regenerados; `OpenApiConfig` passa a gerar `privacy` como valor que aceita nulo. O bloco L1 de `inception/contract-design/contract-summary.md` foi atualizado junto.

**Apps (`apps/web`, `apps/admin`)**
- **Tipos e cliente:** `schema.d.ts` regenerado nos dois apps. `features/legal/legal-api.ts` guarda em cache por 300 s só as respostas de sucesso, e a origem da API fica só no servidor.
- **Componentes:** `LegalDocument`, `RetentionTable`, `DeploymentFields`, `RequestButton`, `VersionHistory`, `LegalPage` e `SiteFooter` nos dois apps.
- **Páginas:** `/termos` e `/privacidade` (com `loading` no web).
- **Rodapé:** entra no `AppShell` do web e no `AdminLayout` do admin. No web, a versão aceita vem de `GET /api/v1/account`, por meio de `session-account.ts`.
- **Spec de ponta a ponta:** `apps/web/e2e/legal-documents/smoke.spec.ts`. O spec da U3 foi ajustado e agora compara com a versão mostrada no convite.

**Infraestrutura, carga e documentação**
- **`docker-compose.yml`:**
  - rede com `ipam` em `${COMPOSE_SUBNET:-172.28.0.0/16}` e `ip_range` dinâmico fora da sub-faixa dos apps;
  - IPs fixos de `web` e `admin` em `${COMPOSE_APPS_SUBNET:-172.28.1.0/24}`;
  - variáveis `MEMETRADE_LEGAL_*` e `API_INTERNAL_ORIGIN`.
- **`infra/docker-compose.test.yml`:** monta `terms-test/reviewed` só para leitura e define `MEMETRADE_TERMS_LOCATION`.
- **`.env.example`:** recebeu só os nomes das variáveis. O `ComposeFilesTest` foi atualizado.
- **Carga:** `scripts/load/legal.mjs` e seu teste, com o caso `legal` em `scripts/load/load-test.mjs` e `scripts/load-test.sh`.
- **Documentação:** `docs/api/u1-legal-documents.md`, com as rotas, a ativação, a situação da v2 e o checklist de abertura do beta.

## Decisões de implementação

1. **Testes antes do código na ativação.** O `TermsCatalogActivationTest` (21 testes) foi escrito antes do código. O Red foi `sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml test -Dtest='TermsCatalogActivationTest'`, que falhou na compilação: o pacote `com.memetrade.identity.domain.legal` ainda não existia. Depois do Passo 3, os 21 testes ficaram verdes.
2. **A v1 continua vigente.** A v2 está marcada como vigente, mas não ativa até a revisão jurídica ser registrada nela (BR1.1). Por isso a v1 legacy segue vigente, e a resposta sai com `provisional=true` e `publishable=false`.
3. **`ETag`.** É o SHA-256 do corpo, como no plano e no bloco L1. Não usamos o formato `"legal-<versão>"`, que aparecia no `performance-design.md`.
4. **Texto da legacy sem títulos.** Numa versão legacy, um texto sem `## ` vira uma única seção implícita. Numa versão não legacy, texto antes do primeiro `## ` invalida a versão.
5. **Campos novos em L1.** O campo `provisional` foi acrescentado em `LegalDocuments` e `LegalVersionRef` (BR1.9).
6. **Rodapé no admin.** O rodapé do admin não mostra "Versão aceita", porque o administrador não aceita os termos do convidado. Ali, "Fazer um pedido" leva à entrada do app do convidado.

## Testes executados

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml verify` (todo o backend, Spotless e JaCoCo `check`) | 994 testes de unidade e 784 de integração, 0 falhas; cobertura de linhas 93,87% (piso de 80%) |
| Comando Maven da Unit (`unit-test-instructions.md`) | 114 testes, 0 falhas |
| `apps/web`: `lint`, `typecheck`, `format:check`, `check:api`, `test:coverage` | verdes; 442 de 442 testes; piso de 80% atendido (95,57% de linhas) |
| `apps/admin`: `lint`, `typecheck`, `format:check`, `check:api`, `test:coverage` | verdes; 114 de 114 testes; piso de 80% atendido (91,22% de linhas) |
| `node --test scripts/load/load-test-legal.test.mjs` | 8 de 8 |

Os testes novos da Unit somam cerca de 210:
- backend: 114 no filtro da Unit, mais os ajustes em testes existentes;
- 51 no web;
- 29 no admin;
- 6 de ponta a ponta;
- 8 do script de carga.

A primeira rodada completa do backend teve uma falha em `AdminGuestRoutesIT`: o teste esperava 403 para o token de administrador em toda rota do convidado, mas `/api/v1/legal/**` agora é pública para os dois (BR1.7). As três rotas entraram na lista `PUBLIC` desse teste. Na segunda rodada, tudo passou.

## Testes escritos e não executados nesta etapa

- **`apps/web/e2e/legal-documents/smoke.spec.ts` (6 testes) e o spec da U3 ajustado.** Os dois precisam do ambiente subido pelo `scripts/verify.sh`. O responsável do projeto acrescenta `e2e/legal-documents` ao `verify.sh` e o roda no ponto de controle do esqueleto.
- **Cenário de carga `legal` contra o ambiente.** Fica para o Build and Test, depois que o responsável acrescentar a opção `legal` ao `load-test.yml`.
- **CI.** O `ci.yml` ainda não tem as mudanças do `ci-config.md`, um pré-requisito do responsável antes do primeiro commit deste trabalho. Por isso, nada desta Unit rodou na CI.

## Desvios do plano e lacunas

1. **`MEMETRADE_LEGAL_RATE_LIMIT_PER_MINUTE` não é repassada no Compose.** Uma variável de ambiente se sobrepõe ao `application-loadtest.yml`, e o valor 60 faria o cenário de carga receber 429. O padrão 60 vem do `application.yml`; na nuvem, a variável funciona por binding direto. Isso é uma lacuna na especificação de infraestrutura.
2. **Variáveis de Compose além da especificação:**
   - `API_INTERNAL_ORIGIN`, sem a qual o servidor Next.js dentro do contêiner não alcança a API;
   - `COMPOSE_GATEWAY`, `COMPOSE_DYNAMIC_RANGE`, `COMPOSE_WEB_IPV4` e `COMPOSE_ADMIN_IPV4`, para nenhum outro contêiner cair na faixa isenta.
3. **Catálogo revisado no ambiente de ponta a ponta.** O catálogo de teste com a v2 revisada entra por montagem no overlay de teste, e não pelo perfil `test`. Isso evita quebrar as ITs desse perfil e deixa a v2 de teste fora da imagem.
4. **`POST` com sessão em `/api/v1/legal/**`.** Responde 400 sem `Idempotency-Key`, ou 405 com ela. O `security-design.md` falava só em 405; seguimos o plano aprovado, que exige apenas o 401 sem sessão.
5. **Grupo OpenAPI do admin.** Passou a incluir `/api/v1/legal/**`. O construtor de 2 argumentos do `TermsCatalog` foi removido por causa da regra ArchUnit do relógio.
6. **Relatório de carga.** O cenário de carga grava o relatório em `docs/perf/u1-legal-documents-<data>.md` e não grava `load-test-report.json`.
7. **Volume de testes abaixo da estratégia.** `RetentionTable`, `DeploymentFields`, `VersionHistory` e `RequestButton` têm 2 ou 3 testes cada, abaixo dos 10 a 15 por componente da estratégia `Comprehensive`. `LegalDocument` tem 14 no web e 10 no admin. A lacuna fica registrada; nenhuma meta foi reduzida.

8. **Saúde `legalDocuments` sempre `UP` (correção no ponto de controle do esqueleto).** A primeira verificação com `bash scripts/verify.sh` falhou antes do Playwright. Sem os campos de implantação, o componente ficava `DEGRADED`, e a ordem de status `DOWN,OUT_OF_SERVICE,DEGRADED,UP` levava a saúde geral para `DEGRADED`. O `verify.sh` espera `UP`, e o NFR7.3 pede que o componente não derrube a saúde geral. Por decisão do responsável do projeto, o componente fica sempre `UP` e sinaliza a versão não publicável pelo detalhe `publishable=false`, que é o que o alerta `LEGAL_NOT_PUBLISHABLE` lê. Isso contraria o NFR8.2, que previa `DEGRADED`. Os ajustes estão em `LegalDocumentsHealthIndicator`, `LegalDocumentsHealthIndicatorTest`, `LegalDocumentsControllerIT` e `docs/api/u1-legal-documents.md`.

## Pendências do responsável do projeto

1. Aplicar no `ci.yml` as mudanças do `ci-config.md` antes do primeiro commit deste trabalho.
2. Acrescentar `e2e/legal-documents` ao `scripts/verify.sh` antes do ponto de controle da U1.
3. Acrescentar a opção `legal` ao `.github/workflows/load-test.yml` antes do Build and Test.
