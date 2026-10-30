# Justificativa da ordem dos Bolts — MemeTrade Analytics (beta PAPER por convite)

Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda. Este documento explica por que os 9 Bolts de [bolt-plan.md](bolt-plan.md) estão na ordem B1 (U1), B2 (U2), B3 (U4), B4 (U7), B5 (U6), B6 (U5), B7 (U9), B8 (U3), B9 (U8). As Units estão em [unit-of-work.md](../units-generation/unit-of-work.md) e o grafo em [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md); os requisitos e as histórias, em [requirements.md](../requirements-analysis/requirements.md) e [stories.md](../user-stories/stories.md); os componentes, em [components.md](../domain-design/components.md); as telas, em [mockups.md](../refined-mockups/mockups.md).

## Heurísticas usadas

1. **Esqueleto primeiro** (walking skeleton, Cockburn): o primeiro Bolt é a menor fatia que atravessa toda a arquitetura e funciona de verdade (`team.md`, [team-practices.md](../practices-discovery/team-practices.md)).
2. **Fases da escolha mista** (Q1): depois do esqueleto vêm a fundação de auditoria e dados (U2, U4), depois o núcleo financeiro (U7, U6, U5) e por último o que completa o produto para convidados (U3, U8, U9).
3. **WSJF dentro de cada fase** (Q2): WSJF (Weighted Shortest Job First, Reinertsen e SAFe) dá uma nota = (valor + urgência + redução de risco) ÷ tamanho; nota maior vai primeiro. Pesos iguais para os três termos.
4. **Grafo de dependências**: nenhuma Unit começa antes das Units de que depende.

A escolha mista vem antes do WSJF porque responde à maior preocupação declarada (Q6: o núcleo financeiro dar resultado errado); o WSJF desempata dentro de cada fase.

## Pontuação WSJF

Escala de 1 a 8 para valor, urgência e redução de risco; tamanho pela complexidade da Unit (S = 2, M = 3, L = 5, XL = 8). A U1 não é pontuada: é pré-requisito de todas as outras.

| Unit | Valor | Urgência | Redução de risco | Tamanho | Nota | Motivo resumido |
|---|---|---|---|---|---|---|
| U2 auditoria | 3 | 5 | 5 | 2 | 6,5 | Todas as Units seguintes publicam eventos; a tabela de saída é base do "exatamente um evento" |
| U6 barreira | 5 | 5 | 8 | 3 | 6,0 | Regra de segurança financeira; maior preocupação (Q6) |
| U7 ledger e P&L | 8 | 5 | 8 | 5 | 4,2 | Núcleo do valor do beta (P&L reconciliável) e da maior preocupação |
| U9 painel | 5 | 2 | 1 | 2 | 4,0 | Primeira tela, mas só lê dados já prontos |
| U3 convites e contas | 5 | 3 | 3 | 3 | 3,7 | Necessária antes de convidar pessoas, não antes de construir |
| U4 descoberta | 8 | 5 | 5 | 5 | 3,6 | Valor central do beta; destrava todo o núcleo |
| U8 administração | 3 | 2 | 3 | 3 | 2,7 | Necessária antes de convidar pessoas |
| U5 bots e decisão | 8 | 3 | 8 | 8 | 2,4 | Muito valor e risco, mas a maior Unit |

## Ordem resultante, fase a fase

| Fase | Units candidatas | Ordem pelo WSJF | Ordem aplicada | Por quê |
|---|---|---|---|---|
| 0 — Esqueleto | U1 | — | U1 | Pré-requisito e postura do `team.md` |
| 1 — Fundação | U2, U4 | U2 (6,5), U4 (3,6) | U2, U4 | Igual ao WSJF |
| 2 — Núcleo financeiro | U7, U6, U5 | U6 (6,0), U7 (4,2), U5 (2,4) | U7, U6, U5 | A U6 depende da U7 (C13); o grafo obriga U7 antes de U6 e as duas antes de U5 |
| 3 — Produto para convidados | U9, U3, U8 | U9 (4,0), U3 (3,7), U8 (2,7) | U9, U3, U8 | Igual ao WSJF; a U8 depende da U3 |

### Desvios em relação ao WSJF puro

- **U4 antes de U3** (3,6 contra 3,7): a diferença é mínima, e a U4 destrava a U7, a U6 e a U5, que somam as maiores notas de redução de risco. Seguir o WSJF puro adiaria o núcleo financeiro, contra a escolha Q1 e a preocupação Q6.
- **U7 antes de U6** (4,2 contra 6,0): não é escolha, é dependência. A barreira lê exposição, perda do dia e patrimônio da U7 (C13 de [contract-summary.md](../contract-design/contract-summary.md)).
- **U5 antes de U9, U3 e U8** apesar da nota menor (2,4): a U9 e a U8 dependem da U5; a U3 poderia vir antes, mas a escolha mista (Q1) coloca o produto para convidados depois do núcleo, porque convidar pessoas antes de o núcleo funcionar não valida nada.

## Argumento de risco

- **O núcleo financeiro é a maior preocupação** (Q6). Por isso ele começa logo depois da fundação (B4 a B6) e tem TDD, teste de contrato entre barreira e ledger no pronto do B5 e teste do ciclo completo no pronto do B6 (Q8).
- **Pontos de contrato ainda abertos** foram aceitos no Contract Design e têm Bolt dono: R-10, R-13 e R-18 no B1; R-11 e R-12 no B4; R-14 e R-16 no B6; R-15 no B8; OQ-3 (moeda e escala) antes do design funcional do B4.
- **A primeira prova completa do caminho PAPER** chega só no B6. O teste de contrato do B5 reduz esse risco antes (Q8).
- **Dados reais** não bloqueiam nenhum Bolt: a U4 é aceita com fontes simuladas e tolerância configurável (Q5); os adaptadores reais entram depois do G0.

## O que este plano não decide

- A ordem que a construção segue de fato vem do grafo de Units; este plano registra a justificativa econômica e coincide com uma ordem válida do grafo.
- Datas e prazos: dependem do ritmo de um desenvolvedor solo e ficam no acompanhamento de cada Bolt.
