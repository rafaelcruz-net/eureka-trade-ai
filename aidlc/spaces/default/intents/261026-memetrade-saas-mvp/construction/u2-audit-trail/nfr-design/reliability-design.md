# Desenho de confiabilidade — U2 Trilha de auditoria

Como a Unit `u2-audit-trail` cumpre [reliability-requirements.md](../nfr-requirements/reliability-requirements.md) (NFR4.1 a NFR4.7, NFR10.1, NFR13.1), para os fluxos F1, F2, F4 e F5 e as máquinas de estado de [functional-spec.md](../functional-design/functional-spec.md), o contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e as escolhas de [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md). Decisões em [nfr-design-questions.md](nfr-design-questions.md) (Q4, Q5).

## Exatamente um evento (NFR4.1, NFR4.7)

- O publicador exige transação ativa (propagação obrigatória, C9) e grava na tabela de saída. Se a gravação falha, a exceção desfaz a ação inteira. Uma ação sensível nunca confirma sem o seu evento.
- O relay da U1 entrega ao menos uma vez, e a chave única de `event_id` em `audit_event` descarta a repetição sem erro.
- Os testes de integração da U1 continuam (queda depois do commit, entrega forçada duas vezes). A U2 acrescenta dois: ação desfeita sem evento, e mesmo `eventId` publicado duas vezes com uma entrada e um evento.
- Relay, expurgo, limpeza e consulta indisponíveis nunca bloqueiam ações de negócio: elas só dependem da inserção na tabela de saída.

## Medida da entrega (NFR4.2)

- O PostgreSQL roda com `track_commit_timestamp=on` no Docker Compose e nos testes (Q5).
- Ao reservar o lote, o relay lê, para cada entrada, o horário do commit da transação que a inseriu (`pg_xact_commit_timestamp(xmin)`). Depois de gravar o evento, registra no histograma de entrega a diferença entre `recordedAt` e esse horário.
- Quando o horário de commit volta nulo, por exemplo numa linha confirmada antes de o registro ser ligado (ligar exige reiniciar o banco), a amostra é ignorada e conta em `audit_delivery_unmeasured_total`.
- A medida segue a resposta da Q3 dos requisitos, "depois do commit da ação", confirmada na Q5 deste estágio. Ela substitui a redação "depois da criação da entrada na tabela de saída" de NFR4.2 e NFR9.5, que é a leitura menos precisa da mesma meta.
- Na partida, a API confere se o registro de horário de commit está ligado. Desligado, ela registra `ERROR` e não publica a métrica de entrega; a saúde e o resto da trilha seguem normais. Um teste confere a configuração no Docker Compose e no banco de teste.
- O teste de NFR4.2 gera a carga de NFR2.1 com picos de 60 eventos por segundo por 1 hora e confere que 99% das entregas ficam em até 5 s.

## Tarefas agendadas (NFR4.3 a NFR4.6, NFR13.1)

| Tarefa | Horário (UTC) | Limite | Bloqueio | Resultado |
|---|---|---|---|---|
| Expurgo da trilha | 03:00 | 30 min | ShedLock: máximo 35 min, mínimo 5 min | `COMPLETED`, `FAILED` ou `TIME_LIMIT`, em `AUDIT_RETENTION_PURGED` e na tabela de estado |
| Limpeza da tabela de saída | 03:30 | 30 min | ShedLock: máximo 35 min, mínimo 5 min | `COMPLETED`, `FAILED` ou `TIME_LIMIT`, na tabela de estado |
| Resumo das recusas repetidas | A cada 10 s | — | Nenhum: cada instância grava os seus | `REQUEST_FORBIDDEN_REPEATED` por chave e de estouro |

- **Horários em UTC:** as expressões de agendamento declaram o fuso `UTC` de forma explícita; o corte é o início do dia UTC menos 12 meses, calculado pelo relógio injetável. Os testes cobrem as fronteiras do dia UTC com o relógio em fuso de Brasília.
- **Bloqueio mínimo de 5 min:** evita que a recuperação ao subir e o horário marcado, ou duas instâncias com relógios um pouco diferentes, rodem a mesma tarefa duas vezes seguidas.
- **Uma execução por vez (NFR4.6):** quem não obtém o bloqueio termina sem apagar nada e sem erro, com log `DEBUG`. Teste: dois agendadores disparados ao mesmo tempo sobre o mesmo banco geram uma execução.

