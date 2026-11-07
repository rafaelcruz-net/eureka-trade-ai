# Resumo do código — U2 Trilha de auditoria

Código gerado para a Unit `u2-audit-trail` a partir do [plano aprovado](code-generation-plan.md), com os testes descritos em [unit-test-instructions.md](unit-test-instructions.md). Os 18 passos foram executados e marcados. Rastreabilidade em [traceability.json](traceability.json) e caminhos em `source-manifest.json`.

## Volta 1 de Build and Test (2026-11-06)

Só os Passos 19 e 20 do plano. Nenhum código de produção mudou.

**Passo 19 — plano do índice num banco próprio (falha F1).**

- `AuditEventReaderPlanIT` (novo) roda o `EXPLAIN` das quatro formas preparadas num banco só dele (`u2_plan_*`), no mesmo contêiner. O banco é criado, preparado pelo `bootstrap.sh` e migrado pelo dono pelo caminho do db-migrate. A classe não sobe contexto Spring.
- A semente é fixa: os mesmos `event_id`, gerados por `md5`. No mesmo dia ficam 20 000 eventos de uma ação dominante, 50 da ação consultada e 500 de cada uma de outras quatro ações, mais 30 000 eventos nos 30 dias anteriores. Depois roda `ANALYZE audit_event`. Isso reproduz, dentro do banco próprio, a condição que levou o planner ao índice errado.
- A asserção não mudou: com `action` aparece `audit_event_action_time_id_idx`, sem `action` aparece `audit_event_time_id_idx`, e nenhuma forma tem `Sort`.
- O teste saiu de `JdbcAuditEventReaderIT`. Os outros cinco testes dessa classe continuam no banco compartilhado.
- **Base comum:** `testsupport/OwnTestDatabase` (novo) cria um banco por prefixo e por JVM. `AbstractU9MeasurementIT` e `U7VolumeDatabase` passaram a usá-lo, com o mesmo nome de banco e o mesmo caminho de antes.
  - **Desvio do plano:** é uma classe de apoio, e não a superclasse `AbstractOwnDatabaseIT` sugerida. `AbstractU9MeasurementIT` precisa continuar estendendo `AbstractU9IT`, e o teste do plano não precisa de contexto Spring.
- **Defeito achado e corrigido (código de teste):** na primeira execução de `-Dgroups=u2`, `AuditObservabilityIT` perdeu dois testes (`no log line contains: Audit job started: ...` e `no log line contains: Audit trail query answered`).
  - Causa: migrar um banco próprio no meio da execução reinicia o Logback da JVM com `root=WARN`, por causa do `--logging.level.root=WARN` do `MigrationRunner`. Os contextos Spring que já estavam em cache param de escrever as linhas `INFO`.
  - Reprodução: `AdminAuditQueryIT`, depois `AuditEventReaderPlanIT`, depois `AuditObservabilityIT`.
  - Correção: `OwnTestDatabase` migra com `org.springframework.boot.logging.LoggingSystem=none`, e a propriedade volta ao valor anterior em seguida.
  - Regressão: `OwnTestDatabaseIT` (novo). Sem a correção, ele falha com `expected: INFO but was: WARN`.
  - Efeito: o mesmo defeito existia, latente, nas medições da U9 e no volume da U7. Agora as migrações desses bancos escrevem os logs `INFO` do Liquibase.

**Passo 20 — rastreabilidade.**

- `traceability.json` ganhou as linhas NFR2.3, NFR3.2, NFR6.8 (`OK`) e NFR10.1 (`N/A`, sem meta de disponibilidade no beta local), também em `upstream_ids`.
- Notas e caminhos extras foram para o campo `note`.
- **Desvio do plano:** cada `target` `OK` tem um único caminho. O sensor `sensor-traceability` exige um único arquivo existente e não divide o texto por `; `. Os demais caminhos ficam em `note`, como "Outros alvos".
- NFR3.1 aponta para `AuditEventReaderPlanIT`. BR2.3 e BR2.4 citam a classe nova na nota.
- `aidlc engine sensor-traceability --stage build-and-test` não lista mais nenhum item da U2. Os itens que restam são de `u8-admin-feedback` e `u9-dashboard` (`upstream_ids must be an array of non-empty strings`).
- NFR2.3 está coberto pela capacidade do relay e pela entrega única. Não há teste de carga isolado do pico de 60 eventos por segundo, o que está registrado na nota.

