# Decisões de tecnologia — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`, os módulos atuais (`bots`, `pnl`, `botwallet`, `riskcontrol`), `apps/txsigner`, `team.md` (Testing Posture) e `nfr-requirements-questions.md` (Q4, Q7, Q8).

| ID | Decisão | Motivo |
|---|---|---|
| U4-NFR8.3 | Módulo novo `realexecution` (`api`, `domain`, `infra`, `infra.jupiter`, `web`); a M6 em `realexecution.api` | dono da RealExecution no Domain Design |
| U4-NFR8.4 | P&L REAL em `pnl` (pacote `pnl.real`), lendo as ordens liquidadas da M6 (`settledOrders`, valores lidos da rede) e a M4; o P&L fecha com os lançamentos da M2, provado por teste de reconciliação | o P&L já mora em `pnl`; revisado em 2026-11-18 (ver nota abaixo) |
| U4-NFR8.5 | Campo `market` no módulo `bots`, com migração e valor padrão `PAPER` | aditivo |
| U4-NFR8.6 | Política de swap no TxSigner (`apps/txsigner`), ao lado da de saque, com o mesmo leitor de mensagem | uma só política por processo |
| U4-NFR8.7 | Cliente Jupiter com o `java.net.http.HttpClient` e Resilience4j (tempo limite e circuito) | revisado em 2026-11-18 (ver nota abaixo) |
| U4-NFR8.8 | Testes antes do código para a máquina de estados, a idempotência e a política de swap; JUnit 5, AssertJ, Testcontainers, WireMock com respostas gravadas da Jupiter, validador local com contas da Jupiter clonadas; piso de 80% na API e no TxSigner | `team.md` (núcleo financeiro e TxSigner) |
| U4-NFR8.9 | Testes obrigatórios por risco tocados pela U4: REAL/PAPER, idempotência (uma transação por ordem REAL), limites e pausa (pela barreira), política do TxSigner, REAL desligado por padrão, isolamento entre tenants | `team.md` |
| U4-NFR10.1 | Lamports e unidades base em `long` e texto no JSON; preços em `BigDecimal`; instantes em UTC; nunca ponto flutuante | NFR10, `project.md` |

**Revisão de 2026-11-18 (U4-NFR8.4 e U4-NFR8.7).** O Build and Test (Loop-back 1 em `construction/build-and-test/test-results.md`) marcou como não cumpridos os dois desvios mantidos na Code Generation. O responsável do projeto aceitou os dois e pediu a revisão das metas, no lugar de desfazer o código:

- **U4-NFR8.4:** a meta original pedia o P&L lendo só a M2 e a M4. O código lê os valores das ordens já liquidadas na M6, que são os valores lidos da rede, e o resultado fecha com os lançamentos da M2. A reconciliação é coberta por `RealPnlCalculatorTest` e `RealSettlementIT`. A meta passa a descrever o que foi construído.
- **U4-NFR8.7:** a meta original pedia o `RestClient` do Spring. O código usa o `java.net.http.HttpClient` com Resilience4j: mesmo tempo limite, mesmo circuito e mesma classificação das falhas. A meta passa a descrever o que foi construído.

**Nenhuma biblioteca nova de produção.** O SDK Solana entra só no escopo de teste, para gerar vetores, como na U1.
