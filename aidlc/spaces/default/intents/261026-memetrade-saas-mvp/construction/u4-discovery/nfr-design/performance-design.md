# Desenho de desempenho — U4 Descoberta e avaliação

Como a U4 cumpre os requisitos de [performance-requirements.md](../nfr-requirements/performance-requirements.md) e de [scalability-requirements.md](../nfr-requirements/scalability-requirements.md), com as decisões Q1 a Q3 de [nfr-design-questions.md](nfr-design-questions.md). A lista é montada por consulta direta a cada pedido (Q3), sem tabela de leitura.

## Orçamento de tempo da lista (NFR3.1, p95 ≤ 1 s)

| Etapa | Orçamento | Como |
|---|---|---|
| Autenticação, limite e validação | ≤ 20 ms | Filtros já existentes da U1 e contador em memória |
| Consulta única no banco (valores atuais, avaliação, filtros, contadores e página) | ≤ 600 ms | Uma consulta com CTEs (abaixo), índices dedicados |
| Montagem da resposta (marca de desatualizado, divergência, JSON) | ≤ 150 ms | Só para os itens da página (até 100) |
| Folga | ≥ 230 ms | GC e concorrência de 20 usuários |

## Consulta da lista (F3)

Uma consulta parametrizada por pedido, em etapas:

1. **Tokens da foto:** `token` com `first_seen_at <= snapshotAt` e alguma observação com `observed_at` nas últimas 24 horas (índice `market_observation (chain, mint, observed_at)`).
2. **Valor escolhido por campo:** para cada token e campo, a observação `KNOWN` mais recente entre fontes não `FAILED` (`DISTINCT ON (chain, mint, field) ... ORDER BY observed_at DESC, source_id`), com junção ao estado das fontes; campo sem `KNOWN` fica `unknown` (BR1.2, BR1.3, BR1.9). Junção por `chain` e `mint` juntos, resolvendo o achado R-03 da U1.
3. **Linha por token:** os valores escolhidos lado a lado (pivô por campo), mais a avaliação guardada (`risk_assessment`, uma linha por token).
4. **Filtros e contadores:** cada filtro tem duas colunas derivadas, "passa" e "unknown"; `excludedByUnknown` conta tokens sem falha em filtro conhecido e com algum filtro `unknown`; `total` conta os que passam em tudo; `pendingNewTokens` conta tokens com `first_seen_at > snapshotAt` que passam nos filtros. Tudo por agregação na mesma consulta (NFR3.4).
5. **Ordem e página:** ordem do campo escolhido com `unknown` no fim e desempate por `mint`, `chain` (BR2.5); continuação depois da posição do cursor (`lastSortValue`, `lastMint`, `lastChain`) por comparação de tupla, sem `OFFSET`; `LIMIT` de até 101 para saber se há próxima página.

Para 2 000 tokens, 2 fontes e 5 campos, a etapa 2 lê até 20 000 linhas atuais, sempre pelo índice. A divergência (BR1.7) e o desatualizado (BR1.1) são calculados em Java só para os itens da página, a partir de todas as observações `KNOWN` deles, lidas numa segunda consulta pelo conjunto de mints da página.

## Índices

| Tabela | Índice | Uso |
|---|---|---|
| `market_observation` | `(chain, mint, field, observed_at DESC, source_id)` | Escolha do valor por campo (etapa 2) |
| `market_observation` | `(chain, mint, observed_at)` | Tokens com observação nas últimas 24 horas |
| `token` | `(first_seen_at)` | Foto e novidades em espera |
| `risk_assessment` | chave primária `(chain, mint)` | Junção na etapa 3 |
| `observation_sample` | `(chain, mint, field, observed_at)` | Variação de 1 hora e janelas do C11 |
| `observation_sample` | `(observed_at)` | Limpeza horária |
| `data_source` | chave primária | Estado das fontes (2 linhas) |

## Detalhe da avaliação (NFR3.2, p95 ≤ 500 ms)

Leitura por chave de `risk_assessment` com os fatores guardados num campo estruturado (JSONB com valores decimais em texto); a marca de desatualizado é calculada na leitura. O primeiro detalhe de um token sem avaliação calcula na hora (F4): uma leitura dos valores atuais e uma da amostra de 1 hora, ambas pelo índice.

## Coleta, recálculo e limpeza (NFR2.1 a NFR2.3)

| Trabalho | Desenho | Meta |
|---|---|---|
| Coleta (F1) | Validação em memória; gravação em lote (`INSERT ... ON CONFLICT DO UPDATE` para observações atuais, `INSERT ... ON CONFLICT DO NOTHING` para amostras do intervalo de 5 minutos); uma transação | ≤ 10 s para 2 000 tokens, caminho sem falha (achado R-03) |
| Recálculo (F5) | Leitura em lote dos valores atuais e da amostra de 1 hora de todos os tokens (duas consultas), cálculo em memória, gravação em lotes de 500 só das avaliações cujo `inputsFingerprint` mudou | ≤ 10 s para 2 000 tokens |
| Limpeza (F6) | `DELETE` em lotes de 10 000 por `observed_at`, uma transação por lote | ≤ 60 s para uma hora de amostras |

Uma coleta em andamento faz a próxima execução do mesmo agendamento ser pulada pelo bloqueio no banco (ShedLock), sem acúmulo (achado R-03 dos requisitos).

## Pool de conexões

O pool principal da U1 continua com 10 conexões. Uso estimado da U4: 1 conexão por coleta (2 fontes), 1 para o recálculo, 1 para a limpeza, e até 20 consultas simultâneas da lista de curta duração. Sem pool novo.

## Frontend (NFR3.3)

A tela pede a primeira página com os filtros na URL; o painel lateral pede só o detalhe e não refaz a lista; a atualização de valores refaz a página atual com o mesmo cursor sem trocar a seleção. Nenhum cache de cliente além do da página aberta.

## Acessibilidade e textos da tela (NFR11.1 a NFR11.3, NFR14.1)

- **Teclado (NFR11.1):** a lista é uma tabela com linhas focáveis; Enter abre o painel (`role="dialog"` não modal ao lado da lista), Esc fecha e devolve o foco à linha guardada pelo identificador chain + mint; filtros, ordenação, paginação e os botões "Limpar filtros", "Tentar de novo" e "Atualizar lista" são controles nativos. Teste Playwright só com teclado.
- **Estado nunca só por cor (NFR11.2):** desatualizado, divergente, falha de fonte, `--` e nível de risco têm texto ou ícone com `aria-label`; tokens de cor do tema escuro com contraste ≥ 4,5:1 conferido em teste de componente.
- **Região viva (NFR11.3):** uma região `aria-live="polite"` anuncia "Atualizado às HH:MM" e "N novos tokens" sem mover o foco.
- **Textos (NFR14.1):** catálogo pt-BR da tela com explicação de `liquidity`, `holders`, `volume`, `mint` e `stale` em dica acessível por mouse e foco; o teste de termos proibidos cobre esse catálogo.

## Medição

O script de carga local (`scripts/load-test.sh` ampliado) semeia 2 000 tokens nas duas fontes, roda 20 usuários por 10 minutos em lista e detalhe e grava p50, p95 e p99 por rota, além das durações de coleta, recálculo e limpeza lidas das métricas. Ele também confere o plano da consulta da lista (sem varredura completa do histórico, NFR2.6).
