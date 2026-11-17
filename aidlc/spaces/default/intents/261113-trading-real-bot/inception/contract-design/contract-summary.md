# Contratos — Trading REAL

Conversa: português (Brasil). Contratos entre as 7 Units de [unit-of-work.md](../units-generation/unit-of-work.md), seguindo as arestas de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md), as entidades de [components.md](../domain-design/components.md) e os requisitos de [requirements.md](../requirements-analysis/requirements.md). As decisões vêm das respostas Q1 a Q11 de [contract-design-questions.md](contract-design-questions.md). As regras comuns da API, a auditoria por tabela de saída (C9) e as interfaces de módulo do intent anterior (`261026-memetrade-saas-mvp/inception/contract-design/contract-summary.md`) continuam valendo; este documento só acrescenta o que o REAL muda.

Os contratos são de cinco tipos:

- **REST (OpenAPI), gerado do código:** rotas do convidado em `/api/v1/real/...` em `openapi-guest.yaml` e rotas do administrador em `/api/v1/admin/real/...` em `openapi-admin.yaml`. A CI compara o arquivo gerado com o versionado, e os tipos do frontend são gerados dele (Q3, Q10).
- **API interna do TxSigner:** HTTP em `/internal/v1/...`, só na rede privada, com credencial de serviço própria (mTLS na nuvem). O navegador nunca chega a ela (Q1, Q2).
- **Interfaces de módulo (esquema compartilhado):** interfaces públicas Java com records imutáveis entre módulos do monólito; nenhum módulo lê tabela de outro (Q7).
- **Auditoria pela tabela de saída:** o contrato C9 existente, com campos REAL tipados (Q7).
- **Portas de fornecedor:** Jupiter, CoinGecko, RPC Solana e KMS, cada uma com a Unit consumidora como dona e implementação simulada no ambiente local (Q11).

## Tabela de contratos

| # | Provider Unit | Consumer | Mechanism | Owner |
|---|---|---|---|---|
| R1 | U1 `u1-bot-wallet` (TxSigner) | U1 BotWallet; U4 pela BotWallet | HTTP interno `/internal/v1` (OpenAPI interna) | U1 |
| R2 | U1 | U1 tela mínima; U6 telas da carteira | REST: carteira do bot, consentimento, depósitos e saques | U1 |
| R3 | U4 `u4-real-execution` | U6 telas de ordens, modo e assinatura | REST: ordens REAL, troca de modo, sessão de assinatura, vender tudo, P&L REAL | U4 |
| R4 | U5 `u5-real-operations` | App do administrador (U6 área admin) | REST admin: liberação, desligar, teto, bloqueio, kill switch REAL, visão e divergências | U5 |
| R5 | U7 `u7-exits-and-fiscal` | U6 (telas de saídas) | REST: ordens condicionais e exportação fiscal | U7 |
| R6 | U1 e U4 (eventos), canal existente hospedado no processo de API | U6 | Canal ao vivo existente (fetch em leitura contínua), novos tipos de evento REAL | O canal continua com o dono atual (módulo Bots); cada Unit produtora é dona do seu tipo de evento (U1: depósito, saque, pausa; U4: ordem e assinatura) |
| M1 | U1 BotWallet | U3, U4, U5 | Interface de módulo: leitura e comandos da carteira do bot | U1 |
| M2 | U1 Ledger REAL | U3, U4, U5, U7 | Interface de módulo: escrita e leitura do ledger REAL | U1 |
| M3 | U1 ChainWatcher | U1, U4 | Interface de módulo (porta da rede) | U1 |
| M4 | U2 `u2-real-market-data` | U3, U4 (P&L) | Interface de módulo: dados de mercado com tipo de fonte e frescor (amplia C11) | U2 |
| M5 | U3 `u3-real-risk` | U4, U5 | Interface de módulo: barreira REAL e lista de bloqueio | U3 |
| M6 | U4 RealExecution | Bots (ciclo); U5; U7 | Interface de módulo: registro da decisão, cancelamento, leitura de ordens | U4 |
| M7 | U5 | U7 | Interface de módulo: estado do kill switch REAL | U5 |
| M8 | U1 (validador da auditoria) | U1 a U7 | Tabela de saída C9 com campos REAL tipados | U1 |
| M9 | U1 Identity | U1, U6 | Interface de módulo: termos v3 e conferência de consentimento | U1 |
| X1 | Jupiter (externo) | U4, U7 | Porta de fornecedor: cotação, swap, condicionais | U4 |
| X2 | CoinGecko (externo) | U2 | Porta de mercado existente | U2 |
| X3 | Solana RPC (externo, dois provedores) | U1 ChainWatcher | Porta de fornecedor | U1 |
| X4 | AWS KMS (externo) | U1 TxSigner | Porta de fornecedor | U1 |

Não existe contrato para consumidor fora do sistema (Q1). Todo contrato segue uma aresta direta do grafo de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md): nenhum provedor chama de volta o consumidor. As arestas U2→U4 (M4, marcação a mercado do P&L REAL), U3→U5 (M5, lista de bloqueio) e U1→U7 (M2, preenchimento das condicionais) foram acrescentadas ao grafo nesta revisão; nenhuma cria ciclo. A U7 lê o kill switch pela M7 em vez de a U5 chamar a U7 (Q7).

| Contrato | Aresta do grafo |
|---|---|
| R1, M1, M2, M3, M9 | U1 interno; U1→U3, U1→U4, U1→U5, U1→U6, U1→U7 |
| M4 | U2→U3, U2→U4 |
| M5 | U3→U4, U3→U5 |
| M6, R3, R6 | U4→U5, U4→U6, U4→U7 |
| M7 | U5→U7 |

## Regras comuns acrescentadas à API REST

Valem para R2 a R5, além das regras comuns do intent anterior (autenticação, tenant pelo token, `Idempotency-Key` em toda mudança, `ProblemDetail`, instantes em UTC, paginação por cursor).

| Tema | Regra |
|---|---|
| Base | Convidado em `/api/v1/real/...`; administrador em `/api/v1/admin/real/...` (Q3). |
| Modo | Nenhuma requisição declara `mode`. O filtro existente continua recusando `mode` diferente de `PAPER` nas rotas PAPER. As rotas REAL só existem para o REAL: o modo vem do caminho. |
| Ligado no ambiente | Com `memetrade.real.enabled=false` (o padrão), as rotas que **abrem risco** respondem `403 REAL_DISABLED`: desafio e ativação, subir modo ou teto, sessão de assinatura, vender tudo e condicionais novas. Continuam disponíveis, para quem já tem carteira do bot: leitura da carteira, das ordens e do P&L, rebaixar modo, baixar teto, pausar e **sacar** (FR3.3). Com a configuração padrão nenhuma carteira do bot pode ser criada, então nenhum caminho assina nem envia transação. |
| Liberação | Sem liberação vigente do usuário (24 h após registrada), as rotas que abrem risco respondem `403 REAL_NOT_RELEASED`; leitura, rebaixar, pausar e sacar continuam. O saque nunca depende da liberação, do flag nem do kill switch (FR3.3). |
| Termos | Sem aceite da v3: `403 TERMS_REACCEPTANCE_REQUIRED` (mecanismo existente) em toda rota, **menos** `GET /api/v1/real/bot-wallet` e as rotas de saque, para que o usuário que recusar uma versão nova dos termos ainda consiga tirar o próprio dinheiro. Essa exceção muda a regra de `team.md` "a versão nova dos termos exige novo aceite antes de qualquer outra rota" e entra na lista de mudanças de regra do ADR-008, registrada pelo AI-DLC antes da Construction. |
| Flag fora do HTTP | O mesmo flag vale para o worker e para o TxSigner, cada um com a própria configuração e padrão desligado: o consumidor da fila de decisões cancela as decisões REAL com `REAL_DISABLED`; o ciclo do bot não grava decisão REAL; o TxSigner (`txsigner.real.enabled=false`) recusa criar carteira e assinar swap, e só assina **saque** para a carteira principal registrada na WalletPolicy. A reconciliação continua rodando (só lê e compensa). |
| Lamports | SOL trafega em lamports como texto inteiro (`LamportsString`, padrão `^[0-9]{1,20}$`), nunca número JSON (Q4). Valores que podem ser negativos (P&L REAL, compensações, divergências) usam `SignedLamportsString` (`^-?[0-9]{1,20}$`). |
| Quantidade de token | Unidades base como texto inteiro (`BaseUnitsString`) com `decimals` do mint ao lado. A tela converte. |
| Preço e P&L | `DecimalString` com as escalas existentes; P&L REAL em lamports quando o valor é em SOL. |
| Endereços e assinaturas | Base58 em campos próprios (`address`, `txSignature`); nunca em `details` livre. A carteira principal aparece só para o próprio usuário. |
| Repetição pelo cliente | Como hoje; uma ordem ou saque repetido com a mesma `Idempotency-Key` devolve o mesmo recurso e nunca gera segunda transação (FR5.5). |

### Códigos de erro novos

| `code` | HTTP | Uso |
|---|---|---|
| `REAL_DISABLED` | 403 | REAL desligado no ambiente |
| `REAL_NOT_RELEASED` | 403 | Usuário sem liberação vigente |
| `REAL_PAUSED` | 409 | REAL do usuário pausado (divergência, perda do dia, kill switch) |
| `BOT_WALLET_NOT_ACTIVE` | 409 | Operação exige carteira do bot ativa |
| `BOT_WALLET_ALREADY_ACTIVE` | 409 | Segunda ativação |
| `CONSENT_INVALID` | 422 | Assinatura, nonce, validade ou valores do consentimento não conferem |
| `CONSENT_EXPIRED` | 422 | Desafio vencido (5 min) |
| `CAP_ABOVE_MAXIMUM` | 422 | Teto acima do máximo do perfil (até 10 SOL) |
| `CAP_INCREASE_NOT_ALLOWED` | 422 | O administrador tentou subir o teto (só pode baixar) |
| `REAL_NOT_PAUSED` | 409 | Retomar um REAL que não está pausado pelo usuário |
| `DIVERGENCE_NOT_OPEN` | 409 | Compensar uma divergência já resolvida |
| `REAL_CAP_EXCEEDED` | 422 | Compra passaria do teto |
| `REAL_LIMIT_EXCEEDED` | 422 | Limite por ordem, perda do dia ou número de ordens |
| `TOKEN_NOT_ELIGIBLE` | 422 | Token sem cobertura suficiente ou bloqueado |
| `STALE_DATA` | 422 | Dado com mais de 60 s (código existente) |
| `SIMULATED_SOURCE_ACTIVE` | 409 | Fonte de mercado simulada ativa |
| `WITHDRAWAL_DESTINATION_INVALID` | 422 | Destino diferente da carteira principal verificada |
| `INSUFFICIENT_FREE_BALANCE` | 422 | Saldo livre insuficiente para o saque |
| `SIGNING_POLICY_REFUSED` | 422 | O TxSigner recusou a transação; o motivo vai só para a auditoria |
| `SIGNING_DEADLINE_EXPIRED` | 409 | Prazo de 8 s do modo Assinada vencido |
| `SIGNED_MESSAGE_MISMATCH` | 422 | A transação devolvida pelo navegador não é a que o servidor montou |
| `ORDER_AMBIGUOUS` | 409 | Vender tudo, saque total ou troca de modo bloqueados enquanto houver ordem ou saque ambíguo ainda não conferido na rede |
| `ORDER_ALREADY_IN_PROGRESS` | 409 | Vender tudo pedido com uma liquidação em andamento; no ciclo do bot, a mesma regra (uma ordem por bot) vira recusa registrada, não resposta HTTP |
| `UPSTREAM_UNAVAILABLE` | 503 | RPC, Jupiter, TxSigner ou CoinGecko indisponível; novas ordens pausam, saque continua |

