## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-11-21
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261121-paper-real-unico/ideation/rough-mockups/wireframes.md > seções 3 (Painel) e 5 (Celular) | O aviso "o resultado simulado não garante o resultado real" aparece só no bloco Simulado da tela P&L. O Painel mostra "Resultado simulado: +US$ 9,30" sem o aviso. O cartão Simulado do celular também não traz o aviso. O escopo exige o rótulo "simulado" junto com o aviso, e o Q3 fala em aviso "fixo no bloco Simulado" em qualquer tela. | Incluir o aviso no total simulado do Painel e no cartão Simulado do celular, ou declarar por escrito que o aviso vale em todo lugar onde o total simulado aparecer. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/ideation/rough-mockups/wireframes.md > seções 1, 3 e 4 | Só a tela P&L mostra os estados carregando, erro e `unknown`. A lista de bots (coluna P&L), o Painel e o detalhe do bot não mostram como ficam esses estados. Também não há estado vazio (usuário sem bot simulado ou sem bot real, ou carteira do bot inativa). | Acrescentar uma linha de estados por tela, incluindo o vazio, com o texto exibido. Mostrar `unknown` como desconhecido, nunca como zero. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/ideation/rough-mockups/wireframes.md > seção 5 (Celular) | A seção do celular não tem nota de acessibilidade. As outras quatro seções têm. | Acrescentar a nota: ordem de foco dos cartões, rótulos de modo e moeda, e alvos de toque. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/ideation/rough-mockups/wireframes.md > seção 4, e user-flow.md > Caminho feliz | O fluxo e o detalhe citam "fechado por condicional", mas o wireframe só mostra "fechado por liquidação". O excedente rateado pela suposição A8 do escopo não tem marca nenhuma. | Mostrar uma linha "fechado por condicional" no detalhe e a marca do excedente rateado, ou registrar que ela fica para os requisitos. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/ideation/rough-mockups/wireframes.md > seção 1 (filtro) e seção 4 (tabela de ordens) | O filtro Todos/Simulado/Real e o detalhe com as ordens são itens Should do escopo, desenhados sem essa marca. Isso não é invenção, pois o Q1 os aprovou, mas o leitor do escopo pode lê-los como Must. | Marcar os dois como Should nos rascunhos. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/ideation/rough-mockups/user-flow.md > Caminho feliz | O fluxo só tem o caminho feliz. Não mostra a falha ao criar o bot REAL (carteira do bot inativa, REAL desligado). O Painel também não aparece no fluxo. | Acrescentar o desvio "carteira inativa ou REAL desligado" e a entrada pelo Painel. | New |

### Summary

Os rascunhos cobrem todos os Must do escopo e não somam PAPER e REAL em nenhuma tela. O texto acompanha toda cor, não há promessa de lucro, e os valores de exemplo fecham aritmeticamente. O ponto que mais pesa é o aviso do simulado, que falta no Painel e no celular. Esse e os demais pontos são ajustes, e a engenharia pode partir dos rascunhos como estão.
