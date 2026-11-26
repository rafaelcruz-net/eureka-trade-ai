# Instruções dos testes de desempenho — intent `261121-paper-real-unico`

Existem metas numéricas de desempenho nos NFRs das quatro Units, então este arquivo vale mesmo com a Test Strategy `Standard`. Há dois tipos de medida: testes de integração de medida (rodam dentro do `mvnw verify`, registram no log e só falham quando uma leitura falha) e o cenário de carga do workflow **manual** `load-test` (portão). Nenhuma meta é decidida por um portão de tempo na CI automática, porque um portão de tempo num runner compartilhado seria instável (`team.md`, Testes instáveis).

## 1. Metas e onde cada uma é medida

| Meta | Valor | Medida | Portão |
|---|---|---|---|
| U1-NFR1.1 a NFR1.4 (C4, C2, C3, C1) | p95 ≤ 1.000 ms | `ModePnlLatencyIT` (100 pedidos) | registro; o portão de C4 é o da U2 |
| U2-NFR1.1 (C4 com o bloco REAL da U2) | p95 ≤ 1.000 ms com 20 pessoas simultâneas | cenário `pnl-by-mode` | **sim**, no `load-test` manual (NFR1.25) |
| U2-NFR1.2 (`GET /api/v1/real/pnl`) | p95 ≤ 1.000 ms | cenário `pnl-by-mode` | registro no relatório |
| U2-NFR1.3 (atribuição `PNL`) | p95 ≤ 500 ms dentro do bloco de 800 ms | cenário `pnl-by-mode`, `pnl_real_attribution_read_seconds{path="PNL"}` | **sim**, no `load-test` |
| U2-NFR1.4 (atribuição `GATE` e `CYCLE`) | p95 ≤ 250 ms | `RealAttributionMeasurementIT` | decisão nesta etapa |
| U2-NFR1.5 (barreira REAL, compra e venda) | compra: teto 500 ms (fundador, 2026-11-23) e limite relativo pela comparação intercalada; venda: 30 ms | `RealRiskMeasurementIT`; base em `docs/perf/u2-barrier-baseline.md` | decisão nesta etapa |
| U2-NFR1.26 (compra com posição sem preço) | p95 ≤ 400 ms | `RealAttributionMeasurementIT` | decisão nesta etapa |
| U3-NFR1.1 a NFR1.4 (C4 com `market` e `RANGE`, `listBots`, C5, C6) | p95 ≤ 1.000 ms por caso | `PnlViewsLatencyIT` (11 casos) e cenário `pnl-by-mode` ampliado | **sim** para C4 `RANGE` 30/366 d, C5 e C6 no `load-test` (U3-NFR1.6) |
| U4-NFR1.1 (leitura do `RealBotHoldings`) | **p99,9 ≤ 500 ms** (fundador F6, 2026-11-25; antes 250 ms), ou seja, `BUDGET_EXCEEDED` ≤ 0,1% | `RealBotHoldingsMeasurementIT`, 10.000 leituras por semente, 3 execuções, mediana | registro; acima da meta vai ao fundador antes de ligar a flag (BR5.2) |
| U4-NFR1.2 (ciclo REAL com a flag ligada) | p95 ≤ 2.000 ms | o mesmo IT, 100 ciclos com a flag ligada e desligada | registro |
| U4-NFR2.17 (`STALE_SNAPSHOT` sem concorrência) | razão = 0 | o mesmo IT | registro; acima de 0 vai ao fundador |

## 2. Testes de integração de medida

Ambiente: Testcontainers com PostgreSQL 17 e as migrações da aplicação; 10 aquecimentos descartados e 100 medidas por caso (U4: 200 aquecimentos e 10.000 leituras); p95 e p99,9 pelo posto mais próximo.

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify \
  -Dit.test='ModePnlLatencyIT,PnlViewsLatencyIT,RealAttributionMeasurementIT,RealRiskMeasurementIT' \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true

# U4, medida oficial (NFR1.5): repetir 3 vezes e usar a mediana
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealBotHoldingsMeasurementIT' \
  -Du4.measure.reads=10000 -Du4.measure.warmups=200 -Du4.measure.cycles=100 \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

- Linhas de log a procurar: `U1 latency`, `U3 latency`, `U2 informative measurement`, `U3 informative measurement` (barreira), `u4-measure seed=A|B mode=budget250|unbounded`.
- A CI (`ci.yml`, job `backend`) roda a U4 com 300 leituras e nenhum ciclo, para caber no tempo do job; com 300 leituras o p99,9 é o maior valor da amostra.
- Nesta máquina a medida oficial da U4 não roda (memória). Caminhos: uma máquina Linux com memória suficiente ou um disparo manual da CI com as propriedades acima. O resultado vai para `docs/perf/u4-own-quantity-cycle-<data>.md`, com o p99,9, o SHA e a máquina.

## 3. Cenário de carga `pnl-by-mode` (manual)

- **Pré-condição (U2-NFR1.9):** o responsável aplica o texto pronto do `.github/workflows/load-test.yml` (está no `code-summary.md` da U2, com o `pnl-by-mode` e a pendência `real` do 261113; a U3 amplia o mesmo cenário). Hoje o arquivo não tem a opção.
- **Disparo:** `gh workflow run load-test -f scenario=pnl-by-mode` no SHA candidato.
- **Semente:** 20 pessoas, cada uma com 5 bots PAPER e 5 REAL e 730 dias de ordens (perfis `test,loadtest`, nunca em produção), com a mistura de regras da U2 e um par PAPER/REAL do mesmo `templateId` por pessoa (U3).
- **Tráfego:** 20 usuários virtuais, 1 leitura a cada 2 s, 60 s de aquecimento e 600 s de carga, em ciclo de 9 leituras (C4 `ALL` e `RANGE` 30/366 d, `GET /api/v1/real/pnl`, C5 primeira e seguinte, C6 `ALL` e `RANGE`).
- **Portões:** p95 de C4 ≤ 1.000 ms; p95 da atribuição `PNL` ≤ 500 ms; C5, C6 e C4 `RANGE` ≤ 1.000 ms; 0 respostas diferentes de 200; 0 divergências de conferência; bloco REAL `UNAVAILABLE` por `TIMEOUT` ≤ 0,5% e 0 por outra causa; `hikaricp_connections_pending` > 0 por no máximo 10 s; memória de `api` e `postgres` ≤ 900 MB; `EXPLAIN` da leitura principal com o índice da `0023`.
- **Saída:** `load-test-report.json` e `docs/perf/u2-real-bot-pnl-<data>.md`, anexados ao registro. A redução de pessoas simultâneas só vale com os dados que a justificam, nunca abaixo de 5; o volume por pessoa e a meta de 1 s nunca baixam (U2-NFR1.25).
- **Teste offline do cenário:** `node --test "scripts/load/*.test.mjs"` (104 testes, inclusive `load-test-pnl-by-mode.test.mjs` e `load-test-pnl-plans.test.mjs`).
- **Devnet (U2-NFR1.24):** o fundador repete a leitura de C4 na devnet depois da implantação, com o mesmo limite de 1.000 ms.

## 4. Detecção de regressão

- A base da barreira REAL antes da U2 está em `docs/perf/u2-barrier-baseline.md` (commit `30017d4`, mediana de 3 execuções: compra 206 ms, venda 15 ms, nesta máquina). Comparações relativas só valem na mesma máquina e na mesma sessão, intercaladas com o código da base.
- Os números da CI (Linux, 2 vCPU) e os desta máquina (Windows, Docker Desktop) não se comparam entre si.
- Toda medida nova entra em `docs/perf/` com o SHA, a máquina e o comando.
