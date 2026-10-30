# ADR-0002 — Papéis de banco separados e migrações fora da API

- **Status:** aceito (geração de código da U2, 2026-10-30)
- **Requisitos:** NFR6.2, NFR6.3, NFR6.4, NFR7.1 e as regras BR1.3, BR3.1 e BR3.2 da U2 ([security-design.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/nfr-design/security-design.md), [infrastructure-specification.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/infrastructure-design/infrastructure-specification.md))
- **Contrato afetado:** C9 — publicação de eventos de auditoria (tabela de saída e trilha)

## Contexto

Na U1, a API era dona do esquema e aplicava as migrações do Liquibase ao subir. O login de aplicação podia, então, criar, alterar e apagar qualquer tabela, inclusive a trilha de auditoria. O gatilho append-only da U1 recusava `UPDATE` e `DELETE`, mas o dono da tabela podia desligá-lo. A U2 exige três coisas:

- a trilha só aceita exclusão pelo expurgo de retenção, e só de eventos com mais de 12 meses;
- nem a API nem o dono do esquema podem alterar ou truncar eventos;
- a API em execução não tem credencial capaz de mudar o esquema.

## Decisão

1. **Três logins e um papel fixo**, criados pelo serviço `db-bootstrap` (`infra/postgres/bootstrap/10-roles.sql`, idempotente, rodado como superusuário do contêiner):
   - `DB_OWNER_USER`: dono do esquema; usado só pelo `db-migrate`.
   - `DB_USER`: login de aplicação, só dados (`SELECT`, `INSERT`, `UPDATE`, `DELETE` pelos privilégios padrão do dono), sem `CREATE` no esquema.
   - `DB_RETENTION_USER`: membro do papel `audit_retention` (`NOLOGIN`), que só pode ler e apagar `audit_event`.
2. **Migrações fora da API.** O serviço `db-migrate` usa a mesma imagem da API com o perfil `migrate` (`com.memetrade.ops.MigrationRunner`): aplica o Liquibase com o login do dono, sem servidor web e sem agendadores, e termina. No perfil padrão, `spring.liquibase.enabled=false` e não existe nenhuma credencial do dono. A `api` só sobe depois de o `db-migrate` terminar com sucesso.
3. **Gatilho com três ramos** (migração `0005-audit-trail`, sem `SECURITY DEFINER`):
   - `UPDATE` e `TRUNCATE` são recusados sempre, inclusive para o dono;
   - `DELETE` só é aceito de um membro de `audit_retention`, e só para eventos anteriores ao início do dia UTC menos 12 meses.
4. **Pool de retenção próprio** na API: um segundo pool Hikari (`maximumPoolSize=1`, `minimumIdle=0`), entregue só ao expurgo (`AuditRetentionPurge`). Uma regra ArchUnit falha o build quando outra classe depende dele.

## Consequências

- Um defeito ou uma invasão da API não apaga, não altera e não trunca a trilha, e não muda o esquema.
- O modo de desenvolvimento passa a ser `docker compose up postgres db-bootstrap db-migrate`, com a API fora de contêiner. Uma migração nova exige rodar `docker compose up db-migrate` de novo.
- **Limite do rollback:** a U2 só acrescenta (logins, privilégios, índices, coluna `recorded_at` anulável, tabelas `audit_job_run` e `shedlock`, nova função do gatilho). A posse transferida ao dono não quebra a versão da U1, porque a aplicação mantém os privilégios de dados pelas concessões. Por isso, voltar a API para a versão da U1 funciona sobre o banco já migrado, sem desfazer nada. O limite é este: a versão da U1 roda o Liquibase dentro da API com o login de aplicação, que não cria objetos. O rollback para antes da U2 só funciona sobre um banco sem migração nova pendente. Uma migração escrita depois da U2 só pode ser aplicada pelo `db-migrate`.
- São quatro senhas no `.env` (superusuário, dono, aplicação e retenção). A CI e os scripts as geram a cada execução.
- Antes de um ambiente compartilhado (G1), as senhas vão para um gerenciador de segredos com rotação (NFR6.5).

## Alternativas rejeitadas

| Alternativa | Por que foi rejeitada |
|---|---|
| A API continua aplicando as migrações com um login de dono | A credencial que muda o esquema e desliga o gatilho ficaria no processo exposto à rede. |
| Expurgo pelo login de aplicação, com `DELETE` liberado | Qualquer defeito da API poderia apagar a trilha; o gatilho não distinguiria o expurgo de um erro. |
| Função `SECURITY DEFINER` para o expurgo | Mais difícil de auditar; o gatilho teria de confiar no dono da função. O papel fixo deixa `current_user` visível no próprio gatilho. |
| Particionamento por mês com `DROP PARTITION` | Exige DDL frequente pelo dono e muda a forma da tabela; reavaliado na decisão de hospedagem (NFR2.1). |
