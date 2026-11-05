# U6 — Barreira de limites (C14, C21)

A U6 é o módulo `com.memetrade.riskcontrol`: a barreira obrigatória antes de cada ordem simulada (ADR-003). Ela avalia a ordem candidata dentro da transação do ciclo da U5, devolve `Approved` ou `Rejected`, grava as recusas com os detalhes e expõe a rota de leitura C21. Tudo é `PAPER`; nada é assinado nem enviado.

## Módulos e fronteiras

| Pacote | O que tem |
|---|---|
| `riskcontrol.api` | C14: `OrderGate`, `GateResult` (`Approved`, `Rejected`), `CandidateOrder`, `BotLimitsVersionRef`, `RejectionReason`, `PauseTrigger`, `OrderSide` |
| `riskcontrol.domain` | `LimitChecks` (as verificações, função pura), `SlippageEstimate` (cópia da política `v1` da U7), `OrderGateService` (fluxos F1 e F2), `RejectionQueries` (F3) e as portas dos repositórios, da trilha e da métrica |
| `riskcontrol.infra` | Repositórios JDBC, `TransactionalOrderGate` (o único bean `OrderGate`, com propagação `MANDATORY`), publicação do `ORDER_REJECTED`, métricas, indicador de saúde, pool de retenção e o expurgo |
| `riskcontrol.web` | `RejectionController` (C21, só `GET`) |
| `riskcontrol.loadtest` | Gerador do cenário de carga `u6`; só existe com o perfil `loadtest` |

A U6 depende só de `pnl.api.PortfolioRiskView` (C13), `marketdata.api` (C11), `identity.api` (C10), `audit.api` (C9) e `shared`. A camada web também reaproveita a forma comum `MarketValue` da U4, como a U7. Nenhuma outra Unit lê as tabelas da U6. O `RiskControlArchitectureTest` confere essas fronteiras e também:

- que não há rede, assinatura nem SDK Solana;
- que não há `double` nem `float`;
- que não há `BigDecimal.ZERO` no lugar de um valor desconhecido;
- que o pool de retenção só chega ao `RiskEvaluationPurgeJob`.

## Contrato C14 para a U5

```java
GateResult result = orderGate.evaluate(candidateOrder, limitsVersionRef);
```

- **Transação.** A chamada exige a transação do ciclo aberta, em `READ COMMITTED`. Sem transação, falha e não grava nada.
- **Tenant e pessoa.** Vêm só do contexto de autenticação do ciclo, que a U5 liga com o `Principal` do dono do bot. A ordem e os limites não carregam tenant.
- **Leituras.** Uma leitura de preço e liquidez (C11) e uma da visão de risco (C13) por chamada, inclusive quando a chamada é duplicada.
- **`Approved(order)`.** É o único caminho para `PaperExecution.execute`, com a mesma versão de limites.
- **`Rejected(rejectionId, reasons, limitsVersion, pauseTriggers)`.** A U5 não executa a ordem e aplica cada gatilho de `pauseTriggers` na mesma transação.
- **Falha técnica.** Uma falha da C11, da C13, do banco ou de tempo de espera lança exceção e desfaz o ciclo inteiro. Não há nova tentativa e a falha nunca vira aprovação.

## Verificações e ordem dos motivos

A ordem abaixo é a ordem fixa da BR3.1 e a declaração do enum `RejectionReason`. O primeiro motivo da lista é o motivo principal mostrado na tela.

