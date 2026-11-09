# Alocação dos Bolts — Termos de uso e LGPD

Conversa: português (Brasil). Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda. Um **mob** é o grupo que trabalha junto num Bolt.

Este documento diz quem executa e quem aprova cada um dos 5 Bolts de [bolt-plan.md](bolt-plan.md). Fontes:
- as Units de [unit-of-work.md](../units-generation/unit-of-work.md) e o grafo de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md);
- os componentes de [components.md](../domain-design/components.md) e os requisitos de [requirements.md](../requirements-analysis/requirements.md);
- a formação do time em [team-assessment.md](../../ideation/team-formation/team-assessment.md).

## Alocação

| Bolt | Unit | Executa | Apoio | Aprova |
|---|---|---|---|---|
| B1 | U1 `u1-legal-documents` | responsável do projeto | agentes de desenvolvimento, qualidade e arquitetura (revisão) | responsável do projeto (inclusive a aprovação do esqueleto) |
| B2 | U2 `u2-retention` | responsável do projeto | os mesmos | responsável do projeto |
| B3 | U3 `u3-reacceptance` | responsável do projeto | os mesmos | responsável do projeto |
| B4 | U4 `u4-data-requests` | responsável do projeto | os mesmos | responsável do projeto |
| B5 | U5 `u5-anonymization` | responsável do projeto | os mesmos | responsável do projeto |

## Fora dos Bolts

| Atividade | Quem | Quando |
|---|---|---|
| Revisão do texto final dos termos v2 e da política | assessoria jurídica | antes da publicação da versão do beta |
| Mudanças no `ci.yml` e spec novo no `verify.sh` | responsável do projeto (só ele altera esses arquivos) | antes do primeiro commit e do ponto de controle da U1 |
