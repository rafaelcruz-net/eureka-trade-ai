# Contratos — Termos de uso e LGPD

Conversa: português (Brasil). Fontes:
- `inception/units-generation/unit-of-work.md` e `unit-of-work-dependency.md`;
- `inception/domain-design/components.md`;
- `inception/requirements-analysis/requirements.md`;
- as respostas de `contract-design-questions.md` (Q1 a Q5).

Os contratos aqui são aditivos aos do produto: os existentes, como o C1 de sessão e o C3 de conta, continuam valendo.

## Regras comuns (herdadas e confirmadas)

- REST sob `/api/v1/`. Erros em `application/problem+json` com `type`, `title`, `status`, `code` estável e `correlationId`. Nunca stack trace, SQL, carteira completa, texto de pedido, mensagem ou assinatura de verificação.
- Todo `POST` exige `Idempotency-Key`; repetir a mesma chave com o mesmo corpo devolve a mesma resposta (Q5).
- O tenant e a pessoa vêm sempre do token, nunca do corpo.
- Datas em UTC (ISO-8601); o app converte para o fuso da pessoa.
- Clientes ignoram campos desconhecidos. Os tipos dos apps são gerados do OpenAPI.
- Sem repetição automática no cliente, exceto em 5xx de operação idempotente.
- Referências como `common#/...` e `P1#/...` entre os blocos deste documento são notação de leitura; o OpenAPI gerado do código prevalece.
- Toda rota administrativa pode responder `403 FORBIDDEN_ROLE` sem o papel `ADMIN`. Toda rota com `{requestId}` responde `404 NOT_FOUND` para pedido inexistente ou de outro tenant ou pessoa, sem revelar qual.

## Códigos de erro novos

Acréscimo ao `ErrorCode` existente, na convenção do produto: `400` para entrada malformada, `422` para valor bem formado que quebra regra e `409` para conflito de estado.

| Código | Status | Categoria | Onde |
|---|---|---|---|
| `TERMS_REACCEPTANCE_REQUIRED` | 403 | domínio | L3 |
| `TERMS_VERSION_OUTDATED` | 409 | domínio | L2 |
| `DATA_REQUEST_ALREADY_OPEN` | 409 | domínio | P1 (aberto = mesmo tipo e mesma pessoa em `RECEIVED`, `AWAITING_VERIFICATION` ou `IN_PROGRESS`) |
| `DATA_REQUEST_CLOSED` | 409 | domínio | P2 (pedido `DONE` ou `REFUSED`) |
| `INVALID_TRANSITION` | 409 | domínio | P2. Código existente, reusado; o título passa a ser genérico ("Invalid state transition"), e o `detail` diz qual transição |
| `IDENTITY_NOT_VERIFIED` | 409 | domínio | P2; também na anonimização (U5, AC6.1.2): origem EMAIL sem identidade confirmada |
| `VERIFICATION_EXPIRED` | 409 | domínio | P2 |
| `VERIFICATION_ALREADY_USED` | 409 | domínio | P2 |
| `VERIFICATION_INVALID` | 422 | domínio | P2 |
| `UNPROCESSABLE_FIELDS` | 422 | domínio (existente) | P2: recusa sem motivo |
| `CONFIRMATION_REQUIRED` | 422 | domínio | P2 anonimização (U5): confirmação diferente de `ANONIMIZAR` |
| `REQUEST_NOT_ELIGIBLE` | 409 | domínio | P2 anonimização (U5): o pedido não é Exclusão `IN_PROGRESS` |
| `NO_LINKED_ACCOUNT` | 409 | domínio | P2 anonimização (U5): pedido sem conta ligada |
| `ADMIN_ACCOUNT_NOT_ANONYMIZABLE` | 409 | domínio | P2 anonimização (U5): a conta ligada é `ADMIN` |
| `LOCK_TIMEOUT` | 503 | técnico | P2 anonimização (U5): trava não obtida em 2 s; nada mudou; `Retry-After`; repetir com a mesma chave |
| `VALIDATION_FAILED` | 400 | entrada (existente) | P1, P2: tipo inválido, texto acima do limite, e-mail malformado |

## Tabela de contratos

