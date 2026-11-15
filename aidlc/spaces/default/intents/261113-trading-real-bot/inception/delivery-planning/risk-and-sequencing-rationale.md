# Risco e justificativa da ordem — Trading REAL

Conversa: português (Brasil). Fontes: `bolt-plan.md`, `ideation/scope-definition/intent-backlog.md` (fases e WSJF aprovados), `inception/units-generation/unit-of-work-dependency.md`, `inception/units-generation/unit-of-work.md`, `inception/contract-design/contract-summary.md`, `inception/requirements-analysis/requirements.md`, `inception/domain-design/components.md` e `delivery-planning-questions.md` (Q1, Q2, Q5, Q8).

Um **Bolt** é uma passada de construção sobre uma Unit que termina em algo que roda. O **esqueleto** é a primeira fatia fina que atravessa o sistema de ponta a ponta. **WSJF** (Weighted Shortest Job First, de Reinertsen e do SAFe) é a pontuação (valor + urgência + redução de risco) ÷ tamanho.

## Heurística

Esqueleto primeiro, depois risco primeiro pelas fases do backlog, com o WSJF ordenando dentro de cada fase e o grafo de Units como limite (regra de `project.md`) [Q1, Q2].

## Por que esta ordem

| Posição | Bolt | Argumento |
|---|---|---|
| 1 | B1 U1 | Esqueleto e Fase 0: as provas técnicas (Ed25519 do KMS e swap da Jupiter sem SDK) podem derrubar o desenho, então vêm antes de qualquer outra coisa; a U1 também é a base de quase todas as outras Units |
| 2 | B2 U2 | Fase 1, sem dependências; precisa existir antes da barreira (U3) e do P&L (U4) |
| 3 | B3 U3 | Fase 1; WSJF 8,3 (PB-05), o maior da fase depois das provas; depende de U1 e U2 |
| 4 | B4 U4 | Fase 2; o executor só pode vir com carteira, dados e barreira prontos |
| 5 | B5 U5 | Fase 3; liberação com quarentena (WSJF 11,0, PB-09), reconciliação e kill switch: o controle precisa existir antes de abrir telas a mais gente [Q5] |
| 6 | B6 U6 | Fase 3; telas e modos (PB-06, PB-07) |
| 7 | B7 U7 | Fase 4 (Should), depois do piloto |

### Desvios

- **Fases do backlog × Units:** o backlog põe PB-05 (barreira) antes de PB-01 e PB-02 na Fase 1, mas o grafo exige U1 e U2 antes de U3; o grafo vence (regra de `project.md`).
- **U5 antes de U6:** no WSJF puro PB-06 (6,7) fica perto de PB-09 (11,0); a ordem segura é a mesma do WSJF, e a justificativa extra é que o kill switch REAL e a reconciliação existem antes de qualquer tela pública do REAL [Q5].
- **PB-04 (ledger e reconciliação):** o ledger REAL entra na U1 (depósito e saque) e na U4 (swaps), e a reconciliação na U5; o item do backlog fica dividido entre três Bolts.

## Riscos atacados cedo

| Risco | Onde | Como |
|---|---|---|
| KMS não assina Ed25519 no formato da Solana | B1 (início) | prova técnica antes do código; se falhar, volta ao Domain Design |
| Transação da Jupiter não serializável sem SDK | B1 (início) | prova técnica no validador clonado |
| TxSigner assina algo fora da política | B1 | testes de ataque antes do código: programa fora da lista, destino estranho, saída mínima baixa, ALT desconhecida, troca de autoridade, repetição, contexto ausente [Q8] |
| Duas transações para a mesma ordem | B4 | testes antes do código de estados e idempotência; ordem ambígua só repete depois da conferência na rede [Q8] |
| Divergência entre ledger e rede | B5 | reconciliação a cada 15 min e depois de cada ordem; pausa do usuário |
| Fonte de dados insuficiente | B2 | respostas gravadas do plano pago antes de contratar |
| Mistura de REAL e PAPER | todos | testes obrigatórios de separação em cada Bolt (`team.md`) |

## Pontos de decisão (seguir ou parar)

- Fim do B1: as duas provas técnicas passaram e o esqueleto foi verificado e aprovado; senão, voltar ao Domain Design.
- Fim do B4: p95 da decisão ao envio até 3 s no validador; senão, rever o NFR Design da U4 antes do B5.
- Antes da mainnet do fundador: B1 a B6 prontos, conta AWS, KMS e RPC reais, quarentena de 24 h.
- Antes dos convidados: 7 dias sem divergência e parecer de custódia decidido.
