# Avaliação de histórias de usuário — Trading REAL

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md` e o mapa do código (`business-overview.md`, `component-inventory.md`).

## Decisão

**Executar.**

## Justificativa

- Funcionalidade voltada ao usuário, com dinheiro real: ativação da carteira do bot, depósito, modos, saque e acompanhamento.
- Três personas com objetivos diferentes (fundador operando REAL, convidado, administrador).
- Lógica de negócio complexa: teto, limites, perda do dia, ordem ambígua, concorrência e kill switch.
- A revisão dos requisitos apontou lacunas de teto, limites, concorrência e ordem ambígua; as histórias fecham essas lacunas nos critérios de aceite (perguntas Q4 a Q11 de `user-stories-questions.md`).

## Fatores considerados

| Fator | Situação |
|---|---|
| Tipo de projeto | brownfield, funcionalidade nova em vários módulos |
| Escopo voltado ao usuário | telas novas no app do convidado e no app do administrador |
| Complexidade | alta (máquina de estados da ordem, reconciliação, carteira com chave no KMS) |

## Onde as histórias agregam mais valor

1. Critérios de aceite testáveis para teto, limites e perda do dia.
2. Comportamento claro em concorrência: saque, kill switch e troca de modo com ordem em andamento.
3. Saída definida para a ordem ambígua.
