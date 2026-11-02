# Desenho de confiabilidade — U4 Descoberta e avaliação

Como a U4 cumpre [reliability-requirements.md](../nfr-requirements/reliability-requirements.md), com a decisão Q1 de [nfr-design-questions.md](nfr-design-questions.md) (saúde das fontes nunca `DOWN`) e os achados R-01 e R-03 de [review-01.md](../nfr-requirements/reviews/review-01.md).

## Chamada a uma fonte (NFR10.3)

| Parâmetro | Valor | Onde |
|---|---|---|
| Tempo limite por chamada | 5 s | `TimeLimiter` do Resilience4j |
| Novas tentativas | até 2 (3 chamadas no total) | `Retry` do Resilience4j |
| Espera entre tentativas | 1 s e 2 s, com variação aleatória de até 20% | `IntervalFunction` exponencial com aleatoriedade |
| Erros que geram nova tentativa | tempo esgotado, erro de rede, resposta 5xx ou 429 da fonte | lista fechada |
| Erros sem nova tentativa | resposta inválida (`INVALID_PAYLOAD`), 4xx exceto 429 | lista fechada |
| Pior caso | 3 × 5 s + 1 s + 2 s + 20% = cerca de 18,6 s | acima do intervalo de 30 s? não; acima da meta de 10 s, que vale só para o caminho sem falha (achado R-03) |

A espera usa um `Sleeper` injetável, e os testes rodam sem esperar de verdade. A variação aleatória usa uma semente fixa nos testes.

Uma coleta que passa do intervalo (até cerca de 18,6 s, menor que o intervalo padrão de 30 s) não sobrepõe a próxima. Com intervalo configurado menor que a duração, o ShedLock pula a execução sobreposta e conta `market_collection_total{result="SKIPPED_LOCKED"}`.

## Isolamento por fonte (NFR10.2, NFR10.4, NFR10.5)

- Cada fonte tem seu próprio agendamento, bloqueio, instâncias de `Retry` e `TimeLimiter`; a falha de uma não afeta a outra.
- O estado de cada fonte é a fonte da verdade para a lista (BR1.3, BR1.4) e muda só no fim de cada coleta, na mesma transação que grava os dados (NFR4.3).
- A recuperação é automática: a próxima coleta bem-sucedida muda o estado; o recálculo seguinte recompõe as avaliações.

## Reinício (NFR10.6)

- Estado, observações, histórico e avaliações ficam no banco; a migração da U4 muda o estado inicial das fontes registradas para `PENDING` só quando elas ainda não têm `last_updated_at`, e o registro de fontes na partida não regrava o estado (corrige o comportamento da U1 de regravar `FAILED`).
- Na partida (`ApplicationReadyEvent`), cada fonte agenda uma coleta imediata, ainda pelo bloqueio.

## Trabalhos sem sobreposição e sem resultado parcial (NFR10.7, NFR10.8)

| Trabalho | Bloqueio | Transação | Repetição |
|---|---|---|---|
| Coleta por fonte | ShedLock `market-collect-<sourceId>`, `lockAtMostFor` 2 min | uma por coleta | mesma observação não duplica |
| Recálculo | ShedLock `risk-recalculate`, `lockAtMostFor` 2 min; gatilhos juntados por um sinalizador "pendente" em memória | uma por lote de 500 | mesmo `inputsFingerprint` não regrava |
| Limpeza | ShedLock `market-history-cleanup`, `lockAtMostFor` 10 min | uma por lote de 10 000 | apagar de novo não muda nada |
| Ativação de pesos | trava da linha ativa | uma | reativar a mesma versão não faz nada |

A gravação de cada avaliação usa `INSERT ... ON CONFLICT (chain, mint) DO UPDATE ... WHERE risk_assessment.weight_set_version <= EXCLUDED.weight_set_version`, e uma versão menor nunca sobrescreve uma maior (NFR10.8).

## Saúde (NFR9.1, decisão Q1, achado R-01)

| Indicador | `UP` | `DEGRADED` (HTTP 200) | `UNKNOWN` (HTTP 200) | Nunca |
|---|---|---|---|---|
| `marketSources` | alguma fonte `OK` ou `DEGRADED` | todas as fontes `FAILED` | todas as fontes `PENDING` | `DOWN` |
| `riskRecalculation` | último recálculo bem-sucedido há até 5 min | há mais de 5 min, ou falhou | nenhum recálculo ainda | `DOWN` |

`DEGRADED` já está mapeado para HTTP 200 e fica abaixo de `DOWN` na ordem de status da U2, então a saúde geral (`/actuator/health`), usada pelo healthcheck do Compose e pelo `scripts/verify.sh`, continua respondendo 200 com todas as fontes fora; o detalhe mostra o estado de cada fonte. Os dois indicadores ficam fora do grupo de prontidão. Testes: com todas as fontes `FAILED`, `GET /actuator/health` responde 200 e o corpo traz `marketSources.status = DEGRADED` com as duas fontes `FAILED`.

## Falhas técnicas (NFR10.9)

- C11 e C12: exceção técnica na leitura vira `Unknown(SOURCE_UNAVAILABLE)` ou `Unavailable(SOURCE_UNAVAILABLE)`, sem nova tentativa (contrato), com log `ERROR` só com a classe da exceção.
- Recálculo: exceção num lote desfaz o lote, mantém as avaliações anteriores, registra o erro e marca `riskRecalculation` como `DEGRADED`; o próximo gatilho tenta de novo.
- Banco fora na consulta: `503` técnico em `ProblemDetail`, sem dado antigo.

## Retenção (NFR4.4)

A limpeza apaga amostras com `observed_at` anterior a agora menos 24 horas; rodando a cada hora, nenhuma amostra passa de 25 horas. Testes de fronteira com relógio injetável (24 h exatas fica; 24 h e 1 s sai).