Erros de domínio são `4xx` e auditados; erros técnicos são `5xx` ou `503` e só são repetidos quando a operação é idempotente (`team.md`).

## Especificações

### Componentes comuns REAL (referenciados por R2 a R5)

```yaml
openapi: 3.0.3
info: { title: Componentes comuns REAL, version: 1.0.0 }
paths: {}
components:
  schemas:
    LamportsString:
      type: string
      pattern: '^[0-9]{1,20}$'
      description: SOL em lamports (1 SOL = 1000000000)
    SignedLamportsString:
      type: string
      pattern: '^-?[0-9]{1,20}$'
      description: lamports com sinal (P&L, compensação, divergência)
    BaseUnitsString:
      type: string
      pattern: '^[0-9]{1,30}$'
    WithdrawalState:
      type: string
      enum: [REQUESTED, SIGNED, SENT, CONFIRMED, FAILED, AMBIGUOUS]
    SigningSession:
      type: object
      required: [transactionBase64, messageDigest, deadline, display]
      properties:
        transactionBase64: { type: string }
        messageDigest: { type: string, description: SHA-256 da mensagem montada }
        deadline: { type: string, format: date-time, description: 8 s depois da cotação }
        display:
          type: object
          properties:
            side: { type: string, enum: [BUY, SELL] }
            input: { $ref: '#/components/schemas/TokenAmount' }
            expectedOutput: { $ref: '#/components/schemas/TokenAmount' }
            minOutput: { $ref: '#/components/schemas/TokenAmount' }
            feeLamports: { $ref: '#/components/schemas/LamportsString' }
    SignedTransactionRequest:
      type: object
      required: [signedTransactionBase64]
      properties:
        signedTransactionBase64: { type: string }
    TokenAmount:
      type: object
      required: [mint, baseUnits, decimals]
      properties:
        mint: { type: string }
        baseUnits: { $ref: '#/components/schemas/BaseUnitsString' }
        decimals: { type: integer, minimum: 0, maximum: 18 }
    OperationMode:
      type: string
      enum: [SIGNED, AUTO_EXITS, AUTO_FULL]
      description: Assinada (padrão), Automática nas saídas, Automática total
    RealOrderState:
      type: string
      enum: [PROPOSED, QUOTED, SIGNED, SENT, CONFIRMED, FAILED, AMBIGUOUS, CANCELLED]
    PausedReason:
      type: string
      enum: [NONE, USER, DAILY_LOSS, DIVERGENCE, KILL_SWITCH, ADMIN, UPSTREAM]
    TxSignature:
      type: string
      pattern: '^[1-9A-HJ-NP-Za-km-z]{64,90}$'
    SolanaAddress:
      type: string
      pattern: '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
```

### Mensagem de consentimento (usada por R1, R2, R3 e M9)

A API (M9) e o TxSigner (R1) conferem o **mesmo** texto, montado só pelo servidor a partir do desafio e assinado pela carteira principal com `signMessage` (Ed25519 sobre os bytes UTF-8). Formato canônico: linhas `chave: valor` em ordem fixa, separadas por `\n` (LF), sem espaço no fim, sem linha extra:

```yaml
shared-schema:
  ConsentMessage:
    line1: "Eureka Trader - consentimento REAL v1"   # separador de domínio; a mensagem de login começa com outro título e nunca contém esta linha
    line2: "purpose: ACTIVATE | RAISE_MODE | RAISE_CAP"
    line3: "network: LOCALNET | DEVNET | MAINNET"
    line4: "main-wallet: <endereço Base58 da carteira principal verificada no login>"
    line5: "bot-wallet: <endereço Base58 | NEW>"          # NEW só na ativação
    line6: "mode: SIGNED | AUTO_EXITS | AUTO_FULL"
    line7: "cap-lamports: <inteiro>"
    line8: "nonce: <32 bytes em Base58>"
    line9: "issued-at: <ISO 8601 UTC com Z>"
    line10: "expires-at: <ISO 8601 UTC com Z>"          # issued-at + 5 min
  verification:
    - a assinatura confere com a chave pública de main-wallet
    - expires-at no futuro; nonce nunca usado antes (API e TxSigner guardam o último nonce por carteira)
    - network igual à rede do ambiente
    - mode e cap-lamports iguais aos valores do pedido; cap-lamports <= máximo do perfil
    - "mensagem com o título de login, ou sem a linha 1 exata, é recusada (CONSENT_INVALID)"
```

A mesma assinatura nunca serve para login nem para operar: o login usa outro título, e o consentimento só autoriza mudar a política, nunca uma ordem específica.

### R1 — API interna do TxSigner (U1)

Só na rede privada; credencial de serviço própria (`Authorization: Bearer <service-token>` no local, mTLS na nuvem); sem credencial válida, `401`; credencial de outro serviço, `403`. A API e o worker não têm permissão de assinar no KMS; só o TxSigner tem. O TxSigner confere de novo toda assinatura de consentimento e aplica a política de conteúdo de [components.md](../domain-design/components.md) antes de assinar. O contexto enviado pela BotWallet só pode restringir.

**Falha fechada:** para `SWAP_BUY` e `SWAP_SELL`, `context` é obrigatório; sem ele, ou com qualquer campo fora da política, a resposta é `422 SIGNING_POLICY_REFUSED`. Em `WITHDRAWAL`, o destino tem de ser a `mainWalletAddress` gravada na WalletPolicy na criação, que nunca muda (não existe rota de troca).

**Idempotência:** `/wallets` e `/consents` são idempotentes por `requestId`, e `/signatures` por `signingRequestId`: repetir devolve a mesma resposta, sem nova chave nem nova assinatura.

**Risco residual aceito (decisions.md, ADR-010):** a carteira principal gravada na criação vem da API. Uma API invadida poderia criar uma carteira do bot ligada à carteira de um atacante, desde que tivesse a assinatura dessa carteira, mas não consegue sacar para fora da carteira principal de uma carteira já criada nem assinar fora da política. Mitigações: o endereço principal é mostrado ao usuário antes do primeiro depósito; um depósito de origem diferente da carteira principal fica marcado (`knownSource=false`) e gera alerta; o endereço principal nunca muda depois da criação.

```yaml
openapi: 3.0.3
info: { title: TxSigner interno, version: 1.0.0 }
servers: [{ url: /internal/v1 }]
paths:
  /wallets:
    post:
      summary: Cria a chave no KMS e a WalletPolicy a partir do consentimento de ativação
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [requestId, mainWalletAddress, consentMessage, consentSignature]
              properties:
                requestId: { type: string, format: uuid }
                mainWalletAddress: { $ref: 'common#/components/schemas/SolanaAddress' }
                consentMessage: { type: string, description: texto canônico do consentimento }
                consentSignature: { type: string, description: Ed25519 em Base58 }
      responses:
        '201':
          content:
            application/json:
              schema:
                type: object
                required: [botWalletAddress, kmsKeyRef]
                properties:
                  botWalletAddress: { $ref: 'common#/components/schemas/SolanaAddress' }
                  kmsKeyRef: { type: string, description: referência opaca; nunca a chave }
        '401': { description: sem credencial de serviço }
        '403': { description: credencial de outro serviço, ou REAL_DISABLED no TxSigner }
        '422': { description: CONSENT_INVALID ou CONSENT_EXPIRED }
  /wallets/{botWalletAddress}/consents:
    post:
      summary: Atualiza a WalletPolicy (subir modo ou teto) a partir de consentimento assinado; baixar só restringe e não exige assinatura
      parameters:
        - { name: botWalletAddress, in: path, required: true, schema: { type: string } }
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [requestId, kind]
              properties:
                requestId: { type: string, format: uuid }
                kind: { type: string, enum: [RAISE_MODE, RAISE_CAP, LOWER_MODE, LOWER_CAP, CLOSE] }
                consentMessage: { type: string }
                consentSignature: { type: string }
                lowerTo: { type: object, description: só para LOWER_*; modo ou teto mais restritos }
      responses:
        '200': { description: política atualizada }
        '422': { description: CONSENT_INVALID }
  /signatures:
    post:
      summary: Assina uma transação serializada se ela passar pela política
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [signingRequestId, botWalletAddress, operationKind, transactionBase64]
              properties:
                signingRequestId: { type: string, format: uuid, description: idempotente; repetir devolve a mesma resposta }
                botWalletAddress: { type: string }
                operationKind: { type: string, enum: [SWAP_BUY, SWAP_SELL, WITHDRAWAL] }
                transactionBase64: { type: string }
                context:
                  type: object
                  properties:
                    inputLamports: { $ref: 'common#/components/schemas/LamportsString' }
                    minOut: { $ref: 'common#/components/schemas/BaseUnitsString' }
                    quotedOut: { $ref: 'common#/components/schemas/BaseUnitsString' }
                    maxSlippageBps: { type: integer, minimum: 0, maximum: 500 }
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                required: [signature, signedTransactionBase64]
                properties:
                  signature: { $ref: 'common#/components/schemas/TxSignature' }
                  signedTransactionBase64: { type: string }
        '401': { description: sem credencial de serviço }
        '403': { description: REAL_DISABLED para swap com o flag desligado; saque continua }
        '422':
          description: SIGNING_POLICY_REFUSED; o corpo traz só um código de motivo (ex. CONTEXT_MISSING, PROGRAM_NOT_ALLOWED, DESTINATION_NOT_ALLOWED, MIN_OUT_TOO_LOW, CAP_EXCEEDED), nunca a transação
        '503': { description: KMS indisponível }
```

Comportamento: tempo limite de 2 s no cliente, uma repetição com o mesmo `signingRequestId` (Q9). Cada assinatura e cada recusa grava um `SigningRecord` no banco próprio do TxSigner e um evento de auditoria. Nenhuma rota devolve material de chave; não existe rota de importação ou exportação.

### R2 — Carteira do bot (U1)

