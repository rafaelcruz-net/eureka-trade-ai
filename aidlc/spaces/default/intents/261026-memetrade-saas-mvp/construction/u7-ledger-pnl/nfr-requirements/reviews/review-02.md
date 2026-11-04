## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-04T13:58:24Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | High | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/security-requirements.md > NFR6.2 | A imutabilidade no banco cobre só `ledger_entry` e `fill`. O design funcional (entities.md, BR5.3, F4) exige que o papel da aplicação ALTERE `paper_order` (CLOSE_PENDING para FILLED), `position`, `paper_account` (OPEN/CLOSING/CLOSED) e `equity_peak`, e que APAGUE `reconciliation_check` (limite dos últimos 100 por pessoa, BR8.1). Nenhum requisito diz quais tabelas e colunas o papel pode ter em `UPDATE`/`DELETE`, nem que uma ordem `FILLED`, `PARTIALLY_FILLED` ou `NOT_FILLED` fica imutável, nem que `equity_peak` só cresce. Quem implementar não sabe o que conceder, e a regra "só INSERT e SELECT" não é aplicável ao núcleo como escrito. `fill` depende só de privilégio, sem gatilho. | Acrescentar uma matriz por tabela com os privilégios exatos do papel da aplicação (INSERT/SELECT/UPDATE por coluna/DELETE). Exigir restrição no banco para a única transição permitida de `paper_order` (CLOSE_PENDING para FILLED, sem tocar nos valores gravados), que `equity_peak` nunca diminua, e gatilho ou revogação equivalente também em `fill`. Cobrir cada linha da matriz com teste usando o papel da aplicação. | Resolved (reviewer) |
| R-02 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/reliability-requirements.md > NFR10.5 e NFR10.2 | A execução roda dentro da transação do ciclo da U5, com a linha do bot e depois a posição e o maior patrimônio travados. Os NFRs exigem ordem de trava sem deadlock, mas não definem tempo máximo de espera por trava (`lock_timeout`) nem `statement_timeout`. Uma trava presa pela marcação, pela reconciliação ou por uma consulta lenta pode estourar o orçamento de 250 ms do NFR3.1 e o ciclo de 30 s sem critério de falha. | Acrescentar `lock_timeout` e `statement_timeout` com valores numéricos para `execute`, `closePositions` e as tarefas. Definir o resultado quando estourar (falha técnica que desfaz o ciclo, NFR10.2) e um teste com trava segurada por outra transação. | Resolved (reviewer) |
| R-03 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/observability-requirements.md > NFR9.1 e NFR10.6 | Por Q4 não há métrica de negócio, e o NFR9.1 só marca `DEGRADED` quando a tarefa falha ou atrasa. Uma reconciliação concluída com `DIVERGENT` não altera nenhum indicador operacional; ela só aparece na tela da própria pessoa. Numa divergência de ledger, o responsável pelo projeto não tem como detectar (nem pelo log estruturado exigido) sem abrir a conta de cada pessoa. Isso é coerente com Q4=B, mas deixa o NFR10.6 ("divergência nunca escondida") sem verificação do lado do operador. | Sem criar métrica de negócio, exigir que a rodada de reconciliação com `DIVERGENT` registre um log de nível `ERROR`/`WARN` com a quantidade (sem pessoa) e que o detalhe do indicador de saúde do NFR9.1 informe o número de divergências da última rodada, ou registrar explicitamente a aceitação desse risco. | Resolved (reviewer) |
| R-04 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/performance-requirements.md > NFR3.1 e NFR3.5 | O NFR3.1 mede 250 ms com carga de 2 ciclos por segundo, sem competição por trava com marcação, fechamento pendente e reconciliação rodando ao mesmo tempo, que são justamente as concorrentes da BR2.8. O NFR3.5 ("até 10 s depois da coleta") não tem carga concorrente nem percentil. A meta pode passar isolada e falhar em uso real. | Exigir que a medição do NFR3.1 rode com as três tarefas ativas ao mesmo tempo, e definir o percentil e as condições do NFR3.5. | Resolved (reviewer) |
| R-05 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/performance-requirements.md > Orçamento da execução e scalability-requirements.md > NFR2.1 | O orçamento de gravação conta 3 lançamentos por preenchimento (TRADE_COST, SLIPPAGE, FEE), enquanto o NFR2.1 dimensiona o volume com até 4 por preenchimento. Os dois números precisam ser coerentes com o catálogo C1 a C15. | Alinhar os dois valores ao máximo real do catálogo (incluindo a compensação) e ajustar o orçamento ou o volume. | Resolved (reviewer) |
| R-06 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/security-requirements.md > NFR6.6 e performance-requirements.md > NFR3.2 | A validação aceita períodos de até 366 dias, mas a meta de p95 ≤ 1 s só é definida para 30 dias de P&L. O pior caso permitido (366 dias com 10 000 preenchimentos) não tem meta nem teste. | Medir o NFR3.2 também com o período máximo permitido, ou reduzir o máximo aceito. | Resolved (reviewer) |
| R-07 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/security-requirements.md > NFR6.4 | `LedgerCompensation` é uma classe que só testes podem usar, mas o texto não diz onde ela fica (conjunto de fontes de teste ou produção). Se estiver no código de produção, a ArchUnit sozinha não impede uso futuro por rota administrativa. | Definir que a classe vive no conjunto de fontes de teste (ou num módulo de teste), ou exigir o teste de arquitetura sobre todo o código de produção. | Resolved (reviewer) |
| R-08 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/nfr-requirements/performance-requirements.md > NFR3.5 | O p95 medido sobre só 20 rodadas seguidas equivale, na prática, ao pior caso (a 19ª ou 20ª amostra). Isso torna a meta mais estrita do que parece e pode dar teste instável. Não bloqueia. | Medir com mais rodadas (por exemplo 60) ou dizer que a meta é "nenhuma rodada acima de 10 s" em 20 rodadas. | New |

