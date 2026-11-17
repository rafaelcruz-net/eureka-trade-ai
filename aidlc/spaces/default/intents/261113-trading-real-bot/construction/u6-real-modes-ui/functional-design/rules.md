# Regras de tela — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes:
- `functional-design-questions.md` (Q1 a Q10);
- `inception/user-stories/stories.md` (US1.1, US2.1, US3.1, US3.2, US4.1, com as telas de US4.2, US5.1, US5.2 e US7.1 a US8.2);
- `inception/refined-mockups/` (R1 a R6, A1, `interaction-spec.md`);
- `project.md` (Forbidden e Mandated).

A U6 não cria regra de negócio. Toda decisão sobre dinheiro, limite, modo, liberação e pausa continua no servidor (U1 a U5). As regras abaixo dizem o que a tela mostra, o que ela recusa antes de enviar e como ela se comporta.

## Mudança de contrato (adendo da U6)

São mudanças só de leitura e de transporte. Nenhuma regra de negócio muda, e o comportamento das rotas que já existem continua igual (ajustado na iteração 1 da revisão).

1. **Rota nova `GET /api/v1/real/release`:**
   - devolve `network` e `release`, com `state` (`NONE`, `QUARANTINE`, `ACTIVE`, `BLOCKED`, `REVOKED`), `effectiveAt` e `blockedReason`;
   - a leitura é pela M1 da U5, com o tenant do contexto de autenticação;
   - com o REAL desligado, devolve `403 REAL_DISABLED`;
   - é a leitura que a ativação usa;
   - o `GET /api/v1/real/bot-wallet` continua devolvendo `404 NOT_FOUND` sem carteira, como na U1.
2. **`BotWalletView` ganha dois campos só de leitura:** `capSetBy` (`USER` ou `ADMIN`) e `release` (o mesmo objeto da rota nova).
3. **`RealSigningSession` ganha `serverTime`** (instante UTC do servidor ao montar a resposta). A tela usa esse campo para descontar a diferença de relógio; o cabeçalho `Date` não é exposto pelo CORS.
4. **O CORS do convidado (`GuestCorsPolicy`) passa a aceitar `PATCH`** nas rotas com token, porque as preferências usam `PATCH /api/v1/real/bot-wallet/preferences`.

## Regras

### BR1 — Navegação e acesso (Q1)

- **BR1.1:** a área "Carteira do bot" aparece no menu com o selo `REAL` só quando o REAL está ligado no servidor. A tela lê `GET /api/v1/real/release`. Com `403 REAL_DISABLED`, o item some do menu e as rotas `/real*` mostram "O REAL ainda não está disponível".
- **BR1.2:** sem carteira ativa (`GET /api/v1/real/bot-wallet` com `404`), `/real`, `/real/modo`, `/real/ordens` e `/real/deposito-saque` redirecionam para `/real/ativacao`. Com carteira ativa, `/real/ativacao` redireciona para `/real`.
- **BR1.3:** `/real/carteira`, a tela mínima da U1, redireciona para `/real`, para os links antigos continuarem valendo.
- **BR1.4:** a tela de depósito e saque (`/real/deposito-saque`) continua acessível sem o novo aceite dos termos, porque a leitura da carteira e as rotas de saque são as exceções do servidor.
  - O redirecionamento global para o novo aceite, feito a partir de `403 TERMS_REACCEPTANCE_REQUIRED`, deixa de fora essa tela e essas três chamadas: `GET /api/v1/real/bot-wallet`, `POST .../withdrawals` e `GET .../withdrawals/{id}`.
  - Nessa tela, as outras leituras que pedem o novo aceite (P&L, ordens) não aparecem, e uma faixa diz "Aceite os termos novos para voltar a operar; o saque continua disponível".
  - As demais rotas `/real*` seguem a regra do novo aceite que já existe.

### BR2 — Termos e ativação (Q2, US1.1, US1.2)

