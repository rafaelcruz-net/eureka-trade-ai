# Instruções de teste — U1 `u1-legal-documents`

Conversa: português (Brasil). Estratégia `Comprehensive` (10 a 15 testes por componente, de unidade, integração e ponta a ponta), com piso de 80% de linhas no backend e em cada app. Ordem do time: os testes da ativação do catálogo vêm antes do código (Passo 2 do plano); nas demais camadas, cada camada é implementada e depois testada.

## Ferramentas e configuração

| Parte | Ferramenta | Configuração existente |
|---|---|---|
| Backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), JaCoCo | `apps/api/pom.xml` (regra `check` em 80%) |
| Web e admin | Jest e Testing Library | `apps/web/jest.config.*`, `apps/admin/jest.config.*` (limite de cobertura 80%) |
| Ponta a ponta | Playwright | `apps/web/playwright.config.ts`, rodado pelo `scripts/verify.sh` |
| Carga | Node (`scripts/load/load-test.mjs`) com `node --test` para o próprio script | `scripts/load/` |

## Comandos da Unit (só os testes desta Unit)

Rodar da raiz do repositório.

```bash
# Backend: ativação, conteúdo, porta J5, rotas, limitador, saúde e Compose
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml test -Dtest='TermsCatalog*Test,LegalTextContentTest,RetentionTableSource*Test,LegalDocumentsController*,LegalDocumentsRateLimiter*,LegalDocumentsHealth*,LegalSettingsBinding*,CurrentTermsActivation*,ComposeFilesTest' -Dsurefire.failIfNoSpecifiedTests=false

# Web: componentes e páginas legais
corepack pnpm@10.18.3 --dir apps/web exec jest src/features/legal src/components/shell/SiteFooter --coverage=false

# Admin: componentes e páginas legais
corepack pnpm@10.18.3 --dir apps/admin exec jest src/features/legal src/components/SiteFooter --coverage=false

# Script de carga (só o teste do cenário legal)
node --test scripts/load/load-test-legal.test.mjs

# Ponta a ponta da Unit (com o ambiente do verify.sh já de pé)
corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/legal-documents
```

Antes do Passo 2, o primeiro comando já roda (o Maven e o filtro existem) e devolve "nenhum teste encontrado" sem falhar.

## Metas de cobertura

80% de linhas no backend, no web e no admin, medido pela configuração existente. As exclusões ficam só na configuração da ferramenta. Os arquivos novos devem passar de 80%.

## Mocks e dados de teste

- **Relógio:** `Clock` injetável nos testes de ativação (vigência futura, ordem por data).
- **Catálogo:** catálogos de teste em `apps/api/src/test/resources/terms-test/` (válido, sem revisão, legacy, futuro, acima do limite, sem política). Nunca o texto real da v2.
- **Configuração:** campos de implantação e `require-publishable` por propriedades de teste.
- **IP:** `MockHttpServletRequest.setRemoteAddr` para o par direto e cabeçalho `X-Forwarded-For` forjado.
- **Apps:** API simulada por `fetch` mockado; nada de rede real.
- **Fixtures:** sem carteira, mint ou dado pessoal real.
