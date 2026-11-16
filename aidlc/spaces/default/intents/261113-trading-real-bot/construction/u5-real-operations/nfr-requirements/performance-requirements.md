# Requisitos de desempenho — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/functional-design/functional-spec.md` (F1 a F10), `inception/requirements-analysis/requirements.md` (NFR3) e `nfr-requirements-questions.md` (Q1).

Os IDs têm o prefixo `U5-`. O número depois do prefixo indica o NFR da intent atendido.

| ID | Operação | Meta | Como medir |
|---|---|---|---|
| U5-NFR3.1 | Reconciliação de uma carteira | p95 até 5 s. A carteira faz no máximo três chamadas ao RPC: uma leitura em lote (`getMultipleAccounts`) da conta do sistema e das contas de token, a assinatura mais recente e o slot finalizado | `real_reconciliation_seconds`, buckets 0,5, 1, 2, 5, 10, 30 s |
| U5-NFR3.2 | Rodada de 15 minutos para 21 carteiras | máximo de 60 s por rodada. São no máximo 4 carteiras ao mesmo tempo (executor limitado): 6 levas de até 5 s dão 30 s, com folga para a troca de provedor | `real_ops_job_seconds{job="reconciliation"}`, máximo da rodada |
| U5-NFR3.3 | Trabalho de portões | máximo de 10 s por execução. A CI é lida uma vez por SHA em cada execução (tempo limite de 5 s), e todas as liberações vencidas usam essa mesma leitura | `real_ops_job_seconds{job="gates"}` |
| U5-NFR3.4 | `GET /api/v1/admin/real/users` (página de 50) e `/users/{userId}` | p95 até 500 ms | histograma HTTP do Spring com buckets publicados (`percentiles-histogram` ligado para essas rotas) |
| U5-NFR3.5 | Kill switch | ver a seção abaixo | testes de integração por consumidor |

## Kill switch: o que "imediato" garante (U5-NFR3.5)

**Leitura da M7:** é uma leitura de uma linha só (`real_kill_switch`), sem cache, com p95 de até 5 ms. Cabe nos 3 s do NFR3 da intent. Três pontos a leem:
1. a barreira (U3), no ciclo do bot;
2. a reconferência antes de assinar (U4);
3. a reconferência antes de enviar (U4).

**Garantia:** depois do commit do interruptor, nenhuma transação é enviada à rede, a menos que o envio já tivesse começado antes do commit.
- Uma decisão aprovada numa transação aberta antes do commit é recusada numa das duas reconferências.
- Uma ordem já entregue ao TxSigner pode voltar assinada, mas a reconferência antes de enviar a cancela.
- O tempo entre o commit e a última recusa possível é o de um passo do condutor, abaixo de 1 s.

**Testes:** um teste por consumidor, com o interruptor ligado no meio:
- a barreira;
- antes de assinar, com a ordem já entregue ao TxSigner;
- antes de enviar.

O cancelamento das ordens não enviadas termina em até 2 minutos, medido por `real_kill_switch_unsent_orders` chegando a 0.

## Condição de medida

- **Antes da produção:** teste de integração com PostgreSQL em contêiner, RPC do validador local e 21 carteiras com 10 contas de token cada.
- **Na produção:** pelas métricas.
- **Sem portão na CI** (`team.md`).
- **Depois da mainnet:** as metas de 5 s por carteira e 60 s por rodada são revistas com a latência real dos dois provedores de RPC.
