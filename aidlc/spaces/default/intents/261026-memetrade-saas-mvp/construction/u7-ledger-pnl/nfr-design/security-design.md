# Desenho de segurança e integridade — U7 Ledger e P&L

Como a Unit `u7-ledger-pnl` atende a [security-requirements.md](../nfr-requirements/security-requirements.md). Base: [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), contratos C5, C9, C11, C13 e C15 e as decisões de [nfr-design-questions.md](nfr-design-questions.md). Os IDs `NFRx.y` são os da U7.

## Imutabilidade no banco (NFR6.2)

A migração `0008-ledger-pnl` cria as tabelas com o dono do esquema e concede ao papel da aplicação só o que a matriz do NFR6.2 permite. A matriz é ampliada aqui com a tabela de resumo diário decidida na Q1 (`pnl_daily`), que não existia quando o requisito foi escrito; ela é dado derivado, protegido por gatilho, e não alimenta a barreira de risco:

| Tabela | Privilégios do papel da aplicação | Proteção no banco |
|---|---|---|
| `ledger_entry` | `INSERT`, `SELECT` | Gatilho `BEFORE UPDATE OR DELETE` que lança erro para qualquer papel; `TRUNCATE` revogado |
| `fill` | `INSERT`, `SELECT` | Mesmo gatilho de `ledger_entry` |
| `paper_order` | `INSERT`, `SELECT`, `UPDATE (status)` | Gatilho `BEFORE UPDATE`: aceita só `OLD.status = 'CLOSE_PENDING' AND NEW.status = 'FILLED'` com todas as outras colunas iguais |
| `position` | `INSERT`, `SELECT`, `UPDATE (quantity, cost_basis, status, updated_at, version)` | `CHECK` de não negativos e de `quantity = 0` se e só se `cost_basis = 0` |
| `paper_account` | `INSERT`, `SELECT`, `UPDATE (status)` | Gatilho: só `OPEN→CLOSING` e `CLOSING→CLOSED`; `CLOSING→CLOSED` exige que nenhuma `position` do bot tenha `quantity > 0`. Um encerramento sem posições faz as duas transições na mesma transação |
| `equity_peak` (dono `pnl`) | `INSERT`, `SELECT`, `UPDATE (peak_equity, peak_at, last_equity, last_equity_at)` | Gatilho recusa `NEW.peak_equity < OLD.peak_equity` |
| `pnl_daily` (dono `ledger`, resumo da Q1) | `INSERT`, `SELECT`, `UPDATE (realized, fees, fill_count, updated_at)` | `CHECK mode = 'PAPER'`; gatilho: `fill_count` nunca diminui; sem mudança de `fill_count`, só `fees` pode mudar (compensação); dado derivado só para relatório, conferido pela reconciliação (`SUMMARY_MISMATCH`) |
| `reconciliation_check` | `INSERT`, `SELECT`, `DELETE` | Só a tarefa apaga, mantendo os 100 últimos por pessoa |
| `simulation_policy` | `SELECT` | Semeada pela migração |

O teste `LedgerPrivilegesIT` conecta com o papel da aplicação e, para cada linha da matriz, confere uma operação permitida e uma recusada (por exemplo, `UPDATE ledger_entry` falha; `UPDATE paper_order SET quantity` falha; `UPDATE paper_order SET status = 'FILLED'` a partir de `CLOSE_PENDING` passa; baixar `peak_equity` falha).

## Isolamento por pessoa e só PAPER (NFR5.1 a NFR5.3)

| Ponto | Desenho |
|---|---|
| Rotas C5 | Tenant e pessoa do `AuthContext` da U1; toda consulta tem `tenant_id = :t AND owner_user_id = :u AND mode = 'PAPER'`. O `botId` é resolvido para a `PaperAccount`: de outra pessoa → `404 NOT_FOUND`; inexistente → resposta vazia |
| Interfaces C13 e C15 | O contexto de autenticação do ciclo (C10) dá tenant e pessoa; conta de outra pessoa → `NotFilled(TENANT_MISMATCH)` e `REQUEST_FORBIDDEN` pelo publicador da trilha |
| Tarefas | Leem tenant e pessoa da própria `PaperAccount` e passam esses valores a todas as consultas da rodada; nenhuma consulta das tarefas junta linhas de pessoas diferentes |
| Registro de rotas | As rotas C5 entram em `tenancy/route-registry.yml` como `CLIENT` com `negativeTest` (`LedgerRoutesIT#anotherPersonsBotIsNotFound`); as tarefas como `GLOBAL` com a nota de que filtram pela linha |
| Só PAPER | Coluna `mode` com `CHECK (mode = 'PAPER')` em todas as tabelas de dados PAPER da U7, inclusive `pnl_daily`, e `mode` nas chaves únicas e nas consultas; `ModeGuardFilter` da U1 já recusa `mode` diferente de `PAPER` com `403 REAL_MODE_UNAVAILABLE` |
| Fronteira de módulos | O `pnl` lê o `ledger` só pela `LedgerReadModel` e pelo evento `FillRecorded` (logical-components.md); regra ArchUnit dedicada |

