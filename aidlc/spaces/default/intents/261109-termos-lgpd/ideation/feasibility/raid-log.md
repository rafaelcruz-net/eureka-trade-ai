# RAID — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/market-research/competitive-analysis.md`, `market-trends.md`, `build-vs-buy.md` e `feasibility-questions.md`.

## Riscos

| ID | Risco | Probabilidade | Impacto | Mitigação | Dono |
|---|---|---|---|---|---|
| R1 | A anonimização deixa um dado que ainda identifica a pessoa | Média | Alto | Inventário dos dados pessoais por módulo; teste que procura a carteira depois da anonimização (Q2) | Responsável do projeto |
| R2 | A política descreve algo diferente do que o sistema faz | Média | Alto | Tabela de retenção ligada aos expurgos reais, com testes (Q1) | Responsável do projeto |
| R3 | Prazo de 15 dias perdido por depender de uma pessoa | Baixa | Médio | Alerta de prazo no app do administrador (Q5) | Responsável do projeto |
| R4 | Mudança de texto sem novo aceite | Baixa | Médio | Versão nova sempre exige novo aceite (mecanismo existente da U3) (Q1) | Responsável do projeto |

## Premissas

| ID | Premissa | Origem |
|---|---|---|
| P1 | O parecer jurídico (C-09) cobre o conteúdo dos termos e da política; o texto definitivo segue o parecer | intent-statement.md |
| P2 | Os identificadores internos que ficam no ledger e na auditoria depois da anonimização não identificam a pessoa sem os dados apagados | Q2 |
| P3 | Os 12 meses de guarda da trilha de auditoria têm base legal indicada no parecer | Q2 |

## Problemas em aberto

| ID | Problema | Origem |
|---|---|---|
| I1 | Local de armazenamento e transferência internacional ainda desconhecidos (sem hospedagem) | Q3 |
| I2 | O e-mail do encarregado ainda não foi informado | Q5 |

## Dependências

| ID | Dependência | Bloqueia |
|---|---|---|
| D1 | Hospedagem (C-10, C-12) | Publicação da versão do beta (campo de armazenamento) (Q3, Q6) |
| D2 | Texto definitivo segundo o parecer | Termos v2 e política (P1) |
| D3 | E-mail do encarregado | Publicação da política (Q5) |
