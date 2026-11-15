# Entidades — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `inception/domain-design/components.md` (MarketData), `inception/contract-design/contract-summary.md` (M4, X2), o código atual do módulo `marketdata` e `functional-design-questions.md` (Q1 a Q10). Revisado para caber no código: nenhuma tabela nova e nenhuma coluna nova, só tipos e configuração. O bloco YAML é a fonte da verdade.

## Modelo

```yaml
entities:
  - name: CoinGeckoSourceConfig
    owner: MarketData
    description: Configuração da fonte coingecko, ao lado de sim-a e sim-b em MarketDataProperties (KNOWN_SOURCES ganha coingecko)
    attributes:
      - { name: apiKeyEnv, type: text, default: COINGECKO_API_KEY, required: true }
      - { name: baseUrl, type: url, required: true }
      - { name: network, type: text, default: solana }
      - { name: discoveryLimit, type: integer, default: 50, min: 1, max: 200 }
      - { name: batchSize, type: integer, default: 30, min: 1, max: 30 }
      - { name: discoveryInterval, type: duration, default: 5m, description: "a rodada de preços segue MARKET_COLLECTION_INTERVAL (30 s recomendado)" }
      - { name: detailCallsPerMinute, type: integer, default: 2, min: 0 }
      - { name: detailRefresh, type: duration, default: 60m }
      - { name: callsPerMinute, type: integer, default: 250, min: 1 }
      - { name: monthlyCallBudget, type: integer, default: 450000, min: 1 }
      - { name: timeout, type: duration, default: 5s }
      - { name: attributionText, type: text, default: "Dados: CoinGecko", required: true }
      - { name: attributionUrl, type: url, required: true }
  - name: FieldFreshness
    owner: MarketData
    description: Limite de stale por campo (BR1.4); Freshness passa a receber o campo
    attributes:
      - { name: PRICE, type: duration, default: 5m }
      - { name: LIQUIDITY, type: duration, default: 5m }
      - { name: VOLUME_24H, type: duration, default: 5m }
      - { name: HOLDERS, type: duration, default: 2h }
      - { name: CREATED_AT, type: duration, default: 2h }
  - name: WatchedTokens
    owner: MarketData
    description: Interface pública marketdata.api.WatchedTokens com Set<TokenId> watched(); implementação padrão vazia; a U4 fornece as posições REAL
    attributes: []
  - name: FetchRequest
    owner: MarketData
    description: Pedido da porta MarketSourceAdapter; ganha os tokens observados (BR1.1)
    attributes:
      - { name: requestedAt, type: instant-utc, required: true }
      - { name: watched, type: set-of-token-id, default: [], description: "vindo da porta WatchedTokens; vazio na U2" }
  - name: SourceKind
    owner: MarketData
    description: Tipo da fonte ativa devolvido por activeSourceKind() (BR1.5); derivado de SourceRef.simulated
    attributes:
      - { name: values, type: enum, allowed: [REAL, SIMULATED] }
  - name: Attribution
    owner: MarketData
    description: Texto e link da fonte real ativa, devolvidos por attribution() e pela API do scanner
    attributes:
      - { name: text, type: text, required: true }
      - { name: url, type: url, required: true }
  - name: CallBudget
    owner: MarketData
    description: Contagem de chamadas da CoinGecko no minuto e no mês, em memória e em métrica
    attributes:
      - { name: minuteCount, type: integer }
      - { name: monthCount, type: integer }
      - { name: monthStartedAt, type: instant-utc }
```

## Resumo

| Item | Mudança na U2 |
|---|---|
| Tabelas | nenhuma: as observações continuam como hoje |
| MarketDataProperties | `coingecko` entra em `KNOWN_SOURCES`, com a configuração própria |
| UnknownReason | sem mudança: valor inválido vira `NOT_PROVIDED` e conta na métrica `marketdata_invalid_values_total` |
| Freshness | limite de stale por campo: 5 min nos preços, 2 h em holders e criação |
| WatchedTokens | interface pública nova, vazia na U2 |
| FetchRequest (porta) | ganha `watched` |
| MarketDataQuery (M4) | ganha `activeSourceKind()` e `attribution()` |
| ResolvedField, MarketValue | sem mudança |