```yaml
openapi: 3.0.3
info: { title: REAL — carteira do bot, version: 1.0.0 }
paths:
  /api/v1/real/consents/challenge:
    post:
      summary: Emite o desafio de consentimento (ativação, subir modo, subir teto)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [kind]
              properties:
                kind: { type: string, enum: [ACTIVATE, RAISE_MODE, RAISE_CAP] }
                mode: { $ref: 'common#/components/schemas/OperationMode' }
                capLamports: { $ref: 'common#/components/schemas/LamportsString' }
      responses:
        '201':
          content:
            application/json:
              schema:
                type: object
                required: [challengeId, message, nonce, expiresAt]
                properties:
                  challengeId: { type: string, format: uuid }
                  message: { type: string, description: texto canônico para assinar; diferente do login }
                  nonce: { type: string }
                  expiresAt: { type: string, format: date-time }
  /api/v1/real/bot-wallet:
    get:
      summary: Visão da carteira do bot do próprio usuário
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BotWalletView' }
        '404': { description: sem carteira ativa }
    post:
      summary: Ativa a carteira do bot (termos v3, liberação vigente e consentimento assinado)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [challengeId, signature]
              properties:
                challengeId: { type: string, format: uuid }
                signature: { type: string }
      responses:
        '201':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BotWalletView' }
        '403': { description: REAL_DISABLED, REAL_NOT_RELEASED ou TERMS_REACCEPTANCE_REQUIRED }
        '409': { description: BOT_WALLET_ALREADY_ACTIVE }
        '422': { description: CONSENT_INVALID ou CONSENT_EXPIRED }
  /api/v1/real/bot-wallet/resume:
    post:
      summary: Retoma o REAL pausado pelo próprio usuário; pausas por divergência, perda do dia, kill switch ou administrador não são retomadas aqui
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BotWalletView' }
        '409': { description: REAL_NOT_PAUSED ou REAL_PAUSED (pausa de outro motivo) }
  /api/v1/real/bot-wallet/preferences:
    patch:
      summary: Teto, derrapagem e pausa; subir o teto exige challengeId e signature; paused só aceita true (retomar é a rota resume)
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                capLamports: { $ref: 'common#/components/schemas/LamportsString' }
                maxSlippageBps: { type: integer, minimum: 0, maximum: 500 }
                paused: { type: boolean }
                challengeId: { type: string, format: uuid }
                signature: { type: string }
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BotWalletView' }
        '422': { description: CAP_ABOVE_MAXIMUM ou CONSENT_INVALID }
  /api/v1/real/bot-wallet/deposits:
    get:
      summary: Depósitos detectados (paginado)
      responses:
        '200': { description: '{ items: Deposit[], nextCursor, total }' }
  /api/v1/real/bot-wallet/withdrawals:
    post:
      summary: Saque só para a carteira principal verificada; sem campo de destino
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [amount]
              properties:
                amount:
                  oneOf:
                    - { type: object, required: [lamports], properties: { lamports: { $ref: 'common#/components/schemas/LamportsString' } } }
                    - { type: object, required: [all], properties: { all: { type: boolean, enum: [true] } } }
      responses:
        '202':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Withdrawal' }
        '409': { description: ORDER_AMBIGUOUS no saque total enquanto houver ordem ou saque ambíguo }
        '422': { description: INSUFFICIENT_FREE_BALANCE }
    get:
      summary: Saques (paginado)
      responses:
        '200': { description: '{ items: Withdrawal[], nextCursor, total }' }
  /api/v1/real/bot-wallet/withdrawals/{withdrawalId}:
    get:
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Withdrawal' }
components:
  schemas:
    BotWalletView:
      type: object
      required: [address, status, mode, capLamports, maxCapLamports, balance, pausedReason, network]
      properties:
        address: { $ref: 'common#/components/schemas/SolanaAddress' }
        status: { type: string, enum: [ACTIVE, CLOSED] }
        mode: { $ref: 'common#/components/schemas/OperationMode' }
        capLamports: { $ref: 'common#/components/schemas/LamportsString' }
        maxCapLamports: { $ref: 'common#/components/schemas/LamportsString' }
        maxSlippageBps: { type: integer }
        balance:
          type: object
          required: [totalLamports, freeLamports, reservedLamports, feeReserveLamports, aboveCapLamports]
          properties:
            totalLamports: { $ref: 'common#/components/schemas/LamportsString' }
            freeLamports: { $ref: 'common#/components/schemas/LamportsString' }
            reservedLamports: { $ref: 'common#/components/schemas/LamportsString' }
            feeReserveLamports: { $ref: 'common#/components/schemas/LamportsString' }
            aboveCapLamports: { $ref: 'common#/components/schemas/LamportsString', description: só disponível para saque (FR3.2) }
            asOf: { type: string, format: date-time }
        pausedReason: { $ref: 'common#/components/schemas/PausedReason' }
        network: { type: string, enum: [LOCALNET, DEVNET, MAINNET] }
    Deposit:
      type: object
      required: [txSignature, lamports, finalizedAt, knownSource]
      properties:
        txSignature: { $ref: 'common#/components/schemas/TxSignature' }
        lamports: { $ref: 'common#/components/schemas/LamportsString' }
        finalizedAt: { type: string, format: date-time }
        knownSource: { type: boolean, description: falso quando a origem não é a carteira principal }
    Withdrawal:
      type: object
      required: [withdrawalId, lamports, state, requestedAt]
      properties:
        withdrawalId: { type: string, format: uuid }
        lamports: { $ref: 'common#/components/schemas/LamportsString' }
        state: { $ref: 'common#/components/schemas/WithdrawalState' }
        txSignature: { $ref: 'common#/components/schemas/TxSignature' }
        requestedAt: { type: string, format: date-time }
```

### R3 — Ordens, modo e assinatura (U4)

```yaml
openapi: 3.0.3
info: { title: REAL — ordens e modo, version: 1.0.0 }
paths:
  /api/v1/real/mode:
    put:
      summary: Troca o modo; rebaixar é imediato, subir exige challengeId e signature (FR4.2); a U4 aplica pela M1 (applyConsent ou lowerMode) e a BotWallet repassa ao TxSigner
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [mode]
              properties:
                mode: { $ref: 'common#/components/schemas/OperationMode' }
                challengeId: { type: string, format: uuid }
                signature: { type: string }
      responses:
        '200': { description: modo aplicado }
        '409': { description: ORDER_AMBIGUOUS ao subir de modo com ordem ambígua não conferida }
        '422': { description: CONSENT_INVALID }
  /api/v1/real/orders:
    get:
      summary: Ordens REAL do usuário (paginado, filtro por bot e estado)
      responses:
        '200': { description: '{ items: RealOrder[], nextCursor, total }' }
  /api/v1/real/orders/{realOrderId}:
    get:
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RealOrder' }
  /api/v1/real/orders/{realOrderId}/signing-session:
    get:
      summary: Modo Assinada — transação não assinada montada pelo servidor
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: 'common#/components/schemas/SigningSession' }
        '409': { description: SIGNING_DEADLINE_EXPIRED }
    post:
      summary: Recebe a transação assinada pela carteira principal; o servidor confere e envia (Q5)
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: 'common#/components/schemas/SignedTransactionRequest' }
      responses:
        '202':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RealOrder' }
        '409': { description: SIGNING_DEADLINE_EXPIRED }
        '422': { description: SIGNED_MESSAGE_MISMATCH }
  /api/v1/real/orders/{realOrderId}/reject:
    post:
      summary: O usuário recusa a assinatura; a ordem é cancelada
      responses:
        '200': { description: CANCELLED }
  /api/v1/real/liquidations:
    post:
      summary: Vender tudo e sacar (FR3.4)
      responses:
        '202': { description: 'ordens de venda criadas; depois da última confirmação a U4 pede o saque total pela M1 (requestWithdrawal, chave de idempotência derivada da liquidação)' }
        '409': { description: ORDER_ALREADY_IN_PROGRESS (liquidação em andamento) ou ORDER_AMBIGUOUS }
  /api/v1/real/pnl:
    get:
      summary: P&L REAL com taxas, nunca misturado com PAPER
      responses:
        '200': { description: mesma forma de PnlReport, valores em SignedLamportsString e TokenAmount; marcação a mercado pela M4 }
components:
  schemas:
    RealOrder:
      type: object
      required: [realOrderId, botId, side, input, operationMode, state, createdAt]
      properties:
        realOrderId: { type: string, format: uuid }
        botId: { type: string, format: uuid }
        side: { type: string, enum: [BUY, SELL] }
        input: { $ref: 'common#/components/schemas/TokenAmount' }
        output: { $ref: 'common#/components/schemas/TokenAmount' }
        operationMode: { $ref: 'common#/components/schemas/OperationMode' }
        state: { $ref: 'common#/components/schemas/RealOrderState' }
        txSignature: { $ref: 'common#/components/schemas/TxSignature' }
        failureCode: { type: string }
        feeLamports: { $ref: 'common#/components/schemas/LamportsString' }
        createdAt: { type: string, format: date-time }
        updatedAt: { type: string, format: date-time }
```

As ordens nascem da decisão do bot (M6), nunca de um `POST` do navegador. No modo Assinada, a ordem fica em `QUOTED` esperando a sessão de assinatura até o prazo.

### R4 — Administração REAL (U5)

```yaml
openapi: 3.0.3
info: { title: REAL — administração, version: 1.0.0 }
paths:
  /api/v1/admin/real/users:
    get:
      summary: Visão por usuário — modo, teto, saldo, liberação, ordens recentes, divergências (FR15.1)
      responses:
        '200': { description: '{ items: RealUserView[], nextCursor, total }' }
  /api/v1/admin/real/users/{userId}:
    get:
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RealUserView' }
  /api/v1/admin/real/users/{userId}/release:
    post:
      summary: Registra a liberação; vale 24 h depois, se os portões estiverem verdes (FR10.2)
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [network, confirmation]
              properties:
                network: { type: string, enum: [DEVNET, MAINNET] }
                confirmation: { type: string, description: texto de confirmação exigido pelo mecanismo existente }
      responses:
        '201': { description: '{ releaseId, effectiveAt }' }
    delete:
      summary: Desliga o REAL do usuário na hora; ordens não enviadas são canceladas
      responses:
        '200': { description: desligado }
  /api/v1/admin/real/users/{userId}/cap:
    put:
      summary: Baixa o teto (nunca sobe)
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [capLamports]
              properties:
                capLamports: { $ref: 'common#/components/schemas/LamportsString' }
      responses:
        '200': { description: teto baixado }
        '422': { description: CAP_INCREASE_NOT_ALLOWED quando o valor sobe o teto }
  /api/v1/admin/real/token-blocks:
    get: { responses: { '200': { description: lista de bloqueio } } }
    post:
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [mint, reason]
              properties:
                mint: { type: string }
                reason: { type: string, maxLength: 200 }
      responses: { '201': { description: bloqueado } }
  /api/v1/admin/real/token-blocks/{mint}:
    delete: { responses: { '200': { description: desbloqueado } } }
  /api/v1/admin/real/kill-switch:
    post:
      summary: Kill switch REAL — bloqueia novas ordens de todos, cancela as não enviadas; saque continua (FR8)
      responses: { '200': { description: '{ active: true, activatedAt, cancelledOrders }' } }
    delete:
      summary: Desliga o kill switch REAL
      responses: { '200': { description: '{ active: false }' } }
  /api/v1/admin/real/divergences:
    get:
      summary: Divergências de reconciliação abertas (pela M1 listDivergences)
      responses: { '200': { description: '{ items: Divergence[], nextCursor, total }' } }
  /api/v1/admin/real/divergences/{runId}/compensation:
    post:
      summary: Resolve a divergência com lançamento compensatório (M1 compensate, que grava pela M2); despausa o usuário
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [lamports, reason, confirmation]
              properties:
                lamports: { $ref: 'common#/components/schemas/SignedLamportsString', description: igual à divergência medida, com sinal }
                mint: { type: string, description: só quando a divergência é de token }
                tokenBaseUnits: { type: string }
                reason: { type: string, maxLength: 200 }
                confirmation: { type: string }
      responses:
        '201': { description: '{ entryId, runId, resolvedAt }' }
        '409': { description: DIVERGENCE_NOT_OPEN }
        '422': { description: VALIDATION_FAILED quando o valor não bate com a divergência }
components:
  schemas:
    RealUserView:
      type: object
      required: [userId, mode, capLamports, balance, release, pausedReason, recentOrders, openDivergences]
      properties:
        userId: { type: string, format: uuid }
        mode: { $ref: 'common#/components/schemas/OperationMode' }
        capLamports: { $ref: 'common#/components/schemas/LamportsString' }
        balance:
          type: object
          properties:
            totalLamports: { $ref: 'common#/components/schemas/LamportsString' }
            freeLamports: { $ref: 'common#/components/schemas/LamportsString' }
            reservedLamports: { $ref: 'common#/components/schemas/LamportsString' }
        release:
          type: object
          properties:
            state: { type: string, enum: [NONE, PENDING_QUARANTINE, ACTIVE, REVOKED] }
            network: { type: string, enum: [DEVNET, MAINNET] }
            effectiveAt: { type: string, format: date-time }
        pausedReason: { $ref: 'common#/components/schemas/PausedReason' }
        recentOrders: { type: array, maxItems: 10, items: { $ref: 'R3#/components/schemas/RealOrder' } }
        openDivergences: { type: array, items: { $ref: '#/components/schemas/Divergence' } }
    Divergence:
      type: object
      required: [runId, botWalletId, detectedAt, divergenceLamports]
      properties:
        runId: { type: string, format: uuid }
        botWalletId: { type: string, format: uuid }
        detectedAt: { type: string, format: date-time }
        divergenceLamports: { $ref: 'common#/components/schemas/SignedLamportsString' }
        mint: { type: string }
        divergenceBaseUnits: { type: string }
```

