# Requisitos de observabilidade e acessibilidade — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes:
- `nfr-requirements-questions.md` (Q4, Q7);
- `inception/refined-mockups/accessibility-checklist.md`;
- `project.md` (acessibilidade básica do beta);
- `team.md`, que não tem portão automático de acessibilidade na CI.

## Observabilidade

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR5.1 | O navegador não ganha telemetria nova. As falhas mostram o texto de BR8.8 e o `correlationId` do ProblemDetail, sem stack trace. | teste de componente |
| U6-NFR5.2 | A rota nova e o `serverTime` seguem os padrões do servidor: registro de rotas, log sem dado sensível e relógio injetável | testes do `apps/api` |

## Acessibilidade

A verificação é feita com testes de componente comuns, que consultam por papel e rótulo (Testing Library), e com teclado no e2e. Não entra ferramenta automática de auditoria de acessibilidade, para manter a decisão do `team.md`.

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR6.1 | Todas as ações funcionam pelo teclado e têm rótulo visível, com nome acessível | testes de componente com `getByRole` e o nome; e2e `real-ui` percorrendo a ativação, o modo e a assinatura só com o teclado |
| U6-NFR6.2 | Os diálogos têm foco preso, Esc fecha e o foco volta para quem abriu; o `MoneyConfirmDialog` começa em "Cancelar" | teste de componente |
| U6-NFR6.3 | O `aria-live` da faixa anuncia só nos marcos de 10 s e 5 s | teste de componente |
| U6-NFR6.4 | O texto e o selo `REAL` têm contraste de pelo menos 4,5:1 nos dois temas; a cor sempre vem com texto ou ícone | teste de unidade que calcula o contraste a partir dos tokens de cor dos dois temas; teste de componente que confere texto ou ícone em cada selo |
| U6-NFR6.5 | Sem rolagem lateral a partir de 320 px; as ordens viram cartões abaixo de 768 px | e2e com a janela de 320 px (`scrollWidth` menor ou igual a `clientWidth`) |
| U6-NFR6.6 | O foco é visível em todos os controles | e2e que confere o contorno do foco nos controles das telas do REAL |
