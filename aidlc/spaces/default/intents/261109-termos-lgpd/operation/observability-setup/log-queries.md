# Consultas de log

Conversa: português (Brasil). Resposta 4A. Os logs são JSON por contêiner (`docker compose logs`); na AWS, CloudWatch Logs Insights. As consultas do intent anterior continuam (`261026-memetrade-saas-mvp/operation/observability-setup/log-queries.md`). Nenhuma consulta procura dado pessoal: os logs não carregam endereço completo de carteira, texto de pedido, contato nem assinatura (`security-design.md` das Units; `LogScanIT`).

## Consultas novas

| Nome | Para quê | Logs Insights (AWS) | Local |
|---|---|---|---|
| `legal-version-ignored` | versão legal ignorada na subida (`LEGAL_VERSION_IGNORED`) | `fields @timestamp, message \| filter message like /Legal version .* ignored/` | `docker compose logs api \| grep "Legal version"` |
| `purge-runs` | execuções dos expurgos e falhas | `fields @timestamp, message \| filter component like /PurgeJob/ \| sort @timestamp desc` | `docker compose logs api \| grep -i purge` |
| `audit-full-wallet-rejected` | tentativa de gravar carteira completa na trilha (`AUDIT_FULL_WALLET_REJECTED`) | `fields @timestamp, correlationId, message \| filter level = "ERROR" and message like /FULL_WALLET/` | `docker compose logs api \| grep FULL_WALLET` |
| `terms-accepted` | aceites registrados (só versão e origem) | `fields @timestamp, message \| filter message like /Terms accepted/` | `docker compose logs api \| grep "Terms accepted"` |
| `anonymization` | anonimizações e falhas (só identificador do pedido e contagens) | `fields @timestamp, level, message \| filter message like /Account anonym/` | `docker compose logs api \| grep "Account anonym"` |
| `correlation` | tudo de uma requisição | `fields @timestamp, component, message \| filter correlationId = "<id>"` | `docker compose logs \| grep <id>` |

O `correlationId` aparece na resposta de erro (`ProblemDetail`) e liga log, requisição e evento da trilha.

## Fontes

- Mensagens e níveis de log: `monitoring-design.md` e `observability-design.md` de cada Unit (U1 a U5).
- Regra de não registrar dado pessoal: `security-design.md` de cada Unit e `project.md` (Forbidden); garantida pelo `LogScanIT` e pela varredura de logs do `verify.sh`.
