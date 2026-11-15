# Regras de negócio — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes:
- `inception/requirements-analysis/requirements.md` (FR3.4, FR4, FR5, FR7.1, FR7.4, NFR3, NFR4);
- `inception/user-stories/stories.md` (US3.1 servidor, US3.2, US3.3, US4.1, US4.2, US5.2, US8.3);
- `inception/contract-design/contract-summary.md` (R1, R3, R6, M1 a M6, adendos da U1 e da U3);
- `inception/domain-design/decisions.md` (ADR-004, ADR-008);
- `functional-design-questions.md` (Q1 a Q15).

## Regras

```yaml
rules:
  - id: BR1.1
    statement: O mercado do bot é escolhido na criação e nunca muda
    category: validation
    applies_to: Bot
    trigger: criação e edição do bot
    logic: >
      SE market = REAL E o usuário não tiver carteira do bot ativa (M1 readRealState) ENTÃO recusa a criação. SE uma
      edição tentar mudar market ENTÃO recusa. Bots existentes ficam PAPER
    violation: 422 VALIDATION_FAILED com o campo market
    source: Q1, FR4.1
  - id: BR1.2
    statement: O bot REAL passa pela barreira e grava a decisão no ciclo
    category: policy
    applies_to: ciclo do bot REAL
    trigger: decisão de compra ou venda do bot REAL
    logic: >
      SE o bot tem ordem em andamento (BR2.2) ENTÃO descarta a decisão com ORDER_IN_PROGRESS. SENÃO confere a posse do
      bot pelo tenant e pelo usuário, chama a M5 evaluate na transação do ciclo e, se aprovada, grava a
      RealDecisionRequest pela M6 recordDecision (propagação MANDATORY). Se a barreira recusar, nada é gravado na U4.
      Se a M5 lançar RealGateUnavailable com transactionUsable = false, o ciclo desfaz a transação. O bot REAL nunca
      escreve no ledger PAPER
    violation: decisão descartada ou recusada, com o motivo no registro do ciclo
    source: Q1, Q4, FR5.1, AC3.3.3, adendo da U3
  - id: BR2.1
    statement: O consumidor transforma a decisão em ordem numa transação curta
    category: policy
    applies_to: RealDecisionRequest
    trigger: consumidor da fila no worker, a cada 200 ms
    logic: >
      Pega decisões PENDING com FOR UPDATE SKIP LOCKED. Numa transação: confere de novo a posse do bot e se o bot tem
      ordem em andamento (M6) ou se há liquidação ativa do usuário; na compra chama M5 handOver(approvalId) e, depois,
      grava o RESERVE na M2 com a chave da ordem (a mesma ordem da BR6.2 da U3); cria a RealOrder PROPOSED com o modo
      atual da carteira; marca a decisão PROCESSED. SE handOver devolver RESERVATION_EXPIRED, houver ordem em andamento
      ou liquidação ativa ENTÃO marca a decisão DISCARDED sem criar ordem; a reserva OPEN da barreira vence sozinha em
      5 min e conta no teto até lá, o que é conservador. Se o índice único for violado mesmo assim, a transação é
      desfeita e a decisão é marcada DISCARDED numa transação nova. Nenhuma chamada de rede nessa transação
    violation: decisão DISCARDED com o motivo
    source: Q3, FR5.1
  - id: BR2.2
    statement: No máximo uma ordem REAL por bot em andamento
    category: constraint
    applies_to: RealOrder
    trigger: ciclo do bot e criação da ordem
    logic: >
      Em andamento = PROPOSED, QUOTED, SIGNED, SENT ou AMBIGUOUS. O ciclo consulta a M6 antes da barreira e descarta a
      decisão nova. Um índice único parcial (botId) nesses estados garante a regra mesmo com corrida; a violação do índice
      no consumidor desfaz a transação (nenhum RESERVE foi gravado) e descarta a decisão com ORDER_IN_PROGRESS (BR2.1).
      Também há no máximo uma venda em andamento por usuário e mint (índice único parcial), para a venda do bot e a da
      liquidação nunca disputarem o mesmo token
    violation: ORDER_IN_PROGRESS
    source: AC3.3.3
  - id: BR3.1
    statement: Uma ordem gera no máximo uma transação na rede
    category: constraint
    applies_to: RealOrder
    trigger: assinatura e envio
    logic: >
      A idempotencyKey é única. Depois de SIGNED, a txSignature (calculada da transação assinada) e a transação assinada
      são gravadas antes do primeiro envio. Todo reenvio, inclusive depois de reinício, manda a mesma transação. A ordem
      nunca é assinada de novo; com o blockhash vencido e sem a transação na rede, ela vira FAILED (BLOCKHASH_EXPIRED) e
      só uma decisão nova gera outra ordem
    violation: não se aplica
    source: FR5.5, AC3.3.4, NFR4
  - id: BR3.2
    statement: Cotação de no máximo 10 s e derrapagem do usuário
    category: validation
    applies_to: RealOrder
    trigger: PROPOSED → QUOTED e antes de assinar
    logic: >
      Cota na Jupiter com maxSlippageBps do usuário (200 padrão, até 500) e entrada = inputLamports na compra ou a
      quantidade na venda. Grava quoteAt, expectedOut e minOut = piso(expectedOut × (10000 − slippage) / 10000). Em
      Automática total: SE no momento de assinar agora − quoteAt > 10 s ENTÃO cota de novo uma vez; SE vencer de novo
      ENTÃO FAILED (QUOTE_EXPIRED) e RESERVE_RELEASE. No modo Assinada não há nova cotação depois que a sessão é
      publicada, porque ela invalidaria a assinatura do usuário (BR4.2). Na compra, a entrada cotada nunca passa da
      reserva. Toda transição de estado da ordem é um UPDATE condicional ao estado esperado (compare-and-set); quem perde
      a corrida não faz nada
    violation: FAILED QUOTE_EXPIRED
    source: FR5.2, FR5.3, AC3.3.2
  - id: BR3.3
    statement: Antes de assinar e antes de enviar, a carteira é reconferida
    category: authorization
    applies_to: RealOrder não enviada
    trigger: antes de SIGNED e antes de SENT
    logic: >
      Chama M1 recheckBeforeSend. SE o REAL estiver desligado, o modo atual não permitir a ordem (modo da ordem mais
      permissivo que o atual), houver pausa (exceto as que a liquidação passa, BR1.1 da U3) ou o kill switch estiver
      ativo ENTÃO a ordem vira CANCELLED com o motivo e RESERVE_RELEASE
    violation: CANCELLED com REAL_DISABLED, MODE_LOWERED, PAUSED ou KILL_SWITCH
    source: components.md (RealExecution), FR8.1
  - id: BR4.1
    statement: Modo Automática total assina pelo TxSigner
    category: policy
    applies_to: ordem em AUTO_FULL; no modo Automática nas saídas, as ordens a mercado do bot e da liquidação seguem a cossinatura da Assinada (BR4.2), e as saídas por stop e alvo são ordens condicionais da U7
    trigger: QUOTED
    logic: >
      Pede M1 signSwap com signingRequestId = realOrderId, a transação da Jupiter e o contexto (entrada, saída cotada,
      saída mínima, derrapagem, taxa). Assinada, grava txSignature e transação e vai para SIGNED. Recusa do TxSigner
      vira FAILED (SIGNING_REFUSED) com o motivo e RESERVE_RELEASE
    violation: FAILED SIGNING_REFUSED
    source: FR5.4, AC3.3.1
  - id: BR4.2
    statement: No modo Assinada, a carteira principal assina primeiro em até 8 s
    category: policy
    applies_to: ordem em SIGNED (modo Assinada)
    trigger: QUOTED
    logic: >
      Cota e, logo em seguida, monta a mensagem pelas instruções de swap da Jupiter (swap-instructions), com uma mensagem
      v0 montada pelo servidor: a carteira principal registrada é a pagadora da taxa e a carteira do bot é a dona dos
      fundos e a pagadora do aluguel das contas criadas. É uma premissa a confirmar na prova técnica da Jupiter (X1).
      Grava messageDigest e signingDeadline = quoteAt + 8 s e publica a sessão de assinatura no mesmo instante; assim a
      cotação tem no máximo 10 s no envio e o usuário tem quase os 8 s inteiros. Quando o navegador envia a transação
      assinada: SE passou o prazo ENTÃO 409 SIGNING_DEADLINE_EXPIRED; SE a mensagem difere da montada (inclusive uma
      carteira que altere a mensagem ao assinar) ENTÃO 422 SIGNED_MESSAGE_MISMATCH e a ordem continua QUOTED até o
      prazo; SENÃO marca a ordem como reivindicada (signingClaimedAt, compare-and-set com state = QUOTED e prazo não
      vencido) e pede ao TxSigner a segunda assinatura (BR4.1, modo SIGNED). Recusa na carteira (POST reject) vira
      CANCELLED USER_REJECTED. Um trabalho a cada 1 s cancela com SIGNING_DEADLINE_EXPIRED só as ordens QUOTED com prazo
      vencido e sem reivindicação; uma ordem reivindicada segue para SIGNED ou FAILED pelo resultado do TxSigner. A
      transação assinada pelo usuário é gravada com a reivindicação. Se a ordem reivindicada continuar QUOTED 15 s
      depois da reivindicação (queda do worker ou TxSigner sem resposta), o mesmo trabalho repete o pedido ao TxSigner
      com o mesmo signingRequestId e o mesmo conteúdo, que devolve a mesma assinatura se ela já existir; se a cotação
      já tiver mais de 10 s nessa hora, a ordem vira FAILED (QUOTE_EXPIRED) com RESERVE_RELEASE e nada é enviado. O
      prazo de 8 s fica dentro dos 10 s do AC3.2.2 e AC3.2.3
    violation: CANCELLED com SIGNING_DEADLINE_EXPIRED ou USER_REJECTED
    source: FR4.3, AC3.2.1 a AC3.2.4, ADR-008
  - id: BR4.3
    statement: O TxSigner só assina swap dentro da política de conteúdo
    category: authorization
    applies_to: TxSigner, operação SWAP
    trigger: POST /internal/v1/signatures com operationKind SWAP
    logic: >
      Recusa, com o motivo registrado: programa fora da lista (Jupiter v6, Token, Token-2022, Associated Token, Compute
      Budget; System só para embrulhar e desembrulhar SOL da própria carteira do bot); conjunto de assinantes diferente
      de exatamente {carteira do bot} no modo AUTO_FULL ou exatamente {carteira principal registrada como pagadora,
      carteira do bot} no modo SIGNED; no modo SIGNED, assinatura da carteira principal ausente ou inválida sobre a mesma
      mensagem, ou a carteira principal aparecendo em qualquer instrução (como autoridade, dona de conta, origem de
      transferência ou conta gravável) além de pagadora da taxa; taxa de prioridade acima do teto configurado (padrão
      0,001 SOL, pelo Compute Budget); aluguel de conta criada pago por quem não seja a carteira do bot; dono dos fundos
      que não seja a carteira do bot; conta de
      destino que não pertença à carteira do bot; minOut menor que piso(quotedOut × (10000 − slippage) / 10000);
      slippage acima de 500; troca de autoridade; fechamento de conta para quem não seja a carteira do bot; entrada
      acima do teto autorizado; modo autorizado que não permite o swap; mesmo signingRequestId com conteúdo diferente;
      mensagem com tabela de endereços (LOOKUP_TABLE_NOT_ALLOWED, U4-NFR1.5)
    violation: 422 SIGNING_POLICY_REFUSED com o motivo
    source: ADR-004, ADR-005, project.md (NEVER deixar o TxSigner assinar fora da política)
  - id: BR5.1
    statement: Envio e confirmação em até 60 s
    category: policy
    applies_to: ordem SIGNED
    trigger: SIGNED
    logic: >
      Reconfere (BR3.3) e, numa transação, grava SENT e o OrderAttempt (intenção de envio) ANTES da chamada de rede;
      só então envia pela M3. Assim, uma ordem SIGNED nunca foi enviada e pode ser cancelada com segurança; uma queda
      depois da gravação deixa a ordem SENT, e a retomada reenvia a mesma transação ou a resolve por BR5.2. O resultado
      do envio fica no OrderAttempt. Consulta o estado a cada 2 s: FINALIZED vira
      CONFIRMED; FAILED na rede vira FAILED (SLIPPAGE_EXCEEDED quando a rede indicar derrapagem, senão CHAIN_REJECTED);
      sem resposta final em 60 s desde o envio, vira AMBIGUOUS. Erro de RPC no envio é reenviado com a mesma transação
      até o blockhash vencer
    violation: não se aplica
    source: FR5.6, AC3.3.1, AC3.3.5, AC8.3.1
  - id: BR5.2
    statement: Ordem ambígua só é resolvida pela rede
    category: policy
    applies_to: ordem AMBIGUOUS
    trigger: trabalho a cada 15 s
    logic: >
      Consulta a assinatura pela M3: FINALIZED vira CONFIRMED; altura atual maior que lastValidBlockHeight e transação
      ausente vira FAILED (BLOCKHASH_EXPIRED); senão continua AMBIGUOUS. Com o RPC fora, continua AMBIGUOUS, o valor
      fica reservado, a M1 pauseUser(UPSTREAM) é pedida e o alerta dispara. Uma ambígua nunca é reenviada nem reassinada
      antes dessa conferência
    violation: não se aplica
    source: AC8.3.2 a AC8.3.4, NFR4
  - id: BR5.3
    statement: A ordem ambígua conta nos limites e no saldo até terminar
    category: constraint
    applies_to: ordem AMBIGUOUS
    trigger: enquanto AMBIGUOUS
    logic: O RESERVE da ordem só é liberado no estado final; a U3 conta o RESERVE de ordem no teto e no saldo
    violation: não se aplica
    source: AC6.1.5, AC4.1.2
  - id: BR6.1
    statement: O ledger REAL usa os valores da rede
    category: calculation
    applies_to: ordem CONFIRMED ou FAILED na rede
    trigger: estado final
    logic: >
      Na confirmação, lê a transação pela M3 (saldos antes e depois da carteira do bot) e grava SWAP_FILL_IN (lamports
      negativos) e SWAP_FILL_OUT (lamports positivos) com mint e quantidade, NETWORK_FEE quando a taxa é paga pela
      carteira do bot (AUTO_FULL), RENT quando houver (sempre pago pela carteira do bot) e RESERVE_RELEASE. No modo
      Assinada a taxa da rede sai da carteira principal: ela não muda o saldo da carteira do bot e por isso não entra no
      ledger dela, mas fica gravada na ordem (feeLamports, payer = MAIN_WALLET) e entra no P&L REAL (BR6.2). Na falha com a transação na rede, só NETWORK_FEE e RESERVE_RELEASE. No
      cancelamento antes do envio, só RESERVE_RELEASE. Toda entrada tem a txSignature quando existe e chave de
      idempotência = realOrderId + tipo
    violation: não se aplica
    source: FR7.1, FR7.4, AC4.2.2, adendo da U3 (convenção dos preenchimentos)
  - id: BR6.2
    statement: P&L REAL por custo médio, com taxas
    category: calculation
    applies_to: RealPnl
    trigger: GET /api/v1/real/pnl
    logic: >
      Por mint, em ordem de confirmação: compra soma quantidade e custo (lamports gastos + taxa, inclusive a taxa paga
      pela carteira principal no modo Assinada, lida da ordem); venda realiza
      (lamports recebidos − taxa − custo médio × quantidade vendida, arredondando o custo para cima) e baixa o custo
      proporcional. Não realizado = valor a preço de cotação (LamportPrice da U3) − custo restante; preço ausente fica
      unknown, nunca zero. Só lê o ledger REAL; nenhum valor PAPER
    violation: não se aplica
    source: FR7.4, AC4.2.1, team.md (P&L determinístico)
  - id: BR7.1
    statement: Rebaixar o modo vale na hora e cancela as ordens não enviadas
    category: policy
    applies_to: PUT /api/v1/real/mode
    trigger: modo pedido menos permissivo que o atual
    logic: >
      Chama M1 lowerMode e, na mesma transação, cancela as ordens PROPOSED e QUOTED do usuário com MODE_LOWERED e
      RESERVE_RELEASE. Ordens SIGNED seguem, porque a reconferência antes do envio (BR3.3) as cancela; SENT e AMBIGUOUS
      são acompanhadas até o fim
    violation: não se aplica
    source: FR4.2, AC3.1.2, AC3.1.4
  - id: BR7.2
    statement: Subir o modo exige consentimento e nenhuma ordem ambígua
    category: authorization
    applies_to: PUT /api/v1/real/mode
    trigger: modo pedido mais permissivo que o atual
    logic: >
      SE houver ordem AMBIGUOUS do usuário ENTÃO 409 ORDER_AMBIGUOUS. SENÃO exige challengeId e signature e chama M1
      applyConsent; consentimento inválido responde 422 CONSENT_INVALID. O modo inicial é Assinada (U1)
    violation: 409 ORDER_AMBIGUOUS ou 422 CONSENT_INVALID
    source: FR4.2, AC3.1.1, AC3.1.3
  - id: BR8.1
    statement: Vender tudo cria uma venda por posição e depois saca
    category: policy
    applies_to: POST /api/v1/real/liquidations
    trigger: pedido do usuário
    logic: >
      SE já houver liquidação SELLING ou WITHDRAWING ou ordem AMBIGUOUS ENTÃO 409. SENÃO, sob a trava da pessoa da U3
      (a mesma que o consumidor da fila pega antes do handOver, então os dois nunca rodam juntos para o mesmo usuário),
      cria a Liquidation em estado SELLING e, na mesma transação, cancela as ordens não enviadas do usuário
      (cancelUnsent USER, motivo LIQUIDATION). Uma ordem em voo que vira AMBIGUOUS durante a espera não trava a
      liquidação: ela segue como as vendas ambíguas (BR8.2), e a posição dessa ordem entra no remaining se a ordem não
      terminar.
      Enquanto a liquidação estiver ativa, o ciclo e o consumidor descartam toda decisão dos bots REAL do usuário
      (LIQUIDATION_ACTIVE), e nenhuma compra nova nasce. As vendas são criadas uma de cada vez, em série: a próxima só
      nasce quando a anterior chega a estado final ou AMBIGUOUS, e só depois que as ordens já enviadas antes do pedido
      terminarem. Cada venda usa a quantidade da posição lida (M2 positions) no momento em que é criada, tem origem
      USER_LIQUIDATION e idempotencyKey = liquidationId + mint, e é avaliada pela M5. Em AUTO_FULL a venda é assinada
      pelo TxSigner; em Assinada e Automática nas saídas abre uma sessão de assinatura por vez, cada uma com seus 8 s.
      Sem posição, vai direto ao saque
    violation: 409 ORDER_ALREADY_IN_PROGRESS ou ORDER_AMBIGUOUS
    source: FR3.4, AC5.2.1
  - id: BR8.2
    statement: O saque da liquidação leva o que está livre e mostra o que ficou
    category: policy
    applies_to: Liquidation
    trigger: todas as vendas em estado final ou AMBIGUOUS
    logic: >
      Quando nenhuma venda estiver em PROPOSED, QUOTED, SIGNED ou SENT, pede M1 requestWithdrawal(ALL) com chave =
      liquidationId. SE todas confirmaram ENTÃO DONE. SENÃO PARTIAL, com remaining = posições que ficaram e o motivo
      (FAILED com o código, AMBIGUOUS "em conferência"). O saque nunca espera a ambígua
    violation: não se aplica
    source: AC5.2.2, FR3.3
  - id: BR9.1
    statement: Falha de fornecedor falha a ordem e, repetida, pausa o REAL do usuário
    category: policy
    applies_to: chamadas à Jupiter, ao RPC e ao TxSigner
    trigger: falha técnica antes da assinatura
    logic: >
      A ordem vira FAILED (UPSTREAM_UNAVAILABLE) e RESERVE_RELEASE. Conta a falha por usuário e fornecedor; na terceira
      seguida, M1 pauseUser(UPSTREAM). Um sucesso desse usuário com esse fornecedor zera a conta. Um trabalho no worker,
      a cada 30 s, faz a chamada de saúde de cada fornecedor (cotação pequena SOL para USDC na Jupiter, getHealth e
      altura do bloco no RPC, rota de saúde do TxSigner) e guarda desde quando cada um está saudável. A pausa UPSTREAM de
      um usuário, venha desta regra ou da BR5.2, é retomada (M1 resumeUser(UPSTREAM)) quando todos os fornecedores
      estão saudáveis há 5 min e o usuário não tem ordem AMBIGUOUS. O saque nunca passa por essa regra
    violation: FAILED UPSTREAM_UNAVAILABLE; pausa UPSTREAM
    source: NFR4, X1, X3
  - id: BR10.1
    statement: Cada mudança de estado é auditada e avisada só ao dono
    category: policy
    applies_to: RealOrder, Liquidation
    trigger: toda mudança de estado
    logic: >
      Evento de auditoria REAL com txSignature e mint nos campos tipados (M8) e evento RealOrderStateChanged no canal ao
      vivo, só para o tenant e o usuário donos. A sessão de assinatura aberta também gera aviso. Logs mascaram assinatura
      e endereço
    violation: não se aplica
    source: FR11.1, FR11.3, R6
  - id: BR10.2
    statement: Ordens e P&L só do próprio usuário
    category: authorization
    applies_to: rotas R3
    trigger: toda leitura e escrita
    logic: >
      Tenant e usuário vêm da autenticação. Ordem de outro usuário responde 404. As rotas R3 exigem o aceite dos termos
      v3 (sem exceção). Valores em lamports e unidades base, em texto; instantes em UTC
    violation: 404 ou 403 TERMS_REACCEPTANCE_REQUIRED
    source: NFR6, NFR10, contract-summary (regras comuns)
```

## Resumo

- **Bot REAL e fila:** BR1 e BR2 definem o bot REAL e o caminho da decisão até a ordem, com uma ordem por bot.
- **Uma transação por ordem:** BR3 garante cotação recente, reconferência antes de assinar e de enviar, e nenhuma segunda transação.
- **Assinatura:** BR4 cobre Automática total, Assinada com cossinatura e a política do TxSigner.
- **Rede:** BR5 cobre o envio, a confirmação e a ordem ambígua.
- **Dinheiro:** BR6 lança no ledger os valores da rede e calcula o P&L REAL.
- **Usuário:** BR7 cobre a troca de modo e BR8 o "vender tudo e sacar".
- **Fora do caminho feliz:** BR9 trata as falhas de fornecedor e BR10 a auditoria e o isolamento.
