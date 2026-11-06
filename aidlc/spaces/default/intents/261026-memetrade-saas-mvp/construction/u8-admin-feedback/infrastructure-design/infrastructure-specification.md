# Especificação de infraestrutura — U8 Administração e feedback

Base: [NFR Design](../nfr-design/), [respostas](infrastructure-design-questions.md) Q1 a Q4 e a infraestrutura local atual. Não há nuvem, região nem orçamento (C-10, C-12). O único ambiente é o Docker Compose local no Windows e na CI em Linux.

## Implantação

| Facet | Escolha | Motivo |
|---|---|---|
| Modelo de computação | Contêineres no Docker Compose. Serviço novo `admin`, com o app do administrador, e os módulos `administration` e `feedback` dentro do contêiner `api` existente | Monólito modular; o ADR-009 separa só o app web |
| Imagem `admin` | `memetrade-admin:local`, Dockerfile em `apps/admin` igual ao do `web`: build em etapas com Node LTS fixado e `corepack pnpm@10.18.3`, instalação do lockfile, saída `standalone`, usuário sem privilégio e `NEXT_TELEMETRY_DISABLED=1` | Mesmo padrão do `web` (Q1) |
| Rede | `admin` publicado em `127.0.0.1:3001:3000`, só no host local. Mesmo site da API (`localhost`) por causa do cookie `SameSite=Strict`. O navegador chama a API em `localhost:8080` com CORS da `AdminCorsPolicy` | Q1, NFR Design (CORS) |
| Dependências | `admin` depende do `api` com `condition: service_healthy`. O `worker` não muda | Q1 |
| Healthcheck do `admin` | `GET /api/health` do próprio app (rota sem dados), como o `web`, a cada 10 s, com 3 tentativas | Q1, NFR10.6, R-07 |
| Variáveis do `admin` | `API_ORIGIN` (padrão `http://localhost:8080`), lida no servidor do app e entregue ao navegador pela página, como o `web` já faz (R-04). Nenhuma variável `NEXT_PUBLIC_*`, nenhum segredo, nenhuma credencial de banco | NFR6.4, project.md |
| Variáveis novas da `api` | `ADMIN_APP_ORIGIN` (padrão `http://localhost:3001`; o guarda recusa curinga). `ADMIN_WALLET_ADDRESSES` já existe | NFR Design (CORS) |
| Armazenamento | PostgreSQL 17 existente, migração `0011-admin-feedback.sql` só de acréscimo | NFR10.7 |
| Ambientes | Só local. Staging e produção ficam para quando existirem (team.md) | C-10 |
| IaC | Não se aplica no beta local | team.md |
| Dimensionamento | `admin`: limite de 512 MB de memória, como o `web`. `api`: pool principal sem mudança, mais 1 conexão dedicada à trava | NFR2.4, performance-design.md |

## Serviços de infraestrutura

| Serviço | Papel | Configuração | Observações |
|---|---|---|---|
| PostgreSQL | banco | Tabelas novas `kill_switch_state` (linha única OFF), `kill_switch_change` e `feedback_entry` (índices `created_at DESC, feedback_id` e `user_id, created_at`). A restrição de `user_session.role` passa a aceitar `ADMIN` | Privilégios, no mesmo padrão da U2 e da U6 (R-01), descritos abaixo |

### Privilégios no banco (R-01)

O `10-roles.sql` concede DML a todas as tabelas para o login da aplicação a cada `up`. Por isso, as restrições da U8 não dependem de `REVOKE`; elas usam gatilhos e o papel de retenção, como a U2 e a U6 já fazem.

| Tabela | Proteção | Quem pode apagar |
|---|---|---|
| `kill_switch_change` | Gatilho `BEFORE UPDATE OR DELETE` que lança exceção para qualquer papel (só de acréscimo) | Ninguém |
| `kill_switch_state` | Gatilho `BEFORE DELETE` e `BEFORE INSERT` que recusa uma segunda linha; `UPDATE` permitido | Ninguém |
| `feedback_entry` | Gatilho `BEFORE UPDATE` que recusa sempre; gatilho `BEFORE DELETE` que só permite quem é membro do papel de grupo `feedback_retention` | Login de retenção (`DB_RETENTION_USER`), membro de `feedback_retention` |

