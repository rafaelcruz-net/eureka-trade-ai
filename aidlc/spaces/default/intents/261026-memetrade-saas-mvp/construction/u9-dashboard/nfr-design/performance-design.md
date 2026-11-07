# Design de desempenho — U9 Painel e navegação

Base: [performance-requirements.md](../nfr-requirements/performance-requirements.md) (NFR1.1 a NFR1.3, NFR2.1, NFR2.2, NFR3.1 a NFR3.4), [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md), [functional-spec.md](../functional-design/functional-spec.md), as respostas de [nfr-design-questions.md](nfr-design-questions.md) e o canal C7 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

## Orçamento da abertura do painel

A abertura dispara, em paralelo, três grupos de chamadas. Cada grupo alimenta só o seu quadro, então a meta de p95 ≤ 2 s (NFR3.1) vale por grupo e um grupo lento não atrasa os outros.

| Grupo | Chamadas | Concorrência | Quadro |
|---|---|---|---|
| Bots | `GET /bots?state=<estado>&limit=100` para os 6 estados, seguindo `nextCursor`; depois `GET /bots/{botId}/trades?limit=1` dos bots que não são rascunho | 6 listas em paralelo; última ordem com no máximo 3 ao mesmo tempo | Bots PAPER |
| P&L | `GET /pnl?from&to` e `GET /reconciliation` | 2 em paralelo | P&L PAPER |
| Fontes | primeiro `data.status` do canal, ou `GET /scanner/tokens?limit=1` depois de 5 s sem ele | 1 | Avisos |
| Casca | `GET /account` uma vez por sessão (guardado no contexto de sessão) | 1 | Carteira |

No perfil de referência (3 ativos e 20 encerrados por pessoa) isso dá 6 listas e 23 buscas de última ordem na primeira abertura. A última ordem de um bot `STOPPED` não muda depois do encerramento, então fica guardada em memória pelo `DashboardProvider` durante a vida da tela e não é buscada de novo nas cargas completas disparadas por `bot.state` (BR1.5); só bots novos na lista e os que estão em uso são consultados de novo, e só quando o `trade.recorded` aponta o bot.

## Estratégias

| Requisito | Estratégia |
|---|---|
| NFR3.1 | Grupos independentes, cada um com o próprio estado no redutor; nenhum `Promise.all` entre grupos. A lista de bots aparece assim que as listas por estado respondem, antes das buscas de última ordem, que preenchem só a linha "Última operação" (com seu próprio carregando). |
| NFR3.2 | A primeira renderização do painel já tem os três quadros em `LOADING` com marcadores; nenhuma chamada bloqueia a renderização. A rota `/` é uma página do cliente sem busca de dados no servidor. |
| NFR3.3 | Um limitador simples de concorrência (fila com no máximo 3 em andamento) para as buscas de última ordem; a carga completa de bots tem uma única execução por vez, com a marca `reloadPending` para no máximo uma nova execução depois dela. |
| NFR3.4 | O P&L e a reconciliação usam o mesmo esquema de "uma em andamento, no máximo uma pendente" (BR3.3). 10 avisos em 1 s geram no máximo 2 buscas de cada. |
| NFR1.1 | O atraso fica dominado pela leitura das marcas a cada 2 s no servidor (C7). No cliente, cada evento é aplicado no redutor sem esperar outra chamada; só `trade.recorded` dispara uma busca curta da ordem completa, que não atrasa a linha (ela aparece primeiro com os dados do evento). |
| NFR1.2 | Um temporizador de 30 s (relógio injetável) recalcula a idade das fontes sem nova chamada. |
| NFR1.3 | Um temporizador de 60 s reiniciado a cada evento e na resposta `200` da abertura (BR5.3, BR5.4). |
| NFR2.1 | Teto de 5 canais por pessoa no servidor (Q1, ver [security-design.md](security-design.md)); o leitor no app trata o `429` como queda, com a espera crescente de BR5.3, sem tentar de novo antes de `Retry-After`. |
| NFR2.2 | Nenhum código reage a `visibilitychange`; o canal só fecha quando o painel sai da tela (BR5.6). |

## Leitor do `text/event-stream`

Leitor próprio (Q4 dos requisitos), sem dependência:

```
loop: chunk <- reader.read(); buffer += decode(chunk, stream: true)
  while buffer contém "\n\n" (ou "\r\n\r\n"):
    bloco <- retira até o separador
    linhas "id:", "event:", "data:" (várias data: juntam com "\n"); ":" é comentário
    entrega { id, event, data } ao validador de envelope
  se buffer > 1 MiB sem separador: fecha a leitura e trata como queda
```

O teto de 1 MiB por evento cobre com folga o maior evento esperado (`bot.state` com 100 bots, poucos KiB) e impede que um fluxo malformado esgote a memória da aba.

## Como as metas são medidas

| Meta | Medição |
|---|---|
| NFR3.1, NFR1.1 | Teste de integração Java com medição, no padrão de `AdminReadLatencyIT` e `KillSwitchPerformanceIT`: PostgreSQL em contêiner, 20 contas semeadas pelo perfil `loadtest` (3 bots ativos e 20 encerrados cada), 20 clientes HTTP do JDK fazendo as chamadas de abertura de cada grupo e abrindo o canal; p95 por grupo. Para NFR1.1, ordens gravadas pelos acionadores do perfil `loadtest` (`ledger/loadtest`) e mudanças de estado pela rota de comando; o atraso é o horário de recebimento do evento no cliente menos o da gravação. |
| NFR3.2, NFR3.3, NFR3.4, NFR1.2, NFR1.3, NFR2.2 | Testes de componente e de unidade no Jest com relógio falso, rotas simuladas que não respondem ou respondem com atraso controlado, e canal falso. |
| NFR2.1 | Teste de integração do canal que abre 6 canais da mesma pessoa (detalhe no design de segurança). |

As medições rodam na suíte completa do merge para `main`, não no smoke de cada mudança (`team.md`, Frequência).
