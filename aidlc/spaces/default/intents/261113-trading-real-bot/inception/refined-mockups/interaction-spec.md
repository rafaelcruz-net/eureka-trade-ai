# Especificação de interação — Trading REAL

Conversa: português (Brasil). Fontes: `mockups.md`, `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md` e `refined-mockups-questions.md`. Formato de `.claude/knowledge/aidlc-design-agent/component-spec-template.md`.

## SigningBanner (faixa de ordem para assinar)

| Field | Value |
|---|---|
| Component | SigningBanner |
| Description | Avisa em todas as telas que há uma ordem para assinar e mostra a contagem regressiva |
| Category | feedback |

### States

| State | Description | Trigger |
|---|---|---|
| default | Token, valor e contagem (10 s) | proposta nova no modo Assinada |
| expired | "A proposta expirou. Nada foi enviado." por 5 s | contagem chega a zero |
| hidden | Não aparece | sem proposta ou fora do modo Assinada |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `region` com `aria-live="polite"`; a contagem anuncia só nos marcos 10 s e 5 s |
| Keyboard interaction | Tab chega ao botão "Ver"; Enter abre o SigningDialog |
| Contrast ratio | WCAG AA (texto 4,5:1) |
| Focus management | ao abrir o diálogo, o foco vai para "Assinar na carteira" |

## SigningDialog (diálogo de assinatura)

| Field | Value |
|---|---|
| Component | SigningDialog |
| Description | Mostra a ordem e chama a carteira do usuário para assinar |
| Category | input |

### States

| State | Description | Trigger |
|---|---|---|
| default | Detalhes da ordem e botões | aberto pela faixa |
| loading | "Aguardando a carteira…" | clicou em Assinar |
| error | Texto do motivo (recusada, expirada, carteira desconectada) | falha |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `dialog`, `aria-modal`, título por `aria-labelledby` |
| Keyboard interaction | foco preso; Esc fecha sem assinar; foco volta para a faixa |
| Screen reader | anuncia token, valor, derrapagem, taxa e o prazo |

## ModeSelector (escolha do modo)

| Field | Value |
|---|---|
| Component | ModeSelector |
| Description | Três cartões de rádio com uma frase cada |
| Category | input |

### States

| State | Description | Trigger |
|---|---|---|
| default | Modo atual marcado | carga |
| disabled | "Automática nas saídas" com "Em breve" | até a entrega do FR13 |
| confirming | Diálogo de consentimento com assinatura | subir de modo |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | grupo de rádio com `fieldset`/`legend` |
| Keyboard interaction | setas trocam a opção; Enter salva |

## MoneyConfirmDialog (confirmação de ação com dinheiro)

| Field | Value |
|---|---|
| Component | MoneyConfirmDialog |
| Description | Confirma ativar, subir de modo, sacar e vender tudo e sacar, nomeando valor e destino |
| Category | feedback |

### States

| State | Description | Trigger |
|---|---|---|
| default | Valor, destino e efeito | ação com dinheiro |
| submitting | Botão desabilitado; impede duplo envio | confirmou |
| error | Motivo em texto e "tentar de novo" | falha da API |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `alertdialog` |
| Keyboard interaction | foco inicial em "Cancelar"; Esc cancela |

## OrderStateBadge (estado da ordem)

| State | Texto | Ícone |
|---|---|---|
| proposta | Proposta | ○ |
| cotada | Cotada | ◔ |
| assinada | Assinada | ◑ |
| enviada | Enviada | ◕ |
| confirmada | Confirmada | ✔ |
| falhou | Falhou | ✖ |
| ambígua | Em conferência | ⟳ |

Sempre texto mais ícone; nunca só cor.

## Responsive Behaviour (telas R1 a R6 e A1)

| Breakpoint | Behaviour |
|---|---|
| mobile (<768px) | menu recolhido; cartões empilhados; tabela de ordens vira lista de cartões |
| tablet (768–1024px) | duas colunas na visão geral |
| desktop (>1024px) | layout completo; sem rolagem lateral a partir de 320 px |

## Fluxos

```
Flow: Ativar e operar
Persona: P2 convidado
Trigger: abre "Carteira do bot" com o REAL liberado
Steps:
  1. R1 passo 1 -> aceita termos -> segue
  2. R1 passo 2 -> assina consentimento -> carteira criada
  3. R6 -> deposita da carteira principal -> saldo aparece após finalizar na rede
  4. R3 -> mantém Assinada -> bot propõe -> R4 -> assina -> ordem enviada
Success outcome: ordem confirmada em R5 com link da transação
Error paths:
  - REAL não liberado -> aviso em R1 -> espera liberação
  - Assinatura recusada -> nada enviado -> próxima proposta
  - Proposta expirada -> "Nada foi enviado"
```
