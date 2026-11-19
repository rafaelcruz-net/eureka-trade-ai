# Code Generation Plan Approval

AI-DLC writes this file when it asks you to approve the plan. To answer here
instead of in chat, write your answer after `[Answer]:` and tell me in chat when you are done.

## Plan Approval

Approve the code plan for u4-real-execution?

- Builds: execução das ordens REAL — bot REAL, fila de decisões, máquina de estados com uma transação por ordem, Jupiter, cossinatura no modo Assinada, política de swap do TxSigner, confirmação e amb...
- Touches: `apps/api` (módulo novo `realexecution`, `pnl.real`, `bots`, `shared.solana`, `RuntimeSettingsGuard`, migração 0019, canal ao vivo), `apps/txsigner` (`SwapPolicy`, leitor), `infra/kms-sim`...
- Tests: cerca de 260 testes — estados, idempotência e política de swap escritos antes do código; integração com PostgreSQL real (concorrência, queda, privilégios); vetores Solana comuns; ponta a pon...
- Conversa: português (Brasil). Fontes:
- `construction/u4-real-execution/functional-design/`: BR1.1 a BR10.2, F1 a F10, S1 a S30;

Full plan: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u4-real-execution/code-generation/code-generation-plan.md
Test instructions: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u4-real-execution/code-generation/unit-test-instructions.md

[Approval Fingerprint]: sha256:v3:3ca3d38a3d51c883fa35abae0f6497cd6f6e28f245162992a490170647d7d45e
[Planned Source]: 86dc0ba035a7af144345b8b97e6b4f3fb2a4e888de16be7839272a85f42acc69

- A. Approve Plan
- B. Request Changes
- C. I'll edit the files

[Answer]: A. Approve Plan
