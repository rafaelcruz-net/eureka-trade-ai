# Contratos — MemeTrade Analytics (beta PAPER por convite)

Contratos entre as 9 Units de [unit-of-work.md](../units-generation/unit-of-work.md), seguindo as arestas de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md), as entidades de [components.md](../domain-design/components.md) e os requisitos de [requirements.md](../requirements-analysis/requirements.md). As decisões vêm das respostas Q1 a Q20 de [contract-design-questions.md](contract-design-questions.md). As respostas Q16 a Q20 revisaram os pontos críticos encontrados na primeira versão: Q18 substitui Q9 e Q15.

Os contratos são de quatro tipos:

- **REST (OpenAPI):** entre o backend e os dois apps web. A API é privada (Q1) e nasce do código: o backend gera o OpenAPI, a CI compara o arquivo gerado com o versionado e os tipos do frontend são gerados dele (Q2). São duas especificações, uma do convidado e uma administrativa (Q3).
- **Interfaces de módulo (esquema compartilhado):** chamadas síncronas entre módulos do mesmo backend, por interface pública Java com registros (records) imutáveis. Nenhum módulo lê tabela ou repositório de outro (`team.md`).
- **Tabela de saída da auditoria (outbox):** cada ação sensível grava seu evento de auditoria numa tabela de saída, na mesma transação da ação, no processo de API ou no worker; a trilha lê essa tabela e grava cada evento uma única vez (Q18).
- **Marcas de alteração:** o canal ao vivo do painel descobre mudanças lendo marcas de "última alteração" pelas interfaces públicas, o que funciona para mudanças feitas tanto no processo de API quanto no worker (Q19).

Os blocos abaixo descrevem a forma e o comportamento esperados. Como o contrato nasce do código (Q2), eles são a referência que o código gerado deve reproduzir; a primeira geração do OpenAPI de cada Unit é conferida contra o bloco correspondente. As referências `common#/...` e `C2#/...` apontam para o bloco de componentes comuns e para o bloco C2 deste documento; no repositório elas viram referências dentro de cada arquivo gerado (`openapi-guest.yaml` e `openapi-admin.yaml`), que repete os componentes comuns que usa.

## Tabela de contratos

| # | Provider Unit | Consumer | Mechanism | Owner |
|---|---|---|---|---|
| C1 | U1 `u1-walking-skeleton` (completado por U3) | U1 app do convidado | REST: autenticação e sessão do convidado | U1 |
| C2 | U4 `u4-discovery` (iniciado em U1) | U1/U4 telas do scanner e do detalhe | REST: mercado, scanner e avaliação | U4 |
| C3 | U3 `u3-identity-access` | U3 telas de entrada e conta | REST: convite, termos e conta | U3 |
| C4 | U5 `u5-bots-decision` | U5 telas de bots; U9 painel | REST: bots, decisões e controles | U5 |
| C5 | U7 `u7-ledger-pnl` | U7 telas de P&L e ledger; U5 tela do bot; U9 painel | REST: operações, ledger, P&L e reconciliação | U7 |
| C6 | U8 `u8-admin-feedback` | U8 formulário de feedback no app do convidado | REST: envio de feedback | U8 |
| C7 | U5 (hospeda o canal no processo de API) | U9 `u9-dashboard` | REST stream (Server-Sent Events) | U5 |
| C8 | U8 (convites com regras de U3) | U8 app do administrador | REST: API administrativa, inclusive autenticação do administrador | U8 |
| C9 | U2 `u2-audit-trail` (implementação mínima em U1) | U1, U3, U5, U6, U7, U8 | Interface publicadora + tabela de saída (outbox) na mesma transação | U2 |
| C10 | U1 | U2 a U8 | Interface de módulo: contexto de autenticação | U1 |
| C11 | U4 | U5, U6, U7, U8 | Interface de módulo: dados de mercado | U4 |
| C12 | U4 | U5 | Interface de módulo: avaliação de risco | U4 |
| C13 | U7 | U6 | Interface de módulo: visão de risco da carteira | U7 |
| C14 | U6 | U5 | Interface de módulo: barreira de ordem | U6 |
| C15 | U7 | U5 | Interface de módulo: execução PAPER dentro da transação do ciclo | U7 |
| C16 | U5 | U8, U9 | Interface de módulo: pausa de sistema, contagens e resumo | U5 |
| C17 | U3 | U8 | Interface de módulo: administração de convites e contagens | U3 |
| C18 | U4, U5, U7 | U5 (canal C7) | Interface de módulo: marcas de última alteração | Cada produtor é dono da sua marca |
| C19 | Fontes de mercado (externas, simuladas no local) | U4 | Porta de adaptador de mercado | U4 |
| C20 | U5 (processo API) | U5 (processo worker) | Esquema compartilhado do próprio módulo | U5 |
| C21 | U6 `u6-risk-control` | U5 tela do bot | REST: ordens recusadas | U6 |

Não existe contrato para consumidor externo no beta (Q1). A assinatura de login acontece na extensão da carteira, dentro do navegador; nenhum contrato recebe chave ou frase secreta (FR1.5).

Toda aresta de contrato segue o sentido das dependências de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md): nenhum provedor chama de volta um consumidor (Q16, Q17). O canal C7 é hospedado pela U5, que já depende de U4 e U7, e é consumido pela U9 (Q19).

## Regras comuns da API REST

Valem para C1 a C8 e C21.

| Tema | Regra |
|---|---|
| Base | Convidado em `/api/v1/...`; administração em `/api/v1/admin/...` (Q3). Cada app gera tipos só da sua especificação; o código do convidado não contém operações administrativas (ADR-009). |
| Autenticação | `Authorization: Bearer <accessToken>` em toda rota, menos desafio, verificação, renovação e consulta de convite (Q11). O token de acesso fica só na memória do app. O token do convidado só vale em rotas do convidado, e o do administrador só em rotas `/api/v1/admin/...`. |
| Renovação | Token de renovação em cookie `HttpOnly`, `Secure`, `SameSite=Strict`, com `Path=/api/v1/auth/refresh` (convidado) ou `Path=/api/v1/admin/auth/refresh` (administrador), usado só para obter um novo token de acesso (Q13). Por causa de `SameSite=Strict`, a API e os dois apps ficam no mesmo site (mesmo domínio registrável; no ambiente local, `localhost` em portas diferentes). |
| CORS | Lista fixa com só as origens dos dois apps; credenciais permitidas só nas rotas de renovação e saída. |
| Tenant | Nunca vem no corpo, na query ou no cabeçalho; vem do token. Um `tenantId` enviado pelo cliente é ignorado (AC1.4.2). Recurso de outro tenant ou de outra pessoa responde `404` (AC1.4.1). A única exceção é a emissão de convite pelo administrador (C8, ADR-002). |
| Idempotência | Toda requisição `POST`, `PUT`, `PATCH` ou `DELETE` exige o cabeçalho `Idempotency-Key` (UUID), menos as rotas de renovação (`/auth/refresh` e `/admin/auth/refresh`), que não mudam estado de negócio: repetir só emite outro token de acesso (Q7, Q20). Mesma chave e mesmo conteúdo devolvem o mesmo resultado; mesma chave e conteúdo diferente devolvem `409 IDEMPOTENCY_CONFLICT`. Escopo da chave: depois do login, tenant, pessoa e operação; antes do login (`challenge` e `verify`), endereço da carteira e operação. Retenção provisória de 24 horas, com valor final no NFR Design (OQ-6); o mecanismo comum nasce na U1. |
| Decimais | Dinheiro, quantidades, preços, P&L e percentuais trafegam como texto decimal (`DecimalString`), nunca como número JSON (Q6). A escala é fixa por tipo de valor (tabela "Escalas"). |
| `unknown` | Todo valor de mercado é um `MarketValue` com `status` `KNOWN` ou `UNKNOWN` (Q5). `null` não representa ausência. |
| Instantes | Texto ISO 8601 em UTC com sufixo `Z`; a conversão para o fuso da pessoa acontece só na interface. |
| Paginação | Por cursor: `?cursor=<opaco>&limit=<1..100>`. Toda resposta paginada tem a forma `{ items, nextCursor, total }` (esquema `PageMeta` mais `items` do tipo da rota). |
| Erros | `application/problem+json` (RFC 7807) com `type` estável por categoria, `code` legível por máquina e `correlationId`; sem stack trace, SQL ou segredo. Erros de domínio são `4xx` e auditados; erros técnicos são `5xx` ou `503`. |
| Repetição pelo cliente | O app repete no máximo 2 vezes, com espera crescente, só `GET` e requisições com `Idempotency-Key`, e só em `503` ou tempo esgotado. Tempo limite do cliente: 10 s (valor revisto no NFR Design). |
| Correlação | O cliente pode enviar `X-Correlation-Id`; o backend sempre devolve um no cabeçalho e no `ProblemDetail`. |
| Modo | `mode` só aparece em respostas, sempre `PAPER`. Nenhuma requisição declara `mode`; um corpo ou query que traga `mode` com qualquer valor diferente de `PAPER` responde `403 REAL_MODE_UNAVAILABLE` e é auditado, e nenhuma rota, tarefa ou tela aceita operação REAL (FR15.2, AC4.2.5, AC4.3.5). |

### Escalas

| Tipo de valor | Escala | Observação |
|---|---|---|
| Quantidade de token | Casas decimais do mint (lidas de `Token.decimals` pela C11) | Nunca mais casas que o mint permite; truncada para baixo (`DOWN`) (AC4.11.3, BR1.7 da U7) |
| Valor na moeda de referência do saldo virtual (saldo, taxas, P&L, exposição, valor bruto) | 6 casas, em USDC, arredondado meio para o par (`HALF_EVEN`) uma única vez, no momento do cálculo | Fechado pela U7 (Q1 e Q5 do Functional Design da U7); fecha o OQ-3 |
| Preço unitário em USDC (referência, cotado, preenchimento, saída) | 12 casas, `HALF_EVEN` | Acréscimo da U7: preserva spread e impacto em tokens de preço muito baixo (C14 do catálogo) |
| Percentual (slippage, drawdown, cobertura) | Pontos percentuais com 4 casas decimais | `"1.5000"` significa 1,5%; `"100.0000"` significa 100%. Nunca fração de 0 a 1 |
| Indicador de risco | Inteiro de 0 a 100 | Não é valor monetário; trafega como número inteiro |

### Códigos de erro de domínio

| `code` | HTTP | Uso |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Campo inválido, com a lista de campos |
| `UNAUTHENTICATED` | 401 | Token ausente, inválido ou expirado |
| `FORBIDDEN_ROLE` | 403 | Papel sem permissão (por exemplo, convidado em rota administrativa ou carteira de administrador na entrada do convidado) |
| `NOT_FOUND` | 404 | Recurso inexistente, de outro tenant ou de outra pessoa |
| `IDEMPOTENCY_CONFLICT` | 409 | Mesma chave com conteúdo diferente |
| `INVALID_TRANSITION` | 409 | Transição de bot fora da tabela (AC4.4.3) |
| `ACTIVE_BOT_LIMIT` | 409 | Já existem 3 bots ativos (AC4.3.2) |
| `PAUSED_BY_SYSTEM` | 409 | Kill switch acionado (AC7.2.3) |
| `LIMIT_INCOHERENT` | 422 | Limites incoerentes (AC4.2.3) |
| `VIRTUAL_BALANCE_ABOVE_CAP` | 422 | Saldo virtual acima do teto (AC4.2.4) |
| `INVITE_INVALID` | 422 | Convite expirado, revogado ou usado, ou tenant do convite não ativo, com `reason` (`EXPIRED`, `REVOKED`, `USED`, `TENANT_INACTIVE`; U3) |
| `INVITE_NOT_FOUND` | 404 | Código de convite desconhecido ou mal formado, com a mesma mensagem para os dois (U3) |
| `INVITE_NOT_REVOCABLE` | 409 | Revogação de convite usado ou expirado, com `reason` (U3) |
| `VALIDATION_FAILED` | 422 | Na emissão de convite, valor que fere a regra de negócio (`tenantId` não ativo, `expiresAt` fora de 1 hora a 30 dias), com a lista de campos (U3) |
| `TERMS_NOT_ACCEPTED` | 422 | Vínculo de carteira antes do aceite (AC1.1.1) |
| `WALLET_ALREADY_LINKED` | 409 | Carteira já vinculada a outra conta (AC1.1.5) |
| `LOGIN_CHALLENGE_REJECTED` | 401 | Nonce expirado, reutilizado ou mensagem alterada (AC1.2.2, AC1.2.3) |
| `REAL_MODE_UNAVAILABLE` | 403 | Qualquer tentativa de modo REAL (AC4.2.5, AC4.3.5) |
| `SOURCE_UNAVAILABLE` | 503 | Todas as fontes de mercado indisponíveis (AC2.3.3) |

