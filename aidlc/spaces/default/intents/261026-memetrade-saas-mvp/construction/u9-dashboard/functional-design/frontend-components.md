# Componentes de frontend — U9 Painel e navegação

Componentes do app do convidado para a tela S2 e a casca única das telas logadas. Os fluxos e as regras (`BRx.y`) estão em [functional-spec.md](functional-spec.md); a Unit e as fronteiras, em [unit-of-work.md](../../../inception/units-generation/unit-of-work.md); as rotas, em [contract-summary.md](../../../inception/contract-design/contract-summary.md). Os nomes abaixo são lógicos; os arquivos seguem a organização por área já usada no app (`features/<área>/`).

## Hierarquia

```
AppShell                       (casca única de S2 a S8)
  SkipLink                     (já existente no layout raiz)
  AppHeader
    PrimaryNav                 (Painel, Scanner, Bots PAPER, P&L PAPER, Conta)
    FeedbackButton             (U8, sem mudança)
    WalletBadge                (carteira abreviada)
    SignOutButton              (U3, sem mudança)
  NoticesRegion                (região "Avisos", aria-live polite)
  main#conteudo
    <tela>

DashboardScreen                (S2, rota /)
  DashboardProvider            (estado do painel + cliente do canal)
  DashboardHeader              (h1 "Painel", "Atualizado às HH:MM")
  DataNoticesBanner            (avisos de fonte e de valores desatualizados)
  BotsCard
    BotsCounts
    BotRow (n)
      PauseButton
    LastTradeLine
    BotsEmptyState
  PnlCard
    PnlTotals
    ReconciliationLine
  CardError                    (mensagem e "Tentar de novo", usado pelos quadros)
```

## AppShell e navegação

| Componente | Props | Estado | Comportamento |
|---|---|---|---|
| `AppShell` | `current: 'dashboard' \| 'scanner' \| 'bots' \| 'pnl' \| 'account'`, `titleId`, `children` | — | Monta cabeçalho, região de avisos e `main#conteudo` com `aria-labelledby={titleId}`. Substitui os cabeçalhos próprios das telas atuais (`BotsShell`, o cabeçalho de scanner, P&L e Conta), que passam a só fornecer o título e o conteúdo (BR6.1). |
| `PrimaryNav` | `current` | — | `nav` com `aria-label="Navegação principal"`; links na ordem do mockup; `aria-current="page"` no item atual (BR6.2). |
| `WalletBadge` | — | `walletMasked: string \| null` | Lê `GET /api/v1/account` uma vez por sessão (guardado no contexto de sessão em memória); falha mostra "Carteira" sem endereço (BR6.4). |
| `NoticesRegion` | — | fila de mensagens | `role="region"` com `aria-label="Avisos"` e `aria-live="polite"`; recebe mensagens por um contexto `announce(text)`, junta as de até 2 s numa só e nunca move o foco (BR6.3, BR7.2, BR7.3). |

Mapeamento das telas existentes para `current`: `/` painel; `/scanner` scanner; `/bots`, `/bots/new` e `/bots/[botId]` bots; `/pnl` P&L; `/conta` conta. As telas S1 (`/convite`, `/entrar`) não usam a casca.

## DashboardScreen e DashboardProvider

`DashboardProvider` guarda o estado do painel num redutor único, para que REST e canal passem pelo mesmo caminho:

| Fatia | Tipo lógico | Origem |
|---|---|---|
| `bots` | `{ status: LOADING \| READY \| EMPTY \| ERROR, inUse: BotSummary[], activeCount, activeLimit, draftCount, stoppedCount, draftToContinueId, reloadPending }` | carga completa por estado (`GET /bots?state=…` até `nextCursor` nulo); `bot.state` só atualiza os bots já em `inUse` e marca `reloadPending` (BR1.5) |
| `lastTrade` | `{ status, trade: PaperTrade \| TradeNotice \| null }` | `GET /bots/{id}/trades?limit=1`; `trade.recorded` (`TradeNotice`, sem motivo, trocado pela `PaperTrade` quando a busca do bot responde; BR2.3) |
| `pnl` | `{ status, report: PnlReport \| null, period: { from, to } }` | `GET /pnl` |
| `reconciliation` | `{ status, result: ReconciliationResult \| null }` | `GET /reconciliation` |
| `sources` | `{ status, items: SourceStatus[] }` | `data.status`, `GET /scanner/tokens?limit=1` |
| `channel` | `{ state: CONNECTING \| OPEN \| STALE \| WAITING \| RENEWING \| CLOSED, lastEventId, lastSuccessAt, attempt }` | cliente do canal |
| `pendingPauses` | `Map<botId, { idempotencyKey, status: SENDING \| FAILED }>` | `PauseButton` |

