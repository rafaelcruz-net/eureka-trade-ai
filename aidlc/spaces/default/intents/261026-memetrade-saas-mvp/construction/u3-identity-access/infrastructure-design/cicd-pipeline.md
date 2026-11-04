# Pipeline de CI — U3 Convites, contas e sessões

A U3 usa os workflows existentes (`.github/workflows/ci.yml`, `security.yml`, `load-test.yml`) sem criar workflow novo. Ações de terceiros continuam fixadas por SHA, com `permissions` mínimas, e os lockfiles versionados (`project.md`). Integração por squash-merge em `main`, sem merge com CI vermelha (`team.md`). Os IDs `NFRx.y` são os da U3.

## Estágios e portões

| Estágio | Job | O que a U3 acrescenta | Portão |
|---|---|---|---|
| Lint, formato e tipos | `backend` (Spotless), `frontend` (ESLint, Prettier, `tsc`) | Código do `identity` (convites, conta, termos) e das páginas `/convite/[codigo]` e `/conta`; regra de lint contra `dangerouslySetInnerHTML` nessas páginas (termos como texto simples) | Falha bloqueia o merge |
| Testes do backend | `backend` (`mvnw verify`) | Unitários (`InviteCodes`, estado derivado, validade no limite, ordem das conferências da verificação); integração com Testcontainers: emissão, lista, revogação, consulta com limite e formato, verificação com convite em todas as recusas, concorrência (mesmo convite, mesma carteira, revogação contra verificação, 50 repetições cada), falha injetada na criação da conta, idempotência sem o código, `AccountRouteIT` entre tenants, `AdminInviteRouteIT` com token de convidado, `AuthenticationFilter` (rotas públicas estreitas), registro de rotas com `tenantFromClient` e varredura dos OpenAPI por `tenantId`, `TermsCatalog` com arquivo inválido (API não sobe), migração repetida, `LogScanIT` e `MetricsLabelsIT` ampliados; ArchUnit (`IdentityAdministration` só em rotas administrativas; semeadores só no perfil `test`) | Falha ou cobertura < 80% bloqueia |
| Testes do frontend | `frontend` (Jest com 80%) | `safeOrigin` com os casos de controle, codificação e rotas; páginas de convite e conta (estados, aceites em `sessionStorage`, botão indisponível com motivo, REAL indisponível sem controle); cabeçalhos do `middleware.ts` em `/convite/*` e `/conta`; catálogo de termos proibidos sobre os textos da U3 | Falha ou cobertura < 80% bloqueia |
| Contrato | `contract` | OpenAPI do convidado (C1, C3: `inviteCode`, `acceptedTermsVersion`, `INVITE_NOT_FOUND`, `TENANT_INACTIVE`, `429`) e do administrador (C8: rotas de convite, `Invite` com `codeSuffix`, `createdAt`, `revokedAt`, `code` só no `201`, `INVITE_NOT_REVOCABLE`) regenerados; tipos do frontend regenerados | Diferença entre gerado e versionado bloqueia |
| Smoke | `smoke` (`bash scripts/verify.sh`) | O esqueleto, a trilha e a descoberta continuam passando. Um smoke Playwright da U3 (`e2e/u3-identity-access`) é escrito: abre `/convite/<código>`, marca os dois aceites, conecta a carteira de teste gerada no próprio teste, cria a conta, abre a tela Conta e sai. Ele entra no `verify.sh` por ação do responsável do projeto (abaixo) | Falha bloqueia |
| Segurança | `security` (Gitleaks, OWASP Dependency-Check, CodeQL) | Nenhuma dependência nova | Crítico ou Alto bloqueia; Médio avisa |
| Carga | `load-test.yml` (manual) e `scripts/load-test.sh` local | Cenário da U3 abaixo | Não bloqueia o merge; é evidência do ponto de controle da U3 |

## Mudanças no script de carga (`scripts/load-test.sh` e `scripts/load/load-test.mjs`)

