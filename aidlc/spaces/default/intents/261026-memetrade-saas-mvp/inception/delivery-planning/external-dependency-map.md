# Dependências externas — MemeTrade Analytics (beta PAPER por convite)

> Revalidado em 2026-11-09: OQ-3 e OQ-4 resolvidas (ver "Leitura rápida").

Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda. Este mapa lista o que está fora do controle do desenvolvimento e pode segurar os Bolts de [bolt-plan.md](bolt-plan.md), com dono, prazo, Bolt afetado e plano se atrasar. As fontes são [requirements.md](../requirements-analysis/requirements.md) (FR14, FR15, restrições e questões abertas), [stories.md](../user-stories/stories.md) (SP-1 a SP-5), [contract-summary.md](../contract-design/contract-summary.md) (questões abertas), [unit-of-work.md](../units-generation/unit-of-work.md), [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md), [components.md](../domain-design/components.md) (dependências externas por componente) e [team-practices.md](../practices-discovery/team-practices.md).

## Mapa

| Item | Tipo | Dono | Prazo | Bolt afetado | Bloqueia? | Se atrasar |
|---|---|---|---|---|---|---|
| Docker Desktop no Windows | Ambiente | Responsável do projeto | Antes do B1 | Todos | Sim | Já instalado e funcionando (Q9) |
| Cota gratuita do GitHub Actions | Plataforma | Responsável do projeto | Sprint 0, antes do B1 | B1 e seguintes | Parcial | Reduzir a frequência da suíte completa na CI e rodar o comando de verificação localmente; o smoke continua em toda mudança |
| SP-1 — comparação de fontes e aprovação no G0 (FR14.1) | Investigação e aprovação | Responsável do projeto | Antes do fim do B3 (Q5) | B3 (só adaptadores reais e tolerância final de US2.4) | Não | B3 é aceito com fontes simuladas e tolerância configurável; adaptadores reais entram depois do G0 sem bloquear os demais Bolts |
| SP-2 — entrevistas e tarefas acompanhadas (FR14.2) | Investigação | Responsável do projeto | Antes de convidar pessoas | Nenhum Bolt; afeta a abertura do beta | Não | O beta fica fechado até a conclusão |
| SP-3 — inventário de dados pessoais e retenção (FR14.3) | Investigação e revisão jurídica | Responsável do projeto | Antes do design funcional do B2 (retenção da trilha) | B2, B3 (retenção de 24 h), B8 | Parcial | Usar a retenção proposta como parâmetro configurável e revisar quando o inventário sair |
| SP-4 — capacidade e orçamento (FR14.4) | Investigação | Responsável do projeto | Antes do NFR Design do B6 | B6 (meta de 60 bots ativos, NFR2) | Não | Manter a hipótese de NFR2 e medir no teste de carga |
| SP-5 — prova de conceito da delegação em devnet (FR15.1) | Investigação | Responsável do projeto | Sem prazo no beta | Nenhum | Não | REAL continua bloqueado (FR15.3) |
| Parecer jurídico (CVM, BCB, LGPD, PLD, tributação; C-09) | Aprovação externa | Responsável do projeto, com assessoria a contratar | Antes de convidar pessoas | Nenhum Bolt; afeta a abertura do beta | Não | O beta fica fechado até o parecer |
| Revisor independente para G2/G3 (C-14) | Pessoa externa | A nomear | Só para REAL | Nenhum | Não | REAL continua bloqueado |
| Moeda de referência e escala dos valores (OQ-3) | Decisão de produto | Responsável do projeto | Antes do design funcional do B4 | B4, B5, B6 | Sim | O B4 não começa o design funcional sem essa decisão |
| Duração dos tokens e da sessão (OQ-4) | Decisão de segurança | Responsável do projeto | No NFR Requirements do B1 | B1, B8, B9 | Parcial | Usar valores provisórios curtos e revisar |
| Carteiras Phantom e Solflare | Terceiro (extensão do navegador) | Fornecedores | Já disponíveis | B1, B8 | Não | Os testes usam carteira de teste gerada no código de teste, nunca no código de produção |
| Hospedagem, região e residência de dados | Decisão e orçamento | Responsável do projeto | Antes de convidar pessoas | Nenhum Bolt de construção; afeta Operation | Não | O beta continua só local (C-10, C-12) |

## Leitura rápida

- **Bloqueiam a construção:** só o Docker (já resolvido) e a decisão de moeda e escala (OQ-3), que precisa sair antes do B4.
- **Atualização de 2026-11-09 (revalidação depois da revisão do Contract Design):** a OQ-3 foi resolvida no Functional Design da U5 (saldo em USDC, 6 casas, preço com 12 casas, `HALF_EVEN`) e a OQ-4 no Functional Design da U1 (tokens de 15 min, 8 h e 12 h). As duas estão na tabela "Resolvidas" do `contract-summary.md`. Os nove Bolts foram construídos na ordem deste plano; a revisão do contrato só registrou o formato do canal C7 e a ordem de `GET /api/v1/bots`, sem efeito na sequência, na alocação nem nos riscos.
- **Bloqueiam a abertura do beta, não a construção:** SP-2, SP-3, parecer jurídico e hospedagem.
- **Não bloqueiam nada no beta:** SP-5 e o revisor independente, porque REAL segue bloqueado.
