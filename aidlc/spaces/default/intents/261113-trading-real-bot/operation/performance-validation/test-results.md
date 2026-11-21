# Resultados do teste de carga do REAL — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Performance Validation.

## Situação

**Pendente: aguardando o responsável aplicar o texto do load-test.yml.**

O texto está em `load-test-plan.md`, seção 8.1. Nenhuma rodada do cenário `real` foi disparada [Q5]. Por isso esta página ainda não tem latência, vazão, taxa de erro, análise de gargalos nem validação de escala.

## Comando da rodada

Depois de o responsável aplicar o texto e fazer o commit em `main`:

```bash
gh workflow run load-test -f scenario=real
gh run list --workflow load-test --limit 1
gh run watch <id do run> --exit-status
gh run download <id do run> --name load-test-report --dir /tmp/load-real
```

## Onde os resultados serão registrados

| O quê | Onde |
|---|---|
| Relatório da máquina (todas as amostras resumidas, gargalos, erros, duplicidades, filas e planos) | `load-test-report.json`, no artefato `load-test-report` do run (30 dias) |
| Relatório legível | `docs/perf/real-trading-<data>.md`, no mesmo artefato; o arquivo entra no repositório num commit pedido pelo responsável, como os relatórios anteriores em `docs/perf/` |
| Resumo, id do run, aprovado ou reprovado, p50, p95 e p99 de cada meta e a análise dos gargalos | esta página, na seção "Resultado da rodada da CI" |
| Situação de cada meta | `nfr-validation-matrix.md`: a coluna Situação passa de Pendente para Atingida ou Não atingida, com o id do run |
| Meta não atingida | volta ao NFR Design da Unit dona [Q3]; o limite não muda |

## Resultado da rodada da CI

Pendente.

## Fase 2 (devnet) e disponibilidade de 30 dias

Pendente. Não existe conta AWS. O plano, o comando e as consultas do CloudWatch estão nas seções 9 e 10 de `load-test-plan.md`.

## Testes do próprio cenário (já executados, nesta máquina)

Esta seção não traz resultado de carga. Ela registra só que o código do cenário foi testado. O cenário de carga em si nunca rodou nesta máquina, por causa da memória curta e por decisão [Q1].

| Comando | Resultado |
|---|---|
| `node --test --experimental-test-coverage --test-coverage-include="scripts/load/real*.mjs" scripts/load/load-test-real.test.mjs` | 34 testes, 34 verdes; cobertura de linhas: 100% em `real-stats.mjs` e 99,3% em `real.mjs` (total 99,6%) |
| `node --test scripts/load/load-test-*.test.mjs` | 86 testes, 86 verdes (os cenários anteriores continuam inteiros) |
| `sh apps/api/mvnw -B -ntp -q -f apps/api/pom.xml test spotless:check -Dtest=LoadtestRealHistogramsTest ...` | 3 testes, 3 verdes; Spotless sem violação |
| `sh apps/api/mvnw ... test -Dtest=CoinGeckoRoundMeasurementTest ...` | 2 testes, 2 verdes: a medição sintética de U2:NFR3.1 a NFR3.3 com 300 ms por chamada fica dentro dos limites (parte de preço com 50 tokens: 600 ms contra 10 s) |
| `sh apps/api/mvnw ... test -Dtest='WorkflowsTest,ComposeFilesTest,ProfileDefaultsTest,...'` | 10, 28 e 6 testes, todos verdes |
| `node --check scripts/load/load-test.mjs` e `bash -n scripts/load-test.sh` | sem erro de sintaxe |

## Sources

- `load-test-plan.md` (seções 8 a 10).
- `performance-validation-questions.md` (Q1, Q3 e Q5).
- `construction/build-and-test/build-and-test-summary.md` (as 19 metas desta etapa).

## Assumptions & Open Questions

None.
