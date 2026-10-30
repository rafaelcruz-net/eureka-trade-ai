# Alocação dos Bolts — MemeTrade Analytics (beta PAPER por convite)

Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda. Este documento diz quem executa e quem aprova cada um dos 9 Bolts de [bolt-plan.md](bolt-plan.md), que constroem as Units de [unit-of-work.md](../units-generation/unit-of-work.md) na ordem permitida por [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md). As Units agrupam os componentes de [components.md](../domain-design/components.md) e atendem os requisitos de [requirements.md](../requirements-analysis/requirements.md).

## Equipe

A formação de equipe (Team Formation) foi pulada neste fluxo: o projeto tem uma única pessoa, o responsável do projeto, trabalhando com apoio de IA e sem orçamento (C-12), como registrado em [team-practices.md](../practices-discovery/team-practices.md). Não existe mais de uma equipe, então não há quadro de programa entre equipes.

| Papel | Quem | O que faz em cada Bolt |
|---|---|---|
| Execução | Agente desenvolvedor de IA (`aidlc-developer-agent`), com os especialistas de cada etapa (arquiteto, qualidade, segurança, plataforma) | Escreve design, testes e código da Unit, roda a verificação e prepara o merge |
| Aprovação e revisão | Responsável do projeto | Aprova o plano de código, confirma resumos, aprova cada ponto de controle e autoriza o comando de verificação; é a única revisão humana do dia a dia (`team.md`) |
| Portões automáticos | CI no GitHub Actions | Lint, formatação, tipos, testes, cobertura, contrato gerado, varreduras de segredos, dependências e SAST; nenhum merge com a CI vermelha |

## Alocação por Bolt

| Bolt | Unit | Executa | Aprova | Observação |
|---|---|---|---|---|
| B1 | U1 `u1-walking-skeleton` | Agente desenvolvedor | Responsável do projeto | Ponto de controle do esqueleto sempre humano |
| B2 | U2 `u2-audit-trail` | Agente desenvolvedor | Responsável do projeto | — |
| B3 | U4 `u4-discovery` | Agente desenvolvedor | Responsável do projeto | O responsável também conduz a SP-1 (Q5) |
| B4 | U7 `u7-ledger-pnl` | Agente desenvolvedor | Responsável do projeto | TDD obrigatório |
| B5 | U6 `u6-risk-control` | Agente desenvolvedor | Responsável do projeto | TDD obrigatório |
| B6 | U5 `u5-bots-decision` | Agente desenvolvedor | Responsável do projeto | TDD nas regras de pausa e encerramento |
| B7 | U9 `u9-dashboard` | Agente desenvolvedor | Responsável do projeto | — |
| B8 | U3 `u3-identity-access` | Agente desenvolvedor | Responsável do projeto | — |
| B9 | U8 `u8-admin-feedback` | Agente desenvolvedor | Responsável do projeto | — |

## Ritmo

Um Bolt por vez (Q4): o próximo começa só depois do ponto de controle do anterior. Depois do esqueleto verificado e aprovado, o fluxo oferece seguir automaticamente ou revisar cada ponto de controle; aprovação do plano de código, confirmação de resumo, escolha do comando de verificação e falhas continuam exigindo o responsável (`team.md`).

## Gates que não podem ser aprovados pelo responsável sozinho

Os gates G2 e G3 de REAL exigem um revisor independente ainda a nomear (C-14). Nenhum Bolt deste plano toca REAL, então nenhum deles depende desse revisor.
