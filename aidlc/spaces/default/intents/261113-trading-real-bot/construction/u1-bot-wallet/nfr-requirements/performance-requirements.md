# Requisitos de desempenho — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/functional-design/functional-spec.md` (F1, F3, F5), `inception/requirements-analysis/requirements.md` (NFR3), `inception/contract-design/contract-summary.md` (tempos limite de Q9) e `nfr-requirements-questions.md` (Q5).

## Metas

| ID | Operação | Meta (p95) | Como medir |
|---|---|---|---|
| NFR3.1 | Desafio de consentimento e leituras da carteira (`GET /api/v1/real/bot-wallet`, depósitos, saques) | até 300 ms | teste de carga local com 1.000 carteiras |
| NFR3.2 | Resposta do pedido de saque (API) | até 500 ms | teste de carga |
| NFR3.3 | Do pedido de saque ao envio para a rede | até 5 s | métrica do worker no validador local; medida de novo na devnet com o KMS real antes da mainnet |
| NFR3.4 | Ativação, sem o tempo do usuário assinando | até 3 s | teste de integração com o KMS simulado; medido de novo com o KMS real antes da mainnet |
| NFR3.5 | Depósito visível no saldo depois de finalizado | até 45 s com ciclo de até 15 s (NFR3.7); com ciclo maior, até o ciclo mais 30 s | teste no validador local |

As metas são medidas no teste de carga com o perfil de NFR3.9, como medição informativa: não há portão de desempenho na CI (`team.md`). Uma meta não atingida antes da mainnet volta para o NFR Design.

## Orçamentos

- Chamada ao TxSigner: tempo limite de 2 s, com uma repetição (contract-summary Q9).
- Chamada ao RPC: tempo limite de 3 s por provedor, com troca para o segundo.
- A trava da carteira no pedido de saque espera no máximo 2 s (BR4.9).

## Fora de meta

O tempo de finalização da rede não é controlado pelo sistema. O acompanhamento vai até 60 s e, depois disso, a operação vira ambígua (NFR4).
