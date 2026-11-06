# Regras de negócio — U8 Administração e feedback

Fonte de verdade das regras da Unit `u8-admin-feedback`. As entidades estão em [entities.md](entities.md). "Agora" vem sempre do relógio injetável, e todo instante é UTC.

```yaml
rules:
  # BR1 — Entrada do administrador (AC1.2.6, Q1)
  - id: BR1.1
    statement: Só as carteiras de ADMIN_WALLET_ADDRESSES recebem desafio válido na entrada administrativa.
    category: authorization
    applies_to: POST /api/v1/admin/auth/challenge
    trigger: pedido de desafio
    logic: "SE o endereço não está na lista configurada ENTÃO 403 FORBIDDEN_ROLE e evento LOGIN_REJECTED com reason NOT_ADMIN_WALLET (endereço mascarado no log). SENÃO criar desafio com nonce de uso único e validade curta, como na entrada do convidado. Uma carteira de administrador nunca entra pela rota do convidado (já recusada pela U1)."
    violation: 403 FORBIDDEN_ROLE, auditado
    source: AC1.2.6, FR2.2
  - id: BR1.2
    statement: A verificação da assinatura abre a sessão de administrador sem convite.
    category: authorization
    applies_to: POST /api/v1/admin/auth/verify
    trigger: assinatura enviada
    logic: "Conferir nonce (uso único, não vencido), assinatura Ed25519 e que o endereço ainda está na lista. SE a carteira já está vinculada a uma conta BETA_USER ENTÃO 403 FORBIDDEN_ROLE com reason WALLET_IS_GUEST e LOGIN_REJECTED, sem converter nem alterar a conta. Na primeira entrada da carteira, criar a AdminAccount (papel ADMIN, tenant do sistema) na mesma transação. Abrir AdminSession com token de 15 minutos e sessão de 8 horas; gravar ADMIN_LOGIN. Nonce reutilizado, vencido ou assinatura inválida: 401 e LOGIN_REJECTED. A assinatura de entrada não autoriza nenhuma outra ação."
    violation: 401 UNAUTHENTICATED ou 403 FORBIDDEN_ROLE, auditado
    source: AC1.2.6, FR2.2
  - id: BR1.3
    statement: A renovação usa cookie próprio e nunca prorroga a sessão.
    category: policy
    applies_to: POST /api/v1/admin/auth/refresh
    trigger: token de acesso perto de vencer
    logic: "Cookie HttpOnly, Secure, SameSite=Strict, Path=/api/v1/admin/auth/refresh. Por causa do SameSite=Strict, o app do administrador fica no mesmo site da API e do app do convidado (no ambiente local, localhost na porta 3001). Rotação a cada uso e recusa de cookie já usado (encerra a sessão). O novo token vence em min(agora + 15 minutos, sessionExpiresAt)."
    violation: 401 UNAUTHENTICATED
    source: AC1.2.6, Q1
  - id: BR1.4
    statement: Carteira retirada da configuração perde o acesso na próxima renovação.
    category: authorization
    applies_to: renovação e verificação
    trigger: endereço ausente de ADMIN_WALLET_ADDRESSES
    logic: "SE o endereço da conta não está mais na lista ENTÃO revogar a sessão, responder 401 e gravar ADMIN_SESSION_REVOKED com reason NOT_ADMIN_WALLET. A conta não é apagada."
    violation: 401 UNAUTHENTICATED
    source: FR2.2
  - id: BR1.5
    statement: Cada token só vale na sua área.
    category: authorization
    applies_to: toda rota da API
    trigger: requisição autenticada
    logic: "Token ADMIN fora de /api/v1/admin/... ou token BETA_USER em /api/v1/admin/... (menos as rotas de entrada): 403 FORBIDDEN_ROLE e o evento REQUEST_FORBIDDEN que a U1/U3 já gravam (um único evento por recusa; a U8 não cria evento próprio). O caminho é normalizado antes da comparação."
    violation: 403 FORBIDDEN_ROLE, auditado
    source: AC7.1.4, AC7.2.5, AC7.4.2
  - id: BR1.6
    statement: Sair encerra a sessão do administrador.
    category: policy
    applies_to: POST /api/v1/admin/auth/logout
    trigger: pedido de saída
    logic: "Revogar a sessão, apagar o cookie de renovação e gravar ADMIN_LOGOUT. Repetir é idempotente."
    violation: nenhuma
    source: AC1.2.6

  # BR2 — Kill switch (US7.2, Q2-Q4)
  - id: BR2.1
    statement: Acionar o kill switch grava o estado antes de pausar os bots.
    category: policy
    applies_to: PUT /api/v1/admin/kill-switch com state ON
    trigger: administrador confirma o acionamento
    logic: "Numa transação: travar KillSwitchState; SE state = OFF ENTÃO state = ON, changedBy, changedAt, reason, version + 1, gravar KillSwitchChange e o evento KILL_SWITCH_ON (ator o administrador, sem tenant, com o motivo). Confirmar. Depois chamar BotAdministration.applySystemPause (que grava a cópia da U5 antes dos lotes) e seguir a BR2.3."
    violation: nenhuma
    source: FR3.2, FR3.3, AC7.2.2, AC7.2.5
  - id: BR2.2
    statement: Desligar o kill switch retira a pausa de sistema sem retomar bots.
    category: policy
    applies_to: PUT /api/v1/admin/kill-switch com state OFF
    trigger: administrador confirma o desligamento
    logic: "Numa transação: travar KillSwitchState; SE state = ON ENTÃO state = OFF, pendingBots = 0, gravar KillSwitchChange e KILL_SWITCH_OFF. Confirmar. Depois chamar BotAdministration.liftSystemPause e seguir a BR2.3. Nenhum bot é retomado; o dono vê o motivo ADMIN_RELEASED (U5)."
    violation: nenhuma
    source: FR3.2, AC7.2.4, AC7.2.5
  - id: BR2.3
    statement: Falhas parciais são repetidas até zerar.
    category: policy
    applies_to: aplicação e retirada da pausa
    trigger: SystemPauseResult.failed não vazio
    logic: "Repetir a chamada até 3 vezes na mesma requisição. SE ainda houver falha ENTÃO gravar pendingBots = quantidade de falhas e responder 200 com o estado e pendingBots. SE a chamada lançar exceção (e não só devolver failed) ENTÃO gravar reconcileNeeded = true e responder 200 com o estado e reconcileNeeded. O trabalho da BR2.5 continua. SENÃO pendingBots = 0 e reconcileNeeded = false."
    violation: nenhuma; a tela mostra "Pausa pendente em N bots" ou "Pausa ainda sendo aplicada"
    source: FR3.2, Q3
  - id: BR2.4
    statement: Repetir o mesmo estado é idempotente.
    category: validation
    applies_to: PUT /api/v1/admin/kill-switch
    trigger: state pedido igual ao atual
    logic: "Não gravar KillSwitchChange nem evento do administrador; devolver o estado atual. SE state = ON ENTÃO chamar applySystemPause de novo e seguir a BR2.3, para corrigir pendências. A mesma Idempotency-Key devolve a mesma resposta."
    violation: nenhuma
    source: Q4
  - id: BR2.5
    statement: Um trabalho reconcilia a U5 com o estado do kill switch.
    category: policy
    applies_to: trabalho de reconciliação do kill switch (processo de API)
    trigger: a cada 30 s, sempre (não só com pendingBots > 0)
    logic: "Tomar a trava de aplicação do kill switch (BR2.8). Ler state e version. SE pendingBots > 0 OU reconcileNeeded OU BotAdministration.systemPaused() diferente de (state = ON) ENTÃO chamar applySystemPause (ON) ou liftSystemPause (OFF) e gravar pendingBots e reconcileNeeded só se a version não mudou desde a leitura. Isso cobre a queda do processo entre a confirmação do estado e a chamada à U5, e uma exceção dessa chamada."
    violation: falha técnica registrada e repetida na próxima execução
    source: FR3.2, Q3
  - id: BR2.6
    statement: Motivo obrigatório e limitado.
    category: validation
    applies_to: PUT /api/v1/admin/kill-switch
    trigger: corpo recebido
    logic: "SE reason vazio depois de tirar espaços OU com mais de 500 caracteres OU state fora de ON/OFF ENTÃO 400 VALIDATION_FAILED com o campo."
    violation: 400 VALIDATION_FAILED
    source: FR3.2, C8
  - id: BR2.7
    statement: A consulta do kill switch traz o número para a confirmação.
    category: calculation
    applies_to: GET /api/v1/admin/kill-switch
    trigger: abrir a tela ou o diálogo
    logic: "Devolver state, changedBy (endereço mascarado do administrador), changedAt, reason, pendingBots e activeBotsToPause = contagem ACTIVE de BotAdministration.countByState()."
    violation: nenhuma
    source: AC7.2.1
  - id: BR2.8
    statement: Mudanças concorrentes são serializadas.
    category: constraint
    applies_to: kill switch
    trigger: dois pedidos ao mesmo tempo
    logic: "Dois níveis de ordem. (1) A trava da linha KillSwitchState ordena a gravação do estado; cada pedido vê o estado gravado pelo anterior e segue a BR2.1, a BR2.2 ou a BR2.4. (2) Toda chamada a applySystemPause ou liftSystemPause, das requisições e do trabalho da BR2.5, acontece sob uma trava distribuída única de aplicação do kill switch, mantida durante a chamada. Sob essa trava, o chamador relê state e version e chama a operação do estado ATUAL, não do estado que ele gravou. Assim, um OFF confirmado depois de um ON nunca é desfeito por um apply atrasado, e vice-versa."
    violation: nenhuma
    source: FR3.2
  - id: BR2.9
    statement: O histórico do kill switch é só de acréscimo.
    category: constraint
    applies_to: KillSwitchChange
    trigger: qualquer escrita
    logic: "UPDATE ou DELETE em KillSwitchChange falha no banco."
    violation: erro do banco
    source: FR3.3, Q2

  # BR3 — Estado do sistema (US7.4, Q5)
  - id: BR3.1
    statement: O estado do sistema tem só contagens e estado das fontes.
    category: authorization
    applies_to: GET /api/v1/admin/system-status
    trigger: abrir a tela
    logic: "Montar guestCount e inviteCounts (IdentityAdministration.counts), botCountsByState (BotAdministration.countByState, com CLOSING_PENDING), killSwitch (KillSwitchState), sources (estado e última atualização por fonte, interface de dados de mercado), auditOutboxPending (trilha) e rejectedOrdersToday (BR3.2). Nenhum identificador de tenant, pessoa, bot, carteira, ordem ou lançamento."
    violation: nenhuma
    source: AC7.4.1, FR2.2
  - id: BR3.2
    statement: Ordens recusadas hoje é uma contagem global desde 00:00 UTC.
    category: calculation
    applies_to: rejectedOrdersToday
    trigger: montagem do estado do sistema
    logic: "Pedir à U6 o total de ordens recusadas de todos os tenants com horário >= 00:00 UTC de hoje. A U6 devolve só o número."
    violation: falha da fonte deixa o campo como unknown, nunca zero
    source: AC7.4.1, Q5
  - id: BR3.3
    statement: Falha de uma parte não derruba o estado do sistema.
    category: policy
    applies_to: GET /api/v1/admin/system-status
    trigger: uma interface falha
    logic: "A parte que falhou volta como unknown com o motivo; as outras aparecem. Contagem ausente nunca vira zero."
    violation: nenhuma
    source: AC7.4.1

  # BR4 — Fronteira da administração (AC7.4.2, FR3.3)
  - id: BR4.1
    statement: O administrador não age sobre o bot de uma pessoa nem lê ledger ou operações.
    category: authorization
    applies_to: API inteira
    trigger: token ADMIN em rota de bot, ledger, P&L, operações ou carteira
    logic: "Essas rotas são do convidado; pela BR1.5 o token ADMIN recebe 403 FORBIDDEN_ROLE, auditado. A API administrativa não tem rota equivalente. O kill switch é a única ação sobre bots."
    violation: 403 FORBIDDEN_ROLE, auditado
    source: AC7.4.2, FR2.2
  - id: BR4.2
    statement: Toda ação administrativa gera um evento com o administrador como ator.
    category: policy
    applies_to: entrada, saída, convites, kill switch
    trigger: ação concluída
    logic: "Um evento por ação, na mesma transação, com ator ADMIN, correlationId e sem segredo, nonce, assinatura ou código de convite. Recusas de acesso geram o REQUEST_FORBIDDEN da BR1.5. O expurgo do feedback não é ação do administrador: o evento dele tem ator system (BR6.7)."
    violation: falha ao gravar o evento desfaz a ação
    source: FR3.3, AC7.2.5

  # BR5 — Telas de convite (US7.1)
  - id: BR5.1
    statement: O formulário de convite escolhe entre os tenants ativos.
    category: validation
    applies_to: tela de emissão e GET /api/v1/admin/tenants
    trigger: abrir o formulário
    logic: "Listar IdentityAdministration.activeTenants. Validade padrão de 7 dias, aceita de 1 hora a 30 dias. Emitir com Idempotency-Key gerada ao abrir o formulário. O código completo aparece uma única vez, com o botão de copiar o link; depois só o sufixo."
    violation: as recusas da U3 (422, 409) aparecem no campo
    source: AC7.1.1
  - id: BR5.2
    statement: A lista mostra o estado em texto.
    category: policy
    applies_to: tela de convites
    trigger: abrir a lista
    logic: "Mostrar PENDENTE, USADO, EXPIRADO ou REVOGADO conforme o estado da U3 (um pendente vencido já vem EXPIRED), a validade e o sufixo do código. Paginação por cursor."
    violation: nenhuma
    source: AC7.1.2
  - id: BR5.3
    statement: Revogar pede confirmação.
    category: policy
    applies_to: ação Revogar
    trigger: clique em Revogar
    logic: "Abrir diálogo 'Revogar este convite?'; só chamar a rota depois de confirmar. 409 INVITE_NOT_REVOCABLE mostra o motivo e atualiza a linha."
    violation: nenhuma
    source: AC7.1.3

  # BR6 — Feedback (US6.1, Q6-Q8)
  - id: BR6.1
    statement: O feedback tem de 1 a 2.000 caracteres e tela de origem válida.
    category: validation
    applies_to: POST /api/v1/feedback
    trigger: envio
    logic: "SE text vazio depois de tirar espaços OU com mais de 2.000 pontos de código OU screen fora de S1 a S9 ENTÃO 400 VALIDATION_FAILED com o campo. O texto é guardado como enviado."
    violation: 400 VALIDATION_FAILED; a tela preserva o texto
    source: AC6.1.3, FR13.1
  - id: BR6.2
    statement: Tenant e pessoa vêm do token.
    category: authorization
    applies_to: POST /api/v1/feedback
    trigger: envio
    logic: "Só token BETA_USER envia (ADMIN recebe 403 pela BR1.5). tenantId e userId do corpo são ignorados."
    violation: 403 FORBIDDEN_ROLE
    source: AC6.1.2
  - id: BR6.3
    statement: Até 10 envios por pessoa por hora.
    category: constraint
    applies_to: POST /api/v1/feedback
    trigger: envio
    logic: "Na transação do envio, travar a linha da pessoa (serializa envios simultâneos da mesma pessoa) e contar. SE a pessoa já tem 10 FeedbackEntry com createdAt > agora - 1 hora ENTÃO 429 RATE_LIMITED com Retry-After. A repetição com a mesma Idempotency-Key de um 201 devolve a resposta guardada e não conta. Respostas 400 e 429 não ficam guardadas pela idempotência: o reenvio com a mesma chave depois da janela é processado de novo."
    violation: 429 RATE_LIMITED; a tela preserva o texto
    source: Q6
  - id: BR6.4
    statement: O envio é deduplicado pela chave de idempotência.
    category: constraint
    applies_to: POST /api/v1/feedback
    trigger: reenvio
    logic: "Usar o mecanismo comum de idempotência da identidade: mesma chave e mesmo corpo devolvem o mesmo 201; corpo diferente 409 IDEMPOTENCY_CONFLICT."
    violation: 409 IDEMPOTENCY_CONFLICT
    source: AC6.1.2
  - id: BR6.5
    statement: Só o administrador lê o feedback.
    category: authorization
    applies_to: GET /api/v1/admin/feedback
    trigger: abrir a lista
    logic: "Não existe rota do convidado que leia feedback. A lista vem do mais novo para o mais antigo, por cursor."
    violation: 403 FORBIDDEN_ROLE para outro papel
    source: AC6.1.2
  - id: BR6.6
    statement: A lista do administrador não identifica a pessoa e mostra o texto puro.
    category: policy
    applies_to: GET /api/v1/admin/feedback e tela de feedback
    trigger: abrir a lista
    logic: "Cada item traz feedbackId, tenantId, nome do tenant, tela, texto e horário; nunca userId nem carteira. A tela mostra o texto como texto, sem interpretar HTML ou Markdown."
    violation: nenhuma
    source: AC6.1.4, Q8
  - id: BR6.7
    statement: Feedback com mais de 180 dias é apagado.
    category: policy
    applies_to: trabalho diário de expurgo (processo de API)
    trigger: uma vez por dia
    logic: "Apagar FeedbackEntry com createdAt < agora - 180 dias, em lotes, e gravar um evento FEEDBACK_PURGED (ator system) com a quantidade. Uma execução por vez no cluster."
    violation: falha registrada e repetida no dia seguinte
    source: Q8

  # BR7 — Telas (AC6.1.1, AC7.2.1, CT-UX)
  - id: BR7.1
    statement: Com o kill switch ligado, toda tela administrativa mostra "Kill switch ACIONADO".
    category: policy
    applies_to: app do administrador
    trigger: state = ON
    logic: "Faixa no topo com texto e ícone, não só cor. Com pendingBots > 0, também 'Pausa pendente em N bots'."
    violation: nenhuma
    source: AC7.2.1
  - id: BR7.2
    statement: Acionar e desligar pedem confirmação com o número de bots.
    category: policy
    applies_to: tela do kill switch
    trigger: clique em Acionar ou Desligar
    logic: "O diálogo de acionar diz 'N bots ativos serão pausados' (activeBotsToPause lido ao abrir) e pede o motivo. O de desligar diz que os bots continuam pausados até cada dono retomar. Só chama a rota depois de confirmar."
    violation: nenhuma
    source: AC7.2.1, AC7.2.4
  - id: BR7.3
    statement: O diálogo de feedback não tira a pessoa da tela e preserva o texto.
    category: policy
    applies_to: app do convidado
    trigger: abrir, enviar ou falhar
    logic: "O item 'Enviar feedback' fica no cabeçalho atual (Q7). A tela de origem vem da rota aberta. Ao enviar com sucesso, o diálogo fecha e o foco volta ao item que o abriu. Em erro (400, 429, 503 ou rede), o motivo aparece e o texto fica no campo. Contador '0 / 2.000'."
    violation: nenhuma
    source: AC6.1.1, AC6.1.3
  - id: BR7.4
    statement: Quem não é administrador vê "Acesso restrito".
    category: authorization
    applies_to: app do administrador
    trigger: 403 FORBIDDEN_ROLE na entrada ou numa rota
    logic: "Mostrar 'Acesso restrito' sem dados e oferecer sair. O evento já foi gravado pela API."
    violation: nenhuma
    source: AC7.1.4, AC7.2.5
```

## Resumo

| Grupo | Regras | O que garantem |
|---|---|---|
| BR1 Entrada | BR1.1–BR1.6 | Só as carteiras configuradas entram, com sessão curta e cookie próprio, e cada token só vale na sua área |
| BR2 Kill switch | BR2.1–BR2.9 | O estado é gravado antes da pausa, as falhas são repetidas até zerar, repetir é idempotente, desligar não retoma bots e o histórico só cresce |
| BR3 Estado do sistema | BR3.1–BR3.3 | Só contagens, incluindo recusas de hoje; uma parte que falha aparece como `unknown`, nunca zero |
| BR4 Fronteira | BR4.1–BR4.2 | Nenhuma ação sobre o bot de uma pessoa nem leitura de ledger; toda ação administrativa é auditada |
| BR5 Convites | BR5.1–BR5.3 | Tenant escolhido entre os ativos, estado em texto, revogação com confirmação |
| BR6 Feedback | BR6.1–BR6.7 | Validação, tenant do token, limite por hora, idempotência, leitura só pelo administrador, sem identificar a pessoa, expurgo em 180 dias |
| BR7 Telas | BR7.1–BR7.4 | Faixa do kill switch, confirmações, diálogo que preserva o texto, "Acesso restrito" |
