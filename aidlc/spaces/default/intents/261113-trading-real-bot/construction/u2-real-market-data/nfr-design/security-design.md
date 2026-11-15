# Desenho de segurança — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/nfr-requirements/security-requirements.md` (NFR1.1 a NFR1.6, NFR2.1, NFR6.1, NFR10.1), `construction/u2-real-market-data/functional-design/rules.md` (BR1.2, BR1.6, BR1.10) e `nfr-design-questions.md` (Q1).

## Chamada ao fornecedor (`CoinGeckoHttp`)

- **Endereço:** o endereço base vem de uma lista fixa de domínios da CoinGecko, conferida na subida. Só HTTPS, com o certificado padrão da JVM (NFR1.2).
- **Cliente:** o `RestClient` é criado sobre o `JdkClientHttpRequestFactory`, com um `HttpClient` do JDK em `followRedirects(NEVER)` e tempo de conexão de 2 s. Toda resposta 3xx vira falha não repetível (`MarketSourceException` sem repetição). O pedido leva `Accept-Encoding: identity`, para o corpo vir sem compressão.
- **Chave:** vai no cabeçalho de autenticação. O interceptor de log do cliente remove esse cabeçalho, e a chave nunca entra em métrica nem em exceção (NFR1.1).
- **Mint:** é validado como Base58 de 32 a 44 caracteres antes de entrar na URL. Um mint inválido nunca é pedido (NFR1.2).
- **Corpo da resposta:** lido por `exchange` como `InputStream`, embrulhado num contador que lança erro ao passar de 2 MB. O Jackson lê direto desse fluxo, sem carregar tudo na memória.
  - Se a resposta vier comprimida (`Content-Encoding` diferente de `identity`), ela é recusada. Assim o limite vale sempre sobre os bytes reais, e uma bomba de compressão não passa.
  - `StreamReadConstraints`: `maxNestingDepth` 32, `maxStringLength` 10 mil, `maxNumberLength` 64 e `maxDocumentLength` de 2 MB. Números lidos como `BigDecimal` (NFR1.3, NFR10.1).
- **Mensagens de erro:** levam só o nome da rota (por exemplo `token_prices`) e o código HTTP. Nunca levam a URL completa, os cabeçalhos ou o corpo.

## Isolamento de rede

A regra ArchUnit proíbe, no módulo `marketdata` fora de `infra.coingecko`, dependências de `java.net..`, `java.net.http..`, `org.springframework.web.client..` e `org.springframework.http.client..`. Um teste cria uma classe de exemplo fora do pacote e confere que a regra falha (NFR1.4).

## Processo

- O adaptador e o cliente só são criados no papel `worker` (`@ProcessRole(WORKER)`); a API nunca chama o fornecedor (NFR1.6).
- Na subida, com o REAL ligado e fora dos perfis `test` e `local`, a presença de `sim-a` ou `sim-b` impede a aplicação de subir (NFR1.5).

## Validação de valores (`ValueParser`)

- O texto numérico é lido como decimal exato e passa pelas faixas de BR1.2.
- Um valor fora da faixa vira `Unknown NOT_PROVIDED` e soma 1 em `marketdata_invalid_values_total{field}`.
- O log registra só o campo e o motivo.
