# Decisões de tecnologia — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`, `construction/u2-real-market-data/functional-design/functional-spec.md`, `inception/requirements-analysis/requirements.md`, `inception/contract-design/contract-summary.md` e `nfr-requirements-questions.md` (Q1).

| Área | Escolha | Por quê |
|---|---|---|
| Adaptador | Pacote `com.memetrade.marketdata.infra.coingecko`, que implementa `MarketSourceAdapter` | mesma porta das fontes simuladas |
| Cliente HTTP | `RestClient` do Spring, que já está no projeto, com tempos limite de conexão e leitura de 5 s | sem dependência nova |
| Resiliência | Resilience4j (retry e timelimiter, que já existem, mais o circuit breaker que a U1 trouxe) | consistência com a U1 |
| JSON | Jackson, que já existe, com `StreamReadConstraints` para profundidade e tamanho de texto; números lidos como `BigDecimal` (`USE_BIG_DECIMAL_FOR_FLOATS`) | segurança e decimal exato |
| Regra de arquitetura | `theMarketModuleNeverUsesTheNetwork` passa a excluir só `..marketdata.infra.coingecko..` | rede isolada no adaptador real |
| Testes | WireMock com respostas sintéticas e JUnit 5. O WireMock é dependência nova, só no escopo de teste do `apps/api/pom.xml`. O verify.sh continua com as fontes simuladas | sem rede nos testes |
| Leitura do corpo | Corpo lido em fluxo com corte em 2 MB e redirecionamento desligado no `RestClient` | NFR1.2, NFR1.3 |
| Uso da cota | Rota de uso da chave da CoinGecko, lida na subida e a cada hora, como fonte da verdade do orçamento | NFR3.7 |

## Qualidade (NFR8)

| ID | Requisito |
|---|---|
| NFR8.1 | Piso de 80% de linhas no backend, com testes do adaptador escritos em seguida ao código (test-after: adaptador fora do núcleo financeiro) |
| NFR8.2 | Nenhum teste chama a CoinGecko real |

## Acessibilidade (NFR7)

| ID | Requisito |
|---|---|
| NFR7.1 | A atribuição e o aviso de fonte simulada são texto visível, com contraste, e não só cor |