- **BR2.1:** o passo 1 da ativação mostra o texto da versão vigente dos termos. O botão "Continuar" fica desabilitado até a caixa ser marcada, com o motivo em texto.
- **BR2.2:** o aceite é gravado pela rota de aceite dos termos que já existe. Uma falha mostra o motivo e "Tentar de novo", e a ativação continua bloqueada (AC1.1.4).
- **BR2.3:** quem já aceitou a versão vigente começa no passo 2 (AC1.1.2).
- **BR2.4:** o passo 2 pede o desafio `ACTIVATE` (`POST /api/v1/real/consents/challenge`), assina com `signMessage` a mensagem devolvida, sem mudar nada, e ativa (`POST /api/v1/real/bot-wallet`, com `Idempotency-Key`). A tela diz em texto que essa assinatura não é a do login e não movimenta fundos.
- **BR2.5:** sem liberação efetiva (`GET /api/v1/real/release` com `state` diferente de `ACTIVE`, ou `403 REAL_NOT_RELEASED` na ativação), a ativação mostra o estado da liberação em vez dos passos:
  - "O REAL ainda não está disponível para sua conta";
  - "em quarentena até `<hora no fuso>`";
  - "bloqueada: `<motivo>`".

### BR3 — Assinatura de ordens (Q3, Q4, US3.2)

- **BR3.1:** o navegador só assina.
  - Ele recebe a transação sem assinatura já montada pelo servidor (`transactionBase64` de `GET .../signing-session`) e a passa sem mudança a `signTransaction`.
  - Ele devolve a transação assinada (`signedTransactionBase64` no `POST .../signing-session`). O servidor confere que a mensagem é a mesma (`messageDigest`) antes de enviar (U4).
  - O navegador não monta, não altera, não envia à rede e não guarda a transação depois de fechar o diálogo.
- **BR3.2:** a contagem usa o `deadline` do servidor. A diferença de relógio é `serverTime` (campo novo da sessão, adendo) menos o relógio do navegador na chegada da resposta, e é descontada. Sem `serverTime`, a contagem usa o relógio do navegador. Em qualquer caso, o servidor continua sendo quem decide a expiração.
- **BR3.3:** ao chegar a zero, ou com `409 SIGNING_DEADLINE_EXPIRED`, a faixa mostra "A proposta expirou. Nada foi enviado." por 5 s e some (AC3.2.3).
- **BR3.4:** recusar na tela chama `POST .../reject`. Recusar na carteira também chama `POST .../reject` e mostra "Você recusou. Nada foi enviado." (AC3.2.4).
  - Se o `reject` falhar, a tela mostra "Não foi possível registrar a recusa; a proposta expira sozinha e nada é enviado" e fecha o diálogo.
  - Nada é enviado de qualquer forma, porque sem assinatura o servidor não envia.
- **BR3.5:** a faixa aparece em todas as telas do app só no modo Assinada e só com sessão aberta.
  - **Evento:** ela ouve o evento `real.signing.requested` (dados `RealSigningSessionOpened`: `realOrderId` e `deadline`) do canal ao vivo. Hoje o `dashboard/envelopes.ts` descarta esse evento, e a U6 passa a tratá-lo.
  - **Releitura:** na carga do app e a cada troca de rota, a faixa lê `GET /api/v1/real/orders?state=QUOTED&limit=1` e, havendo ordem, `GET .../{id}/signing-session`.
  - **Sessão encerrada:** `404` ou `409` na sessão significa que a ordem já não espera assinatura, e a faixa some.
  - **Sem o novo aceite:** com o estado "novo aceite pendente", a faixa não relê ordens nem abre o canal ao vivo, porque essas leituras não são exceções do servidor. Um `403 TERMS_REACCEPTANCE_REQUIRED` vindo da própria faixa é ignorado e nunca dispara o redirecionamento global. Assim, a tela de depósito e saque continua aberta (BR1.4).
- **BR3.7:** recusas ao enviar a transação assinada, cada uma com seu texto e o diálogo fechado:
  - `422 SIGNED_MESSAGE_MISMATCH`: "A transação assinada não confere com a proposta. Nada foi enviado.";
  - `422 SIGNING_POLICY_REFUSED`: "A proposta foi recusada pela política de segurança. Nada foi enviado.";
  - `409` com a ordem fora de `QUOTED`: "Esta proposta já não espera assinatura.".
- **BR3.6:** carteira sem `solana:signTransaction` não abre o diálogo e mostra "Sua carteira não assina transações; use Phantom, Solflare ou Backpack".

### BR4 — Modo (Q5, US3.1)

