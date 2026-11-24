# Decisões de pilha — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24.

A pilha não muda (restrição "mesma pilha" do intent) [Q4]. A U4 usa só módulos e bibliotecas que já existem, nas versões fixadas no repositório. Nenhuma dependência nova entra no `pom.xml` da API nem no `package.json` do `apps/web`. A única mudança de dados é a migração `0026` (NFR2.7), e a única mudança de IaC é a variável da flag, a métrica na lista do CloudWatch e o alarme.

As linhas deste arquivo são **linhas de referência**: registram decisões de pilha e alternativas rejeitadas e levam um ID herdado só para mostrar a que NFR se ligam. Elas **não são alvos de cobertura** no `traceability.json`. Cada NFR é coberto pelos requisitos verificáveis dos outros cinco arquivos.

## Decisões

| ID | Camada | Tecnologia e versão | Decisão | Justificativa |
|---|---|---|---|---|
| NFR8.5 | Backend | Java 21 e Spring Boot 3.5.16 (JDBC com `JdbcClient`, `TransactionTemplate` só de leitura, actuator) | Mantém. O RealBotHoldings fica no `pnl.application`, atrás da porta de C15, e reaproveita as leituras em fluxo e os algoritmos puros da U2 (C12, C13, `RealCostBasis`). O `realexecution` ganha só `openSells` (C16). O corte fica no `bots.worker` (`RealCycle`, `CycleRunner`). | Monólito modular com os módulos donos que já existem; sem ORM, como hoje. |
| NFR8.6 | Banco e migrações | PostgreSQL 17 e Liquibase (aplicado só pelo processo `db-migrate`) | Mantém, com `db/changelog/changes/0026-bot-cycle-own-quantity-outcomes.sql` em dois changesets (`NOT VALID` e `VALIDATE`), incluída em `db.changelog-master.yaml` (NFR2.7). | Expand-contract do `team.md`; o padrão de troca de regra é o da `0020`, agora sem varrer a tabela com bloqueio forte. |
| NFR8.7 | Flag | Propriedade `memetrade.real.own-quantity-sell`, ligada a `${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}` no `application.yml`; variável nos mapas `api_environment` e `worker_environment` de `infra/terraform/main.tf`, com `"false"` | Mantém o mecanismo de flags do REAL (variáveis de ambiente das definições de tarefa, versionadas no Terraform). Dois mapas porque a API e o worker são dois contêineres da mesma tarefa e viram em implantações diferentes (NFR2.8). | `team.md`, Flags: mudar uma flag é uma implantação auditada separada. |
| NFR8.8 | Métricas | Micrometer com o exportador do Prometheus; CloudWatch Agent (sidecar) com a lista de `app-metrics.tf` | Mantém; acrescenta 3 contadores (NFR2.10, NFR2.11, NFR2.16) e 2 nomes na lista do CloudWatch: `real_own_holdings_unavailable_total` por `reason` e `real_own_holdings_reads_total` sem dimensão, 4 métricas no total (NFR2.12, NFR8.2). | Base dos alarmes que já existem; nenhum serviço AWS novo (NFR8.1). |
| NFR8.9 | Alarmes | Regras do Prometheus em `infra/observability/real-ops-alerts.yml`; alarmes do CloudWatch em `infra/terraform/modules/observability/real-ops.tf` | Mantém; 2 regras (a razão com `for: 15m` e o bot preso da F3) e 2 alarmes (o de expressão `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)` e o `Minimum` do bot preso) a mais, conferidos pelo `RealOpsAlarmParityTest` (`hasSize(16)`, anotação `cloud_periods`) e pelo `promtool` (NFR2.12, NFR2.18, NFR7.6; alinhamentos A17 e A18). | Mesmo par regra e alarme da U2. |
| NFR2.15 | Tipos numéricos | `long` com operações exatas para unidades base e lamports, `BigInteger` nas frações do custo, `BigDecimal` na quantidade da decisão | Mantém. | `project.md` (sem ponto flutuante); `team.md`, Code Style. |
| NFR7.7 | Testes do backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL 17 e a imagem `prom/prometheus` fixada pelo digest do `ci.yml`), ArchUnit, JaCoCo (piso de 80%) | Mantém. O `promtool test rules` de T20 roda por Testcontainers dentro do `mvnw verify`, com o digest da imagem lido do passo do `promtool` no `.github/workflows/ci.yml`, que fica o único lugar do digest [fundador F1] (NFR7.6). As regras ArchUnit de `bots`, `realexecution` e `pnl` ganham as fronteiras novas (T13). | Ferramentas do `team.md`; nenhuma biblioteca nova. |
| NFR7.8 | Frontend | Next.js 15.5.28, TypeScript `strict`, Jest 30.2.0 com Testing Library, `openapi-typescript` 7.9.1 | Mantém. A lista `/bots` lê `realOwnQuantitySell` dos tipos gerados do contrato (C7) e mostra o aviso (T15 a T17). | Mesma pilha; tipos vêm sempre do contrato. |