A visão do administrador não mostra a carteira principal do usuário nem o endereço da carteira do bot por extenso, só mascarados.

Não existe comando administrativo de saque nem de envio para outro endereço (components.md, Administration).

### R5 — Saídas automáticas e exportação fiscal (U7)

```yaml
openapi: 3.0.3
info: { title: REAL — saídas e fiscal, version: 1.0.0 }
paths:
  /api/v1/real/conditional-orders:
    post:
      summary: Prepara stop ou alvo da Jupiter para a carteira principal assinar (sessão como R3)
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [positionMint, kind, triggerPrice]
              properties:
                positionMint: { type: string }
                kind: { type: string, enum: [STOP, TAKE_PROFIT] }
                triggerPrice: { type: string, description: DecimalString, escala de preço existente }
      responses:
        '201':
          content:
            application/json:
              schema:
                type: object
                required: [conditionalOrderId, signingSession]
                properties:
                  conditionalOrderId: { type: string, format: uuid }
                  signingSession: { $ref: 'common#/components/schemas/SigningSession' }
        '403': { description: REAL_DISABLED ou REAL_NOT_RELEASED }
        '422': { description: TOKEN_NOT_ELIGIBLE ou VALIDATION_FAILED }
    get:
      responses: { '200': { description: '{ items: ConditionalOrder[], nextCursor, total }' } }
  /api/v1/real/conditional-orders/{conditionalOrderId}/signature:
    post:
      summary: Recebe a transação assinada e cria a condicional na Jupiter
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: 'common#/components/schemas/SignedTransactionRequest' }
      responses:
        '202':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ConditionalOrder' }
        '409': { description: SIGNING_DEADLINE_EXPIRED }
        '422': { description: SIGNED_MESSAGE_MISMATCH }
  /api/v1/real/exports/fiscal:
    get:
      summary: Exportação do histórico REAL (CSV) com data, token, quantidade, preço, taxa e assinatura (FR14)
      parameters:
        - { name: from, in: query, schema: { type: string, format: date } }
        - { name: to, in: query, schema: { type: string, format: date } }
      responses: { '200': { content: { text/csv: {} } } }
components:
  schemas:
    ConditionalOrder:
      type: object
      required: [conditionalOrderId, positionMint, kind, triggerPrice, state, needsUserCancellation, createdAt]
      properties:
        conditionalOrderId: { type: string, format: uuid }
        positionMint: { type: string }
        kind: { type: string, enum: [STOP, TAKE_PROFIT] }
        triggerPrice: { type: string }
        state: { type: string, enum: [PENDING_SIGNATURE, OPEN, FILLED, CANCELLED, EXPIRED] }
        needsUserCancellation: { type: boolean }
        filledAt: { type: string, format: date-time }
        txSignature: { $ref: 'common#/components/schemas/TxSignature' }
        createdAt: { type: string, format: date-time }
```

`needsUserCancellation` é **calculado na leitura** pela U7, sem contrato novo: verdadeiro quando a condicional está `OPEN` e o kill switch REAL está ativo (M7) ou o modo atual do usuário não é mais `AUTO_EXITS` nem `AUTO_FULL` (M1 `readRealState`). Assim a U4 e a U5 não precisam avisar a U7. Os preenchimentos detectados são gravados no ledger REAL pela M2 (aresta U1→U7). O formato da API de condicionais da Jupiter continua em aberto e bloqueia só a U7.

### R6 — Eventos REAL no canal ao vivo (U1, U4)

```yaml
asyncapi: 2.6.0
info: { title: Canal ao vivo — eventos REAL, version: 1.0.0 }
channels:
  /api/v1/stream/dashboard:
    subscribe:
      message:
        oneOf:
          - name: RealOrderStateChanged
            payload:
              type: object
              required: [type, realOrderId, state, at]
              properties:
                type: { const: REAL_ORDER_STATE }
                realOrderId: { type: string, format: uuid }
                state: { $ref: 'common#/components/schemas/RealOrderState' }
                at: { type: string, format: date-time }
          - name: RealSigningRequested
            payload:
              type: object
              required: [type, realOrderId, deadline]
              properties:
                type: { const: REAL_SIGNING_REQUESTED }
                realOrderId: { type: string, format: uuid }
                deadline: { type: string, format: date-time }
          - name: RealDepositDetected
            payload:
              type: object
              required: [type, lamports, knownSource, at]
              properties:
                type: { const: REAL_DEPOSIT }
                lamports: { $ref: 'common#/components/schemas/LamportsString' }
                knownSource: { type: boolean }
                at: { type: string, format: date-time }
          - name: RealWithdrawalStateChanged
            payload:
              type: object
              required: [type, withdrawalId, state, at]
              properties:
                type: { const: REAL_WITHDRAWAL_STATE }
                withdrawalId: { type: string, format: uuid }
                state: { $ref: 'common#/components/schemas/WithdrawalState' }
                at: { type: string, format: date-time }
          - name: RealPaused
            payload:
              type: object
              required: [type, reason, at]
              properties:
                type: { const: REAL_PAUSED }
                reason: { $ref: 'common#/components/schemas/PausedReason' }
                at: { type: string, format: date-time }
```

O canal só leva o que é do próprio usuário (tenant e usuário do token). Os eventos são aviso; a fonte da verdade é o `GET`. A tela de assinatura também consulta `GET /api/v1/real/orders/{id}` a cada 1 s enquanto o diálogo está aberto (Q8).

### M1 a M9 — Interfaces de módulo

```yaml
shared-schema:
  M1_BotWalletPort:                 # dono U1 (BotWallet)
    readRealState(tenantId, userId) -> RealState{ mode, capLamports, feeReserveLamports, pausedReason, released, botWalletAddress }
    signSwap(tenantId, userId, SwapSigningRequest{ signingRequestId, transactionBase64, operationKind, inputLamports, minOut, quotedOut, maxSlippageBps }) -> SignedTransaction | SigningRefused
    recheckBeforeSend(tenantId, userId, realOrderId) -> Allowed | Cancelled{ reason }
    applyConsent(tenantId, userId, challengeId, signature) -> RealState | ConsentInvalid   # subir modo ou teto (U4 PUT /mode; U1 preferences); confere pela M9 e repassa ao TxSigner
    lowerMode(tenantId, userId, mode) -> RealState                                        # rebaixar, imediato, repassado ao TxSigner como LOWER_MODE
    pauseUser(tenantId, userId, PausedReason)
    resumeUser(tenantId, userId, PausedReason) -> RealState | NotPaused                   # só retoma a pausa do mesmo motivo
    requestWithdrawal(tenantId, userId, Amount{ lamports | ALL }, idempotencyKey) -> WithdrawalView   # usado pela liquidação da U4
    adminRelease(userId, network) -> ReleaseView        # usado pela U5
    adminRevoke(userId); adminLowerCap(userId, capLamports)
    adminUserView(userId) -> RealUserViewData            # dados da carteira para a visão R4
    listDivergences(cursor, limit) -> Page<DivergenceView>
    compensate(runId, Compensation{ lamports(signed), mint?, tokenBaseUnits?, reason }, adminId) -> entryId   # grava pela M2 e retoma a pausa DIVERGENCE
    closeForAnonymization(tenantId, userId) -> Closed | Refused{ BALANCE | POSITION | ORDER_IN_PROGRESS }
  M2_RealLedger:                    # dono U1 (Ledger)
    append(RealEntry{ tenantId, entryKind: DEPOSIT|WITHDRAWAL|SWAP_FILL_IN|SWAP_FILL_OUT|NETWORK_FEE|RENT|RESERVE|RESERVE_RELEASE|COMPENSATION, botId?, botWalletId, mint?, tokenBaseUnits?, lamports(signed), txSignature?, idempotencyKey })
    writers: [RealExecution (fills, fees, reserves; U4 e as condicionais da U7), BotWallet (deposits, withdrawals, compensations)]
    freeBalance(tenantId, botWalletId) -> lamports      # já desconta RESERVE
    exposure(tenantId, botWalletId) -> positions[]
    dayLoss(tenantId, botId, sinceUtc) -> lamports
    rule: append-only; UPDATE e DELETE falham; toda leitura filtra mode = REAL
  M3_ChainPort:                     # dono U1 (ChainWatcher)
    send(signedTransactionBase64) -> txSignature
    status(txSignature) -> FINALIZED | FAILED | PENDING | UNCERTAIN
    balance(address) -> lamports; incoming(address, sinceSignature) -> Transfer[]
    blockhash() -> { blockhash, lastValidHeight }
  M4_MarketDataPort:                # dono U2 (amplia C11)
    quote(chain, mint) -> MarketValue + { source, observedAt, sourceKind: REAL|SIMULATED }
    activeSourceKind() -> REAL|SIMULATED
  M5_RealRiskGate:                  # dono U3
    evaluate(tenantId, userId, botId, OrderIntent{ side, mint, inputLamports }) -> Approved | Rejected{ code }
    codes: [REAL_CAP_EXCEEDED, REAL_LIMIT_EXCEEDED, TOKEN_NOT_ELIGIBLE, STALE_DATA, SIMULATED_SOURCE_ACTIVE, REAL_PAUSED]
    rule: vendas de saída sempre passam; conferência sob trava
    blockToken(mint, reason, adminId); unblockToken(mint)   # usado pela U5
  M6_RealExecutionPort:             # dono U4
    recordDecision(RealDecision) -> decisionRequestId    # Propagation.MANDATORY: só dentro da transação do ciclo
    cancelUnsent(scope: USER(userId) | ALL, reason) -> count
    recentOrders(tenantId, userId, limit) -> RealOrderView[]
  M7_RealKillSwitchState:           # dono U5
    current() -> { active, activatedAt }
  M8_AuditOutbox:                   # contrato C9 existente, validador mudado na U1
    typedRealFields: { txSignature, mint, botWalletAddress }   # aceitos só nessas chaves
    rule: carteira principal só como HMAC; details livre mantém a regra atual; logs mascaram endereço e assinatura
  M9_IdentityPort:                  # dono U1 (Identity)
    termsV3Accepted(userId) -> boolean
    verifyConsent(userId, message, signature) -> Valid | Invalid{ reason }   # Ed25519 nativo do Java
    mainWallet(userId) -> address
```

### X1 a X4 — Portas de fornecedor

| Porta | Operações usadas | Tempo limite e repetição (Q9) | Simulada no local |
|---|---|---|---|
| X1 Jupiter | cotação, transação de swap, criar e consultar condicional | 2 s; cotação vencida (mais de 10 s) nunca é reaproveitada | respostas gravadas; swaps no validador local com contas clonadas |
| X2 CoinGecko | preço, liquidez, volume, holders, criação | 5 s; uma repetição em leitura | fonte `sim-a`/`sim-b` existente, marcada `SIMULATED` |
| X3 Solana RPC | envio, estado, saldo, entradas, blockhash | 3 s por provedor, troca para o segundo; envio repete só com a mesma transação assinada | validador local |
| X4 AWS KMS | criar chave, assinar Ed25519 | dentro dos 2 s do R1 | KMS simulado no processo do TxSigner, só no perfil local e de teste |

