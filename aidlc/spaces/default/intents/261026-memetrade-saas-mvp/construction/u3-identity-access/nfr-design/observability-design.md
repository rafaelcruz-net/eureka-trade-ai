# Desenho de observabilidade — U3 Convites, contas e sessões

Como a Unit `u3-identity-access` atende a [observability-requirements.md](../nfr-requirements/observability-requirements.md) (NFR9.1 a NFR9.6 da U3). Sem métricas de negócio próprias (Q5 de NFR Requirements).

| Sinal | Desenho | Requisito |
|---|---|---|
| Saúde | O indicador de banco do Actuator, já existente, cobre a identidade; nenhum indicador novo. A condição dos termos não entra na saúde, porque a API não sobe sem eles | NFR9.1 |
| Taxa de erro | A métrica `http.server.requests` (Micrometer) já existente, com rótulos `uri` (modelo da rota) e `status`; a taxa de 5xx das rotas da U3 sai dela | NFR9.2 |
| Rótulos seguros | Nenhum rótulo com código, endereço, `inviteId` ou identificador de pessoa; o `uri` é sempre o modelo (`/api/v1/invites/{code}`). Teste lê `/actuator/prometheus` depois de um fluxo completo e procura esses valores | NFR9.3 |
| Trilha de auditoria | `INVITE_ISSUED` (ator administrador mascarado, alvo `inviteId`, detalhes: `tenantId`, `expiresAt`), `INVITE_REVOKED` (alvo `inviteId`), `ACCOUNT_CREATED` (ator a nova pessoa, alvo `inviteId`, detalhes: `termsVersion`, `acceptedAt`), publicados pela interface C9 na mesma transação; recusas como `LOGIN_REJECTED` com `reason`; 403 como `REQUEST_FORBIDDEN` | NFR9.4 |
| Logs | `CorrelationIdFilter` da U1 em todas as rotas; mascaramento de endereço do `LogMasking`; redação de segmentos longos cobre o código; o `LogScanIT` ganha um fluxo completo da U3 | NFR9.5 |
| Alertas | Nenhum no beta local; métricas e logs disponíveis no ambiente. Definidos com a hospedagem, abaixo das metas de SLO | NFR9.6 |

## SLIs para quando houver hospedagem

| SLI | Fonte | Uso |
|---|---|---|
| p95 da verificação com convite | `http.server.requests` com `uri=/api/v1/auth/verify` | Comparar com 500 ms (NFR3.1 da U3) |
| Taxa de 5xx das rotas da U3 | `http.server.requests` por `uri` e `status` | Base para o alerta futuro |
