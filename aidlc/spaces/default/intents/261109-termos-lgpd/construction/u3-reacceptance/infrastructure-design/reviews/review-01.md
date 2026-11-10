## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T07:11:08Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/infrastructure-design/infrastructure-specification.md > Deployment (Ambientes) e cicd-pipeline.md > Etapas e portões (smoke e e2e) | O desenho não diz como o ambiente de teste ganha os dados que a trava exige. `SmokeAccountSeeder` (perfil `test`) insere tenant, `app_user` e `wallet` da conta BETA_USER do smoke e não grava nenhum `terms_acceptance`. Depois que a trava existir, essa conta fica "desatualizada" e recebe 403 em toda rota fora da lista, e os specs já existentes do `verify.sh` (u1, u2, u4, u5, u7, u8, u9, u3, legal-documents) passam a quebrar. Além disso, o spec `e2e/reacceptance` e o cenário de carga precisam de uma pessoa desatualizada, e o catálogo de teste (`terms-test/reviewed`) só tem v1 e v2 (v2 vigente), sem nenhum seed ou fixture que registre um aceite de v1 para essa pessoa. Nada disso aparece no desenho nem no functional-spec. | Acrescentar ao desenho a mudança do seed do perfil de teste: a conta do smoke e as contas de carga passam a ter aceite da versão vigente (para os specs antigos continuarem verdes), e existe uma segunda pessoa semeada com aceite só de v1 (variável ou mecanismo do overlay `infra/docker-compose.test.yml`) para o spec `e2e/reacceptance` e para o cenário de carga. Registrar essa mudança em `infrastructure-specification.md` e no `traceability.json`. | New |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/infrastructure-design/cicd-pipeline.md > Pré-requisitos (responsável do projeto) | O cenário `reacceptance` só pode rodar se `scripts/load-test.sh` o aceitar: o `case "$LOAD_SCENARIO"` termina em `*) ... exit 1` e lista só os cenários atuais (u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal). O arquivo `scripts/load/reacceptance.mjs` e o despacho em `load-test.mjs` também não são citados. A lista de pré-requisitos cobre só `verify.sh` e `load-test.yml`, então a opção nova do workflow chamaria um script que recusa o valor. | Listar em `cicd-pipeline.md` as mudanças de `scripts/load-test.sh` (novo ramo do `case`, mensagem de erro e variáveis de ambiente do cenário), de `scripts/load/load-test.mjs` e do novo `scripts/load/reacceptance.mjs`, dizendo quem as faz (código da Construction, não do responsável) e que o `workflow_dispatch` só recebe a opção depois disso. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/infrastructure-design/cicd-pipeline.md > Pré-requisitos | Os pré-requisitos do responsável não dizem a ordem nem o texto exato: o `verify.sh` real tem uma linha única do Playwright com os specs separados por espaço, e o `load-test.yml` tem `description` e `options` com a lista de cenários; o texto da mensagem de erro do `load-test.sh` também deve concordar. | Escrever o trecho exato a acrescentar (nome do spec depois de `e2e/legal-documents`; `reacceptance` depois de `legal` e na descrição) para o responsável aplicar sem adivinhar. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| traceability.json (leitura manual) | Os 7 IDs listados existem no `traceability.json` do NFR design; estão todos como OK | Cobertura coerente com o NFR design; NFR2.5 (503 fechado) e NFR4.2 não têm alvo de infraestrutura, o que é aceitável |
| Formato das tabelas do estágio | Deployment, Infrastructure Services, Shared Infrastructure, Metrics, Alerts e SLIs seguem os cabeçalhos exigidos | Conforme |
| Migração 0013 | Último arquivo é `0012-retention.sql`; a restrição única nova é aditiva | Numeração e padrão expand-contract coerentes |

### Summary

O desenho de migração, métricas e portões é coerente com o NFR design, mas não resolve o ambiente de teste: o seed do smoke não tem aceite de termos (a trava quebraria os specs atuais) e não há pessoa desatualizada para o spec e o cenário de carga. Falta também o despacho do cenário em `scripts/load-test.sh`.
