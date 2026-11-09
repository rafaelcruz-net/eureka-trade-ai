# Relatório de saúde

Conversa: português (Brasil). Checagens de saúde da release local `v2026.11.08-1` (`8fd7a7b`), no ambiente listado em `operation/environment-provisioning/environment-inventory.md`. As checagens seguem os portões de `operation/deployment-pipeline/deployment-strategy.md` e o fluxo de `operation/deployment-pipeline/cd-config.md`.

## Ensaio sobre um único banco (projeto Compose `memetrade-release`)

`docker compose up -d --build --remove-orphans --wait` em cada passo. O `--wait` só termina quando todos os healthchecks passam: `pg_isready` no postgres, `HealthProbe` na API, e as checagens do worker, do web e do admin.

| Passo | Versão | Resultado |
|---|---|---|
| 1 | `0cd9ccf` (anterior) | saudável |
| 2 | atualização para `8fd7a7b` | saudável |
| 3 | rollback para `0cd9ccf` sobre o banco migrado | saudável |
| 4 | volta para `8fd7a7b` | saudável em 47 s; `db-bootstrap` saiu com 0; `db-migrate` saiu com 0; `databasechangelog` com 64 linhas; `/actuator/health` = `UP` (grupos `liveness` e `readiness`; componentes `adminKillSwitch` `UP` sem divergência e `auditOutboxCleanup` `UP`); web `GET /` 200; admin `GET /` 200 |

Os detalhes dos passos 1 a 3 se perderam no filtro do log. Eles passaram porque o script para no primeiro erro e chegou ao passo 4 (ver `deployment-log.md`). O ambiente foi removido no fim, com `down -v` e saída 0.

## Checagens do `verify.sh` (três execuções)

| Checagem | Resultado |
|---|---|
| Quatro serviços saudáveis em até 240 s | ok |
| Primeira coleta da fonte simulada em até 60 s | ok |
| Primeira purga de retenção da auditoria e limpeza do outbox em até 60 s | ok |
| Smokes Playwright | 10/10 nas três |
| Logs de api, worker e admin sem carteira completa, texto de feedback ou código de convite | ok |

## Postura

- Portas publicadas só em `127.0.0.1`; nenhum fornecedor real; REAL desligado por padrão.
- Nenhum segredo gravado: o ensaio e o `verify.sh` usam segredos aleatórios só no processo.
- Sem métricas de taxa de erro ou latência em produção, porque não há produção. Observabilidade é a próxima etapa.

## Veredito

**Saudável.** A release local está pronta. Staging e produção dependem da conta na AWS (`environment-inventory.md`).
