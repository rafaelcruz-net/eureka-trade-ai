## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-17
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | construction/u7-exits-and-fiscal/nfr-requirements/reliability-requirements.md e traceability.json (BR5.3b) | O ajuste mais arriscado da revisão do functional-design não tem requisito não funcional: a reconciliação da U5 passa a somar ao saldo do mint o saldo da conta de custódia de cada condicional `OPEN` ou `CANCELLING`, lido na rede. Sem isso, uma ordem aberta vira divergência falsa e pode disparar o bloqueio da U5. Também faltam o comportamento quando a leitura da conta de custódia falha (fail-closed ou `unknown`?) e a fonte de verdade da posição ser o ledger (venda só com posição > 0). BR5.3b nem aparece em `upstream_ids`. | Criar `U7-NFR4.x` com pass/fail: teste de integração em que `OPEN` e `CANCELLING` não geram divergência; leitura de custódia indisponível não gera nem esconde divergência (resultado explícito); a conta de custódia some após `FILLED` ou `CANCELLED` sem divergência; mais uma métrica ou alerta de divergência ligada à condicional. Registrar BR5.3b na rastreabilidade. | Resolved (reviewer) |
| R-02 | Major | construction/u7-exits-and-fiscal/nfr-requirements/security-requirements.md e traceability.json (BR6.9) | A recusa da anonimização com condicional não terminal (`OPEN_CONDITIONALS`) não está coberta. A anonimização toca tabelas append-only e a lógica de dados pessoais está sob teste-antes-do-código (`team.md`). Também não há requisito de que a anonimização não cancele nada sozinha, nem de que a retenção de 5 anos (NFR3.7) conviva com a anonimização. | Criar requisito com teste escrito antes do código: anonimização recusada com `OPEN_CONDITIONALS` (`OPEN`, `CANCELLING`, `PENDING_SIGNATURE`) e liberada com todas finais; nenhum cancelamento é efetuado pela recusa; confirmar o tratamento do endereço da carteira nas condicionais retidas. Incluir BR6.9 na rastreabilidade. | Resolved (reviewer) |
| R-03 | Major | construction/u7-exits-and-fiscal/nfr-requirements/security-requirements.md e traceability.json (BR8.1, BR9.3) | O comportamento do flag `memetrade.real.exits.enabled` não tem requisito. Faltam: padrão desligado em toda rede (como `team.md` exige para o REAL); criação devolve `403 EXITS_DISABLED` e a subida para `AUTO_EXITS` é recusada; leituras, cancelamento, exportação e saque continuam; quem já está em `AUTO_EXITS` permanece. Em BR9.3 o flag na mainnet depende da aprovação do fundador. | Criar requisitos testáveis: teste de configuração padrão (flag off, nada criado, nenhuma assinatura pedida ao KMS); teste das duas metades (criação recusada versus cancelamento, leitura, exportação e saque disponíveis com o flag off); teste da transição de modo. Registrar BR8.1 e BR9.3 na rastreabilidade. | Resolved (reviewer) |
| R-04 | Minor | construction/u7-exits-and-fiscal/nfr-requirements/observability-requirements.md (NFR5.7, BR6.7) | `needsUserCancellation` (BR6.7) só vira verdadeiro após 15 minutos de cancelamento parado, mas nenhum requisito testa a transição nem o aviso para o administrador. O alerta NFR5.7 usa o mesmo limiar, sem vínculo declarado com o campo do contrato. | Acrescentar uma verificação: `needsUserCancellation` falso em 14 min e verdadeiro em 15 min com relógio fixo, alinhado a NFR5.7; ligar BR6.7 e BR8.3 à rastreabilidade. | Resolved (reviewer) |
| R-05 | Minor | observability-requirements.md (NFR5.8) | O alerta "`OPEN` sem leitura há mais de 2 h" (BR5.6) não tem métrica de origem: `real_conditionals{state}` e `real_conditional_fill_lag_seconds` não medem a idade da última leitura bem-sucedida. | Adicionar um medidor, por exemplo `real_conditional_oldest_unread_seconds`, e usar nos dois sistemas de alerta, com teste de paridade. | Resolved (reviewer) |
| R-06 | Minor | performance-requirements.md (NFR3.3, NFR3.4) | O p95 de 3 s e os 10 s de 20 mil linhas são "medição informativa, sem portão". Sem pass/fail, não são testáveis. Isso é aceitável para `team.md` (sem portão de carga) mas deve ficar explícito como meta e não como requisito. | Reclassificar como meta, ou pôr um limite determinístico (por exemplo, memória limitada e linhas em fluxo sem carga completa). | Resolved (reviewer) |
| R-07 | Minor | scalability-requirements.md (NFR3.8) | O índice por pessoa e período existe (`real_ledger_entry_tenant_created_idx`, em tenant e `created_at DESC`), mas um teste de `EXPLAIN` com poucos dados tende a escolher varredura sequencial e ficar instável (`team.md` trata instável como defeito). | Especificar dados semeados suficientes ou desabilitar `enable_seqscan` no teste, e nomear o índice esperado. | Resolved (reviewer) |
| R-08 | Minor | traceability.json | Há regras com aspecto não funcional sem entrada: BR1.1 e BR1.2 (cossinatura), BR3.7 (posição inteira), BR5.2 (`finalized` e dados vindos da rede, nunca da Jupiter), BR5.3 (sem tipo novo nem mudança de CHECK, idempotência `cond:<sig>:fill` e `:fee`), BR6.5, BR7.10 (arquivo não guardado, auditoria sem conteúdo), BR7.11 (exportação disponível com o kill switch), BR4.4 (mesma chave e mesmo corpo devolvem a mesma condicional). NFR1.9 cobre só em parte o BR4.4. | Acrescentar os IDs e os requisitos de BR5.2, BR7.10, BR7.11 e BR4.4, ou registrar a exclusão motivada. | Resolved (reviewer) |
| R-09 | Minor | observability-requirements.md (rodapé) e NFR8.5 | "Logs mascaram endereços e nunca trazem a chave de API" não tem ID nem verificação própria (NFR1.5 cobre só a chave). O spec `e2e/real-exports` está bem alinhado ao padrão `real-*` existente (`real-bot-wallet`, `real-ops`, `real-swap`, `real-ui`). | Dar um ID ao mascaramento de endereços, com teste de log. | Resolved (reviewer) |
| R-10 | Minor | construction/u7-exits-and-fiscal/nfr-requirements/reliability-requirements.md > U7-NFR4.8 | O requisito diz que a falha da leitura de custódia deixa a reconciliação em `DEFERRED`. O código atual de `ReconciliationResult` já tem `UNAVAILABLE` para rede não lida e reserva `DEFERRED` para algo em trânsito. Isso pode levar a um mapeamento diferente na implementação. | Confirmar na geração de código qual dos dois resultados cobre a falha e alinhar o texto. Em qualquer caso, nunca `DIVERGENT`. | New |
| R-11 | Minor | traceability.json > BR7.11 | BR7.11 exige exportação disponível com o kill switch, sem liberação e com o REAL pausado, mas o alvo é só o flag desligado (U7-NFR4.10) e U7-NFR4.7. | Acrescentar à U7-NFR4.10 ou ao NFR1.3 um teste da exportação com kill switch ligado e sem liberação. | New |

