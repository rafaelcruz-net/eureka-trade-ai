# Mapeamento para o sistema de design — paper-real-unico

Cada elemento das telas mapeado para o componente ou token que já existe, com o que é novo marcado. Não há tema novo: valem o tema escuro de `apps/web/src/lib/theme/tokens.ts` e as classes de `apps/web/src/app/globals.css` [Q4].

## Sources

- `refined-mockups-questions.md`: Q4 (sistema de design), Q2 (padrões de interação), Q3 (estados), Q5 (acessibilidade), Q6 (tamanhos de tela).
- `inception/user-stories/stories.md`: AC1.1.1, AC1.2.2 a AC1.2.7, AC1.3.1, AC2.1.2, AC2.2.1 a AC2.2.3, AC3.10.2, AC4.1.1, AC4.2.1, AC4.3.1 a AC4.3.3, AC4.4.2, AC4.4.3, AC4.4.5.
- `inception/requirements-analysis/requirements.md`: FR1.2, FR2.2, FR4.2, NFR5.
- `inception/practices-discovery/team-practices.md` e `team.md`, Code Style: valores do REAL em inteiros e `bigint` só em `features/real/money.ts`; cada modo na sua unidade (PAPER em US$, REAL em SOL); textos de interface em pt-BR.
- O código: `tokens.ts`, `globals.css`, `features/real/` (`RealBadge`, `ModalDialog`, `RealErrorNotice`, `ReleaseStatus`, `money.ts`, `messages.ts`), `features/bots/messages.ts`, `features/dashboard/messages.ts`, `lib/i18n/messages.ts` (`pnl`), `features/pnl/SignedValue.tsx`, `features/pnl/period.ts`.

## 1. Tokens

Nenhum token novo. Os contrastes foram medidos com a fórmula de `tokens.ts` (WCAG 2.1).

| Token | CSS | Uso neste intent | Contraste |
|---|---|---|---|
| `background` | `--background` | Fundo da página; texto sobre o âmbar do `RealBadge` | `background`/`real` 8,80:1 |
| `surface` | `--surface` | Fundo dos blocos, cartões e tabelas | — |
| `surfaceRaised` | `--surface-raised` | Hover dos cartões do `ModeStep`; fundo dos diálogos e avisos (`.notice`) | — |
| `text` | `--text` | Texto principal; texto e ícone do `SimulatedBadge` | 14,45:1 em `surface` |
| `textMuted` | `--text-muted` | Descrições, "Mostrando…", ajuda de campo; **contorno do `SimulatedBadge`** | 8,83:1 em `surface` |
| `accent` | `--accent` | Valores de ganho (`.money-gain`); borda do cartão selecionado; links | 8,33:1 em `surface` |
| `danger` | `--danger` | Valores de perda (`.money-loss`); erros (`.notice-error`, `.field-error`) | 7,63:1 em `surface` |
| `warning` | `--warning` | Aviso do simulado (⚠, `.notice-warning`) | 11,67:1 em `surface` |
| `unknown` | `--unknown` | Valor "desconhecido" (`.money-unknown`, itálico); borda do aviso "◐ Dado parcial" (`.notice-partial`) | 9,77:1 em `surface` |
| `real` | `--real` | `RealBadge` e a borda esquerda dos cartões REAL (`.real-card`) | 8,11:1 em `surface` |
| `focus` | `--focus` | Contorno de foco de 3 px (`:focus-visible`) | 12,07:1 em `surface` |
| `border` | `--border` | Divisórias decorativas apenas | 1,67:1: **não** serve para indicar estado nem para o contorno do selo |

## 2. Elementos de cada tela

### 2.1 Criação do bot (`/bots/new`, `/bots/new/{id}`)

