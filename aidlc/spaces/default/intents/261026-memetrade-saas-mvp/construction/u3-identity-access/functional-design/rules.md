# Regras de negócio — U3 Convites, contas e sessões

Regras do IdentityAccess completo. As fontes são:
- os requisitos FR1.1, FR1.2, FR1.5, FR1.6, FR3.1, FR3.3, FR11.3 e FR15.2 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- as histórias US7.1, US1.1, US1.3 e US1.5 de [stories.md](../../../inception/user-stories/stories.md);
- os contratos C1, C3, C8 e C17 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- as respostas Q1 a Q10 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. As regras da U1 (login, sessão, isolamento e auditoria mínima) continuam valendo e aparecem aqui só quando a U3 as estende.

```yaml
rules:
  # BR1 - Convites pelo administrador
  - id: BR1.1
    statement: Um convite é emitido para um tenant ativo, com validade entre 1 hora e 30 dias, como pendente e de uso único.
    category: validation
    applies_to: Invite
    trigger: IdentityAdministration.issue (POST /api/v1/admin/invites)
    logic: "SE o tenant não existe ou não está ACTIVE ENTÃO 422 VALIDATION_FAILED no campo tenantId. SE expiresAt < agora + 1 hora OU expiresAt > agora + 30 dias ENTÃO 422 VALIDATION_FAILED no campo expiresAt. SENÃO gerar um código aleatório de 256 bits em base64url (43 caracteres), guardar só codeDigest e codeSuffix, status PENDING, gravar INVITE_ISSUED com o administrador como ator. 'agora' vem do relógio injetável."
    violation: 422 com o campo; nenhum convite é criado
    source: FR3.1, FR3.3, AC7.1.1, Q1, Q2, Q3

  - id: BR1.2
    statement: O estado exibido de um convite é derivado na leitura; um pendente vale até expiresAt, inclusive.
    category: calculation
    applies_to: Invite
    trigger: toda leitura ou uso de convite
    logic: "SE status = PENDING E agora <= expiresAt ENTÃO PENDING. SE status = PENDING E agora > expiresAt ENTÃO EXPIRED. SENÃO o status guardado (USED ou REVOKED)."
    violation: não se aplica (cálculo)
    source: FR1.1, AC1.1.2, AC7.1.2

  - id: BR1.3
    statement: A lista de convites mostra estado em texto, validade e o final do código, nunca o código.
    category: policy
    applies_to: IdentityAdministration.list (GET /api/v1/admin/invites)
    trigger: consulta da lista pelo administrador
    logic: "Ordem por createdAt decrescente e desempate por inviteId; paginação por cursor. Cada item traz inviteId, tenantId, status derivado (BR1.2), expiresAt, createdAt, usedAt, revokedAt e codeSuffix. O código completo aparece só na resposta 201 da emissão."
    violation: não se aplica
    source: FR3.1, AC7.1.2, Q2

  - id: BR1.4
    statement: Só um convite pendente e ainda válido pode ser revogado; revogar de novo um revogado devolve o mesmo convite.
    category: constraint
    applies_to: Invite
    trigger: IdentityAdministration.revoke (POST /api/v1/admin/invites/{inviteId}/revoke)
    logic: "Bloquear a linha do convite. SE estado derivado = PENDING ENTÃO status REVOKED, revokedAt = agora e gravar INVITE_REVOKED. SE status = REVOKED ENTÃO devolver o convite sem novo evento. SE USED ou EXPIRED ENTÃO 409 INVITE_NOT_REVOCABLE. Convite inexistente: 404. A confirmação antes de revogar é da tela da U8."
    violation: 409 INVITE_NOT_REVOCABLE ou 404
    source: FR3.1, FR3.3, AC7.1.3, Q7

  - id: BR1.5
    statement: As rotas e a interface de convites são só do administrador.
    category: authorization
    applies_to: rotas /api/v1/admin/invites, IdentityAdministration
    trigger: toda chamada
    logic: "SE o token não é de administrador ENTÃO 403 FORBIDDEN_ROLE e evento REQUEST_FORBIDDEN, pelo filtro de rotas administrativas da U1. A interface IdentityAdministration recebe o administrador autenticado e nunca é exposta às rotas do convidado."
    violation: 403 FORBIDDEN_ROLE auditado
    source: FR3.3, AC7.1.4

  - id: BR1.6
    statement: As contagens de identidade trazem só números, sem identificar pessoas.
    category: policy
    applies_to: IdentityCounts
    trigger: IdentityAdministration.counts (estado do sistema, U8)
    logic: "guestCount = pessoas BETA_USER ACTIVE; inviteCounts = convites por estado derivado (BR1.2), com as quatro chaves sempre presentes, inclusive com zero."
    violation: não se aplica
    source: C17, AC7.4.1

  - id: BR1.7
    statement: O tenant de nome Beta é semeado pela migração e não há cadastro de tenant no beta.
    category: policy
    applies_to: Tenant
    trigger: migração da U3
    logic: "A migração insere o tenant Beta com status ACTIVE e identificador fixo, uma única vez (repetir a migração não duplica). Nenhuma rota cria, edita ou suspende tenant."
    violation: não se aplica
    source: ADR-002, Q1

  - id: BR1.8
    statement: A repetição da emissão com a mesma chave de idempotência devolve o mesmo convite sem o código.
    category: policy
    applies_to: IdentityAdministration.issue, registro de idempotência
    trigger: repetição de POST /api/v1/admin/invites com a mesma chave e o mesmo corpo
    logic: "A resposta guardada pelo mecanismo comum de idempotência é a do convite sem o campo code, com codeSuffix. A repetição devolve essa resposta; o código não pode ser recomposto a partir do resumo. Quem perdeu o código revoga o convite e emite outro."
    violation: não se aplica
    source: Q2, BR2.3, mecanismo comum de idempotência da U1

  # BR2 - Consulta do convite pelo convidado
  - id: BR2.1
    statement: A consulta do convite, sem sessão, devolve o estado derivado, a validade e os termos vigentes; código desconhecido responde 404.
    category: validation
    applies_to: GET /api/v1/invites/{code}
    trigger: abertura do link do convite
    logic: "Procurar pelo resumo do código. SE não existe ENTÃO 404 INVITE_NOT_FOUND com mensagem genérica. SENÃO 200 com status (BR1.2), expiresAt, termsVersion, termsText e riskNoticeText da versão vigente. A resposta nunca traz tenant, inviteId nem dados de outra pessoa."
    violation: 404 INVITE_NOT_FOUND
    source: FR1.1, FR1.2, AC1.1.1, Q5

  - id: BR2.2
    statement: A consulta do convite tem limite de 10 por minuto por IP.
    category: constraint
    applies_to: GET /api/v1/invites/{code}
    trigger: toda consulta
    logic: "Contador por IP em janela de 1 minuto, separado do contador da entrada. SE passou de 10 ENTÃO 429 RATE_LIMITED com Retry-After, sem dizer se o código existe."
    violation: 429 RATE_LIMITED
    source: Q5

  - id: BR2.3
    statement: O código do convite nunca é guardado, registrado em log, na trilha ou em métrica.
    category: policy
    applies_to: Invite, logs, AuditTrail, métricas
    trigger: emissão, consulta e uso
    logic: "Comparação só pelo resumo. O registro de idempotência da emissão também não guarda o código (BR1.8). Logs e trilha usam o modelo da rota (/api/v1/invites/{code}) e o inviteId. Com 43 caracteres, o código também cai na redação de segmentos longos da U1. A página do convite no app responde com Referrer-Policy no-referrer."
    violation: não se aplica (proibição de projeto)
    source: Q2, Q5, PRD §12

  # BR3 - Criação da conta na verificação com convite
  - id: BR3.1
    statement: Sem o aceite da versão vigente dos termos, a tela não deixa conectar a carteira e a API recusa o vínculo.
    category: validation
    applies_to: tela de entrada com convite, POST /api/v1/auth/verify
    trigger: verificação com inviteCode
    logic: "Na tela, 'Conectar carteira' fica indisponível, com o motivo escrito, até as duas marcações (termos e aviso de risco). Na API, como última conferência da BR3.2 (depois das conferências do convite, do papel, da carteira e do tenant), SE inviteCode presente E (acceptedTermsVersion ausente OU diferente da versão vigente) ENTÃO 422 TERMS_NOT_ACCEPTED."
    violation: 422 TERMS_NOT_ACCEPTED; nenhuma conta é criada
    source: FR1.2, AC1.1.1, Q6

  - id: BR3.2
    statement: Depois da assinatura válida, a verificação com convite confere, nesta ordem, convite, papel, carteira, tenant e termos, e recusa com o motivo da primeira conferência que falhar.
    category: validation
    applies_to: POST /api/v1/auth/verify com inviteCode
    trigger: verificação com assinatura aceita pelas regras de login da U1
    logic: "Dentro da transação, com a linha do convite bloqueada: (1) código desconhecido: 404 INVITE_NOT_FOUND; (2) estado derivado EXPIRED, REVOKED ou USED: 422 INVITE_INVALID com reason igual ao estado; (3) carteira de administrador: 403 FORBIDDEN_ROLE; (4) carteira já vinculada: 409 WALLET_ALREADY_LINKED (BR3.7); (5) tenant do convite não ACTIVE: 422 INVITE_INVALID com reason TENANT_INACTIVE; (6) termos: 422 TERMS_NOT_ACCEPTED (BR3.1). Os termos vêm por último porque só fazem sentido para um convite utilizável. O estado é lido na própria transação, então uma revogação feita durante o aceite já vale (AC7.1.3)."
    violation: a recusa correspondente; nenhuma conta é criada e o convite não muda
    source: FR1.1, AC1.1.2, AC7.1.3, Q4

  - id: BR3.3
    statement: A criação da conta acontece numa única operação.
    category: constraint
    applies_to: User, Wallet, Invite, TermsAcceptance, Session
    trigger: verificação com convite aprovada pela BR3.2
    logic: "Numa só transação: criar User (tenant do convite, BETA_USER, ACTIVE); criar Wallet (endereço e provedor); Invite para USED com usedAt e usedByUserId; criar TermsAcceptance (versão, acceptedAt, inviteId); gravar ACCOUNT_CREATED na saída da trilha; abrir a sessão como na U1. Qualquer falha desfaz tudo."
    violation: falha técnica desfaz tudo e responde 5xx; nenhum estado parcial
    source: FR1.1, FR1.2, AC1.1.3

  - id: BR3.4
    statement: Dois usos simultâneos do mesmo convite criam uma única conta.
    category: constraint
    applies_to: Invite
    trigger: verificações concorrentes com o mesmo inviteCode
    logic: "A linha do convite é bloqueada antes da leitura do estado. A primeira transação passa o convite para USED; a segunda, ao obter o bloqueio, lê USED e recebe 422 INVITE_INVALID com reason USED."
    violation: 422 INVITE_INVALID, reason USED
    source: AC1.1.4, Q4

  - id: BR3.5
    statement: A criação da conta grava um evento com a versão dos termos, o horário e o convite como referência.
    category: policy
    applies_to: AuditTrail
    trigger: BR3.3
    logic: "Evento ACCOUNT_CREATED com ator a nova pessoa, tenant, termsVersion, acceptedAt e referência ao inviteId; endereço da carteira mascarado como na U1. Uma recusa da BR3.2 grava um único LOGIN_REJECTED com o motivo (regra de recusas da U1), sem o código."
    violation: não se aplica
    source: FR1.2, FR3.3, AC1.1.3

  - id: BR3.6
    statement: O desafio de login usado numa verificação recusada não volta a valer; numa falha técnica nada é consumido.
    category: policy
    applies_to: LoginChallenge
    trigger: recusa da BR3.1, da BR3.2 ou da BR3.7, ou falha técnica na verificação com convite
    logic: "A verificação com convite roda numa única transação, como na U1. Numa recusa, o desafio CONSUMED e um único LOGIN_REJECTED são confirmados na mesma transação antes da resposta (regra de recusas da U1); quando a recusa vem de uma violação de unicidade que desfaz a transação, os dois são gravados numa nova transação antes da resposta. Numa falha técnica (5xx) a transação inteira é desfeita, inclusive o consumo do desafio, que continua sujeito ao próprio prazo. Para tentar de novo, a tela sempre pede um novo desafio."
    violation: não se aplica
    source: FR1.3

  - id: BR3.7
    statement: Uma carteira que já tem conta não usa outro convite e entra pela entrada normal.
    category: validation
    applies_to: POST /api/v1/auth/verify
    trigger: verificação com inviteCode de carteira já vinculada
    logic: "Recusar com 409 WALLET_ALREADY_LINKED; o convite continua PENDING e pode ser usado por outra carteira. Vale também quando duas verificações da mesma carteira com convites diferentes passam juntas pela conferência: a unicidade de Wallet.address é a garantia final, e a violação dela desfaz a transação e responde 409 WALLET_ALREADY_LINKED, nunca INVITE_INVALID."
    violation: 409 WALLET_ALREADY_LINKED
    source: FR1.1, AC1.1.5

  - id: BR3.8
    statement: A verificação sem convite segue as regras de login da U1, sem mudança.
    category: policy
    applies_to: POST /api/v1/auth/verify sem inviteCode
    trigger: verificação sem inviteCode
    logic: "Carteira sem conta continua recusada como na U1. acceptedTermsVersion enviado sem inviteCode é ignorado."
    violation: a recusa da U1
    source: FR1.3, US1.2

  - id: BR3.9
    statement: Da carteira, só o endereço público e o provedor ficam guardados.
    category: policy
    applies_to: Wallet
    trigger: BR3.3
    logic: "Guardar address e provider; nenhum campo da tela ou da API aceita frase secreta, mnemônica ou chave privada (regra da U1 reafirmada)."
    violation: não se aplica (proibição de projeto)
    source: FR1.5, AC1.2.5

  # BR4 - Sessão expirada, retorno e saída
  - id: BR4.1
    statement: Uma chamada com sessão expirada leva à entrada com aviso de sessão expirada, nunca a um erro técnico.
    category: policy
    applies_to: app do convidado
    trigger: resposta 401 de qualquer rota do convidado
    logic: "O app tenta uma renovação. SE a renovação também responde 401 ENTÃO descartar o token da memória e ir para a entrada com origem igual ao caminho atual, mostrando 'Sua sessão expirou. Entre de novo para continuar.'"
    violation: não se aplica
    source: FR1.6, AC1.3.1, AC1.3.3

  - id: BR4.2
    statement: O retorno depois da entrada aceita só caminhos internos do app.
    category: validation
    applies_to: parâmetro origem da entrada
    trigger: entrada com origem
    logic: "Aceitar só um caminho que começa com uma única '/', sem esquema, sem host, sem '//' nem '\\' no início, e que seja uma rota do convidado. Qualquer outro valor é trocado pela tela inicial."
    violation: o valor inválido é ignorado
    source: AC1.3.1, Q9

  - id: BR4.3
    statement: Depois de entrar de novo, a pessoa volta ao caminho de origem.
    category: policy
    applies_to: app do convidado
    trigger: sessão aberta a partir da entrada com origem válida
    logic: "Navegar para a origem validada pela BR4.2. A preservação de rascunhos fica para cada Unit (a U5 usa este retorno para o rascunho do bot)."
    violation: não se aplica
    source: FR1.6, AC1.3.1, Q9

  - id: BR4.4
    statement: "Sair" está no menu da conta e na tela Conta; encerra a sessão e mantém os bots rodando.
    category: policy
    applies_to: app do convidado, POST /api/v1/auth/logout
    trigger: escolha de Sair
    logic: "Chamar a saída da U1 (revoga a sessão e grava LOGOUT), descartar o token da memória e voltar à entrada. Bots PAPER ativos continuam no servidor. Não existe 'desconectar carteira'."
    violation: não se aplica
    source: FR1.6, FR11.3, AC1.3.2

  # BR5 - Tela Conta
  - id: BR5.1
    statement: A tela Conta mostra a carteira abreviada e só para leitura, o provedor e o fim absoluto da sessão.
    category: policy
    applies_to: GET /api/v1/account, tela Conta
    trigger: abertura da tela Conta
    logic: "Pessoa e tenant vêm do token. walletAddressMasked = 4 primeiros + '…' + 4 últimos caracteres; provider; sessionExpiresAt = absoluteExpiresAt da sessão atual, com a frase 'ou antes, depois de 8 horas sem uso'."
    violation: sem sessão: 401 (BR4.1)
    source: FR1.5, FR1.6, AC1.5.1, Q10

  - id: BR5.2
    statement: Os termos aceitos aparecem com a versão e a data do aceite no fuso da pessoa.
    category: policy
    applies_to: tela Conta
    trigger: escolha de "ver" nos termos aceitos
    logic: "A API devolve version e acceptedAt em UTC; a tela converte para o fuso do navegador e mostra o texto daquela versão."
    violation: não se aplica
    source: FR1.2, AC1.5.2

  - id: BR5.3
    statement: A tela Conta mostra "Modo REAL: indisponível no beta" em texto e nenhum controle para ativá-lo.
    category: policy
    applies_to: tela Conta, GET /api/v1/account
    trigger: exibição da tela
    logic: "realMode é sempre UNAVAILABLE; a tela não tem botão, chave nem link para REAL."
    violation: não se aplica
    source: FR15.2, AC1.5.3

  - id: BR5.4
    statement: A tela Conta não oferece desvincular nem trocar a carteira.
    category: policy
    applies_to: tela Conta
    trigger: exibição da tela
    logic: "O endereço não é campo editável; não há ação de desconectar a carteira no beta."
    violation: não se aplica
    source: FR1.6, Q13 das histórias

  # BR6 - Tela de entrada com convite
  - id: BR6.1
    statement: Sem código ou sem carteira instalada, a entrada explica o que fazer e preserva as marcações de aceite.
    category: policy
    applies_to: tela de entrada com convite
    trigger: entrada sem código, ou nenhuma carteira Phantom ou Solflare detectada
    logic: "Sem código: campo para colar o código e o texto de onde encontrá-lo. Sem carteira: links para instalar Phantom ou Solflare. As duas marcações de aceite ficam guardadas na sessão do navegador até a conta ser criada ou a aba ser fechada, então voltar não as perde."
    violation: não se aplica
    source: AC1.1.6

  - id: BR6.2
    statement: Os textos da entrada com convite e dos termos não prometem lucro nem garantia de detecção de fraude.
    category: policy
    applies_to: tela de entrada, TermsDocument
    trigger: revisão de texto
    logic: "Os textos passam pelo catálogo de termos proibidos da U4 e pelo seu teste."
    violation: o teste do catálogo falha
    source: C-16
```

