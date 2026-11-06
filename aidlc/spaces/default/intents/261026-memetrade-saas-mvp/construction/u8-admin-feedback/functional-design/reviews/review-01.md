## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-05T17:56:00Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-00 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/functional-spec.md > review findings table | The reviewer's findings table could not be read, so its rows are not listed here: the findings report could not be read: the Prior findings table header is, ID, Severity, Location, Finding, Required action, Status, ; it must be, ID, Now, Severity, Note, . Write the whole review again with the required Prior findings and New findings tables | Address the reviewer's findings as written below this table. | Unresolved |

**The reviewer's findings, as written:**

> **Prior findings**
>
> | ID | Severity | Location | Finding | Required action | Status |
> |---|---|---|---|---|---|
>
> **New findings**
>
> | ID | Severity | Location | Finding | Required action | Status |
> |---|---|---|---|---|---|
> | R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/rules.md > BR2.1, BR2.3, BR2.5 | A BR2.1 grava e confirma o estado ON e só depois chama `applySystemPause`. Se o processo cair, ou a chamada lançar exceção (não apenas devolver `failed`), nesse intervalo, o `KillSwitchState` fica ON, a faixa "Kill switch ACIONADO" aparece, mas a U5 não recebeu a pausa e os bots continuam operando. A BR2.3 só trata `SystemPauseResult.failed`, e a BR2.5 só roda com `pendingBots > 0`, que nesse caso continua 0. É uma falha silenciosa no controle de risco do AC7.2.2. O mesmo vale para OFF (bots ficam recusados com a pausa de sistema ainda ligada). | Definir que exceção da chamada à U5 grava `pendingBots` como "desconhecido" (por exemplo, um valor sentinela ou um campo `reconcileNeeded`). Fazer o trabalho da BR2.5 comparar `BotAdministration.systemPaused()` com `KillSwitchState.state` a cada execução, e não só olhar `pendingBots`, e repetir a operação do estado atual quando divergirem. Acrescentar cenário de borda e teste para "queda entre o commit e a chamada". | open |
> | R-02 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/functional-spec.md > F4 e rules.md > BR2.8 | A BR2.8 diz que a trava da linha ordena os pedidos concorrentes, mas a trava só vale até o commit. As chamadas `applySystemPause` e `liftSystemPause` acontecem depois, fora da trava, e cada uma grava o estado da U5 em transação própria. Cenário: ON confirma (v1), OFF confirma (v2), e o `liftSystemPause` da segunda requisição roda antes do `applySystemPause` da primeira. A U5 termina com a pausa ligada enquanto a U8 mostra OFF e `pendingBots = 0`, e nada corrige isso. O trabalho de reparo da BR2.5 também chama `lift` ou `apply` fora de qualquer ordenação com as requisições. | Serializar as chamadas à U5 na mesma ordem das versões, por exemplo com trava consultiva distribuída (a mesma do trabalho de reparo) mantida durante a chamada. Alternativa: depois de cada chamada, reler `version` e, se mudou, repetir a operação do estado novo. A reconciliação por `systemPaused()` da R-01 também cobre a divergência final. Descrever isso na BR2.8 e em F4. | open |
> | R-03 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/functional-spec.md > Acréscimos a contratos e rules.md > BR3.3, BR6.6 | Os acréscimos ao C8 estão incompletos. A BR3.3 diz que qualquer parte do estado do sistema pode voltar como `unknown`, mas o C8 declara `guestCount`, `inviteCounts`, `botCountsByState`, `killSwitch`, `sources` e `auditOutboxPending` como obrigatórios e inteiros; só `rejectedOrdersToday` ganha `integer ou unknown`. A BR6.6 manda exibir o nome do tenant no feedback, mas o item do C8 só traz `tenantId`. A `GET /admin/kill-switch` devolve `pendingBots` (BR2.7), e isso consta nos acréscimos só de passagem. | Listar nos acréscimos o formato de `unknown` com motivo para cada campo do `system-status` (por exemplo, união discriminada), o campo `tenantName` no item de feedback (ou a regra de resolvê-lo no cliente com `GET /admin/tenants`) e `pendingBots` na resposta do GET e do PUT. | open |
> | R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/rules.md > BR1.5, e functional-spec.md > Eventos de auditoria novos | O evento novo `ACCESS_DENIED` duplica o `REQUEST_FORBIDDEN`, que a U1/U3 já grava em toda recusa 403 (`AuditedForbiddenRequestRecorder`, `AdminRouteFilter`, e o caso de token de administrador em rota de convidado em `AdminAuthorization`). Pode haver dois nomes para o mesmo fato, ou dois eventos por recusa. | Trocar `ACCESS_DENIED` por `REQUEST_FORBIDDEN`, estendendo-o se faltar algum campo, ou dizer que a U8 não grava evento próprio nas recusas da BR1.5 e da BR4.1 além do que a U3 já grava. | open |
> | R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/rules.md > BR4.2 e BR6.7 | A BR4.2 inclui o "expurgo do feedback" entre as ações com ator ADMIN, e a BR6.7 e a tabela de eventos dizem `FEEDBACK_PURGED` com ator `system`. | Tirar o expurgo da lista da BR4.2, ou dizer que ele é a exceção com ator `system`. | open |
> | R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/rules.md > BR1.2 e entities.md > AdminAccount | A criação da AdminAccount na primeira entrada não diz o que acontece se a carteira configurada já está vinculada a uma conta BETA_USER, por exemplo se foi adicionada à lista depois de entrar como convidado. A unicidade da carteira faria a criação falhar. | Definir o resultado (por exemplo, 403 com `reason` próprio e evento, sem converter a conta) e incluir um teste. | open |
> | R-07 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/rules.md > BR6.3 | O limite de 10 envios por hora é "contar e depois inserir". Pedidos concorrentes da mesma pessoa podem passar de 10. Também não está dito se uma resposta 429 fica guardada pela idempotência (o reenvio com a mesma chave depois da janela deveria poder passar). | Serializar a contagem por pessoa (trava por usuário ou índice/contador) ou aceitar o excesso de forma explícita. Dizer que 429 e 400 não ficam guardados pela idempotência. | open |
> | R-08 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u8-admin-feedback/functional-design/frontend-components.md > Integração com a API e rules.md > BR1.3 | O cookie de renovação `SameSite=Strict` só funciona com o `apps/admin` no mesmo site da API, como na regra do projeto para os dois apps web. O endereço do app do administrador é só "próprio", sem dizer o site. Em outro site, a renovação falharia sempre. | Registrar o endereço de desenvolvimento do `apps/admin` e a exigência de mesmo site com a API e o `apps/web`. | open |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Cobertura de AC (traceability.json) | OK: AC6.1.1–4, AC7.1.1–4, AC7.2.1–5, AC7.4.1–2 e AC1.2.6 mapeados para BRs existentes; AC7.2.3 como N/A para a U5 | Todos os BR citados existem em rules.md (BR1.1–BR7.4). Sem referência quebrada. Os ACs de kill switch dependem da correção das R-01 e R-02 para serem atendidos de verdade. |
| Conferência com o código existente | `Principal(tenantId, userId, role)` e `AdminAccount` no tenant do sistema são compatíveis; `Role.ADMIN` existe; `BotAdministration.systemPaused()` existe e permite a reconciliação da R-01; `SessionService` e `AuthDtos` ainda fixam BETA_USER (a Unit deve estendê-los); `REQUEST_FORBIDDEN` já existe (R-04) | O projeto cabe no que a U1, a U3 e a U5 construíram. |
| Dados faltando, UTC, sem segredo no log | Sem zero no lugar de `unknown` (BR3.2, BR3.3); instantes em UTC; endereço mascarado e nenhum nonce, assinatura ou código nos eventos; o feedback do administrador não traz `userId` nem carteira | Conforme. |
| Acréscimos de contrato explícitos | `rejectedOrdersToday`, `pendingBots`, `GET /admin/tenants`, 429 no C6 e `countRejectedSince` estão listados, com lacunas na R-03 | Quase completo. |

### Summary

O desenho cobre todos os ACs com BRs existentes e respeita as fronteiras de tenant, papel e dados do administrador, mas o kill switch tem duas lacunas de segurança operacional: a queda entre o commit do estado e a chamada à U5, e a ordem das chamadas `apply` e `lift` fora da trava (R-01 e R-02). Com dois Major e o resto Minor, o veredito é READY, e as duas lacunas devem ser fechadas antes do Code Generation.
