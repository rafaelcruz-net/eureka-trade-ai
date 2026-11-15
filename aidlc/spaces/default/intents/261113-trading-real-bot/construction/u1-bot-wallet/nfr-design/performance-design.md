# Desenho de desempenho — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/nfr-requirements/performance-requirements.md` (NFR3.1 a NFR3.5), `construction/u1-bot-wallet/functional-design/functional-spec.md` (F4) e `nfr-design-questions.md` (Q7).

## Decisões

| Meta | Como |
|---|---|
| NFR3.1 leituras até 300 ms | Saldo calculado numa consulta agregada sobre os lançamentos REAL da carteira, com índice por carteira e tipo; sem cache de saldo nem de estado de saque [Q7]. Listas paginadas por cursor |
| NFR3.2 pedido de saque até 500 ms | A API só calcula, trava, reserva e grava (uma transação curta). Montagem, assinatura e envio ficam no worker. A espera pela trava é de no máximo 2 s |
| NFR3.3 envio até 5 s | O worker pega saques prontos a cada 1 s, trata até 4 em paralelo e vai direto do blockhash à assinatura e ao envio (cerca de 1 s de espera, mais 3 s de blockhash, assinatura e envio no pior caso sem repetição). O TxSigner guarda a chave pública em cache na memória [Q7] |
| NFR3.4 ativação até 3 s | Uma ida ao TxSigner (criação de chave e política), com conferência local da assinatura antes |
| NFR3.5 depósito visível | Varredura a cada 15 s por cursor (`scalability-design.md`) |

## Banco

- Índices:
  - lançamentos por (`bot_wallet_id`, `entry_kind`) e por `idempotency_key` (único);
  - saques por (`state`, `updated_at`) e por (`tenant_id`, `idempotency_key`);
  - depósitos por `tx_signature` (único).
- Pools de conexão separados para a API e o worker, como hoje; o TxSigner tem pool próprio.

## Orçamento por pedido de saque (API)

Autenticação de 20 ms, trava e cálculo de 100 ms, gravação de 50 ms e folga de 330 ms, dentro dos 500 ms.
