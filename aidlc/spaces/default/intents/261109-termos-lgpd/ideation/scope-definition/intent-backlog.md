# Backlog priorizado — Termos de uso e LGPD

Conversa: português (Brasil). Priorização MoSCoW (Q2), ordem por dependência e risco (Q4). Base: `ideation/intent-capture/intent-statement.md`, `ideation/feasibility/feasibility-assessment.md`, `constraint-register.md`, `raid-log.md` e `scope-document.md`.

| Ordem | ID | Item (proto-Unit) | MoSCoW | Depende de | Risco | Restrições |
|---|---|---|---|---|---|---|
| 1 | C1 | Termos de uso v2 e política de privacidade versionados, com novo aceite | Must | — | Médio: o texto precisa refletir o sistema (R2) | TL-C01, TL-C04, TL-C08 |
| 2 | C3 | Tabela de retenção ligada aos expurgos reais, com testes | Must | alimenta C1 | Médio (R2) | TL-C01 |
| 3 | C2 | Páginas públicas `/termos` e `/privacidade` e links no rodapé e no convite | Must | C1 | Baixo | TL-C03 |
| 4 | C4 | Pedidos de titular: formulário na Conta, registro com prazo, atendimento no administrador | Must | — | Médio: prazo de 15 dias (R3) | TL-C01, TL-C09 |
| 5 | C5 | Exclusão por anonimização | Must | C4 | Alto: dado residual que identifica a pessoa (R1) | TL-C02, TL-C09 |
| 6 | C6 | Campos de implantação: armazenamento, transferência internacional, e-mail do encarregado | Must | hospedagem (D1), e-mail (D3) | Médio: bloqueia a publicação | TL-C05, TL-C07 |
| 7 | C7 | Alerta de prazo dos pedidos | Should | C4 | Baixo | — |
| 8 | C8 | Histórico das versões publicado | Could | C1, C2 | Baixo | — |

## Escopo mínimo

C1, C3, C2 e C6 (Q1). C4 e C5 completam o que a LGPD exige antes de abrir o beta (Q2).

## Dependências externas

Hospedagem para o campo de armazenamento (D1), texto definitivo segundo o parecer (D2) e e-mail do encarregado (D3) (raid-log.md).
