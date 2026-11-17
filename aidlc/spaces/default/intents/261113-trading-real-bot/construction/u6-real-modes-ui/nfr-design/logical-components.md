# Componentes lógicos — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes:
- `construction/u6-real-modes-ui/functional-design/` (BR1 a BR9, adendo);
- `construction/u6-real-modes-ui/nfr-requirements/` (U6-NFR*);
- `nfr-design-questions.md` (Q1 a Q7);
- o código atual de `apps/web`, `apps/admin` e `apps/api` (`botwallet.web`, `GuestCorsPolicy`, `route-registry.yml`).

## `apps/web`

| Componente | Onde | Papel |
|---|---|---|
| Rotas do REAL | `src/app/real/` (`page.tsx`, `ativacao/`, `modo/`, `ordens/`, `deposito-saque/`, `carteira/`) | R1 a R6; `carteira/` só redireciona (BR1.3) |
| `LiveChannelProvider` | `src/features/live/`, montado no `app/layout.tsx` | uma conexão por pessoa com o `stream-client` e a `channel-renewal` que já existem; `subscribe(tipo, handler)`; reconexão com aviso aos assinantes (Q2) |
| `DashboardProvider` (mudado) | `src/features/dashboard/` | assina o canal pelo `LiveChannelProvider` em vez de abrir conexão própria (ver ajustes da revisão) |
| `envelopes.ts` (mudado) | `src/features/dashboard/` | passa a aceitar `real.signing.requested` e `real.order.state` |
| `api.ts` | `src/features/real/` | chamadas tipadas pelo `schema.d.ts`; `toBaseUnits` converte todo valor para `bigint`; cada código de erro vira um `RealError` tipado |
| `money.ts` | `src/features/real/` | `parseSol(texto) → bigint`, `formatSol(bigint)` e `formatToken(bigint, casas)`; único lugar com conversão |
| `messages.ts` | `src/features/real/` | os textos pt-BR, a tabela de BR8.8 e os textos de `pausedReason` |
| Componentes | `src/features/real/components/` | `RealBadge`, `PauseBanner`, `SolAmountInput`, `MoneyConfirmDialog`, `ModeSelector`, `OrderStateBadge`, `SigningBanner`, `SigningDialog`, `ReleaseStatus`, `RealPnlCard` |
| Hooks | `src/features/real/hooks/` | `useRelease`, `useRealWallet`, `useRealOrders`, `useSigningSession`, `useMoneyAction` (chave por corpo, botão desabilitado, sem reenvio automático), `useTracking` (acompanhamento a cada 3 s, por até 2 min) |
| `countdown.ts` | `src/lib/time/` | diferença de relógio por `serverTime`, segundos restantes e marcos de 10 s e 5 s; fora das pastas do REAL (U6-NFR7.3) |
| `base64.ts` | `src/lib/encoding/` | base64 próprio, sem biblioteca |
| `WalletPort` (mudado) | `src/features/auth/wallets.ts` | `canSignTransaction` e `signTransaction(bytes)` pela feature `solana:signTransaction` |
| Menu (mudado) | layout autenticado | item "Carteira do bot" com `RealBadge`, só com o REAL ligado (BR1.1) |
| Redirecionamento do novo aceite (mudado) | `features/reacceptance` | não age na tela de depósito e saque nem nas três chamadas de exceção (BR1.4) |
| Cartão do P&L REAL | painel | `RealPnlCard`, separado do PAPER (BR7.2) |

## `apps/admin`

| Componente | Onde | Papel |
|---|---|---|
| Rotas do REAL | `src/app/real/` (`page.tsx`, `usuarios/[id]/`, `tokens/`, `kill-switch/`, `divergencias/`, `restauracao/`) | A1 |
| `api.ts`, `money.ts`, `messages.ts` | `src/features/real/` | o `money.ts` é a mesma implementação do web, copiada e testada aqui |
| `AdminActionDialog` | `src/features/real/components/` | motivo de 1 a 500 caracteres, confirmação, chave por corpo e erro em texto |
| Menu (mudado) | layout | item "REAL", separado de "Kill switch", que é o do PAPER |

## `apps/api` (adendo)

| Componente | Onde | Papel |
|---|---|---|
| `GET /api/v1/real/release` | `botwallet.web`, ao lado da leitura da carteira | lê pela M1 da U5 (`readRelease(tenant, userId)`); a M1 ganha esse método só de leitura |
| `RealReleaseView` | `botwallet.web` | `network` e `release` (`state`, `effectiveAt`, `blockedReason`) |
| `BotWalletView` (mudado) | `botwallet.web` | `capSetBy` e `release` |
| `RealSigningSession` (mudado) | `realexecution.web` | `serverTime` pelo `Clock` injetado |
| `GuestCorsPolicy` (mudado) | `identity.web` | `PATCH` só no ramo com token |
| `route-registry.yml` (mudado) | recursos | a rota nova, com tenant e a exigência do novo aceite |

