# Backlog do intent — paper-real-unico

Fonte: `scope-document.md` e `scope-definition-questions.md` (Q2 a Q4).

A prioridade segue o MoSCoW, e a ordem segue o risco primeiro [Q4]. Os itens são proto-Units, refinadas na Units Generation.

| # | Item (proto-Unit) | MoSCoW | Depende de | Fonte |
|---|---|---|---|---|
| 1 | **Cálculo do P&L REAL por bot na leitura:** custo médio por bot, rateio das vendas sem bot com a marca de origem, conferência com o ledger REAL. Testes escritos antes do código | Must | confirmação das condicionais no P&L REAL (intent 261113) | [Q1] [Q2] [Q3] [Q4] |
| 2 | **API do P&L por bot e por modo:** um bot, a lista e o total de cada modo, sem somar os dois | Must | 1 | [Q2] [Q4] |
| 3 | **Tela:** lista única de bots com o modo, P&L por bot, total por modo, rótulo "simulado" e aviso | Must | 2 | [Q1] [Q2] [Q4] |
| 4 | **Filtros e detalhe do bot:** por modo e por período, com as ordens que compõem o P&L | Should | 2, 3 | [Q2] |
| 5 | **O bot REAL vende só a própria quantidade:** mudança no ciclo | Must | Performance Validation do intent 261113 | [Q3] [Q4] |
| 6 | **Comparação lado a lado** de um bot PAPER e um REAL da mesma estratégia | Could | 3 | [Q2] |

## Fluxo de valor

O usuário passa por quatro momentos:
1. cria um bot PAPER e acompanha o P&L dele;
2. decide testar com dinheiro real, cria um bot REAL com a mesma estratégia e acompanha os dois lado a lado;
3. vê o total de cada modo separado;
4. o P&L REAL de cada bot confere com o ledger.

Os itens 1, 2 e 3 entregam esse fluxo. O item 5 deixa a divisão por bot exata quando dois bots operam o mesmo token [Q1] [Q3].

## Assumptions & Open Questions

None.
