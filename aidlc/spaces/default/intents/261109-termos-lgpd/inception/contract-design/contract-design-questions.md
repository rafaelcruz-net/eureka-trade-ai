# Perguntas do Contract Design — Termos de uso e LGPD

Conversa: português (Brasil). Base: `inception/units-generation/unit-of-work.md` e `unit-of-work-dependency.md`, `inception/domain-design/components.md` e `inception/requirements-analysis/requirements.md`. Regras herdadas do produto:
- REST sob `/api/v1/`;
- erros em `ProblemDetail` com código estável e `correlationId`;
- `Idempotency-Key` em POST, PUT, PATCH e DELETE;
- tipos dos apps gerados do OpenAPI;
- interfaces Java públicas entre módulos tratadas como contrato;
- contrato e código mudam no mesmo commit.

## Q1. Que superfície pública a API expõe?

A) Quatro grupos de rotas:
- **Documentos (Identity, sem login):** `GET /api/v1/legal/current` (versão vigente com termos, política, aviso de risco, resumos e campos de implantação) e `GET /api/v1/legal/versions` e `/{version}` (histórico, Could).
- **Aceite (Identity, convidado):** `POST /api/v1/terms/acceptances` com a versão aceita (`409 TERMS_VERSION_OUTDATED`); a Conta passa a trazer a versão aceita e a vigente.
- **Pedidos (Privacy, convidado):** `GET` e `POST /api/v1/privacy/requests`, e `GET /api/v1/privacy/requests/{id}`.
- **Administração (Privacy, ADMIN):**
  - `GET /api/v1/admin/privacy/requests` com filtro;
  - `GET /api/v1/admin/privacy/requests/{id}` (com o resumo de Acesso);
  - `POST /api/v1/admin/privacy/requests` (registro por e-mail);
  - `POST /api/v1/admin/privacy/requests/{id}/transitions`;
  - `POST /api/v1/admin/privacy/requests/{id}/verification` e `/verification/confirm`;
  - `POST /api/v1/admin/privacy/requests/{id}/anonymization`;
  - `POST /api/v1/admin/privacy/requests/{id}/reply-sent`.

(recomendado)

B) Sem rota pública: os documentos vêm embutidos no app
X) Other (please specify)

[Answer]: A

## Q2. Que mecanismo em cada fronteira?

A) Duas formas:
- REST (OpenAPI) entre os apps e a API;
- interfaces Java públicas entre módulos, chamadas na mesma transação quando preciso. Entre elas:
  - `Identity.anonymizeAccount`, `Identity.personSummary`, `Identity.verifyAccountSignature`;
  - `Bots.anonymizeOwnerBots`, `Bots.countOwnerBots`;
  - `Feedback.deleteByUser`, `Feedback.countByUser`;
  - `Ledger.countPaperOrders`;
  - `Privacy.retentionCatalog`;
  - `Audit.publish`, já existente, com o HMAC.

Sem eventos entre processos e sem leitura de tabela de outro módulo (recomendado)

B) Eventos assíncronos entre módulos
X) Other (please specify)

[Answer]: A

## Q3. Quem é dono de cada contrato?

A) Assim:
- `Identity`: documentos, aceite, filtro e as interfaces de identidade;
- `Privacy`: pedidos (convidado e administrador) e o catálogo de retenção;
- `Bots`, `Feedback` e `Ledger`: as próprias interfaces novas;
- `Audit`: o formato do evento com `walletHmac`.

(recomendado)

B) `Privacy` dono de todas as interfaces de anonimização
X) Other (please specify)

[Answer]: A

## Q4. Que regra de versão e de mudança?

A) Tudo aditivo dentro de `/api/v1`. Os clientes ignoram campos desconhecidos. Mudança que quebra só no mesmo commit que atualiza os dois apps e os módulos consumidores, com a CI verde. Contrato e OpenAPI gerado mudam juntos (regra do projeto) (recomendado)
B) Criar `/api/v2` para as rotas novas
X) Other (please specify)

[Answer]: A

## Q5. Que erros, tempos e repetições?

A) Códigos estáveis em `ProblemDetail`:
- `TERMS_REACCEPTANCE_REQUIRED` (403);
- `TERMS_VERSION_OUTDATED` (409);
- `IDENTITY_NOT_VERIFIED` (409);
- `DATA_REQUEST_ALREADY_OPEN` (409);
- `DATA_REQUEST_CLOSED` (409, transição em pedido encerrado);
- `INVALID_TRANSITION` (409);
- `VERIFICATION_EXPIRED` e `VERIFICATION_ALREADY_USED` (409);
- `VERIFICATION_INVALID` (422);
- `VALIDATION_FAILED` (400);
- `NOT_FOUND` (404, inclusive para pedido de outra pessoa).

Todos os POST exigem `Idempotency-Key`; a anonimização repetida devolve o mesmo resultado. Sem repetição automática no cliente, exceto em erros 5xx de operação idempotente. Tempo-limite das chamadas entre módulos: o da transação do banco (recomendado)

B) Erros sem código estável
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Rotas: documentos públicos (`GET /api/v1/legal/current`, `/legal/versions`, `/legal/versions/{version}`); aceite (`POST /api/v1/terms/acceptances`); pedidos do convidado (`/api/v1/privacy/requests`); pedidos do administrador (`/api/v1/admin/privacy/requests` com transições, verificação, confirmação, anonimização e registro de envio).
- Mecanismo: REST entre apps e API; interfaces Java públicas entre módulos (Identity, Bots, Feedback, Ledger, Privacy, Audit), na mesma transação quando preciso; sem eventos entre processos.
- Donos: Identity (documentos, aceite, filtro, identidade), Privacy (pedidos, retenção), Bots/Feedback/Ledger (próprias interfaces), Audit (evento com `walletHmac`).
- Versão: aditivo em `/api/v1`; quebra só com os consumidores no mesmo commit; contrato e OpenAPI juntos.
- Erros: `TERMS_REACCEPTANCE_REQUIRED` 403, `TERMS_VERSION_OUTDATED` 409, `IDENTITY_NOT_VERIFIED` 409, `DATA_REQUEST_ALREADY_OPEN` 409, `DATA_REQUEST_CLOSED` 409, `INVALID_TRANSITION` 409, `VERIFICATION_EXPIRED`/`VERIFICATION_ALREADY_USED` 409, `VERIFICATION_INVALID` 422, `VALIDATION_FAILED` 400, `NOT_FOUND` 404; `Idempotency-Key` em todos os POST; anonimização idempotente.

- Looks correct
- Request changes

[Answer]: Looks correct
