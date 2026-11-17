# Perguntas de Functional Design — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Base:
- `inception/units-generation/unit-of-work.md` (U6) e `unit-of-work-story-map.md` (US1.1, US3.1, US3.2 e US4.1 nas telas; US2.1 com o campo do teto);
- `inception/refined-mockups/` (R1 a R6, A1, `interaction-spec.md`, `accessibility-checklist.md`, `design-system-mapping.md`);
- o contrato atual (`apps/api/openapi/openapi-guest.yaml` e `openapi-admin.yaml`), com as rotas REAL da U1 à U5;
- o código atual:
  - `apps/web/src/app/real/carteira/BotWalletScreen.tsx`, a tela mínima da U1;
  - `apps/web/src/features/reacceptance/`, o novo aceite genérico do intent `261109-termos-lgpd`;
  - `apps/web/src/features/auth/wallets.ts` (`WalletPort` com `connect` e `signMessage`);
  - `apps/admin/src/app/` (a página `kill-switch`, que é a do PAPER);
- `project.md`: acessibilidade básica do beta, cor sempre com texto ou ícone, o navegador nunca monta nem envia transação.

## Q1. [Estrutura] Como organizar as telas do REAL no app do convidado?

A. Uma área "Carteira do bot" no menu, com o selo `REAL`, e cinco rotas:
- `/real`: visão geral (R2);
- `/real/ativacao`: ativação em três passos (R1);
- `/real/modo`: modo de operação (R3);
- `/real/ordens`: ordens (R5);
- `/real/deposito-saque`: depósito e saque (R6).

`/real/carteira`, a tela mínima da U1, vira um redirecionamento para `/real`. A ativação aparece enquanto não há carteira ativa; nas outras rotas, quem não tem carteira vai para ela.
B. Manter tudo numa tela só
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Termos v3] Como a tela de novo aceite e o passo 1 da ativação usam os termos v3?

A. O novo aceite forçado reusa o `features/reacceptance` que já existe (genérico por versão). Ele só ganha o caso da v3 nos textos e nos testes.

O passo 1 da ativação mostra o texto da versão vigente, com a caixa "Li e aceito os termos do REAL (posso perder todo o valor depositado)". A tela grava o aceite pela mesma rota de aceite dos termos e não cria rota nova.

Quem já aceitou a v3 pula o passo 1.
B. Uma tela de termos própria do REAL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Assinatura no navegador] Como a carteira do usuário assina a ordem no modo Assinada?

A. O `WalletPort` ganha `signTransaction(message: Uint8Array): Promise<Uint8Array>`, pelo Wallet Standard (`solana:signTransaction`). O fluxo é:
1. O diálogo lê `GET /api/v1/real/orders/{id}/signing-session`, que traz a mensagem montada pelo servidor.
2. A carteira assina só essa mensagem.
3. A tela devolve a assinatura pelo `POST` da mesma rota.

O navegador não monta, não altera e não envia transação. Também não guarda a mensagem depois de fechar o diálogo.

Carteira sem `signTransaction` mostra "Sua carteira não assina transações; use Phantom, Solflare ou Backpack" e não deixa subir para nenhum modo.
B. Assinar uma mensagem de texto em vez da transação
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Faixa e diálogo] Como a faixa fica sabendo que há ordem para assinar?

A. Pelo canal ao vivo do painel, que já existe (fetch em leitura contínua). A faixa ouve o evento `RealSigningSessionOpened`, publicado na U4, e na abertura de cada tela também lê a sessão aberta, para não perder um evento.
- **Contagem:** usa o prazo do servidor (`expiresAt`), não um relógio próprio.
- **Recusar:** chama `POST .../reject`.
- **Expirar:** mostra "A proposta expirou. Nada foi enviado." por 5 s.
- **Acessibilidade:** anúncios só nos marcos de 10 s e 5 s; foco e teclado como em `interaction-spec.md`.
B. Consultar a cada segundo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Modo] Como a escolha do modo funciona?

A. O seletor de modo (`ModeSelector`) mostra três cartões de rádio:
- **Assinada.**
- **Automática nas saídas:** desabilitada, com "Em breve", até a U7.
- **Automática total.**

Subir de modo abre o diálogo de confirmação (`MoneyConfirmDialog`), que pede a assinatura de consentimento (`POST /api/v1/real/consents/challenge`, depois `PUT /api/v1/real/mode`). Voltar para Assinada pede só a confirmação e vale na hora; as ordens não enviadas são canceladas, como a U4 já faz.

Uma recusa do servidor mostra o motivo em texto: sem liberação, pausa ou ordem ambígua (409).
B. Trocar de modo sem assinatura
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Teto] Onde e como o usuário define o teto (ponto aberto dos mockups)?

