# Dependências externas — Termos de uso e LGPD

Conversa: português (Brasil). Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda.

Este mapa lista o que está fora do desenvolvimento e pode segurar os Bolts de [bolt-plan.md](bolt-plan.md). Fontes:
- [requirements.md](../requirements-analysis/requirements.md) (questões abertas);
- [contract-summary.md](../contract-design/contract-summary.md) (questões abertas);
- [unit-of-work.md](../units-generation/unit-of-work.md) e [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md);
- [components.md](../domain-design/components.md);
- [raid-log.md](../../ideation/feasibility/raid-log.md).

## Mapa

| Dependência | Tipo | Dono | Prazo | Bolt afetado | Bloqueia a construção? | Se atrasar |
|---|---|---|---|---|---|---|
| Mudanças no `ci.yml` (lacunas do app administrativo e do web) | Configuração da CI | Responsável do projeto | Antes do primeiro commit deste trabalho | B1 em diante | Sim, o commit | Commit fica parado até a CI cobrir os dois apps |
| Spec novo de ponta a ponta no `verify.sh` | Script de verificação | Responsável do projeto | Antes do ponto de controle da U1 | B1 (e um spec por Unit seguinte) | Sim, o ponto de controle | O ponto de controle espera |
| Texto final dos termos v2 e da política, revisado | Aprovação externa | Assessoria jurídica | Antes da publicação da versão do beta | Nenhum Bolt; afeta a publicação | Não | Versão fica não publicável (FR1.4) |
| E-mail do encarregado | Decisão | Responsável do projeto | Antes da publicação | Nenhum Bolt | Não | Política mostra "a definir" |
| Local de armazenamento e transferência internacional | Decisão de hospedagem | Responsável do projeto | Quando a hospedagem existir | Nenhum Bolt | Não | Política mostra "a definir"; o beta não publica |

## Leitura rápida

- **Bloqueiam a construção:** só o `ci.yml` (antes do primeiro commit) e o spec no `verify.sh` (antes do ponto de controle da U1), ambos com o responsável do projeto.
- **Bloqueiam a publicação, não a construção:** texto revisado, e-mail do encarregado e local de armazenamento.
