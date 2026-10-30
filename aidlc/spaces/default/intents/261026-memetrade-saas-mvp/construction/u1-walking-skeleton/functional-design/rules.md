# Regras de negócio — U1 Esqueleto de ponta a ponta

Regras da Unit `u1-walking-skeleton` ([unit-of-work.md](../../../inception/units-generation/unit-of-work.md)), derivadas de [requirements.md](../../../inception/requirements-analysis/requirements.md), das histórias de [stories.md](../../../inception/user-stories/stories.md) mapeadas em [unit-of-work-story-map.md](../../../inception/units-generation/unit-of-work-story-map.md), dos componentes de [components.md](../../../inception/domain-design/components.md), dos contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das respostas Q1 a Q9 de [functional-design-questions.md](functional-design-questions.md).

## Regras

```yaml
rules:
  # BR1 - Login por assinatura (C1)
  - id: BR1.1
    statement: O desafio de login traz domínio, nonce de uso único, chain, horário de emissão e expiração de 5 minutos.
    category: policy
    applies_to: LoginChallenge
    trigger: Pedido de desafio com um endereço de carteira
    logic: IF o pedido traz endereço válido THEN emitir mensagem com domain, nonce aleatório, chain, issuedAt e expiresAt = issuedAt + 5 minutos, em estado ISSUED
    violation: Endereço inválido recebe VALIDATION_FAILED
    source: FR1.3
  - id: BR1.13
    statement: A mensagem de login segue um modelo fixo de linhas, e a verificação encontra o desafio pelo nonce lido da linha "Nonce".
    category: validation
    applies_to: LoginChallenge
    trigger: Emissão e verificação
    logic: A mensagem tem, nesta ordem, as linhas "<domain> pede que você entre com a sua conta Solana", "<walletAddress>", "Esta assinatura serve só para entrar e não autoriza operações.", "URI", "Chain: <chain>", "Nonce: <nonce>", "Issued At: <issuedAt>" e "Expiration Time: <expiresAt>". IF a mensagem recebida não segue o modelo ou não tem a linha Nonce THEN recusar com motivo MALFORMED_MESSAGE; IF não existe desafio para o nonce lido THEN recusar com motivo UNKNOWN_CHALLENGE, sem mudar estado de nenhum desafio
    violation: LOGIN_CHALLENGE_REJECTED (401), auditado por BR1.6
    source: FR1.3, FR1.4
  - id: BR1.2
    statement: A verificação só aceita assinatura válida da carteira sobre a mensagem exata emitida, com nonce ISSUED e dentro da expiração.
    category: validation
    applies_to: LoginChallenge, Session
    trigger: Envio da assinatura
    logic: IF a mensagem recebida é idêntica à mensagem guardada no desafio AND a assinatura confere com walletAddress AND o nonce está ISSUED AND o instante atual é menor ou igual a expiresAt THEN seguir para BR1.5; IF o instante passou de expiresAt THEN recusar com motivo EXPIRED e marcar o desafio EXPIRED
    violation: LOGIN_CHALLENGE_REJECTED (401), auditado por BR1.6
    source: FR1.3, NFR6
  - id: BR1.3
    statement: Um nonce é consumido no máximo uma vez, inclusive com verificações simultâneas.
    category: constraint
    applies_to: LoginChallenge
    trigger: Verificação aceita
    logic: IF duas verificações do mesmo nonce chegam juntas THEN só a primeira a passar de ISSUED para CONSUMED abre sessão; a outra é recusada
    violation: LOGIN_CHALLENGE_REJECTED, auditado por BR1.6
    source: FR1.3
  - id: BR1.4
    statement: Mensagem com domínio, chain ou nonce diferente do emitido, alterada, ou assinada por outra carteira é recusada.
    category: validation
    applies_to: LoginChallenge
    trigger: Envio da assinatura
    logic: IF qualquer campo da mensagem difere do emitido OR o assinante difere de walletAddress THEN recusar com motivo MESSAGE_MISMATCH ou WRONG_SIGNER e marcar o nonce CONSUMED para impedir nova tentativa com ele
    violation: LOGIN_CHALLENGE_REJECTED, auditado por BR1.6
    source: FR1.3
  - id: BR1.5
    statement: Só abre sessão pela entrada do convidado a carteira vinculada a uma conta BETA_USER ativa de um tenant ativo; a carteira de administrador, lida da configuração do ambiente, é recusada nessa entrada.
    category: authorization
    applies_to: Wallet, User, Session
    trigger: Verificação com assinatura válida (BR1.2 aceita)
    logic: As carteiras de administrador vêm da configuração ADMIN_WALLET_ADDRESSES (lista de endereços públicos; vazia ou ausente significa nenhum administrador). IF o endereço está nessa lista THEN recusar com FORBIDDEN_ROLE e motivo ADMIN_WALLET; IF a carteira pertence a um User BETA_USER ACTIVE de um Tenant ACTIVE THEN passar o nonce para CONSUMED e abrir sessão; IF não pertence a ninguém ativo THEN recusar com motivo UNKNOWN_ACCOUNT. Em toda recusa desta regra o nonce passa para CONSUMED, porque a assinatura já foi usada.
    violation: 401 LOGIN_CHALLENGE_REJECTED ou 403 FORBIDDEN_ROLE, auditado por BR1.6
    source: FR1.3, FR2.2
  - id: BR1.6
    statement: Toda recusa de login é um resultado da operação, não um erro que desfaz a transação; a recusa grava exatamente um evento LOGIN_REJECTED antes da resposta.
    category: policy
    applies_to: LoginChallenge, AuditOutboxEntry
    trigger: Qualquer recusa de BR1.2 a BR1.5
    logic: IF a verificação é recusada THEN, na mesma transação confirmada, gravar o novo estado do desafio e um evento LOGIN_REJECTED com o endereço mascarado e o motivo, e só depois responder com o erro
    violation: Não aplicável (regra de garantia)
    source: FR2.3, FR1.3
  - id: BR1.7
    statement: A sessão autentica o uso do produto e nunca autoriza trading; nenhuma rota aceita a assinatura de login como prova.
    category: authorization
    applies_to: Session
    trigger: Qualquer chamada autenticada
    logic: IF uma rota pede autorização de trading THEN o token de sessão não é aceito; IF uma requisição traz uma assinatura de login como prova THEN é recusada; não existe rota de autorização REAL; toda resposta 403 grava um evento REQUEST_FORBIDDEN com a rota, o papel e o motivo
    violation: 403, auditado como REQUEST_FORBIDDEN
    source: FR1.4, FR15.2, FR2.3
  - id: BR1.8
    statement: Nenhum campo aceita frase secreta, mnemônica ou chave privada, e o código de produção não importa nem gera chaves.
    category: validation
    applies_to: Toda requisição
    trigger: Recebimento de requisição
    logic: IF o corpo traz campo não declarado THEN recusar com VALIDATION_FAILED sem persistir nada
    violation: VALIDATION_FAILED (400)
    source: FR1.5, NFR6
  - id: BR1.9
    statement: Token de acesso dura 15 minutos; a renovação vale por 8 horas desde o último uso; a sessão termina em 12 horas desde a criação.
    category: policy
    applies_to: Session
    trigger: Emissão e renovação
    logic: IF a renovação chega com refreshExpiresAt e absoluteExpiresAt no futuro AND a sessão está ACTIVE AND o User está ACTIVE AND o Tenant está ACTIVE THEN emitir novo token de acesso e estender refreshExpiresAt sem passar de absoluteExpiresAt (a rotação segue BR1.15); IF um prazo venceu THEN recusar com UNAUTHENTICATED e marcar EXPIRED; IF o User ou o Tenant deixou de estar ativo THEN recusar com UNAUTHENTICATED e marcar REVOKED
    violation: UNAUTHENTICATED (401)
    source: FR1.6
  - id: BR1.14
    statement: O token de acesso carrega o identificador da sessão, e toda requisição autenticada confere que a sessão, o usuário e o tenant continuam ativos.
    category: authorization
    applies_to: Session
    trigger: Toda requisição autenticada
    logic: IF o token é válido e não expirou AND a sessão referida está ACTIVE AND o User e o Tenant estão ACTIVE THEN aceitar; ELSE recusar com UNAUTHENTICATED, mesmo dentro dos 15 minutos do token
    violation: UNAUTHENTICATED (401)
    source: FR1.6, FR2.1
  - id: BR1.15
    statement: Cada renovação troca o token de renovação; usar um token de renovação antigo revoga a sessão inteira.
    category: policy
    applies_to: Session
    trigger: Renovação
    logic: IF a renovação usa o token de renovação atual THEN emitir novo token de renovação, guardar só o resumo do novo e manter o resumo do anterior como já usado; IF chega um token de renovação já usado da mesma sessão THEN revogar a sessão (REVOKED), recusar com UNAUTHENTICATED e gravar o evento SESSION_REFRESH_REUSED
    violation: UNAUTHENTICATED (401), auditado
    source: FR1.6, NFR6
  - id: BR1.10
    statement: A verificação de login não guarda resposta para repetição; o desafio exige Idempotency-Key com escopo pela carteira.
    category: policy
    applies_to: IdempotencyRecord
    trigger: Pedidos de desafio e de verificação
    logic: IF o pedido é de desafio THEN aplicar idempotência com escopo carteira + operação, guardando a resposta só até a expiração do desafio emitido; depois disso a mesma chave emite um desafio novo; IF é de verificação THEN não guardar resposta, porque o nonce de uso único garante uma única sessão. O nonce guardado em repouso não é segredo: é público na mensagem, de uso único e expira em 5 minutos.
    violation: Mesma chave com conteúdo diferente no desafio recebe IDEMPOTENCY_CONFLICT (409)
    source: FR1.3
  - id: BR1.11
    statement: O limite de tentativas protege a entrada sem deixar que um terceiro bloqueie o dono de uma carteira.
    category: constraint
    applies_to: LoginChallenge
    trigger: Pedidos de desafio e de verificação
    logic: IF um IP passa de 10 pedidos de desafio ou de verificação no último minuto THEN recusar os pedidos desse IP com 429 e Retry-After; IF uma carteira acumula mais de 10 verificações recusadas no último minuto THEN recusar novas verificações dessa carteira vindas de IPs que tiveram recusas no período, sem afetar outros IPs; pedidos de desafio nunca contam no limite por carteira
    violation: 429 com Retry-After
    source: NFR6
  - id: BR1.12
    statement: Sair revoga a sessão e grava um evento LOGOUT; bots continuam no servidor.
    category: policy
    applies_to: Session
    trigger: Pedido de saída
    logic: IF a sessão está ACTIVE THEN marcar REVOKED, invalidar a renovação e gravar LOGOUT
    violation: Sessão já encerrada responde 204 sem novo evento
    source: FR1.6

  # BR2 - Isolamento por tenant (C10)
  - id: BR2.1
    statement: Tenant e pessoa vêm só do contexto de autenticação; um tenantId enviado pelo cliente é ignorado.
    category: authorization
    applies_to: Toda leitura e escrita de dado de cliente
    trigger: Requisição autenticada
    logic: IF o corpo ou a query trazem tenantId THEN ignorar e usar o do token; nada de outro tenant é lido ou gravado
    violation: Não aplicável (o valor é ignorado)
    source: FR2.1, NFR5
  - id: BR2.2
    statement: Recurso de outro tenant ou de outra pessoa responde 404 e nunca o dado.
    category: authorization
    applies_to: Toda rota com identificador de recurso de cliente
    trigger: Leitura ou alteração por identificador
    logic: IF o recurso não pertence ao tenant e à pessoa do token THEN responder NOT_FOUND e gravar o evento TENANT_MISMATCH com a rota e o identificador pedido
    violation: NOT_FOUND (404), auditado
    source: FR2.1, NFR5
  - id: BR2.3
    statement: Toda rota e tarefa que toca dado de cliente fica num registro verificado, e cada uma precisa de um teste negativo entre tenants.
    category: policy
    applies_to: Rotas e tarefas
    trigger: Execução da suíte
    logic: O registro é a lista de rotas gerada da especificação da API, em que cada rota é marcada como de dado de cliente ou global, mais a lista declarada de tarefas em segundo plano. Um teste da suíte lê esse registro e IF uma rota ou tarefa marcada como de dado de cliente não tem caso de teste negativo entre tenants associado THEN o teste falha e, com ele, o build. Na U1 todas as rotas são globais ou de sessão própria, então o registro nasce vazio de rotas de cliente e o teste passa a valer a partir da primeira.
    violation: Build falha
    source: FR2.1, NFR5

  # BR3 - Dados de mercado e lista do scanner (C2)
  - id: BR3.1
    statement: Todo valor de mercado é KNOWN com valor, fonte, horário e marca de desatualizado, ou UNKNOWN com motivo; ausência nunca vira zero nem vazio.
    category: validation
    applies_to: MarketObservation
    trigger: Coleta e leitura
    logic: IF a fonte não informou o campo THEN gravar UNKNOWN com NOT_PROVIDED; IF a fonte informou 0 THEN gravar KNOWN com valor 0
    violation: Não aplicável (regra de representação)
    source: FR4.4, FR4.3
  - id: BR3.2
    statement: Tokens são identificados por chain + mint; símbolos iguais com mints diferentes são itens distintos, exibidos com o mint abreviado.
    category: constraint
    applies_to: Token
    trigger: Coleta e listagem
    logic: IF dois tokens têm o mesmo símbolo e mints diferentes THEN listar os dois, cada um com o mint abreviado (4 primeiros e 4 últimos caracteres)
    violation: Não aplicável
    source: FR4.2
  - id: BR3.3
    statement: A fonte simulada se identifica como simulada e gera dados com semente fixa, incluindo sempre um token com holders não informados e um com liquidez informada como 0.
    category: policy
    applies_to: DataSource, MarketObservation
    trigger: Cada coleta da fonte simulada
    logic: IF a coleta roda THEN gerar tokens com a semente configurada, marcar a fonte com simulated = true e garantir os dois casos obrigatórios
    violation: Teste do gerador falha se faltar um dos dois casos
    source: FR4.1
  - id: BR3.4
    statement: Um valor fica desatualizado quando a idade passa estritamente de 5 minutos, medida pelo relógio injetável.
    category: calculation
    applies_to: MarketObservation
    trigger: Leitura
    logic: IF agora - observedAt > 5 minutos THEN stale = true; ELSE stale = false
    violation: Não aplicável
    source: FR4.5
  - id: BR3.5
    statement: A cobertura do token é a quantidade de campos KNOWN entre preço, liquidez, volume em 24 horas, holders e idade.
    category: calculation
    applies_to: Token
    trigger: Leitura da lista
    logic: cobertura = campos KNOWN de 5 campos
    violation: Não aplicável
    source: FR4.3
  - id: BR3.6
    statement: A lista vem ordenada pela idade, mais novos primeiro, com desempate pelo mint em ordem crescente; tokens com idade UNKNOWN vão para o fim; a paginação por cursor mantém a ordem.
    category: calculation
    applies_to: Lista do scanner
    trigger: Leitura e paginação
    logic: ordenar por idade crescente, depois mint crescente; IF a idade é UNKNOWN THEN colocar depois de todos os conhecidos, também por mint. A primeira página fixa um instante de corte; o cursor é opaco e leva o instante de corte, o grupo (KNOWN ou UNKNOWN), o instante de criação e o mint do último item. As páginas seguintes usam a mesma ordem calculada no instante de corte, e o total N é o da primeira página. Um token criado depois do corte só aparece numa nova primeira página. Se a idade de um token passar de UNKNOWN para KNOWN entre páginas, ele pode aparecer duas vezes ou faltar até a próxima primeira página; isso é aceito e coberto por teste.
    violation: Não aplicável
    source: FR5.1
  - id: BR3.7
    statement: Carregando, erro e resultado parcial são estados distintos entre si e de unknown; dados novos não mudam a linha selecionada nem o foco.
    category: policy
    applies_to: Tela da lista
    trigger: Carregamento e atualização
    logic: IF a lista carrega THEN mostrar marcadores só na área da lista; IF a fonte falha THEN mostrar erro com "Tentar de novo"; IF parte dos campos é UNKNOWN THEN mostrar unknown por campo, sem confundir com erro
    violation: Não aplicável
    source: FR5.3

  # BR4 - Auditoria (C9)
  - id: BR4.1
    statement: O evento de auditoria é gravado na tabela de saída dentro da transação da ação e entregue à trilha uma única vez.
    category: policy
    applies_to: AuditOutboxEntry, AuditEvent
    trigger: Ação sensível confirmada
    logic: IF a transação da ação confirma THEN a entrada da tabela de saída existe; o relay grava o AuditEvent pelo eventId e marca deliveredAt; eventId repetido é descartado
    violation: Não aplicável (regra de garantia)
    source: FR2.3
  - id: BR4.2
    statement: Na U1, as ações auditadas são LOGIN_SUCCEEDED, LOGIN_REJECTED (BR1.6), LOGOUT (BR1.12), REQUEST_FORBIDDEN (BR1.5, BR1.7), TENANT_MISMATCH (BR2.2) e SESSION_REFRESH_REUSED (BR1.15), com ator, ação, tenant quando houver, horário UTC e correlationId.
    category: policy
    applies_to: AuditEvent
    trigger: As ações listadas
    logic: IF a ação ocorre antes do login THEN tenantId vazio, actorType ANONYMOUS e actor com o endereço mascarado; IF é uma recusa THEN details traz o motivo (por exemplo, EXPIRED, UNKNOWN_CHALLENGE, ADMIN_WALLET)
    violation: Não aplicável
    source: FR2.3
  - id: BR4.3
    statement: Eventos e logs nunca contêm nonce, assinatura, token de acesso, token de renovação nem cabeçalho Authorization; endereços aparecem mascarados fora da trilha.
    category: constraint
    applies_to: AuditEvent, logs
    trigger: Gravação de evento e de log
    logic: IF um campo é segredo, token, nonce ou assinatura THEN não gravar; IF é endereço fora da trilha THEN mascarar
    violation: Varredura de logs dos testes falha
    source: NFR7, NFR6
```

