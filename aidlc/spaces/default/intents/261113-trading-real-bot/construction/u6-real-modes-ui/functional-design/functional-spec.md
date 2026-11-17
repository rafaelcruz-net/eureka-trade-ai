# Especificação funcional — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes:
- `rules.md` (BR1 a BR9) e `entities.md`;
- `inception/refined-mockups/` (R1 a R6, A1, `interaction-spec.md`, `accessibility-checklist.md`);
- `inception/user-stories/stories.md`;
- `functional-design-questions.md` (Q1 a Q10).

## Escopo

A U6 entrega as telas do REAL:
- no app do convidado (`apps/web`): ativação, visão geral com o teto, modo, faixa e diálogo de assinatura, ordens, depósito e saque, o cartão do P&L REAL no painel e o caso da v3 no novo aceite;
- no app do administrador (`apps/admin`): a seção REAL.

Ela também fecha os pontos de tela deixados em aberto nos mockups: o campo do teto, os estados, as confirmações e a cor do REAL.

No servidor, a U6 só faz as mudanças de leitura e transporte do adendo de `rules.md`:
- a rota `GET /api/v1/real/release`;
- os campos `release` e `capSetBy` em `BotWalletView`;
- `serverTime` na sessão de assinatura;
- `PATCH` no CORS do convidado.

## Telas e componentes

| Tela ou componente | Rota | Histórias | Regras |
|---|---|---|---|
| Menu com "Carteira do bot" e o selo `REAL` | layout do app | — | BR1.1, BR7.1 |
| R1 Ativação | `/real/ativacao` | US1.1, US1.2 | BR1.2, BR2.1 a BR2.5, BR6 |
| R2 Visão geral, com o campo do teto | `/real` | US2.1, US2.2, US4.2, US8.2 | BR5, BR7.2, BR8.2, BR8.3 |
| R3 Modo | `/real/modo` | US3.1 | BR4 |
| R4 `SigningBanner` e `SigningDialog` | layout do app | US3.2 | BR3 |
| R5 Ordens | `/real/ordens` | US4.1, US8.3 | BR8.4 a BR8.7 |
| R6 Depósito e saque | `/real/deposito-saque` | US2.2, US5.1, US5.2 | BR6 |
| Cartão do P&L REAL | painel | US4.2 | BR7.2, BR8.2 |
| Novo aceite da v3 | o recurso que já existe | US1.1 | BR1.4, BR2.2 |
| A1 REAL no administrador | `apps/admin` `/real`, `/real/usuarios/[id]`, `/real/tokens`, `/real/kill-switch`, `/real/divergencias`, `/real/restauracao` | US6.2, US7.1 a US7.3, US8.1, US8.2 | BR9 |
| `MoneyConfirmDialog`, `ModeSelector`, `OrderStateBadge`, `RealBadge`, `SolAmountInput` | componentes | — | BR5.1, BR6, BR7, BR8.4 |

## Fluxos

### F1 — Ativar (R1)

1. A tela lê `GET /api/v1/real/release`. Sem liberação efetiva, mostra o estado da liberação e para (BR2.5).
2. Se a pessoa ainda não aceitou a versão vigente dos termos, vem o passo 1, com o texto e a caixa. Ao confirmar, a tela grava o aceite (BR2.1, BR2.2).
3. No passo 2, a pessoa define o teto inicial (padrão 1 SOL, máximo 10).
4. A tela pede o desafio `ACTIVATE`, a carteira assina com `signMessage` e a tela ativa com `Idempotency-Key` (BR2.4, BR6.2).
5. O passo 3 mostra o endereço com "Copiar" e o link "Ir para Depósito e saque".

### F2 — Assinar uma ordem (R4)

1. A faixa aparece com o evento `real.signing.requested`, ou pela releitura (`GET /orders?state=QUOTED&limit=1` e depois a sessão) na carga e a cada troca de rota (BR3.5).
2. "Ver" abre o diálogo, que lê a sessão e mostra o lado, a entrada, a saída esperada e a mínima, a taxa, o motivo e o prazo.
3. "Assinar na carteira" chama `signTransaction(transactionBase64)` e envia `signedTransactionBase64` (BR3.1).
4. Os resultados possíveis:
   - aceita: o diálogo fecha e a ordem segue em R5;
   - prazo esgotado: "A proposta expirou. Nada foi enviado." (BR3.3);
   - `409 SIGNING_DEADLINE_EXPIRED`: o mesmo texto da expiração;
   - `422 SIGNED_MESSAGE_MISMATCH`, `422 SIGNING_POLICY_REFUSED` ou ordem fora de `QUOTED`: o texto de cada caso (BR3.7);
   - recusa na carteira: `POST .../reject` (BR3.4).

