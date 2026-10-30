# Perguntas do desenho funcional — U4 Descoberta e avaliação

Contexto: a U1 já entregou o `MarketData` mínimo (porta `MarketSourceAdapter`, uma fonte simulada, campos `PRICE`, `LIQUIDITY`, `VOLUME_24H` e `HOLDERS`, valor `Known` ou `Unknown` com fonte e horário) e o `Scanner` mínimo (lista paginada por cursor, sem filtros e sem risco). A U4 completa os dois e entrega o `RiskAssessment` (US2.2, US2.3, US2.4, US3.1, US3.2; FR4.5 a FR4.7, FR5.2 a FR5.4, FR6.1 a FR6.4). Os contratos C2, C11, C12, C18 e C19 já fixam a forma da API e das interfaces. As perguntas abaixo cobrem só o que ainda não foi decidido.

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- Fronteiras de filtro inclusivas; "desatualizado" é idade estritamente acima de 5:00; a idade é agora menos o horário de observação (convenções de teste de `stories.md`).
- Token com o campo filtrado `unknown` fica fora do filtro e entra em `excludedByUnknown`, contado no conjunto filtrado inteiro (AC2.2.2).
- Casamento entre fontes por chain + mint e por campo, nunca por símbolo (AC2.4.2).
- A lista é uma foto tirada na primeira página: o cursor guarda o instante da foto; tokens vistos depois entram em `pendingNewTokens` até "Atualizar lista"; valores das linhas já listadas continuam atualizando no lugar.
- Observações mais antigas que 24 horas são descartadas (ADR-008).
- O catálogo de textos proibidos é um arquivo versionado no repositório, conferido por teste automático (AC3.2.3).

## Q1. [Produto] Quais fatores compõem o indicador de risco no beta?

O PRD §9 propõe concentração 25%, liquidez 25%, autoridades 20%, atividade suspeita 20% e preço 10%, como hipótese a calibrar. Hoje a fonte simulada só informa preço, liquidez, volume e holders.

- A. Os cinco fatores do PRD com os pesos iniciais do PRD; os adaptadores simulados passam a informar também concentração dos 10 maiores holders, autoridade de mint e de freeze, um sinal de atividade suspeita e a variação de preço
- B. Só fatores calculáveis com os campos de hoje (liquidez, holders, volume e variação de preço), com pesos novos; os demais fatores do PRD ficam para depois do G0
- X. Other (please specify)

[Answer]: B

## Q2. [Produto] Como é medida a cobertura e qual é o mínimo para mostrar o indicador?

A cobertura decide se o indicador aparece ou fica indisponível com NO_TRADE (FR6.2).

- A. Cobertura = soma dos pesos dos fatores conhecidos; mínimo de 70%
- B. Cobertura = soma dos pesos dos fatores conhecidos; mínimo de 80%
- C. Todos os fatores precisam ser conhecidos (100%)
- X. Other (please specify)

[Answer]: B

## Q3. [Produto] Com cobertura suficiente, como entra um fator `unknown` no cálculo?

AC3.2.2 exige que um fator `unknown` nunca conte como risco zero e que nenhum rótulo de baixo risco apareça nesse caso.

- A. O fator `unknown` entra com o risco máximo do fator (estimativa pessimista); o nível nunca fica abaixo de Médio enquanto houver fator `unknown`
- B. O indicador é calculado só com os fatores conhecidos (pesos redistribuídos); o nível nunca fica abaixo de Médio enquanto houver fator `unknown`
- X. Other (please specify)

[Answer]: A

## Q4. [Produto] Quais faixas definem Baixo, Médio e Alto?

As faixas ficam no conjunto de pesos versionado e podem mudar depois.

- A. Baixo 0 a 33, Médio 34 a 66, Alto 67 a 100
- B. Baixo 0 a 39, Médio 40 a 69, Alto 70 a 100
- X. Other (please specify)

[Answer]: B

## Q5. [Produto] Um dado desatualizado (mais de 5 minutos) entra na avaliação de risco?

- A. Não: para a avaliação, um dado desatualizado vale como `unknown` (reduz a cobertura)
- B. Sim: entra no cálculo, e o fator mostra a marca de desatualizado
- X. Other (please specify)

[Answer]: B

## Q6. [Dados] Quando duas fontes informam o mesmo campo, qual valor aparece?

A tolerância e as fontes reais dependem do SP-1; com adaptadores simulados é preciso uma regra para o valor exibido (achado R-03 da U1).

- A. Ordem de prioridade das fontes configurável: vale o valor não desatualizado da fonte de maior prioridade; as demais servem para detectar divergência
- B. Vale a observação mais recente entre as fontes
- X. Other (please specify)

[Answer]: B

## Q7. [Dados] Qual a tolerância de divergência até o SP-1 definir a final?

Divergência = diferença relativa entre os valores das fontes para o mesmo campo. Diferença exatamente igual à tolerância não gera aviso (AC2.4.1).

- A. 5% para todos os campos, configurável por campo
- B. 10% para todos os campos, configurável por campo
- C. 2% para preço e 10% para os demais campos, configurável por campo
- X. Other (please specify)

[Answer]: A

## Q8. [Desenvolvimento] Quando a avaliação de risco é calculada?

O filtro por indicador (`maxRiskScore`) e a ordenação por risco precisam do indicador de cada token na consulta paginada.

- A. Recalculada após cada coleta e guardada (a última por token); a lista e o filtro leem a avaliação guardada; o detalhe mostra a guardada com a data do cálculo
- B. Calculada na hora de cada consulta, sem guardar
- X. Other (please specify)

[Answer]: A

## Q9. [Operações] Quem muda os pesos, as faixas e a cobertura mínima no beta?

- A. Arquivo versionado no repositório; uma versão nova entra em vigor no próximo deploy e fica registrada com a data de ativação
- B. Tela administrativa na U8, com auditoria de cada mudança
- X. Other (please specify)

[Answer]: B

## Perguntas de acompanhamento

## Q10. [Produto] Quais pesos iniciais têm os quatro fatores escolhidos na Q1?

A Q1 escolheu fatores calculáveis com os campos de hoje (liquidez, holders, volume e variação de preço) com pesos novos, que ainda não foram definidos. A variação de preço usa a janela de 1 hora. Os pesos são a versão 1 do conjunto, a mudar pela tela administrativa da U8 (Q9).

- A. Liquidez 35%, holders 25%, volume 20%, variação de preço 20%
- B. Pesos iguais: 25% cada
- X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
