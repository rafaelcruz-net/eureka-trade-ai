# Resumo da iniciativa — paper-real-unico

Fontes: `ideation/intent-capture/`, `ideation/feasibility/`, `ideation/scope-definition/`, `ideation/rough-mockups/` e `approval-handoff-questions.md` (Q1 a Q6).

## Intenção e problema

Num único ambiente, na mainnet, o usuário cria bots simulados (PAPER) e reais (REAL) lado a lado. Ele acompanha o P&L de cada bot e o total de cada modo, sempre separados. Isso evita manter dois ambientes para os usuários. Os clientes são os convidados do beta e o fundador (`intent-statement.md`).

## Validação de mercado

Não se aplica. A pesquisa de mercado foi pulada porque a feature reorganiza capacidades que o produto já tem [Q5].

## Viabilidade e riscos

A feature é viável, com condições (`feasibility-assessment.md`).
- **O que já existe:** o mercado do bot (PAPER ou REAL), o P&L PAPER por bot e o bot gravado em toda ordem REAL de bot.
- **O que é novo:** o P&L REAL por bot, calculado na leitura com custo médio, com o rateio das vendas sem bot e a tela unificada.
- **Condições abertas** [Q2]:
  1. confirmar, no intent 261113, que as saídas condicionais entram no P&L REAL;
  2. mudar o ciclo do bot REAL para que ele venda só a própria quantidade, depois da Performance Validation do REAL.

## Limites do escopo

O escopo está em `scope-document.md`.
- **Must:**
  - lista única de bots com o modo;
  - P&L por bot nos dois modos;
  - total por modo, separado;
  - rateio das vendas sem bot, marcado;
  - P&L conferido com o ledger;
  - aviso no simulado.
- **Should:** filtros e detalhe do bot.
- **Could:** comparação lado a lado.
- **Fica de fora:** misturar os modos, converter um bot de modo, uma rede nova e a exportação fiscal do simulado.

## Visão das telas

As telas estão em `wireframes.md` e `user-flow.md`, e são as telas atuais de Bots, P&L, Painel e detalhe do bot, com estes ajustes:
- blocos Simulado (US$) e Real (SOL), sem nenhum total geral;
- aviso fixo no simulado;
- a origem de cada venda no detalhe do bot REAL.

## Equipe

O fundador, com o assistente, sem custo novo de infraestrutura [Q3] [Q6].

## Recomendação

**GO** para a Inception, com as duas condições acima registradas no RAID [Q1] [Q2].

## Assumptions & Open Questions

- [assumption] Quando um bot vender mais do que comprou, o excedente é rateado entre os outros bots donos do token. Isso vale até o ciclo mudar (`raid-log.md`, A8).
