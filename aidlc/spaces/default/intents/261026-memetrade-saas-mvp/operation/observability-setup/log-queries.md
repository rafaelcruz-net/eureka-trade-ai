# Consultas de log

Conversa: português (Brasil). Retenção e agregação conforme a resposta 5A e os `monitoring-design.md` das Units (`construction/<unit>/infrastructure-design/`). Regras de conteúdo dos `security-design.md` (`construction/<unit>/nfr-design/`).

## Retenção e agregação

| Ambiente | Onde | Retenção |
|---|---|---|
| Local | stdout de cada contêiner, driver `json-file` | `max-size: 10m`, `max-file: 3` por serviço (por tamanho) |
| CI | logs do smoke como artefato, depois da varredura | 7 dias |
| AWS (desenho) | CloudWatch Logs, um grupo por serviço: `/memetrade/<env>/api`, `/worker`, `/web`, `/admin` | 7 dias (requisitos de observabilidade), com criptografia KMS |
| Trilha de auditoria | banco (U2), fora dos logs | política de retenção da U2 |

Campos de cada linha: `timestamp` (UTC), `level`, `component`, `message`, `correlationId` e os campos permitidos de cada módulo. Nunca entram endereço completo de carteira, token, cabeçalho `Authorization`, nonce, assinatura, segredo nem texto de feedback. A varredura do `verify.sh` e o `LogScanIT` continuam valendo.

## Consultas locais

```bash
# tudo de uma requisição
docker compose logs --no-color api worker | grep '"correlationId":"<id>"'
# erros por componente
docker compose logs --no-color api worker | grep '"level":"ERROR"'
# códigos estáveis do kill switch
docker compose logs --no-color api | grep -E 'KILL_SWITCH_(RECONCILE_STALE|PENDING_STALE|DIVERGENCE|APPLY_FAILED)'
```

## Consultas salvas do Logs Insights (AWS)

| Nome | Consulta |
|---|---|
| `por-correlation-id` | `fields @timestamp, level, component, message \| filter correlationId = "<id>" \| sort @timestamp asc` |
| `erros-por-componente` | `filter level = "ERROR" \| stats count(*) as erros by component, bin(5m) \| sort erros desc` |
| `codigos-de-erro-estaveis` | `filter ispresent(errorCode) \| stats count(*) by errorCode, bin(15m)` |
| `kill-switch` | `filter message like /KILL_SWITCH_/ \| fields @timestamp, level, message \| sort @timestamp desc` |
| `rodadas-de-tarefas` | `filter component in ["AuditRelay","AuditJobs","LedgerJobs","RiskRecalculation","FeedbackPurge","RiskEvaluationPurgeJob"] \| fields @timestamp, component, level, message \| sort @timestamp desc` |
| `divergencia-do-ledger` | `filter component = "LedgerJobs" and level = "WARN" \| fields @timestamp, message` |
| `fonte-de-mercado` | `filter component like /MarketData/ and level in ["WARN","ERROR"] \| stats count(*) by bin(5m)` |
| `respostas-5xx` | `filter status >= 500 \| stats count(*) by uri, bin(5m)` |
| `recusas-de-tenant` | `filter status in [403, 404] and uri like /\/api\/v1\// \| stats count(*) by uri, bin(15m)` |
| `logins-recusados` | `filter component = "IdentityAccess" and message like /rejected/ \| stats count(*) by reason, bin(15m)` |

Os nomes de componente seguem os módulos do código. Antes de salvar uma consulta na AWS, confirme os campos com uma linha real do ambiente.
