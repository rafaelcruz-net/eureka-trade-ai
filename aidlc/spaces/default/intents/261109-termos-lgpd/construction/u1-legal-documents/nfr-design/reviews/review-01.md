**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-09T17:42:02Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-design/security-design.md > Controles "Rotas públicas" e Testes de segurança "POST nas rotas de documentos recebe 405" | A API não usa Spring Security: a autenticação é o `AuthenticationFilter`, com `PUBLIC_ROUTES` e um padrão público só para GET. Se a rota pública cobrir apenas GET, um `POST` ou `HEAD` sem sessão recebe 401, não 405 (e o CORS promete `HEAD`). O design fala em "configuração de segurança" que não existe. | Dizer que o ajuste é no `AuthenticationFilter` (`shouldNotFilter`) e se a isenção vale para todos os métodos de `/api/v1/legal/**` (405 vem do controlador) ou só GET/HEAD (então o teste espera 401). Alinhar o teste ao mecanismo escolhido. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-design/security-design.md > Controles "CORS" | `GuestCorsPolicy`/`AdminCorsPolicy` já registram `/api/v1/**` (GET, POST, PUT, cabeçalhos `Authorization` e exposição de `Retry-After`) e o primeiro padrão que casa vence. O CORS "só GET e HEAD, expondo `ETag`" só vale se for registrado antes desse padrão geral. As páginas buscam no servidor, então o CORS do navegador nem é necessário para os documentos. | Registrar o padrão `/api/v1/legal/**` antes do padrão geral, nas duas políticas, ou declarar que nenhum CORS específico é necessário porque a busca é só no servidor. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-design/security-design.md > Controles "Cabeçalhos" (rotas públicas no controle de acesso dos apps) | Os `middleware.ts` do web e do admin só montam a política de conteúdo e cabeçalhos; não há controle de acesso neles. O `middleware.ts` do admin ainda põe `Cache-Control: no-store` em todas as páginas. O design não diz onde `/termos` e `/privacidade` viram públicas nem que o admin continua com `no-store`. | Apontar o ponto real onde cada app decide o acesso às rotas e registrar que o `no-store` do admin é mantido em `/termos` e `/privacidade`. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-design/security-design.md > Controles "Limite de requisições" e "Isenção interna" | Faltam dois detalhes: a ordem do filtro novo em relação ao `SecurityHeadersFilter` (que define `no-store`) e ao `AuthenticationFilter`, e a rotina de limpeza dos IPs, como o `LoginLimiterEvictionJob` do limitador de convite. A isenção compara o par direto por CIDR, e o par pode vir em formato IPv6 mapeado (`::ffff:172.18.x.x`). Em produção, com a lista vazia, todos os acessos do servidor do app saem de um único IP e dividem 60 por minuto. | Especificar a ordem do filtro e a limpeza periódica dos contadores, normalizar IPv4 mapeado antes de comparar com o CIDR e registrar que, em produção, o servidor dos apps precisa entrar na lista interna ou o cache de dados de 300 s é o que evita o 429. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/nfr-design/performance-design.md > Caminho da requisição, item 2 (`ETag` `"legal-<versão>"`) | O `ETag` derivado só da versão devolve 304 com conteúdo antigo se o texto de uma versão mudar sem mudar o número (revisão corrigida no catálogo). Também não está dito que o `Cache-Control` precisa ser definido antes de `checkNotModified` para que o 304 o carregue. | Derivar o `ETag` de um hash do conteúdo montado na subida e definir `Cache-Control` e `ETag` antes do teste de 304. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Rastreabilidade (`traceability.json` contra os NFR de `nfr-requirements/`) | Os 25 NFR (NFR1.1, 1.2, 3.1 a 3.8, 4.1, 6.1 a 6.6, 7.1 a 7.4, 8.1 a 8.3, 9.1) aparecem como OK com alvo | Cobertura completa; cada alvo existe nos documentos de design |
| Viabilidade no código existente | `SecurityHeadersFilter` define `no-store` antes da cadeia e o controlador o sobrescreve (há `CacheControl.noStore()` em outros controladores); `ClientIpResolver` usa `getRemoteAddr()` e não há `forward-headers-strategy` configurada; `management.endpoint.health` já tem `DEGRADED: 200` e só o grupo readiness listado; o Next 15.5 mantém o cache de dados por `fetch` em página dinâmica com nonce | O padrão de limitador, a isenção pelo par direto, a sobrescrita do cache, o ETag/304 e o indicador de saúde fora dos grupos são viáveis |

### Summary

O design cobre todos os NFR com mecanismos implementáveis no código atual e não há fraqueza de segurança que bloqueie (a isenção usa só o par direto e não dá para forjar com `X-Forwarded-For`). Os achados são Minor, sobre onde exatamente alterar o filtro de autenticação, o CORS e os apps, e sobre o `ETag`, e podem ser resolvidos na Code Generation.
