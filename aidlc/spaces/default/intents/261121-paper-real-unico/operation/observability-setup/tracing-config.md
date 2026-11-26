# Rastreamento — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Observability Setup, Q3 (opção A), com o resumo confirmado. Data: 2026-11-25.

> **Nada está implantado e nada novo é criado para rastreamento** [Q1] [Q3]. Continua a decisão do 261113: **sem X-Ray e sem OpenTelemetry**; o `correlationId` nos logs e nas respostas segue o pedido. Este arquivo registra só como os caminhos novos deste intent usam esse mesmo mecanismo.

## 1. O que é herdado sem mudança

Do `tracing-config.md` do `261113-trading-real-bot`:

- a decisão: sem X-Ray nem OpenTelemetry no piloto (uma tarefa por serviço, cadeia curta, nenhum endpoint `xray` na VPC, nenhuma permissão `xray:PutTraceSegments` no papel do TxSigner);
- o caminho do `correlationId`: o `CorrelationIdFilter` aceita um `X-Correlation-Id` uuid ou gera um, põe no MDC, devolve no cabeçalho e no corpo RFC 7807 (`ProblemDetail.correlationId`); o worker usa um por rodada ou trabalho; o api e o worker o propagam ao TxSigner (`HttpTxSignerClient`);
- o campo `correlationId` no JSON (`includeMdcKeyName` em `logback-spring.xml`) e a consulta salva `eureka-real/seguir-correlation-id`;
- as três condições para rever a decisão: mais de uma tarefa por serviço, p95 fora da meta sem explicação no log na abertura aos convidados, ou um serviço novo no caminho do pedido.

**Nenhuma dessas condições muda com este intent.** Ele não cria processo, fila nem chamada a outro serviço (U1-NFR3.13, U2-NFR3.14, U3-NFR3.12, U4-NFR3.10). Por isso a decisão continua.

## 2. O que este intent acrescenta ao caminho do `correlationId`

| Caminho | Como o `correlationId` chega | Onde aparece | Teste |
|---|---|---|---|
| U1 e U3: pool `pnl-mode-block` (blocos de C4 e lados de C6) | o `ModeBlockExecutor` copia o MDC (`MDC.getCopyOfContextMap()`) da thread do pedido para a thread do pool e chama `MDC.clear()` no fim da tarefa | log `P&L block unavailable`, `PNL_COMPARE_SIDE_UNAVAILABLE`; campo `correlationId` do bloco ou do lado `UNAVAILABLE`; corpo RFC 7807 do 409 e do 503 | U1: teste de captura de log (100% iguais, inclusive um log escrito dentro da tarefa do bloco); U3: `PnlViewsCorrelationIT` |
| U3: executor `pnl-read-audit` | mesma cópia do MDC (`ReadRefusalAudit`) | evento de auditoria da recusa e `PNL_READ_AUDIT_FAILED`; a tela mostra o valor como "Código para o suporte" | `PnlViewsCorrelationIT` |
| U2: conferência do P&L REAL | lido do MDC na thread do pool | bloco `UNAVAILABLE` com `RECONCILIATION_MISMATCH`, evento de auditoria `REAL_PNL_RECONCILIATION_MISMATCH` e log, com o mesmo valor | `CorrelationParityIT` |
| U2: conflito de slot | o `correlationId` do trabalho ou do pedido que confirmou | `REAL_SLOT_CONFLICT` | `U2LogCaptureTest` |
| U4: ciclo do bot REAL | o `cycleId` é o `correlationId` do MDC durante todo o `CycleRunner.run`, inclusive a fase prévia e a leitura do `RealBotHoldings` | linha `Bot cycle: cycleId=...`, todos os `REAL_OWN_*`, a linha de `bot_cycle` e a decisão gravada | NFR3.10 da U4 (`OwnQuantityLogRedactionTest` e os testes do ciclo) |

**Como seguir na nuvem:** a consulta `eureka-real/seguir-correlation-id` (herdada), trocando o valor de exemplo. Num problema de leitura do P&L, o valor vem da resposta (cabeçalho, corpo RFC 7807 ou "Código para o suporte" na tela). Num problema do ciclo, o valor é o `cycleId` da linha `Bot cycle:` achada pelas consultas `real-posicao-propria-*` (`log-queries.md`).

## 3. O que o `correlationId` não mostra

O tempo de cada salto. Para os caminhos deste intent, as fontes são:

- o `elapsedMs` do `REAL_OWN_HOLDINGS_UNAVAILABLE` (U4);
- o `timestamp` de cada linha;
- os histogramas do Prometheus local no cenário de carga (`http_server_requests`, `pnl_real_attribution_read_seconds`) e as medidas da CI (`ModePnlLatencyIT`, `PnlViewsLatencyIT`, `RealBotHoldingsMeasurementIT`).

A medida por passo da leitura da U4 (S1 a S6 e o acumulador), pré-requisito da decisão F7, é um log de teste no `RealBotHoldingsMeasurementIT`, não um rastreamento em produção (`test-results.md`, 9.4).

## 4. Custo

**US$ 0.** Nenhum contêiner, permissão, endpoint ou biblioteca nova.

## 5. Conferência depois do primeiro apply da devnet

```bash
# Uma leitura de C4 sem login responde 401 em ProblemDetail com o correlationId
curl -s https://api.devnet.<domínio>/api/v1/pnl/by-mode | jq -r .correlationId

# O mesmo valor nos logs do api-worker
QID=$(aws logs start-query --log-group-names /ecs/eureka-real/api-worker \
  --start-time "$(date -u -d '-15 minutes' +%s)" --end-time "$(date -u +%s)" \
  --query-string 'fields @timestamp, component, message | filter correlationId = "<valor>" | sort @timestamp asc' \
  --query queryId --output text)
aws logs get-query-results --query-id "$QID"
```

Com login, na devnet, o fundador confere que o "Código para o suporte" de um bloco `UNAVAILABLE` (forçado pela leitura de C4 durante uma parada do banco, se for o caso) aparece na mesma consulta.

## Sources

- `observability-setup-questions.md` (Q3, resumo confirmado) [Q3] [Q1].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/tracing-config.md` (decisão e caminho herdados).
- `construction/u1-mode-skeleton/nfr-design/observability-design.md` (Correlação NFR3.12, Rastreamento NFR3.13).
- `construction/u2-real-bot-pnl/infrastructure-design/monitoring-design.md` e `nfr-design/observability-design.md` (NFR3.13, NFR3.14; `CorrelationParityIT`).
- `construction/u3-pnl-views/infrastructure-design/monitoring-design.md` e `nfr-design/observability-design.md` (NFR3.11, NFR3.12; `PnlViewsCorrelationIT`).
- `construction/u4-own-quantity-cycle/nfr-design/observability-design.md` e `infrastructure-design/monitoring-design.md` (NFR3.10, `cycleId`).
- `construction/build-and-test/test-results.md` (9.4: medida por passo).
- Código lido: `apps/api/src/main/resources/logback-spring.xml`, `bots/worker/CycleRunner.java`, `infra/terraform/modules/observability/log-queries.tf`.

## Assumptions & Open Questions

1. [assumption] Os testes de correlação citados (`CorrelationParityIT`, `PnlViewsCorrelationIT`, os de captura de log) estão verdes no run `45550427456` do `09de0a8`, como o job `backend` inteiro (`test-results.md`, 9.1).
2. [assumption] A rota `GET /api/v1/pnl/by-mode` sem login responde 401 em RFC 7807 com `correlationId`, como as rotas protegidas do 261113.
