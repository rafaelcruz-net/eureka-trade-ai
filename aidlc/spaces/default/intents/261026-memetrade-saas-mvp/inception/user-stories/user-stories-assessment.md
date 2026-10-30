# Avaliação da necessidade de histórias de usuário — MemeTrade Analytics

## Decisão

**Executar.**

## Justificativa

O beta é um produto com interface própria, usado por pessoas com objetivos diferentes: convidados que descobrem, avaliam e simulam, e o responsável do projeto, que administra convites e o desligamento geral. A lógica de negócio é complexa (limites de risco, decisão determinística, simulação, ledger e P&L), e as histórias com critérios Dado/Quando/Então são a base para os testes escritos antes do código no núcleo financeiro, conforme as práticas da equipe ([Team Practices](../practices-discovery/team-practices.md)).

## Fatores considerados

| Fator | Situação | Efeito |
|---|---|---|
| Tipo de projeto | Produto novo, multiusuário | Executar |
| Funcionalidades com interface | Sete telas do beta (wireframes S1 a S7) | Executar |
| Personas | Três (trader experiente, trader iniciante em automação, administrador) | Executar |
| Complexidade de negócio | Alta: risco, simulação, ledger, P&L e isolamento | Executar |
| Coordenação entre equipes | Projeto solo | Não pesa |

## Onde as histórias agregaram mais valor

- Transformaram os requisitos FR1 a FR13 de [requirements.md](../requirements-analysis/requirements.md) em 33 fatias verticais testáveis (30 Must, 3 Should), com a definição de pronto transversal e o anexo de transições do bot em [stories.md](stories.md).
- Fecharam em critérios de aceite as lacunas apontadas na revisão dos requisitos: regras das estratégias modelo (R-01), retomada após perda diária (R-02) e encerramento sem preço confiável (R-03), além das decisões Q8 a Q16 levantadas pela revisão da equipe.
- Separaram os itens de discovery (FR14) e a prova de conceito da delegação (FR15.1), que não são histórias de usuário, como spikes SP-1 a SP-5 para o planejamento de entrega.
