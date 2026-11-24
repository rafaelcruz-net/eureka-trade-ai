# Requisitos de segurança — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

Herdam o NFR3 (isolamento e segurança) e o NFR4 (separação dos modos) do intent. A U3 não muda o modelo de ameaças: a superfície nova é só de leitura (C4 com `market`, `listBots` com `market` e contagens, C5 e C6) e não movimenta fundos [Q4].

## Decisão do fundador (2026-11-23)

Tomada depois da revisão `reviews/review-01.md` (R-01). Não pode ser mudada na Code Generation.

- **D-NFR1. Auditoria pela regra do `team.md`:** "erros de domínio (…) tenant divergente (…) voltam como 4xx e são auditados" (`team.md`, Code Style). Por isso são auditados o 422 `TEMPLATE_MISMATCH` de C6 e o 404 de bot de outra conta em C5 e em C6, com o limite de volume do padrão da U2 (NFR3.15 da U2): no máximo 1 evento por hora por pessoa e por tipo. Uma falha na gravação do evento não muda a resposta. O contador de NFR3.9 fica (NFR3.13, NFR3.14).

## Autenticação e autorização

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.1 | O tenant e a pessoa vêm sempre do token, nunca do corpo, da consulta ou de cabeçalho próprio (BR8.1) [Q4]. | Em C4 com `market`, `listBots` com `market`, C5 e C6, um teste de integração envia `tenantId` ou `accountId` na consulta e confere que o valor é ignorado ou recusado com 400; 0 consultas de dados de cliente sem filtro de tenant (as regras ArchUnit dos módulos `bots` e `pnl` continuam verdes). Os pedidos do `apps/web` levam só ids de bot, período, `market`, `cursor` e `limit`: teste de componente confere as URLs. |
| NFR3.2 | C5 responde 404 `NOT_FOUND` para bot de outra pessoa, inexistente ou PAPER, e o corpo não traz nenhum dado do bot (BR8.2, BR4.5) [Q2]. | Teste de integração com 2 tenants: 3 casos (bot de outro tenant, uuid inexistente, bot PAPER da própria pessoa), todos 404 com o mesmo corpo, sem nome, modo, linhas nem valores. Só o caso do bot de outro tenant grava o evento `TENANT_MISMATCH` (NFR3.13); a resposta dos 3 casos é igual byte a byte, fora o `correlationId`. A tela mostra só "Bot não encontrado." (teste de componente). |
| NFR3.3 | C6 confere a posse dos dois bots antes da estratégia: 404 vem antes do 422 (BR5.2, BR5.3) [Q2]. | Teste de integração com 2 tenants: um par em que o bot REAL é de outro tenant **e** tem `templateId` diferente responde 404 com `reason` `REAL_BOT`, nunca 422; o mesmo com o bot PAPER (`reason` `PAPER_BOT`); bot do modo errado no lado → 404. Em todos, o corpo não traz `templateId`, nome nem valor dos dois bots. Só um par dos dois bots da pessoa, com `templateId` diferente, chega ao 422 `TEMPLATE_MISMATCH`, e esse corpo não traz nenhum valor de P&L. O 404 por bot de outro tenant grava `TENANT_MISMATCH` e o 422 grava `PNL_COMPARE_TEMPLATE_MISMATCH` (NFR3.13). |
| NFR3.4 | As rotas novas (C5, C6) e as mudadas (C4, `listBots`) exigem login e seguem o novo aceite dos termos. | Sem token: 401 nas 4 rotas. Com termos desatualizados: 403 `TERMS_REACCEPTANCE_REQUIRED` nas 4 rotas. Nenhuma entra na exceção do novo aceite (leitura da carteira do bot e saque). |
| NFR3.5 | As contagens de `BotPage` contam só os bots da pessoa do token (BR1.5). | Teste de integração com 2 tenants: `totalCount` e `matchingCount` da pessoa A não mudam quando a pessoa B cria bots; `matchingCount ≤ totalCount` em 100% dos casos testados. |

