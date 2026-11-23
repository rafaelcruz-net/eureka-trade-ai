# Requisitos de observabilidade — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

A U2 passa a medir quatro coisas, sem dado pessoal, e acrescenta um alarme: qualquer divergência do P&L REAL com o ledger em 240 s, porque ela indica dinheiro calculado errado [Q5].

## Convenção de nomes

Mesma convenção da U1: Micrometer, exportado em `/actuator/prometheus` (porta de gestão 8081 na API e 8082 no worker). O nome no Micrometer leva o prefixo do módulo dono (como `pnl.mode_block.unavailable` da U1). O exportador do Prometheus troca o ponto por `_`, acrescenta `_total` aos contadores e `_seconds` aos temporizadores. As etiquetas levam só valores de enum, com poucas séries.

Os nomes de métrica do Functional Design e do contrato seguem esta convenção na Code Generation: a divergência é medida no `pnl` e o conflito de slot no `ledger`, dono de `real_entry_slot` (C14). Os nomes valem como estão na tabela de métricas. Para ninguém criar as duas métricas, a Code Generation da U2 e a da U4 usam este mapeamento e alinham os textos antigos no mesmo commit da métrica (revisão R-04):

| Nome antigo | Onde aparece | Nome novo (Prometheus) | Nome no Micrometer |
|---|---|---|---|
| `real_pnl_reconciliation_mismatch_total` | `functional-spec.md` F4; `rules.md` BR8.7 | `pnl_real_reconciliation_mismatches_total` | `pnl.real.reconciliation.mismatches` |
| `real_slot_conflicts_total` | `functional-spec.md` F7; `rules.md` BR1.4; `contract-summary.md` C13 | `ledger_real_slot_conflicts_total` | `ledger.real.slot_conflicts` |

O `rules.md`, o `functional-spec.md` e o `contract-summary.md` não são editados neste estágio.

## Métricas novas

| ID | Requisito | Nome no Micrometer | Nome exportado (Prometheus) | Etiquetas e valores | Onde é medida |
|---|---|---|---|---|---|
| NFR2.11 | Contar as divergências do P&L REAL com o ledger, por verificação que falhou. | `pnl.real.reconciliation.mismatches` | `pnl_real_reconciliation_mismatches_total` | `check` ∈ {`REALIZED`, `FEES`, `UNREALIZED`, `COST_CONSERVATION`, `QUANTITY_CONSERVATION`, `EVENT_SUMS`, `UNOWNED_WITHOUT_REASON`, `UNKNOWN_BOT`} (8 séries no máximo) | no `pnl`, em F4: +1 em cada série que falhou numa leitura com `MISMATCH`, depois da gravação do evento de auditoria. As 8 séries são registradas com 0 na subida do processo, para que o primeiro incremento apareça no `increase()` do alarme. |
| NFR2.12 | Contar as linhas `SLOT_MISSING` lidas. | `ledger.real.slot_missing` | `ledger_real_slot_missing_total` | nenhuma (1 série) | no `ledger`, em `streamInNetworkOrder`: +1 por linha entregue com `slotStatus = SLOT_MISSING`. |
| NFR2.13 | Contar os conflitos de slot. | `ledger.real.slot_conflicts` | `ledger_real_slot_conflicts_total` | `writer` ∈ {`ORDER_CONFIRMATION`, `CONDITIONAL_FILL`, `WALLET_CONFIRMATION`} (3 séries no máximo) | no `ledger`, em `recordSlot`: +1 por `CONFLICT_REFUSED`, na mesma transação do evento `REAL_SLOT_CONFLICT`. |
| NFR3.16 | Contar as falhas de gravação do evento de divergência (NFR3.15). | `pnl.real.reconciliation.audit_failures` | `pnl_real_reconciliation_audit_failures_total` | nenhuma (1 série) | no `pnl`, em F4: +1 por gravação do evento que falhou ou passou de 200 ms. Fica só no Prometheus; o alarme de NFR2.14 já dispara pela divergência. |
| NFR1.23 | Medir o tempo e as falhas da leitura de atribuição, por caminho. | `pnl.real.attribution.read` (temporizador) | `pnl_real_attribution_read_seconds` (`_count`, `_sum`, `_bucket`, `_max`) | `path` ∈ {`GATE`, `CYCLE`, `PNL`}; `outcome` ∈ {`OK`, `TIMEOUT`, `ERROR`, `UNKNOWN_BOT`} (12 combinações). Histograma com os limites fixos 0,1; 0,25; 0,5; 0,8; 1 e 2 s, que contêm os orçamentos de 250 e 500 ms e o prazo de 800 ms do bloco | no `pnl.application`, em volta de cada leitura de atribuição (F6.2): na API para `PNL`, no worker para `GATE` e `CYCLE`. As falhas por caminho são `_count` com `outcome` diferente de `OK`. |

Critérios mensuráveis das cinco métricas (alinhamento A5):

