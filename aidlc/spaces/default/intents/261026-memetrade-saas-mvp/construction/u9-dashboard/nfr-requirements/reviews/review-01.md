## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-06T13:43:31Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-requirements/security-requirements.md > requisito NFR2.1 e tabela "Ameaças consideradas" (linha "Muitas abas ou reconexões esgotando o servidor (D)") | O NFR2.1 de segurança apresenta o teto de 5 canais por pessoa como controle firme e a mitigação de negação de serviço depende dele. O teto não existe no servidor: `DashboardMarkPoller.open` só cria o `SseEmitter` e registra o canal, sem contar canais por pessoa nem recusar o 6º. A Unit é `ui`, sem código de servidor, e o canal é da U5. O `performance-requirements.md` trata o mesmo NFR2.1 como proposta a "confirmar ou retirar na NFR Design", e o `tech-stack-decisions.md` repete isso. O mesmo ID tem dois textos com graus de compromisso diferentes, e a verificação indicada ("detalhe em performance-requirements") só aponta para a outra proposta. Hoje a ameaça D fica sem controle verificável. | Escolher uma posição única. Ou registrar o teto como requisito entregue pela U5 (dono, mudança no canal C7, recusa definida, por exemplo 429 com código estável, e teste de 6 canais na U5), ou retirá-lo e mitigar a ameaça de outro modo. Manter um só NFR2.1 (ou dar IDs distintos) e remover da tabela de ameaças a dependência de um controle ainda inexistente. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-requirements/security-requirements.md > NFR6.2 | O teste estático "busca no código-fonte de `apps/web/src`" por `EventSource` falha por falsos positivos, porque comentários do próprio código e dos testes citam o termo (por exemplo, a regra "nunca EventSource" em comentários). | Definir o escopo da busca: só código de produção, ignorando comentários e arquivos `*.test.*`, ou usar uma regra de lint (`no-restricted-globals`) em vez de busca de texto. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-requirements/security-requirements.md > NFR6.1 | "Conferir os armazenamentos vazios depois de abrir o painel" conflita com o `acceptance-store.ts` da U3, que grava marcas de aceite em `sessionStorage`, e o critério pode falhar ou ser instável conforme a ordem dos testes. | Reescrever o critério para "nenhuma chave com token ou valor de token em `localStorage`, `sessionStorage` e IndexedDB", em vez de armazenamento vazio. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-requirements/security-requirements.md > NFR6.6 | O requisito cobre só o `401` na abertura. A BR5.5 também trata o evento `auth.expired` e o `401` na reabertura logo depois da renovação, e esses dois caminhos ficam sem critério verificável de não entrar em laço. | Acrescentar os casos `auth.expired` e `401` repetido após renovação ao NFR6.6, com o teste correspondente do cliente do canal. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-requirements/traceability.json > NFR9 (N/A) | A justificativa diz que o cliente REST já manda `X-Correlation-Id` em toda requisição (confirmado em `client.ts`, linha 83). O canal C7 é aberto com `fetch` próprio da U9, e nenhum requisito exige que essa abertura leve o identificador de correlação, que o NFR9 pede em 100% das requisições. | Acrescentar um requisito (ou critério de teste) de que a abertura e a reabertura do canal enviam `X-Correlation-Id`, ou fazer o leitor do canal reutilizar o cliente existente. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u9-dashboard/nfr-requirements/performance-requirements.md > NFR1.1 | A medição diz "gravando mudanças pela API", mas o Q5 afirma que não há rota que grave ordem. Só a mudança de estado do bot é acionável pela API; o atraso de ordem depende dos acionadores Java do perfil `loadtest`, que o texto não nomeia. | Dizer quais acionadores medem o atraso de ordem (perfil `loadtest`) e quais medem o de estado de bot. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| aidlc engine sensor-traceability (nfr-requirements) | `{"pass":true,"gaps":[],"orphans":[],"missing_from_table":[],"missing_from_upstream_ids":[],"invalid_entries":[],"invalid_targets":[],"findings_count":0}` | NFR1 a NFR14 cobertos ou justificados como N/A; sem lacunas estruturais. |
| Verificação no código existente | `DashboardMarkPoller.open` sem limite por pessoa; `connect-src` coberto por `middleware.test.ts`; `formatDecimal` sem `Number`/`parseFloat`; `jest.config.ts` com piso de 80% de linhas; `client.ts` manda `X-Correlation-Id` | Os requisitos de CSP, decimal, cobertura e correlação do REST são verificáveis hoje; o teto de 5 canais não existe (finding Major). |

### Summary

Os quatro artefatos são coerentes com as respostas confirmadas e com as regras BR da Unit, e a rastreabilidade passa sem lacunas. A preocupação principal é o teto de 5 canais por pessoa, tratado como controle de segurança firme no `security-requirements.md`, mas ainda inexistente no servidor e só proposto no `performance-requirements.md`. Há ainda cinco ajustes menores de verificabilidade. Com um Major e nenhum Critical, o veredito é READY.
