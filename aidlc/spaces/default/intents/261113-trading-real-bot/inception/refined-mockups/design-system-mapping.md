# Mapa do sistema de design — Trading REAL

Conversa: português (Brasil). Fontes: `mockups.md`, `interaction-spec.md`, `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md` e o vocabulário visual do intent `261026-memetrade-saas-mvp` (`inception/refined-mockups/design-system-mapping.md`).

## Tokens reaproveitados

| Token | Uso no REAL |
|---|---|
| `color.bg.*`, `color.text.*`, `color.focus.ring` | iguais às telas existentes |
| `color.positive` / `color.negative` | P&L REAL, sempre com sinal + ou - |
| `color.warning` | REAL pausado, divergência, ordem em conferência, perto do limite |
| `color.unknown` | posição sem preço |
| `font.numeric` | valores em SOL (até 9 casas) |

## Tokens novos

| Token | Uso | Valor proposto |
|---|---|---|
| `color.real` | Rótulo "REAL" em toda tela com dinheiro real | #F59E0B (sempre com o texto "REAL") |
| `color.reserved` | Valor reservado em ordens | #93C5FD (com o rótulo "Reservado") |

O rótulo REAL é o par do rótulo PAPER existente: toda tela de carteira, ordem ou P&L mostra um dos dois.

## Componentes da biblioteca usados

| Componente | Telas |
|---|---|
| Diálogo modal | R1 (consentimento), R3 (subir de modo), R4, R6, A1 |
| Indicador de etapas | R1 ("Passo N de 3") |
| Grupo de rádio | R3 |
| Tabela de dados | R5, A1 |
| Faixa de aviso | SigningBanner, REAL pausado, kill switch |
| Selo de estado | OrderStateBadge, estado da liberação |
| Formulário | R6 (valor do saque), teto no perfil |

## Componentes próprios novos

| Componente | Base | Motivo |
|---|---|---|
| SigningBanner | Faixa de aviso | contagem regressiva global (US3.2) |
| SigningDialog | Diálogo | assinatura na carteira do usuário |
| ModeSelector | Grupo de rádio | três modos com descrição |
| MoneyConfirmDialog | Diálogo de alerta | confirmação com valor e destino |
| OrderStateBadge | Selo | estados da ordem em texto e ícone |
| SolAmount | Número formatado | até 9 casas, nunca arredondado para zero |
| RealModeLabel | Selo | par do rótulo PAPER |