- 0 etiquetas com tenant, pessoa, bot, carteira, endereço, assinatura, mint ou `correlationId`; no máximo 133 séries novas por processo: 12 combinações do temporizador vezes 10 séries cada (7 faixas do histograma com a `+Inf`, `_count`, `_sum` e `_max`), mais 8 da divergência, 1 de falha da auditoria, 1 de `SLOT_MISSING` e 3 de conflito;
- teste de unidade de cada uma: depois de uma divergência forçada em `FEES`, `pnl_real_reconciliation_mismatches_total{check="FEES"}` vale 1 e as outras 7 valem 0; depois de 3 linhas `SLOT_MISSING` lidas, `ledger_real_slot_missing_total` vale 3; depois de um conflito na confirmação da ordem, `ledger_real_slot_conflicts_total{writer="ORDER_CONFIRMATION"}` vale 1; uma leitura com tempo esgotado no caminho `PNL` soma 1 a `_count{path="PNL",outcome="TIMEOUT"}`;
- teste de integração confere que as cinco aparecem em `/actuator/prometheus` com os nomes exportados acima.

## Logs

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.12 | Os registros de log da U2 trazem o `correlationId`, o motivo em enum e a categoria do erro, em inglês, no nível adequado: divergência e `UNKNOWN_BOT` em `ERROR` (é defeito, BR8.7); conflito de slot em `WARN`, com a assinatura abreviada e os dois slots; falha ou tempo esgotado da atribuição em `WARN`, com o caminho e a causa técnica; bloco `UNAVAILABLE` em `WARN`, como na U1. | Teste de captura de log: cada um dos 4 eventos gera 1 registro com o `correlationId`; 0 registros com endereço de carteira, assinatura completa, token, cabeçalho `Authorization`, nonce ou assinatura de login (NFR3.7); 0 `catch` vazios no código da U2. |
| NFR3.13 | O `correlationId` do bloco `UNAVAILABLE`, do evento de auditoria e do log de uma leitura é o mesmo. | Teste de integração compara os três: igualdade em 100% dos casos testados. Na confirmação, o evento `REAL_SLOT_CONFLICT` leva o `correlationId` da confirmação. |

## Rastreamento distribuído

| ID | Requisito | Valor |
|---|---|---|
| NFR3.14 | A U2 não acrescenta rastreamento distribuído. | 0 bibliotecas de tracing novas; a correlação entre resposta, auditoria e log é o `correlationId` (NFR3.13). A U2 não chama outro processo (NFR3.10). |

## Alarme e painéis

| ID | Requisito | Valor |
|---|---|---|
| NFR2.14 | Um alarme novo, crítico: qualquer divergência do P&L REAL com o ledger em 240 s, ou seja, duas leituras de 120 s do CloudWatch Agent (`project.md`). | **Prometheus:** regra `RealOpsPnlReconciliationMismatch` no arquivo que já existe `infra/observability/real-ops-alerts.yml` (grupo `real-ops`), para não exigir mudança na lista de arquivos do `promtool` em `ci.yml`, que é do responsável. Expressão `sum(increase(pnl_real_reconciliation_mismatches_total[4m])) > 0`, `severity: critical`, anotações `threshold: "0"` e `window_seconds: "240"` e o resumo que aponta para o runbook. **CloudWatch:** `pnl_real_reconciliation_mismatches_total` entra na lista de `app_metric_declarations` de `infra/terraform/modules/observability/app-metrics.tf`, no grupo sem dimensões (`[[]]`, soma das 8 séries). Alarme `aws_cloudwatch_metric_alarm.real_ops_pnl_reconciliation_mismatch` em `real-ops.tf`: `alarm_name = "${var.name}-real-ops-pnl-reconciliation-mismatch"`, namespace `Eureka/App`, estatística `Sum`, `period = 240`, `evaluation_periods = 1`, `threshold = 0`, `GreaterThanThreshold`, `treat_missing_data = "notBreaching"` (sem divergência, o contador não muda), ação no tópico SNS único do piloto. O nome entra em `alarm_resource_names`. **Testes:** `promtool check rules` do job `backend` passa com uma regra a mais; o `RealOpsAlarmParityTest` compara limite e janela entre a regra e o alarme, e a sua contagem de regras do grupo muda de `assertThat(rules).hasSize(13)` para `hasSize(14)`; o nome `real_ops_pnl_reconciliation_mismatch` entra em `alarm_resource_names`; o teste offline do Terraform (`observability_metrics.tftest.hcl`) confere que o alarme lê uma métrica da lista e que a janela de 240 s é múltipla de 240 s. **Comportamento do alarme:** o contador sobe em toda leitura com divergência (o limite de NFR3.15 vale só para o evento de auditoria), então o alarme fica em `ALARM` enquanto a divergência persistir e alguém abrir a tela; quando ninguém lê o P&L por 240 s, o período fica sem incremento e ele volta a `OK`, e volta a `ALARM` na próxima leitura. Essa oscilação é aceita: cada volta a `ALARM` é a mesma divergência ainda aberta, o que é verdade e merece o aviso, e o fundador fecha o assunto pelo runbook, corrigindo o defeito. Um sinal de estado contínuo (um medidor de divergência aberta por pessoa) exigiria guardar estado entre leituras, o que o ADR-004 evita. **Runbook:** seção nova "Divergência do P&L REAL" em `docs/runbooks/real-operations.md`: ler o evento `REAL_PNL_RECONCILIATION_MISMATCH` pelo `correlationId`, conferir as verificações e as diferenças em lamports, abrir o defeito com teste de regressão; o saque e as ordens continuam (a divergência afeta só a leitura do P&L). **Painéis:** 0 painéis novos. |
| NFR2.15 | Nenhum outro alarme novo entra na U2. | `ledger_real_slot_missing_total`, `ledger_real_slot_conflicts_total` e `pnl_real_attribution_read_seconds` ficam só no Prometheus e no relatório de carga. O histograma da atribuição não vai para o CloudWatch, porque o agente descarta histogramas (`app-metrics.tf`); um alarme de p95 da atribuição, se um dia for preciso, segue o caminho do GAP OBS-G1 (resumo com quantil). O conflito de slot é auditado (NFR3.9) e conferido pelo fundador no runbook. |

