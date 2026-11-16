# Projeto de desempenho — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/nfr-requirements/performance-requirements.md` (U5-NFR3.1 a U5-NFR3.5), `nfr-design-questions.md` (Q1 a Q3) e a `ChainPort` atual (só saldo em lamports).

## `ChainPort` ampliada (U1)

| Método novo | Chamada ao RPC | Usado por |
|---|---|---|
| `tokenBalances(owner)` | `getTokenAccountsByOwner`, uma vez para o Token e uma para o Token-2022 | reconciliação |
| `finalizedSlot()` | `getSlot` com `finalized` | reconciliação e restauração |
| `signaturesSince(address, until, before)` | `getSignaturesForAddress` paginado (1.000 por página) | restauração e depósitos não lidos |
| `transactionSigners(signature)` | `getTransaction`, só para assinaturas que o banco não conhece | restauração |

O `RpcBudget` ganha as finalidades `RECONCILIATION` e `RESTORE_CHECK`, com cota própria. A reconciliação nunca come a cota do saque nem a da execução.

| Requisito | Como o projeto atende |
|---|---|
| U5-NFR3.1 (5 s por carteira) | São 5 chamadas: `getBalance`, 2× `getTokenAccountsByOwner`, `getSlot` e `getSignaturesForAddress` com limite 1 (entrada não lida). O ledger sai de uma consulta (M2 `view`). |
| U5-NFR3.2 (60 s por rodada) | Executor de 4 threads: 6 levas de até 5 s dão 30 s. |
| U5-NFR3.3 (10 s nos portões) | Uma chamada ao GitHub por SHA em cada execução. |
| U5-NFR3.4 (500 ms no administrador) | Paginação por usuário; ordens recentes em lote pela M6. |
| U5-NFR3.5 (kill switch) | A M7 lê `real_kill_switch` sem cache. A M1 `readRealState` inclui a pausa global, e a U3 e a U4 (`recheckBeforeSend` mudado) leem a M1. O `KillSwitchCancelJob` converge em até 2 min. |
