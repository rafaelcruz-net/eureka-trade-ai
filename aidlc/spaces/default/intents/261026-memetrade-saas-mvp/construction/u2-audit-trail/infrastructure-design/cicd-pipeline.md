# Pipeline de CI/CD — U2 Trilha de auditoria

Mudanças da Unit `u2-audit-trail` no pipeline criado pela U1. Fontes: [security-design.md](../nfr-design/security-design.md), [reliability-design.md](../nfr-design/reliability-design.md), [performance-design.md](../nfr-design/performance-design.md), [infrastructure-specification.md](infrastructure-specification.md), regras do `team.md` e do `project.md`, contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md). Decisão em [infrastructure-design-questions.md](infrastructure-design-questions.md) (Q1).

Continuam valendo todas as regras gerais da U1: GitHub Actions, ações fixadas por SHA, `permissions: contents: read`, gatilhos `pull_request` e `push` em `main`, instalação pelos lockfiles e nenhum segredo em fork.

Os workflows existentes são `ci` (jobs `backend`, `frontend`, `contract`, `smoke` e `e2e`), `security` (jobs `secrets`, `sast` com CodeQL para Java e TypeScript, e `dependencies` com o OWASP Dependency-Check) e `load-test` (manual). O portão de NFR6.8 é o CodeQL do workflow `security`: resultado de nível `error` no SARIF bloqueia, `warning` avisa.

## Mudanças no workflow `security`

- O job `sast` (CodeQL) passa a analisar o código da U2 sem mudança de configuração.
- O job `dependencies` (OWASP Dependency-Check, CVSS 7 ou mais bloqueia) cobre a dependência nova, o ShedLock, com versão fixa no `pom.xml`.
- O job `secrets` (Gitleaks) cobre as chaves novas do `.env.example`, que não têm valores.

## Mudanças no workflow `ci`

1. **backend**:
   - A base de testes de integração roda, no contêiner do Testcontainers, o mesmo script de `infra/postgres/bootstrap/` como superusuário. Em seguida aplica as migrações com o login do dono e conecta a aplicação e o expurgo com os seus logins. O contêiner sobe com os parâmetros do PostgreSQL da U2.
   - Testes novos de privilégios, do gatilho, de transição de um banco no formato da U1, da agregação de recusas, das tarefas agendadas, da recuperação ao subir e da saúde, com relógio injetável.
   - Teste de configuração: o perfil padrão da API não tem a credencial do dono e tem o Liquibase desligado.
   - Regras ArchUnit novas:
     - `Principal` criado só no filtro de sessão;
     - `Role.ADMIN` referenciado só numa lista fechada;
     - tabelas da trilha acessadas só pelo AuditTrail;
     - pool de retenção injetado só no expurgo.
   - A varredura de NFR7.3 lê também os logs do contêiner do PostgreSQL do Testcontainers, a saída de métricas e as tabelas `audit_outbox` e `audit_event`.
   - A regra de 80% do JaCoCo continua.
2. **compose-config** (passo novo no job `backend`): roda `docker compose config` com um `.env` efêmero sobre as duas composições, a base (`docker-compose.yml`) e a base com o overlay `infra/docker-compose.test.yml`, usada no smoke, no e2e e no load-test. Falha quando:
   - o serviço `api` recebe `DB_OWNER_PASSWORD` ou `POSTGRES_PASSWORD`;
   - o serviço `db-migrate` recebe `POSTGRES_PASSWORD`;
   - a `api` não depende de `db-migrate` com término bem-sucedido;
   - o healthcheck do `postgres` não usa TCP.
3. **smoke**:
   - O `.env` efêmero passa a gerar as senhas do superusuário, do dono, da aplicação e da retenção.
   - O `scripts/verify.sh` sobe `db-bootstrap` e `db-migrate` antes da `api` e espera a primeira execução bem-sucedida do expurgo e da limpeza.
   - O smoke confere:
     - a consulta da trilha com o contexto de administrador do perfil de teste;
     - o `403` auditado de um convidado;
     - a saúde da trilha sem indicador degradado.
   - Os logs do contêiner do banco entram na varredura e nos artefatos de 7 dias.
