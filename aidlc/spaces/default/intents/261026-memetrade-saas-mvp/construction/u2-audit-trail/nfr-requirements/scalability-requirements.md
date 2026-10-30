# Requisitos de escala — U2 Trilha de auditoria

Capacidade da Unit `u2-audit-trail` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) (F1, F2, F4, F5) e as regras de [rules.md](../functional-design/rules.md) (BR1.1, BR3.1, BR3.2, BR3.4), a partir do NFR2 e do FR2.3 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e da topologia de processos de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (C9, C20). Decisões em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q1, Q5, Q6).

## Requisitos

| ID | Requisito | Meta | Mecanismo | Origem |
|---|---|---|---|---|
| NFR2.1 | Volume de eventos da trilha | Até 100 mil eventos por dia e cerca de 37 milhões de linhas em 12 meses, com as metas de NFR3.1 e NFR4.2 | Tabela única, sem particionamento; índice por (`occurred_at` decrescente, `event_id` decrescente) para a ordem da consulta e índice por (`action`, `occurred_at`) para o filtro por ação | NFR2, FR2.3, Q1 |
| NFR2.2 | Tabela de saída com tamanho limitado | Entradas entregues ficam no máximo 7 dias; com 100 mil eventos por dia, até cerca de 700 mil linhas entregues; pendentes visíveis em `auditOutboxPending` | Limpeza diária em lotes (BR3.4) | BR3.4, Q4 |
| NFR2.3 | Relay absorve os picos | Picos de até 60 eventos por segundo (60 bots no mesmo minuto) entregues sem crescer a fila além de um ciclo | Relay a cada 1 s em lotes de até 100 (U1), cerca de 100 eventos por segundo por instância | NFR2, C9 |
| NFR2.4 | Tarefas agendadas corretas com uma ou várias instâncias | Expurgo e limpeza com uma única execução por vez, qualquer que seja o número de instâncias; relay sem mudança | Bloqueio de tarefas agendadas com tabela no banco (Q5); relay com reserva de linhas que pula linhas já reservadas (U1); pool de retenção de 1 conexão por instância, usado só por quem tem o bloqueio | BR3.2, Q5, Q6 |

## Plano de capacidade

| Dimensão | Beta local (U2) | Antes do beta multiusuário | Mecanismo de escala |
|---|---|---|---|
| Eventos por dia | Até 100 mil | Revisto após o discovery | Índices da NFR2.1 |
| Linhas na trilha | Cerca de 37 milhões em 12 meses | Idem | Particionamento por mês reavaliado com a hospedagem (Q1) |
| Espaço em disco da trilha | Estimativa: entre 20 e 30 GB em 12 meses, contando os índices (eventos de cerca de 500 bytes; a confirmar na carga sintética de NFR3.1) | Idem | Retenção de 12 meses |
| Instâncias de API | 1 | 2 ou mais | Bloqueio de tarefas no banco e relay com reserva de linhas (NFR2.4) |
| Conexões de banco da U2 | Pool da aplicação mais 1 conexão de retenção | 1 conexão de retenção por instância | Pool separado (Q6) |

## Degradação

- Quando o relay não acompanha, as entradas ficam pendentes na tabela de saída e as ações de negócio continuam; a saúde da trilha fica degradada acima de 60 s (U1) e `auditOutboxPending` mostra o atraso ao administrador.
- Quando o expurgo não acompanha, a trilha cresce além de 12 meses só até a próxima execução bem-sucedida; a saúde fica degradada (NFR4.4).
- O particionamento por mês é reavaliado na decisão de hospedagem ou antes, se a medição de NFR3.1 com a trilha cheia passar da meta.

## Fora desta Unit

- Capacidade de 60 bots ativos (NFR2): U5. A U2 só dimensiona os eventos que eles geram.