## Especificações

### Componentes comuns (referenciados por C1 a C8 e C21)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade common components
  version: 1.0.0
paths: {}
components:
  parameters:
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema: { type: string, format: uuid }
    Cursor:
      name: cursor
      in: query
      schema: { type: string }
    Limit:
      name: limit
      in: query
      schema: { type: integer, minimum: 1, maximum: 100, default: 50 }
  securitySchemes:
    accessToken:
      type: http
      scheme: bearer
  schemas:
    DecimalString:
      type: string
      pattern: '^-?\d+(\.\d+)?$'
      description: Decimal exato em texto; nunca número JSON.
    Instant:
      type: string
      format: date-time
      description: UTC com sufixo Z.
    Mode:
      type: string
      enum: [PAPER]
    PageMeta:
      type: object
      required: [nextCursor]
      properties:
        nextCursor: { type: [string, 'null'], description: 'null quando não há próxima página' }
        total: { type: integer, description: Presente quando a contagem é barata }
    MarketValue:
      oneOf:
        - $ref: '#/components/schemas/KnownValue'
        - $ref: '#/components/schemas/UnknownValue'
      discriminator:
        propertyName: status
        mapping:
          KNOWN: '#/components/schemas/KnownValue'
          UNKNOWN: '#/components/schemas/UnknownValue'
    KnownValue:
      type: object
      required: [status, value, source, observedAt, stale]
      properties:
        status: { type: string, const: KNOWN }
        value: { $ref: '#/components/schemas/DecimalString' }
        source: { $ref: '#/components/schemas/SourceRef' }
        observedAt: { $ref: '#/components/schemas/Instant' }
        stale: { type: boolean, description: 'true quando a idade passa de 5 minutos (FR4.5)' }
        divergence:
          type: array
          description: Presente só quando outra fonte diverge acima da tolerância (FR4.7).
          items: { $ref: '#/components/schemas/Divergence' }
    Divergence:
      type: object
      required: [source, value, observedAt]
      properties:
        source: { $ref: '#/components/schemas/SourceRef' }
        value: { $ref: '#/components/schemas/DecimalString' }
        observedAt: { $ref: '#/components/schemas/Instant' }
    UnknownValue:
      type: object
      required: [status, reason]
      properties:
        status: { type: string, const: UNKNOWN }
        reason:
          type: string
          enum: [NOT_PROVIDED, SOURCE_UNAVAILABLE, NOT_YET_OBSERVED]
        source: { $ref: '#/components/schemas/SourceRef' }
    SourceRef:
      type: object
      required: [sourceId, name, simulated]
      properties:
        sourceId: { type: string }
        name: { type: string }
        simulated: { type: boolean }
    Problem:
      type: object
      required: [type, title, status, code, correlationId]
      properties:
        type: { type: string, format: uri }
        title: { type: string }
        status: { type: integer }
        detail: { type: string }
        code: { type: string }
        correlationId: { type: string }
        fields:
          type: array
          items:
            type: object
            required: [field, message]
            properties:
              field: { type: string }
              message: { type: string }
        reason: { type: string }
```

### C1 — Autenticação e sessão do convidado (U1, completado por U3)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - auth
  version: 1.0.0
paths:
  /api/v1/auth/challenge:
    post:
      summary: Emite mensagem de login com domínio, nonce de uso único, expiração de 5 minutos e chain (FR1.3)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [walletAddress]
              properties:
                walletAddress: { type: string, description: Endereço público Solana }
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                required: [message, nonce, domain, chain, issuedAt, expiresAt]
                properties:
                  message: { type: string, description: Texto exato a assinar }
                  nonce: { type: string }
                  domain: { type: string }
                  chain: { type: string }
                  issuedAt: { $ref: 'common#/components/schemas/Instant' }
                  expiresAt: { $ref: 'common#/components/schemas/Instant' }
  /api/v1/auth/verify:
    post:
      summary: Verifica a assinatura e abre a sessão; com convite, cria a conta numa única operação (AC1.1.3, entregue por U3)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [walletAddress, message, signature, provider]
              properties:
                walletAddress: { type: string }
                message: { type: string }
                signature: { type: string, description: Assinatura em base58 }
                provider: { type: string, enum: [PHANTOM, SOLFLARE] }
                inviteCode: { type: string, maxLength: 128, description: 'Presente só no primeiro acesso por convite (U3); sem checagem de formato aqui: mal formado é tratado como desconhecido depois da assinatura' }
                acceptedTermsVersion: { type: string, maxLength: 32, description: Obrigatório quando inviteCode está presente }
      responses:
        '200':
          description: Sessão aberta; o cookie de renovação é emitido em Set-Cookie
          headers:
            Set-Cookie: { schema: { type: string } }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/GuestSession' }
        '400': { description: 'VALIDATION_FAILED; campo extra, inclusive tenantId, é recusado (U3, NFR6.8)' }
        '401': { description: LOGIN_CHALLENGE_REJECTED }
        '403': { description: FORBIDDEN_ROLE quando a carteira é a de administrador }
        '404': { description: 'INVITE_NOT_FOUND: código do convite desconhecido ou mal formado (U3)' }
        '409': { description: 'WALLET_ALREADY_LINKED; o convite continua pendente (corrigido pela U3: convite usado é 422)' }
        '422': { description: 'INVITE_INVALID com reason EXPIRED, REVOKED, USED ou TENANT_INACTIVE; ou TERMS_NOT_ACCEPTED (U3)' }
  /api/v1/auth/refresh:
    post:
      summary: Troca o cookie de renovação por um novo token de acesso (Q13); sem Idempotency-Key
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/GuestSession' }
        '401': { description: UNAUTHENTICATED; o app volta à entrada e depois à tela de origem (AC1.3.1) }
  /api/v1/auth/logout:
    post:
      summary: Encerra a sessão e revoga o token de renovação; bots continuam rodando (AC1.3.2)
      security: [ { accessToken: [] } ]
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      responses:
        '204': { description: Sessão encerrada }
components:
  schemas:
    GuestSession:
      type: object
      required: [accessToken, accessTokenExpiresAt, role, sessionExpiresAt]
      properties:
        accessToken: { type: string }
        accessTokenExpiresAt: { $ref: 'common#/components/schemas/Instant' }
        sessionExpiresAt: { $ref: 'common#/components/schemas/Instant' }
        role: { type: string, enum: [BETA_USER] }
```

Comportamento: a rota de verificação rejeita qualquer campo extra (`additionalProperties: false`), e nenhum campo aceita frase secreta ou chave privada (AC1.2.5). Nonce, assinatura e tokens nunca vão para log nem para a trilha de auditoria. O token de acesso só autentica chamadas de leitura e comando do próprio produto e não serve como autorização de trading (FR1.4). A especificação do convidado só emite `role: BETA_USER`; a carteira de administrador da configuração entra só pela autenticação administrativa de C8 (AC1.2.6) e, na entrada do convidado, recebe `403 FORBIDDEN_ROLE`.

Consequência para o esqueleto: como a U1 não tem convites nem app do administrador, o smoke da U1 entra com uma conta `BETA_USER` de teste semeada só no perfil de teste (fixture de banco e carteira de teste gerada no código de teste, nunca no código de produção). Ver OQ-8.

### C2 — Mercado, scanner e avaliação (U4, iniciado em U1)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - market
  version: 1.0.0
