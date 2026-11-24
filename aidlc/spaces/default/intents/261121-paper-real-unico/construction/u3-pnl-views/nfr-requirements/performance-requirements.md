# Requisitos de desempenho — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

A U3 é de interface, mas muda ou cria quatro leituras no servidor: C4 com o parâmetro `market` e com período `RANGE`, `GET /api/v1/bots?market=` com as contagens de `BotPage`, C5 (linhas do bot REAL) e C6 (comparação). As quatro usam o cálculo da U2 e herdam a meta do NFR1 do intent, p95 ≤ 1.000 ms [Q1].

## Decisão do fundador (2026-11-23)

Tomada depois da revisão `reviews/review-01.md` (R-03). Não pode ser mudada na Code Generation.

- **D-NFR2. Carga de C5, C6 e C4 com `RANGE`:** o NFR1 pede o p95 "medido no cenário de carga da CI e repetido na devnet". C5 e C6 leem os 2 anos de ordens, então a medida só registrada da U1 não basta para elas. O cenário `pnl-by-mode` da U2 é ampliado com as páginas de C5, a comparação C6 e C4 com período `RANGE`, com o mesmo portão: p95 ≤ 1.000 ms em cada leitura (NFR1.6). O texto pronto do `load-test.yml` da U2 (NFR1.9 da U2, no `code-summary.md` da Code Generation da U2) ainda não foi aplicado pelo responsável e é ampliado na Code Generation da U3. A repetição na devnet continua com o responsável, nos estágios de operação. Com isso, o NFR1 fica coberto nesta Unit.

## Metas de tempo de resposta

| ID | Rota ou caminho | Meta | Condição de carga | Como é medido | Portão |
|---|---|---|---|---|---|
| NFR1.1 | `GET /api/v1/pnl/by-mode` (C4) com `market=PAPER` e com `market=REAL`, `period=ALL` e `period=RANGE` de 30 dias (BR3.1, BR2.4) | p95 ≤ 1.000 ms em cada caso | teste de integração: 1 pessoa com 10 bots (5 PAPER e 5 REAL) e 2 anos de ordens (NFR1.5); carga: o cenário `pnl-by-mode` ampliado (NFR1.6) | teste de integração (NFR1.5) e cenário `pnl-by-mode` | **Sim** para C4 com `period=RANGE` de 30 e de 366 dias, no workflow manual (NFR1.6) [fundador D-NFR2]. C4 sem `market` com `ALL` continua com o portão da U2. Os casos com `market` ficam só registrados no log: eles leem metade do que C4 sem `market` lê. |
| NFR1.2 | `GET /api/v1/bots` (listBots) com `market=PAPER`, `market=REAL` e sem `market`, com `matchingCount` e `totalCount` (BR1.2, BR1.5) | p95 ≤ 1.000 ms em cada um dos 3 casos | 1 pessoa com 10 bots | teste de integração (NFR1.5) | Não: só registrado no log. A leitura conta bots e não cresce com o histórico de ordens [Q1]. |
| NFR1.3 | `GET /api/v1/bots/{botId}/pnl/lines` (C5), primeira página (`limit=50`, sem cursor) e página seguinte (com o `nextCursor` da primeira) (BR4.3) | p95 ≤ 1.000 ms em cada um dos 2 casos | um bot REAL com 2.920 ordens, no teste de integração e no cenário ampliado | teste de integração (NFR1.5) e cenário `pnl-by-mode` | **Sim**, no workflow manual, para as duas páginas (NFR1.6) [fundador D-NFR2]. |
| NFR1.4 | `GET /api/v1/pnl/compare` (C6), com `period=ALL` e com `period=RANGE` de 30 dias (BR5.4) | p95 ≤ 1.000 ms em cada um dos 2 casos | um par PAPER e REAL de mesmo `templateId`, no teste de integração e no cenário ampliado | teste de integração (NFR1.5) e cenário `pnl-by-mode` | **Sim**, no workflow manual, com `ALL` e com `RANGE` de 30 e de 366 dias (NFR1.6) [fundador D-NFR2]. |

## Método de medição

