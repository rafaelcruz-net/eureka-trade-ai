# Code Generation Plan Approval

AI-DLC writes this file when it asks you to approve the plan. To answer here
instead of in chat, write your answer after `[Answer]:` and tell me in chat when you are done.

## Plan Approval

Approve the code plan for u7-exits-and-fiscal?

- Builds: saídas automáticas (stop, alvo e OCO) com domínio, máquina de estados, API R5, trabalhos de leitura e cancelamento, interfaces de extensão (venda dupla, custódia na reconciliação, recusa na...
- Touches: `apps/api` (módulo novo `realexits`; `realexecution` com `DecisionConsumer`, `LiquidationJob`, troca de modo e `RealExitChanges`; `botwallet` com interfaces de extensão, `ReconciliationPol...
- Tests: cerca de 230 testes — estados, idempotência, venda dupla, lançamento do preenchimento, reconciliação com custódia, recusa na anonimização, CSV com a conferência do saldo, migração, expurgo e...
- Conversa: português (Brasil). Fontes:
- `construction/u7-exits-and-fiscal/functional-design/`: BR0 a BR9, S1 a S50, adendo de contrato;

Full plan: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u7-exits-and-fiscal/code-generation/code-generation-plan.md
Test instructions: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u7-exits-and-fiscal/code-generation/unit-test-instructions.md

[Approval Fingerprint]: sha256:v3:be84fc531f3ed85265bcd709994cd74120ae449ca0051d5047a2816dda795571
[Planned Source]: 9119dc1c13420fcec79637fa7cba2f4aa0b1144ccb6702f5c23e1ff3e06d54b8

- A. Approve Plan
- B. Request Changes
- C. I'll edit the files

[Answer]: A. Approve Plan
