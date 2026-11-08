# Resumo do código — U1 Esqueleto de ponta a ponta

Código gerado para a Unit `u1-walking-skeleton` a partir do [plano aprovado](code-generation-plan.md), com os testes descritos em [unit-test-instructions.md](unit-test-instructions.md). Os 26 passos foram executados e marcados. Rastreabilidade em [traceability.json](traceability.json) e caminhos em `source-manifest.json`.

## Reabertura de 30/10/2026

A geração de código da U1 foi reaberta a pedido do responsável do projeto. A geração parcial da U2 (passos 1 a 6 do plano dela) mudou arquivos compartilhados, e a conferência seguinte da U1 achou dois defeitos: o smoke falhava porque nenhum serviço criava os logins do banco, e a rota administrativa da U2 não tinha teste negativo registrado.

**Reparo, antes da reabertura:**
- `docker-compose.yml`: serviços `db-bootstrap` (logins e privilégios, superusuário) e `db-migrate` (Liquibase com o dono do esquema). A `api` depende de `db-migrate`, não recebe a credencial do dono nem a do superusuário e recebe a do login de retenção. O `postgres` ganhou healthcheck por TCP e parâmetros de log e de horário de commit.
- `scripts/verify.sh`, `scripts/load-test.sh` e `.env.example`: chaves `DB_OWNER_*` e `DB_RETENTION_*`.
- `apps/api/src/main/java/com/memetrade/identity/web/AdminRouteFilter.java`: só o administrador passa em `/api/v1/admin/**`; os outros papéis recebem `403 FORBIDDEN_ROLE`, e cada recusa grava um `REQUEST_FORBIDDEN`.
- `apps/api/src/test/java/com/memetrade/audit/AdminAuditRouteIT.java`: teste negativo da rota, referenciado pelo registro de rotas.
- `ComposeFilesTest`: cinco serviços e credenciais por serviço.

**Conferência dos 26 passos nesta reabertura:** todos atendidos sobre o código atual, sem regerar código. A única mudança foi o `README.md`, com o modo de desenvolvimento passando a ser `docker compose up postgres db-bootstrap db-migrate`.

**Passos que evoluíram além do texto do plano (o comportamento da U1 continua valendo):**
- Passo 3: a base de integração usa três logins de banco.
- Passo 5: a regra "aplicação sem permissão de criar banco" vem de `infra/postgres/bootstrap/10-roles.sql`.
- Passo 8: o publicador valida antes de gravar e o relay mede a entrega.
- Passo 23: o Compose tem `db-bootstrap` e `db-migrate`.

**Resultados:**

