# Regras de negócio — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md` (US1.1, US1.2, US2.2, US5.1, US8.5), `inception/units-generation/unit-of-work.md`, `inception/units-generation/unit-of-work-story-map.md`, `inception/domain-design/components.md`, `inception/contract-design/contract-summary.md` e `functional-design-questions.md` (Q1 a Q11). O bloco YAML é a fonte da verdade.

## Regras

```yaml
rules:
  # Grupo 1 — ativação e consentimento
  - id: BR1.1
    statement: A ativação só existe com o REAL ligado no ambiente, tanto na API quanto no TxSigner
    category: policy
    applies_to: ativação
    trigger: pedido de desafio ACTIVATE ou de ativação
    logic: SE o flag REAL do ambiente ou do TxSigner está desligado ENTÃO recusar
    violation: 403 REAL_DISABLED; nenhuma chave é criada
    source: FR10.1, NFR8
  - id: BR1.2
    statement: A ativação exige o aceite da versão v3 dos termos
    category: authorization
    applies_to: ativação
    trigger: pedido de desafio ACTIVATE ou de ativação
    logic: SE o usuário não aceitou a v3 ENTÃO recusar e indicar o novo aceite; SE aceitou ENTÃO seguir para o consentimento
    violation: 403 TERMS_REACCEPTANCE_REQUIRED
    source: FR9.2
  - id: BR1.3
    statement: A ativação exige liberação vigente do usuário para a rede do ambiente
    category: authorization
    applies_to: ativação
    trigger: pedido de desafio ACTIVATE ou de ativação
    logic: SE não há RealRelease com effectiveAt <= agora, sem revogação, na rede do ambiente ENTÃO recusar
    violation: 403 REAL_NOT_RELEASED, com texto de REAL indisponível para a conta
    source: FR10.1, FR10.2
  - id: BR1.4
    statement: Uma carteira do bot ativa por usuário
    category: constraint
    applies_to: BotWalletAccount
    trigger: ativação
    logic: SE o usuário já tem carteira ACTIVE ENTÃO recusar
    violation: 409 BOT_WALLET_ALREADY_ACTIVE
    source: FR2.4
  - id: BR1.5
    statement: O consentimento só vale com a mensagem canônica, assinada pela carteira principal verificada no login, com nonce novo, dentro da validade, na rede do ambiente e com os mesmos valores do pedido
    category: validation
    applies_to: WalletConsent
    trigger: ativação, subir modo, subir teto
    logic: SE a linha 1 não é o título de consentimento (inclusive mensagem de login), OU purpose difere do tipo do desafio, OU bot-wallet não é NEW na ativação nem a carteira do próprio usuário nas mudanças, OU a assinatura não confere com a carteira principal, OU o nonce já foi usado, OU expiresAt passou, OU a rede difere, OU modo ou teto diferem do desafio ENTÃO recusar. A mensagem é comparada byte a byte com a guardada no desafio
    violation: 422 CONSENT_INVALID ou CONSENT_EXPIRED; o desafio é consumido
    source: FR2.1, FR4.2
  - id: BR1.6
    statement: A chave da carteira do bot nasce dentro do KMS pelo TxSigner e nunca sai dele
    category: policy
    applies_to: BotWalletAccount, WalletPolicy
    trigger: ativação
    logic: o TxSigner cria a chave não exportável no KMS e devolve só o endereço público e uma referência opaca; nenhuma resposta, log ou tela contém material de chave
    violation: não existe caminho que devolva a chave; teste falha se a referência ou qualquer material aparecer em resposta ou log
    source: FR2.2, NFR1
  - id: BR1.7
    statement: A carteira nasce no modo Assinada com o teto do consentimento
    category: constraint
    applies_to: RealPreferences
    trigger: ativação aceita
    logic: operationMode = SIGNED; capLamports = teto do consentimento, que deve ser <= maxCapLamports; feeReserveLamports = valor do ambiente; o endereço da carteira é devolvido para depósito
    violation: teto acima do máximo recusa o desafio com 422 CAP_ABOVE_MAXIMUM
    source: FR2.3, FR2.4, FR4.1
  - id: BR1.8
    statement: Cada nonce é usado uma única vez, na API e no TxSigner
    category: validation
    applies_to: UsedNonce, SignerUsedNonce
    trigger: consentimento conferido
    logic: o nonce aceito é gravado com purgeAfter = expiresAt + 1 dia em cada lado; um nonce gravado nunca é aceito de novo, salvo a repetição do mesmo pedido (mesmo requestId) no TxSigner, que devolve o mesmo resultado. Guardar todos os nonces dentro da validade é mais estrito que o "último nonce por carteira" citado no contract-summary e o substitui
    violation: 422 CONSENT_INVALID
    source: FR2.1
  - id: BR1.10
    statement: A ativação não deixa estado parcial nem chave órfã sem alerta
    category: policy
    applies_to: ConsentChallenge, BotWalletAccount, WalletPolicy
    trigger: ativação
    logic: >
      O requestId enviado ao TxSigner é o challengeId. A API confere o consentimento e trava o desafio (estado IN_USE)
      sem consumi-lo; chama o TxSigner, que é idempotente por requestId e devolve sempre a mesma carteira para o mesmo
      requestId; só com a resposta a API consome o desafio, grava o nonce e cria a carteira numa transação. Se o TxSigner
      não responder, o desafio volta a PENDING até vencer e o usuário pode repetir com o mesmo desafio, recebendo a
      mesma carteira. Uma WalletPolicy sem carteira na API depois que o desafio venceu é listada pela verificação diária
      de órfãs e gera alerta; o endereço dela nunca foi mostrado, então não recebe depósito
    violation: não se aplica
    source: FR2.2, NFR4
  - id: BR1.9
    statement: No desafio, o valor que não muda entra na mensagem com o valor vigente
    category: calculation
    applies_to: ConsentChallenge
    trigger: emissão do desafio
    logic: ACTIVATE leva modo SIGNED e o teto escolhido; RAISE_MODE leva o teto atual; RAISE_CAP leva o modo atual
    violation: pedido sem o valor que muda recusa com 400 VALIDATION_FAILED
    source: FR4.2, FR2.3

  # Grupo 2 — preferências e pausa
  - id: BR2.1
    statement: O teto do usuário fica entre zero e o teto máximo, que nunca passa de 10 SOL
    category: constraint
    applies_to: RealPreferences
    trigger: mudança de teto
    logic: baixar é imediato; subir exige consentimento RAISE_CAP; maxCapLamports padrão 10 SOL e só o administrador o altera (U5)
    violation: 422 CAP_ABOVE_MAXIMUM; subir sem consentimento recusa com 422 CONSENT_INVALID
    source: FR2.3
  - id: BR2.2
    statement: Baixar teto ou modo é imediato; subir o teto exige consentimento; a política do TxSigner só é ampliada por consentimento que ele mesmo confere
    category: authorization
    applies_to: RealPreferences, WalletPolicy
    trigger: mudança de teto (U1) ou rebaixamento de modo (U1); subir o modo e a rota de modo ficam na U4, usando este mesmo mecanismo
    logic: baixar envia LOWER_* ao TxSigner, que só restringe; subir o teto envia a mensagem e a assinatura, e o TxSigner confere de novo antes de ampliar
    violation: TxSigner recusa e a mudança não acontece
    source: FR4.2, NFR1
  - id: BR2.3
    statement: Pausas têm motivo; o usuário só retoma a pausa que ele mesmo fez; pausa nunca bloqueia saque nem leitura
    category: policy
    applies_to: RealPreferences
    trigger: pausar ou retomar
    logic: SE retomar com motivo diferente de USER pelo usuário ENTÃO recusar; outras pausas são retiradas por quem as causou
    violation: 409 REAL_NOT_PAUSED ou REAL_PAUSED
    source: FR7.3, FR3.3

  # Grupo 3 — depósito e saldo
  - id: BR3.1
    statement: Depósito só conta depois de finalizado na rede e é registrado uma única vez
    category: validation
    applies_to: ObservedDeposit
    trigger: varredura a cada 15 s das carteiras ativas
    logic: cada carteira guarda um cursor (última assinatura processada); a varredura lê as entradas finalizadas depois do cursor, em ordem; para cada entrada ainda não registrada (chave: assinatura) grava o depósito, o lançamento DEPOSIT (chave dep:<assinatura>) e avança o cursor na mesma transação. Entradas abaixo de 1.000.000 lamports (0,001 SOL) também entram no saldo, para o ledger bater com a rede, mas não geram aviso ao usuário nem alerta individual (só um alerta agregado por dia)
    violation: entrada repetida é ignorada sem erro
    source: FR3.1, NFR4
  - id: BR3.2
    statement: SOL de origem que não é a carteira principal entra no saldo, marcado, e nunca volta à origem
    category: policy
    applies_to: ObservedDeposit
    trigger: depósito detectado
    logic: SE a origem difere da carteira principal ENTÃO knownSource = falso, origem gravada só como HMAC, alerta ao administrador, aviso ao usuário e pedido de reconciliação
    violation: não se aplica
    source: FR3.1, FR11.2
  - id: BR3.3
    statement: O bot só opera até o teto; o excedente fica disponível só para saque
    category: calculation
    applies_to: saldo
    trigger: leitura do saldo
    logic: operável = max(0, min(livre - reserva para taxas, teto)); acimaDoTeto = max(0, livre - reserva para taxas - teto)
    violation: não se aplica
    source: FR3.2
  - id: BR3.5
    statement: O saldo segue uma convenção única de sinal e de tipo de lançamento
    category: calculation
    applies_to: LedgerEntry
    trigger: toda leitura de saldo
    logic: >
      Lançamentos de saldo (mexem no total, com sinal): DEPOSIT (+), WITHDRAWAL (-), NETWORK_FEE (-), RENT (-),
      SWAP_FILL_IN e SWAP_FILL_OUT (U4), COMPENSATION (+ ou -). Lançamentos de reserva (não mexem no total, sempre
      positivos): RESERVE e RESERVE_RELEASE. total = soma dos lançamentos de saldo em SOL; reservado = soma(RESERVE) -
      soma(RESERVE_RELEASE); livre = total - reservado. Posições em token = soma dos SWAP_FILL por mint (vazias até a
      U4). Ordem em andamento = RESERVE com referência de ordem ainda sem RESERVE_RELEASE; tudo isso é lido do próprio
      ledger, sem chamar a U4
    violation: não se aplica
    source: FR7.1, FR3.4
  - id: BR3.4
    statement: A reserva para taxas fica fora do saldo operável e aparece separada
    category: calculation
    applies_to: saldo
    trigger: leitura do saldo
    logic: reserva = feeReserveLamports (0,03 SOL padrão, por ambiente); livre = total do ledger REAL - reservas em andamento
    violation: não se aplica
    source: FR3.5

  # Grupo 4 — saque
  - id: BR4.1
    statement: O saque vai só para a carteira principal registrada na criação
    category: authorization
    applies_to: Withdrawal
    trigger: pedido de saque e assinatura
    logic: o pedido não tem campo de destino; a BotWallet monta a transferência para mainWalletAddress; o TxSigner recusa qualquer destino diferente da sua WalletPolicy
    violation: 422 WITHDRAWAL_DESTINATION_INVALID (pedido) ou SIGNING_POLICY_REFUSED (assinatura)
    source: FR3.3
  - id: BR4.2
    statement: O saque é processado com kill switch, REAL desligado, sem liberação, sem o novo aceite dos termos ou com o REAL pausado
    category: policy
    applies_to: Withdrawal
    trigger: pedido de saque
    logic: nenhuma dessas condições bloqueia o saque; o TxSigner assina WITHDRAWAL mesmo com o flag desligado. Isso não contradiz a regra de team.md "nenhuma assinatura no KMS com a configuração padrão": com a configuração padrão nenhuma carteira pode ser criada (BR1.1), então não existe saque a assinar; o saque com flag desligado só acontece para carteiras criadas antes do desligamento
    violation: não se aplica
    source: FR3.3, FR8.3
  - id: BR4.3
    statement: O valor de ordens e saques em andamento fica reservado e não é sacado
    category: calculation
    applies_to: Withdrawal
    trigger: pedido de saque
    logic: sacável = livre (já sem reservas) - reserva para taxas, salvo no saque total
    violation: 422 INSUFFICIENT_FREE_BALANCE
    source: FR3.4
  - id: BR4.4
    statement: O saque total devolve tudo, inclusive a reserva, menos a taxa do próprio saque, e só sem posição nem ordem em andamento
    category: calculation
    applies_to: Withdrawal
    trigger: pedido de saque total
    logic: posição e ordem em andamento vêm do ledger (BR3.5), sem chamar a U4. SE não há posição, nem ordem em andamento, nem outro saque em andamento ENTÃO valor = livre do ledger - taxa calculada da transação (taxa base por assinatura mais a prioridade, conhecida antes de assinar, gravada no saque e conferida pelo TxSigner), o que zera a carteira (NFR4.3); SENÃO o total vira o sacável de BR4.3. Com pausa DIVERGENCE vale BR4.6
    violation: 409 ORDER_AMBIGUOUS quando há saque ou ordem ambígua
    source: FR3.4, FR3.5
  - id: BR4.5
    statement: O saque parcial não deixa a carteira entre zero e o mínimo isento de aluguel nem invade a reserva para taxas
    category: validation
    applies_to: Withdrawal
    trigger: pedido de saque parcial
    logic: SE o saldo restante ficaria > 0 e < mínimo isento (890.880 lamports, valor lido da rede e configurável), OU abaixo da reserva para taxas ENTÃO recusar. Um saque parcial é aceito enquanto outro está AMBIGUOUS, porque a reserva do ambíguo continua fora do livre
    violation: 422 INSUFFICIENT_FREE_BALANCE com o máximo sacável
    source: FR3.5
  - id: BR4.6
    statement: Com o REAL pausado por divergência, o sacável usa o menor saldo entre o ledger e a rede
    category: calculation
    applies_to: Withdrawal
    trigger: pedido de saque com pausa DIVERGENCE
    logic: livre = min(livre do ledger, saldo na rede - reservas)
    violation: 422 INSUFFICIENT_FREE_BALANCE
    source: FR7.3, FR3.3
  - id: BR4.7
    statement: O saque segue estados gravados e uma falha ou ambiguidade nunca gera transferência dupla
    category: policy
    applies_to: Withdrawal
    trigger: cada passo do saque
    logic: >
      REQUESTED grava RESERVE(valor + taxa calculada, chave wd:<id>:reserve), com a taxa calculada antes da assinatura (NFR4.3). SIGNED guarda a transação assinada
      (signedTransaction) e o messageDigest no próprio Withdrawal. SENT. CONFIRMED (commitment finalized) grava
      WITHDRAWAL(-valor, wd:<id>:out), NETWORK_FEE(-taxa real, wd:<id>:fee) e RESERVE_RELEASE(valor + taxa calculada,
      wd:<id>:release) na mesma transação. FAILED (blockhash vencido sem a assinatura em finalized, ou recusa do
      TxSigner) grava só RESERVE_RELEASE(wd:<id>:release). Sem resultado em 60 s vira AMBIGUOUS; a conferência usa a
      assinatura em commitment finalized até a altura passar de lastValidHeight, e então vira CONFIRMED ou FAILED.
      Todo reenvio usa a signedTransaction guardada; nunca se pede outra assinatura para o mesmo saque
    violation: a mesma transação assinada pode ser reenviada; nunca uma nova transação para o mesmo saque
    source: FR3.3, NFR4
  - id: BR4.9
    statement: Pedidos de saque da mesma carteira são serializados
    category: constraint
    applies_to: Withdrawal
    trigger: pedido de saque
    logic: o cálculo do sacável, o RESERVE e a gravação do Withdrawal acontecem numa única transação com trava exclusiva da linha da carteira (BotWalletAccount); dois pedidos simultâneos nunca leem o mesmo livre. O sacável sempre vem do ledger; o saldo da rede só entra como limite extra com a pausa DIVERGENCE (BR4.6)
    violation: o segundo pedido espera a trava (até 2 s) e recalcula; acima disso, 503 com repetição segura pela chave de idempotência
    source: FR3.4, NFR4
  - id: BR4.8
    statement: Cada saque tem chave de idempotência, pelo mecanismo comum do módulo de identidade
    category: validation
    applies_to: Withdrawal
    trigger: pedido de saque
    logic: usa o mecanismo comum de idempotência (regra de project.md), que guarda a chave com o hash do conteúdo; mesma chave e mesmo conteúdo devolvem o mesmo saque; conteúdo diferente devolve conflito
    violation: 409 IDEMPOTENCY_CONFLICT
    source: FR5.5, NFR4

  # Grupo 5 — TxSigner (parte da U1: criação, política e saque)
  - id: BR5.1
    statement: O TxSigner só assina um saque que tenha exatamente a forma permitida, instrução por instrução
    category: policy
    applies_to: SigningRecord
    trigger: pedido de assinatura WITHDRAWAL
    logic: >
      A transação é aceita só se TODAS as condições valem: (a) mensagem sem tabela de endereços (ALT); (b) um único
      assinante, a carteira do bot, que também é o pagador da taxa; (c) blockhash recente comum, sem nonce durável
      (nenhuma instrução AdvanceNonceAccount nem conta de nonce); (d) a lista de instruções é exatamente: no máximo um
      SetComputeUnitLimit (até 1.000 unidades), no máximo um SetComputeUnitPrice e exatamente uma instrução Transfer do
      System Program, da carteira do bot para a mainWalletAddress da WalletPolicy, com lamports iguais ao valor do
      pedido; (e) nenhuma outra instrução do System Program (Assign, Allocate, CreateAccount, TransferWithSeed etc.) e
      nenhum outro programa; (f) taxa total (taxa base mais prioridade) até maxWithdrawalFeeLamports (100.000 lamports,
      por ambiente). Qualquer outra forma é recusada
    violation: 422 SIGNING_POLICY_REFUSED com código de motivo (ALT_NOT_ALLOWED, EXTRA_SIGNER, FEE_PAYER_MISMATCH, DURABLE_NONCE_NOT_ALLOWED, INSTRUCTION_NOT_ALLOWED, DESTINATION_NOT_ALLOWED, AMOUNT_MISMATCH, FEE_TOO_HIGH)
    source: FR3.3, NFR1
  - id: BR5.2
    statement: O TxSigner falha fechado e registra toda decisão
    category: policy
    applies_to: SigningRecord
    trigger: todo pedido de assinatura
    logic: contexto ausente, tipo não suportado nesta Unit (SWAP_* até a U4) ou credencial de serviço inválida recusam; toda assinatura e toda recusa grava SigningRecord e evento de auditoria; repetir o mesmo signingRequestId com o mesmo messageDigest devolve a mesma decisão e a mesma assinatura; o mesmo signingRequestId com outro messageDigest é recusado (REQUEST_MISMATCH), para que um saque nunca tenha duas transações assinadas
    violation: 401 ou 403 para credencial; 422 SIGNING_POLICY_REFUSED para conteúdo
    source: NFR1, FR11.3
  - id: BR5.3
    statement: O TxSigner aplica os limites absolutos sem confiar na API
    category: validation
    applies_to: WalletPolicy
    trigger: criação de carteira e consentimento
    logic: confere de novo a mensagem canônica inteira; purpose igual ao tipo do pedido; bot-wallet igual a NEW na criação e igual à carteira alvo nas mudanças; cap-lamports nunca acima de 10 SOL; nonce de uso único no próprio banco; network igual à do TxSigner
    violation: 422 CONSENT_INVALID
    source: FR2.1, FR2.3, NFR1

  # Grupo 6 — auditoria e ledger
  - id: BR6.1
    statement: Cada passo do REAL da U1 gera evento de auditoria
    category: policy
    applies_to: auditoria
    trigger: ativação (pedida, criada, recusada), consentimento (aceito, recusado), depósito, cada estado do saque, mudança de teto, mudança de modo, pausa e retomada, assinatura e recusa no TxSigner
    logic: o evento vai para a tabela de saída na mesma transação da ação
    violation: sem evento a ação não é confirmada
    source: FR11.3
  - id: BR6.2
    statement: Assinatura de transação, mint e carteira do bot vão em campos próprios; a carteira principal só como HMAC; logs mascaram
    category: policy
    applies_to: auditoria e logs
    trigger: todo evento e log
    logic: esses valores só são aceitos nas chaves tipadas; em `details` livre continuam recusados; logs mostram só o começo e o fim do endereço ou da assinatura
    violation: o validador recusa o evento
    source: FR11.1, FR11.2
  - id: BR6.3
    statement: O ledger REAL é só inclusão e separado do PAPER
    category: constraint
    applies_to: LedgerEntry
    trigger: todo lançamento e toda consulta
    logic: UPDATE e DELETE falham; toda consulta filtra o modo; lançamento REAL exige botWalletId
    violation: erro de banco e teste obrigatório falhando
    source: FR7.1, NFR2

  - id: BR6.4
    statement: Tenant e usuário vêm só da autenticação; recurso de outra conta nunca é visível nem operável
    category: authorization
    applies_to: BotWalletAccount, RealPreferences, ObservedDeposit, Withdrawal, ConsentChallenge
    trigger: toda leitura e escrita, inclusive no worker
    logic: toda consulta filtra por tenant e usuário do contexto de autenticação; o worker confere o tenant gravado em cada carteira antes de lançar; um id de outra conta responde como inexistente
    violation: 404 NOT_FOUND e evento de auditoria
    source: NFR6

  # Grupo 7 — liberação mínima do esqueleto
  - id: BR7.1
    statement: Na U1, a liberação só nasce pela semente do perfil de teste e local, e vale 24 h depois
    category: policy
    applies_to: RealRelease
    trigger: carga da semente
    logic: effectiveAt = requestedAt + 24 h; nenhum perfil de produção grava liberação na U1; relógio injetável nos testes
    violation: não existe rota de liberação na U1
    source: FR10.2, FR10.3
```