Falha de X1, X3 ou R1 pausa novas ordens (`UPSTREAM_UNAVAILABLE`) sem afetar o saque, salvo quando é o próprio saque que falha: ele fica `FAILED` ou `AMBIGUOUS` e é conferido na rede antes de nova tentativa (NFR4).

## Regras de propriedade dos contratos

- Cada contrato tem o dono da tabela; só o dono muda a especificação (Q10).
- R2 a R5 nascem do código; a CI compara o OpenAPI gerado com o versionado e falha em qualquer diferença não comitada.
- Mudança aditiva é livre: consumidores ignoram campo desconhecido e valor de enum novo vira estado "desconhecido" na tela, nunca erro.
- Quebra exige nova versão de rota (`/api/v2/...` ou `/internal/v2/...`), com as duas versões vivas até o consumidor migrar.
- O TxSigner é implantado antes da API em toda mudança do R1; a API nunca chama uma versão que o TxSigner ainda não tem.
- As interfaces M1 a M9 mudam junto com os consumidores no mesmo commit (monólito); quebra numa interface exige atualizar todas as Units que a usam.

## Open questions

| Contract | Question | Blocks |
|---|---|---|
| R2 | Valor exato da reserva mínima para taxas (`feeReserveLamports`) | U1 (Functional Design) |
| R4 | Critério objetivo de "portões verdes" que a liberação confere (FR10.2) | U5 |
| R1 | Prova técnica: o KMS escolhido assina Ed25519 e devolve a assinatura no formato da Solana | U1 |
| X1 | Prova técnica: a transação da Jupiter é assinável e serializável sem SDK Solana na produção | U4 |
| X1 | Formato e autenticação da API de condicionais (Trigger) da Jupiter | U7 |
| X2 | Termos do plano pago da CoinGecko para exibição com atribuição | U2 |
| R1 | Forma da credencial de serviço no local (token) e na nuvem (mTLS) — detalhe no Infrastructure Design | U1 |
| R2 | Registrar no ADR-008 a exceção da regra de novo aceite dos termos para a leitura da carteira e o saque, antes da Construction | U1 |

## Adendo da U1 (Code Generation de `u1-bot-wallet`, 2026-11-14)

Mudanças aceitas pelo condutor e aplicadas no código. A decisão está em `docs/adr/ADR-0006-txsigner-processo-proprio-e-ancora-da-carteira.md`.

### R1 — API interna do TxSigner

| Item | Mudança |
|---|---|
| `POST /wallets` 201 | Devolve só `botWalletAddress`. A referência da chave (`kmsKeyRef`) nunca sai do TxSigner, como pede o NFR1.7. A API não a guarda: a tabela `bot_wallet_account` não tem essa coluna. |
| `POST /signatures`, campo `context` | Ganha `feeLamports` (inteiro em texto): a taxa calculada antes de assinar (BR4.4, NFR4.3). Em `WITHDRAWAL`, `inputLamports` é o valor do saque. Se faltar um dos dois, a resposta é `422 SIGNING_POLICY_REFUSED` com o motivo `CONTEXT_MISSING`. |
| Códigos de recusa (`reason`) | Além dos de BR5.1, a rota pode devolver: `MALFORMED_TRANSACTION`, `CONTEXT_MISSING`, `PROGRAM_NOT_ALLOWED`, `ACCOUNT_NOT_ALLOWED`, `FEE_MISMATCH`, `OPERATION_NOT_SUPPORTED`, `WALLET_NOT_FOUND`, `WALLET_BLOCKED` e `REQUEST_MISMATCH`. `REAL_DISABLED` responde 403, e `RECOVERY_IN_PROGRESS` responde 503. |
| Idempotência de `/consents` | É feita pelo `requestId`, gravado na tabela `policy_change` do banco do TxSigner. |
| Repetição de `/signatures` | O `SigningRecord` guarda a transação assinada. A repetição devolve a mesma resposta sem chamar o KMS de novo. |
| Recuperação | `POST /recovery/wallets` recebe `botWalletAddress`, `mainWalletAddress`, `currentCapLamports` e `currentMode`. Há também a rota nova `POST /recovery/complete`, que grava o `recoveryCompletedAt` na tabela `recovery_run`. |
| Leituras internas | `GET /wallets?createdBefore=` e `GET /signatures?since=` funcionam como no adendo da infraestrutura. Nenhuma das duas devolve a referência da chave nem a transação assinada. |
| Credencial | 401 sem token válido. 403 para um certificado de cliente cujo CN não é `api` nem `worker`. |
| Auditoria do TxSigner | Uma linha JSON no logger `audit` mais o contador `txsigner_decisions_total`, sem tabela de saída de eventos. |
| Etiqueta da âncora | `mainWalletMac` (HMAC do KMS sobre o endereço), nunca o endereço em si. |

### X4 — KMS

O KMS simulado é o contêiner `kms-sim` (`infra/kms-sim`), que fala o protocolo JSON do AWS KMS. Ele só existe no Compose do REAL (`infra/docker-compose.real.yml`). Nos testes de unidade do TxSigner, o mesmo protocolo é servido em memória sobre o `KmsSimulator`. O perfil de produção do TxSigner recusa qualquer outro endereço de KMS.

### R2 — Carteira do bot

| Item | Mudança |
|---|---|
| Nomes dos esquemas no OpenAPI | Os nomes ganham o prefixo `Real` (`RealChallengeRequest`, `RealChallengeView`, `RealActivationRequest`, `RealWithdrawalRequest`, `RealWithdrawalView`, `RealDepositView`, `RealBalanceView`, `RealPage`). Assim eles não colidem com os esquemas do login. |
| `BotWalletView` | Ganha `mainWalletAddress` (só para o dono), `balance.operableLamports` e `policyResetRequired`. |
| Saque | O corpo `{ amount: { lamports } | { all: true } }` não aceita campo de destino. Um campo a mais responde `400 VALIDATION_FAILED`. |

## Adendo da U3 (Code Generation de `u3-real-risk`, 2026-11-14)

Mudanças aplicadas no código no mesmo trabalho da U3, como manda a regra das interfaces de módulo (M1 a M9 mudam junto com os consumidores). A U4 ainda não existe, então nenhum consumidor quebra. As fontes são `construction/u3-real-risk/functional-design/` (Q11) e `construction/u3-real-risk/nfr-design/` (Q3).

### M1 — `botwallet.api`

| Item | Mudança |
|---|---|
| `readRealState` | Vira `RealStateQuery.readRealState(tenantId, userId) -> Optional<RealStateView>`. Vazio quando a pessoa não tem carteira ATIVA naquele tenant (a carteira de outro tenant nunca é lida). |
| `RealStateView` | `{ botWalletId, realEnabled, released, mode, capLamports, feeReserveLamports, pausedReasons, pausedSince }`. O campo `pausedReason` do contrato vira o conjunto `pausedReasons` (`RealPauseReason`: `USER`, `DIVERGENCE`, `DAILY_LOSS`, `KILL_SWITCH`, `ADMIN`, `UPSTREAM`), porque a U1 já guarda um conjunto. `pausedSince` traz o início de cada pausa ativa; um motivo gravado antes da U3 começa na última mudança das preferências. `botWalletAddress` não entra: a barreira não precisa do endereço. |
| `pauseUser`, `resumeUser` | `RealPauses.pauseUser` e `resumeUser(tenantId, userId, RealPauseReason) -> PauseOutcome` (`PAUSED`, `ALREADY_PAUSED`, `RESUMED`, `NOT_PAUSED`, `NO_WALLET`). Cada um muda só o próprio motivo, numa instrução atômica, na transação de quem chama, e audita (`REAL_PAUSED`, `REAL_RESUMED`, ator `system`) só quando mudou algo. |
| Leituras dos trabalhos | Novas: `walletsInForce()` (carteiras ATIVAS com liberação vigente; vazia com o REAL desligado) e `pausedWallets(reason)` (com o início da pausa). |
| Banco | `real_preferences.paused_since jsonb` (migração 0018), mantida pela BotWallet na mesma instrução que muda `paused_reasons`. |
| Preferências da pessoa | O `savePreferences` da U1 passa a mudar só o motivo `USER`, numa instrução atômica sobre a linha atual; os outros motivos (e o seu início) só mudam por `pauseUser` e `resumeUser`. Assim uma pausa `DAILY_LOSS` gravada entre a leitura e a gravação das preferências nunca se perde (revisão R-06). |

### M2 — `ledger.api`

| Item | Mudança |
|---|---|
| `positions` | `RealLedger.positions(tenantId, botWalletId) -> List<RealPosition{ mint, tokenBaseUnits, costLamports }>`, derivada dos `SWAP_FILL_IN` e `SWAP_FILL_OUT` pelo custo médio; o custo que fica na posição é arredondado para cima (a parte vendida, para baixo). |
| `flowsSince` | `RealLedger.flowsSince(tenantId, botWalletId, since) -> RealFlows{ depositedLamports, withdrawnLamports }`, só o que foi gravado depois de `since`; o saque conta com a taxa (`NETWORK_FEE` de referência `wd:`). |
| `RealBalance` | Ganha `orderReservedLamports` (reservas `order:`, inclusive das ordens ambíguas) e `withdrawalReservedLamports` (reservas `wd:`). O construtor antigo continua, com as duas partes em zero. |
| `view` | `RealLedger.view(tenantId, botWalletId, Optional<Instant> flowsSince) -> RealLedgerView{ balance, positions, flows }`: as três leituras numa só instrução SQL, portanto num só snapshot do PostgreSQL. A barreira e os seus trabalhos usam só ela, para que um preenchimento ou depósito gravado no meio nunca conte duas vezes (revisão R-01). |
| `RealEntry` | Ganha `mint` e `tokenBaseUnits` e a fábrica `swapFill`. Convenção para a U4: `SWAP_FILL_IN` traz o token (lamports negativos), `SWAP_FILL_OUT` leva o token (lamports positivos); a quantidade é sempre positiva e o mint é obrigatório. Fora disso a gravação é recusada. As taxas do swap continuam em `NETWORK_FEE` e não entram no custo da posição. |

### M4 — `marketdata.api`

| Item | Mudança |
|---|---|
| `referencePrices(Set<TokenId>)` | Preço de vários tokens numa só consulta, com o mesmo `observedAt` e a mesma regra de `unknown` de `referencePrice`. Todo token pedido tem resposta. |
| `decimalsOf(Set<TokenId>)` | As casas decimais de vários tokens numa só consulta. Necessária para converter a cotação em lamports por unidade base. |
| `volume24h(TokenId)` | O volume de 24 h do token, para a conferência BR3.1. O padrão da interface é `unknown` (falha fechada). |
| Unidade do preço | A cotação da M4 é em dólar por token inteiro. A U3 converte para lamports por unidade base com a cotação do SOL embrulhado (`So11111111111111111111111111111111111111112`, lida no mesmo lote) e as casas decimais. Sem a cotação do SOL, o preço das posições fica `unknown`. A U4 precisa incluir esse mint em `WatchedTokens`. |

### M5 — `riskcontrol.api` (dono U3)

```yaml
M5_RealRiskGate:
  evaluate(OrderIntent{ tenantId, userId, botId?, decisionId?, origin: BOT|USER_LIQUIDATION, side: BUY|SELL, chain, mint,
           inputLamports? (BUY), tokenBaseUnits? (SELL) })
    -> Approved{ evaluationId, approvalId? } | Rejected{ evaluationId, code, detail, figures, detailMint? }
  handOver(tenantId, approvalId) -> HANDED_OVER | ALREADY_HANDED_OVER | RESERVATION_EXPIRED | NOT_FOUND
  blockToken(chain, mint, reason, adminId) -> BlockedToken
  unblockToken(chain, mint, adminId) -> UNBLOCKED | NOT_BLOCKED
  failure: RealGateUnavailable{ dependency: wallet|ledger|market|assessment|lock }   # nunca aprova
  rule: compra e venda passam primeiro pelo estado (BR1.1) e pela fonte (BR1.2); só depois a venda é aprovada
```

