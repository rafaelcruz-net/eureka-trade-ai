# Regras de negócio — U4 Descoberta e avaliação

Regras dos componentes MarketData, Scanner e RiskAssessment, a partir de [requirements.md](../../../inception/requirements-analysis/requirements.md) (FR4.5 a FR4.7, FR5.2 a FR5.4, FR6.1 a FR6.4, NFR1), das histórias US2.2, US2.3, US2.4, US3.1 e US3.2 de [stories.md](../../../inception/user-stories/stories.md), dos contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das respostas Q1 a Q10 de [functional-design-questions.md](functional-design-questions.md). O bloco YAML é a fonte da verdade. Os valores numéricos da versão 1 são hipótese a calibrar, como no PRD §9.

```yaml
rules:
  # BR1 - Dados de mercado: frescor, fontes e divergência
  - id: BR1.1
    statement: Um valor está desatualizado quando a idade passa estritamente de 5 minutos; a idade é o instante atual menos o horário de observação.
    category: calculation
    applies_to: MarketObservation, ResolvedField
    trigger: toda leitura de valor
    logic: "SE agora - observedAt > 5:00 ENTÃO stale = true; SE agora - observedAt <= 5:00 ENTÃO stale = false. 'agora' vem do relógio injetável; nunca o horário da busca."
    violation: não se aplica (cálculo)
    source: FR4.5, NFR1, AC2.3.1, AC2.3.4

  - id: BR1.2
    statement: Quando mais de uma fonte informa o mesmo campo de um token, vale o valor conhecido mais recente.
    category: calculation
    applies_to: ResolvedField
    trigger: toda leitura de campo de token
    logic: "Entre as observações KNOWN de fontes que não estão FAILED, escolher a de observedAt mais recente; empate no horário: menor sourceId. SE nenhuma KNOWN ENTÃO Unknown com o motivo da BR1.9."
    violation: não se aplica (cálculo)
    source: FR4.7, Q6

  - id: BR1.3
    statement: A falha de uma fonte degrada só o que depende dela.
    category: policy
    applies_to: DataSource, ResolvedField, Scanner
    trigger: leitura com uma ou mais fontes FAILED
    logic: "Observações de fonte FAILED não entram na BR1.2 nem na BR1.7. Um token aparece na lista só se ao menos uma fonte que o informa não está FAILED. A resposta lista o estado de cada fonte, com a última atualização."
    violation: não se aplica
    source: FR4.6, AC2.3.2

  - id: BR1.4
    statement: Com todas as fontes FAILED, a lista não mostra tokens e responde que as fontes estão indisponíveis.
    category: policy
    applies_to: Scanner
    trigger: consulta da lista com todas as fontes FAILED
    logic: "SE alguma fonte está OK ou DEGRADED ENTÃO a lista segue normalmente. SENÃO, SE alguma fonte está PENDING ENTÃO 200 com items vazio e o estado de cada fonte (a tela explica que a primeira coleta ainda não terminou). SENÃO (todas FAILED) 503 com código SOURCE_UNAVAILABLE e, no ProblemDetail, o estado de cada fonte com a última atualização; nunca valores zerados ou antigos sem marca."
    violation: resposta 503 SOURCE_UNAVAILABLE em ProblemDetail com a extensão sources
    source: FR4.6, AC2.3.3

  - id: BR1.5
    statement: O estado de cada fonte segue o resultado da última coleta.
    category: policy
    applies_to: DataSource
    trigger: fim de cada coleta de uma fonte
    logic: "Fonte registrada nasce PENDING e lastUpdatedAt vazio. Coleta concluída sem nova tentativa: OK. Concluída depois de nova tentativa, ou com parte dos tokens recusada pela validação: DEGRADED. Falhou depois das 2 novas tentativas do C19: FAILED com a categoria da falha. lastAttemptAt sempre é atualizado; lastUpdatedAt só em OK ou DEGRADED. O estado é guardado: reiniciar a aplicação não o muda."
    violation: não se aplica
    source: FR4.6, C19

  - id: BR1.6
    statement: Valores de fontes diferentes são casados por chain + mint e por campo, nunca por símbolo.
    category: constraint
    applies_to: MarketObservation, ResolvedField
    trigger: junção de observações de várias fontes
    logic: "Duas observações descrevem o mesmo dado SE e SOMENTE SE chain, mint e field são iguais. Tokens com o mesmo símbolo e mints diferentes são tokens diferentes."
    violation: não se aplica
    source: FR4.2, AC2.4.2

  - id: BR1.7
    statement: Um campo é divergente quando outra fonte informa um valor que difere do valor exibido acima da tolerância do campo.
    category: calculation
    applies_to: ResolvedField, DivergencePolicy
    trigger: leitura de campo com duas ou mais observações KNOWN não desatualizadas de fontes diferentes
    logic: "Para cada outra observação, comparar sem divisão, em decimal exato e sem arredondar: SE |outro - exibido| x 100 > tolerância x |exibido| ENTÃO divergente; SENÃO não divergente. Com exibido = 0, a comparação dá divergente sempre que outro != 0. Observações desatualizadas não entram. Tolerância padrão 5.0000 pontos percentuais por campo, configurável."
    violation: não se aplica (o campo mostra as origens e o aviso)
    source: FR4.7, AC2.4.1, Q7

  - id: BR1.8
    statement: O histórico de observações guarda só as últimas 24 horas.
    category: policy
    applies_to: ObservationSample, Token
    trigger: limpeza agendada a cada hora
    logic: "Apagar amostras com observedAt mais de 24 horas antes de agora. Um token sem nenhuma observação nas últimas 24 horas não aparece na lista; a linha do token não é apagada, porque outras Units o referenciam."
    violation: não se aplica
    source: ADR-008

  - id: BR1.9
    statement: Campo ausente é Unknown com o motivo certo, nunca zero.
    category: validation
    applies_to: MarketObservation, ResolvedField
    trigger: coleta e leitura
    logic: "Campo não informado pela fonte: Unknown(NOT_PROVIDED). Campo cujas fontes estão todas FAILED: Unknown(SOURCE_UNAVAILABLE). Campo que exige histórico ainda inexistente: Unknown(NOT_YET_OBSERVED). Valor 0 informado pela fonte continua Known(0)."
    violation: não se aplica
    source: FR4.4, C11, C19

  - id: BR1.10
    statement: A variação de preço de 1 hora é a diferença relativa entre o preço atual e o preço de 1 hora atrás.
    category: calculation
    applies_to: ObservationSample, FactorResult
    trigger: cálculo do fator PRICE_CHANGE_1H
    logic: "Preço atual = valor exibido de PRICE (BR1.2). Preço anterior = a amostra KNOWN de PRICE mais recente com observedAt entre agora - 65 min e agora - 60 min, de qualquer fonte não FAILED. variação = |atual - anterior| / anterior x 100, em pontos percentuais, arredondada para escala 4 meio para cima; a curva usa o valor arredondado. SE não há amostra na janela ENTÃO Unknown(NOT_YET_OBSERVED). SE anterior = 0 ou o preço atual é Unknown ENTÃO Unknown com o motivo correspondente."
    violation: não se aplica
    source: FR6.1, Q1, Q10

  - id: BR1.11
    statement: Toda mudança de estado de uma fonte aumenta a marca global de estado das fontes.
    category: policy
    applies_to: SourceStatusMark
    trigger: mudança de DataSource.status
    logic: "Na mesma transação que muda o estado, mark = mark + 1. Coleta que não muda o estado não muda a marca."
    violation: não se aplica
    source: C18

  # BR2 - Scanner: filtros, ordem e paginação
  - id: BR2.1
    statement: Os filtros são combinados e as fronteiras são inclusivas.
    category: validation
    applies_to: Scanner
    trigger: consulta com filtros
    logic: "Um token passa SE passa em todos os filtros ativos: liquidez >= minLiquidity; volume 24 h >= minVolume24h; holders >= minHolders; idade <= maxAgeMinutes; indicador <= maxRiskScore; informado pela fonte sourceId (fonte não FAILED). Valor igual ao limite passa; o menor incremento além do limite não passa."
    violation: token fora da lista
    source: FR5.2, AC2.2.1

  - id: BR2.2
    statement: Token com o campo filtrado Unknown fica fora e é contado.
    category: policy
    applies_to: Scanner
    trigger: consulta com filtros
    logic: "SE um campo de filtro ativo é Unknown ENTÃO o token fica fora. excludedByUnknown = quantos tokens da foto inteira ficaram fora só por campo Unknown, isto é, passariam em todos os filtros cujo campo é conhecido. Cada token conta uma vez."
    violation: token fora da lista, somado em excludedByUnknown
    source: FR5.2, AC2.2.2

  - id: BR2.3
    statement: No filtro e na ordenação por risco, indicador indisponível vale como Unknown.
    category: policy
    applies_to: Scanner, RiskAssessment
    trigger: filtro maxRiskScore ou ordenação RISK_SCORE
    logic: "Avaliação UNAVAILABLE ou ausente: fora do filtro maxRiskScore e somada em excludedByUnknown; na ordenação, vai para o fim. Indisponível nunca é tratado como risco baixo."
    violation: token fora da lista
    source: FR6.2, AC2.2.2

  - id: BR2.4
    statement: Filtros sem resultado dão o estado vazio com a explicação e a ação de limpar.
    category: policy
    applies_to: Scanner
    trigger: consulta com filtros ativos e nenhum token
    logic: "SE há fontes disponíveis, filtros ativos e nenhum item ENTÃO a resposta tem items vazio e o total 0; a tela mostra o estado vazio, quantos ficaram fora por Unknown e 'Limpar filtros'. Lista vazia sem filtro ativo explica que ainda não há tokens; com fontes só PENDING, explica que a primeira coleta ainda não terminou (BR1.4)."
    violation: não se aplica
    source: FR5.3, AC2.2.3

  - id: BR2.5
    statement: A ordem da lista é determinística, com Unknown sempre no fim.
    category: policy
    applies_to: Scanner
    trigger: toda consulta
    logic: "Ordenar pelo campo escolhido (padrão AGE, mais novo primeiro) e direção escolhida; valores Unknown vão para o fim nas duas direções; empate desfeito por mint e depois chain, em ordem crescente."
    violation: não se aplica
    source: FR5.1, AC2.1.5

  - id: BR2.6
    statement: A lista é uma foto; novidades esperam "Atualizar lista".
    category: policy
    applies_to: Scanner, ScannerSnapshotCursor
    trigger: primeira página e páginas seguintes
    logic: "A primeira página fixa snapshotAt = agora. Só tokens com firstSeenAt <= snapshotAt entram nas páginas dessa foto. pendingNewTokens = tokens com firstSeenAt > snapshotAt que passam nos filtros. Os valores das linhas são sempre os atuais. 'Atualizar lista' pede uma primeira página nova."
    violation: não se aplica
    source: FR5.2, C2

  - id: BR2.7
    statement: Parâmetros inválidos e cursor de outra consulta são recusados.
    category: validation
    applies_to: Scanner
    trigger: toda consulta
    logic: "Decimal mal formado ou negativo, inteiro fora do intervalo do contrato, sort ou order fora da lista, parâmetro desconhecido: 400 VALIDATION_FAILED com a lista de campos. Cursor ilegível, ou cujo filtersDigest, sort ou order difere dos parâmetros: 400 VALIDATION_FAILED com o campo cursor."
    violation: 400 em ProblemDetail
    source: C2, DP-3

  - id: BR2.8
    statement: A idade é comparada pela duração exata.
    category: calculation
    applies_to: Scanner
    trigger: filtro maxAgeMinutes e exibição da idade
    logic: "idade = agora - tokenCreatedAt. Passa no filtro SE idade <= maxAgeMinutes minutos exatos. ageMinutes exibido = parte inteira dos minutos. tokenCreatedAt ausente: idade Unknown."
    violation: não se aplica
    source: AC2.2.1

  # BR3 - Avaliação de risco
  - id: BR3.1
    statement: Cada fator converte o valor de entrada em risco de 0 a 100 pela curva da versão de pesos.
    category: calculation
    applies_to: FactorDefinition, FactorResult
    trigger: cálculo da avaliação
    logic: "Entrada menor ou igual ao primeiro ponto: risco do primeiro ponto. Maior ou igual ao último: risco do último. Entre dois pontos: interpolação linear, arredondada para o inteiro mais próximo, meio para cima. Entradas: LIQUIDITY e VOLUME_24H pelo valor exibido (BR1.2); HOLDERS pelo valor exibido; PRICE_CHANGE_1H pela BR1.10."
    violation: não se aplica
    source: FR6.1, Q1

  - id: BR3.2
    statement: O indicador é a soma ponderada dos riscos dos fatores, de 0 a 100, sempre igual para as mesmas entradas e a mesma versão.
    category: calculation
    applies_to: RiskAssessment
    trigger: cálculo da avaliação
    logic: "contribuição de cada fator = peso / 100 x risco do fator, em decimal exato com escala 4; indicador = soma das contribuições arredondada para o inteiro mais próximo, meio para cima; resultado entre 0 e 100 inclusive. Mesmo inputsFingerprint dá o mesmo resultado. Sem ponto flutuante."
    violation: não se aplica
    source: FR6.1, AC3.1.2, AC3.1.4

  - id: BR3.3
    statement: Abaixo da cobertura mínima o indicador fica indisponível e a avaliação é NO_TRADE.
    category: policy
    applies_to: RiskAssessment
    trigger: cálculo da avaliação
    logic: "cobertura = soma dos pesos dos fatores com entrada Known. SE cobertura < minCoverage ENTÃO status UNAVAILABLE, motivo INSUFFICIENT_COVERAGE, sem indicador e sem faixa, decisionHint NO_TRADE; os fatores continuam calculados e guardados (entrada, risco e contribuição), para o detalhe mostrar o que faltou. SE cobertura >= minCoverage ENTÃO status AVAILABLE e decisionHint ELIGIBLE. Pesos e cobertura em pontos percentuais. Versão 1: minCoverage 80.0000."
    violation: não se aplica
    source: FR6.2, AC3.2.1, Q2

  - id: BR3.4
    statement: Fator Unknown entra com o risco máximo e impede o nível Baixo.
    category: policy
    applies_to: FactorResult, RiskAssessment
    trigger: cálculo com algum fator Unknown e cobertura suficiente
    logic: "Fator com entrada Unknown: risco do fator = 100 e entra na soma; a contribuição é mostrada como UNKNOWN. SE algum fator é Unknown E a faixa calculada é LOW ENTÃO a faixa passa a MEDIUM e bandRaisedByUnknown = true; o painel explica 'Nível elevado para Médio porque há fator sem dado'. Unknown nunca conta como risco zero."
    violation: não se aplica
    source: FR6.2, AC3.2.2, Q3

  - id: BR3.5
    statement: A faixa vem dos limites da versão de pesos.
    category: calculation
    applies_to: RiskAssessment, RiskWeightSet
    trigger: cálculo de avaliação AVAILABLE
    logic: "indicador <= lowMax: LOW; lowMax < indicador <= mediumMax: MEDIUM; indicador > mediumMax: HIGH. Versão 1: lowMax 39, mediumMax 69 (Baixo 0 a 39, Médio 40 a 69, Alto 70 a 100)."
    violation: não se aplica
    source: FR6.1, Q4

  - id: BR3.6
    statement: Entrada desatualizada entra no cálculo e leva a marca.
    category: policy
    applies_to: FactorResult
    trigger: cálculo com entrada desatualizada
    logic: "Valor Known com stale = true conta como conhecido para cobertura e indicador. A marca de desatualizado do fator não é guardada: o detalhe a calcula na leitura pela BR1.1 a partir do horário de observação guardado, e mostra esse horário."
    violation: não se aplica
    source: FR6.3, Q5

  - id: BR3.7
    statement: As avaliações são recalculadas após cada coleta e quando uma versão de pesos é ativada; só a última é guardada.
    category: policy
    applies_to: RiskAssessment
    trigger: fim de cada coleta; ativação de uma versão de pesos
    logic: "Recalcular a avaliação de cada token com observação nas últimas 24 horas e substituir a guardada. Os recálculos rodam um de cada vez; um pedido que chega durante um recálculo é juntado no próximo. Uma avaliação só substitui a guardada se a versão de pesos dela for maior ou igual à da guardada. SE inputsFingerprint e versão não mudaram ENTÃO manter a guardada sem regravar. Lista, filtro e detalhe leem a avaliação guardada, com computedAt."
    violation: não se aplica
    source: FR6.1, Q8

  - id: BR3.8
    statement: Token sem fonte disponível fica com a avaliação indisponível.
    category: policy
    applies_to: RiskAssessment
    trigger: cálculo para token cujas fontes estão todas FAILED
    logic: "SE todas as fontes que informam o token estão FAILED ENTÃO status UNAVAILABLE, motivo SOURCE_UNAVAILABLE, decisionHint NO_TRADE; cada fator é guardado com entrada Unknown(SOURCE_UNAVAILABLE), risco 100 e contribuição UNKNOWN, e a cobertura é 0.0000."
    violation: não se aplica
    source: FR6.2, C2

  - id: BR3.9
    statement: A avaliação mostra como foi feita.
    category: policy
    applies_to: RiskAssessment, FactorResult
    trigger: leitura do detalhe
    logic: "O detalhe traz indicador, nível por escrito, a frase 'maior significa mais risco', o rótulo 'Indicador em validação' (inValidation = true), a versão dos pesos, a cobertura e a cobertura mínima, o horário do cálculo (computedAt), a explicação de nível elevado quando bandRaisedByUnknown, e cada fator com peso, valor de entrada, fonte, horário de observação, marca de desatualizado (calculada na leitura) e contribuição."
    violation: não se aplica
    source: FR6.1, FR6.3, AC3.1.2, AC3.1.3

  # BR4 - Versões de pesos
  - id: BR4.1
    statement: Uma versão de pesos só é aceita se estiver completa e coerente.
    category: validation
    applies_to: RiskWeightSet, FactorDefinition, CurvePoint
    trigger: criação de versão
    logic: "Pesos em pontos percentuais com escala 4, cada um > 0, soma exatamente 100.0000; exatamente os quatro fatores do catálogo do beta (LIQUIDITY, HOLDERS, VOLUME_24H, PRICE_CHANGE_1H), cada um uma vez; curva com 2 a 8 pontos, entradas estritamente crescentes e não negativas, riscos de 0 a 100; 0 <= lowMax < mediumMax < 100; 0 < minCoverage <= 100.0000."
    violation: 400 VALIDATION_FAILED com a lista de campos; nada é gravado
    source: FR6.1, Q9

  - id: BR4.2
    statement: Só o administrador cria e ativa versões; a ativação é auditada e a versão ativa não muda.
    category: authorization
    applies_to: RiskWeightSet
    trigger: criação e ativação de versão
    logic: "Criar: só papel ADMIN, versão nasce DRAFT. Ativar uma DRAFT: na mesma transação, a anterior passa a RETIRED, a nova a ACTIVE, e um evento RISK_WEIGHTS_ACTIVATED é publicado com a versão anterior e a nova. Versão ACTIVE ou RETIRED não pode ser alterada nem ativada de novo (exceto a ativa, abaixo). Ativar de novo a versão já ativa não faz nada nem gera evento. Ativações simultâneas são feitas uma depois da outra, cada uma retirando a ativa do momento. A ativação dispara a BR3.7."
    violation: 403 FORBIDDEN_ROLE auditado para não administrador; 404 NOT_FOUND para versão inexistente; 409 WEIGHT_SET_NOT_EDITABLE para alterar versão ativada ou ativar versão RETIRED (código novo, a acrescentar ao contrato C8 junto com a rota da U8)
    source: FR6.1, Q9, DP-2

  - id: BR4.3
    statement: A versão 1 é criada pela migração inicial com os valores escolhidos.
    category: constraint
    applies_to: RiskWeightSet
    trigger: migração inicial da U4
    logic: "Versão 1 ACTIVE, createdBy 'system'. Pesos: LIQUIDITY 35.0000, HOLDERS 25.0000, VOLUME_24H 20.0000, PRICE_CHANGE_1H 20.0000. minCoverage 80.0000. lowMax 39, mediumMax 69. Curvas: LIQUIDITY (1 000:100, 10 000:60, 50 000:25, 100 000:0); HOLDERS (50:100, 200:60, 1 000:25, 5 000:0); VOLUME_24H (1 000:100, 10 000:60, 100 000:25, 1 000 000:0); PRICE_CHANGE_1H em % (0:0, 10:30, 30:70, 60:100)."
    violation: não se aplica
    source: Q2, Q4, Q10

  # BR5 - Telas e textos
  - id: BR5.1
    statement: Nenhum texto da interface contém termo da lista proibida.
    category: validation
    applies_to: ProhibitedTermCatalog
    trigger: verificação automática da suíte
    logic: "Para cada texto do catálogo de interface do app do convidado, sem diferença de maiúsculas e acentos: SE contém um termo da lista ('lucro garantido', 'sem risco', '100% seguro', 'detecta golpes' e os revisados pelo responsável) ENTÃO a verificação falha nomeando o texto e o termo. O nível de risco nunca usa a palavra 'seguro'."
    violation: falha da suíte
    source: FR6.4, AC3.2.3

  - id: BR5.2
    statement: Enquanto a avaliação carrega, nenhum indicador provisório aparece.
    category: policy
    applies_to: tela do painel lateral
    trigger: abertura do painel lateral
    logic: "Até a resposta da avaliação chegar, o painel mostra 'Carregando avaliação' só na área da avaliação; nenhum número, faixa ou valor anterior."
    violation: não se aplica
    source: AC3.1.5, CT-UX-2

  - id: BR5.3
    statement: O painel lateral abre e fecha sem perder a posição, os filtros e o foco da lista.
    category: policy
    applies_to: tela do scanner
    trigger: seleção de linha; fechamento do painel
    logic: "Clique ou Enter abre o painel ao lado da lista sem recarregar a lista nem mudar filtros, ordenação, página ou rolagem. Esc ou 'Fechar' fecha o painel e devolve o foco à mesma linha. Atualização de valores não muda a linha selecionada nem o foco."
    violation: não se aplica
    source: FR5.4, AC3.1.1, AC2.1.6

  - id: BR5.4
    statement: Indicador indisponível aparece como '--' com o motivo acessível.
    category: policy
    applies_to: tela do scanner, painel lateral
    trigger: avaliação UNAVAILABLE
    logic: "Coluna Risco: '--' com o motivo em texto ao passar o mouse e ao receber foco ('Dados insuficientes para avaliar' ou 'Fontes indisponíveis'). Painel: 'Indicador indisponível', o motivo e 'Avaliação: NO_TRADE'."
    violation: não se aplica
    source: AC3.2.1, CT-UX-7

  - id: BR5.5
    statement: Valor desatualizado mostra marca em texto ou ícone e o horário da última atualização.
    category: policy
    applies_to: tela do scanner, painel lateral
    trigger: valor com stale = true
    logic: "Valor com marca 'Desatualizado' (texto ou ícone com nome acessível) e 'Atualizado às HH:MM' no fuso do usuário; nunca só por cor."
    violation: não se aplica
    source: FR4.5, AC2.3.1, CT-UX-7

  - id: BR5.6
    statement: Fonte com falha aparece num aviso com o nome e a última atualização.
    category: policy
    applies_to: tela do scanner
    trigger: alguma fonte FAILED; todas FAILED
    logic: "Uma ou mais FAILED: aviso no topo nomeando cada fonte e a última atualização; a lista continua. Todas FAILED: aviso explicando a falha, lista vazia com explicação e 'Tentar de novo'."
    violation: não se aplica
    source: FR4.6, AC2.3.2, AC2.3.3, CT-UX-3

  - id: BR5.7
    statement: Campo divergente mostra as duas origens e o aviso.
    category: policy
    applies_to: tela do scanner, painel lateral
    trigger: ResolvedField.divergent = true
    logic: "O campo mostra o aviso 'Fontes divergem' em texto e, ao passar o mouse ou receber foco, cada origem com fonte, valor e horário."
    violation: não se aplica
    source: FR4.7, AC2.4.1
```

