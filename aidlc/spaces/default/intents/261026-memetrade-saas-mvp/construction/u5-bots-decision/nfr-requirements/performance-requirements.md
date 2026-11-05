# Requisitos de desempenho — U5 Bots e decisão

Requisitos de tempo da Unit `u5-bots-decision`. As fontes são:
- o NFR2 e o NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- os fluxos de [functional-spec.md](../functional-design/functional-spec.md);
- as respostas Q1 a Q3 de [nfr-requirements-questions.md](nfr-requirements-questions.md);
- as pendências NFR2.2 e NFR3.2 deixadas pela U6.

As metas valem no ambiente local de referência e são revistas quando houver hospedagem.

## Requisitos

| ID | Requisito | Meta | Carga | Medição | Origem |
|---|---|---|---|---|---|
| NFR2.1 | Ciclo de um bot | p95 ≤ 2 s do início da transação ao commit (decisão, barreira, execução e gravações) | 60 bots ativos, metade com BUY ou SELL por fatia | Cenário de carga `u5` por 30 minutos (6 fatias) | NFR2, Q1 |
| NFR2.2 | Fatia completa | Os 60 bots terminam a fatia em até 120 s depois de `slotStart + 60 s` (até `slotStart + 180 s`), e nenhum ciclo é recusado por frescor só por ter rodado no fim da fila | A mesma | O relatório registra o fim do último ciclo de cada fatia e conta as recusas STALE_DATA da U6 e os NO_TRADE STALE_DATA (devem ser zero com a fonte operante) | NFR2, Q1 |
| NFR2.3 | Barreira mais execução (pendência da U6) | p95 ≤ 500 ms da soma de `OrderGate.evaluate` e `PaperExecution.execute` dentro do ciclo | A mesma | Medido no cenário `u5` | NFR2.2 da U6 |
| NFR3.1 | Rotas C4 | p95 ≤ 1 s em lista, rascunho, comandos, limites, decisões e estratégias | 20 usuários simultâneos, uma ação a cada 2 s, com 20.000 decisões por bot | Cenário `u5` | NFR3, Q2 |
| NFR3.2 | Tela do bot S5 (pendência da U6) | p95 ≤ 2 s do pedido até estado, uso do dia, operações, recusas e decisões visíveis | 20 usuários | O cenário `u5` mede o p95 do conjunto de chamadas que a tela faz (C4, C5 e C21 em paralelo), com 20 usuários. O smoke Playwright confere, com um usuário, que a tela fica visível em menos de 2 s | NFR3, NFR3.2 da U6 |
| NFR3.3 | Canal ao vivo | Uma mudança de estado do bot aparece no canal em até 4 s depois do commit (leitura das marcas a cada 2 s) | 20 canais abertos | Teste de integração com relógio real e o cenário `u5` | FR12.2, C7 |

## Frescor dentro da fatia (NFR2.2)

O ciclo não usa dado guardado do início da fatia. Cada bot lê o preço de referência e a liquidez no momento do próprio ciclo, e a U4 coleta a cada 30 s. Assim, um bot que roda em `slotStart + 180 s` lê um preço com no máximo cerca de 30 s, dentro dos 60 s da barreira da U6, e o fim da fila não causa recusa por frescor. As janelas de 5 minutos usam o último balde fechado em `slotStart`, que tem no máximo 3 minutos de idade nesse instante e está dentro dos 5 minutos da BR4.1. O cenário `u5` confere isso: zero recusas por frescor com a fonte operante.

## Medição sem métrica permanente (Q5)

Os tempos do NFR2.1 e do NFR2.3 são medidos pelo ciclo do perfil `loadtest`. Esse ciclo cronometra cada etapa (decisão, `evaluate`, `execute` e o commit) e grava os tempos no relatório de carga. Não existe histograma permanente em produção. Fora do perfil `loadtest`, essa instrumentação não existe.

## Orçamento do ciclo (p95 2 s)

| Etapa | Orçamento |
|---|---|
| Inserção do BotCycle, trava do bot e leituras de estado | 50 ms |
| Leituras de mercado (janelas, preço, scanner e avaliação) para até 10 tokens | 600 ms |
| Leituras da U7 (posições e saldo) | 100 ms |
| Decisão (memória) e gravação | 100 ms |
| Barreira e execução (NFR2.3) | 500 ms |
| Estado, motivos, `BotHolding` e eventos | 100 ms |
| Folga | 550 ms |
| **Total** | **2.000 ms** |