| Elemento | Componente ou classe | Situação |
|---|---|---|
| Título "Novo bot" e "Assistente do bot" | `BotsShell` (h1 com foco) | Muda: sai a etiqueta "MODO PAPER (simulado)" |
| Indicador "Etapa N de 5" | `.wizard-steps` e `wizard.stepIndicator` | Muda: de 4 para 5 etapas |
| Etapa 3 de um bot real (M3b) | `dl.totals` só leitura com os quatro limites REAL em SOL; `formatSolAmount`; "não definido" em `.money-unknown`; link "Alterar limites" para `/real` | Muda: no REAL, sem saldo virtual nem campos editáveis (decisão D1) |
| Escolha do modo | `ModeStep`, com `fieldset`, `legend` e rádios nativos (o mesmo padrão do `ModeSelector` do REAL) | **Novo** |
| Cartão de modo | `.real-card` (borda esquerda `real`) no "Real"; `.dashboard-card` com contorno `text-muted` no "Simulado" | Reuso, com uma classe nova `.mode-card` para o estado selecionado |
| Motivo de "Real" indisponível | `.muted` mais o ícone ⓘ com `aria-hidden`; o texto ligado por `aria-describedby` | Reuso |
| Link "Ativar a carteira do bot" | Link para `/real` (`ActivationScreen`) | Reuso |
| Hora de fim da quarentena | `formatInstant` (`lib/format/format`), como no `ReleaseStatus` | Reuso |
| Erro da elegibilidade ou da criação | `RealErrorNotice` | Reuso |
| Confirmação do bot real | `RealConfirmDialog` sobre o `ModalDialog` (`role="alertdialog"`) | **Novo** (sobre um componente que já existe) |
| Botão desabilitado com o motivo | O padrão de `activation.continueDisabled` | Reuso |
| Selo do modo | `RealBadge` e `SimulatedBadge` | Reuso e **novo** |

### 2.2 Lista de bots (`/bots`)

| Elemento | Componente ou classe | Situação |
|---|---|---|
| Título "Meus bots" | `BotsShell` | Muda: o h1 e o item do menu passam de "Bots PAPER" para "Meus bots" |
| Filtro de modo | `BotModeFilter` (`fieldset` e rádios) | **Novo** |
| Filtro de estado | O `select` "Filtrar por estado" | Fica como está |
| Tabela (768 px ou mais) | Tabela nova com as regras de `table.scanner` (cabeçalho, divisória) | Muda: hoje a lista é uma `ul` |
| Cartões (abaixo de 768 px) | O padrão de `.real-orders-cards` | Reuso |
| Coluna "Modo" | `SimulatedBadge` e `RealBadge` | Reuso e **novo** |
| P&L da linha | `SignedValue` (US$) e `formatSignedSol` (SOL), com a palavra "(ganho)" ou "(perda)" | Reuso, com o formato da seção 4 |
| P&L `unknown` | `.money-unknown` e o texto "desconhecido (<motivo>)" | Reuso, com o texto novo |
| Aviso do simulado | `.notice.notice-warning` e `role="note"` | Reuso, com o texto novo |
| Aviso do US6.2 | `.notice` e `role="note"` | Reuso, com o texto novo |
| Carregando | `role="status"` mais o esqueleto (o padrão de `.legal-skeleton`) | Reuso |
| Erro | `RealErrorNotice` (no lugar do erro sem código de hoje) | Muda [Q3] |
| Vazio | `bots.empty` "Você ainda não tem bots." mais "+ Novo bot" | Muda: o texto do botão passa de "Criar o primeiro bot" para "+ Novo bot", como no AC2.1.3 |

### 2.3 Resultado (`/pnl`) e painel (`/`)

| Elemento | Componente ou classe | Situação |
|---|---|---|
| h1 "Resultado" | `AppShell` com `titleId` | Muda: hoje é "P&L PAPER"; o item do menu acompanha |
| Seletor de período | `PeriodSelector`; os cálculos reusam `period.ts` (`toUtcPeriod`, `startOfLocalDay`, `addDays`, `todayIn`) | **Novo** (o componente); reuso (o cálculo) |
| Bloco de um modo | `PnlModeBlock` sobre `.dashboard-card` (PAPER) e `.real-card` (REAL); as partes em `dl.totals` | **Novo** (substitui o `Report` de `PnlScreen`, o `PnlCard` e o `RealPnlCard` nas telas deste intent) |
| Grade dos dois blocos | `.dashboard-cards` (2 colunas a partir de 768 px, 1 coluna abaixo) | Reuso; o `@media` de 1 coluna é **novo** |
| Valores com sinal | `SignedValue` e `PnlAmountView` (US$); `formatSignedSol` e `formatSolAmount` (SOL) | Reuso; o formato muda (seção 4) |
| Linhas rateadas | `ProratedLines` | **Novo** |
| Linha "Sem bot" | Linha do `PnlModeBlock` com "Motivo: …" em `.muted` | **Novo** |
| Aviso "◐ Dado parcial" | `.notice` com a classe nova `.notice-partial` (borda `unknown`); texto novo no lugar de `pnl.partial` | Muda: símbolo e rótulo diferentes do aviso do simulado |
| Comparação | `ComparisonView`, com dois `PnlModeBlock` `bot` | **Novo** |
| Conferência e ledger PAPER | `ReconciliationPanel` e `LedgerList`, com o `SimulatedBadge` no título | Ficam como estão, com o selo novo |
| Erro por bloco | `RealErrorNotice` (texto, código e "Tentar de novo") | Reuso |
| Totais do painel | Dois `PnlModeBlock` `compact` numa `section` "Resultados por modo" | Muda: substitui o `PnlCard` e o `DashboardRealPnl` |