## SLI e SLO

| ID | Requisito | Valor |
|---|---|---|
| NFR1.24 | O SLI de latência do P&L é o p95 do `http.server.requests` por `uri` (`/api/v1/pnl/by-mode` e `/api/v1/real/pnl`), que já existe com histograma; e o p95 de `pnl_real_attribution_read_seconds` por `path`. A U2 não declara SLO operacional novo. | O portão de 1.000 ms de C4 e o de 500 ms da atribuição no caminho `PNL` estão no cenário `pnl-by-mode` do workflow manual `load-test`, que fecha o Build and Test (NFR1.1, NFR1.3, NFR1.25). A repetição na devnet que o NFR1 pede é uma leitura de C4 pelo fundador depois da implantação, com 100 pedidos e o mesmo limite de 1.000 ms, registrada em `docs/perf/`. Num ambiente com usuários, a latência é observada pelo mesmo SLI. |

## Sources

- `nfr-requirements-questions.md`: Q5 e o resumo confirmado [Q5].
- `reviews/review-01.md` (R-03, R-04).
- `inception/requirements-analysis/requirements.md`: NFR1 (p95 na carga e na devnet), NFR2 (exatidão), NFR3 (sem endereço de carteira).
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR1.4, BR1.5, BR2.7, BR8.7; `functional-spec.md`: F4, F6.2, F7, F8; `entities.md`: ENT-213 a ENT-215.
- `inception/contract-design/contract-summary.md`: C12 (métrica de `SLOT_MISSING`), C13 (métrica do conflito), C14 (dono `ledger`).
- `construction/u1-mode-skeleton/nfr-requirements/observability-requirements.md`: convenção de nomes, NFR3.11 a NFR3.13 e NFR4.6 da U1.
- `aidlc/spaces/default/memory/project.md`: correção do CloudWatch Agent (leitura a cada 120 s, janelas de 240 s e de 180 s); Forbidden (log de segredos).
- Código lido: `infra/observability/real-ops-alerts.yml` (forma das regras e das anotações), `infra/terraform/modules/observability/app-metrics.tf` (lista do CloudWatch, `alarm_resource_names`, regras das janelas), `real-ops.tf` (forma do alarme), `.github/workflows/ci.yml` (lista fixa de arquivos do `promtool`), `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java`, `docs/runbooks/real-operations.md`, nomes de métricas do código (`bots.cycle.errors`, `real_orders`, `real_release_blocks`).

## Assumptions & Open Questions

1. [assumption] O alarme fica no grupo `real-ops` (arquivo e `.tf` que já existem) para não mudar o `ci.yml`, que é do responsável e lista os arquivos do `promtool` um a um. Se o responsável preferir um arquivo próprio do P&L, ele acrescenta o nome à lista do `ci.yml` e a regra muda de arquivo, sem mudar a expressão.
2. O contador de divergência sobe uma vez por verificação que falhou em cada leitura; o evento de auditoria é limitado a 1 por pessoa, por divergência e por hora (NFR3.15), e diz quantas pessoas foram afetadas. A oscilação do alarme está descrita e aceita em NFR2.14.
3. [assumption] Os limites do histograma da atribuição (0,1 a 2 s) são deste estágio; eles contêm os dois orçamentos e o p95 de 1 s de C4. O NFR Design pode trocá-los mantendo 0,25, 0,5 e 0,8.
4. **Divergência com o Functional Design:** F4, F7, BR1.4, BR8.7 e C13 citam os nomes antigos; o mapeamento para os nomes novos está na seção "Convenção de nomes" e vale para a Code Generation da U2 e da U4.
