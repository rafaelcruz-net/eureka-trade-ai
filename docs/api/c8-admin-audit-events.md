# C8 — Consulta da trilha de auditoria (`GET /api/v1/admin/audit-events`)

Operação nova do contrato administrativo C8, provida pelo módulo `audit` na U2 (história US7.3, requisitos FR3.3 e NFR7). O contrato C8 é da U8, mas a mudança fica registrada no design funcional da Unit provedora ([functional-spec.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/functional-spec.md), seção "Mudança registrada no contrato C8").

A especificação gerada do código fica em [`apps/api/openapi/openapi-admin.yaml`](../../apps/api/openapi/openapi-admin.yaml). O `AdminOpenApiContractTest` confere que ela é igual ao código, e o job `contract` da CI roda esse teste. A especificação do convidado (`openapi-guest.yaml`) não tem nenhuma rota `/api/v1/admin`.

## Acesso

- Só o papel `ADMIN` executa a consulta, e a checagem acontece em duas camadas:
  - **Regra da rota:** `AdminRouteFilter` cobre tudo o que fica sob `/api/v1/admin`. A decisão usa o caminho normalizado (`RequestPaths`: decodificado, sem conteúdo depois de `;`, barras repetidas juntadas, segmentos `.` e `..` resolvidos, sem diferença entre maiúsculas e minúsculas), nunca o URI cru. Assim, `/api/v1/%61dmin/...` e `/api/v1/admin;x/...` também passam pela regra.
  - **Defesa em profundidade:** a própria operação confere o papel por `AdminAccess`.
- As duas camadas usam `AdminAuthorization`, a única classe de produção que referencia `Role.ADMIN` (regra ArchUnit). Outra regra exige que todo `Admin*Controller` dependa de `AdminAccess`.
- Sem token válido, a resposta é `401 UNAUTHENTICATED`.
- Com token de convidado, a resposta é `403 FORBIDDEN_ROLE`, sem dados. Cada recusa grava exatamente um `REQUEST_FORBIDDEN` ([ADR-0003](../adr/ADR-0003-sem-agregacao-de-recusas.md)).
- A rota gravada no evento nunca é o URI do cliente. É o caminho normalizado, sem a query string, com no máximo 200 caracteres. Todo segmento que não seja uma palavra curta ou um UUID vira `{redacted}`. Assim, nenhum token, assinatura ou endereço entra na trilha, e a recusa sempre responde `403`.
- A sessão de administrador nasce na entrada administrativa da U8. Até lá, só os testes criam esse contexto (`AdminSessions`, código de teste), e o smoke confere apenas o `403` do convidado.

## Parâmetros

| Parâmetro | Obrigatório | Regra (BR2.2) |
|---|---|---|
| `from` | sim | Instante ISO-8601 em UTC, inclusivo |
| `to` | sim | Instante ISO-8601 em UTC, exclusivo; maior que `from`; no máximo 31 dias depois |
| `action` | não | Igualdade exata; maiúsculas, dígitos e sublinhado, até 64 caracteres |
| `cursor` | não | Valor opaco devolvido em `nextCursor`; precisa cair dentro do período |
| `limit` | não | Inteiro de 1 a 100; padrão 50 |

Qualquer outro parâmetro, ou um parâmetro repetido, devolve `400 VALIDATION_FAILED` com a lista de campos em `fields`. A rota não usa `Idempotency-Key`, porque é `GET`.

## Resposta `200`

```json
{
  "items": [
    {
      "eventId": "uuid",
      "tenantId": "uuid ou null",
      "actor": "userId, adminId, system ou endereço mascarado",
      "actorType": "PERSON | ADMIN | SYSTEM | ANONYMOUS",
      "action": "LOGIN_SUCCEEDED",
      "targetRef": "texto ou null",
      "correlationId": "texto",
      "occurredAt": "2026-10-28T12:00:00Z",
      "recordedAt": "2026-10-28T12:00:01Z ou null",
      "details": { "chave": "valor" }
    }
  ],
  "nextCursor": "texto opaco ou null na última página"
}
```

- A ordem é `occurredAt` decrescente, com desempate por `eventId` decrescente, estável entre páginas (BR2.3). A busca usa cursor, sem `OFFSET`.
- A consulta traz eventos de todos os tenants e eventos sem tenant. `details` vem como foi gravado, inclusive com o endereço de carteira completo (BR2.4).
- A resposta sempre traz `Cache-Control: no-store`.

## Auditoria da própria consulta (BR2.5)

A leitura e a gravação de `AUDIT_QUERIED` acontecem na mesma transação de leitura e escrita. O evento leva:

- o administrador como ator, com `actorType` `ADMIN`;
- tenant vazio;
- `targetRef` igual a `GET /api/v1/admin/audit-events`;
- `from`, `to` e `action` em `details`, nunca os itens devolvidos.

Se o evento não puder ser gravado, a transação desfaz e a resposta é `500 INTERNAL_ERROR`, sem dados.

## Erros

| Status | Código | Quando |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Parâmetro ausente, inválido, repetido ou não declarado; período acima de 31 dias |
| 401 | `UNAUTHENTICATED` | Sem token ou token inválido |
| 403 | `FORBIDDEN_ROLE` | Token de papel diferente de `ADMIN`; auditado como `REQUEST_FORBIDDEN` |
| 500 | `INTERNAL_ERROR` | `AUDIT_QUERIED` não pôde ser gravado; nenhum item é devolvido |

Todos os erros seguem o formato RFC 7807 (`application/problem+json`), com `code` e `correlationId`.

## Testes

- `AdminAuditRouteIT`, contra o Tomcat real:
  - `401`;
  - `403` com um `REQUEST_FORBIDDEN` por recusa;
  - 17 grafias do caminho com token de convidado, nenhuma com `200` ou dados;
  - caminhos hostis sempre com `403` e um evento saneado.
- `AdminOperationDefenceIT`: a operação recusa o convidado mesmo sem a regra da rota.
- `AdminAuditQueryIT`:
  - `200` com cursor;
  - todos os tenants e endereço completo em `details`;
  - `no-store`;
  - `AUDIT_QUERIED` sem itens;
  - `400` com a lista de campos;
  - `500` sem dados.
- `AdminOpenApiContractTest`: especificação gerada igual à versionada; grupo administrativo só com rotas `/api/v1/admin/`.
