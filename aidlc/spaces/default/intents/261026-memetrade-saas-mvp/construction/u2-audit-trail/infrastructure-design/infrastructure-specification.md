# Especificação de infraestrutura — U2 Trilha de auditoria

Mudanças de infraestrutura da Unit `u2-audit-trail` sobre o ambiente da U1. Fontes:

- desenho não funcional da U2: [logical-components.md](../nfr-design/logical-components.md), [security-design.md](../nfr-design/security-design.md), [reliability-design.md](../nfr-design/reliability-design.md), [scalability-design.md](../nfr-design/scalability-design.md), [performance-design.md](../nfr-design/performance-design.md) e [observability-design.md](../nfr-design/observability-design.md);
- componente AuditTrail de [components.md](../../../inception/domain-design/components.md);
- fluxos de [functional-spec.md](../functional-design/functional-spec.md);
- contratos C9 e C20 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

Decisão em [infrastructure-design-questions.md](infrastructure-design-questions.md) (Q1).

O único ambiente continua sendo o local, no Windows, com Docker Compose (C-10, C-12). A U2 acrescenta dois serviços de curta duração antes da API, um script de desempenho local e ajustes no PostgreSQL.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Modelo de execução | Contêineres no Docker Compose: `postgres`, `db-bootstrap` (novo, termina), `db-migrate` (novo, termina), `api`, `web` | Migrações e preparo dos papéis fora da API (Q1 do NFR Design) |
| `db-bootstrap` | Mesma imagem do `postgres`, fixada pelo mesmo digest; roda `psql` como superusuário sobre o script idempotente `infra/postgres/bootstrap/10-roles.sql` e termina | Único responsável por papéis, posse e privilégios padrão, em volume novo ou já criado pela U1 |
| `db-migrate` | Mesma imagem da `api`, perfil `migrate`: aplica o Liquibase com o login do dono, sem servidor web, e termina | O artefato e as migrações são os mesmos da API; a credencial do dono fica só aqui |
| `api` | Mesma imagem, com `spring.liquibase.enabled=false` e só as credenciais de aplicação e de retenção | A API não consegue alterar a estrutura nem desligar o gatilho |
| Script de inicialização da U1 | `infra/postgres/init/01-create-app-user.sh` deixa de dar a posse do schema à aplicação; passa a só criar o banco com o superusuário | Toda montagem de papéis fica no `db-bootstrap` |
| Modo de desenvolvimento | O comando documentado passa a ser `docker compose up postgres db-bootstrap db-migrate`, com a API e o app fora de contêiner. A API local usa o login de aplicação e o de retenção do `.env`, com o Liquibase desligado como no contêiner. Para aplicar uma migração nova, roda-se de novo `docker compose up db-migrate`. O cabeçalho do `docker-compose.yml` e o `README` mudam para esse comando, e o teste do Compose confere que `db-migrate` e `db-bootstrap` sobem sem a `api` | Sem esse passo, o modo de desenvolvimento ficaria sem papéis e sem esquema |
| Rede e portas | Sem mudança: portas só em `127.0.0.1`; `db-bootstrap` e `db-migrate` não publicam porta | Nada novo exposto |
| Armazenamento | Volume `pgdata` da U1; a trilha cresce até 20 a 30 GB em 12 meses no teto de volume (estimativa de [scalability-design.md](../nfr-design/scalability-design.md)) | No beta local o volume real é bem menor; o tamanho entra no plano de hospedagem |
| Ambientes | Só o local | C-10 |
| IaC | Docker Compose e scripts SQL versionados | Não há nuvem a descrever |
| Dimensionamento | Sem mudança nos limites da U1. O pool de retenção (1 conexão) e o pool principal (10) cabem no padrão de 100 conexões do PostgreSQL. Pool do agendador do Spring com pelo menos 3 linhas de execução | Relay, tarefas diárias e resumos não se bloqueiam |
| Desempenho da trilha | Só local, pelo script `scripts/perf-audit.sh`; a CI não roda (Q1) | Semeadura de cerca de 37 milhões de eventos e janela de 1 hora não cabem na cota gratuita de minutos |

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL 17 | database | Mesma imagem e volume da U1. Linha de comando: `track_commit_timestamp=on`, `log_statement=none`, `log_min_duration_statement=-1`, `log_min_error_statement=panic`, `log_error_verbosity=terse`, `log_parameter_max_length=0`, `log_parameter_max_length_on_error=0`. Autovacuum próprio de `audit_event` por migração (`autovacuum_vacuum_scale_factor=0.01`, `autovacuum_analyze_scale_factor=0.005`) | `track_commit_timestamp` exige reiniciar o banco uma vez; os mesmos parâmetros valem no Testcontainers |
| `db-bootstrap` | preparo de papéis | Depende de `postgres` saudável. Recebe `POSTGRES_PASSWORD` e os nomes e senhas dos três logins. Antes do script, tenta conectar por TCP em `postgres` a cada 2 s por até 60 s; só então roda o SQL. Código de saída diferente de zero falha a subida | Idempotente; roda a cada `docker compose up`. A espera cobre o servidor temporário da primeira inicialização de um volume novo |
| `db-migrate` | migração | Depende de `db-bootstrap` com término bem-sucedido. Recebe só o login e a senha do dono | Termina depois do Liquibase |
| API Spring Boot | aplicação | Depende de `db-migrate` com término bem-sucedido. Recebe só os logins de aplicação e de retenção. Agendadores: relay (1 s), resumo de recusas (10 s), expurgo (03:00 UTC), limpeza (03:30 UTC), além dos da U1 | ShedLock com a tabela `shedlock` criada por migração |
| cache, fila, busca, CDN, DNS, balanceador | — | Não usados na U2 | — |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| `audit_outbox` | U2 | U1, U3, U5, U6, U7, U8 (publicam) | Só por `AuditEventPublisher` (C9) |
| Porta `ForbiddenRequestRecorder` | U1 (porta), U2 (agregador) | Tratamento comum de `403` | Só pela porta |
| Tabela `shedlock` | U2 (cria) | Tarefas agendadas de qualquer Unit que precisem de execução única | Só pela biblioteca, com nome de bloqueio por tarefa |
| Logins de banco e privilégios padrão | U2 (passo de preparo) | Todas as Units (as tabelas futuras nascem com privilégios para a aplicação) | Cada Unit que precisar restringir uma tabela revoga na própria migração |