- **BR4.1:** "Automática nas saídas" fica desabilitada, com "Em breve", enquanto o servidor não aceitar o modo (U7).
- **BR4.2:** subir de modo abre a confirmação. A tela segue três passos, com a chave de BR6.2 (AC3.1.3):
  1. pede o desafio `RAISE_MODE` (`POST /api/v1/real/consents/challenge`);
  2. assina com `signMessage`;
  3. envia `PUT /api/v1/real/mode` com o `challengeId` e a assinatura.
- **BR4.3:** voltar para Assinada pede só a confirmação, que diz "As ordens ainda não enviadas serão canceladas". A troca vale na hora (AC3.1.2, AC3.1.4).
- **BR4.4:** a recusa do servidor aparece em texto: sem liberação, pausa com o motivo, ou `409` com ordem em conferência ("Espere a ordem em conferência terminar").

### BR5 — Teto (Q6, US2.1)

- **BR5.1:** o campo aceita só dígitos e uma vírgula ou ponto, com até 9 casas decimais. Um analisador decimal próprio converte o texto para lamports em `bigint`; `Number`, `parseFloat` e operações em ponto flutuante são proibidos para valores em SOL.
- **BR5.2:** antes de enviar, a tela recusa valor vazio, negativo, acima de 10 SOL ou com mais de 9 casas, e explica o máximo (AC2.1.2).
- **BR5.2b:** baixar o teto vai direto por `PATCH /api/v1/real/bot-wallet/preferences`. Subir o teto pede antes o desafio `RAISE_CAP`, com o novo valor, e a assinatura, como no modo (BR4.2). A tela diz qual dos dois caminhos vai usar antes de confirmar.
- **BR5.3:** as outras regras (exposição, teto abaixo da exposição, teto do administrador) ficam no servidor. A tela mostra a resposta em texto e, depois de salvar, a explicação "Com o teto abaixo da exposição, novas compras ficam bloqueadas; as vendas continuam" quando ela couber (AC2.1.4).
- **BR5.4:** um teto baixado pelo administrador aparece com "Ajustado pelo administrador".

### BR6 — Ações com dinheiro (Q7, US5.1, US5.2)

- **BR6.1:** ativar, subir de modo, sacar e vender tudo e sacar passam pelo diálogo de confirmação, que nomeia o valor, o destino e o efeito.
- **BR6.2:** a `Idempotency-Key` vale para um corpo exato.
  - Ela é criada quando a tela monta o corpo do envio e é reutilizada só no reenvio desse mesmo corpo (falha de rede, tempo esgotado, `5xx`).
  - Quando o corpo muda, por exemplo com um desafio novo depois de `422 CONSENT_EXPIRED` ou `CONSENT_INVALID`, outra assinatura ou outro valor, a tela cria uma chave nova.
  - O servidor responde `409 IDEMPOTENCY_CONFLICT` a uma chave reutilizada com corpo diferente. A tela nunca provoca isso; se acontecer, mostra o erro e cria uma chave nova na próxima ação.
  - Fechar o diálogo descarta a chave.
- **BR6.3:** enquanto o envio está em andamento, o botão fica desabilitado; um segundo clique não envia nada.
- **BR6.4:** o saque fica sempre visível e habilitado: com kill switch, pausa, REAL desligado, sem liberação, com divergência e sem o novo aceite. Com divergência ou restauração, a tela mostra o máximo devolvido pelo servidor ("Disponível para saque agora").
- **BR6.5:** o destino do saque é sempre a carteira principal registrada, mostrada abreviada e sem campo de edição.

### BR7 — Visual do REAL (Q8)

- **BR7.1:** o selo `REAL` tem texto, ícone e a cor âmbar do sistema de design, com contraste de pelo menos 4,5:1 nos dois temas. A cor nunca aparece sozinha.
- **BR7.2:** valores REAL e PAPER nunca aparecem somados nem na mesma linha sem o selo. No painel, o P&L REAL tem um cartão próprio.

### BR8 — Estados e dados (Q9, US4.1)

