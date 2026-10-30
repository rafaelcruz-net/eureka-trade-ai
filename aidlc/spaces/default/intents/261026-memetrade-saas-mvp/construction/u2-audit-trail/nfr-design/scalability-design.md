# Desenho de escala — U2 Trilha de auditoria

Como a Unit `u2-audit-trail` cumpre [scalability-requirements.md](../nfr-requirements/scalability-requirements.md) (NFR2.1 a NFR2.4), para os fluxos F1, F2, F4 e F5 de [functional-spec.md](../functional-design/functional-spec.md), com a topologia de processos de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (C9, C20) e as escolhas de [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md). Decisões em [nfr-design-questions.md](nfr-design-questions.md) (Q2, Q6).

## Volume e forma da trilha (NFR2.1)

- Tabela única `audit_event`, sem particionamento, com os índices de [performance-design.md](performance-design.md).
- A agregação de `REQUEST_FORBIDDEN` ([security-design.md](security-design.md)) limita cada chave (quem, método, rota e código) a no máximo 2 eventos por minuto. Com o teto de 500 chaves e o resumo de estouro, o total fica em até 1.001 eventos de recusa por minuto por instância. Assim a trilha não cresce sem limite por chamadas repetidas.
- A métrica de eventos gravados por dia acompanha o teto de 100 mil. Acima de 80 mil, o alerta candidato dispara (ver [observability-design.md](observability-design.md)).
- **Quando reavaliar o particionamento por mês:** na decisão de hospedagem, se a trilha passar de 50 milhões de linhas ou se a medição de NFR3.1 com a trilha cheia passar da meta.

## Tabela de saída (NFR2.2)

- A limpeza diária mantém até 7 dias de entradas entregues: cerca de 700 mil linhas no teto de volume.
- As entradas pendentes não têm limite de idade. O atraso aparece na saúde, em `auditOutboxPending` (C8) e no alerta de relay parado.

## Relay e picos (NFR2.3)

- O relay da U1 entrega até 100 entradas por ciclo de 1 s, cerca de 100 eventos por segundo por instância. O pico previsto é de 60 eventos por segundo.
- A reserva de linhas que pula linhas já reservadas permite vários relays em paralelo, um por instância, sem dupla entrega; a chave única de `event_id` desduplica o resto.
- Um pico maior que a capacidade só atrasa a entrega. Nenhuma ação de negócio espera o relay.

## Várias instâncias (NFR2.4)

| Peça | Uma instância (beta local) | Várias instâncias |
|---|---|---|
| Expurgo e limpeza | ShedLock garante uma execução por vez | Igual: o bloqueio fica no banco |
| Recuperação ao subir | Roda se a última execução bem-sucedida passou de 24 h | Todas as instâncias tentam; o ShedLock deixa uma só rodar |
| Pool de retenção | 1 conexão, aberta só durante o expurgo (mínimo ocioso 0) | 1 por instância, usada só pela instância que detém o bloqueio |
| Relay | Reserva de linhas | Igual |
| Agregação de recusas | Contagem na memória, até 500 chaves | Cada instância conta e grava os seus resumos (Q6); o teto vale por instância |
| Tabela de estado das tarefas | Uma linha por tarefa | Igual: gravada só por quem rodou |

## Plano de capacidade

| Limiar | Sinal | Ação |
|---|---|---|
| Eventos por dia acima de 80 mil | Métrica de volume diário | Investigar a origem; rever o teto e os índices |
| Pendência mais antiga acima de 60 s | Saúde degradada | Investigar o relay; considerar lote maior ou intervalo menor |
| Trilha acima de 50 milhões de linhas | Contagem estimada da tabela | Reavaliar o particionamento por mês |
| Disco do banco acima de 70% | Monitoramento do host (hospedagem) | Planejar espaço; a estimativa é de 20 a 30 GB em 12 meses |
