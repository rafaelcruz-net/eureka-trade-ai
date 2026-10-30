## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-10-26T14:56:43Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/refined-mockups/mockups.md > S5 (Operações simuladas, "Ordens recusadas (2) [ver motivos]") | A US4.6 (Must) exige que a ordem recusada mostre motivo e versão dos limites. O mockup só traz o link "ver motivos". Não há tela, lista ou diálogo, nem campos (hora, token, limite violado, versão dos limites), nem estado vazio. Quem for construir terá de adivinhar. | Esboçar a visão de ordens recusadas, com colunas, estado vazio e a versão dos limites. Ou registrar que ela será definida no design funcional, dizendo qual critério de aceite cobre. | New |
| R-02 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/refined-mockups/mockups.md > S5, S8, A1 (kill switch) | O lado do convidado do kill switch não aparece. As histórias exigem: bots "pausados pelo sistema" (AC7.2.2), início ou retomada recusados com esse motivo (AC7.2.3), texto de que a pausa foi da administração depois do desligamento (AC7.2.4), e retomada recusada com 3 bots ativos (AC4.8.4). Os mockups só mostram a confirmação e o aviso na área admin. O desligamento do kill switch também não tem fluxo. | Acrescentar os estados do convidado: aviso e motivo "pausado pelo sistema" em S5, S8 e no assistente, recusa de "Retomar", e o fluxo de desligar o kill switch em A1 com sua confirmação. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/refined-mockups/mockups.md > Mapa de telas | US4.9 (editar limites de bot ativo, Should) não tem tela nem aparece como adiada. US1.4, US4.6, US4.7, US4.11 e US7.3 também não constam no mapa. A cobertura só pode ser conferida por inferência. | Incluir no mapa uma linha para cada história sem tela própria (como regra de comportamento ou fora do beta), ou marcar US4.9 como adiada. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/refined-mockups/mockups.md > A1/A2 (menu admin) | O menu admin lista "Kill switch" e "Feedback", mas só há esboço de Estado e Convites. A lista de feedback, com tela de origem e horário (AC6.1.4), existe só em uma frase. | Esboçar a lista de feedback da administração, ou unir "Kill switch" a "Estado" no menu. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/refined-mockups/mockups.md > S1, S2, S6, S7, S4 etapa 2 | Cinco telas dizem "mantém o layout do wireframe" e trazem só as diferenças. O artefato não se sustenta sozinho, e o wireframe é de menor fidelidade. | Aceitável para o beta, se o gate aprovar que o wireframe vale como base. Do contrário, incluir o esboço consolidado. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/refined-mockups/mockups.md > S4 etapa 1, S5 ("reinicia 21:00 Brasilia") | A liquidez aparece como "20.000 SOL" no assistente e como "45,2k" no scanner, o que contradiz a regra de números. O horário de reinício fixa Brasília, enquanto a regra diz que horários seguem o fuso do usuário. | Padronizar o formato numérico. Calcular o reinício no fuso do usuário, mantendo o UTC ao passar o mouse. | New |

### Summary

O pacote é sólido: seis decisões confirmadas, estados claros (vazio, carregando, erro, parcial e `unknown`), números e risco bem definidos e acessibilidade básica rastreada aos CT-UX. Os dois pontos Major (ordens recusadas e o lado do convidado no kill switch) têm contorno no design funcional e não bloqueiam. Vale o gate decidir se os resolve agora ou os registra como pendência.