## Configuração e segredos

| Variável | Serviço | Origem | Observação |
|---|---|---|---|
| `POSTGRES_PASSWORD` | `postgres`, `db-bootstrap` | `.env` local | Superusuário; nunca chega a `db-migrate`, `api` ou `web` |
| `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | `db-bootstrap`, `db-migrate` | `.env` local | Dono do esquema |
| `DB_USER`, `DB_PASSWORD` | `db-bootstrap`, `api` | `.env` local | Login da aplicação, já existente na U1 |
| `DB_RETENTION_USER`, `DB_RETENTION_PASSWORD` | `db-bootstrap`, `api` | `.env` local | Login de retenção, membro de `audit_retention` |
| `AUDIT_RETENTION_MONTHS` | `api` | Perfil da aplicação, padrão 12 | A API não sobe com valor menor que 12 |

O `.env.example` ganha as chaves novas sem valores. Na CI, todas as senhas são geradas a cada execução.

## Verificações de saúde e ordem de subida

1. `postgres` saudável. O healthcheck passa a ser `pg_isready -h 127.0.0.1 -U $POSTGRES_USER`, por TCP: o servidor temporário que roda os scripts de inicialização de um volume novo atende só pelo socket, então o contêiner só fica saudável quando o servidor definitivo aceita conexões TCP. Um teste de subida em volume novo cobre a ordem.
2. `db-bootstrap` termina com código zero.
3. `db-migrate` termina com código zero.
4. `api` responde pronta na porta de gestão. A prontidão continua `DOWN` só com banco indisponível ou migrações não aplicadas. Os indicadores `auditRetention` e `auditOutboxCleanup` degradados respondem HTTP 200 com o estado no corpo.
5. `web` sobe depois da `api` saudável.
6. `scripts/verify.sh` espera, além da primeira coleta, a primeira execução bem-sucedida do expurgo e da limpeza, que rodam logo ao subir num banco sem registro (até 60 s). O smoke falha se algum indicador da trilha continuar degradado.

## Script de desempenho local (Q1)

| Etapa | O que faz |
|---|---|
| Pré-condição | O ambiente de uso diário precisa estar desligado. O script confere que as portas 5432, 8080, 8081 e 3000 estão livres em `127.0.0.1` e para com mensagem clara se alguma estiver ocupada |
| Subida | Sobe a base com o overlay `infra/docker-compose.test.yml` e o perfil `test` num projeto Compose próprio (`memetrade-perf`), com volume separado do volume de uso diário. Gera `SMOKE_BETA_WALLET_ADDRESS` e as senhas efêmeras como o `scripts/verify.sh` |
| Semeadura | SQL `generate_series` com cerca de 37 milhões de eventos nos últimos 12 meses e pelo menos 20 ações, mais 100 mil eventos com 13 meses (antes do corte), rodado pelo login do dono só nesse projeto. Os eventos antigos são a carga do expurgo |
| Consulta (NFR3.1) | 200 consultas ou mais em três cenários: isolada, com relay entregando 60 eventos por segundo e durante um expurgo de 100 mil linhas; confere o `EXPLAIN` sem ordenação à parte; lê o p95 de `http_server_requests_seconds` da rota |
| Entrega (NFR4.2) | 1 hora de carga com a taxa de 100 mil eventos por dia e picos de 60 por segundo; lê o balde de 5 s de `audit_delivery_seconds` |
| Expurgo e limpeza (NFR3.3) | Mede a duração do expurgo das 100 mil linhas antigas e confere, por contagem antes e depois, que todas saíram e nenhuma dentro dos 12 meses foi apagada. O cenário de consulta "durante um expurgo" usa uma segunda carga de 100 mil linhas antigas semeada antes dele. A limpeza é medida com 100 mil entradas entregues há 8 dias |
| Relatório | Gravado em `docs/perf/u2-audit-trail-<data>.md` e versionado no fechamento da U2; o script devolve código diferente de zero se alguma meta falhar |
| Limpeza | Remove o projeto Compose e o volume de desempenho no fim |

O script nunca usa o volume `pgdata` de uso diário. O relatório não leva dados dos eventos, só métricas.

## Pré-requisitos antes de um ambiente compartilhado (G1)

| Item | Origem |
|---|---|
| TLS 1.2 ou superior e criptografia em repouso do banco e dos backups | NFR7.6 |
| Retenção dos backups alinhada aos 12 meses da trilha | NFR7.6 |
| Avaliação da cadeia de resumos SHA-256 da trilha | NFR6.9 |
| Segredos dos logins de banco num gerenciador de segredos, com rotação | NFR6.5 |
| Medidor de volume diário somado entre instâncias (hoje vale por instância) | [observability-design.md](../nfr-design/observability-design.md) |
| Desempenho da trilha repetido no ambiente hospedado | NFR3.1, NFR4.2 |
