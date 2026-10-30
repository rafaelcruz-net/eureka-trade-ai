# Mapeamento do sistema de design — MemeTrade Analytics

Não existe guia visual ([wireframes](../../ideation/rough-mockups/wireframes.md), decisão Q6 dos mockups iniciais). A decisão desta etapa (Q1 de [refined-mockups-questions.md](refined-mockups-questions.md)) é usar uma biblioteca de componentes acessíveis sem visual imposto, com tokens próprios. A biblioteca concreta é escolhida no design da aplicação, alinhada a [team-practices.md](../practices-discovery/team-practices.md); este documento define o vocabulário visual que qualquer escolha deve receber. Os componentes atendem às telas de [mockups.md](mockups.md), às histórias de [stories.md](../user-stories/stories.md), aos [fluxos](../../ideation/rough-mockups/user-flow.md) e ao NFR11 de [requirements.md](../requirements-analysis/requirements.md).

## Tokens de cor (tema escuro)

Valores iniciais propostos; a verificação de contraste é obrigatória antes da implementação (meta: texto ≥ 4,5:1, componentes e foco ≥ 3:1).

| Token | Uso | Valor proposto |
|---|---|---|
| `color.bg.base` | Fundo da aplicação | #0F1216 |
| `color.bg.surface` | Cartões, tabelas, painel lateral | #171B21 |
| `color.bg.raised` | Diálogos, menus | #1F242C |
| `color.border.default` | Divisórias | #2E3540 |
| `color.text.primary` | Texto principal | #E8ECF1 |
| `color.text.secondary` | Texto auxiliar | #A9B3BF |
| `color.focus.ring` | Anel de foco | #7CC4FF |
| `color.positive` | Ganho, ok (sempre com sinal +) | #4ADE80 |
| `color.negative` | Perda, erro (sempre com sinal -) | #F87171 |
| `color.warning` | Desatualizado, divergente, perto do limite | #FBBF24 |
| `color.risk.low` / `medium` / `high` | Faixas do indicador (sempre com texto) | #4ADE80 / #FBBF24 / #F87171 |
| `color.unknown` | Estado `unknown` e indisponível | #94A3B8 |
| `color.paper` | Rótulo "MODO PAPER (simulado)" | #C4B5FD |

## Tokens de tipografia e espaçamento

| Token | Valor proposto | Uso |
|---|---|---|
| `font.family.ui` | Fonte sem serifa do sistema | Interface |
| `font.numeric` | Algarismos de largura fixa (tabular) | Números em tabelas (Q4) |
| `font.size.body` | 14 px | Texto e tabelas densas |
| `font.size.small` | 12 px | Rótulos auxiliares (mínimo) |
| `font.size.h1` | 22 px | Título da tela |
| `space.scale` | 4, 8, 12, 16, 24, 32 px | Espaçamentos |
| `layout.minWidth` | 1280 px | Largura mínima (Q2) |
| `layout.sidePanel` | 360 px | Painel lateral do scanner |

## Componentes da biblioteca e onde são usados

| Componente | Telas | Observação |
|---|---|---|
| Tabela de dados | S3, S5, S6, S8, A2 | Navegação por teclado e ordenação anunciada |
| Painel lateral (drawer não modal) | S3 | Não bloqueia a lista |
| Diálogo modal | S5 (encerrar), S9, A1 (kill switch), A2 (revogar) | Foco preso e retorno ao botão de origem |
| Formulário (campo, seleção, rádio) | S4, S9, A2 | Rótulo visível, unidade e erro em texto |
| Indicador de etapas | S4 | "Etapa N de 4" |
| Selo de estado | S5, S8, A1 | Estado em texto |
| Barra de progresso | S5 | Decorativa; valor em texto |
| Dica de ferramenta (tooltip) | S3, S6 | Abre ao passar o mouse e ao focar |
| Faixa de aviso | S2, S3, S5, A1 | Ícone + texto |
| Menu de navegação | Todas | Menus separados para convidado e administrador (Q3) |

## Componentes próprios (sobre a biblioteca)

| Componente | Base | Motivo |
|---|---|---|
| Indicador de risco | Selo | Número + faixa + "em validação" (Q6) |
| Valor com origem | Texto + tooltip | Fonte, horário, frescor e `unknown` (FR4.3, FR4.4) |
| Número formatado | Texto | Regras de Q4 |
| Rótulo de modo PAPER | Selo | Presente em toda tela de bot e P&L (DP-4) |
| Barra de uso de limite | Barra + texto | Uso do dia (US4.10) |