| # | Provider Unit | Consumer | Mechanism | Owner |
|---|---|---|---|---|
| L1 | U1 `u1-legal-documents` (Identity) | External: public web (app do convidado e do administrador, sem login) | REST | Identity |
| L2 | U3 `u3-reacceptance` (Identity) | GuestWebApp (U3) | REST | Identity |
| L3 | U3 `u3-reacceptance` (Identity) | todas as rotas do convidado (filtro) | REST (resposta de erro comum) | Identity |
| P1 | U4 `u4-data-requests` (Privacy) | GuestWebApp (U4) | REST | Privacy |
| P2 | U4 e U5 (Privacy) | AdminWebApp (U4, U5) | REST | Privacy |
| J1 | U2, U4, U5 (Identity) | Privacy (U4, U5) | Java public interface | Identity |
| J2 | U5 (Bots) | Privacy (U4, U5) | Java public interface | Bots |
| J3 | U5 (Feedback) | Privacy (U4, U5) | Java public interface | Feedback |
| J4 | U4 (Ledger) | Privacy (U4) | Java public interface | Ledger |
| J5 | U1 (Identity, define a porta) e U2 (Privacy, implementa) | Identity (tabela da política) | Java public interface (porta de entrada, inversão de dependência) | Identity |
| J6 | U2 (Audit) | Identity, Privacy | Java public interface (existente, ampliada) | Audit |
| J7 | U2 (pacote compartilhado; implementada por Audit, Feedback, Bots, RiskControl e Identity) | Privacy (catálogo e teste de conformidade) | Java public interface (declaração, sem lógica) | Privacy |

## L1 — Documentos públicos (Identity)

```yaml
openapi: 3.1.0
info: { title: MemeTrade - legal documents, version: 1.0.0 }
paths:
  /api/v1/legal/current:
    get:
      operationId: getCurrentLegalDocuments
      security: []
      summary: Versão vigente dos termos, da política e do aviso de risco (FR1.1, FR2.1)
      responses:
        '200':
          headers: { ETag: { schema: { type: string } } }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/LegalDocuments' }
        '304': { description: não modificado (If-None-Match igual ao ETag), sem corpo }
        '429': { description: RATE_LIMITED com Retry-After (60 por minuto por IP) }
  /api/v1/legal/versions:
    get:
      operationId: listLegalVersions
      security: []
      summary: Histórico de versões publicadas (FR8.1, Could)
      responses:
        '200':
          headers: { ETag: { schema: { type: string } } }
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/LegalVersionRef' } }
        '304': { description: não modificado, sem corpo }
        '429': { description: RATE_LIMITED com Retry-After }
  /api/v1/legal/versions/{version}:
    get:
      operationId: getLegalVersion
      security: []
      parameters: [ { name: version, in: path, required: true, schema: { type: string } } ]
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/LegalDocuments' } } } }
        '304': { description: não modificado, sem corpo }
        '404': { description: NOT_FOUND (versão fora do histórico, inclusive futura ou ignorada na ativação) }
        '429': { description: RATE_LIMITED com Retry-After }
components:
  schemas:
    LegalVersionRef:
      type: object
      required: [version, effectiveAt, provisional]
      properties:
        version: { type: string }
        effectiveAt: { type: string, format: date-time }
        provisional: { type: boolean, description: versão legacy, mostrada como provisória (BR1.9) }
    LegalDocuments:
      type: object
      required: [version, effectiveAt, provisional, publishable, terms, privacy, riskNotice, deployment, retention]
      properties:
        version: { type: string }
        effectiveAt: { type: string, format: date-time }
        provisional: { type: boolean, description: versão legacy, mostrada como provisória }
        publishable: { type: boolean, description: falso quando falta um campo de implantação (FR1.3, BR1.6) }
        terms: { $ref: '#/components/schemas/LegalText' }
        privacy:
          description: nulo quando a versão é legacy e não tem política (BR1.15)
          oneOf: [ { $ref: '#/components/schemas/LegalText' }, { type: 'null' } ]
        riskNotice: { $ref: '#/components/schemas/LegalText' }
        deployment:
          type: object
          required: [storageLocation, internationalTransfer, dpoEmail]
          properties:
            storageLocation: { type: [string, 'null'], description: null = "a definir" }
            internationalTransfer: { type: [string, 'null'] }
            dpoEmail: { type: [string, 'null'] }
        retention:
          type: array
          description: Tabela de retenção (FR3.4); vem da porta J5 (implementada pelo Privacy a partir de U2; antes, texto do catálogo de termos)
          items: { $ref: '#/components/schemas/RetentionItem' }
    LegalText:
      type: object
      required: [summary, sections]
      properties:
        summary: { type: [array, 'null'], items: { type: string }, minItems: 5, maxItems: 7, description: nulo na versão legacy e no aviso de risco }
        sections:
          type: array
          items:
            type: object
            required: [id, title, paragraphs]
            properties:
              id: { type: string, description: âncora do índice }
              title: { type: string }
              paragraphs: { type: array, items: { type: string }, description: texto simples, nunca HTML }
    RetentionItem:
      type: object
      required: [dataItem, purpose, period, endAction]
      properties:
        dataItem: { type: string }
        purpose: { type: string }
        period: { type: string }
        endAction: { type: string }
```

