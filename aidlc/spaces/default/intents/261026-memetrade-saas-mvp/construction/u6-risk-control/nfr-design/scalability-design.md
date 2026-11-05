# Design de escala — U6 Barreira de limites

Como a Unit `u6-risk-control` atende NFR2.3 a NFR2.6 de [scalability-requirements.md](../nfr-requirements/scalability-requirements.md).

## Concorrência (NFR2.3)

- A barreira não tem trava própria. A serialização por bot vem da trava da linha do bot que a U5 faz no ciclo. A unicidade da decisão vem do índice único, com inserção que não aborta a transação.
- A transação do ciclo roda em `READ COMMITTED`, o nível padrão do PostgreSQL e o da U7. Assim, depois de a inserção esperar o vencedor de uma corrida, uma leitura nova enxerga a avaliação confirmada. O teste E20 roda nesse nível e falha se a transação estiver em `REPEATABLE READ` ou `SERIALIZABLE`. Isso responde a um achado Minor da revisão do design funcional.
- Bots diferentes nunca disputam a mesma linha nem a mesma chave da U6. Não há trava por tabela, por tenant nem por pessoa.
- A API e o worker não guardam estado da U6 em memória, então mais de uma instância de cada um funciona sem coordenação. Só o expurgo usa o ShedLock.

## Índices

| Índice | Uso |
|---|---|
| `order_evaluation` único (tenant_id, mode, bot_id, decision_id) | Procura da decisão e inserção sem abortar (BR2.8); `EXISTS` da rota C21 |
| `order_evaluation` parcial (evaluated_at) onde `outcome = 'APPROVED'` | Seleção do lote do expurgo (pedido da revisão dos requisitos) |
| `order_rejection` (tenant_id, owner_user_id, mode, bot_id, evaluated_at DESC, rejection_id DESC) | Página da rota C21 |
| `order_rejection` (evaluation_id) | Conferência da chave estrangeira no expurgo, sem varrer as recusas |
| `rejection_reason_detail` chave (rejection_id, reason) | Leitura dos detalhes da página |

## Expurgo em lotes (NFR2.5)

1. Seleciona até N `evaluation_id` aprovados com `evaluated_at` anterior ao corte, sem recusa que os cite, pelo índice parcial com `NOT EXISTS` no índice de `order_rejection (evaluation_id)`.
2. Apaga esses identificadores numa transação curta. O gatilho confere de novo idade, resultado e papel.
3. Repete até um lote vir vazio ou até 30 minutos de rodada. O restante fica para o dia seguinte.

O tamanho N começa em 1.000 e é fixado pelo cenário de carga `u6`, com o expurgo rodando junto com 60 ciclos. Critério: o p95 da avaliação (NFR2.1) não sobe mais que 10% em relação à rodada sem expurgo, e nenhum lote leva mais de 100 ms. O valor final fica no relatório de carga e na configuração (`riskcontrol.purge.batch-size`), sem número fixo no código.

## Volume (NFR2.4, NFR2.6)

- **Base da projeção.** A projeção usa a duração prevista do beta, de 8 semanas, até a decisão do G1. Com a premissa de no máximo metade das avaliações recusadas, cada bot gera até 1.440 recusas por dia, cerca de 80.000 em 8 semanas.
- **Volume do teste de carga.** O teste usa 10.000 recusas por bot como volume de referência da página, porque a rota lê sempre a primeira página pelo índice e não depende do total. O relatório de carga registra também o tempo com 80.000 recusas num único bot, para confirmar que o índice mantém a meta.
- **Tamanho das tabelas.** O relatório de carga registra o tamanho das tabelas e dos índices depois do cenário.

## Fora do escopo do beta

Particionamento por mês das recusas e réplica de leitura. Ficam para depois do beta, se o relatório mostrar necessidade.
