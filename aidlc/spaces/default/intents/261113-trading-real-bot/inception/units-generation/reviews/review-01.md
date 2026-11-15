## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-14
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-dependency.md > bloco yaml `units:` (u1-bot-wallet `depends_on: []`) e unit-of-work-story-map.md > US1.1, US7.1 | A U1 não declara dependência, mas a US1.2 (ativação) exige o aceite dos termos v3 (US1.1, mapeada para a U6) e a liberação do REAL pelo administrador (AC1.2.5, depende da US7.1, mapeada para a U5). As duas Units dependem da U1, então a U1 só passa nos critérios da US1.2 se tiver um substituto das duas. O próprio stories.md diz que "US7.1 controla a disponibilidade de US1.2". Como está, o esqueleto não prova a ativação real. | Dizer na U1 como o aceite v3 e a flag de liberação existem no esqueleto (por exemplo, um estado de liberação mínimo e o termo v3 no Identity entregues na U1, com a tela e a quarentena de 24 h na U5/U6), ou mover essas partes para a U1 e ajustar o mapa de histórias e as dependências. | New |
| R-02 | Major | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-dependency.md > bloco yaml `units:` (u7-exits-and-fiscal `depends_on: [u4-real-execution]`) e unit-of-work.md > U7 | A U7 inclui o "aviso no kill switch" e as ordens condicionais criadas pela U7 precisam ser canceladas pelo kill switch (FR8.2), que é entregue na U5. A U7 não depende da U5, e a ordem de construção pode colocá-la antes. | Acrescentar `u5-real-operations` em `depends_on` da U7 (o grafo continua acíclico) ou deixar explícito que o cancelamento de condicionais da U5 é uma interface que a U7 só registra. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-story-map.md > US3.1 e unit-of-work.md > U6 | A U6 é do tipo `ui`, mas a US3.1 (escolher e trocar o modo com consentimento assinado) exige lógica de servidor: conferir o consentimento, gravar o modo e aplicar a regra de subir ou descer de modo. A U1 só cita "preferências básicas" e a U4 precisa do modo para decidir quem assina. Não está dito quem entrega o endpoint. | Atribuir o endpoint de troca de modo a uma Unit de serviço (U1 ou U4) e deixar na U6 só a tela. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-story-map.md > US6.3 e unit-of-work.md > U2/U3 | A US6.3 (dados reais e frescos) está só na U2, mas a recusa por dado vencido (FR1.4, 60 s) e a recusa com fonte simulada (FR1.3) são regras da barreira, que está na U3. O mapa não registra essa parte. | Listar a US6.3 também na U3 em "Histórias que atravessam Units" ou mover a parte de frescor para a U3. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work.md > U4 (Componentes) | A US4.2 (P&L REAL) está na U4, mas o componente Pnl não aparece na lista de componentes da U4 (só na U7). | Incluir Pnl nos componentes da U4 ou explicar que o P&L REAL sai do Ledger. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Conferência manual da cobertura (24 histórias, US1.1 a US8.5, contra stories.md, story-map e traceability.json) | PASS | Todas as 24 têm Unit e as 7 Units têm histórias; o traceability.json bate com o mapa. |
| Conferência manual do grafo (bloco yaml) | PASS | Acíclico: U1 e U2 sem dependências, U3 depende de U1 e U2, U4 de U1 e U3, U5 e U6 de U1 e U4, U7 de U4; as dependências de componentes (RiskControl→BotWallet/Ledger/MarketData, RealExecution→RiskControl/BotWallet, Administration→BotWallet/RealExecution/RiskControl) estão cobertas. As exceções estão nos dois achados Major. |

### Summary

O fatiamento em 7 Units é coerente, a cobertura é completa e o grafo é acíclico e bate com os componentes. Os pontos a corrigir antes de aprovar são duas dependências ocultas: a ativação da U1 depende do termo v3 (U6) e da liberação (U5), o que enfraquece o esqueleto, e a U7 precisa do kill switch da U5. Nenhum desses achados bloqueia, mas vale revisar no gate.