**Comportamento** (atualizado no Code Generation da U1, junto do OpenAPI gerado em `apps/api/openapi/`):
- A versão vigente efetiva é a marcada como vigente, se for ativável (revisada ou legacy, sem vigência futura e publicável quando a exigência de publicação está ligada); senão, a ativável de vigência mais recente anterior a ela, e um aviso vai para o log (FR1.4, BR1.1).
- Sem nenhuma versão ativável, a API não sobe; por isso L1 não tem resposta 503.
- `privacy` e os resumos (`summary`) são nulos quando a versão é legacy (BR1.15); os campos ausentes vão como `null` explícito, nunca omitidos.
- `publishable: false` aparece quando falta um campo de implantação; a página mostra "a definir".
- Respostas 200 e 304 levam `Cache-Control: public, max-age=300` e `ETag` forte, o hash SHA-256 do corpo; 404, 429 e erros mantêm `no-store`.
- As rotas também entram no grupo do OpenAPI administrativo (`openapi-admin.yaml`), porque o app do administrador as lê.

## L2 — Aceite e estado na Conta (Identity)

```yaml
openapi: 3.1.0
info: { title: MemeTrade guest API - terms acceptance, version: 1.0.0 }
security: [ { accessToken: [] } ]
paths:
  /api/v1/terms/acceptances:
    post:
      operationId: acceptTerms
      summary: Aceita a versão vigente (FR4.2)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AcceptTermsRequest'
      responses:
        '200':
          description: Aceite registrado, ou já existente para a versão (sem nova gravação); a mesma chave devolve a mesma resposta
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TermsStatus' }
        '400': { description: VALIDATION_FAILED (versão ausente, vazia ou com mais de 32 caracteres, campo desconhecido ou Idempotency-Key ausente) }
        '401': { description: UNAUTHENTICATED }
        '403': { description: FORBIDDEN_ROLE para token de administrador }
        '409': { description: TERMS_VERSION_OUTDATED (corpo traz a versão vigente em `currentVersion`) ou IDEMPOTENCY_CONFLICT (mesma chave com outra versão) }
components:
  schemas:
    AcceptTermsRequest:
      type: object
      additionalProperties: false
      required: [version]
      properties:
        version: { type: string, maxLength: 32 }
    TermsStatus:
      type: object
      additionalProperties: false
      required: [acceptedVersion, currentVersion, acceptedAt, upToDate]
      properties:
        acceptedVersion: { type: string, description: versão do aceite mais recente }
        currentVersion: { type: string, description: versão vigente efetiva }
        acceptedAt: { type: string, format: date-time, description: instante do aceite mais recente (UTC) }
        upToDate: { type: boolean, description: verdadeiro quando há um aceite da versão vigente (a mesma decisão da trava L3) }
    Problem:
      description: o problema comum ganha o campo opcional `currentVersion`, só presente em TERMS_REACCEPTANCE_REQUIRED e TERMS_VERSION_OUTDATED
      properties:
        currentVersion: { type: string }
```

A resposta de `GET /api/v1/account`, já existente, ganha o campo aditivo `terms: TermsStatus`. A sessão (`GuestSession.termsVersion`) continua igual.

**Comportamento** (atualizado no Code Generation da U3, junto do OpenAPI gerado em `apps/api/openapi/`):
- O `terms` da Conta fica ausente só numa conta sem nenhum aceite (hoje, apenas contas semeadas por teste); o app trata a ausência como "não está em dia".
- `upToDate` usa a mesma decisão da trava: existe um aceite da versão vigente efetiva. Se a vigente voltar a uma versão já aceita, a pessoa segue em dia, mesmo que o aceite mais recente seja de outra versão.
- O `409 TERMS_VERSION_OUTDATED` não grava nada nem consome a `Idempotency-Key`; só a resposta `200` fica guardada.
- O `currentVersion` do problema também aparece no `openapi-admin.yaml`, porque o esquema `Problem` é comum aos dois grupos.

## L3 — Trava de novo aceite (Identity)

- Todas as rotas do convidado, depois da autenticação, podem responder `403` com o código `TERMS_REACCEPTANCE_REQUIRED` e `currentVersion` no corpo.
- **Rotas liberadas** (prefixos configuráveis):
  - `/api/v1/legal/`, `/api/v1/terms/acceptances` e `/api/v1/account`;
  - `/api/v1/auth/logout` e `/api/v1/auth/refresh`;
  - o prefixo de pedidos `/api/v1/privacy/`, registrado por U4.
- O worker não passa pelo filtro.
- O filtro lê a última versão aceita na conta (banco), não no token. Assim, depois de `acceptTerms`, o mesmo token já passa, sem precisar renovar a sessão.
- As rotas L1 (`/api/v1/legal/`) são públicas e aceitam no CORS as origens dos dois apps (convidado e administrador), só com `GET` e sem credenciais.

## P1 — Pedidos de titular do convidado (Privacy)

