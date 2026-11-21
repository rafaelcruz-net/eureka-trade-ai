# Verificação da passagem Ideation → Inception — paper-real-unico

Data: 2026-11-21.

## Consistência entre intenção, escopo e backlog

| Item da intenção | No escopo | No backlog | Resultado |
|---|---|---|---|
| Bots simulados e reais lado a lado num ambiente | Lista única de bots com o modo (Must) | item 3 (tela) | consistente |
| P&L por bot | P&L por bot nos dois modos (Must) | itens 1, 2 e 3 | consistente |
| P&L total separado por modo | Total por modo, separado (Must) | itens 2 e 3 | consistente |
| P&L fecha com o ledger de cada modo | Conferência com o ledger (Must) | item 1 | consistente |
| Nunca misturar os modos | Fora do escopo, e regra M1 | todos os itens | consistente |

## Respaldo de viabilidade para cada item do escopo

| Item do escopo | Respaldo em `feasibility-assessment.md` | Resultado |
|---|---|---|
| Lista única com o modo | o mercado do bot já existe (`BotMarket`) | respaldado |
| P&L PAPER por bot | `GET /api/v1/pnl?botId` já existe | respaldado |
| P&L REAL por bot | toda ordem REAL de bot guarda o bot; custo médio por bot (Q8) | respaldado, com a condição das saídas condicionais (R5) |
| Rateio das vendas sem bot | Q9 | respaldado |
| Venda só da própria quantidade | Q10, depois da Performance Validation do REAL | respaldado, com condição de prazo |
| Aviso no simulado | regra C-16, Q3 | respaldado |
| Filtros, detalhe e comparação (Should e Could) | módulos existentes (Q2) | respaldado |

## Veredito

**APROVADO para a Inception.** Há duas condições abertas, registradas no RAID:
- **R5 / D1:** as saídas condicionais no P&L REAL, no intent 261113;
- **Q10:** a mudança do ciclo, só depois da Performance Validation.

Nenhum item do escopo está sem respaldo de viabilidade.
