# Requisitos de escala — U4 Descoberta e avaliação

Requisitos de capacidade e crescimento da Unit `u4-discovery`, derivados do NFR2 e NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos fluxos de [functional-spec.md](../functional-design/functional-spec.md) e das respostas Q1 e Q2 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Planejamento de capacidade

| Dimensão | Beta local (meta da U4) | Depois do G0 (referência, não meta) | Mecanismo |
|---|---|---|---|
| Tokens na janela de 24 horas | 2 000 | 10 000 | Índices pela ordem e pelos filtros; consulta única por página |
| Fontes | 2 simuladas | 2 a 3 reais | Um agendamento por fonte |
| Usuários simultâneos na tela | 20 | a definir com a hospedagem | Limite por pessoa, consultas paginadas |
| Avaliações guardadas | 1 por token (≤ 2 000) | 1 por token | Substituição no lugar |
| Amostras do histórico (24 h) | ≤ 4,7 milhões de linhas | ≤ 35 milhões | Resolução de 5 minutos, limpeza horária |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR2.4 | Histórico com resolução de 5 minutos | No máximo uma amostra por token, fonte e campo em cada intervalo de 5 minutos UTC (a primeira observação do intervalo); as demais coletas do intervalo só atualizam o valor atual. Isso refina o passo 6 do F1 e mantém a janela de 1 hora da BR1.10 (60 a 65 minutos) com exatamente um intervalo. Teste: 10 coletas em 5 minutos geram uma amostra por token, fonte e campo | Q1, ADR-008, BR1.10 |
| NFR2.5 | Volume do histórico limitado | Com 2 000 tokens, 2 fontes e 4 campos, o histórico de 24 horas fica em até 4,7 milhões de linhas (2 000 × 2 × 4 × 288) e o espaço em disco do histórico com índices fica abaixo de 2 GB no ambiente de referência; a limpeza horária mantém o volume estável | Q1, Q2, ADR-008 |
| NFR2.6 | Consultas sem varrer o histórico | A lista e o detalhe leem só as observações atuais e as avaliações guardadas; o histórico só é lido no cálculo da variação de preço de 1 hora (uma amostra por token e fonte) e pelas janelas do C11. Plano de execução sem varredura completa do histórico, verificado no script de carga | F3, F4, F5 |
| NFR2.7 | Uma instância de API e um processamento por vez | No beta, a API roda em um processo; coleta por fonte, recálculo e limpeza rodam uma execução por vez, com bloqueio no banco (como a U2), para continuar correto quando houver mais de uma instância | F1, F5, F6 |
| NFR2.8 | Limite de requisições por instância declarado | O contador de 60 requisições por minuto por pessoa (NFR6.3) fica na memória do processo; com mais de uma instância, o limite efetivo se multiplica. Registrado como item a rever com a hospedagem | Q3 |
| NFR3.5 | Crescimento para 10 000 tokens avaliado antes do G1 | Antes de ligar fontes reais, repetir o script de carga com 10 000 tokens; se as metas do NFR3.1 e NFR3.2 não se mantiverem, abrir decisão de escala (por exemplo, pré-cálculo de páginas ou leitura de réplica) antes dos convites | Q1, NFR3 |

## Política de degradação

Quando a capacidade aperta, nesta ordem:

1. A limpeza do histórico pode atrasar até a próxima hora (o volume passa um pouco de 24 horas por uma hora).
2. O recálculo das avaliações junta gatilhos (BR3.7); a avaliação exibida mostra o horário do cálculo.
3. Pedidos acima do limite por pessoa recebem `429` com `Retry-After`.
4. A coleta nunca é pulada para dar lugar a consultas; uma coleta atrasada aparece como dado desatualizado (NFR1.1), nunca como dado sem marca.