Regras de redução: um evento que chega com o quadro ainda em `LOADING` vai para uma fila e é aplicado depois da resposta REST (BR1.4); `lastTrade` só troca por uma ordem com `createdAt` maior (BR2.3); `lastSuccessAt` muda em toda resposta ou evento recebido (BR5.2).

O cliente do canal (`DashboardStreamClient`) é uma peça sem React, testável sozinha: abre o `fetch` com `Authorization` e `Last-Event-ID`, considera o canal aberto na resposta `200`, trata `401` na abertura como token vencido (renova uma vez e reabre; um segundo `401` ou a falha da renovação encerram no fluxo de sessão expirada), lê o corpo em pedaços, separa os eventos (`id`, `event`, `data`), confere o envelope de cada um, entrega ao redutor, controla a espera crescente e o temporizador de 60 s, e recebe o relógio por injeção (BR5.1 a BR5.6). O token vem do mesmo armazenamento em memória do cliente REST; o cliente nunca grava o token em `localStorage`, `sessionStorage` nem na URL.

## Quadros

| Componente | Props | Mostra |
|---|---|---|
| `BotsCard` | fatias `bots`, `lastTrade`, `pendingPauses` | Título "Bots PAPER — MODO PAPER (simulado)" com `tabIndex=-1` e foco inicial (BR6.5, BR6.6); `BotsCounts`, linhas, `LastTradeLine`; contagens de rascunhos e encerrados com link para `/bots`. |
| `BotsCounts` | `activeCount`, `activeLimit`, `pausedCount` | "Ativos: N de 3" e "Pausados: N". |
| `BotRow` | `bot: BotSummary`, `pending` | Nome (link para `/bots/{botId}`), estado em texto, motivos de pausa com os textos da U5, retomada automática em horário local; `PauseButton` só em `ACTIVE` (BR2.1, BR2.4). |
| `PauseButton` | `botId`, `botName` | Gera a `Idempotency-Key` no primeiro acionamento e a reusa em "Tentar de novo"; "Pausando…" e desabilitado enquanto espera; trata `200`, `409`, `404` e falha técnica (BR2.2); devolve o foco ao texto do estado da linha quando some (BR7.3). |
| `LastTradeLine` | `trade: PaperTrade \| null`, `botName` | "Última operação: HH:MM — <bot> — Compra/Venda <token> — <situação>"; "Nenhuma operação ainda" (BR2.3). |
| `BotsEmptyState` | `draftCount`, `draftToContinueId`, `stoppedCount` | "Nenhum bot PAPER em uso", "Abrir scanner" (`/scanner`), "Criar bot PAPER" (`/bots/new`), "Você tem N rascunhos" com "Continuar rascunho" (`/bots/new?botId=…`, mesma retomada do assistente da U5), em que o rascunho é o primeiro item de `state=DRAFT`, senão de `state=VALIDATED`, na ordem da rota (última alteração primeiro) (BR1.3). |
| `PnlCard` | fatias `pnl`, `reconciliation` | Título "P&L PAPER (simulado)", rótulo do período, `PnlTotals`, `ReconciliationLine`, "Ver P&L" (`/pnl`) (BR3.1, BR6.6). |
| `PnlTotals` | `report: PnlReport` | Realizado, não realizado e taxas com sinal escrito e unidade, usando os formatadores decimais já existentes do P&L (U7), sem ponto flutuante; "unknown" com o motivo; aviso de parcial; "Nenhuma operação hoje" (BR3.2, BR3.5). |
| `ReconciliationLine` | `result`, `status` | "Reconciliado às HH:MM", "N divergências" (link `/pnl`), "Ainda não conferido"; erro local com "Tentar de novo" (BR3.4). |
| `DataNoticesBanner` | fatias `sources`, `channel` | Um aviso por fonte atrasada ou com falha ("Fonte B sem atualização desde HH:MM"), "Dados: OK HH:MM" quando todas estão em dia, e o aviso de valores desatualizados com "Tentar de novo" (BR4.1, BR4.2). |
| `CardError` | `message`, `onRetry` | O que aconteceu, por quê e o que fazer, com "Tentar de novo" (CT-UX-3). |