## Resumo das regras

| Grupo | Regras | O que decide |
|---|---|---|
| BR1 Convites pelo administrador | BR1.1 a BR1.8 | emissão para tenant ativo com validade de 1 hora a 30 dias, estado derivado com validade inclusiva, lista sem o código, revogação só de pendente, rotas só do administrador, contagens, tenant Beta semeado, repetição da emissão sem o código |
| BR2 Consulta do convite | BR2.1 a BR2.3 | consulta sem sessão com termos vigentes e 404 genérico, limite de 10 por minuto por IP, código fora de log, trilha e métrica |
| BR3 Criação da conta | BR3.1 a BR3.9 | aceite antes do vínculo, ordem das conferências, operação única, concorrência no mesmo convite, evento de criação, nonce consumido, carteira já vinculada, verificação sem convite, só endereço e provedor |
| BR4 Sessão e saída | BR4.1 a BR4.4 | aviso de sessão expirada, origem só interna, retorno à origem, Sair com bots rodando |
| BR5 Tela Conta | BR5.1 a BR5.4 | carteira abreviada, provedor e fim absoluto, termos no fuso da pessoa, REAL indisponível, sem desvincular |
| BR6 Tela de entrada | BR6.1 e BR6.2 | sem código ou carteira, sem promessa de lucro |
