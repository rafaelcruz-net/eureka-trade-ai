## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T11:00:50Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/infrastructure-design/infrastructure-specification.md > Dados de teste > Cenário de carga `data-requests` | O cenário mede o p95 das rotas administrativas (1 s), mas nada no desenho dá ao cenário uma sessão de administrador. `scripts/load-test.sh` e `scripts/load/load-test.mjs` não geram carteira de administrador, não exportam `ADMIN_WALLET_ADDRESSES` (hoje só o `verify.sh` faz isso, e o overlay de teste lê o valor dele) e não fazem login administrativo. Sem isso, as rotas P2 respondem 401 ou 403 e a meta de 1 s não pode ser medida. | Acrescentar à especificação (e à lista de mudanças do `load-test.sh`) a geração da carteira de administrador efêmera, a exportação de `ADMIN_WALLET_ADDRESSES` antes do `up` e o login administrativo no `data-requests.mjs`, ou declarar que o cenário reaproveita outro mecanismo existente. | New |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/infrastructure-design/infrastructure-specification.md > Dados de teste > "o perfil `loadtest` semeia 1000 pedidos" | A semeadura não está desenhada. Faltam: o componente que semeia (classe no pacote `loadtest` do Privacy), a propriedade e a variável (`LOADTEST_*`) que a liga em `application-loadtest.yml` e em `load-test.sh` (todos os outros cenários desligam seus drivers por padrão), de quem são os pedidos, e o estado deles. O índice único parcial `uq_open_request_per_type` (`tenant_id, user_id, type` dos abertos com `user_id` não nulo) impede 1000 pedidos abertos do mesmo tipo para as poucas contas do cenário; o desenvolvedor precisa adivinhar se usa 1000 pessoas, vários tipos, `user_id` nulo ou estados fechados. Isso muda diretamente o que a lista por `(status, due_at)` e a meta de p95 representam. | Definir a semeadura: componente, propriedade e variável (desligada por padrão), distribuição (pessoas, tipos, estados, `due_at`) compatível com o índice único e com o filtro de `status` que a rota mede, e a regra de não semear fora de `test,loadtest`. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/infrastructure-design/cicd-pipeline.md > Pré-requisitos (responsável do projeto) 1 e 2 | Os dois pré-requisitos supõem que as mudanças da U3 do responsável já foram aplicadas. No repositório, `scripts/verify.sh` termina em `... e2e/legal-documents` (sem `e2e/reacceptance`) e a descrição do `load-test.yml` e suas `options` ainda não trazem `reacceptance`. Aplicados ao pé da letra sobre o estado atual, o spec novo ficaria depois de um spec que não está na lista, e a descrição citaria uma opção ausente. | Escrever os pré-requisitos como ordem explícita (primeiro os da U3, depois os da U4) ou dar a linha final completa dos dois arquivos. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/infrastructure-design/infrastructure-specification.md > Dados de teste > contas do cenário | O caso `u3` gera `SMOKE_INVITE_CODES` dentro do próprio ramo em `load-test.sh`, e o segundo `case` zera as variáveis de carga do ledger. A especificação diz que `data-requests` reaproveita os convites do `u3`, mas não diz que o ramo novo precisa gerar os códigos, definir `USERS` e a duração. | Listar essas variáveis na mudança do `load-test.sh`, junto com o limite `requests-per-hour` elevado no perfil `loadtest`. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/infrastructure-design/monitoring-design.md > Alerts > `DATA_REQUEST_DUE_SOON` | O alerta é descrito como "visto na lista do app do administrador", mas nenhuma métrica, saúde ou consulta calcula pedidos a 3 dias ou menos do prazo; não há condição verificável nem cobertura na `traceability.json`. | Ou definir a fonte (por exemplo, um gauge ou o filtro da lista administrativa com limiar), ou reclassificar como indicação de tela, não alerta. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/infrastructure-design/infrastructure-specification.md > Deployment > Armazenamento | A migração `0014-data-requests.sql` não menciona o registro no `db.changelog-master.yaml` nem os privilégios das tabelas novas para os papéis da aplicação e do expurgo. O pool 18 para 21 também não cita o ajuste dos testes que fixam o valor como item do `RuntimeSettingsGuard` na lista de verificação. | Acrescentar uma linha de registro no changelog mestre e de concessões (ou citar o padrão de `0012` e `0013`). | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Leitura de `docker-compose.yml`, `.env.example`, `application.yml` | `SCHEDULER_POOL_SIZE` padrão 18 e `MIN_SCHEDULER_POOL = 18` | Confirmam o ponto de partida do pool 21 e a lista de arquivos que mudam juntos |
| Leitura de `scripts/load-test.sh` e `scripts/load/` | Nenhuma carteira ou login de administrador; nenhum driver `data-requests` | Base dos dois achados Major |
| Leitura de `scripts/verify.sh` e `load-test.yml` | Sem `e2e/reacceptance` nem opção `reacceptance` | Base do achado Minor dos pré-requisitos |
| Leitura de `changes/` | Última migração `0013`; `0014` livre | Numeração consistente |

### Summary

O desenho local é coerente com o NFR design (pool, jobs, travas, métricas, tabelas no formato exigido), mas o cenário de carga não é implementável como escrito: falta a sessão de administrador e a semeadura dos 1000 pedidos está sem definição. São dois Major, dentro do limite para READY; devem ser corrigidos antes da geração de código.
