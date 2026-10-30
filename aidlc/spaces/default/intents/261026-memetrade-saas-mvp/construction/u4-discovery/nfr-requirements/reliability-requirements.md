# Requisitos de confiabilidade — U4 Descoberta e avaliação

Requisitos de tolerância a falhas e recuperação da Unit `u4-discovery`, derivados do FR4.6, NFR4 e NFR10 de [requirements.md](../../../inception/requirements-analysis/requirements.md), das regras BR1.3 a BR1.5, BR3.7 e BR3.8 de [rules.md](../functional-design/rules.md), do contrato C19 e da resposta Q4 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR10.1 | Sem meta numérica de disponibilidade no beta local | A meta de disponibilidade é definida com a hospedagem, antes dos convites, como no NFR10; a U4 cumpre os requisitos de degradação abaixo | NFR10 |
| NFR10.2 | Falha de uma fonte só degrada o que depende dela | Com a fonte B `FAILED`, os tokens da fonte A continuam na lista com valores e avaliações; tokens só da fonte B saem da lista; o aviso nomeia B e a última atualização. Teste com o adaptador simulado B configurado para falhar | FR4.6, AC2.3.2, BR1.3 |
| NFR10.3 | Tempo limite e novas tentativas na chamada à fonte | Cada chamada a um adaptador tem tempo limite de 5 s; até 2 novas tentativas com espera crescente (1 s e 2 s) e variação aleatória de até 20%; depois, a fonte fica `FAILED` até a próxima coleta. Feito com Resilience4j (Q4). Teste com adaptador que demora 6 s e com adaptador que falha duas vezes e responde na terceira (`DEGRADED`) | C19, Q4, BR1.5 |
| NFR10.4 | Todas as fontes fora sem dado enganoso | Com todas as fontes `FAILED`, a lista responde `503 SOURCE_UNAVAILABLE` com o estado das fontes; nenhum valor antigo sem marca é exibido; o app oferece "Tentar de novo" | AC2.3.3, BR1.4 |
| NFR10.5 | Recuperação sozinha | Quando a fonte volta, a primeira coleta bem-sucedida a leva a `OK` ou `DEGRADED` sem reinício manual; o recálculo seguinte devolve as avaliações a `AVAILABLE` quando a cobertura permite | BR1.5, BR3.7 |
| NFR10.6 | Reinício sem perder estado nem dados | O estado das fontes, as observações, o histórico e as avaliações sobrevivem a um reinício da API; ao subir, cada fonte coleta imediatamente, sem esperar o primeiro intervalo; nenhuma fonte volta a `PENDING` ou `FAILED` por causa do reinício | BR1.5, functional-spec.md (cantos) |
| NFR10.7 | Trabalhos agendados sem sobreposição | Coleta por fonte, recálculo e limpeza rodam uma execução por vez, com bloqueio no banco; uma execução interrompida no meio não deixa resultado parcial (transação por coleta, por lote de recálculo e por lote de limpeza); repetir a execução dá o mesmo resultado | F1, F5, F6 |
| NFR10.8 | Avaliação nunca regride de versão | Uma avaliação calculada com uma versão de pesos menor nunca substitui uma guardada com versão maior, mesmo com recálculos atrasados | BR3.7, F5 |
| NFR10.9 | Falha técnica sem dado inventado | Uma falha técnica numa leitura das interfaces C11 vira `Unknown(SOURCE_UNAVAILABLE)`, sem nova tentativa (contrato C11); uma falha técnica no recálculo mantém a avaliação anterior e registra o erro; nenhum caminho de erro devolve zero no lugar de um valor | C11, NFR4.2 |
| NFR4.4 | Histórico consistente com a retenção | Nenhuma amostra com mais de 25 horas permanece (24 horas mais o intervalo da limpeza); amostras dentro de 24 horas nunca são apagadas. Teste de fronteira com relógio injetável | ADR-008, BR1.8 |

## Modos de falha considerados

| Componente | Falha | Efeito | Tratamento |
|---|---|---|---|
| Adaptador de uma fonte | Lento, erro, resposta inválida | Fonte `DEGRADED` ou `FAILED` | NFR10.2, NFR10.3, NFR6.5 |
| Todas as fontes | Indisponíveis | Lista sem tokens | NFR10.4 |
| Banco | Indisponível na coleta | Coleta falha inteira, nada parcial; a fonte não muda de estado | NFR4.3, NFR10.7 |
| Banco | Indisponível na consulta | `503` técnico em `ProblemDetail`, sem dado antigo | NFR10.9 |
| Processo da API | Reinício | Estado preservado, coleta imediata | NFR10.6 |
| Recálculo | Atrasado ou concorrente | Gatilhos juntados, versão nunca regride | NFR10.7, NFR10.8 |
| Limpeza | Atrasada | Histórico passa um pouco de 24 horas | NFR4.4 |

## Recuperação

- **RPO:** não se aplica a dados de mercado; observações perdidas são recuperadas na próxima coleta. Avaliações são recalculadas a partir das observações.
- **RTO local:** a lista volta a ter dados na primeira coleta depois do reinício (até 30 s mais a duração da coleta).