| ID | Requisito | Valor |
|---|---|---|
| NFR1.5 | Medida por teste de integração (`*IT`, Failsafe, Testcontainers com PostgreSQL 17 e as migrações da aplicação), no padrão de `AbstractU9MeasurementIT` e do `DashboardOpenLatencyIT`. | Semente da U1 (NFR1.5 da U1): 1 pessoa, 10 bots (5 PAPER e 5 REAL), 730 dias, 4 ordens por bot por dia, 2.920 ordens por bot e 29.200 no total, com os lançamentos de ledger dos dois modos. Por caso: 10 pedidos de aquecimento descartados e 100 pedidos medidos em sequência; p95 pelo posto mais próximo (o 95º menor dos 100). São 11 casos (4 + 3 + 2 + 2). O teste escreve no log, por caso, o p95 ao lado da meta de 1.000 ms. Na CI, um p95 acima da meta não falha o build (runner compartilhado com ruído; teste instável é defeito, `team.md`). O teste falha só se algum pedido medido não responder 200. O portão é o cenário de carga de NFR1.6. |
| NFR1.6 | Ampliação do cenário `pnl-by-mode` da U2 (NFR1.6 da U2) [fundador D-NFR2]. | **Semente:** a mesma da U2 (20 pessoas, 5 bots PAPER e 5 REAL cada, 730 dias, 4 ordens por bot por dia, só nos perfis `test,loadtest`), com uma condição a mais: cada pessoa tem pelo menos 1 bot PAPER e 1 bot REAL com o mesmo `templateId`, para C6. **Tráfego:** os mesmos 20 usuários virtuais, 1 leitura a cada 2 s, 600 s depois de 60 s de aquecimento descartados. Cada usuário roda em ciclo 9 leituras: C4 sem `market` com `ALL` (U2), `GET /api/v1/real/pnl` (U2), C4 sem `market` com `RANGE` dos últimos 30 dias, C4 sem `market` com `RANGE` de 366 dias (o maior intervalo aceito, terminando agora), C5 primeira página (`limit=50`), C5 página seguinte (com o `nextCursor` recebido), C6 com `ALL`, C6 com `RANGE` de 30 dias e C6 com `RANGE` de 366 dias, as três de um par de mesmo `templateId`. **Portões novos:** p95 ≤ 1.000 ms em cada uma das 7 leituras novas (C4 `RANGE` 30 dias, C4 `RANGE` 366 dias, C5 1ª página, C5 página seguinte, C6 `ALL`, C6 `RANGE` 30 dias, C6 `RANGE` 366 dias), pelo tempo de cada leitura no `load-test.mjs`, separado por uma etiqueta do cenário, porque o histograma `http_server_requests_seconds` por `uri` não separa os períodos; 0 respostas diferentes de 200. **Portões da U2:** continuam todos, sem mudança de limite (C4 com `ALL` ≤ 1.000 ms, atribuição no caminho `PNL` ≤ 500 ms, 0 divergências, `UNAVAILABLE` por `TIMEOUT` ≤ 0,5%, pool, memória ≤ 900 MB, índice da `0023`). **Saída:** `load-test-report.json` e `docs/perf/u3-pnl-views-<data>.md`, com p50, p95 e p99 de cada leitura. **Onde muda:** a Code Generation da U3 amplia `scripts/load/pnl-by-mode.mjs` e `scripts/load/load-test.mjs`; o texto pronto inteiro do `.github/workflows/load-test.yml` fica no `code-summary.md` da Code Generation **da U3**, que diz substituir o texto da U2 (o comentário do topo passa a citar C5, C6 e `RANGE`; o tempo máximo de 90 min fica). O `code-summary.md` da U2 **não** é editado (alinhamento A5; revisão R-03 do Infrastructure Design, que supera o A6 do NFR Design e a frase da Q3 do Infrastructure Design). O arquivo do workflow é do responsável (`team.md`). **Fechamento:** como na U2 (NFR1.25 da U2), o Build and Test da U3 só fecha com 1 execução verde do `pnl-by-mode` ampliado no SHA candidato, disparada pelo responsável depois de aplicar o texto pronto. **Devnet:** a repetição na devnet que o NFR1 pede fica com o responsável, nos estágios de operação, com as mesmas leituras e o mesmo limite de 1.000 ms. |

## Comportamento da tela

| ID | Requisito | Valor |
|---|---|---|
| NFR1.7 | Cada cartão do painel relê o próprio bloco de C4 pelos eventos do canal ao vivo do próprio modo, com no máximo 2 pedidos a cada 30 s: 1 imediato e 1 pendente no fim da janela (BR7.5) [Q1]. | O primeiro evento com o cartão livre relê na hora e abre uma janela de 30 s. Os eventos que chegam dentro da janela marcam 1 releitura pendente, feita no fim dela, e essa releitura abre a janela seguinte (0 eventos perdidos). Em qualquer intervalo de 30 s, o cartão faz no máximo 2 pedidos; com eventos contínuos, o ritmo sustentado é 1 pedido a cada 30 s. Um cartão faz 0 pedidos por evento do outro modo. `real.order.state` fora de `CONFIRMED` e `FAILED` e `real.signing.requested` fazem 0 pedidos. Testes de componente com relógio injetável, um por cartão (NFR4.12). |
| NFR1.8 | Trocar o filtro, o período ou a escolha da comparação faz no máximo 1 pedido por bloco [Q1]. | Troca do filtro de modo em `/bots`: 1 `listBots` e no máximo 1 C4 (com o `market` do filtro, ou sem `market` em Todos) (BR1.2, BR1.6). Troca de período em `/pnl`: 1 C4 sem `market` para os dois blocos; mais 1 C6 se a comparação estiver aberta; mais 1 `GET /api/v1/pnl` se o período for concreto (BR2.6, BR2.9). "Tentar de novo" de um bloco: 1 C4 com `market` daquele bloco, 0 pedidos para o outro (BR3.2). "Tentar de novo" de um lado da comparação: 1 C6 (BR5.5). "Carregar mais": 1 C5 com o cursor (BR4.3). Um pedido anterior ainda pendente é cancelado ou tem a resposta descartada; só a resposta do último pedido aparece. Testes de componente contam os pedidos de cada troca com o `fetch` simulado. |
| NFR1.9 | O detalhe do bot lê o P&L do bot no bloco de C4 do modo do bot, sem rota nova (BR4.6). | Ao abrir `/bots/{id}`: 1 `GET /api/v1/bots/{id}`, 1 C4 com `market` igual ao modo do bot e `period=ALL`, e, para bot REAL, 1 C5 com `limit=50`. Para bot PAPER: 0 pedidos a C5 (BR4.5). O bloco inteiro fica dentro do prazo do bloco da U2 (`memetrade.pnl.by-mode.block-timeout`, 800 ms) e de NFR1.1. |

