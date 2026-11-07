# Requisitos de desempenho — U9 Painel e navegação

Base: [respostas](nfr-requirements-questions.md) Q1 a Q3 e Q5, [especificação funcional](../functional-design/functional-spec.md), [regras](../functional-design/rules.md) BR1 a BR5, NFR1 a NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e o canal C7 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

Todas as medições são no ambiente local de referência (Docker Compose no Windows), com fontes simuladas e relógio real, porque não há hospedagem (NFR10). As metas são revistas quando houver hospedagem, como o NFR3 já prevê.

## Perfil de carga de referência

| Item | Valor | Origem |
|---|---|---|
| Convidados simultâneos com o painel aberto | 20 | NFR2, NFR3 |
| Bots por convidado | 3 ativos e 20 encerrados | Q1 (inclui encerrados para exercitar a carga completa por estado, BR1.5) |
| Abas com o painel por convidado | até 2 | Q3 |
| Intervalo de leitura das marcas do canal | 2 s | C7 (U5) |

## Requisitos

| ID | Requisito | Meta e verificação | Origem |
|---|---|---|---|
| NFR3.1 | Cada quadro aparece rápido e sozinho | Cada quadro (bots, P&L, avisos de dados) chega a pronto, vazio ou erro em p95 ≤ 2 s depois da abertura do painel, no perfil de referência. Medido por um teste de carga local que abre o painel pelas mesmas rotas (bots por estado, última ordem por bot, P&L, reconciliação, conta, canal) com 20 sessões de teste | Q1, NFR3, BR1.2 |
| NFR3.2 | Carregando visível na hora | Os marcadores de carregando de cada quadro aparecem em ≤ 200 ms depois da navegação para `/`, sem esperar resposta da API. Teste de componente com relógio falso e rotas que não respondem | Q1, CT-UX-2 |
| NFR3.3 | Abertura sem rajada de chamadas | No máximo 3 buscas de última ordem ao mesmo tempo e uma carga completa de bots por vez; a carga completa segue `nextCursor` com `limit=100`. Teste do cliente que conta chamadas simultâneas | BR1.5, BR2.3 |
| NFR3.4 | Buscas de P&L sem acúmulo | Com 10 `pnl.updated` em 1 s, o painel faz no máximo 2 buscas de P&L e 2 de reconciliação. Teste com canal falso | BR3.3 |
| NFR1.1 | Mudança aparece rápido na tela | Entre a gravação de uma ordem ou mudança de estado de bot e a tela: p95 ≤ 5 s e máximo de 60 s no perfil de referência. Passar de 60 s reprova (AC5.4.1). Medido no teste de carga gravando mudanças pela API e lendo o horário do evento recebido no cliente de teste | Q2, NFR1, AC5.4.1 |
| NFR1.2 | Fonte velha sempre marcada | 100% das fontes com `lastUpdatedAt` mais antigo que 5 minutos aparecem na faixa de avisos em até 30 s, mesmo sem novo `data.status`. Teste de componente com relógio falso | NFR1, BR4.1 |
| NFR1.3 | Canal parado detectado | 60 s sem evento deixam o canal em `STALE` e mostram o aviso em até 61 s. Teste com relógio falso | BR5.4, AC5.4.3 |
| NFR2.1 | Canais por pessoa limitados | Um canal por aba, sem limite no app. O servidor aceita até 5 canais abertos por pessoa; o 6º recebe recusa e o app trata como queda (BR5.3). O teto é proposto aqui e confirmado ou retirado na NFR Design, porque o canal é da U5 | Q3, NFR2 |
| NFR2.2 | Abas sem foco mantêm o canal | O canal continua aberto com a aba em segundo plano; nenhuma reabertura extra por troca de aba. Teste de componente que simula `visibilitychange` | Q3 |

## Fora do escopo desta Unit

- Desempenho do canal no servidor (leitura das marcas, envio dos eventos): é da U5 e foi medido na NFR da U5.
- Desempenho das rotas de bots, P&L e reconciliação: são da U5 e da U7.
- Tamanho do pacote do app: sem meta numérica no beta; a casca não acrescenta dependência nova (ver [tech-stack-decisions.md](tech-stack-decisions.md)).
