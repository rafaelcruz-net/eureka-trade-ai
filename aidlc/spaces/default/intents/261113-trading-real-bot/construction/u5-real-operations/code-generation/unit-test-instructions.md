# Instruções de teste — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (Testing Contract: custom, comprehensive), `construction/u5-real-operations/functional-design/functional-spec.md` (S1 a S29) e `construction/u5-real-operations/nfr-requirements/tech-stack-decisions.md`.

## Ferramentas

| Onde | Ferramentas |
|---|---|
| API (`apps/api`) | JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock com respostas gravadas do GitHub e do RPC, JaCoCo com piso de 80% e ArchUnit |
| Ponta a ponta | Playwright no `verify.sh`, com o validador local e a ferramenta de mint só de teste no `local-validator.ts` |
| Relógio | injetável para 1 min, 5 min, 15 min, 2 h e 24 h |

Nenhum teste chama o GitHub real, a devnet ou a mainnet. Nenhum token do GitHub é usado em teste.

## Comandos desta Unit

Rode um Maven por vez, sempre a partir da raiz. Não exporte `MEMETRADE_AUDIT_WALLET_HMAC_KEY`.

| Parte | Comando |
|---|---|
| API, testes da U5 | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.botwallet.**.*Test,com.memetrade.botwallet.**.*IT,com.memetrade.administration.**.Real*Test,com.memetrade.administration.**.Real*IT,com.memetrade.privacy.**.*Test,com.memetrade.privacy.**.*IT,com.memetrade.riskcontrol.**.Real*Test,com.memetrade.realexecution.**.*Recheck*Test,com.memetrade.bots.**.RealBot*Test,com.memetrade.shared.config.**.*Test,com.memetrade.architecture.ArchitectureTest'` |
| API completa, com cobertura | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` |
| Alertas | `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-ops-alerts.yml` |
| Terraform | `terraform fmt -check` e `terraform validate` em `infra/terraform`, pela imagem `hashicorp/terraform:1.9.8` |
| Ponta a ponta | `bash scripts/verify.sh`, com o spec `e2e/real-ops` acrescentado pelo responsável |

## Volume (Comprehensive)

| Componente | Testes | Antes do código |
|---|---|---|
| Migração 0021 (privilégios, gatilhos, redação, CHECK, pré-condição) | 18 | sim |
| `GateEvaluator`, `ReleaseService`, `isEffective` e trava | 25 | sim |
| `CiStatus` (GitHub e configurado) | 10 | sim |
| Pausas globais, M7, barreira da U3, reconferência da U4, cancelamento | 25 | sim |
| `ReconciliationPolicy` | 20 | sim |
| `ChainPort` ampliada e `RpcBudget` | 12 | não |
| Reader, Job e `DivergenceService` (compensação) | 20 | sim |
| `WithdrawalLimit` | 10 | sim |
| `RestoreDetector` | 15 | sim |
| Teto, lista de bloqueio e comandos ao TxSigner | 15 | sim |
| Anonimização e redação | 15 | sim |
| `decision.mode` | 4 | sim |
| Rotas do administrador | 20 | não |
| Expurgo | 6 | sim |
| Configuração, guard, conferências de subida e ArchUnit | 12 | não |
| Métricas e paridade | 10 | não |
| Ponta a ponta (`real-ops`) | 1 spec | não |

## Testes obrigatórios por risco (`team.md`) tocados pela U5

- separação REAL/PAPER (reconciliação e decisão);
- deduplicação por `Idempotency-Key` nas rotas do administrador;
- limites e controles de risco (teto, lista de bloqueio, kill switch e pausas na barreira);
- pausa impede novas operações;
- ledger sem edição: a correção só por lançamento compensatório;
- REAL desligado por padrão;
- isolamento entre tenants nos trabalhos em segundo plano;
- anonimização sem o endereço em tabela ou log;
- saque só para a carteira principal, disponível com o kill switch, sem liberação, com divergência (limitado) e sem o novo aceite.

## Cobertura

80% de linhas na API, sem nenhuma exclusão nova.
