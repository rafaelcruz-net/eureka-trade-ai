# Desenho de desempenho — U2 Trilha de auditoria

Como a Unit `u2-audit-trail` cumpre [performance-requirements.md](../nfr-requirements/performance-requirements.md) (NFR3.1 a NFR3.3), para os fluxos F1, F3, F4 e F5 de [functional-spec.md](../functional-design/functional-spec.md), com o volume de [scalability-requirements.md](../nfr-requirements/scalability-requirements.md) e as escolhas de [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md). Decisões em [nfr-design-questions.md](nfr-design-questions.md).

## Consulta da trilha (NFR3.1)

| Aspecto | Desenho |
|---|---|
| Índice sem filtro | `audit_event (occurred_at DESC, event_id DESC)` |
| Índice com filtro por ação | `audit_event (action, occurred_at DESC, event_id DESC)`. O `event_id` entra no índice para o desempate do cursor sair do próprio índice, sem ordenação extra |
| Índice de `recorded_at` | `audit_event (recorded_at)`, usado só na contagem do dia na partida (ver [observability-design.md](observability-design.md)) |
| Índices da U1 | `audit_event_action_time_idx (action, occurred_at)` sai, substituído pelo índice com `event_id`. `audit_event_tenant_time_idx` e `audit_event_correlation_idx` ficam, para investigação por tenant e por correlação |
| Busca por cursor | Sem `OFFSET`: a página seguinte começa estritamente depois do par (`occurred_at`, `event_id`) do último item |
| Página | Lê `limit + 1` linhas; a linha a mais só diz se existe `nextCursor` |
| Conexões | Pool principal da U1 (10 conexões) |
| Cache | Nenhum: a consulta é rara e a trilha muda o tempo todo |

```sql
SELECT ... FROM audit_event
WHERE occurred_at >= :from AND occurred_at < :to
  AND (:action IS NULL OR action = :action)
  AND (occurred_at, event_id) < (:cursorOccurredAt, :cursorEventId)
ORDER BY occurred_at DESC, event_id DESC
LIMIT :limit + 1
```

Na primeira página, a condição do cursor é omitida. Na prática, o filtro por ação vira duas consultas preparadas, com e sem `action`, para cada uma usar o seu índice.

### Orçamento e medição

O orçamento por etapa de [performance-requirements.md](../nfr-requirements/performance-requirements.md) é uma referência de desenho. A meta é o p95 de ponta a ponta, medido no servidor. A carga sintética semeia cerca de 37 milhões de eventos, por SQL `generate_series`, num script só de teste. Com essa trilha cheia, o teste mede três cenários:

1. Consulta isolada.
2. Consulta com o relay entregando 60 eventos por segundo (NFR2.3).
3. Consulta durante um expurgo de 100 mil linhas.

O teste confere também, pelo `EXPLAIN` da consulta, que ela usa o índice e não ordena à parte. A meta de p95 até 2 s vale nos três cenários.

## Publicação (NFR3.2)

- A validação de BR1.2 e BR1.4 usa expressões pré-compiladas e percorre `details` uma vez; não acessa o banco.
- A inserção em `audit_outbox` é uma única linha por evento, na transação da ação.
- Os testes de desempenho das rotas que publicam, a começar pelo login da U1, rodam com o publicador completo e continuam cumprindo as metas da sua Unit.

## Expurgo e limpeza (NFR3.3)

| Tarefa | Lote | Seleção | Índice |
|---|---|---|---|
| Expurgo (pool de retenção) | Até 1000 linhas por transação | `DELETE ... WHERE event_id IN (SELECT event_id FROM audit_event WHERE occurred_at < :cutoff ORDER BY occurred_at LIMIT 1000)` | Índice por `occurred_at` |
| Limpeza (pool principal) | Até 1000 linhas por transação | Entradas com `delivered_at < agora - 7 dias` | Índice parcial `audit_outbox (delivered_at) WHERE delivered_at IS NOT NULL` |

- Antes de cada lote, a tarefa confere o tempo decorrido. Com 30 minutos ou mais, para e registra a parada pelo limite (ver [reliability-design.md](reliability-design.md)).
- Um dia normal tem cerca de 100 lotes, cada um com transação curta; o relay e a consulta não esperam por eles.
- `audit_event` recebe parâmetros próprios de autovacuum (fator de escala baixo), porque apaga cerca de 100 mil linhas por dia numa tabela de dezenas de milhões. Os valores exatos ficam no Infrastructure Design.

## Fora desta Unit

- Desempenho das rotas de negócio que publicam: Unit dona da rota.
