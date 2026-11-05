# Requisitos de escala — U6 Barreira de limites

Requisitos de carga e volume da Unit `u6-risk-control`. As fontes são o NFR2 de [requirements.md](../../../inception/requirements-analysis/requirements.md), o design de [functional-spec.md](../functional-design/functional-spec.md) e a resposta Q5 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Projeção de carga

| Item | Valor no beta |
|---|---|
| Convidados | 20 |
| Bots ativos | 60 (3 por convidado) |
| Ciclos por bot | 1 a cada 30 s no pior caso, 2.880 por dia |
| Avaliações por dia | até 172.800 (todo ciclo com BUY ou SELL) |
| Avaliações aprovadas guardadas | até cerca de 5,2 milhões (30 dias, Q5) |
| Recusas guardadas | todas do beta; o teste de carga usa 10.000 por bot |

## Requisitos

| ID | Requisito | Meta | Origem |
|---|---|---|---|
| NFR2.3 | Concorrência do ciclo | 60 avaliações ao mesmo tempo, uma por bot, sem espera entre bots diferentes: nenhuma trava da U6 é por tabela inteira ou por pessoa, só a linha do bot (da U5) e a chave única da decisão | NFR2 |
| NFR2.4 | Volume da rota C21 | NFR3.1 mantido com 10.000 recusas por bot e 5,2 milhões de avaliações na tabela | NFR3, Q5 |
| NFR2.5 | Expurgo das aprovadas | Tarefa diária apaga avaliações aprovadas com mais de 30 dias em lotes de até 5.000 linhas por transação, sem travar o ciclo por mais de 100 ms por lote | Q5 |
| NFR2.6 | Crescimento das recusas | Índice por (tenant, pessoa, modo, bot, horário decrescente, recusa); o tamanho das tabelas é registrado no relatório de carga | Q5 |

## Fora do escopo do beta

- Particionamento de tabelas e réplica de leitura. Ficam para depois do beta, se o relatório de carga mostrar necessidade.