```yaml
openapi: 3.1.0
info: { title: MemeTrade guest API - data subject requests, version: 1.0.0 }
security: [ { accessToken: [] } ]
paths:
  /api/v1/privacy/requests:
    get:
      operationId: listMyDataRequests
      responses:
        '200': { content: { application/json: { schema: { type: array, items: { $ref: '#/components/schemas/DataRequestView' } } } } }
    post:
      operationId: createDataRequest
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [type, details]
              properties:
                type: { $ref: '#/components/schemas/DataRequestType' }
                details: { type: string, maxLength: 1000 }
      responses:
        '201': { content: { application/json: { schema: { $ref: '#/components/schemas/DataRequestView' } } } }
        '400': { description: VALIDATION_FAILED }
        '409': { description: DATA_REQUEST_ALREADY_OPEN | IDEMPOTENCY_CONFLICT }
        '429': { description: "RATE_LIMITED (U4, aditivo): 10 pedidos da pessoa na última hora; Retry-After em segundos; o 429 não consome a chave" }
  /api/v1/privacy/requests/{requestId}:
    get:
      operationId: getMyDataRequest
      parameters: [ { name: requestId, in: path, required: true, schema: { type: string, format: uuid } } ]
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/DataRequestView' } } } }
        '404': { description: NOT_FOUND (inclusive pedido de outra pessoa ou tenant) }
components:
  schemas:
    DataRequestType: { type: string, enum: [ACCESS, CORRECTION, DELETION, OTHER] }
    DataRequestStatus: { type: string, enum: [RECEIVED, AWAITING_VERIFICATION, IN_PROGRESS, DONE, REFUSED] }
    DataRequestView:
      type: object
      required: [requestId, type, status, receivedAt, dueAt]
      properties:
        requestId: { type: string, format: uuid }
        type: { $ref: '#/components/schemas/DataRequestType' }
        status: { $ref: '#/components/schemas/DataRequestStatus' }
        receivedAt: { type: string, format: date-time }
        dueAt: { type: string, format: date-time }
        response: { type: [string, 'null'] }
        refusalReason: { type: [string, 'null'] }
        accessSummary: { oneOf: [ { $ref: '#/components/schemas/AccessSummary' }, { type: 'null' } ] }
    AccessSummary:
      type: object
      required: [walletMasked, provider, accountCreatedAt, acceptedVersions, botCount, paperOrderCount, feedbackCount, requests]
      properties:
        walletMasked: { type: string }
        provider: { type: string }
        accountCreatedAt: { type: string, format: date-time }
        lastLoginAt: { type: [string, 'null'], format: date-time }
        acceptedVersions:
          type: array
          items: { type: object, required: [version, acceptedAt], properties: { version: { type: string }, acceptedAt: { type: string, format: date-time } } }
        botCount: { type: integer }
        paperOrderCount: { type: integer }
        feedbackCount: { type: integer }
        requests:
          type: array
          items: { type: object, required: [type, status, receivedAt], properties: { type: { $ref: '#/components/schemas/DataRequestType' }, status: { $ref: '#/components/schemas/DataRequestStatus' }, receivedAt: { type: string, format: date-time } } }
```

Os campos `response`, `refusalReason` e `accessSummary` só aparecem para a pessoa depois que o pedido é concluído ou recusado; antes disso vêm como `null` (U4: os campos são sempre enviados, com `null`). O prazo `dueAt` é `receivedAt` mais 15 dias corridos. A lista vem do pedido mais novo para o mais antigo. Um `requestId` malformado responde o mesmo `404` de um pedido inexistente. No OpenAPI gerado, os nomes dos esquemas da linha do resumo são `AcceptedVersion` e `AccessSummaryRequest`, e o corpo do `POST` é `CreateDataRequest`.

## P2 — Pedidos de titular do administrador (Privacy)

