# Consultas salvas do Logs Insights — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Observability Setup, Q5 (opção A).

> **Nada está implantado.** As cinco consultas são recursos `aws_cloudwatch_query_definition` em `infra/terraform/modules/observability/log-queries.tf`, validados offline. Elas só aparecem no console, na pasta `eureka-real`, depois do primeiro `apply` da devnet.

## Logs que as consultas leem

- **Grupos:** `/ecs/eureka-real/api-worker` (api e worker), `/ecs/eureka-real/txsigner`, `/ecs/eureka-real/web` e `/ecs/eureka-real/admin`.
- **Retenção:** 90 dias (`log_retention_days`), por minimização da LGPD. A trilha de auditoria fica no banco.
- **Formato:** JSON do `LogstashEncoder`, com os campos `timestamp` (UTC), `level`, `component` (o logger), `message`, `correlationId` e os argumentos estruturados de cada linha. Por exemplo, a auditoria do TxSigner traz `eventType`, `operation` e `reason`.
- **O que nunca aparece:** segredo, token, nonce, assinatura de login e endereço completo. O codificador mascara endereços e assinaturas (Base58), e nenhuma consulta pede esses campos.
- **Grupos dos eventos EMF** (`/ecs/eureka-real/metrics/*`): só levam valores de métrica e rótulos fechados, e ficam 7 dias. Nenhuma consulta os usa.

## Consultas

| Nome | Grupos | Responde a |
|---|---|---|
| `eureka-real/seguir-correlation-id` | api-worker, txsigner | O que aconteceu com este pedido? Mostra, em ordem, todas as linhas de um `correlationId` no api, no worker e no TxSigner. Antes de rodar, troque `COLE-AQUI-O-CORRELATION-ID` pelo valor da resposta de erro (`ProblemDetail.correlationId`) ou do log, porque consultas salvas não têm parâmetro. |
| `eureka-real/recusas-txsigner-por-motivo` | txsigner | Por que o TxSigner está recusando? Conta os eventos `SIGNING_REFUSED`, `WALLET_CREATION_REFUSED`, `POLICY_CHANGE_REFUSED` e `REQUEST_MISMATCH` por operação e motivo. Uma recusa de saque ou de swap fora do normal é ataque ou erro: veja `alarms.md`. |
| `eureka-real/erros-5xx-por-componente` | api-worker | De onde vêm os 5xx? Agrupa os `ERROR` do `ProblemDetailsHandler` e dos tratadores `*Errors` por componente e mensagem. **A rota não está no log** (lacuna OBS-G3). Use junto com o painel Serviços, que mostra os 5xx do grupo-alvo do api pelo balanceador. |
| `eureka-real/ordens-real-ambiguas` | api-worker | Quais ordens REAL ficaram ambíguas e por quê? Lista as linhas com "ambiguous", com o `correlationId`. Complementa o alarme `real-execution-ambiguous-old` e o runbook `docs/runbooks/real-execution.md`. |
| `eureka-real/falhas-dos-trabalhos` | api-worker, txsigner | Qual trabalho em segundo plano está falhando? Conta os `ERROR` e `WARN` de componentes `*Job`, `*Jobs`, `*Schedule`, `*Driver`, `*Consumer` e `*Watcher` por hora, com a última mensagem. Complementa os alarmes `*-job-stalled-*`. |

## Texto das consultas

O texto completo está em `log-queries.tf`. Um exemplo:

```
fields @timestamp, eventType, operation, reason
| filter eventType in ["SIGNING_REFUSED", "WALLET_CREATION_REFUSED", "POLICY_CHANGE_REFUSED", "REQUEST_MISMATCH"]
| stats count(*) as recusas by eventType, operation, reason
| sort recusas desc
```

## Custo

O Logs Insights cobra US$ 0,005 por GB lido, e só quando a consulta roda. Uma consulta de 24 h no grupo `api-worker` do piloto lê poucos MB.

## Conferência depois do primeiro apply da devnet

```bash
# As cinco consultas existem
aws logs describe-query-definitions --query-definition-name-prefix eureka-real/ \
  --query 'queryDefinitions[].name'

# Uma consulta roda e devolve resultado (exemplo: falhas dos trabalhos na última hora)
QID=$(aws logs start-query --log-group-names /ecs/eureka-real/api-worker /ecs/eureka-real/txsigner \
  --start-time "$(date -u -d '-1 hour' +%s)" --end-time "$(date -u +%s)" \
  --query-string 'fields @timestamp, level, component, message | filter level in ["ERROR", "WARN"] | limit 20' \
  --query queryId --output text)
aws logs get-query-results --query-id "$QID" --query 'status'

# Nenhum endereço completo nem segredo nos logs: deve devolver zero linhas
aws logs start-query --log-group-names /ecs/eureka-real/api-worker /ecs/eureka-real/txsigner \
  --start-time "$(date -u -d '-1 day' +%s)" --end-time "$(date -u +%s)" \
  --query-string 'fields @message | filter @message like /(?i)authorization: bearer|BEGIN PRIVATE KEY|seed phrase/ | limit 5'
```

Depois, abrir cada consulta salva no console e rodá-la com o ambiente no ar. Ela deve terminar sem erro de sintaxe, mesmo que devolva zero linhas.

## Sources

- `observability-setup-questions.md` (Q5).
- `apps/api/src/main/resources/logback-spring.xml` e `apps/txsigner/src/main/resources/logback-spring.xml` (campos do JSON).
- `apps/txsigner/src/main/java/com/memetrade/txsigner/infra/audit/LoggingSignerEvents.java` (campos da auditoria do TxSigner).
- `apps/api/src/main/java/com/memetrade/shared/error/ProblemDetailsHandler.java` (log dos 5xx).
- `infra/terraform/modules/services/logs.tf` (grupos e retenção) e `infra/terraform/modules/observability/log-queries.tf`.