## Alternativas rejeitadas

| ID | Alternativa | Motivo da rejeição |
|---|---|---|
| NFR8.10 | Ler a posição própria dentro da transação do ciclo, sob a trava do bot. | Seguraria a trava e a conexão do ciclo pelo tempo da leitura, e uma falha da leitura derrubaria a transação do ciclo (BR2.1, BR2.3; NFR1 "nunca trava a execução de ordens"). |
| NFR8.11 | O RealExecution calcular a quantidade do bot no consumidor da fila. | Rejeitada no ADR-006: o `realexecution` passaria a depender do `pnl` e da atribuição (BR4.3). |
| NFR8.12 | Guardar a quantidade própria por bot numa tabela ou cache. | O ADR-004 decide que nada é guardado entre leituras; uma cópia poderia divergir do ledger sem ser vista. |
| NFR8.13 | Flag no banco ou em rota administrativa, ligada sem implantação. | `team.md`, Flags: só kill switch e liberação ficam no banco; a flag de comportamento é variável versionada e auditada pela implantação. |
| NFR8.14 | Histograma da leitura exportado ao CloudWatch para alarme de p95. | O CloudWatch Agent descarta histogramas (`app-metrics.tf`); o prazo de 250 ms já é aplicado em execução e aparece como `BUDGET_EXCEEDED` no contador (NFR1.13). |
| NFR8.15 | Alarme pela soma bruta das falhas de todos os bots em 3 janelas disjuntas de 5 min (a primeira versão deste estágio). | Revisão R-01 e R-02: com a meta só no p95, a cauda normal de 63 bots dispararia o alarme em operação normal; janelas disjuntas perdem um incremento na fronteira e não toleram o jitter do ciclo. Substituído pela razão de NFR2.12 [fundador F2]. |
| NFR8.16 | Subconsulta `[15m:5m]` com `min_over_time` para "3 de 3". | O número de pontos depende de o intervalo ser fechado (Prometheus 2.x) ou aberto à esquerda (3.x), e os pontos ficam alinhados a múltiplos absolutos de 5 min. O `for: 15m` sobre janelas sobrepostas não depende disso. |
| NFR8.17 | Alarme por bot (etiqueta `bot` na métrica). | Etiqueta de alta cardinalidade e com id de bot, contra a regra de etiquetas só com enum (NFR3.7); custo de uma métrica do CloudWatch por bot. O fundador escolheu a razão (F2); o bot com falha persistente entre muitos aparece no log e na revisão do runbook. |

## Sources

- `nfr-requirements-questions.md`: Q1 a Q4 e o resumo confirmado [Q1] [Q2] [Q3] [Q4].
- `reviews/review-01.md` (R-01, R-02, R-05) e as decisões do fundador de 2026-11-24 F1 e F2.
- `inception/requirements-analysis/requirements.md`: Restrições ("mesma pilha"), NFR2, NFR7, NFR8.
- `inception/domain-design/decisions.md`: ADR-004, ADR-006.
- `inception/contract-design/contract-summary.md`: C7, C12, C13, C15, C16.
- `construction/u4-own-quantity-cycle/functional-design/rules.md`: BR2.1, BR2.3, BR4.3, BR5.2.
- `construction/u2-real-bot-pnl/nfr-requirements/tech-stack-decisions.md` (forma e versões).
- `aidlc/spaces/default/memory/team.md`: Code Style, Testing Posture (Ferramentas), Deployment (Flags).
- Código lido: `apps/api/src/main/resources/application.yml`, `infra/terraform/main.tf`, `infra/terraform/modules/services/api-worker.tf`, `.github/workflows/ci.yml` (digest da imagem do Prometheus), `db/changelog/changes/` (última: `0025-real-position-epoch.sql`).

## Assumptions & Open Questions

1. [assumption] As versões listadas são as do repositório em 2026-11-24, as mesmas registradas pela U2; uma atualização do Dependabot durante a U4 não muda as decisões desta tabela.
