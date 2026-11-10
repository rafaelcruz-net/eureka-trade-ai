## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T10:43:57Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/functional-design/rules.md > BR4.5, BR4.9, BR4.11 | A máquina de estados tem um beco sem saída. O administrador pode levar um pedido EMAIL de Acesso ou Exclusão ligado a uma conta de RECEIVED direto para IN_PROGRESS (BR4.5), mas a verificação só pode ser iniciada em RECEIVED (BR4.11). Depois disso `identityVerified` nunca vira verdadeiro, então a liberação do resumo (BR4.9) e a anonimização da U5 ficam impossíveis, e a única saída é recusar o pedido. O diagrama de estados não mostra IN_PROGRESS para AWAITING_VERIFICATION. | Fechar o caminho: ou RECEIVED para IN_PROGRESS é recusado (409 INVALID_TRANSITION) em EMAIL de ACCESS ou DELETION ligado a conta enquanto `identityVerified` for falso, ou a verificação pode ser iniciada também em IN_PROGRESS. Atualizar BR4.5, BR4.11, o diagrama e o contrato P2. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/functional-design/rules.md > BR4.10 e functional-spec.md > Decisões de implementação (J1 `PersonDataPort`) | O registro de pedido por e-mail recebe só o `userId` no corpo (P2), mas BR4.10 exige que Privacy descubra se a conta existe, em que tenant está e se foi anonimizada, e que o `tenantId` do pedido venha dela. A J1 do contrato só tem `personSummary(Principal)`, `personSummary(tenantId, userId)` (exige o tenant que não se tem), `verifyAccountSignature(userId, ...)` (sem tenant) e `anonymizeAccount`. Nenhum método resolve a conta por `userId`. O Principal do administrador tem tenant próprio, e o registro de rotas só admite tenant vindo do cliente na emissão de convites. O desenvolvedor teria de ler a tabela de contas do Identity ou inventar o método. | Definir o método novo da J1 (por exemplo `Optional<AccountRef> findAccount(UUID userId)` com tenant, estado e carteira mascarada) em `PersonDataPort`, registrar a mudança no contrato e dizer em BR4.10 e BR4.12 de onde vem o tenant em cada rota administrativa (inclusive a conferência da assinatura). | Resolved (reviewer) |
| R-03 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/functional-design/rules.md > BR4.2; functional-design-questions.md > Q2 | O índice único parcial de Q2 é por (tenant, usuário, tipo) sempre que há usuário, o que inclui pedidos EMAIL ligados a conta. Mas BR4.2 limita o gatilho a "criação de pedido com conta" na rota do convidado, e o P2 `adminRegisterEmailRequest` só declara 201 e 400. Registrar um pedido EMAIL com `userId` que já tem pedido aberto do mesmo tipo (inclusive um pedido ACCOUNT) estoura o índice sem resposta definida, e a regra pode virar 500 ou duplicata. | Declarar o comportamento: o registro EMAIL com conta também devolve 409 DATA_REQUEST_ALREADY_OPEN, com o mesmo escopo de BR4.2, e acrescentar o 409 à rota de registro de P2. Ou excluir o EMAIL do índice e dizer isso. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/functional-design/rules.md > BR4.12, BR4.13 | Faltam as regras de concorrência e precedência. A confirmação da assinatura e o job de 15 minutos que expira a verificação podem correr juntos, e nada manda a transição ser condicional ao estado PENDING. Quando o administrador recusa um pedido em AWAITING_VERIFICATION, a verificação continua PENDING, e uma confirmação posterior não tem a precedência definida entre DATA_REQUEST_CLOSED e VERIFICATION_*. | Dizer que confirmação e expiração fazem atualização condicional por estado (uma só vence), que a recusa manual encerra a verificação como EXPIRED, e a ordem de precedência dos erros 409. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/functional-design/rules.md > BR4.10 versus AC5.6.9 | BR4.10 recusa (400) o registro com conta anonimizada, mas AC5.6.9 pede recusar com orientação um pedido de conta já anonimizada. O administrador só consegue registrar esse caso sem ligar a conta, e a ligação, útil para a auditoria, se perde. | Registrar a decisão: aceitar conta ANONYMIZED no registro (sem verificação possível) ou documentar que se registra sem conta. | Resolved (reviewer) |
| R-06 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/functional-design/functional-spec.md > Decisões de implementação (Mensagem de verificação) | O texto diz que o `LoginVerificationService` "recusa uma mensagem com essa linha". No código esse serviço só valida o desafio emitido por nonce e compara a mensagem exata, então uma mensagem de verificação nunca chega a ser aceita nem precisa de filtro. A mudança descrita é desnecessária e pode confundir a implementação. | Trocar pelo teste que prova AC5.6.7 (a assinatura de verificação enviada ao login é recusada) ou explicar qual mudança no serviço é de fato necessária. | Unresolved |
| R-07 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/functional-design/rules.md > BR4.11 logic | A regra manda a rota de login recusar a mensagem de verificação, mas a spec diz que o login só aceita desafios emitidos por nonce e não muda. Quem implementar pode alterar o login sem necessidade. | Trocar a frase por: o login não muda e um teste prova que a assinatura de verificação enviada ao login é recusada. | New |
| R-08 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/contract-design/contract-summary.md > bloco J1 | O bloco J1 ainda não lista `findAccount`, só `verifyAccountSignature`. A spec já prevê a atualização no mesmo commit. | Garantir a atualização do bloco J1 na Code Generation. | New |

> R-01 Reviewer note: BR4.5 e o diagrama de estados agora recusam RECEIVED para IN_PROGRESS em EMAIL de ACCESS ou DELETION com conta ativa (409 INVALID_TRANSITION). A verificação é o único caminho.

> R-02 Reviewer note: BR4.10 e a spec definem a J1 nova `findAccount(userId)`, que devolve tenant e estado, sem leitura de tabela do Identity.

> R-03 Reviewer note: BR4.2 estende o 409 DATA_REQUEST_ALREADY_OPEN ao registro administrativo, e a spec registra a mudança aditiva em P2.

> R-04 Reviewer note: BR4.12 define a precedência (DONE ou REFUSED vence), a atualização condicional em uma transação e a recusa manual que marca a verificação como EXPIRED.

> R-05 Reviewer note: BR4.10 aceita conta ANONYMIZED, bloqueia a verificação e orienta a recusa com resposta modelo (AC5.6.9).

> R-06 Reviewer note: A spec diz que o login não muda, mas BR4.11 ainda afirma que "a rota de login recusa essa mensagem". Faltou alinhar o texto da regra à spec.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Nenhuma ferramenta de validação listada | não executada | Verificação feita por leitura cruzada de spec, regras, entidades e contratos. |

### Summary

Os três Majors e os dois Minors do ciclo anterior foram corrigidos. Resta uma redundância textual em BR4.11 (R-06) e uma atualização de contrato pendente, ambas Minor e sem bloquear a implementação.