### 2.4 Detalhe do bot (`/bots/{id}`)

| Elemento | Componente ou classe | Situação |
|---|---|---|
| Cabeçalho com o selo e o modo em texto | `BotsShell` e `RealBadge` ou `SimulatedBadge` | Muda |
| P&L do bot | `PnlModeBlock` `bot` | **Novo** |
| Tabela das linhas com a origem | `BotPnlLines` (classes `.real-orders-table` e `.real-orders-cards`) | **Novo** (sobre classes que já existem) |
| Seções do bot simulado (limites, uso do dia, operações, recusas, decisões) | As que já existem | Ficam como estão |
| Não encontrado | `detail.notFound` "Bot não encontrado." | Reuso |

## 3. O que é novo

| Novo | Tipo | Observação |
|---|---|---|
| `ModeStep` | componente | Rádios em cartões; a mesma estrutura do `ModeSelector` |
| `RealConfirmDialog` | componente | Sobre o `ModalDialog` |
| `SimulatedBadge` | componente | O par do `RealBadge`; classe `.simulated-badge`: contorno de 1 px em `text-muted`, texto `text`, ícone ◇, sem preenchimento |
| `PnlModeBlock` | componente | Variantes `full`, `compact` e `bot` |
| `ProratedLines` | componente | Bloco que abre e fecha [Q2] |
| `PeriodSelector` | componente | Reusa `period.ts` |
| `BotModeFilter` | componente | Rádios nativos |
| `ComparisonView` | componente | Dentro de `/pnl` |
| `BotPnlLines` | componente | Tabela e cartões |
| `.mode-card`, `.mode-card[data-selected]` | classe CSS | Borda de 3 px `accent` mais "✓ Selecionado" |
| `@media (max-width: 767px)` para `.dashboard-cards` | regra CSS | Uma coluna, sem rolagem lateral |
| `.notice-partial` | classe CSS | Borda `unknown`, símbolo ◐, rótulo "Dado parcial:" |
| Largura máxima de 1.200 px em `/bots` e `/pnl` | regra CSS | Largura de leitura [Q6] (hoje o `main` vai até 1.440 px) |
| Formatação compacta de SOL e US$ | função | Seção 4 |
| Textos novos | catálogo | Seção 5 |

Nenhum token, fonte ou tema novo.

## 4. Moedas e formato dos valores

| Modo | Moeda mostrada | Origem do valor | Formatação |
|---|---|---|---|
| PAPER | **US$** | Texto decimal da API (`PnlReport`, `PnlAmount`) | As funções de `features/pnl/decimal.ts` (`signedText`, `signOf`); prefixo "US$ " |
| REAL | **SOL** | Lamports inteiros da API, convertidos só por `toBaseUnits` e `toBaseUnitsOrUnknown` (`money.ts`) | `formatSignedSol` e `formatSolAmount` (`money.ts`), sufixo " SOL" |

- **Sinal.** "+" para ganho e "−" para perda. A palavra "(ganho)", "(perda)" ou "(zero)" vem do `SignedValue` (`pnl.gain`, `pnl.loss`, `pnl.zero`). As taxas aparecem como pagas, com "−" no total do bloco (o padrão de `feesText` do `PnlCard`).
- **Casas decimais.**
  - Os critérios usam "+US$ 12,40" e "+0,0312 SOL".
  - A proposta é cortar os zeros à direita, sem arredondar nenhum dígito diferente de zero, com pelo menos 2 casas. 31.200.000 lamports viram "0,0312"; 777.777.777 lamports viram "0,777777777"; 12,400000 vira "12,40".
  - O valor mostrado continua exato.
  - A variante compacta fica em `money.ts` (o SOL) e em `features/pnl/decimal.ts` (o US$). Nenhum `number` de JavaScript entra.
  - Ver a pergunta OQ1 de `mockups.md`.
