## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T14:20:38Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u5-anonymization/nfr-requirements/performance-requirements.md > NFR3.2 (e NFR5.3 em reliability-requirements.md) | NFR3.2 afirma que a anonimização usa só acessos indexados e verifica com `EXPLAIN` e `enable_seqscan = off`, mas a lista omite o apagamento dos desafios do endereço (F2 passo J1). A tabela `login_challenge` só tem índices em `nonce` (PK) e `expires_at` (0001-identity.sql); não existe índice em `wallet_address`. Esse apagamento por endereço fará varredura sequencial, então a verificação como escrita falha ou passa por engano, e a migração prevista em NFR5.3 não lista nenhum índice novo | Incluir `login_challenge` por `wallet_address` na lista de NFR3.2. Decidir entre um índice novo na migração 0015 (e então acrescentá-lo a NFR5.3 e a tech-stack-decisions) ou apagar por outro caminho já indexado. Dizer qual no requisito | New |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u5-anonymization/nfr-requirements/scalability-requirements.md > NFR3.3 e NFR3.4 | O NFR3 do inception é desempenho (p95 das páginas e rotas). Concorrência e ordem de travas são integridade e confiabilidade (NFR5), mas aparecem como NFR3.3 e NFR3.4, e `traceability.json` as cobre sob NFR3 | Reclassificar NFR3.3 e NFR3.4 sob NFR5 (ou como subitens de NFR5.x) e ajustar `traceability.json` | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u5-anonymization/nfr-requirements/reliability-requirements.md > NFR5.2 e NFR5.4 | NFR5.2 testa falha "em cada um dos quatro passos", mas o functional-spec F2 tem cinco pontos de escrita (J2, J3, J1, J6 e a conclusão do pedido com `DATA_REQUEST_TRANSITIONED`). NFR5.4 fala em "`lock_timeout` existente" sem o valor, então o teste não tem limite mensurável | Listar os passos exatos que recebem falha injetada (incluindo J6 e a conclusão do pedido) e citar o valor configurado do `lock_timeout` ou a propriedade que o define | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u5-anonymization/nfr-requirements/reliability-requirements.md > NFR5.3 | O functional-spec exige que cada changeset tenha rollback válido só antes da primeira anonimização e que o `CHECK` de Base58 valha só com endereço presente. NFR5.3 não cobre rollback nem esses dois `CHECK` | Acrescentar a NFR5.3 a verificação de rollback por changeset e dos `CHECK` de endereço e provedor | New |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u5-anonymization/nfr-requirements/observability-requirements.md > NFR8.1 a NFR8.3 | O contador `memetrade_accounts_anonymized_total` conta só sucessos; a falha (rollback, `503` por `lock_timeout`) só aparece em log. Não há sinal agregável nem alertável para falhas repetidas, embora o pedido fique preso em `IN_PROGRESS` | Definir se um contador de falhas (sem rótulo de pessoa) entra em NFR8, ou registrar explicitamente que só o log cobre a falha e por quê | New |
| R-06 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u5-anonymization/nfr-requirements/reliability-requirements.md > NFR4.1 | O NFR4 do inception inclui "Pular para o conteúdo", foco visível, títulos h1 a h3 em ordem e regiões de página. NFR4.1 cobre só o diálogo T8, e `traceability.json` marca NFR4 como OK sem dizer que o restante é herdado das telas existentes | Declarar em NFR4.1 ou em `traceability.json` que os demais itens de NFR4 são herdados do layout administrativo existente (ou acrescentá-los) | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Verificação de IDs contra `requirements.md` | NFR1, NFR2, NFR4, NFR5, NFR8 e NFR9 herdados corretamente. NFR7 (expurgos) N/A e NFR6 (segredos) N/A são coerentes com o functional design | OK, salvo a classificação de NFR3.3 e NFR3.4 |
| Conferência de código (`0001-identity.sql`, `0010`, `0011`) | Índices existem para `user_session.user_id`, `wallet.user_id` (UNIQUE), `bot` por dono e `feedback_entry` por pessoa; faltam em `login_challenge.wallet_address` | Base do achado Major |

### Summary

Os requisitos são mensuráveis, herdam os IDs do inception e não contradizem o functional design. O ponto a corrigir é o NFR3.2: o apagamento dos desafios por `wallet_address` não tem índice e quebraria a verificação de plano indexado. Os demais itens são ajustes de classificação e precisão, sem bloquear.
