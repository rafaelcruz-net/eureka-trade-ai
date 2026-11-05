# Design de desempenho — U6 Barreira de limites

Como a Unit `u6-risk-control` atende NFR2.1, NFR2.2, NFR3.1 e NFR3.2 de [performance-requirements.md](../nfr-requirements/performance-requirements.md).

## Avaliação no ciclo (NFR2.1, p95 ≤ 250 ms)

| Etapa | Desenho | Orçamento |
|---|---|---|
| Preço e liquidez | Duas chamadas da C11 (`referencePrice`, `liquidity`), já servidas pelo índice do MarketData da U4 | 40 ms |
| Visão de risco | Uma chamada da C13 (`snapshot`) para o token e o dia UTC | 80 ms |
| Procura da decisão | Uma consulta pelo índice único `order_evaluation (tenant_id, mode, bot_id, decision_id)` | 20 ms |
| Verificações | Função pura em memória sobre um registro imutável com os dados lidos; sem acesso a banco | 5 ms |
| Gravações | `INSERT ... ON CONFLICT DO NOTHING RETURNING` da avaliação; na recusa, um `INSERT` da recusa, um `INSERT` em lote dos detalhes e a publicação na tabela de saída (C9) | 60 ms |
| Folga | | 45 ms |

- **Sem cache.** Nenhum valor é guardado entre chamadas (NFR10.5). A economia viria de dados que mudam a cada coleta; o risco de recusar ou aprovar com dado velho não compensa.
- **Mesma conexão.** Tudo roda na conexão e na transação do ciclo aberta pela U5. A U6 não abre conexão própria.
- **Tempos de espera.** Valem o `lock_timeout` de 2 s e o `statement_timeout` de 5 s que a U7 define na transação (C15). Estourar um deles é falha técnica e desfaz o ciclo (NFR10.2).

## Barreira mais execução (NFR2.2, p95 ≤ 500 ms)

A soma é medida no mesmo cenário de carga quando o ciclo da U5 existir. Até lá, o cenário `u6` mede só a barreira, com um ciclo de teste no perfil `loadtest`, como a U7 fez.

## Rota de recusas (NFR3.1, p95 ≤ 1 s)

- **Índice.** `order_rejection (tenant_id, owner_user_id, mode, bot_id, evaluated_at DESC, rejection_id DESC)` serve o filtro e a ordem da página.
- **Detalhes.** Os detalhes da página são lidos numa segunda consulta, por `rejection_id = ANY(...)`, sem N+1.
- **Cursor.** O cursor guarda o último `(evaluated_at, rejection_id)` e é assinado pelo mecanismo comum de cursores das Units anteriores.
- **Total.** O `total` usa contagem limitada: conta até 10.001 linhas pelo mesmo índice e devolve "mais de 10.000" acima disso, sem percorrer a tabela.
- **Existência do bot.** A verificação do BR4.1 (alguma avaliação do bot para a pessoa) é um `EXISTS` no índice da decisão, prefixado por (tenant, modo, bot) e filtrado pela pessoa.

## Tela do bot (NFR3.2)

A tela é da U5. A U6 só garante a rota (NFR3.1); o aceite do NFR3.2 fica no estágio da U5.

## Medição

O cenário `u6` de `scripts/load-test.sh` usa os valores abaixo, e o relatório vai para `docs/perf/u6-risk-control-<data>.md`:
- 60 bots, um ciclo de teste a cada 30 s, metade das avaliações recusadas, por 10 minutos;
- 20 leitores da C21 sobre 10.000 recusas por bot;
- o expurgo rodando ao mesmo tempo, para o NFR2.5.
