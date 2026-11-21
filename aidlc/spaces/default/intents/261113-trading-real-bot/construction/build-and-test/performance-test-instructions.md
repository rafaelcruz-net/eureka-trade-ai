# Instruções de testes de desempenho — intent `261113-trading-real-bot`

As Units definem metas numéricas de tempo e volume (NFR3 de cada Unit). `team.md` não põe portão de desempenho na CI: as medições são informativas e uma meta não atingida antes da mainnet volta para o NFR Design. Este arquivo separa o que já é medido nos testes de integração do que exige teste de carga ou ambiente implantado.

## 1. Medições informativas que já rodam na CI

| Meta | Teste | Valor no run 44765239890 |
|---|---|---|
| U3-NFR3.1 p95 da compra ≤ 150 ms | `RealRiskMeasurementIT` | 6 ms (21 carteiras, 10 posições, 4 avaliações/s) |
| U3-NFR3.2 p95 da venda ≤ 30 ms | `RealRiskMeasurementIT` | 2 ms |
| U3-NFR3.4 trabalhos F3/F4 ≤ 5 s | `RealRiskMeasurementIT` | 120 ms e 350 ms |
| U4-NFR3.1 decisão → envio p95 ≤ 3 s | `RealExecutionMeasureIT` | 13 ms (rede e Jupiter falsas) |
| U4-NFR3.2 espera na fila p95 ≤ 500 ms | `RealExecutionMeasureIT` | 3 ms |
| U7-NFR3.3 exportação ≤ 10 s | `FiscalExportIT.twentyThousandEntriesAreExportedInAtMostTenSeconds` | passou (asserção de limite) |
| U7-NFR3.4 criação até a sessão ≤ 3 s, 20 vezes | `ConditionalCreationIT` | passou (asserção de limite) |
| U1-NFR3.5 depósito visível ≤ 45 s | e2e `real-bot-wallet` | 41,0 s no job `e2e` e 41,1 s no `smoke` (duração total do teste; margem de cerca de 4 s; no run de 2026-11-18, 27,1 s) |

Para ver os valores: `gh run view <run> --log --job <backend> | grep -E "informative measurement"`.

## 2. Teste de carga do REAL (ainda não existe)

O `load-test.yml` (manual, `workflow_dispatch`) e o `scripts/load-test.sh` só têm cenários do MVP PAPER (`u1`, `u3`, `u4`, `u5`, `u6`, `u7`, `u7-jobs`, `growth`, `legal`, `reacceptance`, `data-requests`). Nenhum cenário cobre as rotas e os trabalhos do REAL, e o workflow nunca rodou neste repositório. As metas abaixo ficam `Unverified`, com dono `performance-validation`:

| Meta | Perfil de carga pedido | Como medir |
|---|---|---|
| U1-NFR3.1 leituras da carteira p95 ≤ 300 ms | 1.000 carteiras, 10 leituras/s, 10 min (U1-NFR3.9) | histograma HTTP do Spring |
| U1-NFR3.2 pedido de saque p95 ≤ 500 ms | 1 saque/s | histograma HTTP |
| U1-NFR3.3 pedido → envio ≤ 5 s | validador local; de novo na devnet com KMS real | métrica do worker |
| U1-NFR3.4 ativação ≤ 3 s | KMS simulado; de novo com KMS real | temporizador da ativação |
| U1-NFR3.6, U1-NFR3.8, U1-NFR3.9 | 1.000 carteiras, saques concorrentes de carteiras diferentes | latência e ausência de bloqueio entre carteiras |
| U2-NFR3.1 a NFR3.3 rodada ≤ 10 s, descoberta e detalhes ≤ 5 s | 50 tokens; WireMock com latência | `marketdata_round_seconds` |
| U2-NFR3.4 sem piora do scanner | cenário `u4` existente do `load-test.yml` | p95 do scanner antes e depois |
| U4-NFR3.4 `GET` de ordens e `/pnl` p95 ≤ 300 ms | 21 pessoas, 50 itens | histograma HTTP |
| U5-NFR3.1 a NFR3.4 | 21 carteiras, rodada de 15 min, rotas do administrador | `real_reconciliation_seconds`, `real_ops_job_seconds`, histograma HTTP |

### Como acrescentar o cenário (para o `performance-validation`)

1. Criar `scripts/load/real.mjs` com os perfis acima, no mesmo formato de `u5-slices.mjs` e `u6-evaluate.mjs` (relógio do perfil `loadtest`, p95 calculado pelo próprio driver).
2. Acrescentar a opção `real` ao `load-test.yml` e ao `scripts/load-test.sh` (só o responsável do projeto altera workflows).
3. Rodar `gh workflow run load-test.yml -f scenario=real` e guardar o relatório como artefato.

## 3. Metas que exigem ambiente implantado

| Meta | Dono |
|---|---|
| U1-NFR4.1 disponibilidade de 99,5% em 30 dias | `performance-validation` |
| U2-NFR4.1 preço com até 60 s em 99% dos minutos do mês | `performance-validation` |
| U1-NFR3.3 e NFR3.4 remedidos com o KMS real na devnet | `performance-validation` |

## 4. Regressão

Sem portão automático (`team.md`, Desempenho). As medições informativas da seção 1 aparecem no log de toda execução do job `backend`; uma piora acima de 10% em relação ao último valor registrado deve ser anotada como defeito.