> R-01 Reviewer note: U7-NFR4.8 cobre a custódia na reconciliação, com testes antes do código e IT com RPC gravado. Ver o Minor novo sobre `DEFERRED` versus `UNAVAILABLE`.

> R-02 Reviewer note: U7-NFR4.9 cobre `409 OPEN_CONDITIONALS`, sem cancelar nem apagar, com teste antes do código.

> R-03 Reviewer note: U7-NFR4.10 cobre padrão desligado, `403 EXITS_DISABLED`, subida de modo recusada, quem já está em `AUTO_EXITS` e leituras, cancelamento, exportação e saque ativos.

> R-04 Reviewer note: U7-NFR4.11 testa 14:59 e 15:00 com relógio fixo, e NFR5.7 usa o mesmo limiar de 900 s.

> R-05 Reviewer note: U7-NFR5.6b é o medidor de origem do alerta NFR5.8.

> R-06 Reviewer note: NFR3.3 e NFR3.4 têm falha explícita (10 s e 3 s, 20 execuções, escrita em fluxo).

> R-07 Reviewer note: NFR3.8 nomeia o índice, testa 366 e 365 dias e retirou o `EXPLAIN`.

> R-08 Reviewer note: A rastreabilidade tem 24 BRs, incluindo BR4.4, BR5.2, BR5.3, BR6.5, BR7.10 e BR7.11. BR1.1, BR1.2 e BR3.7 continuam sem entrada, mas pertencem à criação e já estão sob os testes de BR1.9 e da política. Aceitável.

> R-09 Reviewer note: U7-NFR5.11 cobre o mascaramento de endereços, a chave de API e o conteúdo da exportação, com teste de captura de log.

### Validation Tool Results

| Tool | Result | Interpretation |
| --- | --- | --- |
| Leitura dos arquivos revisados contra `review-01.md` | R-01 a R-09 resolvidos | Os três Major e os seis Minor anteriores estão tratados |
| `ReconciliationResult` e `ReconciliationPolicy` | `DEFERRED` e `UNAVAILABLE` existem | Origem do Minor novo, não bloqueante |

### Summary

As lacunas de BR5.3b, BR6.9 e BR8.1/BR9.3 foram fechadas com requisitos testáveis, e os Minor anteriores foram resolvidos. Restam dois ajustes Minor, que não bloqueiam; o conjunto está pronto para o design.
