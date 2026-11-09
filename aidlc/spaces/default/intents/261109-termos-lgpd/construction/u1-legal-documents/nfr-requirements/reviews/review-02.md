**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-09T17:27:50Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/security-requirements.md > NFR6.3; tech-stack-decisions.md > linha "Limite de tentativas" | NFR6.3 e a tech-stack dizem "limite existente de 60 requisições por minuto por IP nas rotas de documentos". No código não existe limitador para `/api/v1/legal/*`: o único limitador para rota sem sessão é `InviteLookupRateLimiter` (contadores próprios do convite, padrão `invite-lookup-rate-limit-per-minute: 10` em `application.yml`, 1000 no perfil `loadtest`). O valor 60 e a reutilização são premissas falsas sobre o código existente. | Reescrever NFR6.3 e a linha da tech-stack como limitador novo (ou generalizado) para as rotas de documentos, com contadores separados, propriedade própria com padrão 60 por minuto e valor elevado no perfil `loadtest`; remover "existente". | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/performance-requirements.md > NFR3.1, NFR3.3, NFR3.7 e security-requirements.md > NFR6.3 | O limite é por IP, mas as páginas são montadas no servidor (Next.js): a chamada à API sai do contêiner web, então todos os visitantes compartilham um único IP e um único contador de 60/min. O cenário de carga `legal` (20 acessos simultâneos por 5 min, também de um IP só) esbarraria em 429 e contradiria "0 respostas 5xx"/p95 de NFR3.1 e NFR3.2. Nada define de qual IP vale o limite. | Definir de qual IP o limite vale (por exemplo, IP do cliente repassado pelo servidor web de forma confiável, ou isenção do tráfego servidor a servidor) e como o cenário `legal` o contorna; deixar a decisão testável. | Resolved (reviewer) |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/performance-requirements.md > NFR3.4 e security-requirements.md > NFR6.5 | `SecurityHeadersFilter` da API aplica `Cache-Control: no-store` em toda resposta, e os dois apps geram CSP com nonce por requisição em `middleware.ts`, o que torna a página dinâmica. "Página reaproveitada no servidor por até 5 min" e `public, max-age=300` não são triviais com isso. | Explicitar que o controller de documentos sobrescreve o `no-store` só nas respostas 200/304 (erros e 429 seguem sem cache) e que o reaproveitamento por 5 min vale para o cache de dados do `fetch` da API, não para a página inteira. Confirmar que `/termos` e `/privacidade` são rotas públicas no `middleware.ts` dos dois apps. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/reliability-requirements.md > NFR7.3 e observability-requirements.md > NFR8.2 | O status `DEGRADED` é personalizado, e o `HealthIndicator` precedente (`AuditJobHealthIndicator`) já o usa fora do grupo de prontidão, que é uma lista explícita (`readinessState,db,migrations`). A não-influência na prontidão está correta, mas o requisito não diz que o indicador novo deve ficar fora dessa lista nem qual HTTP status o status personalizado devolve. | Acrescentar "fora dos grupos de liveness e readiness; HTTP 200" e um teste que confira isso. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/security-requirements.md > NFR6.3 (isenção por origem de rede interna) | A isenção não diz qual endereço é comparado. Se for o IP resolvido, ou se um balanceador ou proxy confiável ficar dentro da faixa interna, o tráfego externo que passa por ele herda a isenção. | Registrar que a isenção compara só o endereço do peer direto (`getRemoteAddr`), nunca o resolvido nem X-Forwarded-For, e que a faixa configurada não inclui proxies ou balanceadores confiáveis. Acrescentar um caso ao teste de integração com X-Forwarded-For interno forjado vindo de IP externo, que deve receber 429. | New |

> R-01 Reviewer note: NFR6.3 agora define um limitador novo, com contadores e propriedade próprios (padrão 60/min por IP) e valor alto no perfil `loadtest`, referenciado em NFR3.1.

> R-02 Reviewer note: NFR6.3 isenta as chamadas SSR por origem de rede interna configurada, nunca por cabeçalho do cliente; o teste cobre IP externo (429) e origem interna (sem 429). O `ClientIpResolver` existente usa o peer direto e só honra X-Forwarded-For de proxy confiável, o que sustenta a não falsificação.

> R-03 Reviewer note: NFR3.4 restringe o override do no-store a 200/304, separa o cache de dados (5 min) da página dinâmica por nonce e declara `/termos` e `/privacidade` como rotas públicas nos dois apps, com teste.

> R-04 Reviewer note: NFR7.3 e NFR8.2 colocam `legalDocuments` fora dos grupos de liveness e readiness e fixam `DEGRADED` com HTTP 200.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Nenhuma ferramenta de validação listada para o estágio | n/a | Verificação manual dos artefatos e do `ClientIpResolver`. |

### Summary

Os quatro achados anteriores foram corrigidos e a revisão não trouxe contradição nem fraqueza grave. Resta uma lacuna Minor: especificar que a isenção interna usa só o peer direto.