> R-01 Reviewer note: O NFR6.2 agora traz a matriz de privilégios por tabela. `paper_order` só muda `status` de `CLOSE_PENDING` para `FILLED`, e o gatilho recusa qualquer outra coluna ou transição. `paper_account` só avança `OPEN`, `CLOSING`, `CLOSED`. O gatilho de `equity_peak` recusa `peak_equity` menor. `fill` tem gatilho de `UPDATE` e `DELETE`. Cada linha da matriz tem um teste com o papel da aplicação. Conferi contra `rules.md`: a BR5.3 só muda `status` da ordem CLOSE para `FILLED`, e a BR8.1 mantém os últimos 100 por pessoa, o que justifica o `DELETE` em `reconciliation_check`.

> R-02 Reviewer note: O NFR10.8 define `lock_timeout` de 2 s e `statement_timeout` de 5 s nas operações e `lock_timeout` de 1 s por conta nas tarefas. O resultado ao estourar está descrito (falha técnica que desfaz o ciclo, ou a conta é pulada na rodada). Há teste com a trava segurada por 3 s.

> R-03 Reviewer note: O NFR9.7 registra log `WARN` com a quantidade de pessoas e de divergências por tipo, sem identificar ninguém. O NFR9.1 põe a mesma contagem na saúde e a deixa `DEGRADED` quando há divergência.

> R-04 Reviewer note: O NFR3.1 mede com as três tarefas rodando nos intervalos normais. O NFR3.5 agora tem percentil (p95), 20 rodadas e a carga concorrente do NFR3.1.

> R-05 Reviewer note: O orçamento de execução e o NFR2.1 concordam: no máximo 3 lançamentos por preenchimento no catálogo, e 4 só como folga de dimensionamento.

> R-06 Reviewer note: O NFR3.2 mede `GET /api/v1/pnl` também com o período máximo de 366 dias do NFR6.6.

> R-07 Reviewer note: O NFR6.4 deixa `LedgerCompensation` no código de produção com visibilidade de pacote. O teste de arquitetura cobre todo o código de produção, e só o código de teste o usa.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Validade de `traceability.json` | PASS: JSON válido | Lido por inspeção, sem erro de sintaxe. |
| Cobertura NFR1 a NFR14 | PASS: 14 linhas. NFR1 é `N/A` com justificativa; as demais são `OK`. | Todo NFR da inception tem linha de cobertura. |
| Alvos `OK` existem nos arquivos da U7 | PASS | Todos os alvos aparecem nos arquivos de requisitos: NFR2.1 a 2.5, 3.1 a 3.6, 4.1 a 4.5, 5.1 a 5.3, 6.1 a 6.8, 7.1, 8.1, 9.1 a 9.7 e 10.1 a 10.8. Os alvos NFR11.1, NFR12.1, NFR13.1 e NFR14.1 estão em `tech-stack-decisions.md`, que não reli nesta passada. |

### Summary

As sete observações da iteração anterior foram tratadas, e nenhuma voltou. A matriz de privilégios por tabela, os tempos limite de trava, a visibilidade da divergência ao operador e as medições sob carga concorrente tornam os requisitos implementáveis sem decisão adicional do arquiteto. Resta uma observação menor sobre a amostragem do NFR3.5.