- **Desconhecido.** "desconhecido (<motivo>)", com os motivos que já existem: `PRICE_UNKNOWN` "sem preço", `STALE` "preço desatualizado" e `NO_LIQUIDITY` "sem liquidez". Nunca zero.
- **Nada soma os modos.** Nenhuma função de formatação aceita um valor PAPER e um REAL juntos. O `PnlModeBlock` recebe um modo só. Não existe o texto "Total geral" no catálogo.

## 5. Textos de modo e de estado (pt-BR)

| Chave proposta | Texto | Fonte |
|---|---|---|
| `mode.legend` | "Modo do bot" | AC1.1.1 |
| `mode.paper.name` | "Simulado" | FR1.1 |
| `mode.paper.description` | "Opera com saldo virtual em US$. Nenhuma operação usa dinheiro real." | FR2.2, a partir do `listIntro` atual |
| `mode.real.name` | "Real" | FR1.1 |
| `mode.real.description` | "Opera com SOL da carteira do bot, dentro do teto. Pode perder o valor operado." | FR1.3, C-16 |
| `mode.selected` | "✓ Selecionado" | [Q2] |
| `mode.unavailable` | "Indisponível" | AC1.2.2 a AC1.2.6 |
| `mode.checking` | "Conferindo se o modo real está disponível…" | [Q3] |
| `mode.checkFailed` | "Não foi possível conferir o modo real agora." | [Q3] |
| `mode.reasons.KILL_SWITCH` | "As operações reais estão suspensas pelo sistema no momento." | AC1.2.6 |
| `mode.reasons.REAL_DISABLED` | "O modo real está desligado no momento." | AC1.2.5 |
| `mode.reasons.WALLET_INACTIVE` | "A carteira do bot não está ativa." | AC1.2.2 |
| `mode.reasons.NOT_RELEASED` | "Sua conta ainda não foi liberada para o modo real." | AC1.2.3 |
| `mode.reasons.QUARANTINE` | "Sua liberação para o modo real está em quarentena e ainda não vale. Vale a partir de {hora}." | AC1.2.4 |
| `mode.activateWallet` | "Ativar a carteira do bot" | AC1.2.2, AC4.3.3 |
| `mode.readOnly` | "Modo: {modo} · não pode ser trocado depois da criação" | AC1.4.3 |
| `create.submitPaper` | "Criar rascunho simulado" | AC1.1.2 |
| `create.submitReal` | "Continuar para a confirmação" | AC1.3.1 |
| `realConfirm.title` | "Confirmar bot real" | AC1.3.1 |
| `realConfirm.statement` | "Este bot opera com dinheiro real." | AC1.3.1 (frase fixa) |
| `realConfirm.checkbox` | "Confirmo: este bot opera com dinheiro real e as ordens dele usam o SOL da carteira do bot." | AC1.3.1 |
| `realConfirm.missing` | "Marque a confirmação para criar o bot real." | AC1.3.2 |
| `realConfirm.submit` | "Criar bot real" | AC1.3.3 |
| `realConfirm.sending` | "Criando o bot real…" | AC1.3.5 |
| `badge.simulated` | "SIMULADO" | AC2.1.2 |
| `badge.real` | "REAL" (já existe: `realMessages.badge`) | AC2.1.2 |
| `simulatedWarning` | "O resultado simulado não garante o resultado real." | FR4.2, AC4.2.1 |
| `list.title` | "Meus bots" | wireframes, tela 1 |
| `list.filter.legend` | "Modo" | AC2.3.1 |
| `list.filter.all`, `.paper`, `.real` | "Todos", "Simulado", "Real" | AC2.3.1 |
| `list.shown` | "Mostrando {n} de {m} bots." | AC2.3.2 |
| `list.emptyPaper` | "Você não tem bots simulados." | AC2.3.4 |
| `list.emptyReal` | "Você não tem bots reais." | AC2.3.4 |
| `list.newBot` | "+ Novo bot" | AC2.1.3 |
| `list.ownQuantityNotice` | "Cada bot real agora vende só a quantidade que ele mesmo comprou." | AC6.2.1 |
| `pnl.title` | "Resultado" | AC4.4.5 |
| `pnl.paperHeading` | "Simulado, em dólares" | AC4.4.5 |
| `pnl.realHeading` | "Real, em SOL" | AC4.4.5 |
| `pnl.paperTotalLabel` | "Total simulado, em dólares" | AC4.4.3 |
| `pnl.realTotalLabel` | "Total real, em SOL" | AC4.4.3 |
| `pnl.unknown` | "desconhecido" (hoje é `unknown`) | AC3.8.2 [Q3] |
| `pnl.unknownTotal` | "desconhecido (depende do não realizado)" | AC3.8.3 |
| `pnl.noBot` | "Sem bot" | FR3.4 |
| `pnl.noBotReason.SURPLUS_WITHOUT_OWNER` | "Motivo: excedente sem dono" | AC3.5.2 |
| `pnl.origins.BOT` | "Bot" | AC3.10.2 |
| `pnl.origins.LIQUIDATION` | "Fechado por liquidação" | AC3.10.2 |
| `pnl.origins.CONDITIONAL` | "Fechado por condicional" | AC3.10.2 |
| `pnl.origins.SOLD_BY_OTHER_BOT` | "Vendido por outro bot" | AC3.10.2 |
| `pnl.botStates.REMOVED` | "Removido" | AC3.9.2 |
| `pnl.period.*` | "Desde o início", "Últimos 7 dias", "Últimos 30 dias", "Intervalo" | AC7.1.2 |
| `pnl.period.inverted` | "A data final vem antes da inicial. Corrija o campo "Até"." | AC7.1.4 |
| `pnl.emptyReal` | "Sem bots reais." | AC4.3.2 |
| `pnl.partialNotice` | "Dado parcial: há posição sem preço confiável." | AC3.8.3, revisão R-05 |
| `dashboard.period` | "Resultados por modo · desde o início" | AC4.3.1, decisão D2 |
| `wizard.realLimits.title` | "Limites da sua conta REAL" | decisão D1 |
| `wizard.realLimits.intro` | "Valem para todos os seus bots reais. Este bot não tem saldo virtual: ele opera com o SOL da carteira do bot." | decisão D1 |
| `wizard.realLimits.cap`, `.orderMax`, `.dailyBuys`, `.dailyLoss` | "Teto da carteira do bot", "Tamanho máximo por ordem", "Compras de bot por dia", "Perda máxima do dia" | decisão D1 (BR5.1, BR4.1, BR4.2, BR4.3) |
| `wizard.realLimits.notSet` | "não definido" | decisão D1 |
| `wizard.realLimits.change` | "Alterar limites" | decisão D1 |
| `dashboard.paperTotal` | "Resultado simulado" | AC4.3.1 |
| `dashboard.realTotal` | "Resultado real" | AC4.3.1 |
| `compare.title` | "Comparar um bot simulado com um bot real" | FR8.1 |
| `compare.note` | "Os valores ficam cada um na sua moeda e não são comparados por conta." | AC8.1.2 |