| # | Motivo | Regra | Lado | Medido / limite / unidade |
|---|---|---|---|---|
| 1 | `ORDER_SIZE` | valor > tamanho máximo | compra | valor / limite / USDC |
| 2 | `TOKEN_EXPOSURE` | exposição no token + valor > máximo | compra | exposição depois / limite / USDC |
| 3 | `TOTAL_EXPOSURE` | exposição total + valor > máximo | compra | total depois / limite / USDC |
| 4 | `SLIPPAGE` | slippage estimado > máximo | ambos | % estimado / limite / PERCENT |
| 5 | `MIN_LIQUIDITY` | liquidez `UNKNOWN` ou abaixo da mínima | ambos | liquidez ou `UNKNOWN` / mínima / USDC |
| 6 | `STALE_DATA` | preço `UNKNOWN` ou com mais de 60 s; liquidez conhecida com mais de 60 s | ambos | idade em s ou `UNKNOWN` / 60 / SECONDS |
| 7 | `INTERVAL` | agora < última operação + intervalo | ambos | segundos desde a última / intervalo / SECONDS |
| 8 | `DUPLICATE` | a decisão já foi avaliada | ambos | `decisionId` / `evaluationId` original / REFERENCE |
| 9 | `INSUFFICIENT_PAPER_BALANCE` | custo da compra > saldo disponível | compra | custo / saldo / USDC |
| 10 | `DAILY_LOSS` | perda do dia ≥ máximo (gatilho `DAILY_LOSS`) | ambos | perda / limite / USDC |
| 11 | `DRAWDOWN` | patrimônio vazio, ou patrimônio ≤ piso (gatilho `DRAWDOWN`), ou patrimônio − queda estimada < piso | ambos | drawdown % ou `UNKNOWN` / limite / PERCENT |

Contas da barreira (todas em `BigDecimal`, com `HALF_EVEN`):

- **Valor da ordem:** `valor = money(quantidade × preço com 12 casas)`.
- **Consumo e slippage:** `consumo% = percent(valor ÷ liquidez × 100)` e `slippage% = 0,5000 + consumo% × 1,0000`, sem o teto de 10% da U7.
- **Custo da compra:** `money(valor × (1 + slippage ÷ 100))` mais a taxa de 0,3000%.
- **Queda estimada do patrimônio:** é o custo da compra menos o líquido de saída da quantidade comprada. Na venda, a queda é 0.
- **Comparação do drawdown:** em USDC, contra `piso = money(maior patrimônio × (1 − D ÷ 100))`.

Dado ausente (`UNKNOWN`, preço velho ou patrimônio vazio) vira motivo de recusa e nunca zero. Uma verificação que depende do dado ausente não roda.

## Catálogo

O catálogo E1 a E21 está na especificação funcional da Unit e é conferido por igualdade exata em `RiskGateCatalogTest` (E1 a E19), `OrderGateIT` (de ponta a ponta com a U7 real) e `EvaluationDuplicateIT` (E8, E20 e E21). A base de todos os casos é:

- compra de 100 tokens a 1,000000000000 USDC;
- liquidez de 10.000 USDC;
- patrimônio de 1.000 USDC;
- limites versão 1.

Nessa base, a queda estimada do patrimônio é 3,600000. O `SlippageConformanceIT` confere esse valor contra a U7 real.

## Duplicidade por decisão

`order_evaluation` tem a unicidade (`tenant_id`, `mode`, `bot_id`, `decision_id`).

- **Inserção.** A avaliação é gravada com `INSERT ... ON CONFLICT DO NOTHING RETURNING`, que não aborta a transação.
- **Corrida.** Se outra transação gravou a mesma decisão, esta espera o fim dela. Depois lê a avaliação vencedora e responde `DUPLICATE`.
- **Isolamento.** Isso exige `READ COMMITTED`. Em `REPEATABLE READ`, a perdedora falha com `40001` (teste E20).
- **Rollback.** Uma decisão cuja primeira avaliação foi desfeita é avaliada de novo como a primeira (E21).

## Matriz de privilégios (migração `0009`)

| Tabela | Papel da aplicação | `risk_retention` |
|---|---|---|
| `order_evaluation` | `INSERT`, `SELECT` | `SELECT`, `DELETE` |
| `order_rejection` | `INSERT`, `SELECT` | `SELECT (evaluation_id)` |
| `rejection_reason_detail` | `INSERT`, `SELECT` | nada |
| `risk_purge_run` | `SELECT` | `INSERT`, `SELECT`, `DELETE` |

Os gatilhos valem para qualquer papel, inclusive o dono do esquema:

- recusam `UPDATE` nas quatro tabelas;
- recusam `DELETE` em recusas e detalhes;
- recusam `TRUNCATE` em todas.

Em `order_evaluation`, o `DELETE` só passa quando as três condições valem:

- o papel é membro de `risk_retention`;
- a linha é `APPROVED`;
- `evaluated_at` é anterior a `now() − 30 dias`.

