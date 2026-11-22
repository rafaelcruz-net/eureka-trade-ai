# Avaliação das histórias — paper-real-unico

Fontes: `inception/requirements-analysis/requirements.md`, `user-stories-questions.md` (Q1 a Q4), `ideation/intent-capture/stakeholder-map.md`, `ideation/rough-mockups/` e o mapa do código (`business-overview.md`, `component-inventory.md`).

## Decisão

**Executar.**

## Por que as histórias são necessárias

- **Funcionalidade que o usuário vê.** O intent muda a criação de bot, a lista de bots, a tela de P&L, o painel e o detalhe do bot (FR1, FR2, FR4, FR3.6). Cada mudança tem um comportamento visível que precisa de um critério de aceite.
- **Duas personas com objetivos diferentes.** A convidada do beta quer criar e acompanhar os bots nos dois modos; o fundador quer conferir o P&L com o ledger, manter o contrato e controlar o custo (`personas.md`).
- **Lógica de negócio complexa.** O rateio das vendas sem bot, o maior resto, a ordem por slot e assinatura, as taxas, o preço `unknown` e o bot removido (FR3.3 a FR3.11, FR5.3, FR5.4) são regras de dinheiro em que um lamport errado quebra a conferência com o ledger. As histórias transformam cada regra em critérios de aceite com valores exatos, que viram os testes escritos antes do código do núcleo financeiro.
- **Risco de misturar os modos.** A separação entre PAPER e REAL e a proibição de um total geral (FR4.1, NFR3, NFR4) precisam de critérios testáveis em cada tela e na API.
- **Ordem de entrega com dependência externa.** O FR6 só entra depois da Performance Validation do intent 261113; uma história separada deixa isso visível para o Delivery Planning.

## Fatores considerados

| Fator | Situação |
|---|---|
| Tipo de projeto | Melhoria em vários componentes de um sistema que já existe (brownfield) |
| Escopo visível ao usuário | Alto: cinco telas e a API pública |
| Sinais de complexidade | Rateio em lamports, igualdade exata com o ledger, dois modos, ordem na rede |
| Coordenação | Arquivos de `pnl` e do painel compartilhados com o intent 261113 |

## Plano aplicado

- **Personas:** 2 — Bia, a convidada do beta (principal), e Rafael, o fundador (secundária) [Q1].
- **Histórias:** 34, em oito grupos por fluxo do usuário [Q2]:

| Grupo | Histórias | Quantidade |
|---|---|---|
| US1 Criar bot no modo | US1.1 a US1.5 | 5 |
| US2 Lista única | US2.1 a US2.4 | 4 |
| US3 P&L por bot e rateio | US3.1 a US3.11 | 11 |
| US4 Totais por modo e aviso | US4.1 a US4.5 | 5 |
| US5 Correção do P&L REAL e conferência | US5.1 a US5.4 | 4 |
| US6 Venda só da própria quantidade | US6.1, US6.2 | 2 |
| US7 Períodos | US7.1, US7.2 | 2 |
| US8 Comparação | US8.1 | 1 |

- **Tamanho:** uma história por comportamento que pode ser testado sozinho. As regras de dinheiro viraram critérios de aceite com valores exatos em lamports, não histórias separadas [Q3].
- **Prioridade:** Must para US1 a US6 (com a US6 depois da Performance Validation do intent 261113); Should para o filtro (US2.3), o detalhe (US3.10) e os períodos (US7); Could para a comparação (US8) [Q4].

## Onde as histórias mais agregam valor

1. Os exemplos de rateio, taxa, ordem e conferência (US3.3 a US3.9, US5.3, US5.4), que são a base dos testes escritos antes do código.
2. O teste de nunca somar os modos e o spec de ponta a ponta da fatia fina (US4.1).
3. Os motivos da opção "Real" desabilitada e a confirmação do dinheiro real (US1.2, US1.3).

## Assumptions & Open Questions

As perguntas em aberto das regras de dinheiro estão no fim de `stories.md` (seção "Assumptions & Open Questions", itens 1 a 8).
