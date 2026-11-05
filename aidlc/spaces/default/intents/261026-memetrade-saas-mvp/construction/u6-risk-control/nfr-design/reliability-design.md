# Design de confiabilidade — U6 Barreira de limites

Como a Unit `u6-risk-control` atende NFR10.1 a NFR10.7, NFR4.6 e NFR8.2 de [reliability-requirements.md](../nfr-requirements/reliability-requirements.md), com a resposta Q3 de [nfr-design-questions.md](nfr-design-questions.md).

## Falha fechada (NFR10.2, NFR10.3)

- **Caminho para a aprovação.** Só existe um: a função de verificação devolve a lista de motivos vazia e a avaliação aprovada é gravada.
- **Falha técnica.** Qualquer exceção da C11, da C13, do banco ou de um tempo de espera sobe sem ser capturada pela U6 e desfaz a transação do ciclo. A U6 não tem `catch` que transforme falha em aprovação ou em recusa.
- **Dado ausente.** `Unknown`, dado velho ou patrimônio vazio viram motivos de recusa (BR3.2), nunca zero.
- **Testes.** Com falha injetada:
  - em cada leitura (C11 preço, C11 liquidez, C13);
  - em cada gravação (avaliação, recusa, detalhes, tabela de saída);
  - e com `lock_timeout` estourado.

  Em todos, nada fica gravado e a U7 não é chamada.

## Sem nova tentativa (NFR10.4)

Não há retry, circuit breaker nem fallback na barreira. Uma tentativa nova seria uma segunda leitura de dados que mudaram, dentro do mesmo ciclo. O próximo ciclo da U5 decide de novo, com dados novos.

## Sem estado em memória (NFR10.5)

- Os componentes da U6 são sem estado.
- A única configuração é lida na subida: os parâmetros da cópia da política, o tamanho do lote e o horário do expurgo.
- Um reinício não muda nenhum resultado.

## Atomicidade (NFR4.6)

- Avaliação, recusa, detalhes e evento da trilha usam a mesma transação do ciclo. A tabela de saída da C9 é gravada na mesma conexão.
- Um teste injeta falha depois de cada gravação e confere que nada sobra.

## Expurgo (NFR10.6, NFR10.7)

- **Agendamento.** Roda todo dia às 03:30 UTC, com `@SchedulerLock` (ShedLock) de no máximo 45 minutos, no processo de API, junto da purga da trilha da U2.
- **Conexão.** Usa a conexão de retenção (papel `risk_retention`, security-design.md).
- **Lotes.** Cada lote é uma transação. Uma falha no meio deixa os lotes anteriores apagados e o atual intacto, e a rodada seguinte continua.
- **Registro da rodada.** Ao fim de cada rodada, com sucesso ou falha, uma linha é gravada em `risk_purge_run`, uma tabela de controle da U6 só de inserção, com a mesma conexão de retenção. Ela guarda o início, o fim, o total apagado, o corte de data e o resultado (`SUCCEEDED` ou `FAILED` com a causa curta, sem dado de pessoa). As linhas com mais de 90 dias são apagadas pela própria rodada.
- **Duplicidade depois do expurgo.** O teste de integração descrito em security-design.md prova que a U7 contém a reavaliação.

## Indicador de saúde (NFR9.1, Q3)

- `RiskControlHealthIndicator` lê a última linha de `risk_purge_run`, que a API e o worker leem igual.
  - **`UP`:** a última rodada com sucesso tem menos de 26 horas, ou ainda não houve rodada e o processo subiu há menos de 26 horas.
  - **`DOWN`:** caso contrário, com o motivo `PURGE_STALE` ou `PURGE_FAILED`.
- O indicador fica fora dos grupos `liveness` e `readiness` do actuator. Uma falha de limpeza não derruba a API nem o worker e não afeta o healthcheck do Docker Compose. O `DOWN` aparece só no detalhe da saúde, que o NFR de segurança da U1 já restringe.

## Determinismo (NFR8.2)

- A barreira recebe um `Clock` injetado. Não usa aleatoriedade.
- As contas usam `BigDecimal` com escala e arredondamento explícitos.
- O teste de propriedade usa seed fixa.