A chave estrangeira de `order_rejection.evaluation_id` não tem `ON DELETE`. Por isso, nenhuma avaliação citada por uma recusa pode ser apagada.

O papel de grupo `risk_retention` não tem login. O `infra/postgres/bootstrap/10-roles.sql` o cria de forma idempotente e o concede só ao login de retenção da U2 (`DB_RETENTION_USER`). A aplicação e o dono do esquema nunca são membros. O `RiskPrivilegesIT` e o `RolesBootstrapIT` conferem cada linha da matriz.

## Expurgo das aprovadas antigas

A BR4.3 segue a versão do NFR Design (`security-design.md`), que prevalece sobre o texto do design funcional. Recusas e detalhes nunca são apagados. Só as avaliações `APPROVED` com mais de 30 dias e sem recusa que as cite saem.

| Item | Valor |
|---|---|
| Quando | Todo dia às 03:30 UTC (`memetrade.riskcontrol.purge.cron`), depois da purga da trilha às 03:00 |
| Execução única | ShedLock `riskcontrol-purge`, no máximo 45 min |
| Conexão | `RiskRetentionDataSource`: pool próprio de 1 conexão, espera de 30 s, login de retenção da U2 |
| Seleção | `evaluated_at < now() − 31 dias` do banco, na mesma instrução do `DELETE`; um dia de folga sobre o gatilho |
| Lotes | 1.000 linhas por transação, até um lote vir incompleto ou até 30 min de rodada; o restante fica para o dia seguinte |
| Registro | Uma linha em `risk_purge_run` por rodada (`SUCCEEDED` ou `FAILED` com a causa curta); a própria rodada apaga as linhas com mais de 90 dias |
| Evidência | Log `INFO` por lote ("Risk purge batch ... durationMs") e por rodada; sem evento na trilha |

Uma decisão com mais de 30 dias avaliada de novo não é reconhecida pela U6. Esse limite é conhecido. A segunda execução é contida pela chave de idempotência da U7, que devolve `Duplicate`, e não existe segunda ordem (`PurgedDecisionReplayIT`).

## Rota C21

`GET /api/v1/bots/{botId}/rejections?cursor=&limit=` (1 a 100, padrão 50)

- **Filtro.** Toda consulta filtra por tenant, pessoa e `PAPER` do token. Um `tenantId` ou outro parâmetro não declarado responde `400 VALIDATION_FAILED`.
- **404 uniforme.** Respondem `404 NOT_FOUND`, com a mesma resposta: bot de outra pessoa (avaliado ou não), bot de outro tenant, bot inexistente, bot próprio ainda não avaliado e bot com as avaliações todas apagadas. A tela do bot (U5) trata esse 404 como "nenhuma recusa".
- **Página.** Vem da mais recente para a mais antiga, com cursor opaco ligado à rota e ao bot. Cada item traz `reasons`, `limitsVersion`, `evaluatedAt`, `pauseTriggers`, `details` e `market` (`referencePrice` e `liquidity` como `MarketValue`, `estimatedSlippagePct` e `orderValue` em texto ou `UNKNOWN`).
- **Total.** `total` conta até 10.000. Acima disso, vem `totalCapped = true`.
- **Métodos e limites.** Só `GET`. A rota não tem limite de requisições próprio.

## Observabilidade

| Sinal | Onde | Conteúdo |
|---|---|---|
| Saúde `riskcontrol` | Actuator, fora de liveness e readiness | `UP` com sucesso há menos de 26 h, ou sem rodada com o processo há menos de 26 h; senão `DOWN` com `reason` `PURGE_FAILED` ou `PURGE_STALE` |
| `riskcontrol.evaluation.errors` | Micrometer | Rótulo `cause` (`MARKET_DATA`, `PORTFOLIO`, `DATABASE`, `OTHER`) |
| `riskcontrol.purge.errors` | Micrometer | Sem rótulo |
| Log "Order rejected" | `INFO` | `rejectionId`, `botId`, `decisionId`, motivo principal, número de motivos, gatilhos |
| Log "Order evaluation failed" | `ERROR` | causa, `botId`, `decisionId` e a pilha (nunca na resposta) |
| Evento `ORDER_REJECTED` | Trilha (C9) | Ator `system`, tenant do contexto, alvo `bot:<botId>`, detalhes `rejectionId`, `decisionId`, `reasons`, `pauseTriggers`, `limitsVersion` |

