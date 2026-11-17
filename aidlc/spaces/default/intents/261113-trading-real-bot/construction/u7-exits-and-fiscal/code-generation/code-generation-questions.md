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

[Approval Fingerprint]: sha256:v3:09754ca61db86eab95dda5d9591e98456554971a76c21f2cc011ca7bc96ebe5c
[Planned Source]: e356bd4fde9fa138a73ce7623933bfb0bc4d0c1b0606f213309846f272f1ab6b

- A. Approve Plan
- B. Request Changes
- C. I'll edit the files

[Answer]: A. Approve Plan
