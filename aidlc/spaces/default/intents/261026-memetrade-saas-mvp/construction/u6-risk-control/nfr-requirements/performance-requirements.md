# Requisitos de desempenho — U6 Barreira de limites

Requisitos de tempo de resposta da Unit `u6-risk-control`. As fontes são:
- o NFR2 e o NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- os fluxos F1 a F3 de [functional-spec.md](../functional-design/functional-spec.md);
- as respostas Q1 e Q2 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

As metas valem no ambiente local de referência (Docker Compose) e são revistas quando houver hospedagem.

## Requisitos

| ID | Requisito | Meta | Carga | Medição | Origem |
|---|---|---|---|---|---|
| NFR2.1 | Avaliação no ciclo | p95 ≤ 250 ms para `OrderGate.evaluate`, sem contar a decisão e a execução | 60 bots ativos, um ciclo por bot a cada 30 s, metade das avaliações recusadas | Teste de carga com um ciclo de teste que abre a transação, liga o contexto do dono e chama `evaluate`, medindo o tempo da chamada por 10 minutos | NFR2, Q1 |
| NFR2.2 | Barreira dentro do ciclo | A barreira mais a execução da U7 (p95 ≤ 250 ms) cabem no ciclo de 60 s com folga: p95 da soma ≤ 500 ms | A mesma do NFR2.1 | Medido no mesmo teste, quando o ciclo da U5 existir | NFR2 |
| NFR3.1 | Rota de recusas (C21) | p95 ≤ 1 s na primeira página (`limit` 50) | 20 usuários simultâneos, uma leitura a cada 2 s, com 10.000 recusas por bot | Script de carga local | NFR3, Q2 |
| NFR3.2 | Tela do bot com as recusas | p95 ≤ 2 s do pedido até a lista visível | 20 usuários simultâneos | Playwright com marcação de tempo, na U5, dona da tela | NFR3 |

## Orçamento da avaliação (p95 250 ms)

| Etapa | Orçamento |
|---|---|
| Leitura de preço e liquidez (C11) | 40 ms |
| Visão de risco do bot (C13) | 80 ms |
| Procura da avaliação anterior da decisão | 20 ms |
| Cálculos e verificações (só memória) | 5 ms |
| Gravação da avaliação, da recusa, dos detalhes e do evento da trilha | 60 ms |
| Folga | 45 ms |
| **Total** | **250 ms** |

## Regras de desempenho

- Uma única leitura da C11 e uma da C13 por avaliação (BR1.3). Nada é lido de novo no meio das verificações.
- A procura da avaliação anterior e a inserção sem abortar usam o índice único (tenant, modo, bot, decisão).
- As consultas da C21 usam um índice por (tenant, pessoa, modo, bot, horário decrescente, recusa) e paginação por cursor. Nunca há contagem total que percorra a tabela inteira: o `total` da página é limitado e calculado pelo mesmo índice.
- Os tempos de espera de trava e de instrução definidos pela U7 na transação do ciclo (`lock_timeout` de 2 s, `statement_timeout` de 5 s) também valem para as instruções da U6. Estourar um deles é falha técnica que desfaz o ciclo.
