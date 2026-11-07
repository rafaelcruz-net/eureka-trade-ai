## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-06T13:54:56Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-design/security-design.md > Controles, linhas NFR6.2 e NFR4.2 | O design acrescenta `no-restricted-globals` (EventSource) e `no-restricted-syntax` (`new EventSource`, `Number(`, `parseFloat(`, `+valor`) ao `apps/web/eslint.config.mjs`, mas não diz em qual bloco de `files`. No config atual (flat config) os blocos de `src/features/pnl`, `src/features/bots` e `src/app/pnl\|bots` já definem essas duas regras, e o bloco mais específico substitui as opções do mais geral, não as soma. Se a proibição de `EventSource` ficar num bloco global, ela deixa de valer nesses diretórios; se ficar só no bloco do painel, o requisito NFR6.2 (nada de `EventSource` em todo `apps/web/src`) fica sem cobertura. A busca em `apps/web` não acha `EventSource` hoje, então não há violação, mas o controle não está garantido por lint como o design afirma. | Especificar o bloco de `files` de cada regra: proibir `EventSource` num bloco com `src/**` e repetir `EventSource` e o seletor `new EventSource` nas listas dos blocos existentes de pnl e bots, ou manter o teste estático de NFR6.2 (busca no código-fonte) como segunda garantia. Dizer também que a regra decimal vale para `src/features/dashboard/**` e `src/app/page.tsx` (ou o caminho real da formatação do painel). | New |
| R-02 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-design/security-design.md > Teto de canais por pessoa | O design diz "ao abrir, o canal conta os canais abertos e recusa o 6º", mas não diz que contar e registrar o canal é uma operação atômica. `DashboardMarkPoller` guarda os canais num `ConcurrentHashMap` (linha 64) e `open` os remove em `onCompletion`/`onTimeout`/`onError`; duas aberturas simultâneas da mesma pessoa podem passar pela contagem e chegar a 6 ou 7. O teste descrito abre 6 canais em sequência e não pegaria isso. | Dizer que a contagem e o registro acontecem juntos (por exemplo, contador por pessoa com incremento condicional) e acrescentar ao teste de integração aberturas concorrentes da mesma pessoa. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-design/security-design.md > Teto de canais por pessoa, "Fechar um canal libera a vaga" | Numa queda de rede o servidor só libera a vaga quando uma escrita falha ou o tempo esgota. A reabertura automática de BR5.3 pode então receber `429` do próprio canal fantasma da mesma aba, e uma pessoa com 5 abas ficaria sem canal por algum tempo. O design não trata isso. | Registrar o comportamento esperado (a espera crescente cobre o caso) e o tempo máximo até a vaga ser liberada pelo heartbeat, ou contar só canais com escrita recente; cobrir com um teste de reabertura depois de queda abrupta. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| aidlc engine sensor-traceability (nfr-design, traceability.json) | pass: true, gaps, orphans, missing_from_table, invalid_entries e invalid_targets vazios, findings_count 0 | Os 20 IDs NFR de entrada estão cobertos e os alvos existem. |

### Summary

O design é implementável: os controles de segurança cobrem NFR2.1 a NFR7.1 com testes concretos, e o teto de 5 canais é compatível com `DashboardMarkPoller` e `DashboardStreamController`. O ponto principal é a regra de lint de NFR6.2 e NFR4.2, que sem indicar o bloco de `files` pode ser anulada pelos blocos já existentes no `eslint.config.mjs`. Os outros dois achados são lacunas menores no teto de canais.
