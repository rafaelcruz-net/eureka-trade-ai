# Justificativa da ordem dos Bolts — Termos de uso e LGPD

Conversa: português (Brasil). Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda.

Este documento explica por que os 5 Bolts de [bolt-plan.md](bolt-plan.md) estão na ordem B1 (U1), B2 (U2), B3 (U3), B4 (U4), B5 (U5). Fontes:
- as Units em [unit-of-work.md](../units-generation/unit-of-work.md) e o grafo em [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md);
- os requisitos e as histórias em [requirements.md](../requirements-analysis/requirements.md) e [stories.md](../user-stories/stories.md);
- os componentes em [components.md](../domain-design/components.md).

## Heurística

**Fatia fina primeiro** (Cockburn), depois a **ordem do grafo com o risco cedo** (Cohn: atacar o risco técnico antes). Não usamos pontuação WSJF (Weighted Shortest Job First, que divide valor e urgência pelo tamanho): o grafo já fixa a ordem, e só U2 e U3 podem trocar de lugar (Q2).

## Ordem e motivo

| Ordem | Bolt | Motivo |
|---|---|---|
| 1 | B1 (U1) | Fatia fina: prova que catálogo, API, app sem login e `verify.sh` se conectam. Todas as outras Units dependem dela |
| 2 | B2 (U2) | Reduz o maior risco cedo: a trilha sem a carteira completa é pré-requisito da anonimização sem resíduo. Cria a base do módulo `privacy` que U4 estende |
| 3 | B3 (U3) | Novo aceite, que U4 precisa para registrar o prefixo dos pedidos na lista liberada |
| 4 | B4 (U4) | Pedidos de titular, que dependem de U1, U2 e U3 |
| 5 | B5 (U5) | Anonimização, a de maior risco, que depende de U4 (pedido de Exclusão e verificação) e de U2 (trilha minimizada) |

**Desvio da ordem topológica:** nenhum. A única escolha livre (U2 antes de U3) segue o critério de risco (Q2, Q6).

## Riscos e onde são tratados

| Risco | Bolt | Tratamento |
|---|---|---|
| Carteira residual depois da anonimização | B2, B5 | minimização na trilha (B2); apagar desafios, testes "sem resíduo", falha, repetição e concorrência (B5) |
| Política diferente do sistema | B2 | teste de conformidade da tabela de retenção |
| Bloquear rotas erradas no novo aceite | B3, B4 | lista de rotas liberadas testada; prefixo dos pedidos em B4 |
| Prazo de 15 dias perdido | B4 | evento, métrica e saúde no vencimento |
| `ci.yml` e `verify.sh` dependem de uma pessoa | B1 | pré-requisito explícito antes do primeiro commit |