```yaml
openapi: 3.1.0
info: { title: MemeTrade admin API - data subject requests, version: 1.0.0 }
security: [ { adminAccessToken: [] } ]
paths:
  /api/v1/admin/privacy/requests:
    get:
      operationId: adminListDataRequests
      parameters:
        - { name: status, in: query, schema: { $ref: 'P1#/components/schemas/DataRequestStatus' } }
        - { name: cursor, in: query, schema: { type: string }, description: "U4: cursor opaco devolvido em nextCursor" }
        - { name: limit, in: query, schema: { type: integer, minimum: 1, maximum: 100, default: 50 }, description: "U4" }
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/AdminDataRequestPage' } } } }
        '400': { description: "VALIDATION_FAILED em status, limit ou cursor (U4)" }
        '403': { description: FORBIDDEN_ROLE }
    post:
      operationId: adminRegisterEmailRequest
      summary: Registra pedido recebido pelo e-mail do encarregado (FR5.2c)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [type, details, contact, receivedAt]
              properties:
                type: { $ref: 'P1#/components/schemas/DataRequestType' }
                details: { type: string, maxLength: 1000 }
                contact: { type: string, format: email }
                receivedAt: { type: string, format: date-time }
                userId: { type: [string, 'null'], format: uuid }
      responses:
        '201': { content: { application/json: { schema: { $ref: '#/components/schemas/AdminDataRequest' } } } }
        '400': { description: "VALIDATION_FAILED (receivedAt no futuro; userId de conta inexistente; o erro nomeia o campo, nunca o valor)" }
        '409': { description: "DATA_REQUEST_ALREADY_OPEN (U4, aditivo: a conta ligada já tem pedido aberto do mesmo tipo, de qualquer origem) | IDEMPOTENCY_CONFLICT" }
  /api/v1/admin/privacy/requests/{requestId}:
    get:
      operationId: adminGetDataRequest
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/AdminDataRequest' } } } }
        '404': { description: NOT_FOUND }
  /api/v1/admin/privacy/requests/{requestId}/transitions:
    post:
      operationId: adminTransitionDataRequest
      summary: "U4: RECEIVED para IN_PROGRESS é recusado (409 INVALID_TRANSITION) num pedido EMAIL de ACCESS ou DELETION ligado a conta ativa; ele passa antes pela verificação de identidade"
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [to]
              properties:
                to: { type: string, enum: [IN_PROGRESS, DONE, REFUSED] }
                response: { type: [string, 'null'], maxLength: 4000 }
                refusalReason: { type: [string, 'null'], maxLength: 500 }
                releaseAccessSummary: { type: boolean, default: false }
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/AdminDataRequest' } } } }
        '400': { description: VALIDATION_FAILED (destino fora do enum; U4: resposta acima de 4000 ou motivo acima de 500 caracteres) }
        '422': { description: UNPROCESSABLE_FIELDS (recusa sem motivo; U4: releaseAccessSummary fora de um Acesso ou sem resumo da conta) }
        '409': { description: INVALID_TRANSITION (inclui AWAITING_VERIFICATION → IN_PROGRESS, que só a confirmação faz, e verificação de identidade primeiro) | DATA_REQUEST_CLOSED | IDENTITY_NOT_VERIFIED }
  /api/v1/admin/privacy/requests/{requestId}/verification:
    post:
      operationId: adminStartVerification
      summary: Gera a mensagem de verificação e leva o pedido a AWAITING_VERIFICATION (só origem EMAIL com conta)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/VerificationView' } } } }
        '409': { description: INVALID_TRANSITION (origem Conta, sem conta ligada, conta anonimizada, tipo sem verificação ou pedido fora de RECEIVED) | DATA_REQUEST_CLOSED }
  /api/v1/admin/privacy/requests/{requestId}/verification/confirm:
    post:
      operationId: adminConfirmVerification
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema: { type: object, required: [signature], properties: { signature: { type: string } } }
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/AdminDataRequest' } } } }
        '400': { description: "VALIDATION_FAILED (U4: assinatura ausente)" }
        '409': { description: VERIFICATION_EXPIRED | VERIFICATION_ALREADY_USED | DATA_REQUEST_CLOSED (U4, antes de qualquer erro de verificação) | INVALID_TRANSITION (U4, sem verificação iniciada) }
        '422': { description: VERIFICATION_INVALID (o pedido continua aguardando) }
  /api/v1/admin/privacy/requests/{requestId}/anonymization:
    post:
      operationId: adminAnonymizeAccount
      summary: Anonimiza a conta do pedido de Exclusão em andamento (FR6); idempotente
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      requestBody:
        required: true
        content:
          application/json:
            schema: { type: object, required: [confirmation], properties: { confirmation: { type: string, description: "exatamente ANONIMIZAR" } } }
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/AnonymizationResult' } } } }
        '404': { description: NOT_FOUND }
        '422': { description: "CONFIRMATION_REQUIRED (U5; confirmação diferente de ANONIMIZAR, conferida antes de ler o pedido)" }
        '409': { description: "U5, nesta ordem: REQUEST_NOT_ELIGIBLE (não é Exclusão IN_PROGRESS) | NO_LINKED_ACCOUNT | ADMIN_ACCOUNT_NOT_ANONYMIZABLE | IDENTITY_NOT_VERIFIED (origem EMAIL) | IDEMPOTENCY_CONFLICT" }
        '503': { description: "LOCK_TIMEOUT com Retry-After (U5): nada mudou; repetir com a mesma Idempotency-Key" }
  /api/v1/admin/privacy/requests/{requestId}/reply-sent:
    post:
      operationId: adminMarkReplySent
      summary: Registra a data do envio da resposta ao contato de um pedido por e-mail (só origem EMAIL, estado DONE ou REFUSED)
      parameters: [ { $ref: 'common#/components/parameters/IdempotencyKey' } ]
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/AdminDataRequest' } } } }
        '409': { description: INVALID_TRANSITION (origem ACCOUNT ou pedido ainda aberto) }
components:
  schemas:
    AdminDataRequest:
      allOf:
        - $ref: 'P1#/components/schemas/DataRequestView'
        - type: object
          required: [origin, details]
          properties:
            origin: { type: string, enum: [ACCOUNT, EMAIL] }
            details: { type: string }
            userId: { type: [string, 'null'], format: uuid }
            contact: { type: [string, 'null'] }
            identityVerified: { type: boolean }
            verification: { oneOf: [ { $ref: '#/components/schemas/VerificationView' }, { type: 'null' } ] }
            accessSummaryPreview: { oneOf: [ { $ref: 'P1#/components/schemas/AccessSummary' }, { type: 'null' } ] }
            replySentAt: { type: [string, 'null'], format: date-time }
            overdue: { type: boolean }
            dueSoon: { type: boolean, description: "U4, aditivo: aberto, ainda no prazo e a 3 dias ou menos dele (BR4.16)" }
            accountAnonymized: { type: boolean, description: "U5, aditivo: a conta ligada foi anonimizada; a tela mostra 'Conta anonimizada', nunca a carteira" }
    AdminDataRequestPage:
      description: "U4: a lista é paginada por cursor, por prazo (due_at) e id; verification e accessSummaryPreview vêm null na lista"
      type: object
      required: [items]
      properties:
        items: { type: array, items: { $ref: '#/components/schemas/AdminDataRequest' } }
        nextCursor: { type: string, description: ausente na última página }
    AnonymizationResult:
      description: "U5 (entities.md): só identificadores internos, instante e contagens; a repetição numa conta já ANONYMIZED devolve o mesmo pedido, pessoa e instante, com alreadyAnonymized true e contagens 0"
      type: object
      required: [requestId, userId, anonymizedAt, sessionsRevoked, botsStopped, feedbackDeleted, alreadyAnonymized]
      properties:
        requestId: { type: string, format: uuid }
        userId: { type: string, format: uuid }
        anonymizedAt: { type: string, format: date-time }
        sessionsRevoked: { type: integer, minimum: 0 }
        botsStopped: { type: integer, minimum: 0 }
        feedbackDeleted: { type: integer, minimum: 0 }
        alreadyAnonymized: { type: boolean }
    VerificationView:
      type: object
      required: [message, expiresAt, state]
      properties:
        message: { type: string, description: domínio, pedido, finalidade e validade; só para este pedido }
        expiresAt: { type: string, format: date-time }
        state: { type: string, enum: [PENDING, CONFIRMED, EXPIRED] }
```