**Resultados da volta:**

| Comando | Resultado |
|---|---|
| `AuditEventReaderPlanIT` sozinho | BUILD SUCCESS: 2/2 |
| `-Dgroups=u2`, primeira execução | BUILD FAILURE: unitários 145/145; integração 101/103, antes de `OwnTestDatabaseIT` existir (as 2 falhas de `AuditObservabilityIT` descritas acima) |
| `-Dgroups=u2`, depois da correção | BUILD SUCCESS: unitários 145/145; integração 106/106 (com os 3 de `OwnTestDatabaseIT`) |
| `-Dgroups=u9`, primeira execução | BUILD FAILURE: unitários 16/16; `DashboardOpenLatencyIT.everyGroupOfTheDashboardOpeningAnswersWithinTwoSecondsAtP95` falhou na meta de 2 s (correção própria da U9 nesta mesma volta) |
| `-Dgroups=u9`, segunda execução (só integração) | BUILD SUCCESS: integração 10/10 |
| `spotless:check` | BUILD SUCCESS |
| `apps/api/mvnw -f apps/api/pom.xml verify`, rodada 1 | BUILD SUCCESS: unitários 890/890; integração 752/752; JaCoCo `All coverage checks have been met` |
| `apps/api/mvnw -f apps/api/pom.xml verify`, rodada 2 | BUILD SUCCESS: unitários 890/890; integração 752/752; **93,85%** de linhas (JaCoCo consolidado) |

`scripts/perf-audit.sh` não foi executado nesta volta. Ele fica com a pessoa.

## Correção após a revisão de 30/10/2026

A primeira revisão do código veio NOT-READY. O responsável do projeto pediu a correção de três achados. Cada um ganhou um teste de regressão, executado antes da correção para confirmar a falha.

- **Crítico, rota administrativa contornável (confirmado).** Com token de convidado, três grafias respondiam `200` com a trilha de todos os tenants pelo Tomcat real: `/api/v1/%61dmin/audit-events`, `/api/v1/admin;x/audit-events` e `/api/v1/admin;jsessionid=1/audit-events`. Correção em duas camadas:
  - `AdminRouteFilter` decide pelo caminho normalizado de `RequestPaths` (decodificado, sem `;`, barras juntadas, `.` e `..` resolvidos, sem diferença de maiúsculas);
  - a operação chama `AdminAccess.requireAdministrator`, e um papel diferente de `ADMIN` recebe `403 FORBIDDEN_ROLE` com um único `REQUEST_FORBIDDEN`.

  `AuthenticationFilter` passou a usar o mesmo caminho normalizado, porque `//api/v1/...` pulava a autenticação. A regra ArchUnit de `Role.ADMIN` agora aponta para `AdminAuthorization`, o componente usado pelo filtro e pela operação, e todo `Admin*Controller` precisa depender de `AdminAccess`. `AdminAuditRouteIT` cobre 17 grafias de caminho.
- **Major, recusa virando `500` sem evento (confirmado).** A rota gravada passou a ser o caminho normalizado, sem query string, com segmentos suspeitos trocados por `{redacted}` e no máximo 200 caracteres. Se o publicador ainda recusar, grava a rota fixa `UNRECORDABLE {redacted}`. A recusa sempre responde `403` e grava um único evento.
- **Menor, relógio do corte do expurgo (confirmado).** O corte usado é o menor entre o da política, pelo relógio injetável, e o do gatilho, pelo `now()` do banco. O `DELETE` repete esse limite, e o corte registrado é o efetivamente usado.

