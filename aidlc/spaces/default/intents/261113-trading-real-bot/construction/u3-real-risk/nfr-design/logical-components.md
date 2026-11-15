# Componentes lógicos — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/functional-design/` (BR1.1 a BR7.3, F1 a F6), `construction/u3-real-risk/nfr-requirements/` e `nfr-design-questions.md` (Q1 a Q6).

| Componente | Pacote | Processo | Papel |
|---|---|---|---|
| `RealRiskGate` (M5) | `riskcontrol.api` | os dois | `evaluate`, `handOver` (pega a trava da carteira por dentro), `blockToken`, `unblockToken`; `OrderIntent`, `GateDecision` |
| `RealRiskPolicy` | `riskcontrol.real.domain` | os dois | regra pura: recebe `RealGateInputs` e devolve a decisão; sem I/O nem relógio próprio (Q4) |
| `RealGateInputs` | `riskcontrol.real.domain` | os dois | foto imutável das entradas: estado, fonte, avaliação, bloqueio, preços, posições, saldo, reservas, contagem, foto do dia, fluxos, limites, instante |
| `RealRiskLimits` | `riskcontrol.real.domain` | os dois | limites da configuração, conferidos na subida (U3-NFR8.3) |
| `TransactionalRealRiskGate` | `riskcontrol.real.infra` | worker (`evaluate` do ciclo), API (liquidação, lista de bloqueio) | `MANDATORY`; pega a trava, monta as entradas, chama a política e grava |
| `WalletLock` | `riskcontrol.real.infra` | os dois | `pg_try_advisory_xact_lock(<constante>, hashtext(botWalletId))` a cada 20 ms por até 1 s (Q2) |
| `RealGateInputsReader` | `riskcontrol.real.infra` | os dois | lê M1, M2, M4 (em lote), avaliação, lista de bloqueio, reservas, contagem e foto do dia |
| `JdbcRealRiskStore` | `riskcontrol.real.infra` | os dois | `real_risk_evaluation` e `real_risk_reservation` com `ON CONFLICT (decision_id)` (Q5), `real_risk_day`, `real_blocked_token` |
| `RealRiskJobs` | `riskcontrol.real.infra` | worker | F3 (5 min), F4 (1 min), F5 (1 min), ShedLock, batimento por trabalho |
| `RealRiskPurgeJob` | `riskcontrol.real.infra` | API | expurgo diário de 13 meses às 04:00 UTC, depois do expurgo PAPER das 03:30, com nome próprio no ShedLock, pelo pool `risk_retention` de uma conexão |
| `RealRiskMetrics` | `riskcontrol.real.infra` | os dois | métricas U3-NFR5.1 a U3-NFR5.8; o medidor de reservas só no worker, atualizado por tarefa própria a cada 30 s, fora da coleta |
| `RealStateQuery` e `RealPauses` (M1) | `botwallet.api` | os dois | acrescentados pela U3: `readRealState` (com `pausedSince`), `pauseUser`, `resumeUser` |
| `RealLedger` (M2) | `ledger.api` | os dois | ganha `positions`, `flowsSince` e as reservas separadas por tipo em `RealBalance` |
| `MarketDataQuery` (M4) | `marketdata.api` | os dois | ganha `referencePrices(Set<TokenId>)` (Q3) |

**Desvio de Q1 e Q6:** o expurgo roda na API, não no worker, porque o pool `risk_retention` e o expurgo PAPER já ficam nela. A regra ArchUnit que hoje dá esse pool só ao `RiskEvaluationPurgeJob` passa a aceitar também o `RealRiskPurgeJob`.

## Regras ArchUnit que mudam

| Regra | Mudança |
|---|---|
| `riskcontrolDependsOnlyOnThePublicInterfacesOfOtherModules` | passa a aceitar `botwallet.api`, `ledger.api` e `riskassessment.api` |
| Regra do pool `risk_retention` (só o `RiskEvaluationPurgeJob`) | passa a aceitar também o `RealRiskPurgeJob` |
| Regra do `BigDecimal.ZERO` | vale para `riskcontrol.real` sem mudança: a política usa lamports em `long` e nunca usa zero como substituto de `unknown` |
| Novas | fronteira de `riskcontrol.real` (U3-NFR8.1), chamadores de `blockToken` (U3-NFR6.2), `botwallet` sem `riskcontrol` (U3-NFR4.7) |

## Falhas e raio de impacto

| Falha | Impacto | Contenção |
|---|---|---|
| Banco lento ou trava presa | a decisão do bot fica para o próximo ciclo | trava de no máximo 1 s; erro técnico, nunca aprovação (U3-NFR4.1, U3-NFR4.8) |
| M1, M2, M4 ou avaliação com erro | nenhuma ordem REAL sai | falha fechada; `real_risk_errors_total{dependency}`; alerta |
| A U4 não consome a decisão | a reserva fica presa | vence em 5 min; alerta de reserva vencida |
| Ciclo desfeito | a decisão e a pausa somem | o F4 refaz a pausa em até 1 min |
| Worker parado | as fotos, a pausa e o vencimento atrasam | batimento por trabalho; alerta de trabalho parado; a compra grava a foto se faltar |

## Recursos compartilhados

- **Banco:** as quatro tabelas da U3, a trava de transação, as leituras dos módulos M1, M2 e M4 e a saída de auditoria que já existe.
- **Rede:** nenhuma.