## Resumo

| ID | Regra | Fonte |
|---|---|---|
| BR1.1 | REAL ligado no ambiente e no TxSigner para ativar | FR10.1 |
| BR1.2 | Termos v3 aceitos | FR9.2 |
| BR1.3 | Liberação vigente | FR10.1, FR10.2 |
| BR1.4 | Uma carteira por usuário | FR2.4 |
| BR1.5 | Consentimento canônico válido | FR2.1, FR4.2 |
| BR1.6 | Chave só no KMS | FR2.2 |
| BR1.7 | Nasce em Assinada com o teto do consentimento | FR2.3, FR4.1 |
| BR1.8 | Nonce de uso único nos dois lados | FR2.1 |
| BR1.9 | Valor vigente no desafio | FR4.2 |
| BR1.10 | Ativação sem estado parcial | FR2.2, NFR4 |
| BR2.1 | Teto até o máximo de 10 SOL | FR2.3 |
| BR2.2 | Rebaixar imediato, subir com consentimento | FR4.2 |
| BR2.3 | Pausa com motivo | FR7.3 |
| BR3.1 | Depósito finalizado, uma vez | FR3.1 |
| BR3.2 | Origem desconhecida marcada | FR3.1 |
| BR3.3 | Opera só até o teto | FR3.2 |
| BR3.4 | Reserva para taxas separada | FR3.5 |
| BR3.5 | Convenção de sinal e de tipos do saldo | FR7.1, FR3.4 |
| BR4.1 | Saque só para a principal | FR3.3 |
| BR4.2 | Saque sempre disponível | FR3.3, FR8.3 |
| BR4.3 | Reservas fora do sacável | FR3.4 |
| BR4.4 | Saque total | FR3.4, FR3.5 |
| BR4.5 | Mínimos do saque parcial | FR3.5 |
| BR4.6 | Saque com divergência | FR7.3 |
| BR4.7 | Estados do saque | FR3.3, NFR4 |
| BR4.8 | Idempotência do saque | FR5.5 |
| BR4.9 | Saques serializados por carteira | FR3.4, NFR4 |
| BR5.1 | Forma exata do saque no TxSigner | FR3.3, NFR1 |
| BR5.2 | TxSigner falha fechado | NFR1 |
| BR5.3 | Limites absolutos no TxSigner | FR2.1, FR2.3 |
| BR6.1 | Eventos de auditoria | FR11.3 |
| BR6.2 | Campos tipados e máscara | FR11.1, FR11.2 |
| BR6.3 | Ledger só inclusão | FR7.1 |
| BR6.4 | Isolamento entre contas | NFR6 |
| BR7.1 | Liberação por semente de teste | FR10.2 |
