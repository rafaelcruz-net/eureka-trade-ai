# Checklist de acessibilidade — Trading REAL

Conversa: português (Brasil). Fontes: `mockups.md`, `interaction-spec.md`, `inception/user-stories/stories.md` (US4.1 e as telas das demais histórias), `inception/requirements-analysis/requirements.md` (NFR7) e a regra de acessibilidade básica do beta em `project.md`.

## Nível

Acessibilidade básica do beta (Q6): teclado, foco visível, rótulos visíveis, contraste legível no tema escuro, cor sempre com texto ou ícone. WCAG 2.1 AA completo fica para antes da abertura ao público.

## Checklist por tela

| Item | R1 | R2 | R3 | R4 | R5 | R6 | A1 |
|---|---|---|---|---|---|---|---|
| Tudo operável só pelo teclado | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Foco visível (anel 2 px, contraste 3:1) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Rótulos visíveis em todo campo | ✓ | — | ✓ | — | — | ✓ | ✓ |
| Cor sempre com texto ou ícone (REAL, estados, P&L) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Diálogos com foco preso, Esc e retorno do foco | ✓ | — | ✓ | ✓ | — | ✓ | ✓ |
| Mensagens dinâmicas em `aria-live` | ✓ | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| Títulos h1 a h3 em ordem e regiões de página | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Sem rolagem lateral a partir de 320 px | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

## Pontos específicos

- **Prazo de 10 s da assinatura:** é um limite de tempo; ele é inevitável (a cotação vence), então o diálogo avisa o prazo antes, a contagem é anunciada só em marcos (10 s e 5 s) e a expiração não perde nada (o próximo ciclo traz outra proposta).
- **Valores em SOL:** lidos por leitores de tela como número completo, com a unidade.
- **Duplo envio:** botões de ação com dinheiro ficam desabilitados durante o envio, com o motivo em texto.
- **Testes:** testes de componente cobrem teclado, foco e `aria-live` em cada componente novo.
