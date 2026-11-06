# Desenho de escalabilidade — U8 Administração e feedback

Base: [requisitos de escalabilidade](../nfr-requirements/scalability-requirements.md).

| ID | Desenho |
|---|---|
| NFR2.4 | A U8 não carrega bots. A U5 aplica e retira a pausa em lotes com a trava de cada bot (C16). A U8 só recebe `changed` e `failed` |
| NFR2.5 | `feedback_entry` com índices (`created_at DESC, feedback_id`) para a lista paginada do administrador e (`user_id, created_at`) para o limite por hora. Página máxima de 100 |
| NFR2.6 | O expurgo apaga em lotes de 1.000 por `feedback_id`, cada lote na sua transação, até acabar. Uma execução diária às 03:00 UTC |
| NFR2.7 | A reconciliação e o expurgo usam o `@SchedulerLock` (ShedLock), já usado nas outras Units, e `@ProcessRole(API)`. A trava de aplicação (consultiva) é separada da trava do agendador |

O processo de API é único no beta. Com mais de uma réplica, a trava de aplicação e o ShedLock já garantem uma execução por vez, sem mudança de desenho.
