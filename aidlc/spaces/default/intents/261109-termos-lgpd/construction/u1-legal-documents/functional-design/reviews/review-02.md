**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-09T15:41:00Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/rules.md > BR1.1, BR1.3, BR1.8, BR1.12; entities.md > LegalVersion (privacy, retention) | Quando a versão vigente efetiva é a v1 (legacy), que não tem política, resumos nem retention.tsv, a resposta de /legal/current e a página /privacidade não têm definição. O contrato L1 exige privacy, retention e resumos de 5 a 7 itens. Esse é o estado esperado em produção, porque a v2 fica sem revisão até a assessoria. Resultado: AC2.1.1 (versão, resumo e índice) e AC2.5.1 (e-mail do encarregado na política) ficam sem comportamento definido. | Definir o que a API devolve e o que /privacidade mostra quando a vigente é legacy (por exemplo, política ausente com aviso explícito, ou campos opcionais no contrato). Alinhar L1 e o fallback de retenção. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/functional-spec.md > F1 passo 4; rules.md > BR1.1 e BR1.6 | Os campos de implantação são globais ao ambiente, não por versão. Com require-publishable ligado e algum campo faltando, a v1 legacy também fica não publicável. Não há versão ativável, e a API não sobe. Isso contradiz AC1.3.3 ("a versão anterior continua vigente"). Não está dito se a regra de publicável vale para versões legacy nem como "ativável anterior" é calculada. | Dizer explicitamente que legacy é isenta de publishable (ou aceitar a falha na subida e ajustar o AC). Definir a ordem de "mais nova anterior" (effectiveAt ou nome da versão, já que v10 ordena antes de v2 como texto). | Resolved (reviewer) |
| R-03 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/rules.md > BR1.1; functional-spec.md > F1 | Nenhuma regra diz que o fluxo de aceite existente (CurrentTerms, que grava termsVersion, e o aceite no convite) passa a usar a versão vigente efetiva. Hoje TermsCatalog.current() devolve a versão marcada. Sem essa regra, um convite aceitaria a v2 não revisada enquanto /privacidade mostra a v1. O mesmo vale para find(version) aceitando uma versão ignorada. | Acrescentar uma regra: CurrentTerms e find passam a respeitar a ativação (vigente efetiva e histórico), com teste de que uma versão ignorada nunca é aceitável nem exibida como vigente. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/rules.md > BR1.1 e functional-spec.md > F1 passo 5; inception/contract-design/contract-summary.md > L1 Comportamento e 503 | O contrato L1 diz que, sem versão ativável, a API sobe com a anterior, e prevê 503. O design faz a API falhar na subida, então o 503 nunca ocorre. | Alinhar o L1 (remover o 503 ou o comportamento "sobe com a anterior") à decisão de Q1. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/rules.md > BR1.9 | "Versão futura" é definida por effectiveAt depois da vigente, mas BR1.1 não usa effectiveAt na escolha. Uma versão marcada com data futura não tem tratamento. | Definir a referência (instante atual) e o tratamento de effectiveAt futuro na ativação. | Resolved (reviewer) |
| R-06 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/traceability.json > AC1.1.2, AC2.1.3; rules.md > BR1.5, BR1.13 | AC1.1.2 aponta para BR1.8, que é retenção e não cobre as seções. AC2.1.3 (p95 em até 2 s com 20 acessos) vai para BR1.13, mas só cita spec de ponta a ponta, sem teste de carga. BR1.5 não nomeia a lista de termos proibidos de AC1.1.3. | Corrigir o mapeamento, indicar como o p95 é verificado e citar a lista versionada em BR1.5. | Resolved (reviewer) |
| R-07 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/rules.md, entities.md, frontend-components.md | Os artefatos citam detalhes de tecnologia: nomes de arquivo (retention.tsv, privacy.txt), chave require-publishable, .notice-error, NEXT_PUBLIC_*, CORS. A chave require-publishable também não está em nenhuma entidade. | Mover esses detalhes para o design de código, ou deixá-los como decisão registrada. Modelar o interruptor de produção como atributo da configuração. | Resolved (reviewer) |
| R-08 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/functional-design/functional-spec.md > Regras (resumo derivado de `rules.md`) | O resumo diz "BR1.1 a BR1.14", mas `rules.md` tem BR1.1 a BR1.16 (BR1.15 e BR1.16 faltam). | Atualizar o resumo para BR1.1 a BR1.16 e citar a legacy sem política e o aceite pela vigente efetiva. | New |

> R-01 Reviewer note: BR1.3, BR1.8 e BR1.15 definem o comportamento com a vigente legacy sem política, resumos ou retenção (resposta com campos ausentes, tabela vazia, páginas F3 passo 6).

> R-02 Reviewer note: BR1.1 e F1 definem "ativável" com a legacy isenta de publicável, e a anterior é ordenada por data de vigência, não por nome. Sempre existe uma versão ativável (a legacy) ou a subida falha com motivo.

> R-03 Reviewer note: BR1.16 e F1 passo 8 fazem o aceite existente e a busca usarem a vigente efetiva e o histórico. A busca de versão ignorada ou futura não encontra nada.

> R-04 Reviewer note: A falha na subida e a ausência de 503 estão registradas em "Decisões de implementação registradas", com o ajuste entrando no L1 no mesmo commit do OpenAPI.

> R-05 Reviewer note: A vigência futura é tratada em BR1.1(b) e no passo 4 de F1.

> R-06 Reviewer note: A rastreabilidade mapeia AC1.1.2 e AC2.1.3 para BR1.5 e BR1.13, e o p95 tem um cenário de carga definido.

> R-07 Reviewer note: Os detalhes de tecnologia foram para uma seção própria, e a exigência de publicação é modelada em `LegalSettings`.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| leitura de traceability.json | todos os 17 ACs com alvo existente em `rules.md`; reversos justificados | Sem referência quebrada. |

### Summary

A revisão resolveu as pendências de ativação, legacy e aceite, sem nova contradição estrutural. Resta só o resumo de regras desatualizado em `functional-spec.md`, que não bloqueia.
