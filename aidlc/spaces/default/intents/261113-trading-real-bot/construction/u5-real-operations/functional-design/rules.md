# Regras de negócio — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes:
- `inception/requirements-analysis/requirements.md`: FR7.2, FR7.3, FR8, FR10, FR12, FR15;
- `inception/user-stories/stories.md`: US7.1, US7.2, US7.3, US8.1, US8.2, US8.4;
- `inception/contract-design/contract-summary.md`: R4, M1, M2, M5, M6, M7;
- `project.md` (saque sempre disponível; REAL desligado por padrão) e `team.md` (rollout do REAL);
- `functional-design-questions.md`: Q1 a Q12, com o Q2 mudado pelo responsável.

## Regras

```yaml
rules:
  - id: BR1.1
    statement: O REAL nasce desligado e só vale com liberação ativa
    category: authorization
    applies_to: todo usuário
    trigger: qualquer leitura de estado REAL (M1 readRealState)
    logic: released = existe RealRelease ACTIVE para o usuário e a rede configurada; sem isso, released = false
    violation: as ordens são recusadas pela barreira (U3 REAL_PAUSED NOT_RELEASED)
    source: FR10.1, AC7.1.1
  - id: BR1.2
    statement: Registrar uma liberação inicia a quarentena de 24 horas
    category: policy
    applies_to: POST /api/v1/admin/real/users/{userId}/release
    trigger: pedido do administrador
    logic: >
      Cria a RealRelease QUARANTINE com effectiveAt = agora + 24 h, na rede configurada do ambiente. Repetir o pedido
      com a mesma Idempotency-Key devolve a mesma liberação. Um usuário com liberação não revogada nessa rede recebe 409
      RELEASE_EXISTS. O usuário precisa ter carteira do bot ativa e o aceite dos termos v3
    violation: 409 RELEASE_EXISTS; 422 NO_BOT_WALLET ou TERMS_V3_MISSING
    source: FR10.2, AC7.1.2
  - id: BR1.3
    statement: Na mainnet, fundador primeiro e no máximo 20 convidados
    category: constraint
    applies_to: liberação na MAINNET
    trigger: POST release
    logic: >
      O fundador é a conta de usuário comum (BETA_USER, com carteira do bot e liberação próprias) indicada por
      memetrade.real.founder-user-id; ela é diferente da conta ADMIN, que só entra pela entrada administrativa
      (correção do project.md). Com a rede MAINNET, a API e o worker não sobem sem essa propriedade. SE o pedido é de
      um convidado E o fundador não tem RealRelease ACTIVE na MAINNET ENTÃO recusa. SE já há 20 liberações não revogadas
      de convidados na MAINNET ENTÃO recusa; a contagem e a criação acontecem sob uma trava de transação única da
      liberação na mainnet, então dois pedidos simultâneos nunca passam do limite. Não há espera mínima de operação do
      fundador (decisão do responsável no Q2; a resposta do Q2 dizia "papel ADMIN", corrigido aqui). Na DEVNET e na
      LOCALNET nada disso se aplica
    violation: 409 FOUNDER_FIRST ou 409 PILOT_FULL
    source: FR10.3, AC7.1.3, Q2
  - id: BR1.4
    statement: Ao vencer a quarentena, os portões decidem
    category: policy
    applies_to: RealRelease QUARANTINE ou BLOCKED
    trigger: trabalho a cada 5 min (ShedLock), que trata as vencidas em QUARANTINE e reconfere as BLOCKED na mesma execução
    logic: >
      Para cada liberação com effectiveAt ≤ agora: confere (a) a execução do workflow "ci" do commit exato em
      implantação (o SHA gravado na imagem) terminou com conclusion = "success"; execução ausente, em andamento ou com
      qualquer outra conclusão é vermelho. A porta CiStatus lê a API REST do GitHub com um token só de leitura de
      Actions (segredo próprio), tempo limite de 5 s e só o campo conclusion da resposta; qualquer erro é vermelho. Na
      MAINNET a fonte tem de ser o GitHub (um valor configurado recusa a subida); na LOCALNET, na DEVNET e nos testes o
      valor pode ser configurado; (b) nenhuma
      Divergence OPEN ou COMPENSATED do usuário; (c) o kill switch REAL desligado; (d) a rede da liberação igual à
      rede configurada da API e do TxSigner. Todos verdes: ACTIVE. Algum vermelho: BLOCKED com o primeiro motivo
      vermelho, nesta ordem: NETWORK_MISMATCH, KILL_SWITCH_ON, DIVERGENCE_OPEN, CI_UNREACHABLE (a CI não respondeu), CI_RED (a CI respondeu sem success). A mudança é compare-and-set
      sobre o estado; o GateSnapshot fica no evento de auditoria
    violation: BLOCKED com o motivo
    source: FR10.2, AC7.1.2, Q1
  - id: BR1.5
    statement: Mainnet só com configuração explícita de produção
    category: authorization
    applies_to: subida da API, do worker e do TxSigner
    trigger: subida
    logic: A rede MAINNET só sobe no perfil de produção com a configuração explícita (guardas de U1 e U4); liberação MAINNET fora disso é impossível
    violation: o processo não sobe
    source: FR10.4, AC7.1.4
  - id: BR1.6
    statement: Revogar a liberação vale na hora
    category: policy
    applies_to: DELETE /api/v1/admin/real/users/{userId}/release
    trigger: pedido do administrador, com motivo
    logic: >
      Sob a trava da pessoa (M5 withPersonLock), a liberação vira REVOKED e chama M6 cancelUnsent(USER, REAL_DISABLED)
      na mesma transação. As ordens enviadas seguem até o fim; as condicionais abertas ficam marcadas pela U7. O saque
      não muda. Revogar uma liberação já revogada devolve 200 sem efeito
    violation: não se aplica
    source: FR10.1, FR15.2
  - id: BR2.1
    statement: A reconciliação roda depois de cada ordem confirmada e a cada 15 minutos
    category: policy
    applies_to: carteiras do bot ativas
    trigger: evento de ordem CONFIRMED (U4) e trabalho a cada 15 min (ShedLock)
    logic: >
      Só compara uma carteira assentada. SE a carteira tem ordem SENT ou AMBIGUOUS, saque SENT ou AMBIGUOUS, ou entrada
      na rede (assinatura em que ela não assina) ainda não registrada pela leitura de depósitos até o slot ENTÃO o
      resultado é DEFERRED e a reconciliação tenta de novo no próximo gatilho; uma carteira DEFERRED por mais de 2 h
      emite o alerta de reconciliação adiada (a ordem ambígua já tem alerta próprio na U4). Assentada: lê no último slot
      finalizado o saldo de SOL da conta do sistema da carteira do bot e o de cada conta de token dela, e compara com o
      saldo do ledger REAL. O ledger já registra os valores reais lidos da rede em cada ordem confirmada (U4 BR6.1):
      troca, taxa da rede quando paga pela carteira do bot, aluguel de conta de token (RENT, com a devolução no
      fechamento) e o SOL embrulhado; no modo Assinada a taxa é paga pela carteira principal e não toca nem a carteira
      do bot nem o ledger dela. Por isso a tolerância é zero: CLEAN quando a diferença é zero em SOL e em cada mint;
      DIVERGENT quando não. (O "fora das taxas de aluguel previstas" da Q4 se cumpre porque o aluguel já está no
      ledger.) Com a rede indisponível, UNAVAILABLE, sem divergência nem pausa nova
    violation: DIVERGENT abre ou atualiza a Divergence (BR2.2)
    source: FR7.2, AC8.2.1, Q4
  - id: BR2.2
    statement: Divergência pausa o usuário e alerta
    category: policy
    applies_to: ReconciliationRun DIVERGENT
    trigger: fim da reconciliação
    logic: >
      Abre a Divergence OPEN (ou atualiza a aberta da carteira), pede M1 pauseUser(DIVERGENCE), registra evento de
      auditoria e incrementa a métrica de divergências, que dispara o alerta
    violation: pausa DIVERGENCE
    source: FR7.3, AC8.2.2
  - id: BR2.3
    statement: Correção por lançamento compensatório e nova reconciliação limpa
    category: policy
    applies_to: POST /api/v1/admin/real/divergences/{divergenceId}/compensation
    trigger: pedido do administrador em POST /api/v1/admin/real/divergences/{divergenceId}/compensation, com motivo e um item por ativo divergente (SOL em lamports e cada mint em unidades base, com sinal)
    logic: >
      Cada item tem de ser igual à diferença medida pela última reconciliação da divergência para aquele ativo; um
      valor diferente é recusado (422 COMPENSATION_MISMATCH), para o administrador nunca lançar um valor arbitrário.
      Grava um lançamento COMPENSATION por ativo pela M1 compensate (M2), com a chave de idempotência derivada da
      divergência e do ativo e o administrador como ator; a Divergence guarda a lista dos lançamentos e vira
      COMPENSATED. Roda uma reconciliação nova (AFTER_COMPENSATION). SE ela
      vier CLEAN ENTÃO a divergência vira RESOLVED e pede M1 resumeUser(DIVERGENCE). SE vier DIVERGENT ENTÃO a
      divergência volta a OPEN com os valores novos e a pausa continua. O ledger nunca é editado
    violation: 409 DIVERGENCE_NOT_OPEN; 422 VALIDATION_FAILED sem motivo
    source: FR7.3, project.md (ledger append-only)
  - id: BR2.4
    statement: Com divergência aberta, o saque fica limitado ao menor saldo
    category: constraint
    applies_to: saque (U1)
    trigger: pedido de saque com Divergence OPEN ou COMPENSATED da carteira, ou com a pausa RESTORE ativa (BR2.5)
    logic: >
      O valor sacável é o mínimo entre o saldo livre do ledger e o saldo de SOL na rede (menos a reserva de taxa). O
      saque total (ALL) usa esse mínimo. Sem divergência, a regra da U1 não muda. O saque nunca fica indisponível
    violation: o valor acima do limite é recusado com WITHDRAWAL_LIMITED_BY_DIVERGENCE; o restante pode ser sacado
    source: BR4.6 da U1, Q6, project.md (saque sempre disponível)
  - id: BR2.5
    statement: Depois de restaurar o banco, reconciliação completa obrigatória
    category: policy
    applies_to: subida do worker
    trigger: subida do worker
    logic: >
      Só a carteira do bot assina as próprias saídas (trocas e saques), e cada uma fica registrada no banco antes de ir
      à rede. Por isso uma assinatura da rede em que a carteira do bot é assinante e que o banco não conhece só existe
      se o banco perdeu histórico (restauração) ou se houve uso indevido da chave. Na subida, para cada carteira, lê
      todas as assinaturas da rede mais novas que a assinatura mais recente que o banco conhece para ela (paginando
      getSignaturesForAddress com until até esgotar; sem nenhuma conhecida, desde a criação da carteira), separa as em
      que a carteira assina e procura cada uma nas ordens, nos envios e nos saques. Não há janela fixa: um backup
      antigo é coberto inteiro. SE alguma
      não é conhecida ENTÃO cria o RestoreMarker, liga a pausa global RESTORE (valor novo de PauseReason, separado de
      DIVERGENCE) e roda a reconciliação AFTER_RESTORE de todas as carteiras; a pausa só sai quando todas vierem CLEAN e
      o administrador confirmar. Depósitos (entradas que a carteira não assina) nunca disparam a regra, então um
      reinício comum não pausa ninguém. Com o RPC indisponível na subida, a conferência é tentada de novo a cada
      minuto e, até terminar, as ordens REAL novas ficam recusadas pela pausa RESTORE_CHECK_PENDING; o saque continua
      (BR2.4)
    violation: REAL pausado para todos até a reconciliação completa e a confirmação do administrador
    source: pendência da U1, Q11
  - id: BR3.1
    statement: O kill switch REAL bloqueia ordens novas de todos
    category: policy
    applies_to: PUT /api/v1/admin/real/kill-switch
    trigger: pedido do administrador, com motivo
    logic: >
      Liga o RealKillSwitch numa transação curta, com compare-and-set na versão. A autoridade imediata é a leitura ao
      vivo da M7 current(): a M1 readRealState inclui KILL_SWITCH entre as pausas sempre que a M7 está ativa, então a
      barreira (U3) e a reconferência antes de assinar e de enviar (U4) recusam na hora toda ordem nova, inclusive de
      carteiras criadas ou liberadas com o interruptor ligado. Não há pausa gravada por usuário. Depois do commit, um
      trabalho chama M6 cancelUnsent(ALL, KILL_SWITCH) e repete a cada minuto enquanto o interruptor estiver ligado e
      sobrar ordem não enviada (convergência; uma falha parcial é refeita na rodada seguinte). As ordens enviadas
      seguem até o fim; a U7 marca as condicionais abertas pela M7. O saque continua e as posições ficam como estão.
      Ligar com ele já ligado devolve 200 sem efeito; ligar e desligar seguidos são serializados pela versão
    violation: não se aplica
    source: FR8.1 a FR8.3, AC8.1.1 a AC8.1.4, Q7
  - id: BR3.2
    statement: Desligar o kill switch não religa nada
    category: policy
    applies_to: DELETE /api/v1/admin/real/kill-switch
    trigger: pedido do administrador, com motivo
    logic: Desliga o interruptor (compare-and-set na versão); a leitura ao vivo da M7 deixa de incluir KILL_SWITCH e o trabalho de cancelamento para; bots parados continuam parados e as outras pausas continuam
    violation: não se aplica
    source: Q7
  - id: BR4.1
    statement: Baixar o teto vale na hora e é repassado ao TxSigner até confirmar
    category: policy
    applies_to: PUT /api/v1/admin/real/users/{userId}/cap
    trigger: pedido do administrador, com o teto novo
    logic: >
      SE o teto novo é maior ou igual ao atual ENTÃO 422 CAP_ONLY_LOWERED. SENÃO chama M1 adminLowerCap, que grava o
      teto e o PendingTxSignerCommand LOWER_CAP. A barreira da U3 aplica o teto novo na hora (AC2.1.4). Um trabalho a
      cada minuto reenvia os comandos não confirmados com o mesmo commandId; o TxSigner é idempotente pelo requestId
    violation: 422 CAP_ONLY_LOWERED
    source: FR2.3, FR15.2, AC7.2.1, Q8, pendência da U1
  - id: BR4.2
    statement: Bloquear e desbloquear token pela barreira
    category: policy
    applies_to: POST e DELETE /api/v1/admin/real/token-blocks
    trigger: pedido do administrador, com motivo no bloqueio
    logic: Chama M5 blockToken ou unblockToken (U3); vale a partir do registro para todos os bots
    violation: 422 VALIDATION_FAILED
    source: FR6.3, FR15.2, AC7.2.2
  - id: BR5.1
    statement: A visão do administrador nunca mostra chave nem a carteira principal inteira
    category: authorization
    applies_to: GET /api/v1/admin/real/users e /users/{userId}
    trigger: leitura pelo administrador (papel ADMIN, entrada administrativa)
    logic: >
      Monta RealUserView com M1 adminUserView, M2 (saldo), M6 recentOrders (20) e as divergências abertas. A carteira
      principal sai abreviada (4 primeiros … 4 últimos); a carteira do bot sai inteira; nenhuma chave nem referência
      de chave. Lista paginada
    violation: 403 para quem não é ADMIN
    source: FR15.1, AC7.3.1, Q9
  - id: BR6.1
    statement: Anonimização recusada com saldo, posição ou ordem em andamento
    category: validation
    applies_to: anonimização (Privacy)
    trigger: pedido de anonimização
    logic: Chama M1 closeForAnonymization; SE devolver Refused ENTÃO recusa com a explicação de sacar antes
    violation: 409 com BALANCE, POSITION ou ORDER_IN_PROGRESS
    source: FR12.1, AC8.4.1
  - id: BR6.2
    statement: Sem saldo nem posição, a anonimização encerra a carteira e apaga os endereços
    category: policy
    applies_to: anonimização aceita
    trigger: closeForAnonymization Closed
    logic: >
      Na mesma transação: a carteira do bot vira CLOSED; grava o PendingTxSignerCommand DEACTIVATE, cujo payload leva
      só o botWalletId e nunca um endereço (reenviado até o TxSigner confirmar); limpa
      bot_wallet_account.main_wallet_address, consent_challenge.message e, nos saques do usuário,
      withdrawal.signed_transaction, a mensagem e o resumo (a transação serializada contém o destino, que é a carteira
      principal); as liberações do usuário viram REVOKED. Uma migração só de expansão torna anuláveis
      bot_wallet_account.main_wallet_address e bot_wallet_account.public_address (mantendo o UNIQUE para os valores
      presentes), consent_challenge.message e as colunas de transação e mensagem de withdrawal, e faz os CHECKs de
      Base58 aceitarem NULL. Quando o TxSigner confirma o DEACTIVATE, bot_wallet_account.public_address também é
      limpo, porque o endereço do bot se liga à carteira principal na rede pelos depósitos. A trilha de auditoria é só
      de inclusão e guarda o endereço do bot no campo tipado audit_event.bot_wallet_address (U1): a mesma migração cria
      uma função SECURITY DEFINER, no padrão da 0015, que é o único caminho para pôr NULL nesse campo dos eventos do
      usuário anonimizado; ela não apaga evento nem muda outro campo, e grava um evento novo de redação com a contagem
      de linhas. O gatilho que impede UPDATE na trilha continua para todo o resto. As ordens já não
      guardam endereço (U4). O teste obrigatório confere que nenhum dos dois endereços sobra em tabela ou log
    violation: não se aplica
    source: FR12.2, AC8.4.2, team.md (anonimização sem endereço), pendência da U1
  - id: BR7.1
    statement: As decisões dos bots REAL são gravadas como REAL
    category: validation
    applies_to: decision.mode
    trigger: ciclo do bot REAL (U4)
    logic: O CHECK de decision.mode passa a aceitar REAL (migração só de expansão); o ciclo REAL grava REAL; as linhas antigas não mudam
    violation: não se aplica
    source: pendência da U4, Q12
  - id: BR7.2
    statement: Toda ação do administrador é auditada e idempotente
    category: policy
    applies_to: rotas R4 de mudança
    trigger: toda mudança
    logic: >
      Exigem papel ADMIN pela entrada administrativa, Idempotency-Key (mecanismo comum) e motivo onde indicado. Cada
      mudança gera evento de auditoria REAL com o administrador como ator e o usuário afetado; a carteira principal só
      como HMAC
    violation: 403; 400 sem Idempotency-Key
    source: FR11.3, FR15.2
```

## Resumo

- **Liberação:** BR1.1 a BR1.6 cuidam da quarentena, dos portões, do "fundador primeiro" com no máximo 20 convidados, da rede e da revogação.
- **Reconciliação:** BR2.1 a BR2.5 cuidam da reconciliação, da divergência e da correção, do saque limitado e da restauração do banco.
- **Kill switch REAL:** BR3.1 e BR3.2.
- **Teto e bloqueio:** BR4.1 e BR4.2.
- **Visão do administrador:** BR5.1.
- **Anonimização:** BR6.1 e BR6.2.
- **Correção da U4 e auditoria:** BR7.1 corrige o modo das decisões REAL; BR7.2 cuida da auditoria e da idempotência.
