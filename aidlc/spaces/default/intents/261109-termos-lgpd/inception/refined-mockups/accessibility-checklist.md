# Checklist de acessibilidade — Termos de uso e LGPD

Conversa: português (Brasil). Nível (Q5): o básico do beta em todas as telas; contraste e estrutura do WCAG 2.1 AA em T1 e T2. Base: `ideation/rough-mockups/wireframes.md`, `user-flow.md`, `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md` (NFR4), `inception/practices-discovery/team-practices.md` e `refined-mockups-questions.md`.

## Por tela

| Tela | Títulos | Regiões | Entrada do teclado | Ordem de foco | Cor com texto ou ícone | Nível |
|---|---|---|---|---|---|---|
| T1 `/termos` | h1 documento, h2 seções | header, main, nav (índice), footer | "Pular para o conteúdo" → h1 | pular → Entrar → índice → texto → rodapé | aviso PAPER com ícone e texto | AA |
| T2 `/privacidade` | h1, h2, tabela com `caption` | header, main, nav, footer | "Pular para o conteúdo" → h1 | pular → índice → texto → "Fazer um pedido" → rodapé | idem | AA |
| T3 rodapé | — | footer | — | links em ordem | — | básico |
| T4 convite | h1 | main, footer | "Pular para o conteúdo" → h1 | resumo → links → caixa 1 → caixa 2 → Conectar | motivo do botão desabilitado em texto | básico |
| T5 Conta | h1, h2 "Meus dados (LGPD)", h2 "Meus pedidos", h3 pedido | nav, main, footer | "Pular para o conteúdo" → h1 | dados da conta → tipo (grupo de rádio) → detalhes → enviar → lista | situação por `RequestStatusTag` em texto | básico |
| T6 pedidos (admin) | h1, h2 pedido, h3 verificação e resposta | nav, main, footer | "Pular para o conteúdo" → h1 | filtro → lista → detalhe → verificação → resposta → ações | prazo vencido com ícone e texto | básico |
| T7 novo aceite | h1 "Os termos mudaram", h2 "O que mudou" | main, footer | foco no h1 ao abrir | links → caixa → Aceitar → Sair | — | básico |
| T6a registro por e-mail | h2 | main | foco no primeiro campo ao abrir | tipo → contato → detalhes → conta → data → Registrar | erros em texto junto do campo | básico |
| T6b verificação | h3 | região do painel com `aria-live="polite"` para a mudança de estado | botão da etapa atual | mensagem → Copiar → assinatura → Conferir | cada estado com texto e ícone | básico |
| T6c recusa | h2 no diálogo | `role="dialog"` | foco no campo de motivo | motivo → resposta → Cancelar → Recusar | motivo obrigatório explicado em texto | básico |
| T8 diálogo | h2 no diálogo | `role="dialog"`, `aria-modal`, `aria-labelledby` | foco no campo de confirmação ao abrir | campo → Cancelar → Anonimizar; Esc fecha; o foco volta ao botão de origem | erro em texto | básico |

## Itens gerais

- [ ] Foco visível em todos os elementos interativos (contorno com contraste de 3:1).
- [ ] Rótulos visíveis em todos os campos; contador "0/1000" associado ao campo por `aria-describedby`.
- [ ] Erros anunciados (`role="alert"`) e ligados ao campo.
- [ ] Botões desabilitados explicam o motivo em texto visível.
- [ ] Nenhuma informação só por cor (situação, prazo, erro).
- [ ] Links que abrem documento dizem para onde levam.
- [ ] Contraste de texto 4.5:1 nas páginas legais (AA) e legível no tema escuro nas demais.
- [ ] T1 e T2 funcionam com zoom de 200% e a partir de 320 px sem rolagem horizontal.
- [ ] Teste automático de teclado nos specs de ponta a ponta das telas novas, como já é feito no smoke da U1.