**Comportamento:**
- **Transições permitidas:**
  - `RECEIVED` → `IN_PROGRESS`;
  - `RECEIVED` → `AWAITING_VERIFICATION` (pela rota de verificação, só com origem e-mail);
  - `AWAITING_VERIFICATION` → `IN_PROGRESS` (pela confirmação) ou → `REFUSED`;
  - `IN_PROGRESS` → `DONE` ou → `REFUSED`, com `refusalReason` obrigatório.
- Um pedido `DONE` ou `REFUSED` responde `DATA_REQUEST_CLOSED`.
- Uma verificação sem assinatura válida em 24 h leva o pedido a `REFUSED`, com o motivo "identidade não confirmada" (tarefa agendada).
- `releaseAccessSummary` e a anonimização exigem `identityVerified` em pedidos com origem `EMAIL`.
- `adminAnonymizeAccount` (U5), numa só transação:
  1. confere `ANONIMIZAR` antes de qualquer leitura, reivindica a chave (`DATA_REQUEST_ANONYMIZE`), aplica o `lock_timeout` de 2 s e trava o pedido;
  2. faz a anonimização (J2 → J3 → J1);
  3. publica `ACCOUNT_ANONYMIZED`, leva o pedido de Exclusão de `IN_PROGRESS` para `DONE` com a resposta modelo de exclusão e publica `DATA_REQUEST_TRANSITIONED`, nessa ordem.

  Repetir numa conta já anonimizada (com a mesma chave ou com outra) devolve o mesmo pedido, pessoa e instante com `alreadyAnonymized` verdadeiro, sem escrita nem evento; o pedido fica como está. Recusas e falhas não consomem a chave.
- A mensagem de verificação é rejeitada como login, e a assinatura de login é rejeitada como verificação.

## J1 a J6 — Interfaces Java públicas (shared schema)

