# Perguntas de NFR Design — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Base:
- `construction/u6-real-modes-ui/functional-design/` (BR1 a BR9, adendo de contrato);
- `construction/u6-real-modes-ui/nfr-requirements/` (U6-NFR1.1 a U6-NFR10.1);
- o código atual:
  - `apps/web/src/lib/api` (`createApiClient`, `ApiProvider`, renovação da sessão);
  - `apps/web/src/features/dashboard` (`stream-client`, `channel-renewal`, `envelopes.ts`, `DashboardProvider`). O canal ao vivo hoje só abre no painel e fecha ao sair dele;
  - `apps/web/src/features/auth/wallets.ts`;
  - `apps/web/e2e/support/test-wallet.ts`;
  - `apps/admin/src/app`.

## Q1. [Módulos] Onde fica cada parte no `apps/web`?

A. As partes ficam assim:
- **Rotas:** `src/app/real/` (`page.tsx`, `ativacao/`, `modo/`, `ordens/`, `deposito-saque/`, e `carteira/`, que só redireciona).
- **Recurso `src/features/real/`:**
  - `api.ts`: chamadas tipadas pelo contrato gerado e conversão para `bigint`;
  - `money.ts`: analisador e formatador;
  - `messages.ts`: textos e a tabela de BR8.8;
  - `components/`: `RealBadge`, `MoneyConfirmDialog`, `ModeSelector`, `OrderStateBadge`, `SolAmountInput`, `PauseBanner`, `SigningBanner` e `SigningDialog`;
  - `hooks/`: `useRealWallet`, `useRelease` e `useSigningSession`.
- **Tempo fora das pastas do REAL:** `src/lib/time/countdown.ts` (U6-NFR7.3).
- **A carteira:** `features/auth/wallets.ts` ganha `signTransaction`.
B. Tudo dentro das páginas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Canal ao vivo] Como a faixa recebe o evento em qualquer tela?

A. O canal sobe para o layout autenticado. Um `LiveChannelProvider` abre uma única conexão por pessoa com o `stream-client` e a `channel-renewal` que já existem, e expõe `subscribe(tipo, handler)`.
- O `DashboardProvider` passa a assinar por ele e deixa de abrir a própria conexão. O comportamento do painel e os testes dele continuam iguais.
- O `SigningBanner` assina `real.signing.requested`.
- Fora do modo Assinada, ou sem o novo aceite, a faixa não assina nem relê (BR3.5).
B. Uma segunda conexão só para a faixa
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Dados] Como as telas leem e escrevem?

A. Pelo `ApiProvider` e pelo `createApiClient` que já existem, com a renovação de sessão atual, sem biblioteca nova de cache:
- **Leitura:** cada parte da tela lê por um hook próprio que devolve `loading`, `error`, `empty` ou `data`. Um erro fica só naquela parte (U6-NFR4.3), e o saque nunca depende de outra leitura.
- **Escrita:** as ações com dinheiro usam um `useMoneyAction`, que guarda a chave por corpo (BR6.2), desabilita o botão (BR6.3) e nunca reenvia sozinho.
- **Acompanhamento:** usa `countdown.ts` e uma leitura por vez (U6-NFR3.4).
B. Uma biblioteca de cache (React Query)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Assinatura] Como `signTransaction` entra no `WalletPort`?

A. O `WalletPort` ganha `signTransaction(transaction: Uint8Array): Promise<Uint8Array>` e um `canSignTransaction`, lido da feature `solana:signTransaction` do Wallet Standard (versão `legacy` e `0`).
- **Base64:** a conversão é feita por uma função própria, sem biblioteca.
- **Teste:** compara byte a byte a mensagem antes e depois. A mensagem é a transação sem a parte das assinaturas.
- **Carteira de teste do e2e:** recebe `signTransaction` só no código de teste. Ela assina a mensagem da transação com a mesma chave de teste e o mesmo mecanismo do `signMessage` atual, e devolve a transação com a assinatura no lugar dela.
B. Usar `signMessage` sobre a mensagem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Servidor] Como o adendo entra no `apps/api`?

A. São três mudanças:
- **Rota nova:** `GET /api/v1/real/release` fica no `botwallet.web`, ao lado da leitura da carteira. Ela usa a M1 e devolve o `RealReleaseView`. Entra no registro de rotas (`route-registry.yml`), com o tenant do contexto e a exigência do novo aceite como as outras leituras REAL.
- **Campos novos:** `serverTime`, vindo do `Clock` injetado, entra no `RealSigningSession`; `capSetBy` e `release` entram no `BotWalletView`.
- **CORS:** `GuestCorsPolicy` ganha `PATCH` só no ramo com token.

Os testes do servidor seguem o padrão das Units anteriores, e o OpenAPI e os tipos dos dois apps são regenerados.
B. Ler a liberação por outra Unit
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Administrador] Como fica o `apps/admin`?

A. As partes ficam assim:
- **Rotas:** `src/app/real/` (`page.tsx` com os usuários, `usuarios/[id]/`, `tokens/`, `kill-switch/`, `divergencias/` e `restauracao/`).
- **Recurso:** `src/features/real/` com `api.ts`, `money.ts` (o mesmo código do web, copiado e testado nos dois apps, porque não há pacote compartilhado), `messages.ts` e `components/` (`AdminActionDialog`, que pede motivo e confirmação e cuida da chave por corpo).
- **Menu:** "REAL" fica separado de "Kill switch", que é o do PAPER.
B. Um pacote compartilhado novo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Lint] Como as regras de lint ficam organizadas?

A. Cada app ganha, no `eslint.config.mjs`, um bloco para `src/{app,features}/real/**`. Esse bloco:
- repete as restrições que já existem no app, como a de `EventSource`;
- acrescenta as de U6-NFR1.1b, U6-NFR1.2, U6-NFR1.9 e U6-NFR7.3.

O `money.ts` fica liberado só do `+` unário e do `Number(`, e é o único lugar que converte. Um teste do lint roda o ESLint em fixtures que precisam falhar.
B. Regras só em revisão
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Rotas em `app/real`; recurso em `features/real` (API, dinheiro, textos, componentes e hooks); tempo em `lib/time`; `signTransaction` na carteira [Q1].
- Canal ao vivo com uma conexão por pessoa no layout, que o painel e a faixa assinam [Q2].
- Leitura por hooks com erro isolado por parte; ações com chave por corpo e sem reenvio automático; sem biblioteca nova [Q3].
- `signTransaction` pelo Wallet Standard, base64 próprio, teste byte a byte e carteira de teste do e2e com assinatura [Q4].
- Servidor: `/real/release` no `botwallet.web` pela M1, `serverTime` pelo relógio, `capSetBy` e `release` na carteira, `PATCH` no CORS com token [Q5].
- Administrador com seção `/real` própria, diálogo de ação com motivo e chave, e o menu separado do kill switch do PAPER [Q6].
- Bloco de lint das pastas do REAL nos dois apps, com teste das fixtures que falham [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
