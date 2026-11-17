# Projeto de tecnologia e testes — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `construction/u6-real-modes-ui/nfr-requirements/tech-stack-decisions.md` (U6-NFR8.x, U6-NFR10.1) e `nfr-design-questions.md` (Q4 a Q7). A escala da U6 está em `performance-design.md` (U6-NFR3.5, U6-NFR3.5b, U6-NFR3.6).

| Requisito | Como o projeto atende |
|---|---|
| U6-NFR8.1 | Nenhuma dependência nova de produção. |
| U6-NFR8.2 | O `WalletPort.signTransaction` usa a feature `solana:signTransaction` do `@solana/wallet-standard-features`. O `canSignTransaction` lê as versões suportadas (`legacy` e `0`). |
| U6-NFR8.3 | Depois do adendo do servidor, `gen:api` e `check:api` nos dois apps. |
| U6-NFR8.4 | Jest com React Testing Library; o `coverageThreshold` de 80% que já existe nos dois apps. |
| U6-NFR8.5 | O `e2e/real-bot-wallet` ativa pela tela nova, e o spec `apps/web/e2e/real-ui/` cobre visão geral e teto, modo, ordens, depósito e saque, administrador e 320 px (ver `infrastructure-design/infrastructure-specification.md`). A assinatura no modo Assinada fica nos testes de componente com a carteira falsa e nos testes de integração da U4. O `e2e/support/test-wallet.ts` ganha `signTransaction`. |
| U6-NFR8.6 | Bloco do ESLint por app em `src/{app,features}/real/**`, que repete as restrições atuais, com `money.ts` como exceção nomeada. O teste `real-lint.test.ts` roda o ESLint nas fixtures que precisam falhar. |
| U6-NFR8.7 | Testes do servidor com JUnit e Testcontainers: rota, isolamento, `serverTime` com relógio fixo e CORS. |
| U6-NFR10.1 | Datas pelo `Intl.DateTimeFormat('pt-BR', { timeZone })` do navegador, a partir de UTC. |
