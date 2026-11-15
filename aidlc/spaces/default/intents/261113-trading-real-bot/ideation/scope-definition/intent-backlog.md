# Backlog da intenção — Trading REAL

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/feasibility/feasibility-assessment.md`, `ideation/feasibility/constraint-register.md` e `scope-definition-questions.md` (Q1 a Q8).

## Priorização (MoSCoW e WSJF)

WSJF = (valor + urgência + redução de risco) / tamanho, em escala de 1 a 10. A ordem final segue o risco primeiro (Q2) e as dependências (Q3); o WSJF ordena dentro de cada fase.

| Item | Descrição | MoSCoW | Valor | Urgência | Redução de risco | Tamanho | WSJF | Depende de |
|---|---|---|---|---|---|---|---|---|
| P0 | Provas técnicas: Ed25519 no KMS e swap da Jupiter no validador clonado | Must | 3 | 9 | 10 | 2 | 11,0 | — |
| PB-01 | Dados de mercado reais (CoinGecko) | Must | 7 | 8 | 7 | 3 | 7,3 | P0 |
| PB-02 | Carteira do bot (KMS, teto no perfil, depósito, saque) | Must | 9 | 8 | 8 | 5 | 5,0 | P0 |
| PB-05 | Barreira de risco REAL (limites, teto, pausa, kill switch) | Must | 8 | 8 | 9 | 3 | 8,3 | — |
| PB-03 | Executor Jupiter (cotação, swap, assinatura, confirmação, idempotência) | Must | 9 | 8 | 7 | 5 | 4,8 | PB-01, PB-02, PB-05 |
| PB-04 | Ledger REAL e reconciliação | Must | 8 | 7 | 9 | 4 | 6,0 | PB-03 |
| PB-06 | Modos, consentimento assinado e termos do REAL | Must | 7 | 7 | 6 | 3 | 6,7 | PB-04 |
| PB-07 | Modo Assinada (padrão) | Must | 6 | 6 | 5 | 3 | 5,7 | PB-06 |
| PB-09 | Liberação controlada (REAL por usuário, quarentena) | Must | 7 | 7 | 8 | 2 | 11,0 | PB-07 |
| PB-08 | Automática nas saídas (ordens condicionais Jupiter) | Should | 6 | 4 | 5 | 3 | 5,0 | PB-04 |
| PB-10 | Exportação fiscal | Should | 5 | 3 | 3 | 2 | 5,5 | PB-04 |

## Ordem de entrega proposta

1. **Fase 0 — provas:** P0.
2. **Fase 1 — base:** PB-05, PB-01, PB-02 (WSJF dentro da fase).
3. **Fase 2 — execução:** PB-03, depois PB-04.
4. **Fase 3 — uso:** PB-06, PB-07, PB-09.
5. **Fase 4 — depois do piloto:** PB-10, PB-08.

## Critérios de pronto por item (nível de backlog)

| Item | Pronto quando |
|---|---|
| P0 | uma assinatura Ed25519 do KMS é aceita pela rede de teste e um swap da Jupiter roda de ponta a ponta no validador clonado |
| PB-01 | o scanner mostra dados reais com fonte, horário e `unknown`; o REAL recusa operar com a fonte simulada |
| PB-02 | a carteira do bot é criada sem a chave sair do KMS; depósito acima do teto é recusado; saque só vai para a carteira principal |
| PB-03 | uma ordem gera uma única transação, mesmo com repetição; falha e confirmação ficam registradas |
| PB-04 | todo lançamento REAL tem assinatura on-chain; a reconciliação acha e sinaliza divergências |
| PB-05 | nenhuma ordem passa acima dos limites; o kill switch bloqueia novas ordens e cancela as condicionais das carteiras do bot |
| PB-06 | subir de modo exige consentimento assinado; sem o aceite dos termos do REAL, a carteira do bot não ativa |
| PB-07 | cada ordem real no modo Assinada só sai depois da assinatura do usuário |
| PB-09 | o REAL só liga para um usuário liberado pelo administrador, 24 horas depois da liberação com tudo verde |
| PB-08 | stop e alvo viram ordens condicionais que disparam sem o usuário online |
| PB-10 | o usuário exporta o histórico REAL com data, token, quantidade, preço e taxa |
