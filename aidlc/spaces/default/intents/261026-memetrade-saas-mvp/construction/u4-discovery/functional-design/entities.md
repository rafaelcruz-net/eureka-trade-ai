# Entidades — U4 Descoberta e avaliação

Modelo de dados dos componentes MarketData (completo), Scanner e RiskAssessment, a partir de [unit-of-work.md](../../../inception/units-generation/unit-of-work.md), [components.md](../../../inception/domain-design/components.md), dos contratos C2, C11, C12, C18 e C19 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das respostas Q1 a Q10 de [functional-design-questions.md](functional-design-questions.md). O bloco YAML é a fonte da verdade; os nomes de atributos seguem o código (inglês) e as descrições ficam em português.

`DataSource`, `Token` e `MarketObservation` já existem desde a U1; aqui aparecem completos, com o que a U4 acrescenta marcado em `added_in: u4`.

```yaml
entities:
  - name: DataSource
    owner: MarketData
    description: Uma fonte de dados de mercado atrás de um adaptador (C19). No ambiente local, todas são simuladas.
    identifier: sourceId
    attributes:
      - { name: sourceId, type: string, required: true, unique: true, max: 64 }
      - { name: name, type: string, required: true, max: 120 }
      - { name: simulated, type: boolean, required: true }
      - { name: status, type: enum, required: true, allowed: [PENDING, OK, DEGRADED, FAILED], default: PENDING, constraints: "PENDING até a primeira coleta terminar; o estado é guardado e um reinício da aplicação não o muda (PENDING é acréscimo ao SourceStatus do C2)" }
      - { name: lastUpdatedAt, type: instant, required: false, constraints: "UTC; instante da última coleta bem-sucedida; vazio antes da primeira" }
      - { name: lastAttemptAt, type: instant, required: false, constraints: "UTC; instante da última tentativa, com ou sem sucesso" }
      - { name: failureCategory, type: enum, required: false, allowed: [TIMEOUT, ERROR_RESPONSE, INVALID_PAYLOAD], added_in: u4, constraints: "preenchido só quando status = FAILED ou DEGRADED; nunca o texto do erro" }
      - { name: collectionInterval, type: duration, required: true, added_in: u4, default: "30 s", min: "5 s", max: "60 s" }
    relationships:
      - { to: MarketObservation, cardinality: "1:N", direction: "DataSource -> MarketObservation" }
      - { to: ObservationSample, cardinality: "1:N", direction: "DataSource -> ObservationSample" }

  - name: Token
    owner: MarketData
    description: Um token identificado por chain + mint; o símbolo não identifica.
    identifier: [chain, mint]
    attributes:
      - { name: chain, type: string, required: true, max: 64 }
      - { name: mint, type: string, required: true, max: 44, constraints: "opaco; maiúsculas e minúsculas preservadas" }
      - { name: symbol, type: string, required: true, max: 32 }
      - { name: name, type: string, required: false, max: 120 }
      - { name: decimals, type: integer, required: true, min: 0, max: 18 }
      - { name: firstSeenAt, type: instant, required: true, constraints: "UTC; primeira coleta que trouxe o token; marca a entrada na foto da lista" }
      - { name: tokenCreatedAt, type: instant, required: false, constraints: "UTC; vem do campo CREATED_AT; vazio = idade unknown" }
    relationships:
      - { to: MarketObservation, cardinality: "1:N", direction: "Token -> MarketObservation" }
      - { to: ObservationSample, cardinality: "1:N", direction: "Token -> ObservationSample" }
      - { to: RiskAssessment, cardinality: "1:0..1", direction: "Token -> RiskAssessment", constraints: "só a avaliação mais recente é guardada (Q8)" }

  - name: MarketObservation
    owner: MarketData
    description: O valor atual de um campo de um token informado por uma fonte. Uma linha por token, fonte e campo.
    identifier: observationId
    attributes:
      - { name: observationId, type: uuid, required: true, unique: true }
      - { name: chain, type: string, required: true, references: Token.chain }
      - { name: mint, type: string, required: true, references: Token.mint }
      - { name: sourceId, type: string, required: true, references: DataSource.sourceId }
      - { name: field, type: enum, required: true, allowed: [PRICE, LIQUIDITY, VOLUME_24H, HOLDERS, CREATED_AT] }
      - { name: status, type: enum, required: true, allowed: [KNOWN, UNKNOWN] }
      - { name: value, type: decimal, required: false, constraints: "decimal exato; obrigatório se KNOWN, vazio se UNKNOWN; nunca ponto flutuante" }
      - { name: unknownReason, type: enum, required: false, allowed: [NOT_PROVIDED, SOURCE_UNAVAILABLE, NOT_YET_OBSERVED], constraints: "obrigatório se UNKNOWN, vazio se KNOWN" }
      - { name: observedAt, type: instant, required: true, constraints: "UTC; horário de observação informado pela fonte, não o da busca" }
    constraints:
      - "Única por (chain, mint, sourceId, field)."
      - "Exatamente um de value e unknownReason, conforme status."

  - name: ObservationSample
    owner: MarketData
    added_in: u4
    description: Histórico de observações dos últimos 24 horas, usado nas janelas de preço e volume (C11) e na variação de preço de 1 hora (Q10).
    identifier: sampleId
    attributes:
      - { name: sampleId, type: uuid, required: true, unique: true }
      - { name: chain, type: string, required: true, references: Token.chain }
      - { name: mint, type: string, required: true, references: Token.mint }
      - { name: sourceId, type: string, required: true, references: DataSource.sourceId }
      - { name: field, type: enum, required: true, allowed: [PRICE, LIQUIDITY, VOLUME_24H, HOLDERS] }
      - { name: status, type: enum, required: true, allowed: [KNOWN, UNKNOWN] }
      - { name: value, type: decimal, required: false, constraints: "mesma regra de MarketObservation" }
      - { name: unknownReason, type: enum, required: false, allowed: [NOT_PROVIDED, SOURCE_UNAVAILABLE, NOT_YET_OBSERVED] }
      - { name: observedAt, type: instant, required: true }
    constraints:
      - "Única por (chain, mint, sourceId, field, observedAt): a mesma observação recebida de novo não duplica."
      - "Apagada quando observedAt fica mais de 24 horas no passado (ADR-008)."

  - name: SourceStatusMark
    owner: MarketData
    added_in: u4
    description: Marca global que só cresce, alterada a cada mudança de estado de uma fonte (C18, SourceStatusMarks).
    identifier: singleton
    attributes:
      - { name: mark, type: integer, required: true, min: 0, default: 0 }
    constraints:
      - "Atualizada na mesma transação que muda DataSource.status."

  - name: RiskWeightSet
    owner: RiskAssessment
    added_in: u4
    description: Uma versão dos pesos, curvas de fator, faixas e cobertura mínima do indicador. Imutável depois de ativada.
    identifier: version
    attributes:
      - { name: version, type: integer, required: true, unique: true, min: 1, constraints: "sequencial, nunca reutilizada" }
      - { name: status, type: enum, required: true, allowed: [DRAFT, ACTIVE, RETIRED], default: DRAFT }
      - { name: factors, type: list<FactorDefinition>, required: true, constraints: "um item por fator, sem repetir factorId" }
      - { name: lowMax, type: integer, required: true, min: 0, max: 98, constraints: "maior indicador da faixa Baixo" }
      - { name: mediumMax, type: integer, required: true, min: 1, max: 99, constraints: "maior indicador da faixa Médio; lowMax < mediumMax" }
      - { name: minCoverage, type: percentage, required: true, min: "0.0001", max: "100.0000", constraints: "pontos percentuais, escala 4 (tabela Escalas do contrato); parte dos pesos com dado conhecido" }
      - { name: createdAt, type: instant, required: true }
      - { name: createdBy, type: string, required: true, constraints: "ator mascarado do administrador ou 'system' para a versão 1" }
      - { name: activatedAt, type: instant, required: false, constraints: "preenchido ao ativar" }
      - { name: retiredAt, type: instant, required: false }
    constraints:
      - "No máximo uma versão ACTIVE ao mesmo tempo; sempre existe uma ACTIVE depois da migração inicial."
      - "Soma dos pesos dos fatores = 100.0000 exato."
      - "Exatamente os quatro fatores do catálogo do beta (LIQUIDITY, HOLDERS, VOLUME_24H, PRICE_CHANGE_1H), cada um uma vez."
    relationships:
      - { to: RiskAssessment, cardinality: "1:N", direction: "RiskWeightSet -> RiskAssessment" }

  - name: FactorDefinition
    owner: RiskAssessment
    added_in: u4
    kind: value-object
    description: Um fator do indicador dentro de uma versão de pesos.
    attributes:
      - { name: factorId, type: enum, required: true, allowed: [LIQUIDITY, HOLDERS, VOLUME_24H, PRICE_CHANGE_1H] }
      - { name: weight, type: percentage, required: true, min: "0.0001", max: "100.0000", constraints: "pontos percentuais, escala 4" }
      - { name: curve, type: list<CurvePoint>, required: true, constraints: "2 a 8 pontos, entradas estritamente crescentes; riscos de 0 a 100" }

  - name: CurvePoint
    owner: RiskAssessment
    added_in: u4
    kind: value-object
    description: Um ponto da curva que converte o valor de entrada de um fator em risco de 0 a 100 (interpolação linear entre pontos).
    attributes:
      - { name: input, type: decimal, required: true, min: "0" }
      - { name: risk, type: integer, required: true, min: 0, max: 100 }

  - name: RiskAssessment
    owner: RiskAssessment
    added_in: u4
    description: A avaliação mais recente de um token, guardada após cada coleta (Q8).
    identifier: assessmentId
    attributes:
      - { name: assessmentId, type: uuid, required: true, unique: true }
      - { name: chain, type: string, required: true, references: Token.chain }
      - { name: mint, type: string, required: true, references: Token.mint }
      - { name: weightSetVersion, type: integer, required: true, references: RiskWeightSet.version }
      - { name: status, type: enum, required: true, allowed: [AVAILABLE, UNAVAILABLE] }
      - { name: score, type: integer, required: false, min: 0, max: 100, constraints: "obrigatório se AVAILABLE, vazio se UNAVAILABLE" }
      - { name: band, type: enum, required: false, allowed: [LOW, MEDIUM, HIGH], constraints: "obrigatório se AVAILABLE" }
      - { name: bandRaisedByUnknown, type: boolean, required: true, default: false, constraints: "true quando a faixa calculada era LOW e passou a MEDIUM por fator Unknown (BR3.4)" }
      - { name: unavailableReason, type: enum, required: false, allowed: [INSUFFICIENT_COVERAGE, SOURCE_UNAVAILABLE], constraints: "obrigatório se UNAVAILABLE" }
      - { name: coverage, type: percentage, required: true, min: "0.0000", max: "100.0000", constraints: "pontos percentuais, escala 4" }
      - { name: decisionHint, type: enum, required: true, allowed: [NO_TRADE, ELIGIBLE], constraints: "NO_TRADE se UNAVAILABLE" }
      - { name: factors, type: list<FactorResult>, required: true, constraints: "um item por fator da versão usada, na ordem da versão" }
      - { name: inputsFingerprint, type: string, required: true, constraints: "resumo da versão de pesos e, de cada fator, do valor, fonte e horário de observação da entrada (para PRICE_CHANGE_1H, também do preço e horário da amostra de 1 hora); a marca de desatualizado não entra; mesmo resumo = mesmo resultado" }
      - { name: computedAt, type: instant, required: true, constraints: "UTC; exposto no detalhe (acréscimo ao Assessment do C2)" }
    constraints:
      - "Única por (chain, mint): uma avaliação nova substitui a anterior."
      - "Uma avaliação só substitui a guardada se a versão de pesos dela for maior ou igual à da guardada."
      - "Nos caminhos UNAVAILABLE, factors continua com um item por fator, coverage é calculada e score e band ficam vazios (BR3.3, BR3.8)."

  - name: FactorResult
    owner: RiskAssessment
    added_in: u4
    kind: value-object
    description: O resultado de um fator numa avaliação, com a entrada usada.
    attributes:
      - { name: factorId, type: enum, required: true, allowed: [LIQUIDITY, HOLDERS, VOLUME_24H, PRICE_CHANGE_1H] }
      - { name: weight, type: percentage, required: true }
      - { name: input, type: MarketValue, required: true, constraints: "Known com valor, fonte e horário de observação, ou Unknown com motivo. A marca de desatualizado não é guardada: é calculada na leitura pela BR1.1 a partir do horário guardado" }
      - { name: factorRisk, type: integer, required: true, min: 0, max: 100, constraints: "100 quando a entrada é Unknown (Q3)" }
      - { name: contribution, type: decimal-or-unknown, required: true, constraints: "peso / 100 × risco do fator, em pontos do indicador, escala 4; UNKNOWN quando a entrada é Unknown" }

  - name: ResolvedField
    owner: MarketData
    added_in: u4
    kind: value-object
    description: O valor exibido de um campo de um token depois de juntar as fontes (não é guardado).
    attributes:
      - { name: value, type: MarketValue, required: true }
      - { name: divergent, type: boolean, required: true }
      - { name: origins, type: list<MarketValue>, required: true, constraints: "os valores conhecidos e não desatualizados de cada fonte, usados para a divergência" }

  - name: ScannerSnapshotCursor
    owner: Scanner
    added_in: u4
    kind: value-object
    description: O conteúdo do cursor opaco da lista; liga a página à foto, aos filtros e à ordenação.
    attributes:
      - { name: snapshotAt, type: instant, required: true }
      - { name: filtersDigest, type: string, required: true }
      - { name: sort, type: enum, required: true, allowed: [AGE, LIQUIDITY, VOLUME_24H, HOLDERS, RISK_SCORE] }
      - { name: order, type: enum, required: true, allowed: [ASC, DESC] }
      - { name: lastSortValue, type: decimal-or-unknown, required: true }
      - { name: lastChain, type: string, required: true }
      - { name: lastMint, type: string, required: true }
      - { name: offsetShown, type: integer, required: true, min: 0, constraints: "para 'Exibindo X–Y de N'" }

  - name: DivergencePolicy
    owner: MarketData
    added_in: u4
    kind: configuration
    description: Tolerância relativa de divergência por campo (Q7), lida da configuração do ambiente.
    attributes:
      - { name: field, type: enum, required: true, allowed: [PRICE, LIQUIDITY, VOLUME_24H, HOLDERS] }
      - { name: tolerance, type: percentage, required: true, default: "5.0000", min: "0.0000", max: "100.0000", constraints: "pontos percentuais, escala 4" }

  - name: ProhibitedTermCatalog
    owner: RiskAssessment
    added_in: u4
    kind: configuration
    description: Lista versionada de termos proibidos nos textos da interface (FR6.4, AC3.2.3).
    attributes:
      - { name: version, type: integer, required: true, min: 1 }
      - { name: terms, type: list<string>, required: true, constraints: "comparação sem diferença de maiúsculas e acentos" }
      - { name: reviewedBy, type: string, required: true, constraints: "o responsável do projeto" }
```

## Resumo

| Entidade | Dono | Novidade da U4 |
|---|---|---|
| `DataSource` | MarketData | categoria da falha e intervalo de coleta por fonte |
| `Token` | MarketData | — (já existe) |
| `MarketObservation` | MarketData | — (valor atual por token, fonte e campo) |
| `ObservationSample` | MarketData | histórico de 24 horas para janelas e variação de preço |
| `SourceStatusMark` | MarketData | marca global de estado das fontes (C18) |
| `RiskWeightSet`, `FactorDefinition`, `CurvePoint` | RiskAssessment | versões de pesos, curvas, faixas e cobertura mínima |
| `RiskAssessment`, `FactorResult` | RiskAssessment | última avaliação por token |
| `ResolvedField`, `ScannerSnapshotCursor` | MarketData, Scanner | valores derivados, não guardados |
| `DivergencePolicy`, `ProhibitedTermCatalog` | MarketData, RiskAssessment | configuração versionada |

Nenhuma entidade da U4 guarda dado de cliente: dados de mercado e avaliações são globais. Por isso não há `tenantId` nas tabelas da U4; as rotas continuam exigindo sessão (C2).
