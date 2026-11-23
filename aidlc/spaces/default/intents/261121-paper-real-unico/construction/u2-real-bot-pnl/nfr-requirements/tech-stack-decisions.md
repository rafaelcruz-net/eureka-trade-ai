# Decisões de pilha — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

A pilha não muda [Q6]. A U2 usa só módulos e bibliotecas que já existem, nas versões fixadas no repositório. Nenhuma dependência nova entra no `pom.xml` da API nem no `package.json` do `apps/web`. A única mudança de dados é a migração `0023`.

As linhas deste arquivo são **linhas de referência**: registram decisões de pilha e alternativas rejeitadas e levam um ID herdado só para mostrar a que NFR se ligam. Elas **não são alvos de cobertura** no `traceability.json`. Cada NFR é coberto pelos requisitos verificáveis dos outros cinco arquivos.

## Decisões

| ID | Camada | Tecnologia e versão | Decisão | Justificativa |
|---|---|---|---|---|
| NFR8.5 | Linguagem e framework do backend | Java 21 e Spring Boot 3.5.16 (Web MVC, JDBC com `JdbcClient`, actuator) | Mantém. A leitura em fluxo fica em `ledger.infra` (C12) e `realexecution` (C13), o cálculo em `pnl.application` e a porta pura `RealCostMethod` em `ledger.api` (C11). | Restrição "mesma pilha" do intent; o monólito modular já tem os módulos donos. Sem ORM, como hoje. |
| NFR8.6 | Banco e migrações | PostgreSQL 17 e Liquibase (aplicado só pelo processo `db-migrate`) | Mantém, com a migração `db/changelog/changes/0023-real-entry-slot.sql`, incluída em `db.changelog-master.yaml`, com os changesets da tabela, dos gatilhos (função `ledger_reject_change` das `0008` e `0016`), das permissões e do índice (NFR1.18). O índice é criado com `DROP INDEX CONCURRENTLY IF EXISTS` e `CREATE INDEX CONCURRENTLY`, num changeset com `runInTransaction:false` (alinhamento A15). A fase 1 acrescentou também a `0024-real-risk-day-deferred.sql` (F-4) e a `0025-real-position-epoch.sql` (Etapa 14b), as duas aditivas e só de inserção. | C14 e Q6; mesma proteção append-only do ledger. |
| NFR8.7 | Leitura em fluxo | `JdbcClient` com `fetchSize` entre 1 e 1.000, numa transação só de leitura, entregando a um `Consumer` | Mantém a biblioteca; acrescenta o padrão de fluxo às duas leituras novas (NFR1.15). | Sem `fetchSize` em transação, o driver do PostgreSQL traz o resultado inteiro para a memória. |
| NFR8.8 | Métricas | Micrometer com o exportador do Prometheus, em `/actuator/prometheus`; CloudWatch Agent (sidecar) com a lista de `app-metrics.tf` | Mantém; acrescenta 3 contadores e 1 temporizador (NFR2.11 a NFR2.13, NFR1.23) e 1 métrica na lista do CloudWatch (NFR2.14). | Base dos alarmes que já existem; nenhum serviço AWS novo (NFR8.1). |
| NFR8.9 | Alarmes | Regras do Prometheus em `infra/observability/real-ops-alerts.yml` e alarmes do CloudWatch em `infra/terraform/modules/observability/real-ops.tf` | Mantém; 1 regra e 1 alarme a mais (NFR2.14). | Mesmo par regra e alarme, conferido pelo `RealOpsAlarmParityTest`. |
| NFR2.16 | Tipos numéricos | `long` com operações exatas para lamports e unidades base, `BigInteger` para as frações do rateio e do custo médio, `BigDecimal` para preços | Mantém. | `project.md` (sem ponto flutuante); `team.md`, Code Style (valores do REAL em inteiros); regra ArchUnit (NFR2.5). |
| NFR7.7 | Testes do backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), ArchUnit, JaCoCo (piso de 80%) | Mantém. O teste de propriedade (NFR2.7) usa `java.util.Random` com sementes fixas, no padrão do `LedgerPropertyTest`. As regras ArchUnit de `ledger`, de `pnl` e de NEVER_SUM_MODES ganham os tipos e as fronteiras novas. | Ferramentas do `team.md`; nenhuma biblioteca nova. |
| NFR7.8 | Testes de carga | `scripts/load-test.sh` e `scripts/load/*.mjs` em Node 24, com testes `node:test`; workflow manual `load-test` | Mantém; acrescenta o cenário `pnl-by-mode` (NFR1.6) e o texto pronto do `load-test.yml` para o responsável (NFR1.9). | Mesma forma dos cenários `u7`, `u6` e `real`. |
| NFR7.9 | Frontend | Next.js 15.5.28, TypeScript `strict`, Jest 30.2.0, `openapi-typescript` 7.9.1 | Mantém. A U2 muda só dois textos do `PnlModeBlock` e regera o `schema.d.ts` (NFR7.5). | Mesma pilha; os tipos vêm sempre do contrato. |

