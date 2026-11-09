**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-11-09T13:28:43Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/inception/refined-mockups/mockups.md > T5, T6, T7 (Estados) | A Q3 prometeu cinco estados (carregando, vazio, erro, sucesso, parcial) em todas as telas com dados, mas só T1, T2 e T4 trazem carregando e erro. T6 (lista e detalhe de pedidos) não tem carregando, vazio, erro de API nem sucesso. T5 não tem carregando, erro 5xx nem o sucesso do envio. T7 não mostra o que acontece se o aceite falhar (5xx, rede) nem o estado "enviando". | Acrescentar a T5, T6 e T7 os estados que faltam (carregando, vazio, erro técnico com "tentar de novo", sucesso, botão desabilitado durante o envio) com o texto de cada um. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/inception/refined-mockups/mockups.md > T5 e T6 (ação "Recusar…") | AC5.3.2 exige motivo obrigatório ao recusar, e AC5.2.2 exige que a pessoa veja o motivo da recusa na Conta. T6 só lista o botão "Recusar…", sem campo ou diálogo de motivo, sem erro de motivo vazio e sem o `409` de transição inválida (AC5.3.3). T5 mostra só "Resposta" e não mostra o motivo da recusa. | Desenhar o fluxo de recusa em T6 (motivo obrigatório, erro, `409` de pedido já Concluído ou Recusado) e mostrar o motivo da recusa no detalhe do pedido em T5. | Resolved (reviewer) |
| R-03 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/inception/refined-mockups/mockups.md > T6 (Registrar pedido por e-mail e Verificação de identidade) | A US5.6 está só em texto. O formulário "Registrar pedido por e-mail" (campos, validação, vínculo opcional com a conta) não tem tela. O botão "Pedir verificação" está no interaction-spec, mas não aparece em T6. Faltam os estados da verificação: o pedido sem conta ligada (que não pode ser verificado), a mensagem vencida em 24 h (pedido recusado com "identidade não confirmada", AC5.6.5), a assinatura reutilizada (AC5.6.6) e o texto de orientação ao contato quando a carteira foi perdida ou a conta já está anonimizada (AC5.6.9). | Desenhar o formulário de registro e o botão "Pedir verificação". Acrescentar os estados de verificação: aguardando, confirmada, inválida, vencida e usada. Dizer como T6 trata pedido por e-mail sem conta ligada. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/refined-mockups/mockups.md > T2 (tabela de retenção) | A tabela de retenção, com quatro ou mais colunas, deve funcionar a partir de 320 px (Q6 e AA), mas nada diz como ela se comporta no celular. O texto "Sem conseguir entrar? Escreva para <e-mail>" e o botão "Fazer um pedido" também não dizem o que acontece sem login. | Definir o comportamento da tabela em 320 px (por exemplo, linhas empilhadas ou rolagem só na tabela, sem rolagem horizontal na página). Dizer para onde "Fazer um pedido" leva quem não tem sessão. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/refined-mockups/interaction-spec.md > Interações de fluxo (Resumo de Acesso) | O erro `409 IDENTITY_NOT_VERIFIED` de "Concluir" um Acesso é tratado com "botão desabilitado com o motivo". Mas a API só recusa no envio, e o `409` pode chegar mesmo com o botão habilitado (corrida ou expiração). Falta a mensagem para esse caso, como T8 já tem. | Definir a mensagem exibida quando o `409 IDENTITY_NOT_VERIFIED` voltar da API ao concluir, além do botão desabilitado. | Resolved (reviewer) |

> R-01 Reviewer note: T5 (tabela de estados), T6 (tabela "Estados de T6") e T7 (tabela "Estados de T7") agora cobrem carregando, vazio, enviando, sucesso, erro de validação, 409, erro técnico e erro ao carregar.

> R-02 Reviewer note: T6c define o fluxo de recusa com motivo obrigatório (botão desabilitado sem motivo, limite de 500) e o `409` de pedido encerrado; T5 mostra "Motivo da recusa" ao titular.

> R-03 Reviewer note: T6a traz o formulário de registro por e-mail com validações e busca de conta; T6b cobre os estados sem conta, pronto, aguardando, conferindo, confirmada, inválida, já usada e vencida, com o texto de orientação.

> R-04 Reviewer note: T2 descreve a tabela de retenção empilhada abaixo de 768 px, sem rolagem horizontal, e o "Fazer um pedido" sem sessão, com o aviso do e-mail do encarregado.

> R-05 Reviewer note: "Estados de T6" traz a mensagem do `409 IDENTITY_NOT_VERIFIED` com recarga do painel de verificação, e T8 trata o mesmo erro.

### Summary

Todos os cinco achados anteriores foram resolvidos. A revisão não introduziu contradição com as histórias nem com os requisitos: prazo de 15 dias, limite de 1000 caracteres, validade de 24 h da verificação, retenção de 12 meses e a confirmação `ANONIMIZAR` estão coerentes com US5.x, US6.1 e FR5.x. Engenharia pode começar sem voltar com dúvidas.
