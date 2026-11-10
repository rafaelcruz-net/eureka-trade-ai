## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T07:14:02Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/infrastructure-design/infrastructure-specification.md > Deployment (Ambientes) e cicd-pipeline.md > Etapas e portões (smoke e e2e) | O desenho não diz como o ambiente de teste ganha os dados que a trava exige. `SmokeAccountSeeder` (perfil `test`) insere tenant, `app_user` e `wallet` da conta BETA_USER do smoke e não grava nenhum `terms_acceptance`. Depois que a trava existir, essa conta fica "desatualizada" e recebe 403 em toda rota fora da lista, e os specs já existentes do `verify.sh` (u1, u2, u4, u5, u7, u8, u9, u3, legal-documents) passam a quebrar. Além disso, o spec `e2e/reacceptance` e o cenário de carga precisam de uma pessoa desatualizada, e o catálogo de teste (`terms-test/reviewed`) só tem v1 e v2 (v2 vigente), sem nenhum seed ou fixture que registre um aceite de v1 para essa pessoa. Nada disso aparece no desenho nem no functional-spec. | Acrescentar ao desenho a mudança do seed do perfil de teste: a conta do smoke e as contas de carga passam a ter aceite da versão vigente (para os specs antigos continuarem verdes), e existe uma segunda pessoa semeada com aceite só de v1 (variável ou mecanismo do overlay `infra/docker-compose.test.yml`) para o spec `e2e/reacceptance` e para o cenário de carga. Registrar essa mudança em `infrastructure-specification.md` e no `traceability.json`. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/infrastructure-design/cicd-pipeline.md > Pré-requisitos (responsável do projeto) | O cenário `reacceptance` só pode rodar se `scripts/load-test.sh` o aceitar: o `case "$LOAD_SCENARIO"` termina em `*) ... exit 1` e lista só os cenários atuais (u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal). O arquivo `scripts/load/reacceptance.mjs` e o despacho em `load-test.mjs` também não são citados. A lista de pré-requisitos cobre só `verify.sh` e `load-test.yml`, então a opção nova do workflow chamaria um script que recusa o valor. | Listar em `cicd-pipeline.md` as mudanças de `scripts/load-test.sh` (novo ramo do `case`, mensagem de erro e variáveis de ambiente do cenário), de `scripts/load/load-test.mjs` e do novo `scripts/load/reacceptance.mjs`, dizendo quem as faz (código da Construction, não do responsável) e que o `workflow_dispatch` só recebe a opção depois disso. | Resolved (reviewer) |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/infrastructure-design/cicd-pipeline.md > Pré-requisitos | Os pré-requisitos do responsável não dizem a ordem nem o texto exato: o `verify.sh` real tem uma linha única do Playwright com os specs separados por espaço, e o `load-test.yml` tem `description` e `options` com a lista de cenários; o texto da mensagem de erro do `load-test.sh` também deve concordar. | Escrever o trecho exato a acrescentar (nome do spec depois de `e2e/legal-documents`; `reacceptance` depois de `legal` e na descrição) para o responsável aplicar sem adivinhar. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/infrastructure-design/infrastructure-specification.md > Dados de teste para a trava | O seeder atual retorna logo quando a carteira já existe. A frase "só insere quando não existe aceite da vigente" cobre o volume persistente, mas não diz que o aceite deve ser gravado também no caminho da conta já existente. Um implementador pode pôr o INSERT dentro do `insertIfAbsent` e deixar de semear uma conta antiga. | Na geração de código, gravar o aceite fora do retorno antecipado, com verificação própria, e cobrir com um teste do seeder rodando duas vezes sobre uma conta existente sem aceite. | New |

> R-01 Reviewer note: A seção "Dados de teste para a trava" cobre os três casos. (1) O `SmokeAccountSeeder` passa a gravar um aceite `REACCEPTANCE` da vigente (a `v2` do catálogo de teste, montado por `infra/docker-compose.test.yml`), de forma idempotente. Isso é coerente com o `insertIfAbsent` atual, que hoje não grava aceite algum. (2) O spec `e2e/reacceptance` insere a pessoa desatualizada (aceite só da `v1`) pelo `DATABASE_URL` que o `verify.sh` já passa ao Playwright. O `pg` já existe em `apps/web/package.json` e é usado por `e2e/support/audit-trail.ts`. (3) O cenário de carga usa o mesmo caminho SQL. O usuário `POSTGRES_USER` não tem UPDATE ou DELETE revogado em `terms_acceptance`, só os demais papéis, então o INSERT funciona. O catálogo de teste tem `v1` (legada) e `v2` (vigente), como o desenho afirma.

> R-02 Reviewer note: O `cicd-pipeline.md` e a especificação agora listam as mudanças em `load-test.sh` (novo caso `reacceptance`, mensagem de erro atualizada, `DATABASE_URL` exportado), em `load-test.mjs` (despacho), em `reacceptance.mjs` (novo, com teste `node --test`) e no seeder. O dono é a geração de código. O Postgres é publicado em `127.0.0.1:5432` no Compose, então o `DATABASE_URL` da máquina funciona.

> R-03 Reviewer note: O pré-requisito do `verify.sh` traz a linha exata a acrescentar, e ela confere com o fim da linha atual do Playwright (`... e2e/legal-documents`). O do `load-test.yml` traz a opção `- reacceptance` e a nova descrição, e confere com a lista e a descrição atuais.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Leitura de `SmokeAccountSeeder.java`, `docker-compose.test.yml`, `terms-test/reviewed/`, `audit-trail.ts`, `package.json`, `verify.sh`, `load-test.sh`, `load-test.yml` e da migração `0007` | Concordam com o desenho | Não rodei nenhuma ferramenta de validação automática; as referências foram conferidas nos arquivos reais. |

### Summary

Os três achados anteriores foram corrigidos, e o desenho agora entrega à trava a conta com aceite e a pessoa desatualizada que os specs e o cenário de carga precisam. Resta uma observação Minor sobre o caminho de conta já existente no seeder, que não bloqueia.
