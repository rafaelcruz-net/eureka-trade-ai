# Design de escala — U5 Bots e decisão

Como a Unit `u5-bots-decision` atende NFR2.4 a NFR2.6 de [scalability-requirements.md](../nfr-requirements/scalability-requirements.md), com a resposta Q2 de [nfr-design-questions.md](nfr-design-questions.md).

## Worker (NFR2.4, Q2)

- **Um worker só no beta.** O despacho de cada fatia usa ShedLock `bots-slot-dispatch`, com `lockAtLeastFor` de 4 minutos e `lockAtMostFor` de 5 minutos.
  - Um segundo worker que suba por engano não consegue despachar a mesma fatia, porque o bloqueio dura quase a fatia toda.
  - Se ele despachasse, o `BotCycle` único por (bot, fatia) ainda impediria o ciclo duplicado.
  - O cron roda em UTC (`zone = "UTC"`).
  - `slotStart` = instante do disparo truncado para o múltiplo de 5 minutos anterior.
- **Executor dos ciclos.** Tem 4 linhas e uma fila do tamanho do número de bots ativos.
- **Pool Hikari do worker.** Tem 14 conexões e `connectionTimeout` de 5 s. A conta:
  - 4 para os ciclos. O gravador de FAILED roda depois do rollback e reaproveita a vaga do ciclo (reliability-design.md).
  - 4 de reserva para gravações de outras Units que abrem transação própria de dentro do ciclo. Hoje é o caso do `REQUEST_FORBIDDEN` da U7 (`PnlAudit`), que só acontece com dono divergente.
  - 6 para as tarefas: o despacho, a meia-noite, a conferência do encerramento e o expurgo. No máximo 3 rodam juntas, e cada uma usa 1 conexão do ShedLock e 1 de trabalho.
- **Pico e medição.** O pico teórico é 14. O teste de carga `u5` registra o pico real de conexões ativas pelo Hikari e falha se alguma espera passar de 1 s.
- **API.** Mantém o pool atual.

## Índices

| Tabela | Índice | Uso |
|---|---|---|
| `bot` | (tenant_id, owner_user_id, state, updated_at DESC) | Lista e contagem de ativos |
| `bot` | parcial (state) onde `state = 'ACTIVE'` | Despacho da fatia |
| `bot` | (tenant_id, owner_user_id, change_seq DESC) | Marca do canal ao vivo |
| `bot_cycle` | único (bot_id, slot_start) | Uma fatia por bot |
| `decision` | (bot_id, created_at DESC, decision_id) | Lista de decisões |
| `decision` | parcial (created_at) onde `action IN ('HOLD','NO_TRADE')` | Expurgo |

## Volume (NFR2.5)

- Cerca de 520 mil decisões no pior caso.
- `factors`, `inputs` e `context` ficam em JSONB, com cerca de 3 KB por decisão, o que dá menos de 2 GB.
- O expurgo diário corre em lotes de 1.000 linhas.

## Canais (NFR2.6)

Um único leitor de marcas, com uma consulta a cada 2 s para todos os canais, faz com que o custo não cresça com o número de canais (Q1).