### F3 — Trocar de modo (R3)

- **Subir:** confirmação, depois o desafio `RAISE_MODE`, a assinatura e o `PUT /mode` (BR4.2).
- **Voltar para Assinada:** confirmação com o aviso de cancelamento e o `PUT /mode` (BR4.3).

### F4 — Definir o teto (R2)

1. A tela analisa o texto para lamports (`bigint`) e valida o máximo e as casas (BR5.1, BR5.2).
2. Baixar vai direto. Subir pede o desafio `RAISE_CAP` e a assinatura (BR5.2b).
3. A tela mostra a resposta e a explicação da exposição (BR5.3).

### F5 — Sacar e vender tudo (R6)

1. **Sacar:** confirmação com o valor e a carteira principal abreviada, depois `POST withdrawals` com `Idempotency-Key` e o acompanhamento do estado até o fim (BR6).
2. **Vender tudo e sacar:** confirmação, depois `POST liquidations` e o acompanhamento até `DONE` ou `PARTIAL`. Com `PARTIAL`, a tela lista as posições que ficaram e diz que o saque liberou o que já estava livre (AC5.2.2).
3. **Ordem em andamento:** o valor reservado aparece como "Reservado em ordens" e fica fora do disponível para saque (AC5.1.3).

### F6 — Administrador (A1)

1. **Lista e detalhe:** cada ação abre um diálogo com motivo e confirmação, depois a rota R4 com `Idempotency-Key`, e então a lista é recarregada (BR9.2).
2. **Divergências:** o formulário vem preenchido com a medida, a pessoa confirma e a compensação é enviada (BR9.6).
3. **Kill switch REAL:** ligar ou desligar com motivo, e o texto do efeito (BR9.4).
4. **Restauração:** confirmar com motivo; com `409 RESTORE_NOT_CLEAN`, a tela mostra quais carteiras ainda não estão limpas.

## Cenários

