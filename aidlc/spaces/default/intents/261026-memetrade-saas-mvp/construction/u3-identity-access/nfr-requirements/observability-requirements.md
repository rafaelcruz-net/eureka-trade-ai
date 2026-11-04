# Requisitos de observabilidade — U3 Convites, contas e sessões

Requisitos de monitoração, log e auditoria da Unit `u3-identity-access`, derivados do NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md), das regras BR1.1, BR1.4, BR2.3 e BR3.5 de [rules.md](../functional-design/rules.md) e da resposta Q5 de [nfr-requirements-questions.md](nfr-requirements-questions.md): a U3 publica só a métrica de saúde e a de erro exigidas pelo NFR9, sem contadores de negócio próprios.

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR9.1 | Saúde do componente de identidade | O indicador de saúde do IdentityAccess (Actuator) fica `DOWN` quando o banco não responde ou quando os termos vigentes não foram carregados; o smoke confere `UP` | NFR9, NFR10.3 |
| NFR9.2 | Taxa de erro das rotas da U3 | A métrica de requisições HTTP da U1 (Micrometer) cobre as rotas da U3 pelo modelo da rota, com status; a taxa de 5xx por rota sai dela. Nenhuma métrica nova de negócio (Q5) | NFR9, Q5 |
| NFR9.3 | Nada sensível em métricas | Rótulos de métricas usam só o modelo da rota e o status; nunca código de convite, endereço, `inviteId` ou identificador de pessoa. Teste lê o endpoint de métricas depois de um fluxo completo e procura esses valores | NFR7, BR2.3 |
| NFR9.4 | Eventos de auditoria da U3 | `INVITE_ISSUED`, `INVITE_REVOKED` e `ACCOUNT_CREATED` são publicados na saída da trilha (C9) na mesma transação da mudança; toda recusa da verificação com convite grava um único `LOGIN_REJECTED` com o motivo; o 403 das rotas administrativas grava `REQUEST_FORBIDDEN`. Teste confere um evento por ação e nenhum na repetição idempotente ou na revogação repetida | BR1.1, BR1.4, BR3.5, AC7.1.4 |
| NFR9.5 | Logs com correlação e sem segredo | 100% das requisições da U3 têm `correlationId` no log e no `ProblemDetail`; log sem código de convite, assinatura, nonce, token ou endereço completo (varredura de logs da U1 ampliada) | NFR9, `project.md` |
| NFR9.6 | Sem alertas automáticos no beta local | Enquanto o beta roda só localmente, não há alerta; métricas e logs ficam disponíveis no ambiente. Alertas são definidos com a hospedagem, abaixo das metas de SLO que vierem com ela | NFR10, U2 e U4 |
