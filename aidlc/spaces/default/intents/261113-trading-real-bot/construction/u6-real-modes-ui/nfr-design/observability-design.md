# Projeto de observabilidade e acessibilidade — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `construction/u6-real-modes-ui/nfr-requirements/observability-requirements.md` (U6-NFR5.x, U6-NFR6.x), `inception/refined-mockups/interaction-spec.md` e `accessibility-checklist.md`.

| Requisito | Como o projeto atende |
|---|---|
| U6-NFR5.1 | Um componente `RealErrorNotice` mostra o texto de BR8.8 e o `correlationId`, sem stack trace e sem `console`. |
| U6-NFR5.2 | A rota nova e o `serverTime` usam o log e o `Clock` que já existem. |
| U6-NFR6.1 | Os componentes usam elementos nativos (`button`, `input`, `fieldset` com `legend`) com rótulos visíveis, e os testes consultam por papel e nome. |
| U6-NFR6.2 | `MoneyConfirmDialog`, `SigningDialog` e `AdminActionDialog` usam o `<dialog>` nativo com `showModal()`, que prende o foco e fecha com Esc. O foco inicial é "Cancelar" no `MoneyConfirmDialog` e "Assinar na carteira" no `SigningDialog`, e o foco volta para quem abriu. |
| U6-NFR6.3 | O `SigningBanner` tem uma região `aria-live="polite"` que só recebe texto nos marcos de 10 s e 5 s, calculados pelo `countdown.ts`. |
| U6-NFR6.4 | O selo âmbar usa os tokens de cor dos dois temas. Um teste de unidade calcula o contraste (fórmula do WCAG) a partir dos tokens e exige pelo menos 4,5:1. Todo selo tem texto e ícone. |
| U6-NFR6.5, U6-NFR6.6 | Layout com CSS em grade. Abaixo de 768 px, a tabela de ordens vira lista de cartões. O e2e confere a largura de 320 px e o contorno do foco. |