security: [ { accessToken: [] } ]
paths:
  /api/v1/scanner/tokens:
    get:
      summary: Lista paginada com ordem determinística e desempate estável (FR5.1)
      parameters:
        - $ref: 'common#/components/parameters/Cursor'
        - $ref: 'common#/components/parameters/Limit'
        - { name: sort, in: query, schema: { type: string, enum: [AGE, LIQUIDITY, VOLUME_24H, HOLDERS, RISK_SCORE] } }
        - { name: order, in: query, schema: { type: string, enum: [ASC, DESC] } }
        - { name: minLiquidity, in: query, schema: { $ref: 'common#/components/schemas/DecimalString' } }
        - { name: maxAgeMinutes, in: query, schema: { type: integer, minimum: 0 } }
        - { name: minVolume24h, in: query, schema: { $ref: 'common#/components/schemas/DecimalString' } }
        - { name: minHolders, in: query, schema: { type: integer, minimum: 0 } }
        - { name: maxRiskScore, in: query, schema: { type: integer, minimum: 0, maximum: 100 } }
        - { name: sourceId, in: query, schema: { type: string } }
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - type: object
                    required: [items, excludedByUnknown, sources, pendingNewTokens]
                    properties:
                      items: { type: array, items: { $ref: '#/components/schemas/ScannerRow' } }
                      excludedByUnknown: { type: integer, description: 'Tokens fora do filtro por campo unknown, no conjunto filtrado inteiro (AC2.2.2)' }
                      pendingNewTokens: { type: integer, description: 'Novidades em espera para "Atualizar lista"' }
                      sources: { type: array, items: { $ref: '#/components/schemas/SourceStatus' } }
        '503': { description: SOURCE_UNAVAILABLE quando todas as fontes falham (AC2.3.3) }
  /api/v1/tokens/{chain}/{mint}/assessment:
    get:
      summary: Avaliação de risco com fatores, pesos e versão (FR6.1 a FR6.3)
      parameters:
        - { name: chain, in: path, required: true, schema: { type: string } }
        - { name: mint, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Assessment' }
components:
  schemas:
    ScannerRow:
      type: object
      required: [chain, mint, symbol, coverage, liquidity, volume24h, holders, ageMinutes, risk]
      properties:
        chain: { type: string }
        mint: { type: string }
        symbol: { type: string }
        name: { type: string }
        coverage:
          type: object
          required: [informed, total]
          properties:
            informed: { type: integer }
            total: { type: integer }
        price: { $ref: 'common#/components/schemas/MarketValue' }
        liquidity: { $ref: 'common#/components/schemas/MarketValue' }
        volume24h: { $ref: 'common#/components/schemas/MarketValue' }
        holders: { $ref: 'common#/components/schemas/MarketValue' }
        ageMinutes: { $ref: 'common#/components/schemas/MarketValue' }
        risk: { $ref: '#/components/schemas/RiskSummary' }
    RiskSummary:
      oneOf:
        - type: object
          required: [status, score, band]
          properties:
            status: { type: string, const: AVAILABLE }
            score: { type: integer, minimum: 0, maximum: 100 }
            band: { type: string, enum: [LOW, MEDIUM, HIGH] }
        - type: object
          required: [status, reason]
          properties:
            status: { type: string, const: UNAVAILABLE }
            reason: { type: string, enum: [INSUFFICIENT_COVERAGE, SOURCE_UNAVAILABLE] }
      discriminator: { propertyName: status }
    Assessment:
      type: object
      required: [risk, coverage, minCoverage, weightSetVersion, inValidation, decisionHint, factors]
      properties:
        risk: { $ref: '#/components/schemas/RiskSummary' }
        coverage: { $ref: 'common#/components/schemas/DecimalString', description: Percentual }
        minCoverage: { $ref: 'common#/components/schemas/DecimalString', description: Percentual }
        weightSetVersion: { type: string }
        inValidation: { type: boolean, const: true }
        decisionHint: { type: string, enum: [NO_TRADE, ELIGIBLE], description: 'NO_TRADE quando indisponível (FR6.2)' }
        factors:
          type: array
          items:
            type: object
            required: [factorId, label, weight, input, contribution]
            properties:
              factorId: { type: string }
              label: { type: string }
              weight: { $ref: 'common#/components/schemas/DecimalString' }
              input: { $ref: 'common#/components/schemas/MarketValue' }
              contribution:
                oneOf:
                  - { $ref: 'common#/components/schemas/DecimalString' }
                  - { $ref: 'common#/components/schemas/UnknownValue' }
    SourceStatus:
      type: object
      required: [source, status, lastUpdatedAt]
      properties:
        source: { $ref: 'common#/components/schemas/SourceRef' }
        status: { type: string, enum: [OK, DEGRADED, FAILED] }
        lastUpdatedAt: { $ref: 'common#/components/schemas/Instant' }
```

Comportamento: um token com o campo filtrado `UNKNOWN` fica fora do filtro e entra em `excludedByUnknown`; fronteiras de filtro são inclusivas (AC2.2.1). Uma fonte com falha aparece em `sources` com `FAILED`, e os tokens das demais fontes continuam (FR4.6). Um fator `UNKNOWN` tem contribuição `UNKNOWN` e nunca conta como risco zero (AC3.2.2). No esqueleto (U1), a mesma rota existe sem filtros e sem `risk`, com a fonte simulada (`simulated: true`).

### C3 — Convite, termos e conta (U3)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - invites and account
  version: 1.0.0
paths:
  /api/v1/invites/{code}:
    get:
      summary: Estado do convite e termos a aceitar, sem sessão (AC1.1.1)
      parameters: [ { name: code, in: path, required: true, schema: { type: string } } ]
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                required: [status, expiresAt, termsVersion, termsText, riskNoticeText]
                properties:
                  status: { type: string, enum: [PENDING, USED, EXPIRED, REVOKED] }
                  expiresAt: { $ref: 'common#/components/schemas/Instant' }
                  termsVersion: { type: string }
                  termsText: { type: string }
                  riskNoticeText: { type: string }
        '404': { description: 'INVITE_NOT_FOUND, a mesma resposta para código desconhecido e mal formado (U3, BR2.1, NFR6.7)' }
        '429': { description: 'RATE_LIMITED com Retry-After: mais de 10 consultas por minuto por IP, decidido antes da busca (U3, BR2.2)' }
  /api/v1/account:
    get:
      summary: Dados da conta (US1.5)
      security: [ { accessToken: [] } ]
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                required: [walletAddressMasked, provider, sessionExpiresAt, realMode]
                properties:
                  walletAddressMasked: { type: string, description: 4 primeiros e 4 últimos caracteres }
                  provider: { type: string, enum: [PHANTOM, SOLFLARE] }
                  sessionExpiresAt: { $ref: 'common#/components/schemas/Instant' }
                  acceptedTerms:
                    type: object
                    description: 'Ausente só para conta criada sem convite (conta de smoke do perfil de teste); U3'
                    required: [version, acceptedAt, termsText, riskNoticeText]
                    properties:
                      version: { type: string }
                      acceptedAt: { $ref: 'common#/components/schemas/Instant' }
                      termsText: { type: [string, 'null'], description: 'Texto simples daquela versão (U3, BR5.2)' }
                      riskNoticeText: { type: [string, 'null'], description: 'Texto simples daquela versão (U3, BR5.2)' }
                  realMode: { type: string, const: UNAVAILABLE }
        '401': { description: UNAUTHENTICATED; o app tenta uma renovação e depois leva à entrada com a origem }
```

Comportamento: a criação da conta acontece em `POST /api/v1/auth/verify` com `inviteCode` e `acceptedTermsVersion` (C1). Dois usos simultâneos do mesmo convite geram uma conta só; o outro recebe `INVITE_INVALID` com `reason: USED` (AC1.1.4).

Registrado pela geração de código da U3 (correções e acréscimos de [functional-spec.md](../../construction/u3-identity-access/functional-design/functional-spec.md)): a consulta do convite ganhou `404` e `429`; `acceptedTerms` passou a ser opcional (só a conta de smoke do perfil de teste não tem aceite) e traz o texto da versão aceita, porque a tela Conta mostra esse texto (BR5.2). A especificação gerada do código fica em `apps/api/openapi/openapi-guest.yaml`.

### C4 — Bots, decisões e controles (U5)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - bots
  version: 1.0.0
security: [ { accessToken: [] } ]
paths:
  /api/v1/strategy-templates:
    get:
      summary: Estratégias modelo com parâmetros, padrões e faixas (FR7.1)
      responses:
        '200':
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/StrategyTemplate' } }
  /api/v1/bots:
    get:
      summary: Bots da pessoa por estado, inclusive rascunhos (FR7.7)
      description: >
        Ordem pela última alteração, mais recente primeiro (`updated_at` decrescente; no empate, `botId`
        decrescente), com ou sem `state`; o cursor segue a mesma ordem. O painel (U9) usa essa ordem para
        "Continuar rascunho": o primeiro item de `state=DRAFT`, senão de `state=VALIDATED` (BR1.3 da U9).
      parameters:
        - { name: state, in: query, schema: { $ref: '#/components/schemas/BotState' } }
        - $ref: 'common#/components/parameters/Cursor'
        - $ref: 'common#/components/parameters/Limit'
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - type: object
                    required: [items, activeCount, activeLimit]
                    properties:
                      items: { type: array, items: { $ref: '#/components/schemas/BotSummary' } }
                      activeCount: { type: integer }
                      activeLimit: { type: integer, const: 3 }
    post:
      summary: Cria rascunho (DRAFT) a partir de uma estratégia modelo
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [templateId, name]
              properties:
                templateId: { type: string }
                name: { type: string, maxLength: 60 }
      responses:
        '201':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BotSummary' }
  /api/v1/bots/{botId}/draft:
    put:
      summary: Salva a etapa do assistente (AC4.1.3)
      parameters:
        - { name: botId, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: 'common#/components/parameters/IdempotencyKey'
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [step]
              properties:
                step: { type: integer, minimum: 1, maximum: 4 }
                parameters: { type: object, additionalProperties: { $ref: 'common#/components/schemas/DecimalString' } }
                tokenSelection: { $ref: '#/components/schemas/TokenSelection' }
                limits: { $ref: '#/components/schemas/BotLimits' }
                virtualBalance: { $ref: 'common#/components/schemas/DecimalString' }
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/BotSummary' } } } }
        '422': { description: LIMIT_INCOHERENT, VIRTUAL_BALANCE_ABOVE_CAP ou VALIDATION_FAILED }
  /api/v1/bots/{botId}/commands/{command}:
    post:
      summary: Comandos de ciclo de vida
      parameters:
        - { name: botId, in: path, required: true, schema: { type: string, format: uuid } }
        - { name: command, in: path, required: true, schema: { type: string, enum: [start, pause, resume, stop] } }
        - $ref: 'common#/components/parameters/IdempotencyKey'
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/BotSummary' } } } }
        '409': { description: INVALID_TRANSITION, ACTIVE_BOT_LIMIT ou PAUSED_BY_SYSTEM }
  /api/v1/bots/{botId}/limits:
    post:
      summary: Nova versão dos limites de bot ativo, válida só para operações futuras (US4.9)
      parameters:
        - { name: botId, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: 'common#/components/parameters/IdempotencyKey'
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/BotLimits' }
      responses:
        '201': { content: { application/json: { schema: { $ref: '#/components/schemas/BotSummary' } } } }
        '422': { description: LIMIT_INCOHERENT }
  /api/v1/bots/{botId}/decisions:
    get:
      summary: Decisões explicadas do bot (US4.5)
      parameters:
        - { name: botId, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: 'common#/components/parameters/Cursor'
        - $ref: 'common#/components/parameters/Limit'
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - type: object
                    required: [items]
                    properties:
                      items: { type: array, items: { $ref: '#/components/schemas/Decision' } }
components:
  schemas:
    BotState:
      type: string
      enum: [DRAFT, VALIDATED, ACTIVE, PAUSED, CLOSING_PENDING, STOPPED]
    PauseReason:
      type: string
      enum: [PERSON, DAILY_LOSS, DRAWDOWN, SYSTEM, ADMIN_RELEASED, WAITING_ACTIVE_SLOT]   # U5: ADMIN_RELEASED (BR7.2), WAITING_ACTIVE_SLOT (BR2.6)
    StrategyTemplate:
      type: object
      required: [templateId, name, description, version, parameters]
      properties:
        templateId: { type: string }
        name: { type: string }
        description: { type: string }
        version: { type: string }
        parameters:
          type: array
          items:
            type: object
            required: [key, label, unit, default, min, max, step]
            properties:
              key: { type: string }
              label: { type: string }
              unit: { type: string }
              default: { $ref: 'common#/components/schemas/DecimalString' }
              min: { $ref: 'common#/components/schemas/DecimalString' }
              max: { $ref: 'common#/components/schemas/DecimalString' }
              step: { $ref: 'common#/components/schemas/DecimalString' }
    TokenSelection:
      type: object
      required: [rules]
      properties:
        tokens: { type: array, items: { type: object, required: [chain, mint], properties: { chain: { type: string }, mint: { type: string } } } }
        rules: { type: array, items: { type: object, required: [field, operator, value], properties: { field: { type: string, enum: [LIQUIDITY, VOLUME_24H, HOLDERS, AGE_MINUTES, RISK_SCORE] }, operator: { type: string, enum: [GTE, LTE] }, value: { $ref: 'common#/components/schemas/DecimalString' } } } }   # U5: field fechado e operador só GTE/LTE (BR1.3)
    BotLimits:
      type: object
      required: [maxOrderSize, maxTokenExposure, maxTotalExposure, maxDailyLoss, maxDrawdown, maxSlippage, minLiquidity, minIntervalSeconds]
      properties:
        maxOrderSize: { $ref: 'common#/components/schemas/DecimalString' }
        maxTokenExposure: { $ref: 'common#/components/schemas/DecimalString' }
        maxTotalExposure: { $ref: 'common#/components/schemas/DecimalString' }
        maxDailyLoss: { $ref: 'common#/components/schemas/DecimalString' }
        maxDrawdown: { $ref: 'common#/components/schemas/DecimalString', description: Percentual }
        maxSlippage: { $ref: 'common#/components/schemas/DecimalString', description: Percentual }
        minLiquidity: { $ref: 'common#/components/schemas/DecimalString' }
        minIntervalSeconds: { type: integer, minimum: 1 }
    BotSummary:
      type: object
      required: [botId, name, mode, state, pauseReasons, limitsVersion]
      properties:
        botId: { type: string, format: uuid }
        name: { type: string }
        mode: { $ref: 'common#/components/schemas/Mode' }
        state: { $ref: '#/components/schemas/BotState' }
        pauseReasons: { type: array, items: { $ref: '#/components/schemas/PauseReason' } }
        resumesAt: { $ref: 'common#/components/schemas/Instant' }
        draftStep: { type: integer }
        limitsVersion: { type: integer }
    Decision:
      type: object
      required: [decisionId, cycleId, action, strategyVersion, limitsVersion, factors, inputs, summary, validUntil, createdAt]
      properties:
        decisionId: { type: string, format: uuid }
        cycleId: { type: string, format: uuid }
        chain: { type: string }                       # U5: acompanha tokenMint
        tokenMint: { type: string }
        action: { type: string, enum: [BUY, SELL, HOLD, NO_TRADE] }
        noTradeReason: { type: string, enum: [UNKNOWN_DATA, STALE_DATA, INSUFFICIENT_HISTORY, ASSESSMENT_UNAVAILABLE, NO_POSITION_TO_SELL, RISK_BAND_HIGH, NO_SIGNAL, NO_TOKENS, QUANTITY_BELOW_MINIMUM] }
        exitReason: { type: string, enum: [TAKE_PROFIT, STOP_LOSS, MAX_HOLD, RANGE_EXIT] }   # U5
        requestedQuantity: { $ref: 'common#/components/schemas/DecimalString' }          # U5: só BUY e SELL
        limitsVersion: { type: integer }                                                 # U5
        strategyVersion: { type: string }
        factors:
          type: array
          items:
            type: object
            required: [token, name, value, satisfied]   # U5: threshold ausente só quando o fator não tem limiar (faixa de risco, com label)
            properties:
              token: { type: object, required: [chain, mint], properties: { chain: { type: string }, mint: { type: string } } }   # U5
              label: { type: string }                                                    # U5: LOW, MEDIUM ou HIGH na faixa de risco
              name: { type: string }
              value:
                oneOf:
                  - { $ref: 'common#/components/schemas/DecimalString' }
                  - { $ref: 'common#/components/schemas/UnknownValue' }
              threshold: { $ref: 'common#/components/schemas/DecimalString' }
              satisfied: { type: boolean }
        inputs: { type: array, items: { $ref: 'common#/components/schemas/MarketValue' } }
        summary: { type: string }
        validUntil: { $ref: 'common#/components/schemas/Instant' }
        createdAt: { $ref: 'common#/components/schemas/Instant' }
```

Comportamento: comandos repetidos com a mesma `Idempotency-Key` produzem uma única transição e um único evento de auditoria (AC4.3.3, AC4.8.3). `stop` sem preço confiável leva a `CLOSING_PENDING` (AC4.12.3). Não existe comando de transferência de saldo nem de mudança de modo (AC4.2.5).

**Acréscimos da U5 (geração de código, registrados pelo Functional Design da U5).** Todos aditivos; a fonte da verdade é o `apps/api/openapi/openapi-guest.yaml` gerado do código.

| Acréscimo | Detalhe |
|---|---|
| `PauseReason` | `ADMIN_RELEASED` (BR7.2) e `WAITING_ACTIVE_SLOT` (BR2.6) |
| `Decision` | `noTradeReason` com `RISK_BAND_HIGH`, `NO_SIGNAL`, `NO_TOKENS` e `QUANTITY_BELOW_MINIMUM`; campos `exitReason`, `requestedQuantity`, `limitsVersion` e `chain`; `factors[].token` (chain e mint) e `factors[].label` |
| `BotLimits` | Os nomes REST `maxDrawdown` e `maxSlippage` (percentuais) continuam; internamente `maxDrawdownPct` e `maxSlippagePct`, com o mapeamento no controlador |
| `TokenSelection.rules` | `field` fechado em `LIQUIDITY`, `VOLUME_24H`, `HOLDERS`, `AGE_MINUTES` e `RISK_SCORE`; `operator` só `GTE` e `LTE` |
| `StrategyTemplate` | Parâmetros v1 das duas estratégias (BR4.3 a BR4.6) |
| `GET /api/v1/bots/{botId}` | Leitura do bot da pessoa (resumo, rascunho e limites vigentes) usada pelo assistente para retomar o rascunho (AC4.4.1) e pela tela S5; outro dono: `404` |
| Erros | `409 DAILY_LOSS_LIMIT_REACHED` na retomada depois da perda diária, com a perda do dia no `detail` (BR2.4); estouro do `lock_timeout` de 2 s responde `503 SERVICE_UNAVAILABLE` com "tentar de novo" (NFR10.7) |

**Histórico.** Revisão de 2026-11-09: a ordem de `GET /api/v1/bots`, que a U5 já aplicava e a U9 usa, passou a constar no bloco acima. Nada muda no comportamento.

**Usos novos de interfaces públicas pela U5** (consumidora registrada, só leitura):

| Interface | Dono | Uso pela U5 |
|---|---|---|
| `LedgerReadModel.account(botId)` | U7 | Conferência do encerramento pendente (BR2.8), com o bot travado |
| `LedgerReadModel.positions(botId)` e `balance(botId)` | U7 | Entradas da decisão dentro da transação do ciclo (BR4.1, BR3.7) |
| `PortfolioRiskView.snapshot(botId, chain, tokenMint, utcDay)` | U7 (C13) | Perda do dia na retomada depois de `DAILY_LOSS` (BR2.4), com o Principal do próprio dono. O token é o primeiro da lista explícita, senão o primeiro com posição, senão o token neutro `solana:11111111111111111111111111111111`, que nunca tem posição; a perda do dia não depende do token |

### C5 — Operações, ledger, P&L e reconciliação (U7)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - ledger and P&L
  version: 1.0.0
security: [ { accessToken: [] } ]
paths:
  /api/v1/bots/{botId}/trades:
    get:
      summary: Operações simuladas do bot, cada uma uma única vez (AC4.10.3)
      parameters:
        - { name: botId, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: 'common#/components/parameters/Cursor'
        - $ref: 'common#/components/parameters/Limit'
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - { type: object, required: [items], properties: { items: { type: array, items: { $ref: '#/components/schemas/PaperTrade' } } } }
  /api/v1/bots/{botId}/day-usage:
    get:
      summary: Uso do dia - exposição atual e perda do dia contra os limites (AC4.10.1)
      parameters: [ { name: botId, in: path, required: true, schema: { type: string, format: uuid } } ]
      responses: { '200': { content: { application/json: { schema: { $ref: '#/components/schemas/DayUsage' } } } } }
  /api/v1/ledger:
    get:
      summary: Lançamentos do ledger PAPER da pessoa (AC5.2.1)
      parameters:
        - { name: botId, in: query, schema: { type: string, format: uuid } }
        - $ref: 'common#/components/parameters/Cursor'
        - $ref: 'common#/components/parameters/Limit'
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - { type: object, required: [items], properties: { items: { type: array, items: { $ref: '#/components/schemas/LedgerEntry' } } } }
  /api/v1/pnl:
    get:
      summary: P&L por token, bot e período (FR10.3)
      parameters:
        - { name: botId, in: query, schema: { type: string, format: uuid } }
        - { name: from, in: query, required: true, schema: { $ref: 'common#/components/schemas/Instant' } }
        - { name: to, in: query, required: true, schema: { $ref: 'common#/components/schemas/Instant' } }
      responses: { '200': { content: { application/json: { schema: { $ref: '#/components/schemas/PnlReport' } } } } }
  /api/v1/reconciliation:
    get:
      summary: Resultado da última conferência entre operações e ledger (FR10.5)
      parameters: [ { name: botId, in: query, schema: { type: string, format: uuid } } ]
      responses: { '200': { content: { application/json: { schema: { $ref: '#/components/schemas/ReconciliationResult' } } } } }
  # U7 (BR8.3): "verificar agora" - confere na hora as contas da pessoa, grava ON_DEMAND e devolve o resultado.
  /api/v1/reconciliation/run:
    post:
      summary: Verificar agora (BR8.3); só lê o ledger, no máximo 6 por minuto por pessoa
      parameters: [ { name: Idempotency-Key, in: header, required: true, schema: { type: string, format: uuid } } ]
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/ReconciliationResult' } } } }
        '429': { description: 'RATE_LIMITED com Retry-After' }
components:
  schemas:
    # U7: um item por ordem, inclusive NOT_FILLED e CLOSE_PENDING; os campos do preenchimento só existem com Fill.
    PaperTrade:
      type: object
      required: [orderId, mode, kind, side, chain, tokenMint, status, requestedQuantity, limitsVersion, idempotencyKey, createdAt]
      properties:
        orderId: { type: string, format: uuid }
        mode: { $ref: 'common#/components/schemas/Mode' }
        kind: { type: string, enum: [TRADE, CLOSE] }
        side: { type: string, enum: [BUY, SELL] }
        chain: { type: string }
        tokenMint: { type: string }
        status: { type: string, enum: [FILLED, PARTIALLY_FILLED, NOT_FILLED, CLOSE_PENDING] }
        notFilledReason: { type: string, enum: [PRICE_UNAVAILABLE, NO_LIQUIDITY, QUANTITY_BELOW_MINIMUM, NO_POSITION, ACCOUNT_CLOSING, INSUFFICIENT_BALANCE, TENANT_MISMATCH] }
        requestedQuantity: { $ref: 'common#/components/schemas/DecimalString' }
        filledQuantity: { $ref: 'common#/components/schemas/DecimalString' }
        referencePrice: { $ref: 'common#/components/schemas/DecimalString' }
        quotedPrice: { $ref: 'common#/components/schemas/DecimalString' }
        fillPrice: { $ref: 'common#/components/schemas/DecimalString' }
        spreadPct: { $ref: 'common#/components/schemas/DecimalString' }
        impactPct: { $ref: 'common#/components/schemas/DecimalString' }
        slippage: { $ref: 'common#/components/schemas/DecimalString', description: Percentual }
        liquidityConsumedPct: { $ref: 'common#/components/schemas/DecimalString' }
        grossAmount: { $ref: 'common#/components/schemas/DecimalString' }
        fees: { $ref: 'common#/components/schemas/DecimalString' }
        realized: { $ref: 'common#/components/schemas/DecimalString' }
        marketSource: { type: string }
        marketObservedAt: { $ref: 'common#/components/schemas/Instant' }
        simulationPolicyVersion: { type: string }
        limitsVersion: { type: integer }
        idempotencyKey: { type: string }
        createdAt: { $ref: 'common#/components/schemas/Instant' }
        filledAt: { $ref: 'common#/components/schemas/Instant' }
    LedgerEntry:
      type: object
      required: [entryId, mode, botId, type, amount, asset, referenceId, createdAt]
      properties:
        entryId: { type: string, format: uuid }
        mode: { $ref: 'common#/components/schemas/Mode' }
        botId: { type: string, format: uuid }
        type: { type: string, enum: [FUNDING, TRADE_COST, TRADE_PROCEEDS, FEE, SLIPPAGE, CLOSE, COMPENSATION] }
        amount: { $ref: 'common#/components/schemas/DecimalString' }
        asset: { type: string }
        referenceId: { type: string }
        orderId: { type: string, format: uuid, description: 'Operação do lançamento, para agrupar (BR10.2)' }
        simulationPolicyVersion: { type: string }
        compensatesEntryId: { type: string, format: uuid }
        createdAt: { $ref: 'common#/components/schemas/Instant' }
    # U7: valor do P&L conhecido ou unknown com motivo, discriminado por status como o MarketValue.
    PnlAmount:
      oneOf:
        - { type: object, required: [status, value], properties: { status: { const: KNOWN }, value: { $ref: 'common#/components/schemas/DecimalString' } } }
        - { type: object, required: [status, reason], properties: { status: { const: UNKNOWN }, reason: { type: string, enum: [PRICE_UNKNOWN, STALE, NO_LIQUIDITY] } } }
      discriminator: { propertyName: status }
    PnlTokenLine:
      type: object
      required: [chain, mint, realized, unrealized, fees, mark, noLiquidity]
      properties:
        chain: { type: string }
        mint: { type: string }
        realized: { $ref: 'common#/components/schemas/DecimalString' }
        unrealized: { $ref: '#/components/schemas/PnlAmount' }
        fees: { $ref: 'common#/components/schemas/DecimalString' }
        mark: { $ref: 'common#/components/schemas/MarketValue' }
        noLiquidity: { type: boolean, description: 'Marcação sem liquidez (AC5.1.4)' }
    PnlReport:
      type: object
      required: [from, to, realized, unrealized, fees, partial, empty, unrealizedAsOf, byToken]
      properties:
        from: { $ref: 'common#/components/schemas/Instant' }
        to: { $ref: 'common#/components/schemas/Instant' }
        realized: { $ref: 'common#/components/schemas/DecimalString' }
        unrealized: { $ref: '#/components/schemas/PnlAmount' }
        fees: { $ref: 'common#/components/schemas/DecimalString' }
        partial: { type: boolean, description: 'true quando alguma posição tem não realizado unknown (AC5.1.3)' }
        empty: { type: boolean, description: 'Nenhum preenchimento no período e nenhuma posição aberta (BR6.6)' }
        unrealizedAsOf: { $ref: 'common#/components/schemas/Instant' }
        byToken: { type: array, items: { $ref: '#/components/schemas/PnlTokenLine' } }
    # U7 (Q11, BR7.5): os limites vêm da U5 na tela; a rota devolve só o uso.
    DayUsage:
      type: object
      required: [exposure, dayLoss, utcDay]
      properties:
        exposure: { $ref: 'common#/components/schemas/DecimalString' }
        dayLoss: { $ref: 'common#/components/schemas/DecimalString' }
        utcDay: { type: string, format: date }
    ReconciliationResult:
      type: object
      required: [status, divergenceCount, divergences]
      properties:
        # PENDING: a pessoa tem conta PAPER e ainda não houve conferência.
        status: { type: string, enum: [RECONCILED, DIVERGENT, PENDING] }
        trigger: { type: string, enum: [SCHEDULED, ON_DEMAND] }
        checkedAt: { $ref: 'common#/components/schemas/Instant' }
        divergenceCount: { type: integer }
        divergences:
          type: array
          items:
            type: object
            required: [botId, kind, expected, found]
            properties:
              botId: { type: string, format: uuid }
              orderId: { type: string, format: uuid }
              positionId: { type: string, format: uuid }
              entryId: { type: string, format: uuid }
              utcDay: { type: string, format: date }
              kind: { type: string, enum: [MISSING_ENTRY, AMOUNT_MISMATCH, ORPHAN_ENTRY, POSITION_MISMATCH, SUMMARY_MISMATCH] }
              expected: { $ref: 'common#/components/schemas/DecimalString' }
              found: { $ref: 'common#/components/schemas/DecimalString' }
```

Acréscimos da U7 (Functional Design e NFR Design da U7): `LedgerEntry.type` ganha `FUNDING`; `PaperTrade` ganha `requestedQuantity`, `filledQuantity`, `status`, `notFilledReason`, `spreadPct`, `impactPct`, `liquidityConsumedPct`, `marketSource`, `marketObservedAt`; `PnlReport` ganha `empty` e `unrealizedAsOf`; `PnlTokenLine.unrealized` `UNKNOWN` traz o motivo; `divergences.kind` ganha `POSITION_MISMATCH` e `SUMMARY_MISMATCH`; `DayUsage` perde `maxTotalExposure` e `maxDailyLoss` (a tela da U5 combina com os limites da própria U5); rota nova `POST /api/v1/reconciliation/run`. Bot de outra pessoa: `404 NOT_FOUND` em toda rota; bot ainda não ativado (sem conta PAPER): resposta vazia. Período `from >= to` ou maior que 366 dias: `400 VALIDATION_FAILED`. Falha técnica: `503 SERVICE_UNAVAILABLE`.

Comportamento: só leitura. Não existe rota de alteração ou exclusão de lançamento (FR10.1, AC5.2.2). O total por token soma exatamente o total geral; com algum não realizado `UNKNOWN`, o total não realizado também é `UNKNOWN` e `partial` é `true` (AC5.1.1, AC5.1.3).

### C6 — Envio de feedback (U8)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - feedback
  version: 1.0.0
security: [ { accessToken: [] } ]
paths:
  /api/v1/feedback:
    post:
      summary: Registra comentário com tela de origem (FR13.1)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [screen, text]
              properties:
                screen: { type: string, enum: [S1, S2, S3, S4, S5, S6, S7, S8, S9] }
                text: { type: string, minLength: 1, maxLength: 2000 }
      responses:
        '201': { description: Registrado }
        '400': { description: VALIDATION_FAILED; o app preserva o texto (AC6.1.3) }
```

**Registrado pela geração de código da U8.** A fonte da verdade é o `apps/api/openapi/openapi-guest.yaml` gerado do código.

- `text` tem de 1 a 2.000 pontos de código e não pode ficar vazio depois de tirar os espaços; `screen` fica entre `S1` e `S9`. Qualquer campo a mais, inclusive `tenantId` ou `userId`, é `400 VALIDATION_FAILED`, pela regra comum de corpo fechado. O tenant e a pessoa vêm só do token.
- A resposta `201` traz `feedbackId` e `createdAt`.
- Acréscimo `429 RATE_LIMITED` com `Retry-After`: a pessoa já enviou 10 comentários na última hora. Respostas `400` e `429` não ficam guardadas pela idempotência; a mesma `Idempotency-Key` com o mesmo corpo depois de um `201` devolve o mesmo `201`, e com outro corpo devolve `409 IDEMPOTENCY_CONFLICT`.
- Token de administrador nesta rota: `403 FORBIDDEN_ROLE`, auditado como `REQUEST_FORBIDDEN`.

### C7 — Canal ao vivo do painel (hospedado pela U5, consumido pela U9)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - dashboard stream
  version: 1.1.0
security: [ { accessToken: [] } ]
paths:
  /api/v1/stream/dashboard:
    get:
      operationId: openDashboardStream
      summary: Server-Sent Events do painel (FR12.2), aberto com fetch em leitura contínua e cabeçalho Authorization (Q8, Q14)
      parameters:
        - { name: Last-Event-ID, in: header, schema: { type: string } }
      responses:
        '200':
          description: >
            Cada evento tem `id` (`b<marca>.l<marca>.s<marca>`, crescente por canal, só devolvido em
            `Last-Event-ID` e nunca interpretado pelo app), `event` (o nome) e `data` (o JSON do envelope do
            nome): `bot.state` DashboardBotStateEvent, `trade.recorded` DashboardTradeRecordedEvent (aviso; o app
            busca preços e motivo em `GET /api/v1/bots/{botId}/trades?limit=1` de C5), `pnl.updated`
            DashboardPnlUpdatedEvent (aviso com a marca do ledger; o app busca o PnlReport em C5), `data.status`
            DashboardDataStatusEvent, `heartbeat` DashboardHeartbeatEvent (a cada 15 s) e `auth.expired`
            DashboardAuthExpiredEvent. O canal é hospedado pela U5 no processo de API e, a cada 2 s (NFR Design),
            lê as marcas de última alteração de C18 só das pessoas com canal aberto; quando uma marca muda,
            busca os detalhes pelas interfaces públicas e envia, sempre filtrando por tenant e pessoa. A cada
            60 s, junto do `heartbeat`, refaz a leitura completa e reenvia `bot.state`. Mudanças feitas pelo
            worker aparecem sem evento entre processos, dentro do limite de 60 s (AC5.4.1). Com `auth.expired`,
            o servidor fecha o canal; o app renova o token (C1) e reabre com `Last-Event-ID`, sem repetir os já
            entregues. Sem eventos novos em 60 s, o app mostra que os valores podem estar desatualizados (AC5.4.3).
          content:
            text/event-stream:
              schema:
                oneOf:
                  - $ref: '#/components/schemas/DashboardBotStateEvent'
                  - $ref: '#/components/schemas/DashboardTradeRecordedEvent'
                  - $ref: '#/components/schemas/DashboardPnlUpdatedEvent'
                  - $ref: '#/components/schemas/DashboardDataStatusEvent'
                  - $ref: '#/components/schemas/DashboardHeartbeatEvent'
                  - $ref: '#/components/schemas/DashboardAuthExpiredEvent'
        '401': { description: UNAUTHENTICATED }
        '429':
          description: >
            RATE_LIMITED: a pessoa já tem o número máximo de canais abertos (`memetrade.bots.stream.max-channels-per-person`,
            padrão 5, de 1 a 20; NFR2.1 da U9). Decidido antes de abrir o fluxo, sem dado da pessoa no corpo.
            Fechar um canal (fim, tempo esgotado, erro, escrita que falha ou `auth.expired`) libera a vaga na hora.
            O app trata como queda: espera ao menos o Retry-After e segue a espera crescente (2, 4, 8, 16 e 30 s).
          headers:
            Retry-After: { schema: { type: integer } }
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
components:
  schemas:
    DashboardBotStateEvent:
      description: Até 100 bots da pessoa, de qualquer estado, mais recentes primeiro; sem activeLimit nem contagens por estado
      type: object
      additionalProperties: false
      required: [activeCount, items]
      properties:
        activeCount: { type: integer, format: int64 }
        items: { type: array, items: { $ref: '#/components/schemas/BotSummary' } }
    DashboardTradeRecordedEvent:
      type: object
      additionalProperties: false
      required: [items]
      properties:
        items: { type: array, items: { $ref: '#/components/schemas/DashboardTradeNotice' } }
    DashboardTradeNotice:
      description: Ordem simulada nova, sem preços nem notFilledReason
      type: object
      additionalProperties: false
      required: [orderId, botId, side, chain, tokenMint, status, createdAt]
      properties:
        orderId: { type: string, format: uuid }
        botId: { type: string, format: uuid }
        side: { type: string, enum: [BUY, SELL] }
        chain: { type: string }
        tokenMint: { type: string }
        status: { type: string, enum: [FILLED, PARTIALLY_FILLED, NOT_FILLED, CLOSE_PENDING] }
        createdAt: { type: string, format: date-time }
    DashboardPnlUpdatedEvent:
      type: object
      additionalProperties: false
      required: [ledgerMark]
      properties:
        ledgerMark: { type: integer, format: int64 }
    DashboardDataStatusEvent:
      type: object
      additionalProperties: false
      required: [items]
      properties:
        items: { type: array, items: { $ref: '#/components/schemas/SourceStatus' } }
    DashboardHeartbeatEvent:
      type: object
      additionalProperties: false
      required: [at]
      properties:
        at: { type: string, format: date-time }
    DashboardAuthExpiredEvent:
      type: object
      additionalProperties: false
      required: [expiredAt]
      properties:
        expiredAt: { type: string, format: date-time }
```

**Implementação.** O canal fica no processo de API (`DashboardStreamController`), e um único `DashboardMarkPoller` lê as marcas (U5 `BotChangeMarks`, U7 `LedgerChangeMarks`, U4 `SourceStatusMarks`). O OpenAPI gerado (`apps/api/openapi/openapi-guest.yaml`) é a fonte da verdade e coincide com o bloco acima.

**Propriedade.** A U5 é dona de C7, inclusive do teto de canais por pessoa. Mudar o teto ou os envelopes exige o smoke da U9 verde (`e2e/u9-dashboard`). A U9 é a consumidora registrada.

**Histórico.** `1.0.0`: desenho inicial. `1.1.0` (revisão de 2026-11-09): o bloco passou a mostrar os envelopes que a U5 já enviava (`trade.recorded` e `pnl.updated` como avisos, não `PaperTrade` nem `PnlReport`) e o `429` do teto de canais acrescentado na U9. Isso não quebra nada: a U9, única consumidora, foi construída sobre esse formato.

### C8 — API administrativa (U8)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade admin API
  version: 1.0.0
security: [ { accessToken: [] } ]
paths:
  /api/v1/admin/auth/challenge:
    post:
      summary: Mesma forma de C1; só a carteira de administrador da configuração recebe desafio válido para login
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
  /api/v1/admin/auth/verify:
    post:
      summary: Mesma forma de C1, sem inviteCode; responde AdminSession; outra carteira recebe FORBIDDEN_ROLE (AC1.2.6)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
  /api/v1/admin/auth/refresh:
    post: { summary: Mesma forma de C1, com cookie próprio; sem Idempotency-Key }
  /api/v1/admin/auth/logout:
    post:
      summary: Mesma forma de C1
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
  /api/v1/admin/invites:
    get:
      summary: Lista convites com estado e validade (AC7.1.2)
      parameters:
        - $ref: 'common#/components/parameters/Cursor'
        - { name: limit, in: query, schema: { type: integer, minimum: 1, maximum: 100, default: 20 }, description: 'U3 (NFR2.2): padrão 20; fora de 1 a 100 é 400' }
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - { type: object, required: [items], properties: { items: { type: array, items: { $ref: '#/components/schemas/Invite' } } } }
        '400': { description: VALIDATION_FAILED em limit ou cursor }
    post:
      summary: Emite convite de uso único para um tenant (AC7.1.1)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [tenantId, expiresAt]
              properties:
                tenantId: { type: string, format: uuid, description: 'Exceção à regra de tenant: o administrador escolhe o tenant do convite (ADR-002)' }
                expiresAt: { $ref: 'common#/components/schemas/Instant' }
      responses:
        '201': { description: 'Convite com o código completo; repetir com a mesma chave devolve o mesmo convite SEM code (U3, BR1.8)', content: { application/json: { schema: { $ref: '#/components/schemas/Invite' } } } }
        '400': { description: VALIDATION_FAILED (Idempotency-Key ausente ou corpo inválido) }
        '409': { description: IDEMPOTENCY_CONFLICT }
        '422': { description: 'VALIDATION_FAILED no campo tenantId (inexistente ou não ACTIVE) ou expiresAt (fora de 1 hora a 30 dias); U3, BR1.1' }
  /api/v1/admin/invites/{inviteId}/revoke:
    post:
      parameters:
        - { name: inviteId, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: 'common#/components/parameters/IdempotencyKey'
      responses:
        '200': { description: 'Revogado; revogar de novo devolve o mesmo convite, sem novo evento (U3, BR1.4)', content: { application/json: { schema: { $ref: '#/components/schemas/Invite' } } } }
        '404': { description: 'NOT_FOUND: convite inexistente (U3)' }
        '409': { description: 'INVITE_NOT_REVOCABLE com reason USED ou EXPIRED (U3, BR1.4)' }
  /api/v1/admin/kill-switch:
    get:
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                required: [state, activeBotsToPause]
                properties:
                  state: { type: string, enum: [ON, OFF] }
                  changedBy: { type: string }
                  changedAt: { $ref: 'common#/components/schemas/Instant' }
                  reason: { type: string }
                  activeBotsToPause: { type: integer, description: Para a confirmação (AC7.2.1) }
    put:
      summary: Aciona ou desliga o kill switch (FR3.2)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [state, reason]
              properties:
                state: { type: string, enum: [ON, OFF] }
                reason: { type: string, maxLength: 500 }
      responses: { '200': { description: Estado confirmado } }
  /api/v1/admin/system-status:
    get:
      summary: Só contagens e estado das fontes (AC7.4.1)
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                required: [guestCount, inviteCounts, botCountsByState, killSwitch, sources, auditOutboxPending]
                properties:
                  guestCount: { type: integer }
                  inviteCounts: { type: object, additionalProperties: { type: integer }, description: 'Chaves PENDING, USED, EXPIRED, REVOKED' }
                  botCountsByState: { type: object, additionalProperties: { type: integer }, description: 'Chaves de BotState' }
                  killSwitch: { type: string, enum: [ON, OFF] }
                  sources: { type: array, items: { $ref: 'C2#/components/schemas/SourceStatus' } }
                  auditOutboxPending: { type: integer, description: 'Eventos de auditoria ainda não gravados na trilha; acima do limite do NFR Design indica atraso da trilha' }
  /api/v1/admin/feedback:
    get:
      summary: Comentários como texto puro (AC6.1.4)
      parameters:
        - $ref: 'common#/components/parameters/Cursor'
        - $ref: 'common#/components/parameters/Limit'
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - type: object
                    required: [items]
                    properties:
                      items:
                        type: array
                        items:
                          type: object
                          required: [feedbackId, tenantId, screen, text, createdAt]
                          properties:
                            feedbackId: { type: string, format: uuid }
                            tenantId: { type: string, format: uuid }
                            screen: { type: string }
                            text: { type: string }
                            createdAt: { $ref: 'common#/components/schemas/Instant' }
components:
  schemas:
    AdminSession:
      type: object
      required: [accessToken, accessTokenExpiresAt, role, sessionExpiresAt]
      properties:
        accessToken: { type: string }
        accessTokenExpiresAt: { $ref: 'common#/components/schemas/Instant' }
        sessionExpiresAt: { $ref: 'common#/components/schemas/Instant' }
        role: { type: string, enum: [ADMIN] }
    Invite:
      type: object
      required: [inviteId, tenantId, status, expiresAt, createdAt, usedAt, revokedAt, codeSuffix]
      properties:
        inviteId: { type: string, format: uuid }
        code: { type: string, description: 'Só na resposta 201 da emissão, nunca numa repetição nem na lista (U3, Q2, BR1.3)' }
        codeSuffix: { type: string, minLength: 4, maxLength: 4, description: 4 últimos caracteres do código (U3) }
        tenantId: { type: string, format: uuid }
        status: { type: string, enum: [PENDING, USED, EXPIRED, REVOKED] }
        expiresAt: { $ref: 'common#/components/schemas/Instant' }
        createdAt: { $ref: 'common#/components/schemas/Instant' }
        usedAt: { type: [string, 'null'], format: date-time }
        revokedAt: { type: [string, 'null'], format: date-time }
```

Comportamento: toda rota exige o token do administrador; um token de convidado recebe `403 FORBIDDEN_ROLE`, auditado (AC7.1.4, AC7.2.5). Não existe rota para iniciar, pausar ou encerrar o bot de um usuário, nem para ler ledger ou operações (AC7.4.2).

Registrado pela geração de código da U3: as rotas de convite já existem no backend (`AdminInviteController`, usado pela tela da U8) e estão em `apps/api/openapi/openapi-admin.yaml`. `Invite` não tem mais `code` obrigatório e nunca traz quem emitiu; a lista e a revogação nunca trazem o código. A rota dos tenants ativos para o formulário de convite fica para a U8, sobre `IdentityAdministration.activeTenants()` (C17).

**Registrado pela geração de código da U8** (desenho funcional, NFR Design e infraestrutura da U8). Todos os acréscimos são aditivos; a fonte da verdade é o `apps/api/openapi/openapi-admin.yaml` gerado do código.

| Acréscimo | Detalhe |
|---|---|
| Rotas `admin/auth/*` | `challenge` responde `200` com um desafio para qualquer endereço válido, sem revelar a lista de administradores (R-05 do NFR Design). `verify` recusa com a mesma resposta genérica `403 FORBIDDEN_ROLE` a carteira fora da lista e a carteira de convidado; o motivo exato (`NOT_ADMIN_WALLET`, `WALLET_IS_GUEST`) fica só no evento `LOGIN_REJECTED`. Nonce reutilizado, vencido, de outro escopo ou assinatura inválida: `401 UNAUTHENTICATED`. Acima de 5 pedidos por minuto por IP ou por par (endereço, IP): `429 RATE_LIMITED` com `Retry-After`. `verify` e `refresh` devolvem `AdminSession` (`accessToken`, `accessTokenExpiresAt`, `sessionExpiresAt`, `role: ADMIN`, `walletMasked`) e o cookie `mt_admin_refresh` (`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/admin/auth/refresh`). O desafio do administrador tem domínio próprio (o do app do administrador): um desafio do convidado não vale aqui, e vice-versa |
| Kill switch | `GET` e `PUT` devolvem `KillSwitch` com `state`, `changedBy` (carteira mascarada 4 + 4), `changedAt`, `reason`, `pendingBots`, `reconcileNeeded` e `version`; o `GET` traz também `activeBotsToPause`. O `PUT` exige `Idempotency-Key`; `reason` tem de 1 a 500 caracteres (`400 VALIDATION_FAILED`) |
| `system-status` | Cada parte (`guestCount`, `inviteCounts`, `botCountsByState`, `killSwitch`, `sources`, `auditOutboxPending` e o novo `rejectedOrdersToday`) é uma união `{status: OK, value}` ou `{status: UNKNOWN, reason: TIMEOUT \| UNAVAILABLE}`, para uma contagem ausente nunca virar zero (BR3.3). Inclui `generatedAt` |
| `GET /api/v1/admin/tenants` | Lista de `TenantOption` (`tenantId`, `name`) dos tenants ativos, para o formulário de convite |
| Feedback | Cada item ganha `tenantName`; nunca `userId` nem carteira. Paginação por cursor, do mais novo para o mais antigo, até 100 por página |
| CORS | `AdminCorsPolicy` só para `/api/v1/admin/**`, origem única `ADMIN_APP_ORIGIN`, métodos `GET`, `POST`, `PUT` e `OPTIONS`, credenciais só em `verify`, `refresh` e `logout` (R-08 do NFR Design). A política do convidado não muda e não aceita a origem do administrador |

### C21 — Ordens recusadas (U6)

```yaml
openapi: 3.1.0
info:
  title: MemeTrade guest API - order rejections
  version: 1.0.0
security: [ { accessToken: [] } ]
paths:
  /api/v1/bots/{botId}/rejections:
    get:
      summary: Ordens recusadas com o limite que barrou e o horário (AC4.10.4, Q17)
      parameters:
        - { name: botId, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: 'common#/components/parameters/Cursor'
        - $ref: 'common#/components/parameters/Limit'
      responses:
        '200':
          content:
            application/json:
              schema:
                allOf:
                  - $ref: 'common#/components/schemas/PageMeta'
                  - type: object
                    required: [items]
                    properties:
                      items:
                        type: array
                        items:
                          type: object
                          required: [rejectionId, decisionId, reasons, limitsVersion, evaluatedAt]
                          properties:
                            rejectionId: { type: string, format: uuid }
                            decisionId: { type: string, format: uuid }
                            reasons:
                              type: array
                              items:
                                type: string
                                enum: [ORDER_SIZE, TOKEN_EXPOSURE, TOTAL_EXPOSURE, SLIPPAGE, MIN_LIQUIDITY, STALE_DATA, INTERVAL, DUPLICATE, INSUFFICIENT_PAPER_BALANCE, DAILY_LOSS, DRAWDOWN]
                            limitsVersion: { type: integer }
                            evaluatedAt: { $ref: 'common#/components/schemas/Instant' }
                            # Acréscimos aditivos da U6 (Q10 do Functional Design)
                            pauseTriggers:
                              type: array
                              items: { type: string, enum: [DAILY_LOSS, DRAWDOWN] }
                            details:
                              type: array
                              items:
                                type: object
                                required: [reason, measured, limit, unit]
                                properties:
                                  reason: { type: string }
                                  measured: { type: string, description: 'DecimalString na escala da unidade, ou UNKNOWN; em DUPLICATE, a decisão' }
                                  limit: { type: string, description: 'DecimalString; em DUPLICATE, o evaluationId original' }
                                  unit: { type: string, enum: [USDC, PERCENT, SECONDS, REFERENCE] }
                            market:
                              type: object
                              required: [referencePrice, liquidity, estimatedSlippagePct, orderValue]
                              properties:
                                referencePrice: { $ref: 'common#/components/schemas/MarketValue' }
                                liquidity: { $ref: 'common#/components/schemas/MarketValue' }
                                estimatedSlippagePct: { type: string, description: 'pontos com 4 casas, ou UNKNOWN' }
                                orderValue: { type: string, description: 'USDC com 6 casas, ou UNKNOWN' }
        '404':
          description: 'NOT_FOUND: nenhuma avaliação desse bot para a pessoa do token'
```

Comportamento: a U6 grava `tenantId`, `ownerUserId` e `mode` em cada avaliação e recusa e filtra toda leitura por eles, a partir do token, sem consultar a U5. Só `GET`.

Mudanças registradas pela U6 (Functional Design e NFR Design da U6):
- **404 para todo bot sem avaliação da pessoa do token.** Bot de outra pessoa (avaliado ou não), de outro tenant, inexistente, bot próprio que ainda não passou pela barreira e bot cujas avaliações foram todas apagadas pelo expurgo respondem o mesmo `404 NOT_FOUND`. A tela do bot (U5) trata esse `404` como "nenhuma recusa".
- **Página vazia** quando o bot foi avaliado e nenhuma avaliação foi recusada.
- **Ordem e cursor.** Da mais recente para a mais antiga (`evaluatedAt` e `rejectionId` decrescentes), cursor opaco, `limit` de 1 a 100 (padrão 50). Cursor ilegível ou de outra consulta e parâmetro desconhecido respondem `400 VALIDATION_FAILED`.
- **Total limitado.** `total` conta até 10.000; acima disso vem `10000` com `totalCapped = true`.
- **DUPLICATE de decisão já executada** aparece na tela como "decisão já avaliada; vale o resultado original", não como limite violado.

### C9 — Auditoria por tabela de saída (U2; implementação mínima em U1)

A interface publicadora é a final desde o esqueleto (Q10). Ela grava o evento numa tabela de saída, na mesma transação da ação, tanto no processo de API quanto no worker. O AuditTrail, no processo de API, lê a tabela e grava cada evento na trilha uma única vez (Q18). Se a transação da ação desfaz, o evento também desaparece; se o processo cai depois do commit, o evento já está na tabela e será gravado. Isso cumpre "exatamente um evento por ação sensível" (FR2.3, DP-2) sem ressalva.

```yaml
shared-schema: AuditEventPublisher and audit_outbox
owner: u2-audit-trail
language: java
interface: |
  public interface AuditEventPublisher {
      // Exige uma transação ativa (propagação MANDATORY): grava em audit_outbox junto com a ação.
      void publish(AuditEventCommand event);
  }
  public record AuditEventCommand(
      UUID eventId,               // gerado pelo chamador; idempotência na trilha
      Optional<UUID> tenantId,    // vazio só em eventos de antes do login e em ações globais do administrador
      String actor,               // userId, adminId, endereço mascarado (antes do login) ou "system"
      ActorType actorType,        // PERSON, ADMIN, SYSTEM, ANONYMOUS
      String action,              // ex.: LOGIN_SUCCEEDED, LOGIN_REJECTED, BOT_PAUSED, ORDER_REJECTED, KILL_SWITCH_ON
      String targetRef,
      String correlationId,
      Instant occurredAt,         // UTC, do relógio injetável
      Map<String, String> details // nunca segredo, token, nonce nem assinatura
  ) {}
table: |
  audit_outbox (
    event_id uuid primary key,
    payload jsonb not null,
    created_at timestamptz not null,
    delivered_at timestamptz null
  )
relay:
  - O relay do AuditTrail roda só no processo de API, lê linhas com delivered_at nulo em lotes (SELECT ... FOR UPDATE SKIP LOCKED), grava em audit_event com event_id único e marca delivered_at na mesma transação.
  - event_id repetido em audit_event é descartado sem erro (idempotência).
  - Intervalo do relay proposto em 1 s; limite de atraso e alarme no NFR Design. auditOutboxPending em C8 mostra o atraso ao administrador.
rules:
  - O chamador publica no máximo um evento por ação sensível; repetição idempotente reutiliza o mesmo eventId.
  - tenantId vazio só para eventos de antes do login (por exemplo, LOGIN_REJECTED sem conta) com actorType ANONYMOUS, e para ações globais do administrador (kill switch). A consulta restrita mostra eventos sem tenant só ao administrador.
  - Endereço de carteira vai completo só em details da trilha; actor e qualquer saída fora da trilha usam a forma mascarada.
  - audit_outbox é a única tabela da U2 que outros módulos tocam, e só por meio de AuditEventPublisher; ninguém lê a tabela diretamente.
actions-added-by-u3:
  - 'INVITE_ISSUED: ator o administrador (ADMIN, sem tenant), targetRef invite:<inviteId>, details tenantId e expiresAt; nunca o código'
  - 'INVITE_REVOKED: ator o administrador, targetRef invite:<inviteId>, details tenantId; a revogação repetida não grava evento'
  - 'ACCOUNT_CREATED: ator a nova pessoa (PERSON, tenant do convite), targetRef invite:<inviteId>, details termsVersion, acceptedAt, provider e walletAddress; substitui o LOGIN_SUCCEEDED da primeira entrada'
  - 'Recusas da verificação com convite gravam um único LOGIN_REJECTED com reason INVITE_NOT_FOUND, INVITE_EXPIRED, INVITE_REVOKED, INVITE_USED, ADMIN_WALLET, WALLET_ALREADY_LINKED, TENANT_INACTIVE ou TERMS_NOT_ACCEPTED'
actions-added-by-u7:
  - 'PAPER_ACCOUNT_FUNDED: ator a pessoa do ciclo (PERSON), targetRef bot:<botId>; details sem valores'
  - 'PAPER_ORDER_FILLED: ator a pessoa do ciclo, targetRef order:<orderId>, details status (FILLED ou PARTIALLY_FILLED) e kind'
  - 'PAPER_ORDER_NOT_FILLED: ator a pessoa do ciclo, targetRef order:<orderId>, details reason'
  - 'POSITION_CLOSE_PENDING: ator a pessoa do ciclo, targetRef position:<positionId>'
  - 'POSITION_CLOSE_COMPLETED: ator system (SYSTEM, sem tenant), targetRef position:<positionId>, details botId'
  - 'LEDGER_ENTRY_COMPENSATED: ator a pessoa, targetRef entry:<entryId compensado>, details reason'
  - 'TENANT_MISMATCH nas interfaces C13 e C15 grava REQUEST_FORBIDDEN (reason TENANT_MISMATCH), sem ordem'
  - 'Duplicate (mesma chave e mesmo conteúdo) não publica evento novo'
```

Na U1, a implementação mínima de `AuditEventPublisher` já grava na tabela de saída, e o relay mínimo grava na trilha; a U2 completa consulta, mascaramento, varredura de logs e as demais ações sem mudar a interface nem os chamadores (Q10).

### C10 — Contexto de autenticação (U1)

```yaml
shared-schema: CurrentPrincipal
owner: u1-walking-skeleton
language: java
interface: |
  public interface AuthContext {
      Principal current(); // lança UnauthenticatedException quando não há sessão
  }
  public record Principal(UUID tenantId, UUID userId, Role role) {}
  public enum Role { BETA_USER, ADMIN }
rules:
  - Único caminho para obter tenant e pessoa; nenhum módulo lê tenant do corpo da requisição.
  - Tarefas em segundo plano recebem um Principal de sistema com o tenant do recurso e validam que ele bate com o do bot (AC1.4.3).
```

### C11 e C12 — Dados de mercado e avaliação (U4)

```yaml
shared-schema: MarketDataQuery and RiskAssessmentQuery
owner: u4-discovery
language: java
interface: |
  public sealed interface MarketValue permits Known, Unknown {}
  public record Known(BigDecimal value, SourceRef source, Instant observedAt, boolean stale) implements MarketValue {}
  public record Unknown(UnknownReason reason, Optional<SourceRef> source) implements MarketValue {}

  public interface MarketDataQuery {
      MarketValue referencePrice(TokenId token);
      MarketValue liquidity(TokenId token);
      // U7 (BR1.10): casas decimais do mint (token.decimals). Known com valor inteiro (escala 0, de 0 a 18); token
      // nunca observado é Unknown, nunca zero. O MarketValue existente não é genérico: o "MarketValue<Integer>" do
      // functional-spec da U7 trafega como BigDecimal inteiro, lido pelo consumidor com intValueExact().
      MarketValue decimals(TokenId token);
      PriceVolumeWindow window(TokenId token, Duration length); // pode ter lacunas Unknown
      List<SourceStatus> sourceStatuses();
  }
  public interface RiskAssessmentQuery {
      AssessmentResult assess(TokenId token); // Available(score, band, version) ou Unavailable(reason)
  }
  public record TokenId(String chain, String mint) {}
rules:
  - Valores ausentes são Unknown, nunca zero nem null.
  - stale = true quando a idade passa de 5 minutos; o consumidor aplica o próprio limite de frescor (por exemplo, 60 s na barreira).
  - Chamada síncrona sem nova tentativa; uma falha técnica vira Unknown com reason SOURCE_UNAVAILABLE.
  - Preço e liquidez em USDC (U7, BR1.10). A U7 considera o preço desatualizado com mais de 5 minutos (BR1.2).
```

### C13 a C15 — Núcleo financeiro (U6, U7, U5)

```yaml
shared-schema: PortfolioRiskView, OrderGate and PaperExecution
owners:
  PortfolioRiskView: u7-ledger-pnl
  OrderGate: u6-risk-control
  PaperExecution: u7-ledger-pnl
language: java
interface: |
  // C13 - U7 para U6
  // Como implementada pela U7 (pacote com.memetrade.pnl.api): recebe o token avaliado; equity é Optional, vazio
  // quando algum preço não é confiável (nunca zero, BR7.4); bot de outra pessoa lança TenantMismatchException
  // (auditada com REQUEST_FORBIDDEN); a perda do dia vem direto dos preenchimentos, nunca do resumo diário.
  public interface PortfolioRiskView {
      RiskSnapshot snapshot(UUID botId, String chain, String tokenMint, LocalDate utcDay);
  }
  public record RiskSnapshot(BigDecimal tokenExposure, BigDecimal totalExposure,
      BigDecimal realizedDayLossWithFees, Optional<BigDecimal> equity, BigDecimal peakEquity,
      BigDecimal availableVirtualBalance, Optional<Instant> lastTradeAt) {}

  // C14 - U6 para U5
  // Como implementada pela U6 (pacote com.memetrade.riskcontrol.api; Approved e Rejected aninhados em GateResult).
  // Mudanças do Functional Design da U6: Set<PauseTrigger> no lugar de Optional<PauseTrigger> (Q8), rejectionId (Q9)
  // e os tipos que a C14 deixou abertos.
  public interface OrderGate {
      GateResult evaluate(CandidateOrder order, BotLimitsVersionRef limits);
  }
  public sealed interface GateResult permits Approved, Rejected {}
  public record Approved(CandidateOrder order) implements GateResult {}
  public record Rejected(UUID rejectionId, List<RejectionReason> reasons, long limitsVersion,
      Set<PauseTrigger> pauseTriggers) implements GateResult {}     // pauseTriggers vazio quando nenhum
  public record CandidateOrder(UUID botId, UUID decisionId, OrderSide side, String chain, String tokenMint,
      BigDecimal requestedQuantity) {}                                // requestedQuantity > 0
  public enum OrderSide { BUY, SELL }
  public record BotLimitsVersionRef(long version, BigDecimal maxOrderSize, BigDecimal maxTokenExposure,
      BigDecimal maxTotalExposure, BigDecimal maxSlippagePct, BigDecimal minLiquidity, long minIntervalSeconds,
      BigDecimal maxDailyLoss, BigDecimal maxDrawdownPct) {}         // valores da versão vigente, entregues pela U5
  public enum RejectionReason { ORDER_SIZE, TOKEN_EXPOSURE, TOTAL_EXPOSURE, SLIPPAGE, MIN_LIQUIDITY, STALE_DATA,
      INTERVAL, DUPLICATE, INSUFFICIENT_PAPER_BALANCE, DAILY_LOSS, DRAWDOWN }   // a ordem é a ordem fixa (BR3.1)
  public enum PauseTrigger { DAILY_LOSS, DRAWDOWN }

  // C15 - U7 para U5
  // Como implementada pela U7 (pacote com.memetrade.ledger.api, tipos aninhados em PaperExecution).
  public interface PaperExecution {
      // Exige a transação do ciclo aberta pela U5 (propagação MANDATORY); grava tudo ou nada.
      FundResult fund(UUID botId, BigDecimal amount, String idempotencyKey);          // U7: uma vez por bot, PAPER_FUND
      ExecutionResult execute(PaperOrderRequest order, String idempotencyKey);
      CloseResult closePositions(UUID botId, long limitsVersion, String idempotencyKey); // U7: PAPER_CLOSE_POSITIONS
  }
  public record PaperOrderRequest(UUID botId, UUID decisionId, OrderSide side, String chain, String tokenMint,
      BigDecimal requestedQuantity, long limitsVersion) {}
  public sealed interface ExecutionResult permits Filled, NotFilled, Duplicate, Conflict {}
  public record Filled(UUID orderId, OrderStatus status, BigDecimal requestedQuantity, BigDecimal filledQuantity,
      FillDetails fill) implements ExecutionResult {}                 // FILLED ou PARTIALLY_FILLED
  public record NotFilled(Optional<UUID> orderId, NotFilledReason reason) implements ExecutionResult {}
  // NotFilledReason: PRICE_UNAVAILABLE, NO_LIQUIDITY, QUANTITY_BELOW_MINIMUM, NO_POSITION, ACCOUNT_CLOSING,
  // INSUFFICIENT_BALANCE, TENANT_MISMATCH (este último sem ordem gravada: orderId vazio)
  public record Duplicate(ExecutionResult original) implements ExecutionResult {}
  public record Conflict(String idempotencyKey) implements ExecutionResult {}
  public sealed interface FundResult permits Funded, Conflict {}       // Conflict: DIFFERENT_CONTENT ou ALREADY_FUNDED
  public sealed interface CloseResult permits Closed, Conflict, Refused {}
  public record Closed(UUID botId, AccountStatus accountStatus, List<ClosedPosition> closed,
      List<PendingPosition> pending, boolean replayed) implements CloseResult {}
rules:
  - Valores monetários em BigDecimal com a escala da tabela "Escalas"; nunca double ou float.
  - A U5 abre a transação do ciclo, trava a linha do bot (SELECT ... FOR UPDATE), confere que ele está ACTIVE e sem motivo de pausa e, dentro da mesma transação, chama DecisionEngine, OrderGate e PaperExecution (ADR-005, AC4.8.2, Q16).
  - A U7 não lê nada da U5; PaperExecution confia na transação aberta pela U5 e recusa ser chamada fora de uma.
  - Uma pausa ou o kill switch gravados pela API esperam a trava do bot; se chegarem antes, o ciclo vê o motivo de pausa e não chama PaperExecution (AC7.2.2).
  - Duplicate devolve o resultado original da mesma chave; Conflict é a mesma chave com conteúdo diferente (AC4.6.3, AC4.6.4).
  - Erros de domínio são resultados explícitos; exceção só para falha técnica, que desfaz a transação inteira do ciclo, sem nova tentativa dentro do ciclo.
  - U7 - NotFilled é ciclo concluído sem operação (o ciclo confirma, não desfaz); a U5 mostra o motivo e não repete a mesma decisão.
  - U7 - fund é chamado uma vez na ativação do bot; closePositions recebe a versão dos limites vigente no encerramento.
  - U7 - closePositions com a conta já em CLOSING ou CLOSED não cria ordem para posição CLOSE_PENDING e devolve o estado atual.
  - U7 - a execução faz SET LOCAL lock_timeout = '2s' e statement_timeout = '5s'; o efeito vale até o fim da transação do ciclo, inclusive para as gravações seguintes da U5 e da trilha, e a U5 registra isso no seu design.
  - U7 - o contexto de autenticação do ciclo (C10) dá tenant e pessoa; a U5 liga o Principal do dono do bot antes de chamar a U7.
  - U6 - OrderGate.evaluate exige a transação do ciclo aberta (propagação MANDATORY); sem ela falha sem gravar nada. Tenant e pessoa vêm só do contexto de autenticação do ciclo (C10); nunca da ordem nem dos limites. A transação do ciclo roda em READ COMMITTED.
  - U6 - Rejected traz rejectionId, os motivos na ordem fixa (o primeiro é o principal) e o conjunto pauseTriggers; a U5 não chama a execução e aplica cada gatilho na mesma transação. A segunda avaliação da mesma decisão volta Rejected([DUPLICATE]) com pauseTriggers vazio.
  - U6 - Toda recusa grava OrderRejection, os detalhes por motivo e um evento ORDER_REJECTED na mesma transação; Approved não gera evento. Falha técnica (C11, C13, banco, tempo de espera) lança exceção e desfaz o ciclo, sem nova tentativa e nunca como aprovação.
```

### C16 e C17 — Interfaces usadas pela administração e pelo painel (U5, U3)

```yaml
shared-schema: BotAdministration and IdentityAdministration
owners:
  BotAdministration: u5-bots-decision
  IdentityAdministration: u3-identity-access
language: java
interface: |
  public interface BotAdministration {
      SystemPauseResult applySystemPause(String correlationId); // idempotente; devolve quantos pausou e quais falharam
      SystemPauseResult liftSystemPause(String correlationId);  // tira o motivo SYSTEM, não retoma bots
      Map<BotState, Long> countByState();
      BotDashboardSummary summaryFor(Principal principal);      // usado pelo painel (U9) e pelo canal C7
  }
  // Como implementada pela U3 (pacote com.memetrade.identity.api):
  public interface IdentityAdministration {
      IssuedInvite issue(UUID tenantId, Instant expiresAt, Principal admin); // transação do chamador (MANDATORY)
      InviteView revoke(UUID inviteId, Principal admin);                     // transação do chamador (MANDATORY)
      InvitePage list(Optional<String> cursor, int limit, Principal admin);
      IdentityCounts counts(Principal admin);
      List<TenantOption> activeTenants(Principal admin);                     // acréscimo da U3 para a U8 (Q1)
  }
  public record IssuedInvite(InviteView invite, String code) {}           // code fora do corpo guardado (NFR6.3)
  public record InviteView(UUID inviteId, UUID tenantId, InviteStatus status, Instant expiresAt,
      Instant createdAt, Instant usedAt, Instant revokedAt, String codeSuffix) {}
  public record InvitePage(List<InviteView> items, String nextCursor) {}
  public record IdentityCounts(long guestCount, Map<InviteStatus, Long> inviteCounts) {} // quatro chaves sempre
  public record TenantOption(UUID tenantId, String name) {}
  public enum InviteStatus { PENDING, USED, EXPIRED, REVOKED }
rules:
  - applySystemPause é repetível: uma falha parcial é repetida até todos os bots ativos terem o motivo SYSTEM (ADR-007).
  - Nenhuma interface expõe ledger, operações ou carteiras à administração (AC7.4.2).
  - IdentityAdministration confere o papel ADMIN do Principal recebido (403 FORBIDDEN_ROLE), além do filtro das rotas administrativas; nenhum controlador do convidado a usa (ArchUnit, U3).
  - A emissão devolve o código só em IssuedInvite.code; quem guarda a resposta da idempotência guarda só o InviteView (U3, BR1.8).
```

**C16 como implementada pela U5** (pacote `com.memetrade.bots.api`):

```java
public interface BotAdministration {
    SystemPauseResult applySystemPause(String correlationId); // grava o estado e confirma antes dos lotes; repetível
    SystemPauseResult liftSystemPause(String correlationId);  // SYSTEM vira ADMIN_RELEASED; nenhum bot é retomado
    Map<BotState, Long> countByState();                        // todos os tenants, sem identificador
    BotDashboardSummary summaryFor(Principal principal);       // só os bots da pessoa
    boolean systemPaused();                                    // cópia da U5 do estado do kill switch
}
record SystemPauseResult(boolean systemPaused, int changed, List<UUID> failed) {}
record BotDashboardSummary(long active, long paused, long activeLimit, Optional<LastDecision> lastDecision) {}
record LastDecision(UUID botId, String action, Optional<String> tokenMint, String summary, Instant createdAt) {}
```

Regras da implementação: cada bot é alterado na sua própria transação com a trava do bot; `failed` lista os bots cuja transação falhou, e a U8 repete até a lista ficar vazia. Os eventos `BOT_PAUSED` (motivo `SYSTEM`) e `BOT_SYSTEM_PAUSE_LIFTED` saem por bot com ator `system`; o evento do administrador (acionamento e desligamento) é da U8 (AC7.2.5).

**Interfaces acrescentadas pela geração de código da U8** (todas devolvem só números, nomes de tenant ou a carteira mascarada; nenhuma devolve pessoa, bot, ordem ou lançamento):

```java
// U6, pacote com.memetrade.riskcontrol.api (BR3.2 da U8)
public interface RejectionCounts {
    long countRejectedSince(Instant from);   // ordens recusadas de todos os tenants desde o instante
}
// U2, pacote com.memetrade.audit.api (BR3.1 da U8)
public interface AuditBacklog {
    long pending();                          // eventos ainda na tabela de saída
}
// U3 (C17), acréscimos a IdentityAdministration, com a mesma conferência do papel ADMIN
Map<UUID, String> tenantNames(Collection<UUID> tenantIds, Principal admin);           // lista de feedback (BR6.6)
Optional<String> maskedAdministratorWallet(UUID adminUserId, Principal admin);        // "quem" do kill switch (BR2.7)
```

Na U8, só a classe `KillSwitchApplier` chama `applySystemPause` e `liftSystemPause` (ArchUnit). As leituras `countByState()` e `systemPaused()` são usadas pelo estado do sistema, pela consulta do kill switch e pela reconciliação.

### C18 — Marcas de última alteração (U4, U5, U7)

```yaml
shared-schema: ChangeMarks
owners:
  BotChangeMarks: u5-bots-decision
  LedgerChangeMarks: u7-ledger-pnl
  SourceStatusMarks: u4-discovery
language: java
interface: |
  public interface ChangeMarks {
      // Número que só cresce para os dados daquela pessoa; muda a cada gravação relevante, em qualquer processo.
      long markFor(Principal principal);
  }
  public interface BotChangeMarks extends ChangeMarks {}     // estados e motivos de pausa dos bots da pessoa
  public interface LedgerChangeMarks extends ChangeMarks {}  // operações e lançamentos da pessoa
  public interface SourceStatusMarks { long globalMark(); } // estado das fontes, igual para todos
rules:
  - Cada dono mantém a própria marca na própria tabela, atualizada na mesma transação da gravação.
  - O canal C7 (U5) compara as marcas a cada 2 s e só busca detalhes quando uma muda; nenhum módulo lê a tabela de marcas de outro.
  - As marcas substituem os eventos em processo do painel da primeira versão, porque funcionam também para gravações feitas no worker (Q19).
```

**`BotChangeMarks` como implementada pela U5** (pacote `com.memetrade.bots.api`, `long markFor(Principal principal)`): `bot.change_seq` recebe o próximo valor da sequência global `bot_change_seq` na mesma gravação de toda mudança de estado, motivo de pausa ou limites (BR8.1); a marca é o máximo de `change_seq` dos bots da pessoa, por índice (tenant, pessoa, `change_seq` decrescente), sem travar linha nenhuma. Ressalva registrada: duas transações da mesma pessoa que confirmam fora da ordem da sequência podem fazer o leitor ver primeiro o valor maior; o efeito é só atraso de tela, corrigido pela próxima mudança ou pela leitura completa que o canal C7 refaz a cada 60 s, junto do `heartbeat`.

**Registrado pela geração de código da U8.** A ressalva da ordem das marcas, vinda da U5, continua valendo. A pausa e a retirada da pausa de sistema pelo kill switch mudam o motivo de cada bot na própria transação dele, e por isso também renovam a marca da pessoa, com a mesma ressalva: o painel de um convidado pode mostrar a pausa SYSTEM com atraso de até 60 s. A U8 não lê marcas: o estado do sistema e o kill switch leem as interfaces de C16 e o próprio `kill_switch_state`.

### C19 — Porta de adaptador de mercado (U4)

```yaml
shared-schema: MarketSourceAdapter
owner: u4-discovery
language: java
interface: |
  public interface MarketSourceAdapter {
      SourceRef source();
      FetchResult fetch(FetchRequest request); // tokens, pools e observações normalizados
  }
  public record Observation(TokenId token, String field, MarketValue value) {}
rules:
  - Implementação simulada identificada com simulated = true no ambiente local (FR4.1); fixtures gravadas nos testes.
  - Tempo limite curto (proposta: 5 s) e até 2 novas tentativas com espera crescente e variação aleatória; depois, a fonte fica FAILED até a próxima coleta (Q12). Valores finais no NFR Design.
  - Campo não informado pela fonte vira Unknown(NOT_PROVIDED); nunca zero.
  - Adaptadores reais só depois da aprovação das fontes no G0 (SP-1, C-06).
```

### C20 — Processos API e worker (U5, com a tabela de saída da U2)

```yaml
shared-schema: bot-runtime-tables
owner: u5-bots-decision
description: >
  Os processos de API e worker executam o mesmo código e compartilham as tabelas de cada módulo, que cada
  dono acessa só pelo próprio código: as do módulo de bots (bot, bot_limits_version, bot_cycle), as da
  execução PAPER (U7), as das recusas (U6) e a tabela de saída da auditoria (U2, C9). Comandos da pessoa e da
  administração gravam o estado pela API; o worker trava e lê o estado na transação do ciclo (C15).
rules:
  - Nenhum módulo lê as tabelas de outro, em nenhum dos dois processos.
  - Um ciclo por bot por vez, garantido pela trava da linha do bot; reinício não repete ciclo interrompido (AC4.5.4).
  - O relay da auditoria e o canal C7 rodam só no processo de API; o agendador do ciclo, só no worker.
  - Migrações seguem expand-contract, porque API e worker podem rodar versões vizinhas durante uma implantação.
```

## Regras de propriedade dos contratos

- **Dono:** a Unit provedora é dona do contrato (coluna Owner). C1 começa em U1 e é estendido por U3 sem mudar as operações existentes. C2 começa em U1 e é completado por U4. C9 tem a interface e a tabela de saída finais desde U1 (Q10, Q18).
- **Sentido das dependências:** um provedor nunca chama de volta um consumidor; quando um provedor precisa de algo do consumidor, o consumidor o entrega na chamada ou na transação (Q16).
- **Fonte da verdade:** para REST, o OpenAPI gerado do código e versionado no repositório (Q2); a CI falha quando o gerado difere do versionado sem os tipos dos dois apps regenerados. Para interfaces de módulo, o pacote público de cada módulo.
- **Mudança aditiva:** campo, operação ou valor de enum novo é livre; consumidores ignoram campos desconhecidos. Valor de enum novo num campo de saída exige que o app trate o valor desconhecido sem quebrar.
- **Mudança que quebra:** permitida no mesmo commit que atualiza os dois apps e todos os módulos consumidores, com a CI verde (Q4). `/api/v1` só ganha nova versão quando houver consumidor externo.
- **Sem leitura cruzada:** nenhum módulo lê tabela, repositório ou entidade interna de outro; só a interface pública (`team.md`).
- **Mudança de contrato depois da Construction começar:** registrada no Functional Design da Unit provedora e avisada às consumidoras pelo mapa de arestas.
- **Contrato e código juntos (revisão de 2026-11-09, Q23):** uma mudança de contrato feita na geração de código atualiza o bloco YAML deste `contract-summary.md` no mesmo commit que muda o OpenAPI gerado, e a revisão da Unit confere os dois. O OpenAPI gerado continua sendo a fonte da verdade; nota solta fora do bloco não substitui a atualização.

## Open questions

| Contract | Question | Blocks |
|---|---|---|
| OQ-7 (C19) | Mapeamento dos campos das fontes reais para `Observation`, depois da aprovação no G0 (SP-1). | Adaptadores reais de U4 |

Resolvidas (revisão de 2026-11-09, Q24):

| Contract | Resposta | Onde foi decidida |
|---|---|---|
| OQ-3 (escalas) | Saldo virtual em USDC; valores com 6 casas, preço unitário com 12 casas, arredondamento `HALF_EVEN` | Functional Design da U5 (Q1) |
| OQ-4 (C1, C8) | Token de acesso de 15 min, renovação válida por 8 h, sessão absoluta de 12 h; renovação troca o token e reuso revoga a família | Functional Design da U1, mantido no NFR Requirements |
| OQ-6 (regras comuns) | Prazo por rota: o desafio guarda a resposta só até expirar (5 min); as demais rotas, 24 h, com limpeza agendada | NFR Design da U1 (resposta B) |
| OQ-8 (C1) | O smoke entra com uma conta `BETA_USER` semeada só no perfil de teste, com carteira de teste gerada no código de teste | correção registrada em `project.md` |
| OQ-9 (C7, C9) | Relay da auditoria em lotes de 100 a cada 1 s; marcas do painel lidas a cada 2 s; atraso da auditoria ≥ 60 s gera alerta | NFR Design da U2 e da U5; `operation/observability-setup/alarms.md` |