| Item | Mudança |
|---|---|
| `OrderIntent` | Ganha `origin`, `decisionId`, `chain` e `tokenBaseUnits`. Compra só com origem `BOT`; `USER_LIQUIDATION` só vende. |
| Regra das vendas | "Vendas de saída sempre passam" vale só depois do estado e da fonte. A venda do bot para em qualquer pausa; a liquidação pedida pela pessoa só para em `KILL_SWITCH` e `DIVERGENCE`. |
| `handOver` | Novo. A U4 chama na mesma transação em que grava o `RESERVE`, depois de criar a ordem. Pega a mesma trava da carteira. Uma reserva vencida vira `EXPIRED` e responde `RESERVATION_EXPIRED`; a U4 não cria a ordem. Recebe o tenant, porque toda leitura filtra por ele. |
| Transação | `evaluate` e `handOver` exigem a transação de quem chama (`MANDATORY`). A trava é por pessoa (uma carteira ATIVA por pessoa), então vem antes de qualquer leitura. `RealGateUnavailable` só é lançada antes de qualquer escrita e traz `transactionUsable()`. Ver a linha "Falha da barreira e a transação da U4". |
| Falha da barreira e a transação da U4 | Com `transactionUsable() = true` (estouro da trava de 1 s, dependência `lock`), a transação continua utilizável e não é marcada para desfazer: a U4 decide. Com `transactionUsable() = false` (erro de SQL numa leitura de `wallet`, `ledger` ou `market`), o PostgreSQL já abortou a transação: a barreira a marca para desfazer e a U4 **precisa** desfazer o ciclo, porque toda instrução seguinte falha. |
| Posse do bot (BR6.3) | **Pré-condição para a U4:** antes de chamar `evaluate`, a U4 confere que o `botId` pertence ao `tenantId` e ao `userId` da intenção (ela lê o bot com o tenant). A U3 não enxerga o módulo de bots: filtra toda leitura por tenant e pessoa, e a carteira de outro tenant nunca é lida (`NO_BOT_WALLET`), mas um `botId` alheio passado com o tenant e a pessoa certos só serviria de chave da contagem (BR4.2) e dos registros. A pré-condição está no javadoc da M5. |
| `blockToken`, `unblockToken` | Recebem também a `chain`. Abrem a própria transação. Só o módulo `administration` pode chamar (regra ArchUnit). Bloqueio repetido não muda nada. |
| Códigos | Os seis do contrato, com o detalhe de `entities.md`; `SIMULATED_SOURCE_ACTIVE` tem o detalhe `NONE`. |
| Auditoria | `REAL_ORDER_REJECTED` (ator `system` e alvo `bot:<botId>`; na liquidação, a pessoa e `user:<userId>`), `REAL_TOKEN_BLOCKED` e `REAL_TOKEN_UNBLOCKED` (o administrador, sem tenant). O mint vai só no campo tipado da M8. |

## Adendo da U4 (Code Generation de `u4-real-execution`, 2026-11-15)

Mudanças aplicadas no código no mesmo trabalho da U4. Os consumidores mudaram junto (bots, painel, P&L e TxSigner), como manda a regra das interfaces de módulo. As fontes são `construction/u4-real-execution/functional-design/`, `nfr-design/` (Q2: a mensagem é montada na API e lida no TxSigner) e `infrastructure-design/`.

### R1 — API interna do TxSigner: operação `SWAP`

| Item | Mudança |
|---|---|
| `operationKind` | Ganha `SWAP`. O pedido continua em `POST /internal/v1/signatures`, com `signingRequestId = realOrderId` (o mesmo pedido repetido devolve a mesma assinatura e nunca gera outra transação). |
| `transactionBase64` | A transação v0 **montada pela API** (sem tabela de consulta de endereços), com as chaves estáticas na ordem do Solana. No modo Assinada ela chega já assinada pela carteira principal (slot 0, pagadora) e o TxSigner assina o slot 1; em Automática total a carteira do bot é a única assinante e pagadora. |
| `signatureContext` | Ganha `mode` (`SIGNED` ou `AUTO_FULL`), `side` (`BUY` ou `SELL`), `inputBaseUnits` (venda, até 19 dígitos) e `mint` (o token da ordem, Base58, obrigatório: a origem e o destino da rota ficam presos a ele; recusa `SOURCE_NOT_ALLOWED`), além de `inputLamports`, `feeLamports`, `minOut`, `quotedOut` e `maxSlippageBps`. Todos os valores trafegam como texto inteiro. |
| Política (ADR-004) | O TxSigner lê a mensagem e recusa tudo fora da lista: programas permitidos (Compute Budget, Token, Token-2022, Associated Token, as rotas `route` e `shared_accounts_route` da Jupiter v6, uma vez, e o System só para embrulhar SOL da própria carteira do bot), assinante e pagador, destinos (só contas de token associadas da carteira do bot, derivadas no TxSigner), saída mínima abaixo da cotação menos a derrapagem, derrapagem acima de 500 bps, troca de autoridade, fechamento de conta para terceiros, taxa de prioridade acima de `max-swap-priority-fee-lamports` (no máximo 1.000.000) e valor acima do teto. Cada recusa tem o seu motivo (`PolicyRefusal`, 13 motivos novos) e conta em `txsigner_swap_decisions_total{outcome,reason}`. |
| Configuração padrão | Com `txsigner.real.enabled=false` nenhum swap é assinado (`REAL_DISABLED`). O perfil de produção recusa `KMS_SIM_SEED`, o endereço do KMS simulado e uma rede diferente da combinada. |
| Vetores comuns | `src/test/resources/solana-vectors/vectors.json`, gerado pelo `solanaj` só no escopo de teste, é o mesmo arquivo na API e no TxSigner (teste de igualdade por SHA-256). |

### M6 — `realexecution.api` (dono U4)

```yaml
M6_RealExecutionPort:
  recordDecision(RealDecision{ tenantId, userId, botId, decisionId, approvalId?, side, mint,
                 inputLamports? (BUY), tokenBaseUnits? (SELL), reason }) -> decisionRequestId   # MANDATORY
  hasOrderInProgress(tenantId, botId) -> boolean
  liquidationActive(tenantId, userId) -> boolean
  cancelUnsent(CancelScope{ User(tenantId, userId) | All }, RealFailureCode) -> int
  recentOrders(tenantId, userId, limit) -> List<RealOrderView>
  settledOrders(tenantId, userId) -> List<RealOrderView>          # CONFIRMED e FAILED, para o P&L REAL
  changeMark(tenantId, userId) -> long                            # canal ao vivo
  changedSince(tenantId, userId, since, limit) -> List<RealOrderView>
```

| Item | Mudança |
|---|---|
| `recordDecision` | Exige a transação do ciclo (`MANDATORY`): um ciclo desfeito leva o pedido junto. Uma segunda chamada com o mesmo `decisionId` devolve o mesmo pedido. |
| Leituras novas | `liquidationActive`, `settledOrders`, `changeMark` e `changedSince` foram acrescentadas para o ciclo do bot (não decidir durante a liquidação), o P&L REAL e o canal ao vivo. Toda leitura filtra por tenant e pessoa. |
| `RealOrderState` | `PROPOSED`, `QUOTED`, `SIGNED`, `SENT`, `CONFIRMED`, `FAILED`, `AMBIGUOUS`, `CANCELLED`. `RealFailureCode` tem 17 códigos (`QUOTE_EXPIRED` a `ROUTE_TOO_LARGE`, `LIQUIDATION` e `NO_ROUTE`). `NO_ROUTE`: a Jupiter recusou a rota da ordem (um 4xx que não é 429); é uma recusa da ordem, fora do circuito e da pausa `UPSTREAM`. `RealOrderView` ganha `rentLamports` (o aluguel que a carteira do bot pagou, que entra no custo do P&L). |
| Posse do bot | O módulo de bots expõe `bots.api.RealBotOwnership`; a fila confere de novo a posse antes de criar a ordem. |

### M5 — `withPersonLock`

| Item | Mudança |
|---|---|
| `RealRiskGate.withPersonLock(tenantId, userId, Supplier<T>)` | Nova. Executa a ação sob a mesma trava por pessoa da barreira (1 s), na transação de quem chama. A U4 usa para criar a ordem, gravar o `RESERVE` e chamar `handOver` sem corrida com outra decisão da mesma pessoa. Trava não obtida: `RealGateUnavailable{ dependency: lock }`, sem nenhuma escrita. |

### M1 — `botwallet.api` (consumidor U4)

| Item | Mudança |
|---|---|
| `RealExecutionWallet` | Nova: `wallet`, `recheckBeforeSend` (cancela por `REAL_DISABLED`, `MODE_LOWERED`, `PAUSED` ou `KILL_SWITCH`), `signSwap` (`Signed` ou `Refused`), `lowerMode`, `applyModeConsent(Principal, mode, challengeId, signature)`, `requestWithdrawalAll` e `signerReachable`. O `applyModeConsent` recebe a pessoa da sessão, nunca uma pessoa montada fora do caminho de autenticação. |
| `RealChain` (M3) | `latestBlockhash`, `blockHeight`, `send`, `status` (`FINALIZED`, `FAILED` com derrapagem, `PENDING`, `NOT_FOUND`) e `effect` (variação de lamports e de unidades do token lidas da rede, a taxa e quem pagou). |
| Exceções | `RealUpstreamUnavailable{ provider: RPC | TXSIGNER }` e `RealConsentRefused{ code }`. |

### R3 — Ordens, modo e assinatura

| Rota | Como ficou |
|---|---|
| `GET /api/v1/real/orders` | Paginada por cursor, só as ordens da pessoa do token. |
| `GET /api/v1/real/orders/{realOrderId}` | Ordem de outra pessoa responde 404. `input` e `output` são `RealTokenAmount{ mint, baseUnits, decimals? }`, com `baseUnits` em texto inteiro e `decimals` ausente quando desconhecido (nunca zero). A taxa vem em `feeLamports` e `feePayer` (`BOT_WALLET` ou `MAIN_WALLET`). |
| `GET` e `POST /api/v1/real/orders/{realOrderId}/signing-session` | A sessão do modo Assinada: a transação a assinar e o prazo; o envio confere que a mensagem assinada é a gravada (`422 SIGNED_MESSAGE_MISMATCH`) e o prazo (`409 SIGNING_DEADLINE_EXPIRED`). |
| `POST /api/v1/real/orders/{realOrderId}/reject` | A pessoa recusa a assinatura (`USER_REJECTED`). |
| `PUT /api/v1/real/mode` | Rebaixar é imediato; subir exige `challengeId` e `signature`. Modo desconhecido responde 400. |
| `POST /api/v1/real/liquidations` | "Vender tudo e sacar": 202; uma segunda enquanto a primeira corre responde `409 ORDER_ALREADY_IN_PROGRESS`. |
| `GET /api/v1/real/pnl` | P&L REAL por custo médio, com taxas, em lamports como texto inteiro; preço desconhecido aparece como `UNKNOWN`, nunca zero; nada PAPER é lido. |
| `Idempotency-Key` | Obrigatória nas quatro rotas de escrita (`PUT /mode`, `POST /orders/{id}/signing-session`, `POST /orders/{id}/reject` e `POST /liquidations`), pelo mecanismo comum do módulo de identidade (`IdempotentOperation` `REAL_MODE`, `REAL_SIGNING_SUBMIT`, `REAL_SIGNING_REJECT` e `REAL_LIQUIDATION`). A mesma chave com o mesmo conteúdo devolve a mesma resposta; com outro conteúdo, `409 IDEMPOTENCY_CONFLICT`; sem a chave, 400. Uma liquidação nova, com outra chave, depois de `DONE` pode criar outra liquidação. |