## Ajustes da revisão (iteração 1)

### Canal ao vivo no layout raiz

**Onde fica.** Não existe layout autenticado: cada tela monta o próprio `AppShell` (`components/shell/AppShell.tsx`). Por isso o `LiveChannelProvider` fica no `app/layout.tsx` raiz, dentro do `SessionProvider`, e persiste entre as navegações do App Router.

**Quando abre.** A conexão abre só com sessão e fecha ao sair da sessão. Ela não abre nas telas sem sessão (`/entrar`, `/convite`, `/termos`, `/privacidade`) nem em `/novo-aceite`, e fica fechada com o novo aceite pendente. A rota atual vem de `usePathname`, que funciona no layout raiz.

**Interface.** O provedor expõe a mesma interface que o `DashboardProvider` usa hoje:
- `status` (`WAITING`, `OPEN`, `STALE`) e `outdated`;
- `retryNow()`;
- `subscribe(tipo, handler)`;
- `onReconnected(handler)`.

Os anúncios de perda e de volta do canal ficam no provedor.

**O que muda no painel:**
- o `DashboardProvider` lê `status`, `outdated` e `retryNow` do provedor e assina os eventos do painel;
- as ações `CHANNEL_STATUS` e `retryChannel` do reducer passam a vir do provedor;
- o reducer continua o mesmo.

**O que muda em BR5.6 do painel.** O canal não fecha mais quando a pessoa sai do painel. O painel só cancela as assinaturas dele, e a conexão continua para a faixa.

**Testes.** Os testes que montam o `DashboardProvider` com `fetchImpl` passam a montá-lo dentro de um `LiveChannelProvider` de teste com o mesmo `fetchImpl`. As asserções sobre o que aparece na tela continuam as mesmas. O teste de BR5.6 muda de "fecha a conexão" para "cancela as assinaturas".

**Eventos novos.** Para os eventos do REAL chegarem à faixa, mudam três arquivos:
- `features/dashboard/warn.ts`: `real.signing.requested` e `real.order.state` entram em `EVENT_KINDS`;
- `stream-client.ts`: deixa de descartar esses nomes como `UNKNOWN_EVENT`;
- `envelopes.ts`: valida os dados de cada evento.

**Onde aparecem.** O `SigningBanner` é renderizado dentro do `AppShell`, logo abaixo do cabeçalho, em todas as telas com sessão. A tag do cabeçalho "MODO PAPER (simulado)" passa a ser "PAPER" ao lado do `RealBadge` quando a pessoa tem carteira do bot ativa.

**Menu.** `components/shell/PrimaryNav.tsx` ganha a seção `'real'` em `ShellSection` e em `NAV_ITEMS` ("Carteira do bot", com o `RealBadge`). O item só aparece com o REAL ligado (BR1.1), e o `AppShell.test.tsx` cobre a seção nova.

### Ajustes menores

- O `GuestCorsPolicy` fica em `identity.web`; a mudança é em `configuration(...)`.
- O `readRelease(tenant, userId)` entra em `RealStateQuery`, a parte de leitura da M1. A rota entra no `route-registry.yml` com `scope: CLIENT` e `negativeTest`, como as outras rotas REAL.
- O `jest.setup.ts` dos dois apps ganha stubs de `HTMLDialogElement.prototype.showModal` e `close`, que o jsdom não implementa. Os testes de foco conferem o foco inicial e a volta dele.
- O novo aceite não muda em `features/reacceptance`: o tratador é por instância de cliente (`setTermsReacceptanceHandler`). O `SigningBanner` e a tela de depósito e saque usam uma segunda instância do `guestApi` sem o tratador. A linha "Redirecionamento do novo aceite (mudado)" da tabela acima fica sem efeito.
- A carteira de teste do e2e passa a declarar `solana:signTransaction` na lista de features da conta. Ela devolve a transação serializada inteira, com a assinatura no lugar dela.

## Fronteiras

- O navegador nunca importa biblioteca de transação Solana; só a feature do Wallet Standard (U6-NFR1.1b).
- `features/real` não lê o estado do PAPER; o painel junta os dois cartões só na exibição.
- O administrador não importa nada do `apps/web`.