```yaml
shared-schema:
  J1-Identity:
    owner: Identity
    interface: com.memetrade.identity.api.PersonDataPort
    constants:
      - "String VERIFICATION_FIRST_LINE = \"MemeTrade - verificação de pedido de titular\" (U4)"
    methods:
      - signature: "Optional<PersonSummary> personSummary(UUID tenantId, UUID userId)"
        returns: "walletMasked, provider, accountCreatedAt, lastLoginAt (Optional), acceptedVersions[] (version, acceptedAt), da mais antiga para a mais nova"
        note: "U4; uso pelo Privacy (prévia e foto do resumo de Acesso); vazio para pessoa inexistente no tenant ou sem carteira. A forma com Principal não foi criada: o Privacy sempre chama com o tenant e a pessoa do pedido"
      - signature: "Optional<AccountRef> findAccount(UUID userId)"
        returns: "AccountRef(userId, tenantId, state ACTIVE | ANONYMIZED); ANONYMIZED quando a conta não tem mais carteira ou tem o estado ANONYMIZED"
        note: "U4, aditivo; só uso administrativo do Privacy (registro de pedido por e-mail, BR4.10)"
      - signature: "boolean verifyAccountSignature(UUID userId, String message, String signature)"
        note: "U4; Ed25519 nativo; falso quando a mensagem não começa pela linha VERIFICATION_FIRST_LINE seguida de quebra de linha (inclusive uma mensagem de login), para conta ANONYMIZED ou inexistente, assinatura malformada ou de outra carteira; nunca lança exceção por entrada ruim; a assinatura nunca é guardada nem registrada"
  J1-Identity-U5:
    owner: Identity
    interface: com.memetrade.identity.api.AccountAnonymizationPort
    methods:
      - signature: "Optional<AnonymizationTarget> target(UUID userId)"
        returns: "AnonymizationTarget(userId, tenantId, role, anonymized, anonymizedAt Optional)"
        note: "U5; só leitura, sem trava; para as conferências de BR5.1"
      - signature: "AccountAnonymization anonymizeAccount(UUID tenantId, UUID userId, Instant now)"
        returns: "AccountAnonymization(userId, anonymizedAt, sessionsRevoked, alreadyAnonymized); o Privacy soma as contagens de bots e feedback no AnonymizationResult"
        note: "U5; MANDATORY transaction. Ordem: sessões FOR UPDATE por session_id; endereço lido sem trava e desafios de login do endereço apagados (com as respostas idempotentes guardadas da emissão do desafio); conta FOR UPDATE; revogação das sessões ACTIVE depois da trava da conta; carteira sem endereço e provedor, com anonymized_at; estado ANONYMIZED. Conta já ANONYMIZED devolve o instante gravado, sem escrita"
      - signature: "Set<UUID> anonymizedAmong(Collection<UUID> userIds)"
        note: "U5, aditivo; só leitura; a marca 'Conta anonimizada' da lista e do detalhe administrativos"
  J2-Bots:
    owner: Bots
    interface: com.memetrade.bots.api.BotOwnerDataPort
    methods:
      - signature: "int anonymizeOwnerBots(UUID tenantId, UUID ownerUserId)"
        note: "U5; MANDATORY transaction; trava pela LockOrder (pausa do sistema compartilhada, bot_owner, todos os bots da pessoa numa consulta ordenada por bot_id); ACTIVE, PAUSED e CLOSING_PENDING vão direto a STOPPED (regra anonymizeStop), DRAFT e VALIDATED ficam; todos renomeados para ANONYMIZED_BOT_NAME = 'Bot anonimizado'; devolve quantos pararam; idempotente"
      - signature: "long countOwnerBots(UUID tenantId, UUID ownerUserId)"
        note: "U4; todos os bots da pessoa no tenant, em qualquer estado; só leitura"
  J3-Feedback:
    owner: Feedback
    interface: com.memetrade.feedback.api.FeedbackOwnerDataPort
    methods:
      - signature: "int deleteByUser(UUID tenantId, UUID userId)"
        note: "U5; MANDATORY transaction; chama a função erase_feedback_of_user (SECURITY DEFINER, migração 0015) e devolve a contagem; o DELETE direto continua recusado pelo gatilho"
      - signature: "long countByUser(UUID tenantId, UUID userId)"
        note: "U4; comentários ainda guardados da pessoa no tenant; nunca lê o texto; só leitura"
  J4-Ledger:
    owner: Ledger
    interface: com.memetrade.ledger.api.PaperOrderCountPort
    methods:
      - signature: "long countPaperOrders(UUID tenantId, UUID ownerUserId)"
        note: "U4; ordens PAPER da pessoa no tenant, em qualquer situação; só leitura; nada no ledger muda"
  J5-RetentionTableSource:
    owner: Identity
    interface: com.memetrade.identity.api.RetentionTableSource
    implemented_by: "Privacy (com.memetrade.privacy.RetentionCatalog), a partir de U2"
    methods:
      - signature: "List<RetentionItem> items()"
        returns: "dataItem, purpose, period, endAction"
    note: >
      Identity define a porta e a consome para montar `retention` em L1. Até U2, a implementação padrão do Identity devolve
      a tabela declarada no catálogo de termos. Em U2, o Privacy registra a implementação dele, que lê o próprio catálogo
      de retenção. Privacy depende de Identity (J1 e J5), e Identity não depende de Privacy: não há ciclo.
  J6-Audit:
    owner: Audit
    interface: com.memetrade.audit.api.AuditEventPublisher (existente)
    change: "eventos com carteira recebem walletHmac (HMAC-SHA-256, chave da configuração) e endereço só mascarado; o endereço completo nunca chega ao details nem ao outbox"
    newActions: [TERMS_ACCEPTED, DATA_REQUEST_CREATED, DATA_REQUEST_TRANSITIONED, DATA_REQUEST_VERIFICATION_STARTED, DATA_REQUEST_VERIFIED, DATA_REQUEST_OVERDUE, DATA_REQUESTS_PURGED, LOGIN_CHALLENGES_PURGED, SESSIONS_PURGED, ACCOUNT_ANONYMIZED]
    accountAnonymized: "U5: ator o administrador, tenant da pessoa, alvo user:<id interno>, details só botsStopped, sessionsRevoked e feedbackDeleted; o validador recusa outra chave; nunca publicado numa repetição"
    changedTitles: "INVALID_TRANSITION passou a ter o título Invalid state transition (U4), por ser usado também pelos pedidos de titular"
  J7-RetentionEnforcerDeclaration:
    owner: Privacy
    package: com.memetrade.shared.retention
    interface: com.memetrade.shared.retention.RetentionEnforcerDeclaration
    added_by: "U2 u2-retention (BR2.17), aditivo e interno"
    methods:
      - signature: "String purgeId()"
        note: "nome do expurgo, igual ao da trava ou do indicador de saúde (por exemplo loginChallengePurge)"
      - signature: "RetentionTerm retention()"
        returns: "prazo em horas inteiras (dias = 24 horas) ou em meses; lido da propriedade que o próprio expurgo usa"
      - signature: "RetentionAnchor anchor()"
        returns: "CREATED | DELIVERED | EXPIRED | ENDED | CONCLUDED"
      - signature: "static RetentionEnforcerDeclaration of(String purgeId, RetentionTerm retention, RetentionAnchor anchor)"
    implemented_by:
      - "Audit: auditRetention (memetrade.audit.jobs.retention-months, CREATED), auditOutboxCleanup (memetrade.audit.jobs.outbox-delivered-retention, DELIVERED)"
      - "Feedback: feedbackPurge (memetrade.admin.feedback.retention, CREATED)"
      - "Bots: decisionPurge (memetrade.bots.decision-retention, CREATED), registrada também no processo da API"
      - "RiskControl: riskcontrol-purge (memetrade.riskcontrol.purge.trigger-age, CREATED)"
      - "Identity: idempotencyCleanup (memetrade.idempotency.default-retention, CREATED), loginChallengePurge (memetrade.identity.purge.challenge-retention, EXPIRED), sessionPurge (memetrade.identity.purge.session-retention, ENDED)"
    consumed_by: "Privacy (com.memetrade.privacy): recebe a lista de declarações; o teste de conformidade compara cada linha PURGE do catálogo com a declaração do mesmo purgeId"
    note: >
      Cada módulo registra um bean por expurgo na própria configuração. A interface fica no pacote compartilhado, então
      nenhum módulo depende do Privacy e o Privacy não depende dos módulos que expurgam: não há ciclo. Uma Unit que cria um
      expurgo novo (por exemplo, a U4 com os pedidos de titular) registra a declaração dele e retira a linha da lista de
      pendências no mesmo commit.
```

