# Instruções de teste — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (Testing Contract: custom, comprehensive), `construction/u3-real-risk/functional-design/functional-spec.md` (cenários S1 a S17) e `construction/u3-real-risk/nfr-requirements/tech-stack-decisions.md`.

## Ferramentas

- **Backend (`apps/api`):** JUnit 5, AssertJ, Testcontainers (PostgreSQL) para trava, repetição, privilégios e trabalhos, JaCoCo com piso de 80% e ArchUnit.
- **Relógio:** injetável para 00:00 UTC, 60 s e 5 min.
- **Rede:** nenhuma.

## Comandos desta Unit

Só os testes da U3, um Maven por vez. Não exporte `MEMETRADE_AUDIT_WALLET_HMAC_KEY`.

| Parte | Comando |
|---|---|
| Backend, testes da U3 | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.riskcontrol.real.**.*Test,com.memetrade.riskcontrol.real.**.*IT,com.memetrade.botwallet.**.RealState*Test,com.memetrade.ledger.**.RealLedger*IT,com.memetrade.marketdata.**.ReferencePrices*IT,com.memetrade.architecture.ArchitectureTest'` |
| Alertas | `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-risk-alerts.yml` |
| Terraform | `terraform fmt -check` e `terraform validate` em `infra/terraform`, pela imagem `hashicorp/terraform:1.9.8` no Docker |

## Volume (Comprehensive)

| Componente | Testes | Antes do código |
|---|---|---|
| Leituras M1, M2 e M4 | 12 | sim |
| `RealRiskPolicy` (S1 a S17 e variações, cada regra) | 40 | sim |
| Migração (privilégios e gatilhos) | 10 | sim |
| `WalletLock`, leitor e gravação | 12 | sim |
| `TransactionalRealRiskGate` (concorrência, repetição, ciclo desfeito, tenant, REAL/PAPER) | 15 | sim |
| Trabalhos F3, F4 e F5 | 12 | sim |
| Expurgo | 8 | sim |
| `RealRiskLimits` e subida | 10 | não |
| Métricas | 6 | não |
| ArchUnit (regras novas e mudadas) | 6 | não |
| Paridade dos alarmes | 4 | não |
| Medição informativa | 2 | não |

## Testes obrigatórios por risco (`team.md`) tocados pela U3

- violação de limites autorizados e controles de risco;
- pausa do bot impede novas operações;
- dado de mercado ausente vira `unknown`, nunca zero;
- separação REAL/PAPER;
- isolamento entre tenants;
- deduplicação.

## Cobertura

80% de linhas no backend, sem nenhuma exclusão nova.