Nenhum texto promete lucro (AC4.2.7, C-16). Os textos dos motivos são a proposta das histórias e podem ser ajustados, desde que continuem diferentes entre si (AC1.2.7).

### Textos que já existem e mudam

| Onde | Hoje | Proposta | Motivo |
|---|---|---|---|
| `botMessages.listTitle` e `navMessages.items.bots` | "Bots PAPER" | "Meus bots" | A lista tem os dois modos (FR2.1) |
| `messages.pnl.title` e `navMessages.items.pnl` | "P&L PAPER" | "Resultado" | AC4.4.5 |
| `BotsShell`, `PnlScreen` e o cabeçalho do `AppShell` | etiqueta "MODO PAPER (simulado)" | sai; o modo vai em cada bot e em cada bloco | FR2.1, FR4.1 |
| `wizard.start` e `wizard.starting` | "Iniciar bot PAPER" e "Iniciando bot PAPER" | "Iniciar bot simulado" ou "Iniciar bot real" | US1.4 |
| `pnl.unknown` | "unknown" | "desconhecido" | [Q3] |
| Unidade do PAPER | "USDC" | "US$" | FR2.2, AC2.2.1; ver a pergunta OQ1 de `mockups.md` |

## Assumptions & Open Questions

**Suposições** [assumption]:
1. **Contorno do `SimulatedBadge`.** Usa `text-muted`, e não `border`, porque o `border` tem só 1,67:1 e não chega aos 3:1 de um componente de interface.
2. **Os dois apps.** O `apps/admin` não entra neste mapeamento: nenhuma história deste intent muda a administração.

**Perguntas em aberto:** consolidadas na lista única do fim de `mockups.md` (OQ1 a OQ7), cada uma com o dono e a opção recomendada.
