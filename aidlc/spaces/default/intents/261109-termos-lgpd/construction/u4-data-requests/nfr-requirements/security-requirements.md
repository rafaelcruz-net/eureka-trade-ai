# Requisitos de segurança — U4 `u4-data-requests`

Conversa: português (Brasil). Fontes: `construction/u4-data-requests/functional-design/` (BR4.3, BR4.4, BR4.7, BR4.9 a BR4.12), `inception/requirements-analysis/requirements.md` (NFR1, NFR2, NFR9), `inception/contract-design/contract-summary.md` (P1, P2, J1) e `nfr-requirements-questions.md` (Q3).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.1 | O texto do pedido, a resposta, o contato, a mensagem de verificação e a assinatura nunca aparecem em log, evento, métrica ou erro | `LogScanIT` ganha o texto de um pedido, um contato e uma assinatura de verificação como agulhas; teste de auditoria sobre o payload dos eventos `DATA_REQUEST_*` |
| NFR1.2 | O resumo de Acesso não traz o endereço completo nem dado de outra pessoa | teste do montador com duas pessoas de dados conhecidos |
| NFR2.1 | Todas as leituras e escritas do convidado filtram pelo tenant e pela pessoa da autenticação; um pedido de outra pessoa ou de outro tenant devolve `404` e nunca o dado | teste de isolamento; teste negativo de tenant no `route-registry.yml` para cada rota nova do convidado |
| NFR2.2 | As rotas administrativas aceitam só `ADMIN` (`403 FORBIDDEN_ROLE` para os demais) | teste de API, e o `AdminGuestRoutesIT` existente cobre as rotas novas pelo registro |
| NFR2.3 | A verificação aceita só a assinatura da carteira da conta sobre a mensagem de verificação daquele pedido, uma única vez, em até 24 h. A assinatura de login não serve como verificação, e a de verificação não serve para entrar nem para operar | testes de API e de unidade (AC5.6.3 a AC5.6.7), incluindo a assinatura de verificação enviada ao login |
| NFR2.4 | A criação de pedido pelo convidado tem limite de 10 por hora por pessoa. Antes de gravar, uma contagem no banco dos pedidos criados pela pessoa na última hora (índice `(tenant_id, user_id, received_at)`) decide igual em todas as instâncias. Acima do limite, a resposta é `429 RATE_LIMITED` com `Retry-After`; a resposta `429` entra de forma aditiva no contrato P1 `createDataRequest`. O `PersonRouteRateLimiter` em memória, que só conta por minuto, não é usado aqui | teste de integração com relógio injetável: o 11º pedido na mesma hora recebe `429` e o primeiro da hora seguinte passa |
| NFR2.5 | O texto, a resposta e o motivo aparecem sempre como texto simples nos dois apps, nunca como marcação | teste de componente com `<script>` |
| NFR9.1 | A lógica de dados pessoais desta Unit (criação, isolamento, transições, verificação, vencimento e expurgo) tem os testes escritos antes do código | ordem no plano de código |

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Ler o pedido de outra pessoa | NFR2.1 |
| Terceiro pedindo dados por e-mail em nome de alguém | NFR2.3 (verificação por assinatura da carteira) |
| Reaproveitar a assinatura de login ou de verificação | NFR2.3 |
| Vazar dado pessoal em log ou evento | NFR1.1 |
| Inundar o canal de pedidos | NFR2.4 |
| Injeção de marcação pelo texto | NFR2.5 |
