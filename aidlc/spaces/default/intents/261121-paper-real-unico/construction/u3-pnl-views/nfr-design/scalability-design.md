# Desenho de escala — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` (R-01, R-03) e da decisão F-1 do fundador de 2026-11-23.

Nada muda na escala [Q4]. A U3 roda no processo da API que já existe, sobre o mesmo PostgreSQL, sem migração, sem configuração nova e sem infraestrutura nova. Este arquivo mostra que as leituras novas cabem nos pools da U1 e da U2 e quanto o painel acrescenta de carga.

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Processos | API e worker sem mudança; nenhuma réplica, fila ou cache novo | NFR1.13 |
| Leituras REAL simultâneas | no máximo 8: C4, C5, o lado REAL de C6 e `GET /api/v1/real/pnl` passam pelo pool `pnl-mode-block` | NFR1.13 |
| Pools | Hikari principal sem mudança (20 na API); `pnl-mode-block` sem mudança (8 threads, fila de 16); `pnl-mismatch-audit` sem mudança (2 conexões), agora também com o executor `pnl-read-audit` (1 thread, fila de 16) | NFR1.13 |
| Dados | 0 tabelas, colunas, índices ou migrações; a única gravação é o evento de auditoria limitado | NFR1.15 |
| Painel | no máximo 2 pedidos de C4 por cartão a cada 30 s | NFR1.14 |
| Custo | 0 recursos AWS, 0 variáveis de ambiente, 0 chaves em `application.yml` | NFR8.1, NFR8.2 |
| Ambientes | só a mainnet para usuários | NFR8.3 |

## Volume de referência (NFR1.12)

Até 10 bots por pessoa e 2 anos de ordens do próprio bot (2.920 por bot na referência). C5 devolve 50 linhas por página e no máximo 100 por pedido. C4 traz até 10 parcelas rateadas por bot. Acima disso, nenhuma meta da U3 é prometida; o teto `memetrade.pnl.by-mode.real-max-orders` (50.000 ordens liquidadas por pessoa) da U2 continua sendo o limite duro da passada REAL, e acima dele a leitura sai `TOO_MANY_ORDERS`.

## Conexões e pools (NFR1.13)

| Caminho | Conexões por pedido | Teto no processo |
|---|---|---|
| C4 sem `market` | até 2, nas threads do pool `pnl-mode-block` | 8 (o pool) |
| C4 com `market` | 1, no pool | 8 |
| C5 | 1, no pool | 8 |
| C6 | até 2, no pool | 8 |
| `listBots` | 1, na thread do pedido, por poucos milissegundos | o Hikari de 20 |
| 404 de C5 e C6 | 1 do principal, na thread do pedido: `botOf` e `ownedByAnotherTenant` pela chave primária | o Hikari de 20 |
| Gravação da auditoria (404 confirmado de outro tenant ou 422) | 1 do `pnl-mismatch-audit`, nenhuma do principal | 1 thread de auditoria; o `pnl-mismatch-audit` continua com 2 conexões |

- O pool `pnl-mode-block` continua sendo a antepara: com C4, C5 e C6 juntos, no máximo 8 conexões do Hikari ficam em leituras de P&L e 12 ficam para o resto da API. A fila de 16 recusa na hora quando cheia: em C4 e C6 o bloco ou o lado sai `UNAVAILABLE` com `REJECTED` e a resposta é 200; em C5 a resposta é 503.
- Pela decisão F-1 do fundador, a verificação `ownedByAnotherTenant` roda na thread do pedido, nos dois 404, na mesma conexão curta da consulta `botOf`. O executor `pnl-read-audit` não usa o pool principal. Um 404 de uuid inexistente nunca entra na fila, então uma rajada de uuid aleatório não enche a fila de 16 nem apaga o evento de uma sondagem real.
- O `pnl-mismatch-audit` passa a ter 3 threads disputando 2 conexões (2 do `ReconciliationAuditGate` e 1 do `ReadRefusalAudit`). O filtro em memória do `ReadRefusalAudit` (pessoa e ação enviadas nos últimos 60 minutos não são enviadas de novo) limita a U3 a 1 gravação por hora por pessoa e por ação em cada processo, então uma sondagem repetida não toma as conexões da gravação da divergência REAL. O `ReadRefusalAuditLimitIT` mede essa espera. O pool não cresce.