## Nenhuma transação real, nenhum ponto flutuante (NFR6.1, NFR6.7)

`LedgerArchitectureTest` (ArchUnit) sobre todo o código de produção:
- `com.memetrade.ledger..` e `com.memetrade.pnl..` não dependem de `java.net.http`, `org.springframework.web.client`, `org.sol4k..`, `software.sava..` nem de classes com `Signature`, `Keypair` ou `Transaction` no nome;
- nenhum campo, parâmetro ou retorno `double`, `float`, `Double` ou `Float` nesses pacotes;
- `LedgerCompensation` é de visibilidade de pacote em `ledger.domain` e nenhuma classe fora de `ledger.domain` o referencia (NFR6.4).

## Superfície da API (NFR6.3, NFR6.5, NFR6.6)

- OpenAPI do convidado: `GET` em `/bots/{botId}/trades`, `/bots/{botId}/day-usage`, `/ledger`, `/pnl`, `/reconciliation`; `POST /reconciliation/run` com `Idempotency-Key`. O teste de contrato falha com qualquer outro verbo sob essas rotas.
- "Verificar agora": um bean próprio do `PersonRouteRateLimiter` (a classe recebe o limite no construtor) com limite 6 por pessoa em janela de 1 minuto, `429 RATE_LIMITED` com `Retry-After`; a limpeza de chaves ociosas entra num trabalho próprio (`ReconciliationRunLimiterEvictionJob`, registrado em `route-registry.yml` como `GLOBAL`); as leituras não têm limite (Q3 de NFR Requirements).
- Validação: `botId` UUID; `from` e `to` instantes UTC com `from < to` e até 366 dias; `cursor` opaco assinado como os das Units anteriores; `limit` 1 a 100; parâmetro desconhecido `400`.

## Idempotência (NFR6.8)

| Operação | Mecanismo | Unicidade final |
|---|---|---|
| `execute` | `INSERT ... ON CONFLICT (tenant_id, mode, idempotency_key) DO NOTHING RETURNING`; se nada voltar, ler a ordem e comparar `content_digest` | Índice único em `paper_order` |
| `fund` | `IdempotencyGuard` da U1, operação `PAPER_FUND` (valor novo do enum `IdempotentOperation`, retenção padrão de 24 h), na transação da U5 | Índice único parcial `ledger_entry (bot_id) WHERE type = 'FUNDING'` |
| `closePositions` | `IdempotencyGuard`, operação `PAPER_CLOSE_POSITIONS`; o `CloseResult` é serializado em JSON para a resposta guardada | Ordens `CLOSE` com chave derivada `<chave>:<positionId>`; `closePositions` com a conta já em `CLOSING` ou `CLOSED` não cria ordem para posição `CLOSE_PENDING` e devolve o estado atual (achado da revisão do design funcional) |

## Logs (NFR7.1)

Os logs da U7 usam só `orderId`, `botId` e `correlationId`; valores, endereços e dados de mercado não vão para o log. O `LogScanIT` ganha um fluxo completo (fund, compra, venda, fechamento, P&L, reconciliação).

## Rastreio para os requisitos

| Requisito | Onde |
|---|---|
| NFR5.1, NFR5.2, NFR5.3 | Isolamento por pessoa e só PAPER |
| NFR6.1, NFR6.4, NFR6.7 | Nenhuma transação real, nenhum ponto flutuante |
| NFR6.2 | Imutabilidade no banco |
| NFR6.3, NFR6.5, NFR6.6 | Superfície da API |
| NFR6.8 | Idempotência |
| NFR7.1 | Logs |
