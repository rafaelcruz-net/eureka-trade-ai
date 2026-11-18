# Code Generation Plan Approval

AI-DLC writes this file when it asks you to approve the plan. To answer here
instead of in chat, write your answer after `[Answer]:` and tell me in chat when you are done.

## Plan Approval

Approve the code plan for u2-real-market-data?

- Builds: fonte real de dados de mercado da CoinGecko no worker — adaptador com cota e orçamento, estados tolerantes da fonte, sinal de fonte real ou simulada e atribuição na API e no scanner
- Touches: `apps/api/src/main/java/com/memetrade/marketdata/` (api, domain, infra, infra/coingecko), migração do contador, regra ArchUnit de rede, `apps/web` (atribuição e aviso no scanner), `infra/o...
- Tests: cerca de 90 testes — unidade e integração (JUnit 5, WireMock com respostas sintéticas, Testcontainers) e Jest na tela; nenhum teste chama a CoinGecko real; piso de 80%
- Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/functional-design/` (BR1.1 a BR1.10), `construction/u2-real-market-data/nfr-requirements/`, `construction/u2-real-market-data...

Full plan: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u2-real-market-data/code-generation/code-generation-plan.md
Test instructions: aidlc/spaces/default/intents/261113-trading-real-bot/construction/u2-real-market-data/code-generation/unit-test-instructions.md

[Approval Fingerprint]: sha256:v3:cdec0a0acb16517489cf51f2bd9d50d579bd8d22da675d74d1c8520bf9988704
[Planned Source]: 12dc1ec18aa90718e91697c45772a2813e9366e403e0daf6c2c24bca21d77280

- A. Approve Plan
- B. Request Changes
- C. I'll edit the files

[Answer]: A. Approve Plan