## Interações

| Ação | Resultado | Regra |
|---|---|---|
| Abrir `/` | Três quadros em carregando; foco no título "Bots PAPER"; canal abrindo | BR1.1, BR6.5 |
| "Tentar de novo" num quadro | Repete só as chamadas daquele quadro | BR1.2 |
| "Pausar" | Comando sem confirmação; linha atualizada; anúncio | BR2.2, BR7.1 |
| "Tentar de novo" no aviso de valores desatualizados | Reabre o canal na hora e busca os três quadros | BR5.3 |
| Evento do canal | Atualiza o quadro e "Atualizado às"; anuncia só mudanças que importam | BR5.2, BR7.1 |
| Sair do painel | Canal fechado, tentativas canceladas | BR5.6 |

Não há formulário nesta Unit; a única entrada da pessoa é o comando "Pausar" e os links.

## Acessibilidade

- h1 "Painel"; títulos de quadro em h2; região "Avisos" com `aria-live="polite"`; regiões navegação, conteúdo principal e avisos nomeadas (CT-UX-9, BR6.3).
- Fluxo completo por teclado; ordem de tabulação: menu, avisos com "Tentar de novo", quadro de bots (linhas e "Pausar"), quadro de P&L; foco visível em todo elemento interativo (CT-UX-4).
- Estado, motivos, sinais de valor e situação de ordem sempre em texto; contraste ≥ 4,5:1 no tema escuro com os tokens já existentes (CT-UX-7).
- Marcadores de carregando com `aria-busy="true"` no quadro afetado; o resto da tela continua utilizável (CT-UX-2).
- Termos de mercado em inglês (P&L, unknown) com explicação ao passar o mouse e ao receber foco, como nas telas da U7 (CT-UX-1).

## `data-testid`

`app-shell`, `app-primary-nav`, `app-nav-<item>-link` (`dashboard`, `scanner`, `bots`, `pnl`, `account`), `app-wallet-badge`, `app-notices-region`; `dashboard-title`, `dashboard-updated-at`, `dashboard-data-notices`, `dashboard-stale-notice`, `dashboard-stale-retry-button`; `dashboard-bots-card`, `dashboard-bots-counts`, `dashboard-bot-row-<botId>` (identificador do bot, estável), `dashboard-bot-pause-button-<botId>`, `dashboard-last-trade`, `dashboard-bots-empty`, `dashboard-open-scanner-link`, `dashboard-create-bot-link`, `dashboard-continue-draft-link`; `dashboard-pnl-card`, `dashboard-pnl-realized`, `dashboard-pnl-unrealized`, `dashboard-pnl-fees`, `dashboard-reconciliation`; `dashboard-card-error-<card>`, `dashboard-card-retry-<card>`.

## Integração com a API

- Tipos gerados do `openapi-guest.yaml`; nenhum tipo do contrato escrito à mão (`team.md`, Code Style). O canal C7 tem `text/event-stream` com corpo `string` no contrato; a geração de código acrescenta ao OpenAPI gerado os esquemas dos seis envelopes de evento descritos em "Formato dos eventos do canal" de [functional-spec.md](functional-spec.md) (só documentação, sem mudar o canal), e o cliente usa os tipos gerados deles. Um evento que não casa com o formato é descartado com registro de aviso, sem derrubar o canal.
- Todas as rotas usam o cliente REST existente (`lib/api/client.ts`), com o mesmo tratamento de `401` (renovação e, se falhar, sessão expirada) e de `ProblemDetail`.
- O comando "Pausar" usa o mesmo chamador de comandos da tela do bot (U5), com `Idempotency-Key` UUID.
- Horários recebidos em UTC e mostrados no fuso do navegador só na interface (`project.md`, Mandated).
