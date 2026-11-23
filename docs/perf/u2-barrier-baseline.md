# Linha de base da barreira REAL — antes da U2 `u2-real-bot-pnl` (2026-11-22)

Passo 0 do plano da U2 (NFR1.5, NFR1.7). Três execuções do `RealRiskMeasurementIT` no último commit antes de
qualquer código da U2. A comparação da U2 com esta linha de base é do Build and Test, na mesma máquina.

- Commit medido: `30017d48b1dad8320110ce350753dfd50ffc9722` (árvore de `apps/` sem mudança)
- Máquina: notebook Windows 11 Home, Intel Core i5-13420H, 16 GB de RAM; Docker 29.9.2 com 8 GB para os contêineres;
  PostgreSQL 17 em contêiner (Testcontainers), JDK Temurin 25.0.1
- Comando de cada execução, a partir da raiz do repositório:
  `./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealRiskMeasurementIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dspotless.check.skip=true`
- Condição de medida (a do teste): 21 carteiras com 10 posições abertas cada, todas com preço; 4 avaliações por
  segundo; 24 avaliações (12 compras e 12 vendas); p95 pela posição mais próxima.

## Avaliação da barreira (`RealRiskGate.evaluate`)

| Execução | p95 da compra | p95 da venda |
|---|---|---|
| 1 | 185 ms | 15 ms |
| 2 | 256 ms | 26 ms |
| 3 | 206 ms | 13 ms |
| **Mediana** | **206 ms** | **15 ms** |

## Rodadas de risco (informativo)

| Execução | Rodada do dia | Rodada da perda diária |
|---|---|---|
| 1 | 412 ms | 329 ms |
| 2 | 388 ms | 448 ms |
| 3 | 336 ms | 335 ms |
| **Mediana** | **388 ms** | **335 ms** |

## Para o Build and Test (NFR1.5)

- A margem relativa do NFR1.5 sobre esta base: compra no maior entre 1,10 × 206 ms = 226,6 ms e 206 + 2 = 208 ms;
  venda no maior entre 1,10 × 15 ms = 16,5 ms e 15 + 2 = 17 ms.
- Observação: nesta máquina, a mediana da compra (206 ms) já passa do teto absoluto de 150 ms do U3-NFR3.1 antes de
  qualquer código da U2. A venda (15 ms) fica dentro do teto de 30 ms. A leitura dessas metas é do Build and Test.