A. Na visão geral, um campo "Teto (SOL)" com até 9 casas decimais, mais o máximo de 10 SOL e o valor atual. A validação acontece na tela antes de enviar:
- o texto é convertido para lamports por um analisador decimal próprio (texto para `bigint`), nunca `Number` nem `parseFloat`;
- valor acima do máximo, negativo ou com mais de 9 casas é recusado.

A gravação vai por `PUT /api/v1/real/bot-wallet/preferences` com `Idempotency-Key`. As regras de subir e baixar o teto continuam no servidor (U3), e a tela mostra a recusa dele em texto. O teto baixado pelo administrador aparece com "Ajustado pelo administrador".
B. Teto só pelo administrador
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Ações com dinheiro] Como evitar envio duplo e confirmar ações com dinheiro?

A. O `MoneyConfirmDialog` confirma estas ações, nomeando valor, destino e efeito:
- ativar;
- subir de modo;
- sacar;
- vender tudo e sacar.

A `Idempotency-Key` é gerada uma vez quando o diálogo abre e é reutilizada nas novas tentativas do mesmo envio. O botão fica desabilitado enquanto envia. Uma falha mostra o motivo e "Tentar de novo".

O saque fica sempre visível e habilitado, inclusive com kill switch, pausa, REAL desligado, divergência (com o máximo devolvido pelo servidor) e sem o novo aceite.
B. Confirmar só o saque
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Visual do REAL] Como distinguir REAL de PAPER (cor do REAL, ponto aberto dos mockups)?

A. Um selo `REAL` com texto, ícone de moeda e cor de destaque âmbar do sistema de design, com contraste de pelo menos 4,5:1 nos temas claro e escuro. O PAPER mantém o selo atual.

O selo aparece no menu, no título de cada tela do REAL e em cada valor do REAL. No painel, o P&L REAL tem um cartão próprio, separado do P&L PAPER, e nunca é somado a ele.

A cor sempre vem acompanhada de texto ou ícone.
B. Só a cor
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Estados] Quais estados cada tela mostra?

A. Todas as telas mostram carregando, erro com "Tentar de novo" e vazio, e um campo ausente aparece como `unknown` ("sem preço", "sem dado"), nunca zero. Os casos específicos:
- **Pausa do REAL:** uma faixa com o motivo em texto para cada valor de `pausedReason`:
  - perda do dia;
  - divergência;
  - ordem em conferência;
  - fornecedor fora;
  - kill switch;
  - restauração;
  - conferência de restauração.
- **Liberação:** "em quarentena até <hora no seu fuso>" ou "bloqueada: <motivo>", lida de `GET /api/v1/real/bot-wallet`.
- **Ordens:** paginadas por cursor, com o selo de estado (`OrderStateBadge`), sempre com texto e ícone, e o link da transação no explorador da rede. Na rede local não há link.
- **Datas:** no fuso do usuário.
B. Só carregando e erro
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Administrador] Como fica a área REAL no app do administrador (A1)?

A. Uma seção `/real` no app administrativo, com as rotas R4 da U5:
- **Usuários:** lista paginada com modo, teto, saldo, liberação e divergência, e a carteira principal abreviada.
- **Detalhe do usuário:** liberar, desligar o REAL e baixar o teto.
- **Tokens bloqueados.**
- **Kill switch REAL:** separado e com rótulo próprio, para não confundir com a página `kill-switch`, que é a do PAPER.
- **Divergências:** lista e formulário de compensação por ativo, com a medida mostrada.
- **Restauração:** confirmação.

Toda ação pede motivo (1 a 500) e confirmação, gera `Idempotency-Key` e mostra a recusa do servidor em texto (`FOUNDER_FIRST`, `PILOT_FULL`, `CAP_ONLY_LOWERED`, `COMPENSATION_MISMATCH`, `RESTORE_NOT_CLEAN`).
B. Só a lista de usuários
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Área "Carteira do bot" com cinco rotas (`/real`, ativação, modo, ordens, depósito e saque); a tela mínima da U1 redireciona [Q1].
- O novo aceite reusa o recurso que já existe; o passo 1 da ativação grava o aceite pela rota de termos [Q2].
- A carteira assina só a mensagem montada pelo servidor (`signTransaction`); o navegador nunca monta nem envia [Q3].
- A faixa ouve o canal ao vivo, usa o prazo do servidor e anuncia só nos marcos [Q4].
- Seletor de modo em três cartões; subir pede consentimento assinado; voltar vale na hora [Q5].
- Campo do teto em SOL convertido para lamports sem ponto flutuante; as regras ficam no servidor [Q6].
- Confirmação com valor e destino, uma `Idempotency-Key` por diálogo e o saque sempre disponível [Q7].
- Selo `REAL` âmbar com texto e ícone, e o P&L REAL num cartão próprio [Q8].
- Estados completos, com pausa por motivo, liberação, `unknown`, ordens com selo e link, e o fuso do usuário [Q9].
- Seção REAL no administrador: usuários, ações, tokens, kill switch REAL próprio, divergências e restauração [Q10].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
