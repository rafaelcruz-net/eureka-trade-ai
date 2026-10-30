# Requisitos de desempenho — U2 Trilha de auditoria

Metas mensuráveis da Unit `u2-audit-trail` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) (F1 publicar, F3 consultar, F4 expurgo, F5 limpeza) e as regras de [rules.md](../functional-design/rules.md) (BR2.2, BR2.3, BR3.2). Derivam do NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e das regras comuns de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (tempo limite do cliente de 10 s, paginação por cursor). Decisões em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q1, Q2, Q4). A meta de entrega de eventos à trilha está em [reliability-requirements.md](reliability-requirements.md) (NFR4.2).

Ambiente de referência: o ambiente local no Windows com Docker Compose (API e PostgreSQL), com a trilha semeada por carga sintética no volume da Q1: 12 meses a 100 mil eventos por dia, cerca de 37 milhões de linhas, distribuídos por pelo menos 20 valores de `action`.

## Requisitos

| ID | Requisito | Métrica e meta | Condição de carga | Como medir | Origem |
|---|---|---|---|---|---|
| NFR3.1 | Tempo de resposta da consulta da trilha | p95 ≤ 2 s por página, do recebimento da requisição ao fim da resposta, incluindo a gravação de `AUDIT_QUERIED` | 1 administrador; páginas de até 100 itens; período de 31 dias; com filtro por `action` e sem filtro; primeira página e páginas seguintes por cursor no início, no meio e no fim do período; trilha cheia como acima | Teste de carga leve contra o ambiente local com pelo menos 200 consultas, medindo o tempo do servidor | NFR3, Q2, BR2.2, BR2.3 |
| NFR3.2 | A publicação não piora as rotas que publicam | As metas de tempo de resposta já fixadas nas Units que publicam continuam valendo com a validação de BR1.2 e BR1.4 ativa (na U1, p95 ≤ 300 ms do login) | A carga de cada meta original | O teste de desempenho de cada rota que publica roda com o publicador completo da U2 | NFR3, BR1.2, BR1.4 |
| NFR3.3 | Expurgo e limpeza dentro do limite de tempo | Cada execução termina em até 30 minutos; ao chegar ao limite, para depois do lote corrente, mantém o que apagou e registra a parada | Expurgo de um dia de eventos (100 mil linhas) em lotes de até 1000; limpeza de um dia de entradas entregues (100 mil linhas) | Teste de integração com a trilha semeada, medindo a duração de uma execução e conferindo a parada por limite com um limite reduzido no teste | Q4, BR3.2, BR3.4 |

## Orçamento de tempo de uma página da consulta

| Etapa | Orçamento (p95) |
|---|---|
| Autenticação e conferência da sessão de administrador | 20 ms |
| Validação dos parâmetros e do cursor (BR2.2) | 10 ms |
| Leitura da página ordenada por índice, a partir do cursor (BR2.3) | 1.200 ms |
| Montagem e serialização dos itens | 200 ms |
| Gravação de `AUDIT_QUERIED` na tabela de saída (BR2.5) | 20 ms |
| Folga | 550 ms |
| **Total** | **2.000 ms** |

## Fora desta Unit

- Tempo de resposta das rotas de negócio que publicam eventos: cada Unit dona da ação (NFR3.2 só exige que a U2 não as piore).
- Tela de auditoria: fora do beta.
- Metas com hospedagem e volume real: revistas quando houver hospedagem (NFR3 é provisório).