**Ordem na anonimização (dentro da transação aberta pelo Privacy):** `J2.anonymizeOwnerBots` → `J3.deleteByUser` → `J1.anonymizeAccount` → `J6.publish(ACCOUNT_ANONYMIZED)`. Uma exceção em qualquer passo desfaz tudo.

## Regras de propriedade dos contratos

- **Dono:** o componente provedor é dono do contrato (coluna Owner). `Identity` cuida de L1, L2, L3, J1 e da porta J5 (que o `Privacy` implementa); `Privacy`, de P1 e P2; cada módulo, das próprias interfaces (J2, J3, J4, J6).
- **Fonte da verdade:** o OpenAPI gerado do código e versionado. Uma mudança de contrato na geração de código atualiza o bloco deste documento no mesmo commit, e a revisão da Unit confere os dois.
- **Mudança aditiva:** livre dentro de `/api/v1`; os clientes ignoram campos desconhecidos. Um valor de enum novo exige que o app trate o desconhecido.
- **Mudança que quebra:** só no mesmo commit que atualiza os dois apps e os módulos consumidores, com a CI verde.
- **Sem leitura cruzada:** nenhum módulo lê tabela de outro; só as interfaces J1 a J6.
- **Segurança:** nenhum contrato aceita tenant do corpo; as rotas administrativas exigem o papel `ADMIN`; nenhum contrato devolve carteira completa.

## Open questions

| Contract | Question | Blocks |
|---|---|---|
| L1 | Local de armazenamento, transferência internacional e e-mail do encarregado (valores reais) | Publicação da versão do beta (FR1.4), não o desenvolvimento |
| L1 | Texto final dos termos v2 e da política, revisado pela assessoria | Publicação da versão do beta |
