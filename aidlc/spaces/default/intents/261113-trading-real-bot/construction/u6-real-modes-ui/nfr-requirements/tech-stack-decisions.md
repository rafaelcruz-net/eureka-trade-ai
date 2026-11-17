# Decisões de tecnologia — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q2, Q6), os `package.json` de `apps/web` e de `apps/admin`, e `team.md` (Testing Posture e Code Style).

| ID | Decisão | Motivo |
|---|---|---|
| U6-NFR8.1 | Next.js 15, React e TypeScript `strict`, como já estão; nenhuma biblioteca de produção nova | o padrão dos dois apps |
| U6-NFR8.2 | A assinatura usa a feature `solana:signTransaction` do Wallet Standard (`@solana/wallet-standard-features`, já instalado), exposta no `WalletPort` como `signTransaction(bytes)` | sem SDK Solana em produção (`project.md`) |
| U6-NFR8.3 | Os tipos vêm do contrato gerado (`gen:api`, `check:api`), regenerados depois do adendo do servidor | `team.md`, Code Style |
| U6-NFR8.4 | Testes de componente com Jest 30 e React Testing Library, que já existem, sem dependência nova; piso de 80% de linhas em cada app | `team.md`, Testing Posture; sem ferramenta automática de acessibilidade na CI |
| U6-NFR8.5 | E2E com Playwright no `verify.sh`, no validador local. O `e2e/real-bot-wallet` passa a ativar pela tela nova, e o spec novo `e2e/real-ui` cobre:<br>• visão geral e teto;<br>• troca de modo;<br>• ordens;<br>• depósito e saque;<br>• administrador;<br>• 320 px.<br>A assinatura no modo Assinada é provada por testes de componente com a carteira falsa (byte a byte e todos os desfechos) e pelos testes de integração da U4. Uma nova ordem no modo Assinada não é reproduzível com a rota gravada (ajuste do Infrastructure Design, iteração 1). A carteira de teste ganha `signTransaction` só no código de teste. A linha no `verify.sh` vai pronta para o responsável. | `team.md`, Walking Skeleton e Determinismo |
| U6-NFR8.6 | As restrições de lint usam regras nativas do ESLint e do `eslint-plugin-react`, que já existem (`no-restricted-syntax`, `no-restricted-imports`, `no-restricted-properties`, `no-restricted-globals`, `react/no-danger`). Elas ficam num bloco das pastas do REAL que repete as restrições que já existem no app. Cada regra tem uma fixture que falha. | U6-NFR1.1b, U6-NFR1.2, U6-NFR1.9, U6-NFR7.3 |
| U6-NFR8.7 | Os testes do servidor para o adendo (`/real/release`, `serverTime`, CORS com `PATCH`) usam JUnit e Testcontainers, como os da U5, e seguem a ordem do `team.md` | `team.md` |
| U6-NFR10.1 | As datas trafegam em UTC e são mostradas no fuso do navegador por `Intl.DateTimeFormat` | `project.md` (Mandated) |
