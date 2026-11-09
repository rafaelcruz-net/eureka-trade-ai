# Perguntas das Telas Refinadas — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/rough-mockups/wireframes.md` e `user-flow.md`, `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md` e `inception/practices-discovery/team-practices.md`.

O que já existe nos apps:
- `AppShell` (navegação, região de avisos), `Dialog`, link "pular para o conteúdo" (`.skip-link`), avisos (`.notice`, `.notice-error`, `.notice-warning`), `.field-error`, `.tag`, `.muted`, linhas de carregando (`.skeleton-row`), tema escuro;
- o aviso "só para computador" abaixo de 1280 px (`DesktopOnlyNotice`).

## Q1. Como cada história vira tela?

A) Oito telas ou componentes:
- T1 `/termos` e T2 `/privacidade` (US1.1, US1.2, US2.1, US2.4, US2.5, US2.6);
- T3 rodapé (US2.2);
- T4 convite (US2.3);
- T5 Conta com "Meus dados (LGPD)" (US5.1, US5.2, US5.5);
- T6 lista e detalhe de pedidos no administrador (US5.3, US5.4, US5.6, US5.7, US5.9);
- T7 tela de novo aceite (US4.1, US4.2);
- T8 diálogo de anonimização (US6.1).

As histórias sem tela (US1.3, US1.4, US3.x, US6.2, US6.3) ficam no comportamento da API (recomendado)

B) Juntar T6 e T8 numa tela só
X) Other (please specify)

[Answer]: A

## Q2. Que padrões de interação usar?

A) Seis padrões:
- novo aceite em tela inteira (T7), que substitui o conteúdo até o aceite;
- formulário de pedido na própria Conta (inline), sem modal;
- verificação por assinatura num painel dentro do detalhe do pedido (T6): mostra a mensagem com o botão "copiar", um campo para colar a assinatura e o prazo de 24 h;
- anonimização no `Dialog` existente, com o campo onde se digita `ANONIMIZAR`;
- resumo de Acesso em painel expansível ("Ver resumo") no detalhe, antes de liberar;
- páginas legais com índice de âncoras (revelação progressiva por seção).

(recomendado)

B) Tudo em modais
X) Other (please specify)

[Answer]: A

## Q3. Que estados cada tela trata?

A) Cinco estados em todas as telas com dados:
- carregando (`.skeleton-row`);
- vazio ("Nenhum pedido ainda", com o que fazer);
- erro (`.notice-error` com "tentar de novo");
- sucesso (`.notice` com confirmação);
- parcial (por exemplo, a política com "a definir" nos campos de implantação, marcada com `.notice-warning`).

Botões ficam desabilitados com motivo em texto durante o envio (recomendado)

B) Só carregando e erro
X) Other (please specify)

[Answer]: A

## Q4. Como alinhar ao que já existe nos apps?

A) Reaproveitar `AppShell`, `Dialog`, `.notice*`, `.field-error`, `.tag` (para situação do pedido, sempre com texto), `.skip-link` e `.muted`. Criar só três peças novas, com as mesmas cores e espaçamentos:
- `LegalDocument` (texto com largura de leitura de até 72 caracteres por linha, índice e títulos);
- `SiteFooter` (nos dois apps, dentro do `AppShell` e nas páginas públicas);
- `RequestStatusTag`.

Nenhuma biblioteca nova (recomendado)

B) Criar um conjunto de componentes novo para as telas legais
X) Other (please specify)

[Answer]: A

## Q5. Que nível de acessibilidade?

A) O básico do beta em todas as telas: teclado, foco visível, rótulos visíveis, contraste legível no tema escuro, cor sempre com texto ou ícone, títulos em ordem, regiões e "pular para o conteúdo". Nas páginas legais T1 e T2, que são públicas, mirar o contraste e a estrutura do WCAG 2.1 AA desde já (recomendado)
B) Só o básico do beta, inclusive nas páginas legais
X) Other (please specify)

[Answer]: A

## Q6. Quais tamanhos de tela?

A) As páginas públicas T1 e T2 e o rodapé se adaptam de 320 px em diante, porque alguém pode abrir o link no celular. As demais telas (convite, Conta, novo aceite, administrador) seguem o resto do app: feitas para 1280 px ou mais, com o aviso "só para computador" abaixo disso, continuando usáveis (recomendado)
B) Todas as telas novas adaptáveis ao celular
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Telas: T1 `/termos`, T2 `/privacidade`, T3 rodapé, T4 convite, T5 Conta com "Meus dados (LGPD)", T6 pedidos no administrador, T7 novo aceite, T8 diálogo de anonimização; histórias sem tela ficam na API.
- Interação: novo aceite em tela inteira; pedido inline na Conta; painel de verificação no detalhe (mensagem com copiar, campo da assinatura, prazo de 24 h); anonimização no `Dialog` com `ANONIMIZAR`; resumo de Acesso em painel expansível; páginas legais com índice.
- Estados: carregando, vazio, erro com tentar de novo, sucesso e parcial; botões desabilitados com motivo durante o envio.
- Componentes: reaproveitar `AppShell`, `Dialog`, `.notice*`, `.field-error`, `.tag`, `.skip-link`, `.muted`; novos só `LegalDocument`, `SiteFooter` e `RequestStatusTag`; nenhuma biblioteca nova.
- Acessibilidade: básico do beta em todas; contraste e estrutura WCAG 2.1 AA nas páginas legais.
- Tamanhos: T1, T2 e rodapé de 320 px em diante; demais telas para 1280 px ou mais com o aviso "só para computador".

- Looks correct
- Request changes

[Answer]: Looks correct