| ID | Dado | Quando | Então |
|---|---|---|---|
| S1 | REAL desligado no servidor | abrir o app | sem o item no menu; `/real` mostra "O REAL ainda não está disponível" |
| S2 | sem liberação | abrir `/real/ativacao` | "O REAL ainda não está disponível para sua conta" |
| S3 | liberação em quarentena | abrir a ativação | "em quarentena até <hora no fuso>" |
| S4 | sem o aceite da v3 | abrir a ativação | passo 1; "Continuar" desabilitado até marcar (AC1.1.1) |
| S5 | aceite falha | confirmar | motivo e "Tentar de novo"; a ativação continua bloqueada (AC1.1.4) |
| S6 | aceite da v3 feito | abrir a ativação | começa no passo 2 (AC1.1.2) |
| S7 | ativação aceita | concluir | passo 3 com o endereço e "Copiar" |
| S8 | teto "12" | salvar | recusado na tela com a explicação do máximo de 10 SOL (AC2.1.2) |
| S9 | teto "3,5" | salvar | envia 3500000000 lamports, sem ponto flutuante |
| S10 | teto "0,0000000001" (10 casas) | salvar | recusado na tela |
| S11 | teto maior que o atual | salvar | pede o consentimento `RAISE_CAP` antes |
| S12 | modo Assinada e sessão aberta | qualquer tela | a faixa aparece com a contagem |
| S13 | diálogo aberto | assinar a tempo | o navegador envia só a transação assinada recebida, sem mudança |
| S14 | prazo esgotado | contagem chega a zero | "A proposta expirou. Nada foi enviado." por 5 s (AC3.2.3) |
| S15 | recusa na carteira | assinar | `reject` chamado; "Você recusou. Nada foi enviado." (AC3.2.4) |
| S16 | carteira sem `signTransaction` | abrir o diálogo | aviso das carteiras compatíveis; nada é assinado |
| S17 | modo Assinada | subir para Automática total | confirmação, assinatura de consentimento e troca (AC3.1.3) |
| S18 | Automática total | voltar para Assinada | confirmação com o aviso de cancelamento; vale na hora (AC3.1.2) |
| S19 | ordem em conferência | trocar de modo | `409` mostrado como "Espere a ordem em conferência terminar" |
| S20 | "Automática nas saídas" | abrir o modo | desabilitada com "Em breve" |
| S21 | kill switch ligado | abrir depósito e saque | faixa "kill switch" e o saque habilitado |
| S22 | divergência aberta | abrir depósito e saque | "Disponível para saque agora", com o máximo do servidor |
| S23 | sem o novo aceite | abrir `/real/deposito-saque` | o saque abre; as outras rotas levam ao novo aceite |
| S24 | dois cliques em "Sacar" | confirmar | um só pedido, com a mesma `Idempotency-Key` |
| S25 | falha de rede no saque | "Tentar de novo" | reenvia com a mesma `Idempotency-Key` |
| S26 | lista de ordens vazia | abrir ordens | "Nenhuma ordem real ainda" |
| S27 | ordem ambígua | abrir ordens | "Em conferência" com ícone e o valor reservado (AC4.1.2) |
| S28 | rede local | abrir ordens | sem link; assinatura abreviada |
| S29 | mainnet | abrir ordens | link do Solscan sem `cluster`; na devnet, com `cluster=devnet` |
| S30 | posição sem preço | abrir a visão geral | custo de compra com "sem preço", nunca zero |
| S31 | painel com PAPER e REAL | abrir o painel | dois cartões de P&L separados; o REAL com o selo |
| S32 | pausa `RESTORE` | abrir a visão geral | faixa com o texto da restauração |
| S33 | administrador libera o 21º convidado | confirmar | "PILOT_FULL" em texto: "Limite de 20 convidados atingido" |
| S34 | administrador compensa | abrir uma divergência | valores preenchidos com a medida; confirmação |
| S35 | administrador liga o kill switch REAL | confirmar com motivo | "Novas ordens REAL bloqueadas para todos; saques liberados" |
| S36 | teto baixado pelo administrador | abrir a visão geral | "Ajustado pelo administrador" |
| S37 | 320 px de largura | qualquer tela do REAL | sem rolagem lateral; as ordens viram cartões |
| S38 | transação assinada diferente da proposta | enviar | `422 SIGNED_MESSAGE_MISMATCH`: "A transação assinada não confere com a proposta. Nada foi enviado." |
| S39 | política do TxSigner recusa | enviar | `422 SIGNING_POLICY_REFUSED` com o texto; diálogo fechado |
| S40 | consentimento expirou ao subir de modo | confirmar | `422 CONSENT_EXPIRED`; novo desafio, nova assinatura e **nova** `Idempotency-Key` |
| S41 | `reject` falha | recusar | "Não foi possível registrar a recusa; a proposta expira sozinha e nada é enviado" |
| S42 | sem o novo aceite | abrir depósito e saque | saldo e saque disponíveis; P&L e ordens ocultos; faixa para aceitar os termos novos; a faixa de assinatura não relê ordens nem abre o canal, e nenhum `403` desvia para o novo aceite |
| S43 | sem carteira ativa | abrir `/real` | `GET /bot-wallet` com `404`; redireciona para a ativação, que lê `/real/release` |
| S44 | venda total parcial | acompanhar | `PARTIAL`: lista das posições que ficaram e o saque do que estava livre (AC5.2.2) |

## Erros e casos de canto

- **Sessão do login expirada:** a renovação que já existe roda e a ação é repetida uma vez com a mesma `Idempotency-Key`.
- **`403 TERMS_REACCEPTANCE_REQUIRED`:** leva ao novo aceite, exceto no saque (BR1.4).
- **Evento do canal perdido:** a releitura da sessão na troca de rota cobre (BR3.5).
- **`pausedReason` desconhecido:** "REAL pausado" (BR8.3).
- **Erro desconhecido do servidor:** mostra o `detail` do ProblemDetail e o `correlationId` para suporte, sem stack trace.

## Acessibilidade

Segue `inception/refined-mockups/accessibility-checklist.md` e `interaction-spec.md`:
- teclado em todas as ações;
- foco visível;
- rótulos visíveis;
- diálogos com foco preso e Esc;
- `aria-live` só nos marcos da contagem;
- contraste de pelo menos 4,5:1;
- cor sempre com texto ou ícone.