### Estado das tarefas (Q4)

Tabela `audit_job_run`, do AuditTrail, com uma linha por tarefa:

| Coluna | Conteúdo |
|---|---|
| `job_name` | `AUDIT_RETENTION_PURGE` ou `AUDIT_OUTBOX_CLEANUP` (chave) |
| `last_run_at` | Início da última execução |
| `last_result` | `COMPLETED`, `FAILED` ou `TIME_LIMIT` |
| `last_success_at` | Fim da última execução com `COMPLETED` |
| `last_cutoff` | Último corte usado |

- A tarefa grava a linha ao terminar, numa transação própria com o pool principal.
- **Recuperação ao subir:** quando a aplicação fica pronta, cada tarefa roda logo se não tiver linha ou se `last_success_at` tiver mais de 24 h, sempre pelo ShedLock.
- **Estado inicial:** sem linha (banco novo, CI, testes), a tarefa roda ao subir e a saúde fica degradada até a primeira execução bem-sucedida.

### Falhas e parada pelo limite (NFR4.3, NFR4.4)

1. Cada lote é uma transação. Um lote que falha desfaz só a si mesmo; os lotes anteriores ficam apagados.
2. A tarefa para no primeiro lote com falha, ou antes de começar um lote com 30 minutos decorridos.
3. O expurgo publica `AUDIT_RETENTION_PURGED` com corte, quantidade, resultado e `correlationId`, pelo publicador normal e pelo pool principal, numa transação própria.
4. Se o banco não aceita essa gravação, a tarefa registra `ERROR` com a causa e tenta atualizar `audit_job_run`. Se nem isso for possível, a ausência de execução bem-sucedida deixa a saúde degradada depois de 26 h.
5. A limpeza só seleciona entradas com `delivered_at` preenchido há mais de 7 dias. Teste: entradas pendentes com 30 dias continuam depois da limpeza.

## Saúde da trilha

| Indicador | Saudável | Degradada |
|---|---|---|
| `auditRelay` (U1) | Pendência mais antiga abaixo de 60 s | 60 s ou mais |
| `auditRetention` | `last_result = COMPLETED` e `last_success_at` há até 26 h | Sem linha, `FAILED`, `TIME_LIMIT` ou `last_success_at` com mais de 26 h |
| `auditOutboxCleanup` | Idem | Idem |

Saúde degradada não derruba a API nem a prontidão. Banco indisponível deixa a API `DOWN`, como na U1. Os testes usam o relógio injetável para cobrir 25 h e 27 h desde a última execução bem-sucedida.

## Disponibilidade (NFR10.1)

Sem meta de disponibilidade no beta local. A meta de entrega de NFR4.2 é de integridade, não de disponibilidade.

## Testes (NFR12.1 a NFR12.3)

- **Cobertura:** JaCoCo com a regra de 80% de linhas no backend. As únicas exclusões da U2 são as classes de configuração dos pools e do agendador, listadas na configuração.
- **Testes obrigatórios por risco:**
  - trilha sem edição, por login e por operação;
  - exatamente um evento;
  - recusa de segredos no publicador;
  - varredura de logs, métricas e tabelas;
  - `403` auditado e agregado;
  - privilégios dos três logins;
  - fronteira do corte;
  - pendentes nunca apagadas;
  - uma execução por vez;
  - estado inicial e regras das 24 h e das 26 h.
- **Determinismo:** relógio injetável para corte, agenda, recuperação, janelas de agregação e saúde. O gatilho, que usa o relógio do banco, é testado com eventos de 13 e de 11 meses. Os testes não acessam a rede real e usam PostgreSQL em contêiner com as mesmas migrações e os três logins. A U2 segue test-after, como no `team.md`.

## Recuperação

| Situação | Comportamento |
|---|---|
| API reinicia | Entradas pendentes entregues no próximo ciclo do relay; tarefas atrasadas rodam ao subir; até 1 min de contagem de recusas repetidas se perde só em queda abrupta |
| Banco reinicia | Relay e tarefas falham com `ERROR` e tentam no próximo ciclo ou horário; a saúde mostra o atraso |
| Máquina desligada à noite | O expurgo e a limpeza rodam ao subir quando passam de 24 h sem sucesso |
| Rollback de versão | As migrações da U2 só acrescentam (logins, privilégios, índices, tabela de estado, função do gatilho); voltar a versão da API não exige desfazer o banco |
