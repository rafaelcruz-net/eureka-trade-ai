# Desenho de escala — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/nfr-requirements/scalability-requirements.md` (NFR3.6 a NFR3.10, NFR9.1), `construction/u1-bot-wallet/functional-design/functional-spec.md` (F3) e `nfr-design-questions.md` (Q2, Q3).

## Varredura de depósitos

- A cada ciclo, primeiro as carteiras com a tela de depósito aberta nos últimos 30 min, depois as outras em rodízio a partir de onde o ciclo anterior parou.
- **Dono do limite:** só o worker chama o RPC, pela ChainWatcher, que mantém um limitador de taxa por provedor. A API não chama o RPC. A conferência de saldo na pausa por divergência (BR4.6) usa o último saldo da rede gravado pela varredura, com no máximo 15 s.
- **Divisão do limite [Q3]:** 80% de `rpcMaxRps` por provedor para a varredura de depósitos. Os 20% restantes ficam para o acompanhamento de saques, que usa uma consulta `getSignatureStatuses` em lote por ciclo (até 256 assinaturas), mais o blockhash e o envio.
- **Duração do ciclo** = carteiras ÷ (0,8 × `rpcMaxRps`):
  - plano gratuito (5 por segundo): 60 carteiras em 15 s;
  - plano pago (50 por segundo): 1.000 carteiras em 25 s.

  Isso muda os números de NFR3.7, que contava 100% do limite (75 carteiras e 20 s), por causa da reserva de 20% decidida aqui. A meta de NFR3.5 continua valendo, porque ela é "ciclo mais 30 s" quando o ciclo passa de 15 s. O piloto de 21 carteiras fica bem abaixo do limite.

## Concorrência

- Os pedidos de saque de carteiras diferentes não se bloqueiam (trava por carteira, NFR3.8).
- O acompanhamento de saques usa só `SKIP LOCKED`, sem ShedLock, com lotes de 20 e até 4 em paralelo. Pode ter mais de uma instância de worker no futuro.
- O TxSigner tem uma instância no piloto. Ele não guarda estado em memória além do cache da chave pública, então pode ter mais instâncias depois de 100 carteiras (NFR3.10).

## Capacidade

| Item | Piloto | Desenho |
|---|---|---|
| Carteiras | 21 | 1.000 |
| Assinaturas por segundo | menos de 0,1 | 1 |
| Linhas novas por mês | menos de 10.000 | cerca de 500.000 |

A revisão de escala e de custo (NFR9.1) acontece antes de passar de 100 carteiras.