## Resumo das regras

| Grupo | Regras | O que decide |
|---|---|---|
| BR1 Dados de mercado | BR1.1 a BR1.11 | frescor de 5 minutos, valor mais recente entre fontes, degradação por fonte, 503 com todas as fontes fora, estado das fontes, casamento por chain + mint, divergência de 5%, retenção de 24 horas, motivos de `unknown`, variação de preço de 1 hora, marca de estado |
| BR2 Scanner | BR2.1 a BR2.8 | filtros inclusivos, exclusão e contagem de `unknown`, risco indisponível como `unknown`, estado vazio, ordem com `unknown` no fim, foto da lista, validação de parâmetros e cursor, idade exata |
| BR3 Avaliação | BR3.1 a BR3.9 | curva por fator, soma ponderada determinística, cobertura mínima de 80% com NO_TRADE, fator `unknown` com risco máximo e nunca Baixo, faixas 39/69, dado desatualizado marcado, recálculo após coleta, fontes fora, explicação completa |
| BR4 Versões de pesos | BR4.1 a BR4.3 | validação, criação e ativação só pelo administrador com auditoria, versão 1 |
| BR5 Telas e textos | BR5.1 a BR5.7 | termos proibidos, sem indicador provisório, painel que preserva a lista, `--` com motivo, marca de desatualizado, aviso de fonte, divergência com as duas origens |
