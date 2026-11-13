# Rastreamento

Conversa: português (Brasil). Resposta 5A. Sem mudança em relação ao intent anterior (`261026-memetrade-saas-mvp/operation/observability-setup/tracing-config.md`): o `correlationId` (cabeçalho `X-Correlation-Id`) atravessa a requisição, o log JSON e o evento da trilha de auditoria; o X-Ray (ou OpenTelemetry) fica para quando houver AWS.

## O que este intent acrescenta ao rastreio

| Fluxo | Como seguir pelo `correlationId` |
|---|---|
| Novo aceite | o `403 TERMS_REACCEPTANCE_REQUIRED` e o aceite seguinte têm `correlationId` próprio; o evento `TERMS_ACCEPTED` na trilha leva o do aceite |
| Pedido do titular | criação, verificação de identidade e resposta geram eventos `DATA_REQUEST_*` com o `correlationId` de cada chamada |
| Anonimização | uma transação única; o evento `ACCOUNT_ANONYMIZED` e o log `INFO` levam o mesmo `correlationId` |
| Expurgos e job de 15 minutos | tarefas de sistema com `correlationId` novo por execução, no log e no evento da trilha |

Para seguir um caso, use a consulta `correlation` de `log-queries.md` e a trilha de auditoria do administrador filtrada pelo mesmo identificador.

## Futuro na AWS

Quando houver AWS, o X-Ray (ou OpenTelemetry) entra pela mesma decisão do intent anterior, com o `correlationId` gravado como anotação do trace. Nenhum dado pessoal vai para atributo de trace (`security-design.md` das Units).