## Carga do painel (NFR1.14)

O temporizador de 30 s por cartão (`performance-design.md`) limita cada cartão a 2 pedidos em qualquer intervalo de 30 s e a 1 a cada 30 s com eventos contínuos.

| Referência: 20 pessoas com o painel aberto (40 cartões) | Pedidos de C4 com `market` | Por segundo |
|---|---|---|
| regime contínuo | até 40 a cada 30 s | cerca de 1,3 |
| pior intervalo de 30 s (todas as janelas fecham juntas) | até 80 | cerca de 2,7 |
| cenário `pnl-by-mode` (referência de capacidade) | 20 usuários, 1 leitura a cada 2 s | 10 |

Cada pedido de cartão lê um só bloco (C4 com `market`), metade de um C4 completo. O canal ao vivo não muda e não ganha evento novo; o painel só passa a assinar `real.order.state` e `real.conditional.state`, que o canal já envia.

## Crescimento de dados (NFR1.15)

- 0 tabelas, colunas, índices e changesets do Liquibase. C5 e C6 leem pelo índice `real_ledger_entry_wallet_created_idx` da `0023` (U2).
- A única gravação é o evento de auditoria, na tabela de auditoria que já existe (outbox e `audit_event`), com a retenção de hoje. Limite: 1 evento por hora por pessoa e por ação, então no máximo 2 por pessoa por hora e 48 por dia.
- Os três contadores ficam na memória do processo e no Prometheus (13 séries por processo, sem etiqueta de pessoa).

## Escala horizontal

O cursor de C5 é calculado de novo em cada pedido a partir do ledger, e o limite da auditoria é lido da própria trilha sob trava consultiva, então vale com várias instâncias da API. O único estado em memória da U3 é o filtro de 60 minutos do `ReadRefusalAudit`, limitado a 10.000 chaves e usado só para evitar trabalho repetido: com várias instâncias ou depois de reiniciar, a trilha continua sendo a fonte da verdade. O temporizador de 30 s vive no navegador de cada pessoa.

## Custo e ambientes (NFR8.1, NFR8.2, NFR8.3)

- 0 recursos novos em `infra/terraform/`, 0 variáveis de ambiente novas nas definições de tarefa e 0 chaves novas em `application.yml`. Os tamanhos do executor de auditoria são constantes do código, validadas por teste.
- O orçamento de US$ 350 por mês por conta (`devnet` e `mainnet`) não muda. As métricas novas não entram na lista do CloudWatch. O cenário de carga ampliado roda no runner do GitHub, nos minutos do Actions.
- Nenhuma tela, rota ou configuração da U3 abre a devnet a uma conta de usuário.

## Sources

- `nfr-design-questions.md` desta pasta: Q1, Q2, Q4 e o resumo confirmado [Q1] [Q2] [Q4].
- `construction/u3-pnl-views/nfr-requirements/scalability-requirements.md`: NFR1.12 a NFR1.15, NFR8.1 a NFR8.3.
- `construction/u1-mode-skeleton/nfr-design/scalability-design.md`: divisão das 20 conexões e o pool `pnl-mode-block`.
- `construction/u2-real-bot-pnl/nfr-design/scalability-design.md`: leituras REAL no pool, pool `pnl-mismatch-audit`.
- Código lido: `apps/api/src/main/resources/application.yml` (Hikari 20, `connection-timeout` 2 s, `memetrade.pnl.by-mode`, `memetrade.pnl.mismatch-audit`), `pnl/infra/PnlByModeProperties.java`, `pnl/infra/PnlMismatchAuditProperties.java`, `audit/infra/MismatchAuditDataSource.java`.
- `aidlc/spaces/default/memory/team.md`: Deployment (contas, orçamento, devnet); `project.md`: NEVER abrir a devnet aos usuários.

## Assumptions & Open Questions

1. [assumption] As 20 pessoas com o painel aberto ao mesmo tempo repetem a referência dos cenários de carga; os tetos da tabela do painel exigem eventos dos dois modos em todas as janelas.
2. [assumption] Se o `ReadRefusalAuditLimitIT` mostrar disputa no `pnl-mismatch-audit` mesmo com o filtro em memória, o ajuste fica com o responsável em outro estágio (por exemplo, um pool próprio de 1 conexão para a U3); este desenho não muda o pool.