As oito rotas estão no `route-registry.yml` (escopo `CLIENT`, com os testes negativos entre contas) e no `openapi-guest.yaml`; os tipos do app web foram gerados de novo.

### R6 — Eventos REAL no canal ao vivo

| Evento | Como ficou |
|---|---|
| `RealOrderStateChanged` | `{ type: REAL_ORDER_STATE, realOrderId, state, at }`, nome SSE `real.order.state`. |
| `RealSigningSessionOpened` | `{ type: REAL_SIGNING_REQUESTED, realOrderId, deadline }`, nome SSE `real.signing.requested`. O nome do esquema muda de `RealSigningRequested` para `RealSigningSessionOpened`; o `type` não muda. |
| Entrega | O mesmo poller do painel lê `changeMark` e `changedSince` da M6 por canal; só as ordens da pessoa do canal. |

### Campo `market` do bot

| Item | Mudança |
|---|---|
| `bot.market` | Coluna nova (`PAPER` ou `REAL`, padrão `PAPER`, imutável por gatilho), migração 0019. O campo `mode` continua `PAPER` para não quebrar o contrato de bots. |
| `BotSummary` | Ganha `market` (obrigatório), que o painel e o canal ao vivo já leem. `BotDetail` ainda não traz o campo (pendência registrada no `code-summary.md` da U4). |
| `POST /api/v1/bots` | Aceita `market` (padrão `PAPER`); `REAL` exige carteira do bot ativa (422 com o campo `market`). O bot REAL não recebe o saldo PAPER inicial. |

## Adendo da U5 (Code Generation de `u5-real-operations`, 2026-11-16)

Mudanças aplicadas no código no mesmo trabalho da U5. Os consumidores mudaram junto (administração, privacidade, barreira, execução, bots e ledger), como manda a regra das interfaces de módulo. As fontes são `construction/u5-real-operations/functional-design/`, `nfr-design/` e `infrastructure-design/`. O OpenAPI de referência é `apps/api/openapi/openapi-admin.yaml` (gerado do código) e os tipos do app administrativo foram regenerados dele.

### R4 — Administração REAL (como construída)

| Rota | Corpo | Resposta | Recusas |
|---|---|---|---|
| `GET /api/v1/admin/real/users?cursor&limit` | — | `RealAdminUserPage { items, nextCursor }` (sem ordens recentes na lista) | — |
| `GET /api/v1/admin/real/users/{userId}` | — | `RealAdminUser` com as 20 ordens mais recentes | `404 NOT_FOUND` |
| `POST /api/v1/admin/real/users/{userId}/release` | `{ network: LOCALNET \| DEVNET \| MAINNET, reason }` | `201 RealAdminRelease { releaseId, userId, network, state, effectiveAt }` | `NO_BOT_WALLET`, `TERMS_V3_MISSING` (422); `RELEASE_EXISTS`, `FOUNDER_FIRST`, `PILOT_FULL` (409); `VALIDATION_FAILED` |
| `DELETE /api/v1/admin/real/users/{userId}/release` | `{ reason }` | `RealAdminRevocation { userId, revoked, cancelledOrders }` | `503` quando a trava da pessoa não é obtida (repetir com a mesma chave) |
| `PUT /api/v1/admin/real/users/{userId}/cap` | `{ capLamports, reason }` | `RealAdminCap { userId, capLamports, commandId }` | `422 CAP_ONLY_LOWERED` |
| `GET /api/v1/admin/real/token-blocks` | — | `RealAdminTokenList { items }` | — |
| `POST /api/v1/admin/real/token-blocks` | `{ mint, reason }` | `201 RealAdminBlockedToken` | `VALIDATION_FAILED` |
| `DELETE /api/v1/admin/real/token-blocks/{mint}` | — | `RealAdminUnblock { mint, unblocked }` | — |
| `GET /api/v1/admin/real/kill-switch` | — | `RealAdminKillSwitch { active, activatedAt, reason, version, changed, cancelledOrders }` | — |
| `PUT /api/v1/admin/real/kill-switch` | `{ reason }` | o mesmo, com `changed` e as ordens canceladas logo depois do registro | — |
| `DELETE /api/v1/admin/real/kill-switch` | `{ reason }` | o mesmo | — |
| `GET /api/v1/admin/real/divergences?cursor&limit` | — | `RealAdminDivergencePage { items, nextCursor }` (abertas ou compensadas, das mais novas) | — |
| `POST /api/v1/admin/real/divergences/{divergenceId}/compensation` | `{ items: [{ asset: SOL \| <mint>, amount }], reason }` | `201 RealAdminDivergence` (`COMPENSATED`) | `409 DIVERGENCE_NOT_OPEN`; `422 COMPENSATION_MISMATCH` |
| `POST /api/v1/admin/real/restore/confirmation` | `{ reason }` | `RealAdminRestore { markerId, detectedAt }` | `404` sem restauração aberta; `409 RESTORE_NOT_CLEAN` |

Diferenças em relação à especificação original de R4, decididas no Functional Design e no plano da U5:

| Item | Mudança |
|---|---|
| Papel e chave | Toda rota exige o papel ADMIN (entrada administrativa); toda mudança exige `Idempotency-Key` (mecanismo comum: mesma chave e corpo, mesma resposta; outro corpo, `409 IDEMPOTENCY_CONFLICT`; recusa nunca é guardada) e `reason` de 1 a 500 caracteres. O campo `confirmation` saiu: o motivo e a chave cumprem esse papel. |
| Kill switch | `PUT` liga (antes `POST`), `DELETE` desliga, `GET` lê. O estado vive no `botwallet` (M7). |
| Compensação | O caminho usa `divergenceId` (antes `runId`) e o corpo é uma lista de itens por ativo (SOL ou mint), cada um igual à diferença medida, com sinal. A divergência fica `COMPENSATED`; a reconciliação seguinte decide `RESOLVED`. |
| Liberação | Estados `QUARANTINE`, `ACTIVE`, `BLOCKED` (com `blockedReason`: `CI_RED`, `CI_UNREACHABLE`, `DIVERGENCE_OPEN`, `KILL_SWITCH_ON`, `NETWORK_MISMATCH`) e `REVOKED`; `NONE` na visão quando não há. `LOCALNET` é aceita fora da produção. |
| Teto | O código de recusa é `CAP_ONLY_LOWERED` (antes `CAP_INCREASE_NOT_ALLOWED`). |
| Visão da pessoa | `mainWalletShort` (4 primeiros … 4 últimos) em vez da carteira principal; a carteira do bot aparece por extenso (endereço público, para conferir na rede); `pausedReasons` é um conjunto; `balance` ganha `aboveCapLamports`; 20 ordens recentes no detalhe. Nenhum campo traz chave nem referência de chave. Valores em lamports e unidades base trafegam como texto inteiro. |
| Restauração | Rota nova `POST /restore/confirmation` (BR2.5). |

### M1 — `botwallet.api`

| Item | Mudança |
|---|---|
| `RealAdministration` | Nova, só para o `RealAdminController`: `adminRelease`, `adminRevoke`, `tenantOf`, `adminLowerCap`, `adminUserView`, `adminUsers`, `listDivergences`, `compensate`, `confirmRestore`. Recusas são `RealAdminRefused{ code, status }`. A revogação roda dentro de `RealRiskGate.withPersonLock`, que o controlador toma; o `botwallet` não depende do `riskcontrol`. |
| `RealAnonymization` | Nova, para a privacidade: `closeForAnonymization(tenantId, userId)` → `Closed(hadBotWallet)` ou `Refused(ORDER_IN_PROGRESS \| POSITION \| BALANCE)`. Fecha a carteira, envia `DEACTIVATE` ao TxSigner, apaga os endereços e redige a trilha. |
| `RealPauseReason` | Ganha `RESTORE` e `RESTORE_CHECK_PENDING`. `readRealState` junta as pausas globais (kill switch e restauração) às da carteira; a visão da própria pessoa (`GET /api/v1/real/bot-wallet`) também mostra a pausa global. |
| Saque | Com divergência aberta, o saque fica limitado ao saldo medido na rede (`422 WITHDRAWAL_LIMITED_BY_DIVERGENCE` acima dele); nunca é bloqueado. |

### M7 — `botwallet.api.RealKillSwitchState` (dono U5)

```yaml
M7_RealKillSwitchState:
  current() -> KillSwitchView{ active, activatedAt, activatedBy, reason, version }   # leitura viva, sem cache
  activate(adminId, reason) -> KillSwitchChange{ view, changed }                    # CAS na versão, auditado
  deactivate(adminId, reason) -> KillSwitchChange{ view, changed }
  cancelUnsentWhileActive() -> int                                                   # M6 cancelUnsent(ALL, KILL_SWITCH)
```

### `CiStatus` (porta do `botwallet`)

`CiStatus.check(commitSha) -> CiResult{ status: GREEN | RED | UNREACHABLE, runRef? }`. Em produção, `GitHubCiStatus` lê os `workflow_runs` do `ci.yml` para o SHA da imagem (`MEMETRADE_BUILD_SHA`) (token só de leitura, 5 s de prazo; qualquer erro é `UNREACHABLE`). Fora da MAINNET, `MEMETRADE_REAL_OPS_CI=GREEN|RED` fixa o resultado (`ConfiguredCiStatus`); a MAINNET recusa esse valor na subida.

### M2, M5 e M6

| Interface | Mudança |
|---|---|
| M2 `RealLedger` | `tokenBalances(tenantId, botWalletId)` para a reconciliação; `RealEntry.compensation(...)` aceita lançamento compensatório de SOL ou de token (lamports 0 com unidades base do mint). |
| M5 `RealRiskGate` | `blockedTokens()` (lista de bloqueio, a mais nova primeiro). `RealRejectionDetail` ganha `PAUSED_RESTORE` e `PAUSED_RESTORE_CHECK_PENDING`. |
| M6 `RealExecutionPort` | `ordersInTransit(tenantId, userId)` (SENT e AMBIGUOUS, para adiar a reconciliação), `knownTransactions(signatures)` (para a conferência de restauração) e `unsentOrders()` (medidor do kill switch). |

### R1 — API interna do TxSigner

| Item | Mudança |
|---|---|
| `LOWER_CAP` e `DEACTIVATE` | Comandos com `commandId` estável, reenviados a cada minuto até confirmar (`real_pending_txsigner_command`). O `DEACTIVATE` vai como consentimento de política `CLOSE`; `WALLET_NOT_FOUND` conta como confirmado. O endereço é lido na hora do envio e nunca fica gravado no comando. |

### Decisão

`decision.mode` aceita `REAL` (migração 0021); o ciclo grava o modo do bot (`PAPER` ou `REAL`).

## Adendo da U6 (Code Generation de `u6-real-modes-ui`, 2026-11-16)

Mudanças só de leitura e de transporte, aplicadas no código no mesmo trabalho da U6. Nenhuma regra de negócio mudou e as rotas que já existiam respondem igual. As fontes são `construction/u6-real-modes-ui/functional-design/rules.md` (seção "Mudança de contrato") e `nfr-design/logical-components.md`. O OpenAPI de referência é `apps/api/openapi/openapi-guest.yaml` (gerado do código) e os tipos de `apps/web` foram regenerados dele; o `openapi-admin.yaml` não mudou.