## Auditoria

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.13 | O 404 de bot de outra conta em C5 e C6 e o 422 `TEMPLATE_MISMATCH` de C6 são auditados como erros de domínio, sem dado pessoal [fundador D-NFR1]. | Ações: `AuditActions.TENANT_MISMATCH`, que já existe, para o 404 de bot que existe em outro tenant (C5, e C6 em cada lado); e uma ação nova, `AuditActions.PNL_COMPARE_TEMPLATE_MISMATCH`, para o 422. Um uuid inexistente, um bot PAPER em C5 e um bot da própria pessoa no modo errado em C6 não são tenant divergente e não geram evento. Cada evento traz só o tenant e a pessoa de quem pediu (uuid), a rota em enum (`BOT_PNL_LINES` ou `PNL_COMPARE`), o lado em enum quando é C6 (`PAPER_BOT` ou `REAL_BOT`), os ids de bot pedidos e o `correlationId`; 0 eventos com nome, `templateId`, valor de P&L, endereço de carteira ou dado do bot da outra conta além do id que a própria pessoa enviou. Teste de integração, com o limite de NFR3.14 em vigor: 3 respostas 404 de outro tenant da mesma pessoa dentro da mesma hora (C5, lado PAPER de C6 e lado REAL de C6) gravam exatamente 1 evento `TENANT_MISMATCH`, com a ação, a rota e o `correlationId` da primeira resposta; em seguida, 1 resposta 422 da mesma pessoa grava o seu próprio evento `PNL_COMPARE_TEMPLATE_MISMATCH` (outra ação, outro limite); 0 eventos nos casos que não são tenant divergente. Os eventos seguem a retenção e a anonimização da auditoria que já existem. |
| NFR3.14 | O volume dos eventos de NFR3.13 é limitado, e a falha da gravação nunca muda a resposta [fundador D-NFR1]. | No máximo 1 evento por hora por pessoa e por ação (`TENANT_MISMATCH` e `PNL_COMPARE_TEMPLATE_MISMATCH` contam separados), pelo mesmo padrão de limite da U2 (`RateLimitedAuditPublisher`, usado pelo `ReconciliationAuditGate`, com executor próprio e fila curta; NFR3.15 da U2). As recusas dentro da hora não gravam evento, mas continuam no log (NFR3.10) e, no 422, no contador de NFR3.9. A gravação roda de forma assíncrona no executor da auditoria, depois que a resposta foi decidida e fora da transação de leitura: a thread do pedido entrega a tarefa ao executor e responde sem esperar (0 ms de espera pela gravação, diferente do U2, em que o chamador espera até 200 ms); a própria tarefa desiste ao passar de 200 ms. Se a gravação falhar, passar do tempo ou a fila estiver cheia, a resposta é a mesma (404 ou 422, mesmo corpo), a falha vai para o log `ERROR` com o `correlationId` e conta em `pnl_read_audit_failures_total` (NFR3.16). Teste de integração: 10 recusas seguidas da mesma pessoa e da mesma ação gravam 1 evento; a outra ação grava 1 evento a mais; outra pessoa grava 1 a mais; com a auditoria falhando, as 10 respostas são iguais às de sem falha. |
| NFR3.17 | O tempo de resposta não revela se um bot existe em outra conta: o 404 de bot de outro tenant e o 404 de uuid inexistente são indistinguíveis pelo tempo (revisão 2). | Os dois casos seguem o mesmo caminho de código até a resposta: a mesma consulta filtrada pelo tenant do token, que devolve vazio nos dois, e a mesma verificação `ownedByAnotherTenant`, que roda na thread do pedido nos dois 404 (alinhamento A1, decisão F-1); só a entrega ao executor da auditoria acontece depois que a resposta foi decidida e sem espera (NFR3.14). Medida: teste de integração no método de NFR1.5 (10 de aquecimento e 100 medidos por caso, intercalados) em C5 e em C6; a diferença entre os p95 dos dois casos fica dentro de 5 ms ou de 10% do maior p95, o que for maior, e é registrada no log. Teste de unidade confere que o controlador responde sem aguardar a tarefa de auditoria (um duplo da auditoria que bloqueia 1 s não atrasa a resposta). |

## Validação na fronteira

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.6 | Entrada malformada responde 400 `VALIDATION_FAILED`, pela convenção do contrato. | Testes de rota: `market` fora de `PAPER` e `REAL` em C4 e em `listBots` → 400; `cursor` inválido em C5 → 400; `limit` fora de 1 a 100 em C5 → 400; período incoerente ou acima de 366 dias em C4 e C6 → 400; `paperBotId` ou `realBotId` que não é uuid → 400. Na tela: `?mode` inválido vale Todos e não gera pedido (BR1.7); `?comparar=` inválido abre a vista vazia sem pedido (BR5.6); período inválido no endereço aplica "Desde o início" sem pedido com `RANGE` (BR2.6). |

## Proteção de dados

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.7 | Nenhum endereço de carteira aparece em C5, C6, C4 com `market`, nos logs da U3 nem na etiqueta da métrica nova (NFR3 do intent) [Q4]. | Teste de contrato: os esquemas de C5 e C6 não têm campo de endereço; teste de integração: o corpo de C5 e de C6 de uma pessoa com carteira do bot ativa não contém o endereço semeado; o contador de NFR3.9 não tem etiqueta, e os de NFR3.15 e NFR3.16 só têm etiquetas de enum; os eventos de auditoria de NFR3.13 não trazem endereço. Nenhum segredo, token, cabeçalho `Authorization`, nonce ou assinatura vai para o log; 0 variáveis `NEXT_PUBLIC_*` novas; Gitleaks verde no pré-commit e no workflow `security`. |
| NFR3.8 | Nenhum caminho da U3 assina ou envia transação; o REAL continua desligado por padrão. | 0 chamadas novas ao TxSigner ou ao KMS; o teste obrigatório "REAL desligado por padrão" continua verde. |

