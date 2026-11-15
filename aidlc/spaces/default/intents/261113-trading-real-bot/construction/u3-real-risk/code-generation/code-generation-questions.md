# Code Generation Plan Approval

AI-DLC writes this file when it asks you to approve the plan. To answer here
instead of in chat, write your answer after `[Answer]:` and tell me in chat when you are done.

## Plan Approval

Approve the code plan for u3-real-risk?

- Builds: barreira de risco REAL (M5) — limites de compra, perda do dia com pausa, teto com reservas, tokens permitidos e bloqueio, frescor e fonte, trava por carteira, trabalhos, expurgo, métricas e...
- Touches: `apps/api/src/main/java/com/memetrade/riskcontrol/` (api, real/domain, real/infra), `botwallet.api` e `ledger.api` (leituras M1/M2), `marketdata.api` (preços em lote), migração 0018, regra...
- Tests: cerca de 140 testes — política pura com os cenários S1 a S17 escritos antes do código, integração com PostgreSQL real (trava, repetição, privilégios, trabalhos), ArchUnit e paridade dos alar...
- Conversa: português (Brasil). Fontes: `construction/u3-real-risk/functional-design/` (BR1.1 a BR7.3, F1 a F6, S1 a S17), `construction/u3-real-risk/nfr-requirements/` (U3-NFR*), `construction/u3-re...

Full plan: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u3-real-risk/code-generation/code-generation-plan.md
Test instructions: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u3-real-risk/code-generation/unit-test-instructions.md

[Approval Fingerprint]: sha256:v3:ed28c4fd6438582235504e99cfca781205a6cea3740f5e49c6b4a2a6e9bbfaea
[Planned Source]: a84829108b1ede5c287b498e219e5b9387decacbb8e51a014e5ece0509580ddb

- A. Approve Plan
- B. Request Changes
- C. I'll edit the files

[Answer]: A. Approve Plan
