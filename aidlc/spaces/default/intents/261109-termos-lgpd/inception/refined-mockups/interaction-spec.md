# Especificação de interação — Termos de uso e LGPD

Conversa: português (Brasil). Formato de componente conforme o modelo do time. Base: `ideation/rough-mockups/wireframes.md`, `user-flow.md`, `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md`, `inception/practices-discovery/team-practices.md` e `refined-mockups-questions.md`.

## LegalDocument

| Field | Value |
|---|---|
| Component | LegalDocument |
| Description | Mostra um documento versionado (termos ou política) com versão, vigência, aviso, resumo, índice e seções |
| Category | display |

### States

| State | Description | Trigger |
|---|---|---|
| default | Documento vigente completo | carregamento ok |
| loading | Três linhas de carregando | busca em andamento |
| error | `.notice-error` com "tentar de novo" | falha da API |
| partial | Campo de implantação "a definir" com `.notice-warning` | versão não publicável |
| focus | Foco visível em links do índice e do texto | Tab |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| kind | `"terms" \| "privacy"` | yes | — | Qual documento |
| version | object | yes | — | Versão, vigência, resumo, seções e campos de implantação |
| previousVersions | array | no | `[]` | Lista para "Versões anteriores" (Could) |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| mobile (<768px) | Uma coluna; índice recolhido em "Nesta página" no topo |
| tablet (768–1024px) | Uma coluna; índice aberto no topo |
| desktop (>1024px) | Texto à esquerda (até 72 caracteres por linha) e índice fixo à direita |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `main` para o conteúdo; `nav` com `aria-label="Nesta página"` para o índice |
| Keyboard interaction | Tab percorre "Pular para o conteúdo", índice e links; Enter segue a âncora e move o foco para o título da seção |
| Label / aria-label | Títulos h1 (documento), h2 (seções), h3 (subseções) |
| Contrast ratio | WCAG AA (4.5:1 texto, 3:1 componentes) |
| Screen reader | A tabela de retenção tem `caption` e `th scope="col"` |
| Focus management | Ao abrir, o foco fica no topo; "Pular para o conteúdo" leva ao h1 |

### Usage Example

```
<LegalDocument kind="privacy" version={current} previousVersions={history} />
```

## SiteFooter

| Field | Value |
|---|---|
| Component | SiteFooter |
| Description | Links para termos e privacidade e, com login, a versão aceita |
| Category | navigation |

### States

| State | Description | Trigger |
|---|---|---|
| default | Dois links | sempre |
| authenticated | Dois links e "Versão aceita: vX" | sessão presente |
| focus | Foco visível nos links | Tab |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| acceptedVersion | string | no | — | Mostrada só quando presente |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| mobile (<768px) | Links empilhados |
| tablet (768–1024px) | Em linha |
| desktop (>1024px) | Em linha, versão à direita |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `contentinfo` (elemento `footer`) |
| Keyboard interaction | Tab alcança os dois links |
| Label / aria-label | Texto visível dos links |
| Contrast ratio | WCAG AA |
| Screen reader | "Versão aceita: v2" lida como texto |
| Focus management | Não move o foco |

### Usage Example

```
<SiteFooter acceptedVersion={session?.acceptedTermsVersion} />
```

## RequestStatusTag

| Field | Value |
|---|---|
| Component | RequestStatusTag |
| Description | Mostra a situação de um pedido de titular com texto, e alerta de prazo quando aplicável |
| Category | feedback |

### States

| State | Description | Trigger |
|---|---|---|
| received | "Recebido" | estado RECEIVED |
| awaiting | "Aguardando verificação" | estado AWAITING_VERIFICATION |
| in-progress | "Em andamento" | estado IN_PROGRESS |
| done | "Concluído" | estado DONE |
| refused | "Recusado" | estado REFUSED |
| due-soon | ícone ⚠ e "vence em N dias" | prazo ≤ 3 dias (Should) |
| overdue | ícone ⚠ e "vencido há N dias" | prazo passado (Should) |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| status | enum | yes | — | Situação |
| dueAt | string (data UTC) | no | — | Para o alerta de prazo |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| mobile (<768px) | Não se aplica (telas para 1280 px ou mais) |
| tablet (768–1024px) | Não se aplica |
| desktop (>1024px) | Tag em linha na tabela |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | texto comum (sem papel especial) |
| Keyboard interaction | Não é interativa |
| Label / aria-label | O texto visível é o rótulo; o ícone tem `aria-hidden` |
| Contrast ratio | WCAG AA; cor nunca sozinha |
| Screen reader | Lê a situação e o alerta de prazo |
| Focus management | Não recebe foco |

### Usage Example

```
<RequestStatusTag status="IN_PROGRESS" dueAt="2026-11-30T00:00:00Z" />
```

## Interações de fluxo

| Interação | Gatilho | Comportamento | Erro |
|---|---|---|---|
| Novo aceite (T7) | Resposta `403 TERMS_REACCEPTANCE_REQUIRED` | O app troca o conteúdo pela T7 e põe o foco no h1. Ao aceitar, volta à rota original | `409 TERMS_VERSION_OUTDATED`: recarrega a T7 com a versão nova e avisa |
| Envio de pedido (T5) | "Enviar pedido" | Botão desabilitado com "Enviando…". No sucesso, `.notice` "Pedido recebido. Prazo: dd/mm" e a lista atualizada | `409` ou `400` em `.field-error` junto do campo; o foco vai para o erro |
| Verificação (T6) | "Pedir verificação" num pedido por e-mail | Gera a mensagem; o pedido vai para "Aguardando verificação"; o botão "Copiar mensagem" avisa "Copiado" | Assinatura inválida: `.notice-error` e o pedido segue aguardando |
| Resumo de Acesso (T6) | "Ver resumo" | O painel expande com o resumo para conferência. "Concluir" libera o resumo junto da resposta | Sem identidade confirmada, o botão fica desabilitado com o motivo. Se a API devolver `409 IDENTITY_NOT_VERIFIED` mesmo assim (verificação vencida no meio), aparece `.notice-error` "A identidade deste pedido não está confirmada (pode ter vencido agora)" e o painel de verificação é recarregado |
| Recusa (T6c) | "Recusar…" | Diálogo com motivo obrigatório; "Recusar pedido" só habilita com motivo; no sucesso, o pedido fica Recusado e o motivo fica visível em T5 | `409` de pedido encerrado: mensagem no diálogo e detalhe atualizado |
| Registro por e-mail (T6a) | "Registrar pedido por e-mail" | Formulário com tipo, contato, detalhes, conta opcional e data de recebimento | Validação junto de cada campo |
| Anonimização (T8) | "Anonimizar conta…" | Abre o `Dialog`; "Anonimizar" só habilita com `ANONIMIZAR` exato. No sucesso, fecha, o pedido aparece "Concluído" e o foco volta à lista | `409`: mensagem no diálogo, sem fechar |
| Verificação: estados (T6b) | Situação do pedido por e-mail | Sem conta ligada, pronto, aguardando, conferindo, confirmada, inválida, já usada e vencida, conforme a tabela de T6b em `mockups.md` | Cada estado tem texto próprio; nenhum mostra a mensagem ou a assinatura fora do painel |

Todas as datas aparecem no fuso da pessoa; a API grava e trafega em UTC.
