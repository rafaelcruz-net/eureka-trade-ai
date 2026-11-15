# Regras de negócio — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `inception/requirements-analysis/requirements.md` (FR1.1 a FR1.4), `inception/user-stories/stories.md` (US6.3), `inception/units-generation/unit-of-work.md`, `inception/units-generation/unit-of-work-story-map.md`, `inception/domain-design/components.md` (MarketData), `inception/contract-design/contract-summary.md` (M4, X2), o código atual do módulo `marketdata` (`MarketSourceAdapter`, `FieldResolver`, `Freshness`, `ResolvedField`, `MarketValue`, `SourceStatus`, `MarketDataProperties`) e `functional-design-questions.md` (Q1 a Q10). Revisado depois da primeira revisão para caber no código existente. O bloco YAML é a fonte da verdade.

## Regras

```yaml
rules:
  - id: BR1.1
    statement: A descoberta traz os pools novos e os em alta da rede solana, mais os tokens observados por outros módulos
    category: policy
    applies_to: adaptador da CoinGecko
    trigger: ciclo de descoberta, a cada 5 min
    logic: une pools novos e em alta, tira os repetidos pelo mint e fica com até 50 tokens (configurável); soma a lista de tokens observados pela interface pública marketdata.api.WatchedTokens (Set<TokenId> watched()), cuja implementação padrão devolve vazio e que a U4 substitui pelas posições REAL; o conjunto ativo e os horários de descoberta e de detalhe vivem na memória do adaptador no worker e são refeitos na primeira rodada depois de um reinício; um token sai do conjunto quando some da descoberta e não está observado
    violation: não se aplica
    source: FR1.1
  - id: BR1.2
    statement: Cada campo vem da resposta do fornecedor, validado por faixa, ou é unknown, nunca zero
    category: validation
    applies_to: PRICE, LIQUIDITY, VOLUME_24H, HOLDERS, CREATED_AT
    trigger: resposta da CoinGecko
    logic: >
      O texto numérico é lido direto como decimal exato (nunca por ponto flutuante). Faixas válidas: PRICE, LIQUIDITY e
      VOLUME_24H maiores ou iguais a 0; HOLDERS inteiro maior ou igual a 0; CREATED_AT convertido de data ISO para
      segundos de época, não futuro. Campo ausente, nulo, fora da faixa ou ilegível vira Unknown NOT_PROVIDED (sem motivo
      novo, para não mudar o CHECK de unknown_reason nem os consumidores); um zero informado é Known 0
    violation: o valor inválido nunca é gravado; a métrica marketdata_invalid_values_total{field} conta e o log registra campo e motivo, sem o conteúdo bruto
    source: FR1.1
  - id: BR1.3
    statement: As chamadas cabem na cota por minuto e no orçamento mensal do plano
    category: constraint
    applies_to: cliente da CoinGecko
    trigger: cada chamada
    logic: >
      Cada chamada de fetch() do coletor (MARKET_COLLECTION_INTERVAL, 30 s recomendado para a coingecko) é uma rodada de
      preços: preço, liquidez e volume de todos os tokens ativos em lotes de até 30 (2 chamadas com 50 tokens). Na mesma
      rodada o adaptador faz a descoberta se passaram 5 min da última (2 chamadas) e uma fatia de detalhes (holders e pool
      principal, 2 chamadas por token) dentro do orçamento de 2 chamadas por minuto, renovando cada token a cada 60 min.
      Conta com 50 tokens: preços 4/min, descoberta 0,4/min, detalhes 100 chamadas por hora (1,7/min), cerca de 6,1
      chamadas por minuto e cerca de 264 mil por mês. O limitador garante no máximo 250 por minuto e o orçamento mensal
      configurável (padrão 450 mil), com alerta a 80%. Cada chamada tem tempo limite de 5 s e uma repetição em leitura
      (X2); 429 respeita o Retry-After quando ele é de até 10 s dentro da rodada, senão a rodada termina como DEGRADED
    violation: acima do orçamento, os detalhes param primeiro e os preços continuam; tokens não atualizados no ciclo deixam a fonte em DEGRADED
    source: FR1.1, NFR9
  - id: BR1.4
    statement: Os valores trazem o horário da busca; a idade de 60 s para o REAL é medida por quem lê
    category: calculation
    applies_to: MarketValue.Known
    trigger: leitura pela interface de mercado (M4)
    logic: >
      Known.observedAt é o horário da busca, como já é hoje. A marca stale passa a ser por campo: PRICE, LIQUIDITY e
      VOLUME_24H com mais de 5 min (como hoje); HOLDERS e CREATED_AT com mais de 2 h, porque são renovados a cada 60 min.
      A regra de 60 s do REAL vale só para PRICE, LIQUIDITY e VOLUME_24H e é aplicada pela barreira (U3) com observedAt;
      HOLDERS e CREATED_AT entram na avaliação pela marca stale de 2 h
    violation: não se aplica
    source: FR1.4
  - id: BR1.5
    statement: A interface de mercado informa se a fonte ativa é real ou simulada e a atribuição
    category: policy
    applies_to: M4 (MarketDataQuery)
    trigger: toda leitura
    logic: >
      Novos métodos: activeSourceKind() devolve SIMULATED se alguma fonte configurada tem SourceRef.simulated, senão REAL;
      attribution() devolve o texto e o link da fonte real ativa, se houver. O tipo de cada valor já vem de
      Known.source().simulated(), sem campo novo. O quote(chain, mint) do contrato é o referencePrice(token) existente
    violation: não se aplica
    source: FR1.3
  - id: BR1.6
    statement: Com o REAL ligado, a API e o worker não sobem com fonte simulada fora dos perfis test e local
    category: policy
    applies_to: configuração
    trigger: subida da API e do worker
    logic: SE memetrade.real.enabled = true E nenhum perfil ativo é test nem local E MARKET_SOURCES tem sim-a ou sim-b ENTÃO a subida é recusada com mensagem clara
    violation: a aplicação não sobe
    source: FR1.3
  - id: BR1.7
    statement: Com uma fonte só, o valor é de fonte única
    category: calculation
    applies_to: ResolvedField
    trigger: resolução de cada campo
    logic: fonte única é derivada, sem campo novo — ResolvedField com origins de tamanho 1 e divergent falso; a avaliação de risco trata esse caso como cobertura reduzida; com duas fontes reais a checagem de divergência atual continua
    violation: não se aplica
    source: FR1.1
  - id: BR1.8
    statement: A tela mostra a atribuição exigida pelo fornecedor
    category: policy
    applies_to: scanner e detalhe do token
    trigger: exibição de dados de fonte real
    logic: a API do scanner devolve a atribuição (texto e link da configuração) junto das fontes; a tela mostra "Dados: CoinGecko" com o link; com fonte simulada, mostra "fonte simulada ativa"
    violation: não se aplica
    source: FR1.2
  - id: BR1.9
    statement: Falha do fornecedor torna o dado desconhecido, nunca troca para a fonte simulada
    category: policy
    applies_to: coletor e FieldResolver
    trigger: erro de rede, 5xx, tempo esgotado ou resposta malformada
    logic: >
      Estados da fonte como hoje (PENDING, OK, DEGRADED, FAILED), decididos pela rodada de preços: OK quando todos os
      tokens ativos tiveram preço atualizado; DEGRADED quando parte foi atualizada, quando a cota ou um 429 longo cortou a
      rodada, ou na primeira rodada sem nenhum preço; FAILED só na segunda rodada seguida sem nenhum preço (tolerância a
      uma falha curta). Descoberta e detalhes que não rodam numa rodada não mudam o estado. Os campos não renovados na
      rodada não são devolvidos pelo fetch e mantêm a observação anterior com o horário dela. Em FAILED, o FieldResolver
      devolve Unknown SOURCE_UNAVAILABLE, como já faz, e o REAL recusa ordem. Nunca há volta automática para a fonte simulada
    violation: não se aplica
    source: FR1.3, FR1.4
  - id: BR1.10
    statement: A chave da API é segredo
    category: policy
    applies_to: cliente da CoinGecko
    trigger: toda chamada e todo log
    logic: lida de COINGECKO_API_KEY, enviada só no cabeçalho de autenticação, nunca em log, resposta, navegador ou NEXT_PUBLIC_*
    violation: teste falha se a chave aparecer em log
    source: NFR1
```

## Resumo

| ID | Regra | Fonte |
|---|---|---|
| BR1.1 | Descoberta mais tokens observados | FR1.1 |
| BR1.2 | Faixas e unknown, nunca zero | FR1.1 |
| BR1.3 | Três ritmos, cota por minuto e orçamento mensal | FR1.1, NFR9 |
| BR1.4 | Horário da busca; 60 s aplicado pela U3 | FR1.4 |
| BR1.5 | activeSourceKind e attribution na M4 | FR1.3 |
| BR1.6 | Sem fonte simulada fora de test e local com REAL ligado | FR1.3 |
| BR1.7 | Fonte única derivada | FR1.1 |
| BR1.8 | Atribuição na tela | FR1.2 |
| BR1.9 | Falha vira unknown; estados atuais | FR1.3 |
| BR1.10 | Chave como segredo | NFR1 |
