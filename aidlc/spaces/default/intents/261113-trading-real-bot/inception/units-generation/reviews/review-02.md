## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-14
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-dependency.md > bloco yaml `units:` (u1-bot-wallet `depends_on: []`) e unit-of-work-story-map.md > US1.1, US7.1 | A U1 não declara dependência, mas a US1.2 (ativação) exige o aceite dos termos v3 (US1.1, mapeada para a U6) e a liberação do REAL pelo administrador (AC1.2.5, depende da US7.1, mapeada para a U5). As duas Units dependem da U1, então a U1 só passa nos critérios da US1.2 se tiver um substituto das duas. O próprio stories.md diz que "US7.1 controla a disponibilidade de US1.2". Como está, o esqueleto não prova a ativação real. | Dizer na U1 como o aceite v3 e a flag de liberação existem no esqueleto (por exemplo, um estado de liberação mínimo e o termo v3 no Identity entregues na U1, com a tela e a quarentena de 24 h na U5/U6), ou mover essas partes para a U1 e ajustar o mapa de histórias e as dependências. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-dependency.md > bloco yaml `units:` (u7-exits-and-fiscal `depends_on: [u4-real-execution]`) e unit-of-work.md > U7 | A U7 inclui o "aviso no kill switch" e as ordens condicionais criadas pela U7 precisam ser canceladas pelo kill switch (FR8.2), que é entregue na U5. A U7 não depende da U5, e a ordem de construção pode colocá-la antes. | Acrescentar `u5-real-operations` em `depends_on` da U7 (o grafo continua acíclico) ou deixar explícito que o cancelamento de condicionais da U5 é uma interface que a U7 só registra. | Resolved (reviewer) |
| R-03 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-story-map.md > US3.1 e unit-of-work.md > U6 | A U6 é do tipo `ui`, mas a US3.1 (escolher e trocar o modo com consentimento assinado) exige lógica de servidor: conferir o consentimento, gravar o modo e aplicar a regra de subir ou descer de modo. A U1 só cita "preferências básicas" e a U4 precisa do modo para decidir quem assina. Não está dito quem entrega o endpoint. | Atribuir o endpoint de troca de modo a uma Unit de serviço (U1 ou U4) e deixar na U6 só a tela. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work-story-map.md > US6.3 e unit-of-work.md > U2/U3 | A US6.3 (dados reais e frescos) está só na U2, mas a recusa por dado vencido (FR1.4, 60 s) e a recusa com fonte simulada (FR1.3) são regras da barreira, que está na U3. O mapa não registra essa parte. | Listar a US6.3 também na U3 em "Histórias que atravessam Units" ou mover a parte de frescor para a U3. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/unit-of-work.md > U4 (Componentes) | A US4.2 (P&L REAL) está na U4, mas o componente Pnl não aparece na lista de componentes da U4 (só na U7). | Incluir Pnl nos componentes da U4 ou explicar que o P&L REAL sai do Ledger. | Resolved (reviewer) |
| R-06 | Minor | aidlc/spaces/default/intents/261113-trading-real-bot/inception/units-generation/traceability.json > coverage US1.1, US3.1, US6.3 | O alvo de cobertura continua em uma só Unit (U6, U6, U2), embora o servidor e a recusa estejam em U1, U4 e U3. O mapa em Markdown explica a divisão, mas o JSON não a registra. | Opcional: registrar as Units secundárias no traceability.json ou manter o JSON só com o dono principal, como está. | New |

> R-01 Reviewer note: A U1 agora traz o catálogo de termos v3 com recusa da ativação sem o aceite e a marca RealRelease mínima (só o fundador, pelo perfil de teste). U6 entrega a tela de aceite e U5 a liberação com quarentena, ambas sobre essas peças. A U1 continua sem dependências e Identity e RealRelease estão cobertos pelos componentes. O mapa de histórias e a ordem interna da U1 refletem a mudança.

> R-02 Reviewer note: `u7-exits-and-fiscal` agora depende de `u4-real-execution` e `u5-real-operations` no yaml, na seção da U7 e no mermaid. Sem ciclo.

> R-03 Reviewer note: O servidor da US3.1 (troca de modo, consentimento, nonce, gravação na BotWallet, aviso ao TxSigner) está na U4; a U6 fica só com a tela e já depende da U4.

> R-04 Reviewer note: A recusa da US6.3 está na descrição da U3, no mapa de histórias e em "Histórias que atravessam Units".

> R-05 Reviewer note: Pnl está nos componentes da U4.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Conferência manual do grafo (bloco yaml e mermaid) | PASS | Acíclico: U1 e U2 raízes; U3 de U1, U2; U4 de U1, U3; U5 e U6 de U1, U4; U7 de U4, U5. O yaml, o mermaid e o texto batem; nenhuma correção criou aresta nova de volta (o U5 ampliar a marca da U1 e o U6 usar o servidor da U4 vão no sentido das dependências). |
| Conferência manual da cobertura (24 histórias, US1.1 a US8.5) | PASS | Todas têm Unit no mapa e no traceability.json; as 7 Units têm histórias. |
| Esqueleto (U1) | PASS | Fatia integrada sem depender de ordens, barreira, dados de mercado, U5 ou U6; a ativação real fica provada com termos v3 e liberação mínimos. |

### Summary

As cinco correções resolvem os achados anteriores sem criar ciclos nem inconsistências entre unit-of-work, grafo, mapa e componentes. Resta só uma observação menor sobre o traceability.json; o fatiamento, a cobertura e o esqueleto estão prontos para aprovação.