Nenhum log, métrica ou evento traz endereço de carteira, token, cabeçalho `Authorization` ou valor de mercado. O `RiskControlObservabilityIT` varre um ciclo com recusa para conferir isso.

## Procedimentos

### Saúde `riskcontrol` em `DOWN`

1. Ler o detalhe da saúde (`/actuator/health`, porta de gestão): `reason`, `lastRunAt`, `lastResult` e `lastSuccessAt`.
2. **`PURGE_FAILED`.** Procurar no log da `api` a linha `Risk purge failed: cause=...` da última rodada. As causas mais comuns são:
   - senha do login de retenção errada (`DB_RETENTION_PASSWORD`);
   - o login sem o grupo `risk_retention`: rodar de novo o `db-bootstrap`, que é idempotente;
   - banco indisponível.

   Os lotes já feitos continuam apagados, e a rodada seguinte retoma de onde parou.
3. **`PURGE_STALE`.** Confirmar que o agendador está ligado (`memetrade.scheduling.enabled`) e que o pool do agendador tem pelo menos 13 threads. Depois, olhar a tabela `shedlock`: um `lock_until` de `riskcontrol-purge` preso por uma instância morta expira em até 45 minutos.
4. A API e o worker continuam funcionando, porque a readiness não inclui `riskcontrol`. Corrigida a causa, a próxima rodada das 03:30 UTC volta a saúde para `UP`.

### Rollback para a imagem da U7

A `0009` só acrescenta tabelas, índices, gatilhos e privilégios. A imagem anterior roda sobre o banco migrado com as tabelas novas sem uso, e o papel `risk_retention` sobra, inofensivo. O `MigrationsIT` roda as consultas das Units anteriores sobre o esquema da U6.

## Cenário de carga `u6`

`LOAD_SCENARIO=u6 SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` (só local; também pelo workflow manual `load-test.yml`).

- **Preparação.** São 20 pessoas (`LOADTEST_LEDGER_PEOPLE`) e 60 bots com conta PAPER criada pela carga da U7, sem preenchimentos semeados. O volume semeado é:
  - 10.000 recusas num bot de cada pessoa;
  - 80.000 recusas num único bot;
  - 200.000 aprovadas com 32 a 40 dias.
- **Ciclos.** Uma avaliação por bot a cada 30 s, metade recusada por limites apertados. 20 leitores leem a C21, uma leitura a cada 2 s, por 10 minutos.
- **Janelas.** A janela sem expurgo vai de 0 a `LOADTEST_U6_PURGE_AT` (300 s). A janela com expurgo vai de lá até o fim.
- **Metas:**
  - p95 de `evaluate` ≤ 250 ms em cada janela, e a segunda no máximo 10% acima da primeira;
  - p95 da C21 ≤ 1 s;
  - todo lote do expurgo ≤ 100 ms;
  - nenhum `Seq Scan` em `order_evaluation` ou `order_rejection`.
- **Relatório.** `docs/perf/u6-risk-control-<data>.md`.

## Pendências para a U5

- A C14 mudou: `Rejected` traz `Set<PauseTrigger> pauseTriggers` (vazio quando nenhum) e `rejectionId`. A U5 aplica cada gatilho na mesma transação do ciclo.
- A transação do ciclo roda em `READ COMMITTED`.
- A U7 só define `lock_timeout` e `statement_timeout` dentro de `execute`, que vem depois da barreira. Para que as instruções da barreira também tenham esses limites, a U5 deve fazer o `SET LOCAL` no início do ciclo.
- A tela do bot trata o `404` da C21 como "nenhuma recusa".
- A tela mostra uma `DUPLICATE` de decisão já executada como "decisão já avaliada; vale o resultado original", não como limite violado.
- A U7 grava o `REQUEST_FORBIDDEN` de um bot de outra pessoa numa transação própria, então esse evento fica mesmo com o rollback do ciclo.
- O NFR2.2 (barreira mais execução, p95 ≤ 500 ms) e o NFR3.2 (tela do bot, p95 ≤ 2 s) são medidos no estágio da U5.
