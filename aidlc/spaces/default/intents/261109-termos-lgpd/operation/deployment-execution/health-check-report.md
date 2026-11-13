# Relatório de saúde

Conversa: português (Brasil). Ensaio de atualização e rollback da release `v2026.11.12-1`, num único volume do banco (projeto Compose `memetrade-release`, segredos aleatórios gerados no processo e nunca impressos; chave HMAC efêmera). Cada passo subiu com `docker compose up -d --build --remove-orphans --wait` e foi conferido pela saúde da API, pelo web e pelo admin e pela contagem de changesets.

| # | Versão | API (`/actuator/health`) | Web (`:3000`) | Admin (`:3001`) | Changesets |
|---|---|---|---|---|---|
| 1 | anterior `8fd7a7b` (`v2026.11.08-1`) | `UP` | 200 | 200 | 64 |
| 2 | candidata `e6c634a` | `UP` | 200 | 200 | 83 |
| 3 | rollback para `8fd7a7b` sobre o banco migrado | `UP` | 200 | 200 | 83 |
| 4 | candidata `e6c634a` de novo | `UP` | 200 | 200 | 83 |

Resultado: **ensaio aprovado**; o ambiente foi removido no fim (`down -v`) e a worktree temporária da versão anterior foi apagada.

## Saúde dos indicadores novos

No smoke da CI (`verify.sh`), o script espera a saúde `UP` da API depois da primeira coleta e das tarefas de auditoria. Os indicadores novos deste intent têm testes próprios na suíte:

| Indicador | Unit | Comportamento |
|---|---|---|
| `legalDocuments` | U1 | `UP` com versão publicável; `DEGRADED` (HTTP 200) sem os campos de implantação; fora de liveness e readiness |
| `loginChallengePurge`, `sessionPurge` | U2 | `DEGRADED` quando o expurgo atrasa |
| `dataRequests` | U4 | `DEGRADED` com pedido vencido |

No ambiente de teste, `legalDocuments` não é publicável (campos de implantação "a definir"), como esperado. A versão do beta só é publicada com esses campos preenchidos.

## Conexões

Pool fixo de 20 conexões na `api`; orçamento de 43 de 100 no PostgreSQL (`docker-compose.yml`). No ensaio, todos os passos subiram sem esgotar conexões.