## Resumo

| ID | Regra | Fonte |
|---|---|---|
| BR1.1 | Desafio com domínio, nonce, chain e expiração de 5 min | FR1.3 |
| BR1.13 | Modelo fixo da mensagem e busca do desafio pelo nonce | FR1.3 |
| BR1.14 | Toda requisição confere sessão, usuário e tenant ativos | FR1.6 |
| BR1.15 | Rotação do token de renovação e revogação por reutilização | FR1.6 |
| BR1.2 | Verificação só com assinatura válida, nonce emitido e dentro do prazo | FR1.3 |
| BR1.3 | Nonce consumido no máximo uma vez | FR1.3 |
| BR1.4 | Mensagem alterada ou assinante diferente é recusada | FR1.3 |
| BR1.5 | Só conta BETA_USER ativa entra; carteira de administrador da configuração recebe 403 | FR1.3, FR2.2 |
| BR1.6 | Recusa grava um evento antes da resposta | FR2.3 |
| BR1.7 | Sessão nunca autoriza trading | FR1.4 |
| BR1.8 | Nenhum campo aceita segredo; campos extras recusados | FR1.5 |
| BR1.9 | 15 min de acesso, 8 h de renovação, 12 h de sessão | FR1.6 |
| BR1.10 | Verificação sem resposta guardada; desafio com idempotência | FR1.3 |
| BR1.11 | 10 pedidos por minuto por IP; por carteira só verificações recusadas | NFR6 |
| BR1.12 | Sair revoga a sessão | FR1.6 |
| BR2.1 | Tenant só do token | FR2.1 |
| BR2.2 | Recurso alheio responde 404 | FR2.1 |
| BR2.3 | Toda rota de cliente tem teste negativo entre tenants | FR2.1 |
| BR3.1 | KNOWN ou UNKNOWN, nunca zero por ausência | FR4.4 |
| BR3.2 | Identidade por chain + mint | FR4.2 |
| BR3.3 | Fonte simulada com casos garantidos | FR4.1 |
| BR3.4 | Desatualizado acima de 5 min | FR4.5 |
| BR3.5 | Cobertura de 5 campos | FR4.3 |
| BR3.6 | Ordem por idade e mint | FR5.1 |
| BR3.7 | Estados distintos de unknown | FR5.3 |
| BR4.1 | Tabela de saída na transação, entrega única | FR2.3 |
| BR4.2 | Ações auditadas na U1 | FR2.3 |
| BR4.3 | Sem segredos em eventos e logs | NFR7 |
