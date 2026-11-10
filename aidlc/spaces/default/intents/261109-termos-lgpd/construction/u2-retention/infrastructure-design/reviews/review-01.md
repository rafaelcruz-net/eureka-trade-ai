## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T03:54:24Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/infrastructure-design/cicd-pipeline.md > Pré-requisitos (responsável do projeto); infrastructure-specification.md > Configuração nova | A variável vira obrigatória no `docker-compose.yml` (`${MEMETRADE_AUDIT_WALLET_HMAC_KEY:?}`), mas só `verify.sh` e o passo `Compose config` do `ci.yml` são tratados. `scripts/load-test.sh` e `scripts/perf-audit.sh` também rodam `docker compose -f docker-compose.yml -f infra/docker-compose.test.yml`, e cada um gera os próprios segredos efêmeros (`load-test.sh` linhas 20 a 26). Sem a chave exportada, ambos falham na interpolação do Compose. O portão de carga manual da tabela de etapas (cenários `u1` e `u3`, via `load-test.sh`) fica inexecutável, e o `perf-audit.sh` também. | Acrescentar o `load-test.sh` e o `perf-audit.sh` à lista de arquivos que geram e exportam a chave efêmera. Dizer em cada pré-requisito quem edita cada arquivo. O `load-test.sh` e o `perf-audit.sh` não são do dono exclusivo (só `verify.sh` e os workflows são). Conferir que `.github/workflows/load-test.yml` não precisa de nada além disso. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/infrastructure-design/cicd-pipeline.md > Pré-requisitos, item 1 | No `verify.sh`, a geração dos segredos efêmeros fica dentro de `if [ ! -f .env ]` (linhas 46 a 59). Se o pré-requisito for aplicado ali, uma execução local com `.env` existente (o caso do responsável do projeto) sobe sem a chave e falha. O texto também não diz que `.env` precisa ganhar a chave. A mesma regra vale para o fluxo "dev" do cabeçalho do Compose (`up postgres db-bootstrap db-migrate`), porque a interpolação obrigatória vale para o arquivo inteiro. | Especificar que o `verify.sh` gera a chave também quando `.env` existe e a variável está vazia, por exemplo com `${MEMETRADE_AUDIT_WALLET_HMAC_KEY:-$(...)}` fora do bloco. Registrar como passo único que o `.env` local precisa da chave. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/infrastructure-design/infrastructure-specification.md > Deployment, linha Tamanho | A linha diz que o pool do agendador passa de 16 para 18, mas não lista onde isso muda na infraestrutura. O `docker-compose.yml` repassa `SCHEDULER_POOL_SIZE:-16`, e o `.env.example` documenta "16 to 32 [16]" com a contagem de jobs. O `nfr-design/scalability-design.md` cobre só `application.yml` e `RuntimeSettingsGuard`. Se o default do Compose ficar em 16, a API sobe com menos threads que jobs, ou recusa a subida depois que o mínimo virar 18. | Listar o default de `SCHEDULER_POOL_SIZE` no Compose (18), o intervalo e o comentário no `.env.example` e o teste do Compose entre os itens a alterar na geração de código. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/infrastructure-design/monitoring-design.md > Alerts, `PURGE_STALE` | O alerta depende de `DEGRADED` nos indicadores, mas o ambiente local não tem transporte de alerta. A tabela diz "saúde e log", o que na prática exige alguém olhar. A métrica `memetrade_purge_last_success_seconds` já existe, mas a tabela dá o limite em horas e não em termos de PromQL. | Escrever a condição de `PURGE_STALE` também como expressão da métrica (por exemplo, `time() - memetrade_purge_last_success_seconds > 172800`), para o desenho da nuvem poder usá-la. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Leitura de `docker-compose.yml`, `infra/docker-compose.test.yml`, `.env.example`, `verify.sh`, `ci.yml`, `application.yml` e migrações | A última migração é a `0011`, então a `0012` é livre. O login da aplicação já tem DELETE nas tabelas novas pelos privilégios padrão. O `ci.yml` tem o passo `Compose config` que o pré-requisito 2 descreve | O desenho é implementável. A lacuna é a lista de scripts que precisam da chave (Major) |
| Formato das tabelas do estágio | Deployment, Infrastructure Services, Shared Infrastructure, Metrics, Alerts e SLIs seguem os cabeçalhos exigidos | Conforme |

### Summary

O desenho é coerente com o NFR design (migração só aditiva, variável obrigatória, rollback por imagem anterior, nenhum serviço novo). O ponto principal é a lista incompleta de arquivos que precisam gerar a chave nova: `load-test.sh` e `perf-audit.sh` quebram com a variável obrigatória. Com um Major e três Minor, o veredito é READY, mas o Major deve ser corrigido antes da geração de código.
