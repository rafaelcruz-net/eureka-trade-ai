# Alocação dos Bolts — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21.

Um **Bolt** é uma passada de construção sobre uma parte do trabalho que termina em algo que roda. Um **mob** é o grupo que trabalha junto no mesmo Bolt, ao mesmo tempo, sobre o mesmo código. Este intent tem quatro Bolts, B1 a B4, um por Unit.

## Sources

- `delivery-planning-questions.md`: Q3 (um Bolt por Unit), Q4 (em série, uma Unit por vez) e o resumo confirmado.
- `bolt-plan.md` deste estágio: a sequência B1 a B4.
- `inception/units-generation/unit-of-work.md`: U1 a U4.
- `aidlc/spaces/default/memory/team.md`: Way of Working ("Revisão": trabalho solo, revisão do responsável nos gates do AI-DLC e portões da CI) e Walking Skeleton ("Depois do esqueleto").
- `aidlc-state.md` deste intent: o estágio de formação de equipes (Team Formation) não rodou; Construction Execution `serial`.

## Alocação

O Team Formation não rodou neste intent, e o trabalho é de uma pessoa só. Por isso, todos os Bolts são executados pelo `aidlc-developer-agent` (IA), e o fundador (responsável do projeto) é o único dono humano e revisor.

| Bolt | Unit | Executa | Revisa e aprova |
|---|---|---|---|
| B1 (esqueleto andante, a primeira fatia de ponta a ponta que funciona de verdade) | U1 `u1-mode-skeleton` | `aidlc-developer-agent` | fundador |
| B2 | U2 `u2-real-bot-pnl` | `aidlc-developer-agent` | fundador |
| B3 | U3 `u3-pnl-views` | `aidlc-developer-agent` | fundador |
| B4 | U4 `u4-own-quantity-cycle` | `aidlc-developer-agent` | fundador |

O mob de cada Bolt é o mesmo: o `aidlc-developer-agent` escreve o código e os testes, e o fundador decide nos gates. Os agentes de apoio e de revisão que cada estágio de Construction chama (por exemplo, a revisão de arquitetura ou de qualidade) entram quando o estágio os pede; eles não mudam a posse do Bolt.

## Onde o fundador decide

- Plan Approval de cada estágio de Construction e confirmação de resumo.
- Escolha do comando de verificação dos pontos de controle (pergunta própria no fim do Delivery Planning).
- Ponto de controle de cada Bolt, sobre um commit com a CI verde. O do B1 é o ponto de controle do esqueleto; depois dele, o framework oferece seguir automaticamente ou revisar cada ponto de controle.
- O ponto de controle interno da U2 e a decisão de dividir a U2, se ele atrasar.
- Os passos que só ele faz: acrescentar o spec novo ao `scripts/verify.sh`, aplicar textos prontos nos arquivos de `.github/workflows/`, pedir os commits e ligar a configuração da U4 numa implantação auditada separada.
- Qualquer falha de verificação.

## Program Board

Um **Program Board** é o quadro que mostra, ao longo do tempo, os Bolts de várias equipes e as dependências entre elas. Como há uma equipe só, não há Program Board neste intent: a sequência B1 → B2 → B3 → B4 do `bolt-plan.md` e o `external-dependency-map.md` cumprem esse papel.

## Assumptions & Open Questions

- [assumption] Não há paralelismo entre os Bolts: depois da U2, a U3 e a U4 poderiam andar juntas pelo grafo, mas o trabalho é solo e o estado registra execução em série [Q4].
