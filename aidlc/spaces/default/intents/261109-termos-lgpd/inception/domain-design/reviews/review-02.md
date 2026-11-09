**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-09T13:49:24Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/inception/domain-design/components.md > componente Identity > behaviour (expurgo de desafios e anonimização) | A tabela `login_challenge` guarda `wallet_address` em texto claro e o catálogo só a expurga 24 h depois de expirar. A anonimização (passo 3 do ADR-002) apaga a carteira e revoga sessões, mas não toca nos desafios. Um desafio emitido pouco antes da anonimização mantém o endereço completo por até 24 h e 5 min depois, e o teste obrigatório "a anonimização não deixa o endereço da carteira em nenhuma tabela" falharia nessa janela. | Incluir no passo de anonimização do Identity a remoção, na mesma transação, dos desafios de login daquele endereço. Registrar isso em `behaviour` e no ADR-002. | Resolved (reviewer) |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/domain-design/components.md > entidades LoginChallenge, AuditEvent e Account | Os atributos do catálogo divergem do código existente. `login_challenge` tem a chave `nonce` (não `challengeId`) e as colunas `wallet_address` e `state`. `audit_event` tem `action`, `actor_type`, `target_ref` e `correlation_id` (não `type` nem `walletHmac`). O CHECK de `app_user.status` só admite `ACTIVE` e `DISABLED`, e `ANONYMIZED` exige migração. Hoje o endereço completo também aparece em `details` (`AdminLoginService`). | Alinhar identificadores e atributos ao esquema real, ou marcar `challengeId` e `walletHmac` como colunas novas. Registrar a migração do CHECK de status e a limpeza do endereço em `details` como trabalho do Identity e do Audit. | Resolved (reviewer) |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/domain-design/decisions.md > ADR-005 Decision | "Os dados de teste são descartados antes da abertura" não diz como. O gatilho de `audit_event` recusa UPDATE e TRUNCATE sempre, e só aceita DELETE do papel `audit_retention` para eventos com mais de 12 meses. Eventos de teste recentes não podem ser apagados por esse caminho. `audit_outbox` com carteira completa também permanece até 7 dias depois da entrega. | Definir o mecanismo do descarte (por exemplo recriar o banco ou usar o procedimento do dono do esquema, como na migração 0005) e quem o executa. Fixar no checklist de abertura que a verificação "sem endereço completo" cobre `audit_event` e `audit_outbox`. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/domain-design/components.md > componentes Bots, Feedback e Ledger (depends_on) e Identity (filtro) | Bot, FeedbackEntry e PaperOrder referenciam Account (do Identity), mas `depends_on` está vazio, e o `behaviour` do Bots diz que seus comandos ficam atrás do filtro do Identity. Além disso, a lista de rotas liberadas do filtro cita rotas do Privacy (pedidos de titular), um acoplamento por caminho não declarado. | Declarar em cada módulo que a referência a Account vem do Identity, e declarar o acoplamento do filtro com as rotas do Privacy (por exemplo, por prefixo configurado) para o teste da lista cobri-lo. | Resolved (reviewer) |

> R-01 Reviewer note: A anonimização do Identity agora apaga os desafios de login daquele endereço (components.md, comportamento do Identity e ADR-002 passo 3), e a trilha guarda só carteira mascarada e HMAC.

> R-02 Reviewer note: LoginChallenge (nonce, walletAddress, message, expiresAt, state), AuditEvent (action, actorType, actor, targetRef, correlationId, walletHmac) e a migração do CHECK de app_user.status agora batem com o esquema real; o endereço em `details` do AdminLoginService está declarado.

> R-03 Reviewer note: ADR-005 define o descarte: banco novo criado pelas migrações, sem cópia de dados locais, e checklist com consulta em audit_event e audit_outbox.

> R-04 Reviewer note: As referências a Account estão declaradas em TermsAcceptance, DataSubjectRequest, Bot, FeedbackEntry e PaperOrder, e o acoplamento do filtro com as rotas do Privacy está documentado e coberto por teste da lista.

### Summary

A revisão resolveu os quatro achados anteriores sem criar contradição. O grafo de dependências segue acíclico (Privacy depende de Identity, Bots, Feedback, Ledger e Audit, e Identity depende só de Audit), o YAML está bem formado e as tabelas de resumo e de propriedade são coerentes com o catálogo. O desenho é implementável sem orientação adicional do arquiteto.
