# Decisões de tecnologia — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`, os módulos atuais (`bots`, `pnl`, `botwallet`, `riskcontrol`), `apps/txsigner`, `team.md` (Testing Posture) e `nfr-requirements-questions.md` (Q4, Q7, Q8).

| ID | Decisão | Motivo |
|---|---|---|
| U4-NFR8.3 | Módulo novo `realexecution` (`api`, `domain`, `infra`, `infra.jupiter`, `web`); a M6 em `realexecution.api` | dono da RealExecution no Domain Design |
| U4-NFR8.4 | P&L REAL em `pnl` (pacote `pnl.real`), lendo só a M2 e a M4 | o P&L já mora em `pnl` |
| U4-NFR8.5 | Campo `market` no módulo `bots`, com migração e valor padrão `PAPER` | aditivo |
| U4-NFR8.6 | Política de swap no TxSigner (`apps/txsigner`), ao lado da de saque, com o mesmo leitor de mensagem | uma só política por processo |
| U4-NFR8.7 | Cliente Jupiter com o `RestClient` do Spring e Resilience4j (tempo limite e circuito) | padrão do projeto |
| U4-NFR8.8 | Testes antes do código para a máquina de estados, a idempotência e a política de swap; JUnit 5, AssertJ, Testcontainers, WireMock com respostas gravadas da Jupiter, validador local com contas da Jupiter clonadas; piso de 80% na API e no TxSigner | `team.md` (núcleo financeiro e TxSigner) |
| U4-NFR8.9 | Testes obrigatórios por risco tocados pela U4: REAL/PAPER, idempotência (uma transação por ordem REAL), limites e pausa (pela barreira), política do TxSigner, REAL desligado por padrão, isolamento entre tenants | `team.md` |
| U4-NFR10.1 | Lamports e unidades base em `long` e texto no JSON; preços em `BigDecimal`; instantes em UTC; nunca ponto flutuante | NFR10, `project.md` |

**Nenhuma biblioteca nova de produção.** O SDK Solana entra só no escopo de teste, para gerar vetores, como na U1.