### R2 — Carteira do bot (convidado)

| Item | Mudança |
|---|---|
| `GET /api/v1/real/release` (rota nova) | Devolve `RealReleaseView { network, release: RealReleaseStatus }`, com `RealReleaseStatus { state: NONE \| QUARANTINE \| ACTIVE \| BLOCKED \| REVOKED, effectiveAt?, blockedReason? }`. Lê pela M1 (`RealStateQuery.readRelease(tenantId, userId)`), com o tenant e a pessoa do token. Com o REAL desligado: `403 REAL_DISABLED`. Sem o aceite dos termos vigentes: `403 TERMS_REACCEPTANCE_REQUIRED` (não é exceção do novo aceite). A liberação mostrada é a aberta da rede do ambiente; sem ela, a última revogada; sem nenhuma, `NONE`. `Cache-Control: no-store`. Entrou no `route-registry.yml` (`scope: CLIENT`). |
| `BotWalletView` | Dois campos novos só de leitura: `capSetBy` (`USER` ou `ADMIN`) e `release` (o mesmo `RealReleaseStatus`). `capSetBy` é `ADMIN` enquanto o teto atual for igual ao valor do último `LOWER_CAP` do administrador para a carteira (U5 BR4.1); qualquer mudança da própria pessoa volta a `USER`. |
| `GET /api/v1/real/bot-wallet` | Continua `404 NOT_FOUND` sem carteira, como na U1. |

### R3 — Sessão de assinatura

| Item | Mudança |
|---|---|
| `RealSigningSession` | Campo novo `serverTime` (instante UTC do `Clock` do servidor ao montar a resposta). A tela desconta a diferença de relógio; o `deadline` não muda e o servidor continua decidindo a expiração. |

### CORS

| Política | Mudança |
|---|---|
| `GuestCorsPolicy` | O ramo com token aceita `PATCH` (preferências do REAL). O ramo por cookie (verify, refresh, logout) continua sem `PATCH`. |
| `AdminCorsPolicy` (desvio da U6) | O ramo com token aceita `DELETE`, porque o app administrativo chama, de outra origem, as rotas `DELETE` da R4 da U5 (revogar liberação, desligar o kill switch REAL, desbloquear token). Sem isso o navegador recusaria o preflight. O ramo por cookie continua sem `DELETE` e a política não aceita `PATCH`. |

### M1 — `botwallet.api`

| Item | Mudança |
|---|---|
| `RealStateQuery.readRelease(tenantId, userId)` | Novo, só de leitura: `RealReleaseInfo { realEnabled, network, state, effectiveAt?, blockedReason? }`. |

### Canal ao vivo (R6)

Sem mudança no servidor. O navegador passou a tratar `real.signing.requested` (`RealSigningSessionOpened`) e `real.order.state` (`RealOrderStateChanged`), que antes descartava como evento desconhecido, e o canal agora é um só por pessoa, aberto no layout raiz.

## Adendo da U7 (Code Generation de `u7-exits-and-fiscal`, 2026-11-17)

Mudanças aplicadas no código no mesmo trabalho da U7. As fontes são `construction/u7-exits-and-fiscal/functional-design/rules.md` (BR0 a BR9, seção "Mudança de contrato"), `nfr-design/` e `infrastructure-design/infrastructure-specification.md`. O OpenAPI de referência é `apps/api/openapi/openapi-guest.yaml` (gerado do código) e os tipos de `apps/web` foram regenerados dele; o `openapi-admin.yaml` não mudou. Até a gravação real (BR0), as rotas de condicionais rodam só com o adaptador simulado no perfil de teste local; em qualquer outro ambiente o flag fica desligado e a criação devolve `403 EXITS_DISABLED`.

### R5 — Saídas automáticas (como construída)

| Item | Mudança em relação ao R5 original |
|---|---|
| `POST /api/v1/real/conditional-orders` | Corpo `{ positionMint, kind: STOP \| TAKE_PROFIT \| OCO, stopPriceUsd?, takeProfitPriceUsd? }`, preços em USD como `DecimalString` (no máximo 18 casas), em vez de `triggerPrice`. `201` com `{ conditionalOrderId, conditionalOrder, signingSession, stopNotGuaranteed: true }`. `signingSession` é `{ kind: MESSAGE \| TRANSACTION, message?, transactionBase64?, deadline, serverTime }`: antes da gravação só existe `MESSAGE` (consentimento montado pelo servidor e assinado com `signMessage` pela carteira principal, sem alteração), janela de 60 s. `Idempotency-Key` obrigatório. Recusas: `403 REAL_DISABLED`, `403 EXITS_DISABLED`, `403 EXITS_MODE_REQUIRED` (modo diferente de `AUTO_EXITS`), `409 CONDITIONAL_ALREADY_OPEN` (BR3.1), `409 NOTHING_TO_SELL`, `422 PRICE_UNKNOWN`, `422 TP_NOT_ABOVE_SL`, `422 TRIGGER_ON_WRONG_SIDE`, `422 TRIGGER_OUT_OF_BOUNDS`, `422 TOKEN_BLOCKED`, `422 VALIDATION_FAILED`. |
| `GET /api/v1/real/conditional-orders` | `{ items: ConditionalOrder[], nextCursor?, total }`, mais novas primeiro, por cursor. |
| `GET /api/v1/real/conditional-orders/quote?positionMint=` (rota nova) | Só leitura, para o diálogo "Definir stop e alvo": `ConditionalQuote { positionMint, positionBaseUnits, priceStatus: KNOWN \| UNKNOWN, priceUsd?, source?, observedAt?, minStopPriceUsd?, maxTakeProfitPriceUsd? }`. Preço ausente é `UNKNOWN`, nunca zero; os limites são os de BR2.6 (50% e 10 vezes). |
| `POST /api/v1/real/conditional-orders/{conditionalOrderId}/signature` | Corpo `{ signature }` (Base58 da assinatura do consentimento) no caminho `MESSAGE`. `202` com o `ConditionalOrder` (`OPEN`, ou `FAILED` com o motivo da Jupiter em `TRIGGER_REJECTED`). Recusas: `409 SIGNING_DEADLINE_EXPIRED`, `422 SIGNED_MESSAGE_MISMATCH`. |
| `DELETE /api/v1/real/conditional-orders/{conditionalOrderId}` (rota nova) | Cancelamento pela pessoa, com `Idempotency-Key`. `202` com o `ConditionalOrder` em `CANCELLED` (de `PENDING_SIGNATURE`, sem transação) ou `CANCELLING`. Repetir com a condicional já em `CANCELLING` devolve a mesma condicional; uma condicional final devolve `409 INVALID_TRANSITION`. Outra pessoa recebe `404`. |
| `ConditionalOrder` | `{ conditionalOrderId, positionMint, kind, stopPriceUsd?, takeProfitPriceUsd?, amountBaseUnits, state: PENDING_SIGNATURE \| OPEN \| CANCELLING \| FILLED \| CANCELLED \| EXPIRED \| FAILED, filledSide?: STOP \| TAKE_PROFIT, filledAt?, needsUserCancellation, txSignature?, createdAt }`. Substitui `triggerPrice` e ganha `CANCELLING` e `FAILED`. |
| `needsUserCancellation` | Muda de sentido (BR6.7): verdadeiro só quando o cancelamento automático está parado há mais de 15 minutos. O servidor cancela sozinho nos gatilhos de BR6 (kill switch REAL, liberação revogada ou ausente, saída do modo `AUTO_EXITS`, expiração da assinatura). |
| `GET /api/v1/real/exports/fiscal` | Novo parâmetro `tz` (zona IANA, padrão `America/Sao_Paulo`) para a coluna `data_local`, porque a pessoa não tem fuso guardado. Período de no máximo 365 dias, nunca invertido (`422 UNPROCESSABLE_FIELDS`); sem datas, os 365 dias até hoje. `200 text/csv` em fluxo (UTF-8 com BOM, `;`, vírgula decimal, CRLF), `Content-Disposition: attachment; filename="historico-real-<de>-<até>.csv"`, `Cache-Control: no-store`. Limite de 10 por pessoa por hora (`429 RATE_LIMITED` com `Retry-After`). Colunas: `data_utc;data_local;tipo;mint;simbolo;quantidade;preco_sol;valor_sol;taxa_sol;assinatura`. |

Todas as rotas acima entraram no `route-registry.yml` (`scope: CLIENT`), com o tenant e a pessoa do token.

### R2 — Carteira do bot

| Item | Mudança |
|---|---|
| `RealReleaseView` (`GET /api/v1/real/release`) | Campo novo `exitsEnabled` (o flag `memetrade.real.exits.enabled`). A tela habilita o modo "Automática nas saídas" e "Definir stop e alvo" só com ele verdadeiro. |
| `PUT /api/v1/real/mode` | Subir para `AUTO_EXITS` com o flag desligado devolve `403 EXITS_DISABLED`; quem já está no modo continua nele. |
| Anonimização (U5) | Recusa nova `409 OPEN_CONDITIONALS` com condicional não terminal (BR6.9). |
| Venda da pessoa no modo `SIGNED`/`AUTO_EXITS` (R3) | A assinatura de uma venda com condicional aberta na posição devolve `409 EXIT_CANCELLING` enquanto o cancelamento não confirma, e `409 NOTHING_TO_SELL` quando não sobra posição. |

### Canal ao vivo (R6)

Evento novo `real.conditional.state` (`RealConditionalStateChanged { type: REAL_CONDITIONAL_STATE, conditionalOrderId, positionMint, state, filledSide?, at }`), emitido pelo mesmo `DashboardMarkPoller` dos eventos REAL anteriores, a cada mudança de estado.

### M1, M2 e portas novas

| Item | Mudança |
|---|---|
| `RealStateQuery.botWalletIds(tenantId, userId)` | Novo, só de leitura. |
| `RealLedger.streamEntries(tenantId, botWalletIds, from, to, consumer)` | Novo, só de leitura, em fluxo (`fetchSize` 500) e em transação só de leitura, para a exportação fiscal. Recebe as carteiras do bot porque o ledger não tem coluna de pessoa. |
| `MarketDataQuery.symbolsOf(tokens)` | Novo, só de leitura (coluna `simbolo` do CSV). |
| `realexecution.api.PositionExitGuard`, `RealExitChanges`; `botwallet.api.EscrowBalances`, `OpenExitsCheck`, `ExitsAvailability` | Interfaces de extensão sem ciclo; a U7 implementa, e cada módulo dono tem um padrão neutro quando a U7 não está no contexto. A reconciliação (U5) soma o token na custódia da Jupiter ao saldo de token da rede. |

### R1 — API interna do TxSigner

| Item | Mudança |
|---|---|
| `operationKind` | Valores novos `TRIGGER_CREATE` e `TRIGGER_CANCEL`. Até a gravação (BR0) são sempre recusados com `reason: POLICY_NOT_RECORDED`, sem registro de assinatura e sem chamar o KMS; a métrica é `txsigner_trigger_refusals_total{reason}`. |
| `trigger-program.json` | Carregado do classpath com o `.sha256` ao lado e o hash configurado em `txsigner.trigger.program-sha256` (obrigatório no perfil de produção); hash diferente falha a subida. Sem o arquivo, o TxSigner sobe e recusa como acima. |

### Decisão

O caminho simulado (adaptador `SimulatedTriggerAdapter`, perfil `test`, só na `LOCALNET`) não chama o TxSigner nem assina com a chave do bot. A `TriggerPolicy` completa e o adaptador real ficam para depois da gravação real, numa entrega própria.