| Comando | Resultado |
|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` | Saída 0; 282 testes unitários e 108 de integração sem falhas; 94,22% de linhas (piso de 80%) |
| `pnpm --dir apps/web exec jest --coverage src/features/auth src/features/scanner src/lib src/middleware.test.ts` | 80 testes passando; 98,32% de linhas |
| `bash scripts/verify.sh` | Saída 0; os 3 specs Playwright passaram e a varredura de logs ficou limpa |

**Não executado nesta reabertura:** lint, Prettier e `tsc` do frontend e a comparação do OpenAPI (`check:api`). São portões da CI, fora dos comandos da Unit; foram verdes na entrega original.

## Smokes da U3 e da U9 no comando de verificação (2026-11-08)

O responsável do projeto editou à mão o `scripts/verify.sh` (Passo 27 do plano), que só ele altera. O agente não editou o script; esta seção só registra a mudança.

**O que mudou no `scripts/verify.sh`:**
- Depois da geração das carteiras de administrador, o script gera o código do convite de smoke com a ferramenta de teste `com.memetrade.tools.SmokeWalletTool --invite-code` (`exec:java` com `-Dexec.classpathScope=test`), tira o fim de linha com `tr -d '\r\n'`, exporta `SMOKE_INVITE_CODE` e para com erro se o código não tiver exatamente 43 caracteres. O `tr` é necessário porque, no Git Bash do Windows, o `println` da ferramenta deixava um `\r` no fim do valor capturado.
- A linha do Playwright recebe `SMOKE_INVITE_CODE="$SMOKE_INVITE_CODE"` e passa a rodar também `e2e/u9-dashboard` e `e2e/u3-identity-access`, depois de `e2e/u1-walking-skeleton`, `e2e/u2-audit-trail`, `e2e/u4-discovery`, `e2e/u7-ledger-pnl`, `e2e/u5-bots-decision` e `e2e/u8-admin-feedback`.
- A varredura dos logs de `api`, `worker` e `admin` procura também o código do convite, ao lado do endereço da carteira beta, dos endereços de administrador e do marcador do texto de feedback; qualquer ocorrência encerra o script com saída diferente de zero.

**Pendências do Build and Test que a mudança fecha:**
- TP-E2E-U3: smoke de ponta a ponta da U3 (convites, contas e sessões) no comando de verificação.
- TP-E2E-U9: smoke de ponta a ponta da U9 (painel) no comando de verificação.
- U3-NFR3.4: o fluxo de convite é exercitado de ponta a ponta pelo comando de verificação.
- U3-NFR9.1: o código do convite não aparece nos logs dos serviços.

**Verificação:** `bash scripts/verify.sh` roda no ponto de controle do esqueleto, executado pelo orquestrador; o agente não rodou o script nem o Docker nesta etapa. O resultado esperado é saída 0, com `e2e/u3-identity-access` e `e2e/u9-dashboard` passando junto dos smokes que já rodavam e nenhum endereço de carteira, texto de feedback ou código de convite nos logs. O orquestrador anota aqui o resultado depois da execução. Se algum smoke falhar, a saída é registrada nesta seção e a decisão fica com o responsável do projeto, sem mudar o `scripts/verify.sh`.

O texto preparado em [`docs/api/u3-convites-contas-e-sessoes.md`](../../../../../../../../docs/api/u3-convites-contas-e-sessoes.md) foi atualizado para bater com o script (captura com `tr -d '\r\n'` e linha do Playwright com `e2e/u9-dashboard`).

## Arquivos criados e modificados

| Área | Caminhos |
|---|---|
| Raiz | `.editorconfig`, `.gitattributes`, `.gitignore` (modificado, bloco do AI-DLC preservado), `.env.example`, `.nvmrc`, `.gitleaks.toml`, `.gitleaks-version`, `README.md`, `docker-compose.yml` |
| Segredos e CI | `.githooks/pre-commit`, `.github/workflows/ci.yml`, `security.yml`, `load-test.yml`, `.github/dependabot.yml`, `security/exceptions.yml` |
| Infra e scripts | `infra/docker-compose.test.yml`, `infra/postgres/init/01-create-app-user.sh`, `scripts/setup.sh`, `scripts/verify.sh`, `scripts/load-test.sh`, `scripts/load/load-test.mjs` |
| Backend | `apps/api/` — Maven Wrapper 3.3.4 (Maven 3.9.11), Spring Boot 3.5.16, Java 21; módulos `shared`, `identity`, `marketdata`, `scanner`, `audit` (pacotes `api`, `domain`, `infra`, `web`); 4 changelogs Liquibase; `openapi/openapi-guest.yaml`; `Dockerfile` |
| Frontend | `apps/web/` — Next.js 15.5.28, React 19.2.9, TypeScript 5.9.3 `strict`; `middleware.ts` (CSP com nonce); telas `/entrar` (S1) e `/scanner` (S3); cliente da API e tipos gerados em `src/lib/api/schema.d.ts`; specs Playwright em `e2e/u1-walking-skeleton/`; `Dockerfile` |
| Documentação | `docs/adr/ADR-0001-cors-credenciais-na-verificacao.md`; Javadoc em `AuditEventPublisher`, `AuthContext` e `MarketSourceAdapter` |

## Decisões de implementação

- **Recusa da carteira de administrador:** grava só `LOGIN_REJECTED` (motivo `ADMIN_WALLET`), seguindo BR1.6 (exatamente um evento por recusa); `ApiException.alreadyAudited()` impede o tratador de erros de gravar também `REQUEST_FORBIDDEN`. Os demais 403 (por exemplo `REAL_MODE_UNAVAILABLE`) gravam `REQUEST_FORBIDDEN`, como pede BR1.7.
- **Verificação do login:** o nonce é consumido por `UPDATE` condicional antes da conferência de mensagem e assinatura; a recusa é um resultado, não uma exceção, para gravar estado e evento antes da resposta.
- **Idempotência:** `IdempotencyService` reivindica a chave com `INSERT ... ON CONFLICT` na transação de quem chama, recusa guardar respostas com `accessToken` ou `refreshToken`, e `AUTH_VERIFY` não guarda resposta.
- **Ordem da lista:** idade desnormalizada em `token.sort_group` e `sort_key` para paginar por cursor sobre o índice composto, sem `OFFSET`; o cursor leva corte, grupo, chave, mint, chain e total.
- **Fonte de mercado:** o coletor registra a fonte em `data_source` antes das observações, na mesma transação (defeito encontrado pelos testes de integração e corrigido).
- **Renovação sem cookie:** a API não envia `Set-Cookie` de limpeza quando nenhum cookie foi enviado, para não apagar o cookie de um login concorrente (corrida encontrada pelo smoke e corrigida).
- **OpenAPI:** gerado numa fatia `@WebMvcTest` sem banco, para a comparação com o arquivo versionado rodar sem Docker.
- **Origem da API no frontend:** `API_ORIGIN` lida no servidor e passada como propriedade, nunca em `NEXT_PUBLIC_*`.
- **Logs:** erros inesperados registram só o tipo da exceção; filtro de mascaramento Base58 no formatador JSON.

## Cobertura e testes

| Comando | Resultado |
|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` | Unitários 226/226 e integração 67/67 verdes; JaCoCo mesclado **95,06%** de linhas (piso 80%) |
| `pnpm --dir apps/web exec jest --coverage src/features/auth src/features/scanner src/lib src/middleware.test.ts` | 80/80 verdes; **98,31%** de linhas (piso 80%) |
| `bash scripts/verify.sh` | Saída 0: três serviços saudáveis, primeira coleta em até 60 s, Playwright 3/3 (`smoke`, `keyboard`, `screen-time`), endereço completo ausente dos logs, ambiente derrubado e chave apagada |
| `spotless:check`, `lint`, `format:check`, `typecheck`, `check:api` | Limpos |
| Gitleaks `dir` na árvore de trabalho | Nenhum segredo encontrado |

