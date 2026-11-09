# Mapeamento para o design existente — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/rough-mockups/wireframes.md`, `user-flow.md`, `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md`, `inception/practices-discovery/team-practices.md` e `refined-mockups-questions.md` (Q4). Nenhuma biblioteca nova.

## Reaproveitado

| Elemento existente | Onde | Uso nestas telas |
|---|---|---|
| `AppShell` (web e admin) | `apps/web/src/components/shell/AppShell.tsx` | Moldura de T4, T5, T6 e T7; ganha o `SiteFooter` |
| `Dialog` (admin) | `apps/admin/src/components/Dialog.tsx` | T8, anonimização |
| `.skip-link` | `globals.css` | "Pular para o conteúdo" em todas as telas |
| `.notice`, `.notice-warning`, `.notice-error` | `globals.css` | Aviso PAPER, versão não publicável, erros e confirmações |
| `.field-error` | `globals.css` | Erros de formulário em T5 e T6 |
| `.tag` | `globals.css` | Base do `RequestStatusTag` |
| `.muted` | `globals.css` | Textos de apoio (prazo de 15 dias, motivo de botão desabilitado) |
| `.skeleton-row` | `globals.css` | Carregando em T1, T2, T4, T5 e T6 |
| `.visually-hidden` | `globals.css` | Rótulos só para leitor de tela quando necessário |
| `DesktopOnlyNotice` | `apps/web/src/components/DesktopOnlyNotice.tsx` | Continua nas telas com login abaixo de 1280 px; não aparece em T1 e T2 |
| Lista de termos proibidos | `apps/web/src/content/prohibited-terms.json` | Teste de "promessa de lucro" nos textos (US1.1) |

## Novos

| Componente | App | Base | Observação |
|---|---|---|---|
| `LegalDocument` | web e admin | tipografia e cores atuais | Largura de leitura até 72 caracteres; índice |
| `SiteFooter` | web e admin | `footer` simples | Dentro do `AppShell` e nas páginas públicas |
| `RequestStatusTag` | web (Conta) e admin | `.tag` | Situação sempre em texto; alerta de prazo com ícone e texto |

## Tokens e convenções

- Tema escuro existente; sem cores novas. O alerta de prazo usa a cor de aviso de `.notice-warning`, sempre com ícone e texto.
- Textos de interface em português do Brasil; código, rotas e enums em inglês (`IN_PROGRESS`, `AWAITING_VERIFICATION`), conforme o time.
- Os textos de interface ficam no arquivo de mensagens de cada app, como hoje.