## Orçamentos e restrições de recursos

| ID | Requisito | Valor |
|---|---|---|
| NFR1.10 | As leituras da U3 (C4 com `market` e com `RANGE`, `listBots` com as contagens, C5 e C6) nunca travam a execução de ordens (NFR1 do intent). | 0 `SELECT ... FOR UPDATE` e 0 travas consultivas nessas leituras. A única escrita é o evento de auditoria de NFR3.13, gravado depois que a leitura terminou, fora da transação de leitura e com o limite de NFR3.14. C5 e C6 usam o cálculo só de leitura da U2 (RealBotPnl, C10 `botLines`; NFR1.10 da U2). Um teste de integração roda C5 e C6 em laço, em paralelo com um ciclo de bot, e confere que o ciclo termina dentro da trava de 2 s que já existe. |
| NFR1.11 | Tamanhos fixos de página e de lista. | C5: `limit` padrão 50, mínimo 1, máximo 100 (contrato C5); a tela sempre pede 50. Parcelas rateadas: até 10 por bot em C4, vindas da U2 (BR4.7). `listBots`: a paginação que já existe não muda. C6 lê só os 2 bots pedidos. |

## Sources

- `nfr-requirements-questions.md`: Q1 e o resumo confirmado [Q1].
- `reviews/review-01.md` (R-03, R-04) e a decisão do fundador de 2026-11-23 (D-NFR2).
- `inception/requirements-analysis/requirements.md`: NFR1 (p95 de 1 s, 10 bots, 2 anos; carga da CI e devnet; leitura nunca trava a execução).
- `construction/u3-pnl-views/functional-design/rules.md`: BR1.2, BR1.5, BR1.6, BR2.4, BR2.6, BR2.9, BR3.1, BR3.2, BR4.3, BR4.5, BR4.6, BR4.7, BR5.4, BR5.5, BR7.5.
- `construction/u3-pnl-views/functional-design/frontend-components.md`: "Integração com a API".
- `construction/u1-mode-skeleton/nfr-requirements/performance-requirements.md`: NFR1.5 (semente e método do p95).
- `construction/u2-real-bot-pnl/nfr-requirements/performance-requirements.md`: NFR1.1, NFR1.6, NFR1.9, NFR1.10, NFR1.12 e NFR1.25 (cenário `pnl-by-mode`, texto pronto do `load-test.yml`, prazo do bloco de 800 ms, fechamento do Build and Test).
- `construction/u2-real-bot-pnl/code-generation/code-summary.md`: o texto pronto do `load-test.yml` ainda pendente com o responsável; `scripts/load/pnl-by-mode.mjs`.
- `inception/contract-design/contract-summary.md`: C4, C5 (`limit` de 1 a 100, padrão 50), C6.
- `aidlc/spaces/default/memory/team.md`: Way of Working (o `load-test` é manual e só o responsável altera `.github/workflows/`), Testing Posture (Desempenho, Determinismo, Testes instáveis).

## Assumptions & Open Questions

1. [assumption] O período de 30 dias representa o caso `RANGE`; o caso `ALL` é o mais caro, porque percorre os 2 anos.
2. [assumption] C4 com `market` e `listBots` ficam fora do cenário de carga: C4 com `market` lê um só bloco do mesmo cálculo que o portão da U2 já cobre com os dois blocos, e `listBots` conta bots, sem depender do histórico.
3. [assumption] Ao entrar no ciclo de 9 leituras, cada leitura da U2 passa a ter 2/9 da frequência que tinha no ciclo de 2 leituras; a taxa total (10 pedidos por segundo) e os portões da U2 não mudam. Se o runner não aguentar, vale a redução do NFR1.25 da U2 (nunca abaixo de 5 pessoas e nunca mudando a meta de 1.000 ms).
4. A Code Generation da U3 decide a forma exata de separar no relatório os períodos de C4 e de C6 (etiqueta do cenário no `load-test.mjs`, como a U2 já separa as leituras por rota).
5. [assumption] O Build and Test da U3 só fecha com 1 execução verde do `pnl-by-mode` ampliado, disparada pelo responsável depois de aplicar o texto pronto do `load-test.yml` da U2; isso depende dos minutos do Actions do plano (`team.md`, Hospedagem).
