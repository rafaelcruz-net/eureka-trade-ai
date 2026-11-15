# Regras de negócio — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes:
- `inception/requirements-analysis/requirements.md` (FR1.3, FR1.4, FR2.3, FR3.2, FR6.1 a FR6.4, NFR2, NFR6, NFR10);
- `inception/user-stories/stories.md` (US2.1, US6.1, US6.2, US6.3);
- `inception/contract-design/contract-summary.md` (M1, M2, M4, M5);
- `functional-design-questions.md` (Q1 a Q12).

## Regras

```yaml
rules:
  - id: BR1.1
    statement: Só uma carteira liberada, com o REAL ligado e sem pausa recebe ordens do bot
    category: authorization
    applies_to: toda avaliação
    trigger: M5 evaluate
    logic: >
      Lê a carteira pela M1 readRealState. SE o REAL estiver desligado, não houver liberação vigente ou não houver carteira
      do bot ENTÃO recusa. SE houver qualquer pausa (USER, DAILY_LOSS, ADMIN, DIVERGENCE, KILL_SWITCH, UPSTREAM) ENTÃO
      recusa as ordens de origem BOT, compra ou venda. A venda de origem USER_LIQUIDATION ("vender tudo e sacar") passa nas
      pausas USER, DAILY_LOSS, ADMIN e UPSTREAM e é recusada com KILL_SWITCH ou DIVERGENCE
    violation: REAL_PAUSED, com o detalhe NOT_RELEASED, REAL_DISABLED, NO_BOT_WALLET ou PAUSED_<motivo>
    source: FR6.1, FR10.1, FR8.3, team.md (pausa do bot impede novas operações)
  - id: BR1.2
    statement: Com a fonte simulada ativa, nenhuma ordem REAL sai
    category: policy
    applies_to: toda avaliação, compra ou venda
    trigger: M5 evaluate
    logic: SE a M4 activeSourceKind() devolver SIMULATED ENTÃO recusa
    violation: SIMULATED_SOURCE_ACTIVE
    source: FR1.3, AC6.3.2
  - id: BR1.3
    statement: As conferências seguem uma ordem fixa e a primeira recusa encerra
    category: policy
    applies_to: toda avaliação
    trigger: M5 evaluate
    logic: >
      Ordem: BR1.1 estado, BR1.2 fonte, BR2.1 e BR2.2 token, BR3.1 e BR2.4 frescor, BR4.1 e BR4.2 limites,
      BR4.3 perda do dia, BR5.1 teto. Uma venda que passa BR1.1 e BR1.2 é aprovada sem as demais conferências
    violation: o código da primeira regra que recusou
    source: FR6.1
  - id: BR2.1
    statement: Só se compra token com avaliação do scanner disponível
    category: validation
    applies_to: compra
    trigger: M5 evaluate
    logic: >
      SE RiskAssessmentQuery.assess(token) devolver Unavailable (cobertura insuficiente ou outro motivo) ENTÃO recusa. Um
      campo unknown nunca conta como seguro: a avaliação Available já exige cobertura suficiente
    violation: TOKEN_NOT_ELIGIBLE, detalhe NO_ASSESSMENT
    source: FR6.3, AC6.2.1
  - id: BR2.2
    statement: Token na lista de bloqueio não é comprado
    category: validation
    applies_to: compra
    trigger: M5 evaluate
    logic: SE (chain, mint) estiver em real_blocked_token ENTÃO recusa; a venda do mesmo token passa
    violation: TOKEN_NOT_ELIGIBLE, detalhe BLOCKED
    source: FR6.3, FR15.2, AC6.2.3
  - id: BR2.3
    statement: A lista de bloqueio muda só pela M5, com auditoria
    category: authorization
    applies_to: lista de bloqueio
    trigger: M5 blockToken e unblockToken (chamados pela U5)
    logic: >
      blockToken grava (chain, mint, motivo, administrador, horário); repetir o bloqueio do mesmo token não muda nada e
      devolve o registro atual. unblockToken apaga a linha; desbloquear token não bloqueado não faz nada. Cada mudança
      efetiva gera um evento de auditoria REAL com o mint no campo tipado
    violation: motivo vazio ou maior que 500 caracteres é recusado com VALIDATION_FAILED
    source: FR15.2, FR11.3
  - id: BR2.4
    statement: Posição com preço unknown ou vencido bloqueia novas compras
    category: constraint
    applies_to: compra
    trigger: M5 evaluate
    logic: >
      Para cada posição aberta (M2 positions), lê o preço pela M4. SE algum estiver unknown ou com observedAt há mais de
      60 s ENTÃO recusa a compra, de qualquer token. As vendas continuam
    violation: STALE_DATA, detalhe POSITION_PRICE_UNKNOWN, com o mint da posição no detalhe
    source: AC6.2.2
  - id: BR3.1
    statement: A compra exige preço, liquidez e volume do token conhecidos e com até 60 s
    category: validation
    applies_to: compra
    trigger: M5 evaluate
    logic: >
      SE PRICE, LIQUIDITY ou VOLUME_24H do token estiverem unknown ENTÃO recusa com detalhe SIGNAL_UNKNOWN; SE algum tiver
      observedAt há mais de 60 s pelo relógio injetável ENTÃO recusa com detalhe SIGNAL_STALE
    violation: STALE_DATA
    source: FR1.4, AC6.3.3
  - id: BR3.2
    statement: A venda não é recusada por dado vencido; a cotação de 10 s é da U4
    category: policy
    applies_to: venda
    trigger: M5 evaluate
    logic: >
      A U3 não confere frescor na venda. A idade máxima de 10 s da cotação da Jupiter é conferida pela U4 antes de
      assinar (FR5.2), não pela barreira
    violation: não se aplica
    source: FR5.2, AC6.3.3
  - id: BR4.1
    statement: Uma compra não passa de 10% do teto vigente
    category: constraint
    applies_to: compra
    trigger: M5 evaluate
    logic: limite = piso(capLamports × 1000 / 10000); SE inputLamports > limite ENTÃO recusa
    violation: REAL_LIMIT_EXCEEDED, detalhe ORDER_SIZE
    source: FR6.1, AC6.1.1
  - id: BR4.2
    statement: Um bot faz no máximo 20 compras aprovadas por dia UTC
    category: constraint
    applies_to: compra
    trigger: M5 evaluate
    logic: >
      Conta as compras aprovadas do bot desde 00:00 UTC (real_risk_evaluation APPROVED, BUY), inclusive as que depois
      falharam, ficaram ambíguas ou venceram. SE a conta for 20 ou mais ENTÃO recusa. As vendas não contam
    violation: REAL_LIMIT_EXCEEDED, detalhe DAILY_ORDER_COUNT
    source: FR6.1, AC6.1.3
  - id: BR4.3
    statement: Ao chegar a 20% de perda no dia, a carteira pausa até o dia seguinte
    category: calculation
    applies_to: compra; trabalho da perda do dia
    trigger: M5 evaluate de compra e um trabalho a cada minuto para as carteiras liberadas sem pausa DAILY_LOSS
    logic: >
      valorAtual = totalLamports da M2 balance (inclui os lamports reservados por ordens) + valor das posições (BR5.2).
      perda = startValueLamports + depositedLamports − withdrawnLamports − valorAtual, com os fluxos da M2
      flowsSince(snapshot.takenAt), ou seja, só os fluxos depois da foto, para não contar duas vezes o que a foto já
      inclui. base = mínimo(startCapLamports, capLamports atual): uma redução do teto no dia vale na hora e um aumento só
      no dia seguinte, desvio conservador da redação do AC6.1.2 ("teto vigente no início do dia"). SE base = 0 ENTÃO
      a conferência da perda não se aplica (com teto zero, BR4.1 já recusa toda compra, porque o limite por ordem é 0). SE perda > 0 E perda × 10000 ≥
      base × 2000 ENTÃO recusa a compra e pede M1 pauseUser(DAILY_LOSS) na transação do ciclo. Se o ciclo desfizer a
      sua transação, a pausa se perde junto, mas o trabalho F4 (a cada minuto, em transação própria) recalcula a perda e
      pausa a carteira em até 1 min; a compra recusada também não sai, porque a decisão foi desfeita. As taxas já estão no valor atual, porque
      saíram do saldo. Com a pausa DAILY_LOSS, as vendas do bot também param (BR1.1); só a liquidação pedida pelo
      usuário passa
    violation: REAL_LIMIT_EXCEEDED, detalhe DAILY_LOSS; pausa DAILY_LOSS
    source: FR6.1, FR6.2, AC6.1.2
  - id: BR4.4
    statement: A foto do dia é gravada às 00:00 UTC ou na primeira avaliação do dia
    category: policy
    applies_to: DaySnapshot
    trigger: trabalho a cada 5 min (o primeiro depois de 00:00 UTC grava as fotos); primeira avaliação de compra do dia sem foto
    logic: >
      Para cada carteira liberada, grava startValueLamports (mesma conta de valorAtual) e startCapLamports. Se a foto
      ainda não existe na avaliação, ela é gravada com o valor e o teto daquele momento. A foto nunca muda depois de
      gravada; a unicidade (botWalletId, day) resolve a corrida entre o trabalho e a avaliação
    violation: não se aplica
    source: AC6.1.2
  - id: BR4.5
    statement: A pausa por perda do dia termina no dia seguinte
    category: policy
    applies_to: pausa DAILY_LOSS
    trigger: trabalho a cada 5 min (ShedLock), que age a partir de 00:00 UTC
    logic: >
      Pede M1 resumeUser(DAILY_LOSS) para cada carteira pausada por esse motivo num dia UTC anterior ao atual (a data
      vem de RealStateView.pausedSince por motivo); as outras
      pausas ficam como estão. O trabalho é idempotente (resumeUser de carteira não pausada devolve NotPaused) e, por
      rodar a cada 5 min, uma execução perdida é refeita na seguinte
    violation: não se aplica
    source: FR6.2
  - id: BR5.1
    statement: Uma compra só passa se couber no teto e no saldo livre
    category: constraint
    applies_to: compra
    trigger: M5 evaluate
    logic: >
      gestão = valor das posições (BR5.2) + lamports reservados por ordens na M2 (RESERVE com referência "order:", ainda
      sem preenchimento, inclusive as ambíguas; as reservas de saque ficam de fora, porque o saque sai do saldo e não é
      exposição; a M2 balance passa a devolver o total separado por tipo de reserva) + soma das reservas da barreira em OPEN da carteira. Não há contagem dupla: a reserva da
      barreira sai de OPEN no mesmo instante em que o RESERVE entra no ledger (BR6.2). SE gestão + inputLamports >
      capLamports ENTÃO recusa com detalhe CAP. SE inputLamports > freeLamports − feeReserveLamports − reservas OPEN
      ENTÃO recusa com detalhe FREE_BALANCE. O freeLamports da M2 já desconta os RESERVE do ledger
    violation: REAL_CAP_EXCEEDED
    source: FR2.3, FR3.2, AC2.1.3, AC2.2.2
  - id: BR5.2
    statement: Posição vale a preço de cotação, ou a custo se o preço for unknown
    category: calculation
    applies_to: valor das posições
    trigger: BR4.3, BR4.4, BR5.1
    logic: >
      valor = piso(tokenBaseUnits × preço em lamports por unidade base), em decimal exato; SE o preço da M4 estiver
      unknown ENTÃO valor = costLamports. Preço vencido ainda vale para a conta do valor, mas bloqueia compras por BR2.4
    violation: não se aplica
    source: AC2.1.3
  - id: BR5.3
    statement: Teto abaixo da exposição bloqueia só compras
    category: policy
    applies_to: carteira com teto reduzido
    trigger: mudança de teto pelo usuário ou pelo administrador
    logic: Nada é vendido à força; as compras ficam recusadas por BR5.1 até a gestão voltar abaixo do teto; as vendas passam
    violation: REAL_CAP_EXCEEDED nas compras
    source: AC2.1.4
  - id: BR6.1
    statement: A avaliação de compra roda sob trava por carteira do bot
    category: constraint
    applies_to: compra
    trigger: M5 evaluate
    logic: >
      Dentro da transação do ciclo do bot (a mesma em que a U4 grava a decisão pela M6 recordDecision), pega
      a trava de transação da carteira do bot (pg_try_advisory_xact_lock repetido a cada 20 ms por até 1 s, sem mudar
      configuração da sessão; passado 1 s, evaluate lança erro técnico e não aprova, sem abortar a transação no banco)
      antes de ler saldo, reservas e contagens. A trava dura até o fim
      dessa transação, quando a reserva OPEN fica visível para a próxima avaliação. Duas decisões simultâneas da mesma
      carteira são avaliadas uma depois da outra. O consumidor da fila da U4 roda depois, em outra transação, e é nela
      que vem o handOver (BR6.2); ele pega a mesma trava antes do handOver. Uma avaliação repetida com o mesmo
      decisionId (única em real_risk_evaluation) devolve o resultado gravado, sem contar de novo em BR4.2 nem criar outra
      reserva. Isso vale também para uma recusa, de propósito: a decisão recusada não é reavaliada mesmo que as condições
      mudem; o ciclo seguinte do bot gera uma decisão nova
    violation: sem transação ativa, evaluate de compra falha (erro técnico, nunca aprova)
    source: AC6.1.4
  - id: BR6.2
    statement: Compra aprovada vira reserva da barreira até a U4 assumir
    category: constraint
    applies_to: GateReservation
    trigger: aprovação de compra; M5 handOver; trabalho de vencimento
    logic: >
      A aprovação grava a reserva OPEN, com vencimento em 5 min, na mesma transação. A U4 chama M5 handOver(approvalId) na
      mesma transação em que grava o RESERVE no ledger; a reserva passa a HANDED_OVER. handOver de reserva já HANDED_OVER
      não faz nada; handOver de reserva EXPIRED falha com RESERVATION_EXPIRED e a U4 não cria a ordem. Um trabalho a cada
      minuto passa a EXPIRED as reservas OPEN vencidas. A ordem ambígua continua contando pelo RESERVE do ledger
    violation: RESERVATION_EXPIRED para a U4
    source: AC6.1.4, AC6.1.5
  - id: BR6.3
    statement: A barreira usa só dados REAL do próprio tenant
    category: authorization
    applies_to: toda leitura
    trigger: M5 evaluate
    logic: >
      Saldo, posições e fluxos vêm só da M2 (ledger REAL); nenhuma leitura do saldo ou do ledger PAPER. O tenant e o
      usuário vêm do bot ou da autenticação e filtram toda consulta. Um botId de outro tenant é recusado como inexistente
    violation: REAL_PAUSED, detalhe NO_BOT_WALLET
    source: FR6.4, AC6.1.6, NFR2, NFR6
  - id: BR7.1
    statement: Toda avaliação é registrada; toda recusa é auditada e contada
    category: policy
    applies_to: RealRiskEvaluation
    trigger: fim de cada evaluate
    logic: >
      Grava a avaliação em real_risk_evaluation. Cada recusa gera evento de auditoria REAL (código, detalhe, mint no campo
      tipado) e incrementa real_risk_evaluations_total{outcome="REJECTED",code,detail}, sem mint nem endereço nos
      rótulos. Avaliação, evento e reserva são gravados na transação do ciclo, pelo mesmo padrão da barreira PAPER (a
      saída de auditoria com propagação MANDATORY): se o ciclo desfizer a transação, a decisão não existiu e o registro
      some com ela. A venda pedida pelo usuário (USER_LIQUIDATION) roda na transação da própria requisição. A retenção
      está nos requisitos não funcionais da U3
    violation: não se aplica
    source: FR11.3, NFR5
  - id: BR7.2
    statement: Dinheiro em inteiros e arredondamento contra a compra
    category: calculation
    applies_to: todas as contas
    trigger: qualquer cálculo
    logic: >
      Lamports e unidades base em inteiros; preços em decimal exato; percentuais em pontos-base. Limites arredondados para
      baixo, valores de posição para baixo. Nunca ponto flutuante
    violation: não se aplica
    source: NFR10
  - id: BR7.3
    statement: As leituras da carteira e do ledger entram pelas interfaces públicas
    category: policy
    applies_to: botwallet.api e ledger.api
    trigger: Code Generation da U3
    logic: >
      A U3 acrescenta em botwallet.api a leitura readRealState e os comandos pauseUser e resumeUser (M1), e em ledger.api
      positions e flowsSince (M2), mudando os módulos donos no mesmo commit; a U3 nunca lê as tabelas deles
    violation: regra ArchUnit de fronteira de módulo
    source: contract-summary (M1, M2), team.md (fronteiras de módulos)
```

## Resumo

- **Estado e fonte:** BR1.1 a BR1.3 decidem se a carteira pode operar e em que ordem as conferências rodam.
- **Tokens e frescor:** BR2.1 a BR3.2 só deixam comprar tokens avaliados, fora da lista de bloqueio e com dados recentes.
- **Limites:** BR4.1 a BR4.5 cuidam do tamanho da ordem, do número de compras e da perda do dia.
- **Teto:** BR5.1 a BR5.3.
- **Trava e reserva:** BR6.1 a BR6.3, para decisões simultâneas.
- **Registro e unidades:** BR7.1 a BR7.3.

Vendas passam pelo estado e pela fonte e por nada mais. É o que permite sair de uma posição mesmo com limite, teto ou dado vencido.
