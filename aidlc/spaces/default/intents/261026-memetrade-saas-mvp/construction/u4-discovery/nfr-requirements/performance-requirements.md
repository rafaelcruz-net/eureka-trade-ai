# Requisitos de desempenho — U4 Descoberta e avaliação

Requisitos de desempenho e frescor da Unit `u4-discovery`, derivados dos fluxos F1 a F6 de [functional-spec.md](../functional-design/functional-spec.md), das regras de [rules.md](../functional-design/rules.md), do NFR1, NFR2 e NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e das respostas Q1, Q2 e Q5 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Condições de carga de referência

| Dimensão | Valor | Origem |
|---|---|---|
| Tokens na janela de 24 horas | até 2 000 | Q1 |
| Fontes simuladas | 2 (A e B), parte dos tokens em comum | Q2 |
| Intervalo de coleta por fonte | 30 s (configurável de 5 s a 60 s) | entities.md (`DataSource.collectionInterval`) |
| Usuários simultâneos na tela do scanner | 20 | NFR3 |
| Ambiente de medição | Docker Compose local, mesma composição do `scripts/verify.sh` | NFR13, U1 |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR1.1 | Valor desatualizado sempre marcado | 100% dos valores com idade estritamente acima de 5:00 aparecem marcados na lista, no detalhe e nas interfaces C11; a marca é calculada na leitura (BR1.1, BR3.6). Testes com relógio injetável em T + 5:00 (sem marca) e T + 5:01 (com marca), inclusive para um valor que cruza 5:00 sem nova observação | NFR1, AC2.3.1, AC2.3.4 |
| NFR1.2 | Idade máxima de 60 s com a fonte operante | Com a coleta a cada 30 s e a fonte `OK`, a idade de cada valor exibido fica em até 60 s mais a duração de uma coleta. Teste de integração com relógio injetável: após duas coletas consecutivas, nenhum valor da fonte operante tem idade acima de 60 s | NFR1 |
| NFR1.3 | Medição de 1 hora com fonte real depois do G0 | A medição "95% dos valores com idade ≤ 60 s em 1 hora" com fonte real fica registrada como verificação pendente para depois do G0 (SP-1), sem bloquear a U4 | NFR1, AC2.3.4 (nota) |
| NFR2.1 | Coleta dentro do intervalo | Uma coleta de 2 000 tokens de uma fonte simulada (validação, gravação atual e histórico) termina em até 10 s no ambiente de referência, abaixo do intervalo de 30 s | NFR2, F1 |
| NFR2.2 | Recálculo das avaliações dentro do intervalo | O recálculo de 2 000 avaliações (F5) termina em até 10 s, para que um recálculo acabe antes do próximo gatilho de coleta | NFR2, F5, BR3.7 |
| NFR2.3 | Limpeza do histórico curta | A limpeza horária do histórico (F6) apaga uma hora de amostras em até 60 s, em lotes, sem travar a coleta | ADR-008, F6 |
| NFR3.1 | Lista do scanner rápida | `GET /api/v1/scanner/tokens` com qualquer combinação de filtros e ordenação, janela cheia (2 000 tokens) e 20 usuários simultâneos: p95 ≤ 1 s, medido no servidor | NFR3, Q5 |
| NFR3.2 | Detalhe da avaliação rápido | `GET /api/v1/tokens/{chain}/{mint}/assessment` nas mesmas condições: p95 ≤ 500 ms; o primeiro detalhe de um token ainda sem avaliação (cálculo na hora, F4) entra na mesma meta | NFR3, Q5 |
| NFR3.3 | Tela do scanner dentro do NFR3 | Tela do scanner com filtros e a primeira página: p95 ≤ 2 s até a lista utilizável, com 20 usuários; abrir o painel lateral não recarrega a lista | NFR3, BR5.3 |
| NFR3.4 | Contadores sem custo por página | `excludedByUnknown`, `pendingNewTokens` e `total` são calculados uma vez por consulta, sobre a foto, dentro da meta do NFR3.1 | BR2.2, BR2.6 |

## Requisitos da tela do scanner e do painel lateral

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR11.1 | Lista e painel operáveis só por teclado | Filtros, ordenação, paginação, seleção de linha (Enter), abertura e fechamento do painel (Esc) e "Limpar filtros", "Tentar de novo" e "Atualizar lista" funcionam só com teclado, com foco visível e sem armadilha de foco; ao fechar o painel, o foco volta à mesma linha. Teste Playwright só com teclado | NFR11, AC3.1.1, CT-UX-4 |
| NFR11.2 | Estado nunca só por cor | Desatualizado, divergente, falha de fonte, risco indisponível e nível de risco aparecem em texto ou ícone com nome acessível; contraste de texto ≥ 4,5:1 no tema escuro | NFR11, BR5.4, BR5.5, BR5.7, CT-UX-7 |
| NFR11.3 | Atualizações anunciadas sem mover o foco | Valores atualizados e "N novos tokens" anunciados a leitores de tela numa região viva educada, com "Atualizado às HH:MM", sem mudar o foco nem a linha selecionada | NFR11, CT-UX-8, AC2.1.6 |
| NFR14.1 | Textos em português com termos explicados | Todos os textos da tela S3 e do painel em pt-BR; os termos `liquidity`, `holders`, `volume`, `mint` e `stale` aparecem com explicação ao passar o mouse e ao receber foco; nenhum texto com termo da lista proibida (NFR6.8) | NFR14, CT-UX-1, BR5.1 |

## Como medir

- **Testes de integração** com relógio injetável para NFR1.1, NFR1.2 e os contadores; PostgreSQL real em contêiner.
- **Script de carga local** ampliando o `scripts/load-test.sh` existente: janela semeada com 2 000 tokens e duas fontes, 20 usuários simultâneos consultando lista (filtros variados) e detalhe por 10 minutos; relatório com p50, p95 e p99 por rota e as durações de coleta, recálculo e limpeza lidas das métricas (observability-requirements.md). Roda localmente, fora da CI, como o script da U2.
- **Durações dos trabalhos agendados** pelas métricas `market_collection_duration_seconds`, `risk_recalculation_duration_seconds` e `market_history_cleanup_duration_seconds`.

## Fora do escopo

- Metas com hospedagem real e fornecedores reais: revistas no G0 e quando houver hospedagem (NFR3, NFR10).
- Desempenho das telas de outras Units.
