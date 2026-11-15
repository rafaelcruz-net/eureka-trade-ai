# Requisitos de desempenho — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/functional-design/functional-spec.md` (F1 a F6), `inception/requirements-analysis/requirements.md` (NFR3), `inception/contract-design/contract-summary.md` (X1, X3, R1) e `nfr-requirements-questions.md` (Q1).

Os IDs têm o prefixo `U4-` para não colidir com os de outras Units. O número depois do prefixo indica o NFR da intent atendido.

| ID | Operação | Meta | Como medir |
|---|---|---|---|
| U4-NFR3.1 | Da gravação da decisão ao envio, na Automática total, só das ordens que chegam ao envio | p95 até 3 s | histograma `real_order_decision_to_send_seconds{mode="AUTO_FULL"}`; as ordens que falham antes do envio contam só em `real_orders_total` com o código |
| U4-NFR3.2 | Da gravação da decisão ao início do consumo | p95 até 500 ms (o consumidor roda a cada 200 ms) | histograma `real_decision_queue_wait_seconds` |
| U4-NFR3.3 | Tempo limite por chamada (pior caso, não meta) | Jupiter 2 s; TxSigner 2 s; RPC 3 s por provedor, com troca para o segundo | configuração e testes com latência simulada |
| U4-NFR3.4 | `GET` de ordens (até 50 itens) e `GET /pnl` (até 50 posições) | p95 até 300 ms | histograma HTTP do Spring |
| U4-NFR3.5 | Do envio ao estado final ou `AMBIGUOUS` | garantido pela regra BR5.1, que passa a ordem a `AMBIGUOUS` aos 60 s | medidor `real_orders_sent_oldest_seconds`, com alerta acima de 75 s |

## Orçamento do p95 de 3 s (U4-NFR3.1)

| Etapa | p95 esperado |
|---|---|
| fila (U4-NFR3.2) | 0,5 s |
| cotação na Jupiter | 0,7 s |
| instruções de swap na Jupiter | 0,6 s |
| blockhash no RPC | 0,3 s |
| assinatura no TxSigner (KMS) | 0,4 s |
| envio no RPC | 0,5 s |
| **total** | **3,0 s** |

Os tempos limite de U4-NFR3.3 são o pior caso de uma chamada e não entram no orçamento. Uma recotação (BR3.2) fica fora do p95: ela é rara e é contada à parte em `real_orders_total{code="REQUOTED"}`.

**Condição de medida:**
- **Antes da produção:** teste de integração com PostgreSQL em contêiner, WireMock da Jupiter com 300 ms por resposta, TxSigner em processo com KMS simulado, RPC do validador local, 21 usuários e 4 decisões por segundo. Esse teste confere o orçamento das etapas internas e é informativo; não prova o p95 de produção, que depende da Jupiter, do RPC e do KMS reais.
- **Na produção:** pelas métricas.

Sem portão na CI (`team.md`).

**Regras:**
- Nenhuma chamada de rede dentro de transação de banco.
- O modo Assinada não entra na meta de 3 s, porque depende do usuário. A janela de 8 s é a regra BR4.2.
