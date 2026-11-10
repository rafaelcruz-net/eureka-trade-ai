# Componentes de frontend — U5 `u5-anonymization`

Conversa: português (Brasil). Só o app do administrador (`apps/admin`) muda. Textos em pt-BR, com `data-testid` nos elementos interativos.

| Componente | Onde | Props e estado | Interação |
|---|---|---|---|
| `AnonymizeAccountButton` | detalhe de um pedido de Exclusão `IN_PROGRESS` com conta ligada (T6a) | pedido | abre o diálogo; fica oculto nos outros casos |
| `AnonymizeAccountDialog` (T8) | modal | texto digitado; `submitting`, `error` | explica o que sai e o que fica; habilita "Anonimizar" só com `ANONIMIZAR`; mostra `409` (identidade não confirmada, pedido encerrado) e `422` em texto; no sucesso, fecha e atualiza o detalhe |

## Acessibilidade

- O diálogo usa `role="dialog"`, com `aria-modal` e título ligado por `aria-labelledby`.
- O foco fica preso no diálogo, Esc fecha, e o foco volta ao botão que abriu.
- O campo tem rótulo visível, e o motivo do botão desabilitado aparece em texto.
