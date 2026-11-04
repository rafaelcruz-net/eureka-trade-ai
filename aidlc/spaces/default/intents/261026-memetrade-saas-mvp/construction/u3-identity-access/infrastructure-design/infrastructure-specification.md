# Especificação de infraestrutura — U3 Convites, contas e sessões

Infraestrutura da Unit `u3-identity-access` no ambiente local do beta (Docker Compose no Windows, CI no GitHub Actions), a partir do desenho não funcional em [../nfr-design/](../nfr-design/) e das decisões de [infrastructure-design-questions.md](infrastructure-design-questions.md). Não há nuvem nem hospedagem nesta etapa (C-10, C-12); a U3 não cria serviço novo no Compose. Os IDs `NFRx.y` são os da U3; IDs de outra Unit vêm com a Unit.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Modelo de computação | Módulo `identity` dentro do processo `api` existente (contêiner Spring Boot da U1); páginas `/convite/[codigo]` e `/conta` no contêiner `web` existente | Monólito modular; nada novo para operar |
| Rede | Sem mudança: `api` em `127.0.0.1:8080` (aplicação) e `127.0.0.1:8081` (gestão), `web` em `127.0.0.1:3000`; nenhum acesso de saída | NFR6.4, NFR6.5 |
| Armazenamento | PostgreSQL 17 existente; tabelas novas `invite` e `terms_acceptance` | logical-components.md |
| Migrações | `0007-identity-access.sql` aplicado pelo `db-migrate` com o dono do esquema (padrão da U2); só acrescenta: `invite` (índices únicos `uq_invite_code_digest`, `uq_invite_used_by` e índice `(created_at desc, invite_id)`), `terms_acceptance`, restrição nomeada `uq_wallet_address` (já única desde a U1; o nome é conferido na migração), índice `app_user(role, status)` e a semeadura do tenant `Beta` com identificador fixo e inserção condicionada à ausência; privilégios da aplicação pelas regras padrão do `10-roles.sql` | NFR4.2, NFR4.3, NFR10.4 |
| Termos e aviso de risco | Arquivos em `apps/api/src/main/resources/terms/v1/` (`terms.txt`, `risk-notice.txt`, `version.properties` com `current=true`), empacotados na imagem; texto provisório escrito na geração de código e marcado "versão provisória, pendente de revisão jurídica" (Q2) | NFR10.3, NFR6.13 |
| Ambientes | Só local (uso diário, smoke, script de carga) e CI; staging e produção ficam para a hospedagem | `team.md` (Deployment) |
| IaC | Docker Compose versionado (`docker-compose.yml`, `infra/docker-compose.test.yml`); sem IaC de nuvem | Decisão da U2 (só local) |
| Dimensionamento | Sem mudança: pool principal de 20 conexões (U4), `mem_limit: 1g` na `api`. Acréscimo da U3: um limitador por IP com até alguns milhares de chaves (menos de 2 MB) e os termos em memória (menos de 100 KB) | scalability-design.md |

## Configuração da API

| Propriedade | Onde | Valor | Uso |
|---|---|---|---|
| `memetrade.auth.invite-lookup-rate-limit-per-minute` | `application.yml` | `10` fixo | Limite da consulta do convite (NFR6.6) |
| `memetrade.auth.invite-lookup-rate-limit-per-minute` | `application-loadtest.yml` | `${INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE:1000}` | Só com os perfis `test` e `loadtest` (`LoadtestProfileGuard`) |
| `memetrade.smoke.invite-code` | `application-test.yml` | `${SMOKE_INVITE_CODE:}` | Código do convite do smoke (Q1); vazio não cria esse convite |
| `memetrade.smoke.invite-codes` | `application-test.yml` | `${SMOKE_INVITE_CODES:}` | Códigos do script de carga, separados por vírgula; vazio não cria nada |
| `memetrade.terms.location` | `application.yml` | `classpath:terms/` | Onde o `TermsCatalog` procura as versões |

O limite da consulta não vira variável do `docker-compose.yml`: o uso diário nunca roda com o limite alto. `SMOKE_INVITE_CODE` entra só no overlay `infra/docker-compose.test.yml`, ao lado de `SMOKE_BETA_WALLET_ADDRESS`. Nenhuma variável nova é segredo de produção; o `.env.example` não muda.

## Convite do smoke (Q1)

| Item | Decisão |
|---|---|
| Semeador | `SmokeInviteSeeder`, com `@Profile("test")` como o `SmokeAccountSeeder` da U1; ao subir, se `memetrade.smoke.invite-code` tiver um código válido (43 caracteres base64url), cria no tenant `Beta` um convite `PENDING` com o resumo desse código e validade de 1 hora; se já existir um convite com o mesmo resumo, não faz nada. O log do semeador mostra só o final do código |
| Origem do código | Gerado a cada execução pelo `scripts/verify.sh` (32 bytes aleatórios em base64url), exportado como `SMOKE_INVITE_CODE`, passado ao Compose pelo overlay e ao Playwright pelo ambiente |
| Carteira da nova conta | Gerada pelo código de teste do Playwright (`e2e/support`), nunca pelo código de produção; diferente da carteira da conta semeada da U1 |
| Fora do perfil `test` | O semeador não existe; nenhum convite é criado por configuração |
| Teste de arquitetura | O teste da U1 que proíbe semeadores fora do perfil `test` passa a cobrir `SmokeInviteSeeder` |

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL 17 (`postgres`) | database | Mesmo contêiner e volume `pgdata`; tabelas, índices e restrições da `0007`; bloqueio de linha (`FOR UPDATE`) na verificação e na revogação | Volume da U3 desprezível (até 200 convites) |
| HikariCP (na `api`) | pool de conexões | Sem mudança (20 conexões); a verificação com convite usa uma conexão, e a segunda transação depois de violação de unicidade usa a mesma conexão depois do rollback | — |
| Actuator (porta 8081) | saúde e métricas | Sem indicador novo; o indicador de banco cobre a identidade | NFR9.1 |
| Cache, fila, CDN, DNS, balanceador | — | Não usados | Revistos com a hospedagem |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| PostgreSQL e volume `pgdata` | U1 | Todas | A U3 só nas tabelas do `identity`; nenhuma outra Unit lê `invite` ou `terms_acceptance` |
| Mecanismo comum de idempotência | U1 (`identity`) | Todas | Operações novas `ADMIN_INVITE_ISSUE` e `ADMIN_INVITE_REVOKE` |
| Registro de rotas e trabalhos (`tenancy/route-registry.yml`) | U1 | Todas | Campo novo `tenantFromClient`, aceito só em `ADMIN` |
| Interface `IdentityAdministration` (C17) | U3 | U8 | Só pelas rotas administrativas |
| Publicador da trilha (C9) | U2 | Todas | A U3 publica `INVITE_ISSUED`, `INVITE_REVOKED` e `ACCOUNT_CREATED` |

## Para a hospedagem futura

- Um proxy ou balanceador à frente do `web` registra o modelo `/convite/[codigo]` ou omite o caminho dessa rota (NFR6.4).
- O limitador por IP passa para um armazenamento compartilhado antes de rodar mais de uma instância da `api` (NFR2.3).
- O perfil `loadtest` e o perfil `test` nunca vão para um ambiente com convidados.
