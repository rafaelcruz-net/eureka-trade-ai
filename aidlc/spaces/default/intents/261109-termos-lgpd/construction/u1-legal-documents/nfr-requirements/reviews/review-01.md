**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-09T17:25:30Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/security-requirements.md > NFR6.3; tech-stack-decisions.md > linha "Limite de tentativas" | NFR6.3 e a tech-stack dizem "limite existente de 60 requisições por minuto por IP nas rotas de documentos". No código não existe limitador para `/api/v1/legal/*`: o único limitador para rota sem sessão é `InviteLookupRateLimiter` (contadores próprios do convite, padrão `invite-lookup-rate-limit-per-minute: 10` em `application.yml`, 1000 no perfil `loadtest`). O valor 60 e a reutilização são premissas falsas sobre o código existente. | Reescrever NFR6.3 e a linha da tech-stack como limitador novo (ou generalizado) para as rotas de documentos, com contadores separados, propriedade própria com padrão 60 por minuto e valor elevado no perfil `loadtest`; remover "existente". | New |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/performance-requirements.md > NFR3.1, NFR3.3, NFR3.7 e security-requirements.md > NFR6.3 | O limite é por IP, mas as páginas são montadas no servidor (Next.js): a chamada à API sai do contêiner web, então todos os visitantes compartilham um único IP e um único contador de 60/min. O cenário de carga `legal` (20 acessos simultâneos por 5 min, também de um IP só) esbarraria em 429 e contradiria "0 respostas 5xx"/p95 de NFR3.1 e NFR3.2. Nada define de qual IP vale o limite. | Definir de qual IP o limite vale (por exemplo, IP do cliente repassado pelo servidor web de forma confiável, ou isenção do tráfego servidor a servidor) e como o cenário `legal` o contorna; deixar a decisão testável. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/performance-requirements.md > NFR3.4 e security-requirements.md > NFR6.5 | `SecurityHeadersFilter` da API aplica `Cache-Control: no-store` em toda resposta, e os dois apps geram CSP com nonce por requisição em `middleware.ts`, o que torna a página dinâmica. "Página reaproveitada no servidor por até 5 min" e `public, max-age=300` não são triviais com isso. | Explicitar que o controller de documentos sobrescreve o `no-store` só nas respostas 200/304 (erros e 429 seguem sem cache) e que o reaproveitamento por 5 min vale para o cache de dados do `fetch` da API, não para a página inteira. Confirmar que `/termos` e `/privacidade` são rotas públicas no `middleware.ts` dos dois apps. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-requirements/reliability-requirements.md > NFR7.3 e observability-requirements.md > NFR8.2 | O status `DEGRADED` é personalizado, e o `HealthIndicator` precedente (`AuditJobHealthIndicator`) já o usa fora do grupo de prontidão, que é uma lista explícita (`readinessState,db,migrations`). A não-influência na prontidão está correta, mas o requisito não diz que o indicador novo deve ficar fora dessa lista nem qual HTTP status o status personalizado devolve. | Acrescentar "fora dos grupos de liveness e readiness; HTTP 200" e um teste que confira isso. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Rastreabilidade manual (`traceability.json` x artefatos) | NFR1.1-1.2, NFR3.1-3.8, NFR4.1, NFR6.1-6.6, NFR7.1-7.4, NFR8.1-8.3 e NFR9.1 existem nos artefatos; NFR2 e NFR5 como N/A com justificativa | Consistente; todos os NFRx.y derivados trazem o id de origem no prefixo |
| Verificação no código | Cabeçalhos de segurança existem (API: `SecurityHeadersFilter`; apps: CSP e X-Frame-Options em `middleware.ts`); prontidão é lista explícita; limitador de `/legal` não existe | Confirma os dois Major e os Minor |

### Summary

Os alvos são quantificados e testáveis, a rastreabilidade fecha e as ameaças estão cobertas. O problema central é que o limite de 60 por IP é apresentado como existente, mas não existe para as rotas de documentos, e o IP seria o do servidor web nas páginas renderizadas no servidor. Os dois Major podem ser corrigidos no texto, por isso READY (no máximo 2 Major).