- **Papel de retenção.** O papel de grupo `feedback_retention` é criado no `10-roles.sql`, como o `risk_retention` da U6. Ele tem `SELECT` e `DELETE` só em `feedback_entry`, e só o login de retenção é membro dele. Essa mudança no `10-roles.sql` faz parte do código da U8.
- **Expurgo.** Usa um `FeedbackRetentionDataSource` próprio com o login de retenção, como o `RiskRetentionDataSource` da U6.
- **Teste.** Um teste com o login da aplicação confere que `UPDATE` e `DELETE` falham nas três situações.
| Trava consultiva | trava | `pg_try_advisory_lock` com chave fixa da U8, num `DataSource` Hikari dedicado (`maximumPoolSize=1`, `minimumIdle=1`) | Total de conexões: 42 de 100 |
| ShedLock | trava de agendamento | Registros `adminKillSwitchReconcile` (a cada 30 s e ao subir o processo) e `feedbackPurge` (03:00 UTC) | Só no processo de API (`@ProcessRole(API)`). O `SCHEDULER_POOL_SIZE` da API passa de 14 para 16. A faixa do guarda da API passa de 14–32 para 16–32 (R-05) |

**Conta de conexões (R-05).** A conta vai de 41 para 42 de 100: entra a conexão dedicada à trava. O `ComposeFilesTest` e o teste de configuração da U5 passam a conferir 16 threads e a nova conta.

## Infraestrutura compartilhada

| Recurso compartilhado | Unit dona | Units consumidoras | Fronteira de acesso |
|---|---|---|---|
| `BotAdministration` (C16) | u5-bots-decision | u8 | Interface Java pública; a U8 não lê tabelas de bots |
| `IdentityAdministration` (C17) | u3-identity-access | u8 | Interface Java pública |
| `RejectionCounts.countRejectedSince` | u6-risk-control | u8 | Interface pública nova na U6, que devolve só o número |
| Estado das fontes | u4-discovery | u8 | Interface de dados de mercado existente |
| Atraso da trilha | u2-audit-trail | u8 | Leitura existente usada pelo estado do sistema |
| Filtros de autenticação, papel, CORS e idempotência | u1/u3 (identidade) | u8 | A U8 acrescenta as rotas públicas da entrada do administrador e a `AdminCorsPolicy`, sem mudar a política do convidado |
| `docker-compose.yml` e overlay de teste | u1 | u8 | Acréscimo do serviço `admin` e de `ADMIN_WALLET_ADDRESSES` no overlay, por execução do `verify.sh` |

## Rollback (Q4)

1. Checkout do commit anterior.
2. `docker compose up -d --build --remove-orphans`, que remove o contêiner `admin` órfão.
3. `bash scripts/verify.sh`, que precisa sair com código 0.

As tabelas da `0011` ficam no banco sem uso, e a versão anterior roda sobre elas.
- **Sessões ADMIN já gravadas (R-07).** A versão anterior não reconhece o papel ADMIN. Ela não emite token ADMIN nem tem rota que o aceite, então essas sessões ficam inertes até vencer (no máximo 8 h). A restrição ampliada de `user_session.role` continua, sem efeito.
- **Teste.** O teste de que a versão anterior sobe sobre o banco migrado roda no job `backend` (`MigrationsBackwardCompatIT`): ele aplica a `0011` e sobe o contexto com as entidades da versão anterior.

**Risco aceito:** se o kill switch estiver ligado no momento do rollback, a pausa continua valendo na U5 (`SystemPauseState`). Sem a U8, ninguém consegue desligá-lo pela tela. Os bots continuam pausados até a U8 voltar e o administrador desligar o kill switch. É o lado seguro: nenhuma operação nova acontece.
