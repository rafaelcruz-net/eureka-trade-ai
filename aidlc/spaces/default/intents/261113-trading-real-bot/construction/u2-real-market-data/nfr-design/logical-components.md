# Componentes lógicos — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `inception/domain-design/components.md` (MarketData), `inception/contract-design/contract-summary.md` (M4, X2), `construction/u2-real-market-data/nfr-requirements/tech-stack-decisions.md` e `nfr-design-questions.md` (Q1).

| Componente | Pacote | Processo | Papel |
|---|---|---|---|
| `CoinGeckoHttp` | `marketdata.infra.coingecko` | worker | chamadas HTTPS, corte de 2 MB, sem redirecionamento, JSON com limites |
| `CoinGeckoAdapter` | `marketdata.infra.coingecko` | worker | implementa `MarketSourceAdapter`: rodadas, conjunto ativo em memória |
| `CallBudget` | `marketdata.infra.coingecko` | worker | limite por minuto (total e de detalhes), orçamento mensal na tabela `marketdata_provider_usage`, prioridade, modo conservador |
| `SourceCaller` (já existe) | `marketdata.infra` | worker | ganha limite e tentativas por fonte: `coingecko` com 20 s e 1 tentativa |
| `ValueParser` | `marketdata.infra.coingecko` | worker | decimal exato, faixas, `unknown` |
| `WatchedTokens` | `marketdata.api` | os dois | interface pública; implementação padrão vazia; a U4 implementa |
| `MarketDataQuery` (M4) | `marketdata.api` | os dois | ganha `activeSourceKind()` e `attribution()` |

## Falhas e raio de impacto

| Falha | Impacto | Contenção |
|---|---|---|
| CoinGecko fora do ar | o preço envelhece; na segunda rodada vira `unknown` e o REAL recusa | circuito, estados e alerta |
| Cota estourada | os detalhes param, depois a descoberta | prioridade do orçamento |
| Resposta malformada ou enorme | a rodada falha sem gravar | corte de 2 MB, limites do JSON |
| Reinício do worker | o conjunto ativo é refeito | contador lido da tabela, conferido com o fornecedor |

## Recursos compartilhados

A tabela de observações e o coletor que já existem. A cota da CoinGecko é usada só pelo worker.