A idempotência (Passo 7) seguiu testes antes do código: Red com `Tests run: 13, Failures: 1, Errors: 12` em `IdempotencyServiceTest` e `Tests run: 5, Failures: 4` em `IdempotencyKeyFilterTest`; Green e Refactor com 18/18.

## Testes escritos e não executados nesta etapa

- **Workflows do GitHub** (`ci.yml`, `security.yml` com CodeQL e OWASP Dependency-Check, `load-test.yml`): o repositório ainda não tem commit nem remoto; rodam no primeiro pedido de merge.
- **`scripts/load-test.sh`:** não executado; usa uma única conta semeada e mede a verificação com poucas amostras, sem as contas dos 20 usuários simulados.

## Desvios do plano

- Acesso a dados com `spring-boot-starter-jdbc` e `JdbcClient`, em vez de data-jdbc ou JPA.
- Modo `dev` do Compose é `docker compose up postgres` (seleção de serviço), não um perfil, para o `docker compose up` padrão continuar subindo os três serviços.
- O teste de `tenantId` ignorado ficou nos Passos 16 e 18, porque a rota da lista só existe a partir do Passo 16.
- Dependências do frontend auditadas com `pnpm audit --audit-level high`; o OWASP Dependency-Check cobre só o Maven.
- Regra `dependencyConvergence` retirada do enforcer (não estava no plano e falhava em dependências transitivas do Liquibase).
- O teste de tempo de tela (NFR3.2) mede um usuário; o p95 com 20 usuários fica para o workflow de carga.
- `.gitleaks.toml` ignora `.next/`, `node_modules/`, `target/` e `.tools/`, todos fora do git.

## Pontos em aberto

- O ambiente local tem JDK 25; o build compila com `release 21`.
- `next build` no Windows falha na cópia `standalone` ao criar links (precisa do Modo de Desenvolvedor); a imagem Linux funcionou no `scripts/verify.sh`.
- Com várias fontes de mercado (U4), a consulta da lista precisará de uma regra de escolha entre observações de fontes diferentes.
- O bloco C1 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e a regra comum de CORS ainda precisam citar o ADR-0001 (credenciais na verificação).
- Renomear `master` para `main` antes do primeiro commit, conforme `team.md`.