4. **Scripts que sobem o ambiente:** `scripts/verify.sh` e `scripts/load-test.sh` passam a gerar as quatro senhas efêmeras e a subir `db-bootstrap` e `db-migrate` antes da `api`, pelo `depends_on` do Compose.

## Desempenho da trilha: só local (Q1)

- O `scripts/perf-audit.sh` roda no computador local, não na CI. Descrição em [infrastructure-specification.md](infrastructure-specification.md).
- É obrigatório antes de fechar a U2 e quando uma mudança tocar a consulta, os índices, o relay ou o expurgo.
- O relatório versionado em `docs/perf/` é a evidência de NFR3.1, NFR3.3 e NFR4.2.
- A existência do relatório é condição verificada no fechamento da U2. As instruções de teste da geração de código listam o `scripts/perf-audit.sh` e o relatório como evidência obrigatória do ponto de controle da Unit. A verificação desse ponto de controle confere que existe um `docs/perf/u2-audit-trail-<data>.md` com data igual ou posterior ao último commit que tocou a consulta, os índices, o relay ou o expurgo.
- O workflow manual `load-test` da U1 continua sem mudança.

## Portões por estágio

| Estágio | Portão novo ou alterado da U2 | Bloqueia o merge |
|---|---|---|
| backend | Privilégios, gatilho, transição, agregação, tarefas, saúde, ArchUnit, varredura com logs do banco, cobertura ≥ 80% | Sim |
| backend (compose-config) | Nas duas composições: credencial do dono e do superusuário fora da `api`; ordem `db-bootstrap` → `db-migrate` → `api`; healthcheck TCP do `postgres` | Sim |
| workflow `security` | CodeQL (`sast`) e Dependency-Check (`dependencies`) cobrindo o código da U2 e o ShedLock | Sim (Médio só avisa) |
| smoke | Subida com preparo e migração separados; consulta, `403` auditado e saúde da trilha; varredura com logs do banco | Sim |
| Desempenho local | `scripts/perf-audit.sh` com código zero e relatório versionado, conferido no ponto de controle da U2 | Bloqueia o fechamento da U2 |

## Consumo estimado de minutos

O job `backend` ganha cerca de 2 minutos pelos testes novos e pelo passo de configuração. O `smoke` ganha cerca de 1 minuto pelos dois serviços de curta duração e pela espera das tarefas. Cada mudança passa de cerca de 75 para cerca de 78 minutos. Os testes de desempenho da trilha não consomem cota.

## Segredos em CI/CD

| Segredo | Onde | Exposição |
|---|---|---|
| Senhas do superusuário, do dono, da aplicação e da retenção | Geradas a cada execução nos jobs `backend` (Testcontainers), `smoke`, `e2e` e `load-test` | Efêmeras; nunca em artefato nem em log; o superusuário só no `postgres` e no `db-bootstrap` |
| `NVD_API_KEY` | Como na U1 | Sem mudança |

## Implantação, promoção e rollback

- Ainda não há ambiente: a entrega termina no merge em `main` com a CI verde.
- **Primeira subida local depois da U2:** o `db-bootstrap` transfere ao dono os objetos que hoje pertencem à aplicação, e o PostgreSQL reinicia uma vez por causa de `track_commit_timestamp`. O volume `pgdata` existente é mantido.
- **Rollback local:**
  - voltar ao commit anterior e rodar `scripts/verify.sh`;
  - as migrações da U2 só acrescentam, então a versão anterior da API funciona sobre o banco migrado;
  - a posse transferida ao dono não quebra a versão anterior, porque a aplicação mantém os privilégios de dados pelas concessões;
  - a versão anterior roda o Liquibase dentro da API com o login da aplicação, e esse login não cria objetos novos. Por isso o rollback para antes da U2 só funciona sobre um banco sem migração nova da U2 pendente. O `README` registra esse limite.
- Uma implantação só termina quando o smoke passa (`team.md`).