| Item | Hoje | Depois da U3 |
|---|---|---|
| Perfis | `test` e `loadtest` | Os mesmos; `INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE=1000` exportado como já é `LOGIN_RATE_LIMIT_PER_MINUTE`, para a consulta não virar `429` |
| Preparação | Conta de smoke da U1 | O script gera 20 códigos (um por usuário simulado) e os exporta em `SMOKE_INVITE_CODES` antes de subir o Compose; o `SmokeInviteSeeder` cria os 20 convites na partida. As 20 carteiras de teste são geradas pelo código de teste |
| Ações | challenge, verify, lista, detalhe | Mais: verificação com convite (20 simultâneas, uma vez cada), consulta do convite e tela Conta por 5 minutos |
| Limites de p95 | Por rota (U1 e U4) | Mais: verificação com convite 500 ms (NFR3.1); consulta do convite e tela Conta 300 ms (NFR3.2) |
| Relatório | `load-test-report.json` e cópia em Markdown | Com as chaves da U3; cópia em `docs/perf/u3-identity-access-<data>.md` |

Para isso, o `SmokeInviteSeeder` aceita também uma lista de códigos (`memetrade.smoke.invite-codes`, lida de `${SMOKE_INVITE_CODES:}`, separados por vírgula), só no perfil `test`; o overlay `infra/docker-compose.test.yml` passa a variável com padrão vazio, então o smoke diário não precisa dela.

## Verificações de NFR na CI

| NFR | Onde roda | Como |
|---|---|---|
| NFR4.2 a NFR4.4 concorrência | job `backend` | Testes com barreira de início, 50 repetições, conexões próprias |
| NFR4.1 tudo ou nada | job `backend` | Falha injetada na inserção do aceite, do evento e da sessão; nenhuma linha nova fica |
| NFR6.3 código fora da idempotência | job `backend` | Lê a tabela do mecanismo comum depois da emissão e da repetição |
| NFR6.5 cabeçalhos | jobs `backend` e `frontend` | `no-store` e `no-referrer` na emissão, consulta, conta e páginas web |
| NFR10.6 validade no limite | job `backend` | Relógio injetável em `expiresAt` e `expiresAt + 1 s` |
| NFR13.1 portabilidade | job `backend` (Linux) e Compose local (Windows) | Termos em UTF-8 e LF pelo `.gitattributes` |

## Implantação e rollback

- **Implantação:** só local; `docker compose up` sobe `postgres`, `db-bootstrap`, `db-migrate` (aplica a `0007`), `api` e `web`.
- **Migração aditiva (expand-contract):** a `0007` só acrescenta tabelas, índices, restrição nomeada e o tenant `Beta`; nenhuma coluna existente é removida ou alterada.
- **Rollback para a U4:** voltar as imagens da `api` e do `web` para o commit anterior à U3 funciona sobre o banco migrado: as tabelas novas ficam sem uso, e o tenant `Beta` não afeta a U1 (a U1 só usa o tenant da conta de smoke).
- **Teste do rollback** (regra da equipe):
  - automático, no job `backend`: `MigrationsIT` aplica todas as migrações até a `0007` e roda as consultas de entrada e sessão da U1 contra o esquema novo, sem erro;
  - manual, no ponto de controle da U3: subir as imagens com a etiqueta do commit anterior à U3 sobre o volume já migrado e rodar `bash scripts/verify.sh` até sair 0; o resultado vai para o resumo do código. O Liquibase da imagem anterior ignora o conjunto `0007` já aplicado.
- **Smoke de aceite:** `bash scripts/verify.sh` verde depois de cada mudança.

## Segredos na CI

A U3 não cria segredo. `SMOKE_INVITE_CODE` é um valor de teste efêmero, gerado a cada execução e descartado com o ambiente; não é segredo de produção e não vai para os segredos do GitHub.

## Ação pendente do responsável do projeto

O `scripts/verify.sh` não é editado pelo agente. Quando o smoke da U3 existir, o responsável do projeto aplica a mudança preparada na geração de código:

1. gerar `SMOKE_INVITE_CODE` (32 bytes aleatórios em base64url) e exportá-lo, ao lado de `SMOKE_BETA_WALLET_ADDRESS`;
2. passar `SMOKE_INVITE_CODE` no overlay `infra/docker-compose.test.yml` (este arquivo é alterado pela geração de código);
3. acrescentar `e2e/u3-identity-access` à linha do Playwright;
4. acrescentar a procura do código do convite no log da `api`, como já é feito com o endereço da carteira de smoke.