**Resultados da correção:**

| Comando | Resultado |
|---|---|
| Comando da U2 | BUILD SUCCESS: 144 unitários e 102 de integração |
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` | BUILD SUCCESS: 238 unitários e 169 de integração |
| `apps/api/mvnw -f apps/api/pom.xml verify` | BUILD SUCCESS: 371 unitários e 169 de integração; **95,61%** de linhas; módulo `audit` 96,92% |

**Efeito colateral aceito:** um segmento de caminho com `.`, como em `/foo/openapi.yaml`, aparece como `{redacted}` na rota gravada da recusa. Isso foi escolhido de propósito, para ficar do lado seguro.

**Continuam em aberto (Menores):** `scripts/verify.sh` termina com `down -v` e apaga o volume `pgdata` do projeto Compose padrão; falta a evidência de desempenho executada.

## O que a U2 entrega

- **Gravação (C9):** o publicador valida campos obrigatórios, formato de `action`, regra do tenant e recusa chaves proibidas e valores em forma de JWT, `Bearer` ou assinatura em `details` e `targetRef`. A exceção leva campo e motivo, nunca o valor. O relay grava `recorded_at` e mede a entrega do commit à gravação (`audit_delivery_seconds`).
- **Consulta (C8):** `GET /api/v1/admin/audit-events`, só para `ADMIN`, com período de até 31 dias, cursor opaco sem `OFFSET`, `AUDIT_QUERIED` na mesma transação, `500` sem dados se a gravação falhar e `Cache-Control: no-store`. OpenAPI administrativo separado em `apps/api/openapi/openapi-admin.yaml`.
- **Recusas:** cada `403` grava um `REQUEST_FORBIDDEN` pelo `AdminRouteFilter`. A agregação foi removida ([ADR-0003](../../../../../../../docs/adr/ADR-0003-sem-agregacao-de-recusas.md)).
- **Retenção e limpeza:** expurgo diário de eventos com mais de 12 meses, pelo login de retenção, em lotes de 1000 com limite de 30 minutos e `AUDIT_RETENTION_PURGED`; limpeza diária das entregas com mais de 7 dias. ShedLock garante uma execução; a recuperação ao subir roda a tarefa sem registro ou atrasada mais de 24 h; os indicadores `auditRetention` e `auditOutboxCleanup` degradam após 26 h, falha ou limite de tempo.
- **Banco:** três logins (dono, aplicação e retenção) e o papel `audit_retention`, criados por `db-bootstrap`; migrações fora da API por `db-migrate` com o login do dono ([ADR-0002](../../../../../../../docs/adr/ADR-0002-papeis-de-banco-e-migracoes-fora-da-api.md)). O gatilho recusa `UPDATE` e `TRUNCATE` para todos e só aceita `DELETE` de membro de `audit_retention` fora da janela de 12 meses.
- **Observabilidade e varredura:** métricas sem rótulos com dado pessoal; `LogScanIT` lê os logs da aplicação e do PostgreSQL, as métricas, `audit_outbox.payload` e `audit_event.details`.

## Resultados

| Comando | Resultado |
|---|---|
| `apps/api/mvnw -f apps/api/pom.xml verify` | BUILD SUCCESS: 341 unitários e 147 de integração, 0 falhas; **95,60%** de linhas (piso 80%); módulo `audit` 97,23%; exclusões sem mudança |
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` | BUILD SUCCESS: a U1 continua verde |
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u2 test-compile surefire:test failsafe:integration-test failsafe:verify` | BUILD SUCCESS: 114 unitários e 80 de integração |
| `bash scripts/verify.sh` | Saída 0, executado pelo responsável do projeto: tarefas da trilha `UP`, Playwright 4/4 (3 da U1 e `e2e/u2-audit-trail/smoke.spec.ts`), varredura de logs limpa |
| `bash -n scripts/perf-audit.sh` | Sintaxe válida, executado pelo responsável do projeto |

## Testes escritos e não executados

- **`scripts/perf-audit.sh`** (Passo 17): não executado nesta etapa. Semeia cerca de 37 milhões de eventos e mede a entrega por 1 hora; precisa de cerca de 30 GB livres e do ambiente diário desligado. O relatório em `docs/perf/` é a evidência de desempenho exigida no ponto de controle da U2 (NFR3.1, NFR3.3, NFR4.2). Como não há token de administrador antes da U8, o script mede o trabalho de banco da rota (SQL de `JdbcAuditEventReader` com `AUDIT_QUERIED` na mesma transação, pelo login de aplicação), não o p95 HTTP.
- **Workflows do GitHub** (passo `compose-config` e `AdminOpenApiContractTest` no `ci.yml`): o repositório ainda não tem commit nem remoto.

## Decisões de implementação

- **ShedLock programático** (`DefaultLockingTaskExecutor` com `usingDbTime()`), para o agendamento e a recuperação ao subir passarem pelo mesmo `AuditJobRunner`, cada execução com `correlationId` próprio.
- **Nomes dos indicadores de saúde:** beans `auditRetentionHealthIndicator` e `auditOutboxCleanupHealthIndicator`, fora do grupo de prontidão; degradado responde HTTP 200, como na U1.
- **Expurgo sem evento:** se `AUDIT_RETENTION_PURGED` não puder ser gravado, a execução fica `FAILED` e a saúde degrada.
- **Logs de falha** com a classe da exceção e o SQLState, nunca a mensagem.
- **Rollback:** as migrações da U2 só acrescentam, mas a U1 aplicava o Liquibase dentro da API com o login de aplicação, que perdeu o direito de criar objetos; o rollback para antes da U2 só funciona sem migração nova pendente (README e ADR-0002).

## Desvios do plano

- **Agregação de `REQUEST_FORBIDDEN` removida** antes do plano (seção "Desvios do desenho").
- **Smoke sem consulta de administrador:** a entrada do administrador só existe na U8; a consulta com `ADMIN` é coberta por `AdminAuditQueryIT`.
- **Passo 13:** o `Principal` é criado em `SessionService.authenticate`, não no filtro. A regra ArchUnit permite o construtor só em `SessionService` e a chamada de `authenticate` só em `AuthenticationFilter`. `Role.ADMIN` só aparece em `AdminRouteFilter`.
- **Passo 8:** o teste "o registro sobrevive a reinício" relê `audit_job_run` por um store novo numa conexão nova, sem reiniciar o contexto Spring.
- **Passos 16 e 17:** `scripts/verify.sh` e `scripts/perf-audit.sh` foram gravados pelo responsável do projeto a partir do texto preparado pelo agente, porque a proteção do AI-DLC recusou a gravação desses scripts pelo agente (falso positivo sobre a palavra "audit" em scripts que gravam arquivos).

## Defeito corrigido

- `JdbcAuditEventReaderIT.everyTenantAndTheEventsWithoutTenantAreReturned` (Passo 6) era instável: o período aleatório de 1 dia podia coincidir com o de outro teste. Cada método passou a usar um período fixo derivado do nome e limpa a própria janela pelo login de retenção antes de começar.

## Pontos em aberto

- Os documentos de NFR Design e de Infrastructure Design da U2 ainda descrevem a agregação removida e citam Semgrep; a ferramenta SAST real é o CodeQL em `.github/workflows/security.yml`. Precisam de revisão dessas etapas.
- Executar `bash scripts/perf-audit.sh` antes do ponto de controle da U2.
- Na execução anterior, um arquivo de teste foi editado por script no shell e a saída de um build foi gravada em `apps/api/target/full-verify.log` (pasta de build, fora do git), contra a regra de trabalho dos agentes.
