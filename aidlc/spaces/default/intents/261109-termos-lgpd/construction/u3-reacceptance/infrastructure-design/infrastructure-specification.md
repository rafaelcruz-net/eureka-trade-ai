# Especificação de infraestrutura — U3 `u3-reacceptance`

Conversa: português (Brasil). Implementa o desenho de `construction/u3-reacceptance/nfr-design/` no ambiente local existente, com o desenho para a nuvem. As escolhas seguem as recomendações (A), pelo pedido do responsável do projeto.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Compute | Contêineres existentes (`api` e `web`); nenhum serviço novo. A trava roda só no processo `api` (`@ProcessRole(API)`) | a U3 é código no monólito e no app |
| Rede | Sem mudança | a trava e o aceite são rotas existentes do convidado |
| Armazenamento | PostgreSQL existente. A migração `0013-reacceptance.sql` acrescenta ou relaxa: `invite_id` aceita nulo, coluna `source`, dois `CHECK` e a restrição única `(user_id, terms_version)` | expand-contract (NFR5.1) |
| Ambientes | Local (Compose e overlay de teste); nuvem só desenhada | sem conta de nuvem |
| IaC | Compose versionado, sem mudança | nenhuma variável nova obrigatória |
| Tamanho | Sem mudança | uma consulta indexada por requisição |

## Configuração nova

| Propriedade | Padrão | Nuvem (desenho) |
|---|---|---|
| `memetrade.terms.reacceptance.allowed-prefixes` | `/api/v1/legal/,/api/v1/terms/acceptances,/api/v1/account,/api/v1/auth/logout,/api/v1/auth/refresh` (no `application.yml`; a U4 acrescenta `/api/v1/privacy/`) | o mesmo arquivo; não vira variável de ambiente |

## Dados de teste para a trava

| Ambiente | Situação hoje | Mudança |
|---|---|---|
| `verify.sh` (perfil `test`) | O `SmokeAccountSeeder` cria a conta `BETA_USER` do smoke sem nenhum aceite. Com a trava, todos os specs existentes receberiam `403` | O seeder passa a gravar, para essa conta, um aceite `REACCEPTANCE` da vigente efetiva (sem convite, como permite o `CHECK` cruzado). Ele só insere quando não existe aceite da vigente, então pode rodar mais de uma vez. A gravação do aceite fica fora do retorno antecipado de hoje, que sai quando a carteira já existe: uma subida com a conta já criada também grava o aceite que faltar. Um teste roda o seeder duas vezes e confere que fica um único aceite. Assim os specs atuais continuam verdes |
| Spec `e2e/reacceptance` | Não existe pessoa desatualizada | O spec gera uma carteira de teste no próprio código de teste e usa o `DATABASE_URL` que o `verify.sh` já passa ao Playwright (o mesmo do `e2e/support/audit-trail.ts`, com o `pg` do `apps/web`). Por ele, insere uma conta `BETA_USER` com aceite só da `v1` do catálogo de teste; a vigente desse catálogo é a `v2`. Depois o spec entra com essa carteira, recebe a tela de novo aceite, aceita e volta. Não precisa de nenhuma variável nova no `verify.sh` |
| Cenário de carga `reacceptance` | `scripts/load-test.sh` recusa cenários fora da lista | Mudanças na geração de código: `load-test.sh` ganha o caso `reacceptance`, com a mensagem de erro atualizada, e exporta `DATABASE_URL` com as credenciais efêmeras que ele mesmo gera. `scripts/load/load-test.mjs` despacha para `scripts/load/reacceptance.mjs` (novo, com teste `node --test`). O cenário cria 20 contas desatualizadas pelo mesmo caminho SQL do spec e, para cada uma, faz: entrar, chamar uma rota bloqueada (`403`), aceitar e chamar a rota de novo (`200`). Ele reprova com p95 do aceite acima de 500 ms ou da trava acima de 5 ms, lida do histograma no `/actuator/prometheus` |

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL | database | migração `0013`; restrição única `uq_terms_acceptance_user_version`, que também serve de índice para a trava | rollback do `DROP NOT NULL` só antes do primeiro novo aceite |
| Prometheus (actuator) | metrics | contadores e timer da U3; o timer com histograma de percentis | sem serviço novo |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| Lista de prefixos liberados da trava | U3 (Identity) | U4 (acrescenta `/api/v1/privacy/`) | só a configuração e o teste de rotas |
| `terms_acceptance` | Identity | U5 (mantém os aceites sem vínculo na anonimização) | só pelo Identity |
