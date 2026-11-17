# Projeto de segurança — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `construction/u6-real-modes-ui/nfr-requirements/security-requirements.md` (U6-NFR1.x, U6-NFR2.1, U6-NFR7.x) e `nfr-design-questions.md` (Q4, Q5, Q7).

| Requisito | Como o projeto atende |
|---|---|
| U6-NFR1.1 | O `SigningDialog` decodifica `transactionBase64` com o `base64.ts`, chama `wallet.signTransaction(bytes)` e codifica a resposta em `signedTransactionBase64`. Um teste com uma carteira falsa compara a mensagem dada e a recebida, byte a byte, a partir do primeiro byte depois das assinaturas. |
| U6-NFR1.1b, U6-NFR1.3 | Bloco do ESLint nas pastas do REAL com `no-restricted-imports` (`@solana/web3.js`, `@solana/kit`, `@solana/spl-token`, `@coral-xyz/anchor`) e `no-restricted-syntax` sobre os literais `sendTransaction` e `sendRawTransaction`. Um teste de dependências compara o `package.json` dos dois apps com a lista permitida. |
| U6-NFR1.2, U6-NFR1.10 | O mesmo bloco proíbe `localStorage`, `sessionStorage`, `document.cookie` e `console` (`no-restricted-globals`, `no-restricted-properties`), e as rotas do REAL não levam valores na URL. O token do administrador continua em memória, como hoje. |
| U6-NFR1.4, U6-NFR1.5 | Nenhum script novo. A rede vem de `/real/release`, e um teste lista as `NEXT_PUBLIC_*`. |
| U6-NFR1.6 | A rota nova lê o tenant do `AuthenticatedPrincipal` e entra no `route-registry.yml`. Os testes de isolamento seguem o padrão da U5: outra conta, REAL desligado e sem o novo aceite. |
| U6-NFR1.7 | `GuestCorsPolicy`: `PATCH` só na lista do ramo com token. O teste cobre o ramo com token, o ramo por cookie e o CORS do administrador. |
| U6-NFR1.8, U6-NFR1.11 | O `AdminActionDialog` conta caracteres, não bytes, e confere de 1 a 500. A carteira principal vem abreviada do servidor e não existe campo de chave. |
| U6-NFR1.9 | `react/no-danger` como erro nas pastas do REAL; todo texto do servidor é renderizado como texto. |
| U6-NFR2.1 | O `RealPnlCard` só recebe `RealPnl`, e o cartão do PAPER só recebe o P&L do PAPER. Nenhum componente soma os dois. |
| U6-NFR7.1 a U6-NFR7.3 | O `api.ts` converte todo campo em lamports ou em unidades de token por `toBaseUnits` (string ou número inteiro para `bigint`), e os tipos do REAL usam `bigint`. As conversões a partir de texto ficam em `money.ts`; as proibições do lint valem fora dele. |
| U6-NFR7.4, U6-NFR7.6 | O `useMoneyAction` guarda `{ corpo serializado, chave }`, gera chave nova quando o corpo muda e mantém um sinal de envio que ignora o segundo clique. |
| U6-NFR7.5 | O `api.ts` mapeia o tipo `unknown` do contrato para um tipo `Unknown` explícito; o formatador mostra "sem preço" ou "sem dado". |