## Alternativas rejeitadas

| ID | Alternativa | Motivo da rejeição |
|---|---|---|
| NFR8.10 | Biblioteca de testes por propriedade (por exemplo, jqwik) para NFR2.7. | Seria dependência nova; o projeto já tem o padrão de sementes fixas com `java.util.Random` (`LedgerPropertyTest`, `DecisionPropertyTest`), que dá sequências reproduzíveis e a semente na mensagem de falha. |
| NFR8.11 | P&L guardado (tabela de resultado, visão materializada ou cache) para baixar o p95 de C4. | O ADR-004 decide que nada é guardado entre leituras; uma cópia guardada poderia divergir do ledger sem ser vista. É plano B com gatilho definido: se a execução de `pnl-by-mode` (NFR1.25) passar de 1.000 ms no p95 de C4 com o índice da `0023`, mesmo com a carga reduzida pelos dados medidos, a Unit para e a escolha volta ao fundador. |
| NFR8.12 | Coluna `slot` em `real_ledger_entry` e em `real_order`, com preenchimento das linhas antigas. | Decisão do fundador de 2026-11-21 (PQ-2, PQ-4): tabela lateral só de inserção e nenhum preenchimento. A C14 proíbe mudar o gatilho de `real_ledger_entry`. |
| NFR8.13 | Buscar o slot na rede (RPC) durante a leitura das linhas `SLOT_MISSING`. | A leitura não chama a rede (BR1.4); uma chamada externa no caminho do P&L poria o p95 de C4 na mão de um provedor e traria custo de RPC. |
| NFR8.14 | Biblioteca de tracing distribuído (OpenTelemetry). | A U2 não chama outro processo; o `correlationId` correlaciona resposta, auditoria e log (NFR3.13, NFR3.14). |

## Sources

- `nfr-requirements-questions.md`: Q6 e o resumo confirmado [Q6]; Q2 (teste de propriedade) [Q2].
- `inception/requirements-analysis/requirements.md`: Restrições ("mesma pilha"), NFR2, NFR7, NFR8.
- `inception/contract-design/contract-summary.md`: C11 a C14, PQ-2, PQ-4, PQ-10.
- `inception/domain-design/decisions.md`: ADR-004 (nada guardado entre leituras).
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR1.4, BR2.8, BR10.1.
- `construction/u1-mode-skeleton/nfr-requirements/tech-stack-decisions.md` (forma e versões).
- `apps/api/pom.xml` (Spring Boot 3.5.16) e `apps/web/package.json` (Next.js 15.5.28, Jest 30.2.0, `openapi-typescript` 7.9.1).
- Código lido: `ledger/domain/LedgerPropertyTest.java`, `decision/domain/DecisionPropertyTest.java`, `db/changelog/changes/` (última: `0022-real-exits.sql`), `scripts/load/`.
- `aidlc/spaces/default/memory/team.md`: Code Style, Testing Posture (Ferramentas).

## Assumptions & Open Questions

1. [assumption] As versões listadas são as do repositório em 2026-11-22; uma atualização do Dependabot durante a U2 não muda as decisões desta tabela.