- **BR8.1:** carregando, vazio e erro com "Tentar de novo" são estados distintos e em texto (AC4.1.3).
- **BR8.2:** um campo de mercado ausente aparece como "sem preço" ou "sem dado", nunca como zero. Uma posição sem preço mostra o custo de compra com o rótulo "sem preço".
- **BR8.3:** cada valor de `pausedReason` tem um texto próprio, e um valor desconhecido mostra "REAL pausado".
- **BR8.4:** o selo de estado da ordem sempre traz texto e ícone. Uma ordem ambígua aparece como "Em conferência", com o valor reservado (AC4.1.2).
- **BR8.8:** os códigos de erro do REAL têm texto próprio; um código sem texto mostra o `detail` do ProblemDetail.

  | Código | Texto |
  |---|---|
  | `REAL_DISABLED` | "O REAL ainda não está disponível" |
  | `REAL_NOT_RELEASED` | "O REAL ainda não está disponível para sua conta" |
  | `REAL_PAUSED` | "REAL pausado: `<motivo>`" |
  | `ORDER_AMBIGUOUS` | "Espere a ordem em conferência terminar" |
  | `ORDER_ALREADY_IN_PROGRESS` | "Já há uma venda total em andamento" |
  | `CONSENT_EXPIRED`, `CONSENT_INVALID` | "A assinatura de consentimento expirou ou não confere; assine de novo" |
  | `CAP_ABOVE_MAXIMUM` | "O teto máximo é de `<máximo>` SOL" |
  | `INSUFFICIENT_FREE_BALANCE` | "Saldo livre insuficiente" |
  | `WITHDRAWAL_LIMITED_BY_DIVERGENCE` | "Agora o saque está limitado a `<máximo>` SOL enquanto a conferência com a rede termina" |
  | `BOT_WALLET_ALREADY_ACTIVE` | "Sua carteira do bot já está ativa" |
  | `TERMS_V3_MISSING` | "Aceite os termos do REAL para continuar" |
  | `SIGNING_DEADLINE_EXPIRED`, `SIGNED_MESSAGE_MISMATCH`, `SIGNING_POLICY_REFUSED` | ver BR3.3 e BR3.7 |
  | `IDEMPOTENCY_CONFLICT` | "Este pedido já foi enviado com outros dados; tente de novo" |
- **BR8.5:** o link da transação usa o explorador da rede configurada: `https://solscan.io/tx/<assinatura>` na mainnet e `...?cluster=devnet` na devnet. Na rede local não há link, só a assinatura abreviada.
- **BR8.6:** datas e horas aparecem no fuso do navegador; a API trafega UTC.
- **BR8.7:** valores em SOL aparecem com 9 casas e os de token com as casas do mint, formatados a partir de `bigint` no padrão pt-BR.

### BR9 — Administrador (Q10, US7.1 a US8.2)

- **BR9.1:** a seção `/real` do app administrativo usa só as rotas R4 da U5.
- **BR9.2:** toda ação pede um motivo de 1 a 500 caracteres e uma confirmação. A `Idempotency-Key` segue a mesma regra de BR6.2.
- **BR9.3:** as recusas do servidor aparecem em texto: `FOUNDER_FIRST`, `PILOT_FULL`, `CAP_ONLY_LOWERED`, `COMPENSATION_MISMATCH`, `RESTORE_NOT_CLEAN`, e as outras pelo `detail` do ProblemDetail.
- **BR9.4:** o kill switch REAL tem uma tela própria com o título "Kill switch REAL", separada da página `kill-switch`, que é a do PAPER. Ligado, a tela diz "Novas ordens REAL bloqueadas para todos; saques liberados".
- **BR9.5:** a carteira principal aparece abreviada, e nenhum campo de chave existe na tela.
- **BR9.6:** o formulário de compensação mostra a medida da última reconciliação por ativo e preenche os valores com ela. A pessoa confirma e não digita os valores.

## Resumo

- 9 grupos e 40 regras (com BR3.7, BR5.2b e BR8.8 da revisão). Nenhuma regra de negócio nova: a tela mostra, valida a forma antes de enviar e protege contra envio duplo.
- As regras de segurança do `project.md` valem nas telas:
  - o navegador nunca monta nem envia transação;
  - não há ponto flutuante em dinheiro;
  - a cor sempre vem com texto ou ícone;
  - o saque está sempre disponível;
  - nenhum segredo vai para variável `NEXT_PUBLIC_*`.
