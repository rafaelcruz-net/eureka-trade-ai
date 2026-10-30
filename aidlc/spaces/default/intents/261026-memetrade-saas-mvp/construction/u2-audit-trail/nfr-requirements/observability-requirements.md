# Requisitos de observabilidade — U2 Trilha de auditoria

Observabilidade da Unit `u2-audit-trail` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) (F1 a F5) e as regras de [rules.md](../functional-design/rules.md) (BR1.2, BR1.4, BR3.3, BR4.1), a partir do NFR7 e do NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e das regras comuns de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (`correlationId`, `auditOutboxPending` em C8). Decisões em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q3, Q4). A base vem da U1: logs JSON com `correlationId`, métricas expostas localmente e nenhum rastreamento distribuído.

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR9.1 | Saúde e erro do AuditTrail | O AuditTrail expõe saúde com os três indicadores de [reliability-requirements.md](reliability-requirements.md) (relay, expurgo, limpeza) e contagem de erros por tarefa e na publicação | NFR9, Q4 |
| NFR9.2 | Métricas da U2 | As métricas da tabela abaixo existem e são conferidas por teste; nenhuma tem rótulo com endereço, `actor`, `tenantId`, `eventId` ou valor de `details` | NFR9, BR4.1 |
| NFR9.3 | Logs das tarefas e da publicação | Cada execução do expurgo e da limpeza registra início e fim em `INFO` com corte, quantidade apagada, duração e resultado; falha em `ERROR` com a causa técnica; recusa do publicador em `ERROR` com `action` e o motivo (campo faltando, formato, chave proibida ou forma proibida), nunca o valor recusado | NFR9, BR1.2, BR1.4 |
| NFR9.4 | `correlationId` nas tarefas e na consulta | Cada execução agendada gera um `correlationId` (U1) que aparece nos logs e em `AUDIT_RETENTION_PURGED`; a consulta usa o `correlationId` da requisição nos logs e em `AUDIT_QUERIED` | NFR9, BR2.5, BR3.3 |
| NFR9.5 | Indicador de entrega com meta | SLI: proporção de eventos com `recordedAt` até 5 s depois da criação da entrada na tabela de saída. SLO: 99%, medido em janela de 1 hora de carga no ambiente local; a janela operacional contínua é definida com a hospedagem. Alertas candidatos ficam na tabela abaixo | Q3, NFR4.2 |

## Métricas da U2

| Métrica | Tipo | Uso |
|---|---|---|
| Pendências da tabela de saída e idade da mais antiga (U1) | Medidor | Atraso do relay e saúde |
| Tempo da criação da entrada até a gravação na trilha | Histograma | Conferir NFR4.2 e NFR9.5 |
| Eventos publicados por `action` | Contador | Volume (NFR2.1) |
| Publicações recusadas por motivo | Contador | Defeito de chamador (BR1.2, BR1.4) |
| Eventos apagados pelo expurgo e duração da execução | Contador e histograma | Retenção (NFR3.3) |
| Entradas apagadas pela limpeza e duração da execução | Contador e histograma | Tabela de saída (NFR2.2) |
| Horário da última execução bem-sucedida e resultado da última, por tarefa | Medidor | Saúde (NFR4.5) |
| Consultas da trilha por status e latência | Contador e histograma | Conferir NFR3.1; `403` em volume indica tentativa de acesso |

## Logs

| Nível | Quando | Retenção local |
|---|---|---|
| ERROR | Falha do relay, de um lote do expurgo ou da limpeza; recusa do publicador | 7 dias |
| WARN | Execução parada pelo limite de 30 minutos; tarefa atrasada rodando ao subir | 7 dias |
| INFO | Início e fim de cada execução do expurgo e da limpeza; consulta respondida (rota, status, quantidade de itens, sem conteúdo) | 7 dias |
| DEBUG | Só em desenvolvimento, desligado por padrão | Não guardado |

## Alertas candidatos

No ambiente local as métricas ficam só expostas, sem notificação (U1). Com a hospedagem, estes alertas são os candidatos, todos disparando antes de a meta ser violada por mais tempo:

| Alerta | Condição | Severidade |
|---|---|---|
| Entrega lenta | Proporção de eventos entregues em até 5 s abaixo de 99,5% por 15 minutos | Ticket |
| Relay parado | Pendência mais antiga com 60 s ou mais por 5 minutos | Chamado imediato |
| Expurgo ou limpeza sem sucesso | Saúde da tarefa degradada (NFR4.5) | Ticket |
| Recusa do publicador | Qualquer publicação recusada | Ticket (defeito de Unit chamadora) |

## Fora desta Unit

- Rastreamento distribuído: fora do beta local (U1).
- Painel e destinatários dos alertas: definidos com a hospedagem e a operação.
