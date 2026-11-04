# C2 — Mercado, scanner e avaliação (U4, iniciado na U1)

Contrato da U4 `u4-discovery` para o app do convidado (histórias US2.2, US2.3, US2.4, US3.1 e US3.2). A U1 entregou a lista mínima; a U4 completa o contrato com filtros, estado das fontes, avaliação de risco e o detalhe. A especificação gerada do código fica em [`apps/api/openapi/openapi-guest.yaml`](../../apps/api/openapi/openapi-guest.yaml); o `GuestOpenApiContractTest` confere que ela é igual ao código, e os tipos do frontend (`apps/web/src/lib/api/schema.d.ts`) são gerados dela.

O `contract-summary.md` do Contract Design não foi editado na geração de código: os acréscimos abaixo ficam registrados aqui, para o responsável do projeto levá-los ao contrato.

## Acréscimos ao C2 (todos aditivos)

| Onde | Acréscimo | Regra |
|---|---|---|
| `SourceStatus.status` | valor `PENDING` (fonte que ainda não coletou) | BR1.5 |
| `SourceStatus.lastUpdatedAt` | opcional: ausente enquanto a fonte nunca coletou | BR1.5 |
| `ScannerRow.risk` | `RiskSummary` (`AVAILABLE` com `score` e `band`, ou `UNAVAILABLE` com `reason`) | BR2.3, BR5.4 |
| `KnownValue.divergence` | lista das outras origens quando o campo diverge acima da tolerância | BR1.7, BR5.7 |
| `Assessment.computedAt` | horário do cálculo guardado | BR3.7, BR3.9 |
| `Assessment.bandRaisedByUnknown` | `true` quando a faixa Baixo passou a Médio por fator sem dado | BR3.4 |
| `AssessmentFactor.factorRisk` | risco do fator de 0 a 100 (100 quando a entrada é `unknown`) | BR3.4 |
| `503 SOURCE_UNAVAILABLE` | `ProblemDetail` com a extensão `sources` (estado e última atualização de cada fonte) | BR1.4 |
| `429 RATE_LIMITED` | novo código comum, com `Retry-After` em segundos | NFR6.3 |

## `GET /api/v1/scanner/tokens`

| Parâmetro | Regra (BR2.7) |
|---|---|
| `cursor` | opaco, vindo de `nextCursor`; um cursor de outros filtros ou de outra ordem é recusado |
| `limit` | inteiro de 1 a 100; padrão 50 |
| `sort` | `AGE` (padrão), `LIQUIDITY`, `VOLUME_24H`, `HOLDERS`, `RISK_SCORE` |
| `order` | `ASC` ou `DESC`; padrão `ASC` para idade (mais novo primeiro) e risco, `DESC` para os demais |
| `minLiquidity`, `minVolume24h` | decimal em texto, não negativo, até 30 dígitos e 18 casas |
| `minHolders`, `maxAgeMinutes` | inteiro não negativo |
| `maxRiskScore` | inteiro de 0 a 100 |
| `sourceId` | identificador de fonte (`sim-a`, `sim-b`) |
| `tenantId` | ignorado, nunca recusado |

Qualquer outro parâmetro, um parâmetro repetido ou um valor inválido devolve `400 VALIDATION_FAILED` com todos os campos em `fields`.

Comportamento:

- Fronteiras inclusivas; um campo filtrado `unknown` deixa o token fora e o conta uma vez em `excludedByUnknown` (só quando passaria nos filtros conhecidos). Indicador indisponível vale como `unknown` no filtro e vai para o fim da ordem.
- A primeira página fixa a foto (`snapshotAt` dentro do cursor); tokens novos depois dela entram em `pendingNewTokens` e esperam "Atualizar lista".
- `unknown` vai para o fim nas duas direções; empates por mint e chain.
- Sem fonte `OK` ou `DEGRADED`: com alguma `PENDING`, `200` com lista vazia e `sources`; com todas `FAILED`, `503 SOURCE_UNAVAILABLE` com `sources`.

## `GET /api/v1/tokens/{chain}/{mint}/assessment`

- `chain` em minúsculas e dígitos; `mint` Base58 de 32 a 44 caracteres; senão `400`.
- Token sem observação nas últimas 24 horas: `404 NOT_FOUND`. O primeiro detalhe de um token novo calcula e guarda a avaliação na hora.
- Resposta: `risk`, `coverage` e `minCoverage` (pontos percentuais, escala 4), `weightSetVersion`, `inValidation = true`, `decisionHint` (`NO_TRADE` quando indisponível), `bandRaisedByUnknown`, `computedAt` e cada fator com `weight`, `input` (com fonte, horário e `stale` calculado na leitura), `factorRisk` e `contribution` (texto decimal, ou `UnknownValue` quando a entrada é `unknown`).

## Acesso e limite

- Sem token: `401 UNAUTHENTICATED`, sem contar no limite.
- Token de convidado: `200`.
- Token de administrador: `403 FORBIDDEN_ROLE`, com um `REQUEST_FORBIDDEN` na trilha. Desde a U4 essa regra comum vale para toda rota do convidado (filtro de papel da U1).
- Até `SCANNER_RATE_LIMIT_PER_MINUTE` (padrão 60) pedidos por pessoa e por rota em 60 s; acima, `429 RATE_LIMITED` com `Retry-After`. O contador fica na memória do processo (risco aceito para uma instância).

## Interfaces Java públicas (contratos de esquema compartilhado)

| Interface | Pacote | Consumidor | Observação |
|---|---|---|---|
| `MarketDataQuery` (C11) | `marketdata.api` | U5 a U8 | sem mudança de assinatura; falha técnica vira `Unknown(SOURCE_UNAVAILABLE)` |
| `RiskAssessmentQuery` (C12) | `riskassessment.api` | U5 | falha técnica vira `Unavailable(SOURCE_UNAVAILABLE)` |
| `SourceStatusMarks` (C18) | `marketdata.api` | U5 (canal C7) | marca global que cresce a cada mudança de estado de fonte |
| `MarketSourceAdapter` (C19) | `marketdata.api` | adaptadores | falhas por `MarketSourceException` com categoria e se cabe nova tentativa |
| `RiskWeightAdministration` | `riskassessment.api` | U8 | **nova**: criar, alterar rascunho, ativar e listar versões de pesos; exige administrador por `AdminAccess` com o pedido da rota administrativa |

Códigos de `RiskWeightAdministration`: `400 VALIDATION_FAILED` (BR4.1), `403 FORBIDDEN_ROLE` (não administrador, auditado), `404 NOT_FOUND` (versão inexistente) e o novo `409 WEIGHT_SET_NOT_EDITABLE` (alterar versão `ACTIVE` ou `RETIRED`, ou ativar uma `RETIRED`), a acrescentar ao C8 quando a U8 criar a rota. A ativação publica `RISK_WEIGHTS_ACTIVATED` (C9) com `fromVersion` e `toVersion`.