A contagem das recusas `TEMPLATE_MISMATCH` (NFR3.9), dos lados `UNAVAILABLE` de C6 (NFR3.15) e das falhas de gravação da auditoria (NFR3.16) está em `observability-requirements.md`.

## Separação dos modos

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR4.1 | Nenhuma resposta e nenhuma tela da U3 soma PAPER e REAL num total geral (BR6.2) [Q2]. | Teste de contrato: os esquemas de C4 (com e sem `market`), C5, C6 e `BotPage` não têm campo de total fora dos blocos ou dos lados; o teste de propriedade "nenhum terceiro número" de C4 e de C6 continua verde; testes de componente do painel, de `/pnl`, do filtro "Todos" e da comparação conferem a ausência de total geral; o spec de ponta a ponta procura "Total geral" no painel, em `/pnl` e na comparação e encontra 0 ocorrências. |
| NFR4.2 | C4 com `market` traz só o bloco pedido; a chave do outro bloco é omitida do JSON, nunca `null` (BR3.1). | Teste de contrato e de rota com 3 casos: sem `market`, `paper` e `real` presentes e não nulos; `market=PAPER`, a chave `real` ausente; `market=REAL`, a chave `paper` ausente; nos 3, 0 campos de total fora dos blocos. |
| NFR4.3 | Cada modo aparece na própria unidade em todas as telas da U3: PAPER em US$, REAL em SOL (BR11.2). | Testes de componente do painel, do detalhe, de `/pnl` e da comparação: 0 valores PAPER em SOL ou lamports e 0 valores REAL em US$; o spec de ponta a ponta confere o bloco PAPER sem "SOL" e o REAL sem "US$". |
| NFR4.4 | Nenhuma lista, ranking ou ordenação mistura os modos por valor (BR6.3). | `/bots` na mesma ordem da API em todos os filtros (a ordem e o cursor de `listBots` que já existem, `updated_at desc, bot_id desc`; nunca por valor nem misturando os modos por valor; conflito 1 do plano da Code Generation, decisão do responsável na aprovação); a comparação sem campo de diferença, percentual, ranking nem destaque de vencedor (teste de contrato de C6 e teste de componente). |
| NFR4.5 | O `apps/web` não soma, subtrai nem converte dinheiro (BR6.1, BR11.1). | O lint de dinheiro de `features/pnl` (sem `bigint`, `BigInt(`, `Number.parseFloat`, `toFixed`) e o de `features/dashboard` (sem `Number(`, `parseFloat`) passam; `bigint` só em `features/real/money.ts`; teste de componente: cada valor mostrado é igual ao texto da fixture da API, formatado por `money-format.ts`. |

## Sources

- `nfr-requirements-questions.md`: Q2, Q4 e o resumo confirmado [Q2] [Q4].
- `reviews/review-01.md` (R-01) e a decisão do fundador de 2026-11-23 (D-NFR1).
- `construction/u2-real-bot-pnl/nfr-requirements/security-requirements.md`: NFR3.15 (limite de 1 evento por hora, gravação fora da leitura com 200 ms, falha que não muda a resposta).
- Código lido: `apps/api/src/main/java/com/memetrade/audit/api/AuditActions.java` (`TENANT_MISMATCH` existe; não há ação para a estratégia diferente), `pnl/application/ReconciliationAuditGate.java` (limite por hora pelo `RateLimitedAuditPublisher`, gravação fora da leitura com 200 ms, falha contada e registrada sem mudar a resposta).
- `inception/requirements-analysis/requirements.md`: NFR3, NFR4.
- `construction/u3-pnl-views/functional-design/rules.md`: BR1.5, BR1.7, BR2.6, BR3.1, BR4.5, BR5.2, BR5.3, BR5.6, BR6.1 a BR6.3, BR8.1, BR8.2, BR11.1, BR11.2.
- `inception/contract-design/contract-summary.md`: regras comuns (tenant do token, 404, 400 contra 422), C4, C5, C6.
- `construction/u1-mode-skeleton/nfr-requirements/security-requirements.md`: forma dos requisitos NFR3 e NFR4.
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos, `NEXT_PUBLIC_*`, soma dos modos, ponto flutuante) e Mandated (tenant no servidor, separação, REAL desligado).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (testes obrigatórios por risco), Code Style (erros RFC 7807, tenant no código, ArchUnit), Way of Working (Gitleaks).

## Assumptions & Open Questions

1. [assumption] O teste de NFR3.1 aceita as duas respostas que o código já dá para parâmetro não declarado (ignorado ou 400); o que importa é que o tenant nunca venha do pedido.
2. [assumption] O nome da ação nova, `PNL_COMPARE_TEMPLATE_MISMATCH`, segue o prefixo de módulo das ações recentes (`REAL_PNL_RECONCILIATION_MISMATCH`); a forma do limite por hora (por exemplo, a impressão digital pessoa mais ação no `RateLimitedAuditPublisher`) fica com o NFR Design, mantendo 1 evento por hora por pessoa e por ação.
