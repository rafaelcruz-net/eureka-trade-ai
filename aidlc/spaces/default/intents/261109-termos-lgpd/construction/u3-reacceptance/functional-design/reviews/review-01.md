## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T06:58:47Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/functional-design/rules.md > BR3.1 e BR3.5; functional-spec.md > F1 passo 3 e F2 passo 4 | A trava compara a versão do aceite MAIS RECENTE com a vigente efetiva (`differs`), enquanto BR3.5 manda devolver 200 sem gravar quando a pessoa já aceitou aquela versão, e a restrição única `(user_id, terms_version)` impede nova inserção. Se a vigente efetiva voltar a uma versão que a pessoa já aceitou antes (o `CurrentTerms` da U1 ignora versões marcadas e ignoradas), o aceite mais recente continua sendo o da versão posterior: a trava segue ativa e o aceite não grava nada. A pessoa fica presa em `/novo-aceite` sem saída. | Definir a trava como "a pessoa NÃO tem aceite da vigente efetiva" (existência de aceite de `currentVersion`), e `TermsStatus.acceptedVersion`/`upToDate` pela mesma regra. Acrescentar um teste de regra para o caso da vigente que volta a uma versão já aceita. | New |
| R-02 | Minor | functional-spec.md > Decisões de implementação (Migração); entities.md > TermsAcceptance.inviteId | A entidade diz que `inviteId` é obrigatório em `INVITE` e ausente em `REACCEPTANCE`, mas a migração só prevê `DROP NOT NULL` e `CHECK` dos dois valores de `source`. Hoje `invite_id` é `NOT NULL` em `0007-identity-access.sql`; sem a regra cruzada, um `INVITE` sem convite ou um `REACCEPTANCE` com convite passa no banco. | Acrescentar `CHECK ((source = 'INVITE') = (invite_id IS NOT NULL))` à migração. | New |
| R-03 | Minor | functional-spec.md > F2 e Decisões de implementação | A nova rota `POST /api/v1/terms/acceptances` não é registrada em `route-registry.yml` (escopo `CLIENT`, com `negativeTest` de tenant), apesar de o teste obrigatório percorrer esse registro e de as rotas existentes exigirem o registro. A trava também não diz o que fazer com uma conta sem nenhum aceite (nenhum aceite = trava ou passa). | Listar a rota no registro com o teste negativo e declarar o comportamento da trava sem nenhum aceite (sugestão: trava). | New |
| R-04 | Minor | rules.md > BR3.11 | O desempenho só cobre a rota de aceite, mas a trava adiciona uma consulta ao banco em toda requisição do convidado fora das rotas liberadas, sem meta nem cache definidos. | Registrar a meta de custo da trava (consulta pelo índice `terms_acceptance_user_idx`) ou a estratégia de cache curto com invalidação no aceite. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| traceability.json | 13 ACs cobertos; BR3.10 sem AC de origem (reverse N/A para L2) | Consistente com os contratos L2 e L3 |
| Conferência do código | `terms_acceptance.invite_id` é `NOT NULL` com `UNIQUE(invite_id)`; `ErrorCode` ainda não tem os dois códigos novos; `CurrentTerms.current()` é a vigente efetiva | A migração e os erros novos são trabalho da U3, sem conflito; o ponto Major vem do contraste entre BR3.1 e BR3.5 |

### Summary

O desenho é coeso e rastreável aos contratos L2 e L3, com um defeito de regra (trava por "mais recente difere" pode prender a pessoa se a vigente voltar a uma versão já aceita) e três pontos menores. Com um Major e nenhum Critical, o desenho é implementável, mas o Major deve ser corrigido antes da geração de código.
