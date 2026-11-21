# Rastreamento — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Observability Setup, Q6 (opção A).

> **Nada está implantado e nada novo foi criado para rastreamento.** O piloto não liga o X-Ray. O caminho do `correlationId` já existe no código, testado na CI. Ele só será conferido na nuvem depois do primeiro `apply` da devnet, com os comandos do fim do arquivo.

## Decisão

- **Sem X-Ray (nem OpenTelemetry) no piloto.** Cada serviço roda com uma tarefa só (`desired_count = 1`), e a cadeia é curta: navegador → balanceador → api ou worker → TxSigner → KMS e RPC. O `correlationId` nos logs e nas respostas segue um pedido inteiro, com custo zero e nenhuma peça a mais.
- O X-Ray exigiria um contêiner auxiliar do daemon (ou do ADOT) em cada tarefa e a permissão `xray:PutTraceSegments` no papel do TxSigner. Exigiria também o endpoint da VPC `com.amazonaws.<região>.xray`, porque a subrede do TxSigner não tem internet. Seriam mais custo e mais superfície no papel mais sensível, sem ganho que o piloto use.

## Caminho do `correlationId`

| Passo | Onde | Como |
|---|---|---|
| 1 | api, na entrada do pedido HTTP | gera ou aceita o `correlationId` e o põe no MDC; volta em toda resposta de erro (`ProblemDetail.correlationId`, RFC 7807) |
| 2 | worker, em cada rodada ou trabalho | um `correlationId` por rodada (U2-NFR5.10) e por processamento de decisão |
| 3 | api e worker → TxSigner | propagado no cabeçalho da chamada interna (`HttpTxSignerClient`); o TxSigner o põe no MDC |
| 4 | logs dos três processos | campo `correlationId` no JSON (`includeMdcKeyName` no `logback-spring.xml`) |
| 5 | consulta | `eureka-real/seguir-correlation-id` (Logs Insights) junta as linhas dos grupos `api-worker` e `txsigner` em ordem de tempo |

Evidência na CI: U1-NFR5.13 está `Met` (`InternalApiIT` no TxSigner e `HttpTxSignerClientTest` no backend, run 44765239890).

O que o `correlationId` não mostra é o tempo gasto em cada salto. Para isso, há três fontes:

- os painéis, com a latência do balanceador e o pior tempo do TxSigner e do KMS;
- o `timestamp` de cada linha de log;
- os histogramas do Prometheus local, no teste de carga.

## Quando rever a decisão

O X-Ray ou o OpenTelemetry voltam à pauta quando qualquer uma destas condições acontecer:

1. um serviço passar a rodar com mais de uma tarefa (escala horizontal), porque fica difícil seguir um pedido entre instâncias só pelo log;
2. a abertura aos convidados, se o p95 de alguma operação passar da meta e o log não explicar onde o tempo foi gasto;
3. entrar um serviço novo no caminho do pedido (por exemplo, uma fila gerenciada entre o api e o worker).

**Como seria:** o coletor ADOT como contêiner auxiliar, com amostragem de 5%, e o Micrometer Tracing com o exportador OTLP. Isso pede mudança de código e uma nova avaliação de segurança do papel do TxSigner. É decisão do fundador num estágio futuro.

## Conferência depois do primeiro apply da devnet

```bash
# 1. Um pedido com erro devolve o correlationId (sem login, uma rota protegida responde 401 em ProblemDetail)
curl -s https://api.devnet.<domínio>/api/v1/real/bot-wallet | jq -r .correlationId

# 2. O mesmo correlationId aparece nos logs do api (trocar o valor na consulta salva seguir-correlation-id)
QID=$(aws logs start-query --log-group-names /ecs/eureka-real/api-worker /ecs/eureka-real/txsigner \
  --start-time "$(date -u -d '-15 minutes' +%s)" --end-time "$(date -u +%s)" \
  --query-string 'fields @timestamp, @logStream, component, message | filter correlationId = "<valor>" | sort @timestamp asc' \
  --query queryId --output text)
aws logs get-query-results --query-id "$QID"

# 3. Numa ativação de carteira na devnet, o mesmo correlationId aparece no grupo do TxSigner
```

## Sources

- `observability-setup-questions.md` (Q6).
- `construction/u1-bot-wallet/nfr-requirements/observability-requirements.md` (NFR5.12 e NFR5.13) e `infrastructure-design/monitoring-design.md` (Logs & Tracing).
- `construction/build-and-test/build-and-test-summary.md` (linha U1:NFR5.13, `Met`).
- `apps/api/src/main/resources/logback-spring.xml` e `apps/txsigner/src/main/resources/logback-spring.xml`.
- `infra/terraform/modules/network/main.tf` (endpoints da VPC; não há endpoint do X-Ray).
