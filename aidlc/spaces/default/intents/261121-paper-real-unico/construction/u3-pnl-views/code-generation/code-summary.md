# Resumo do código — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-24. Sem commit: o coordenador faz o commit.

## Situação

As Etapas 1 a 27 estão prontas. Os testes de unidade e integração da U3 no `apps/api`, o `apps/web` inteiro e os testes `node:test` de `scripts/load` estão verdes.

A Etapa 28 está pronta só em parte: o `code-summary.md`, o `traceability.json` e o `source-manifest.json` estão escritos. Estes itens ficam com o coordenador e o responsável:

- o `mvnw verify` completo com JaCoCo;
- o `bash scripts/verify-checkpoint.sh` no commit;
- a execução verde do `pnl-by-mode` ampliado, depois de o responsável aplicar o texto pronto.

Os detalhes estão em "Pendente".

## O que foi feito

### Servidor (`apps/api`, Etapas 1 a 15, sessão anterior)

- **C4 com `market` e `RANGE`** (`PnlController.byMode`, `PnlWindows`, `ModePnlDtos.PnlByModeDto`):
  - lista fechada de parâmetros `{period, from, to, market}`;
  - `RANGE` exige `from` e `to`, `from < to` e no máximo 366 dias;
  - com `market`, só o bloco pedido (`ModePnlView.single`), e a chave do outro fica **ausente** (`NON_ABSENT`);
  - `paper` e `real` saíram de `required` no OpenAPI.
- **`PaperModeBlockReader`** com `RANGE` pelo mesmo `reportSince` de `GET /api/v1/pnl`, e `lineOf` para o lado PAPER de C6.
- **`listBots` com `market`, `matchingCount` e `totalCount`** (`BotController`, `BotQueries` numa transação só de leitura, `JdbcBotRepository.count`). A ordem da página não mudou (conflito 1: `updated_at desc`).
- **C5 `GET /api/v1/bots/{botId}/pnl/lines`:**
  - `RealBotPnlQuery.botLines` (C10) faz a mesma passada de C4 com um `RealPnlEngine.LineCollector`, sem conta nova;
  - `BotLinesCursor` gera o cursor de 44 caracteres e valida com rigor antes de qualquer leitura;
  - o 404 é igual nos três casos e vem antes de qualquer tarefa;
  - falha da passada vira 503 `SERVICE_UNAVAILABLE` com `correlationId`;
  - cursor cuja âncora sumiu vira 400 no campo `cursor`.
- **C6 `GET /api/v1/pnl/compare`:** confere a posse dos dois bots e responde 404 com `reason` antes do 422 `TEMPLATE_MISMATCH`. Os dois lados rodam em paralelo, e cada um pode ficar `UNAVAILABLE` sozinho.
- **`BotDirectory.ownedByAnotherTenant`** (F-2), chamado só pelo `refuseNotFound`, na thread do pedido, nos dois 404. A regra ArchUnit `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS` garante isso.
- **`ReadRefusalAudit`** (1 thread, fila 16, prazo de 200 ms, filtro de 60 min e 10.000 chaves) e a ação nova `PNL_COMPARE_TEMPLATE_MISMATCH`.
- **Três contadores:** `pnl.compare.template_mismatches`, `pnl.compare.side_unavailable{market,cause}` e `pnl.read_audit.failures{action}`.
- **Contrato:** `NeverSumModesArchitectureTest` com os tipos novos. O `openapi-guest.yaml` foi regenerado, e o `route-registry.yml` ganhou C5 e C6.

### Cliente (`apps/web`, Etapa 16, sessão anterior)

- `client.ts` com `signal`, `RequestCancelled` e `retry: false`.
- `guest-api.ts` com as funções da U3.
- `by-mode.ts` aceita a chave ausente.
- `schema.d.ts` regenerado.

### Telas (`apps/web`, Etapas 17 a 23, esta sessão)

- **Etapa 17, `/bots`:**
  - **`BotModeFilter`:** fieldset "Modo" com três rádios nativos, foco inicial no rádio marcado e setas. "Mostrando N de M bots." fica em `aria-live` e usa os números da API.
  - **`BotList`:** lê `?mode=`; um valor inválido vale Todos e sai do endereço sem pedido. A troca usa `router.replace`. As vagas `list` e `byMode` têm `AbortController` cada uma. `listBots` e C4 levam o mesmo `market`.
  - **Vazios:** os textos são diferentes, com "Ver todos os bots", que leva o foco ao rádio Todos. O aviso do simulado não aparece com Real. "Tentar de novo" relê só C4.
- **Etapa 18, `PeriodSelector`:**
  - cinco opções, "Desde o início" por padrão, e o par UTC fixo ao aplicar, por `period.ts`, que não mudou;
  - as quatro mensagens de BR2.3, com `aria-invalid`, `role="alert"`, foco no campo com erro e rascunho mantido;
  - "Mostrando: …" em `aria-live`.
- **Etapa 19, `pnl-screen-state.ts` e `PnlScreen`:**
  - reducer puro (F-3) com as vagas `byMode`, `retry:PAPER`, `retry:REAL` e `tokens`. Um `*_LOADED` de outra vaga é ignorado pelo `requestId`;
  - a fatia da comparação (`compareReducer`) mora no mesmo arquivo;
  - a `PnlScreen` usa `useReducer` e um `AbortController` por vaga, todos abortados ao sair;
  - o período e a comparação ficam no endereço;
  - "Tentar de novo" lê C4 com `market` e põe o foco no título do bloco;
  - "Ver por token" fica dentro do bloco Simulado, só com período concreto. Em "Desde o início", aparece a nota (BR2.9).
- **Etapa 20, `PnlModeBlock`, só acréscimos:**
  - a variante `compact` do painel e a variante `bot` de uma linha;
  - `realUnavailable`, `headingLevel` e `children`;
  - o `ProratedLines` de cada bot REAL, com resumo, botão com `aria-expanded` e `aria-controls`, até 10 parcelas, cartões abaixo de 768 px e tabela num `TableScroll` acima;
  - a linha "Sem bot" só com valor, decidida por comparação de texto (`isZeroText`), com `pnl.noBotReason.*`.
- **Etapa 21, `BotPnlLines` e `BotDetail`:**
  - **`BotPnlLines`** lê C5 de 50 em 50, com "Carregar mais", foco na primeira linha nova e o estado `moreError`. No `cursorStale`, "Recarregar do início" troca os itens (A9). Um 404 chama `onNotFound`. A compra mostra "—", lido como "sem resultado".
  - **Cabeçalho do `BotDetail`:** o selo do modo e "Modo: … · não pode ser trocado".
  - **P&L do bot:** é a linha de C4 com o `market` do bot (sem linha, "—").
  - **REAL:** `BotPnlLines`, sem as seções simuladas.
  - **PAPER:** o aviso e as seções que já existiam, sem pedido a C5.
  - **404:** só "Bot não encontrado." e "Voltar para Meus bots".
- **Etapa 22, `ComparisonView`:**
  - botão com `aria-expanded`, foco ao abrir e ao fechar, e `?comparar=`;
  - o select real é filtrado pelo `templateId`. O par único vem escolhido quando a pessoa abre a região; um endereço é respeitado como veio;
  - lê as páginas seguintes de `listBots`;
  - 422 e 404 são explicados, com o foco no select;
  - cada lado tem o próprio "Tentar de novo".
- **Etapa 23, painel:**
  - `windowedRefresh` (30 s) e a fatia `modeCards` (`modeCardsReducer`);
  - `DashboardModeCards`: C4 sem `market` e C2 em paralelo; uma janela por cartão; anúncio no máximo 1 vez a cada 30 s e nunca na carga inicial;
  - o `DashboardProvider` assina `real.order.state` e `real.conditional.state`;
  - o `PnlCard` e o `DashboardRealPnl` saíram. A linha da conferência foi para `ReconciliationLine.tsx`, e a leitura do P&L do dia UTC saiu do `dashboard-loader.ts`.
- **CSS:** uma única troca em 768 px, 44 px nos rádios abaixo dela, cartões e tabelas.

### Ponta a ponta (Etapa 25)

- O spec `e2e/zz-paper-real-unico/paper-real-unico.spec.ts` ganhou os 10 casos U3 1 a U3 10, todos só de leitura.
- O caso 9 monta C4 e C5 com `mounted-pnl-fixtures.ts`, só com dados sintéticos.
- Os smokes da U5, da U7 e da U9 foram ajustados às telas novas (ver "Desvios").

### Carga (Etapa 26)

**`scripts/load/pnl-by-mode.mjs`:**

- `CYCLE` com as 9 leituras rotuladas e `NEW_READS` com as 7 novas;
- `requestOf` (`RANGE` de 30 e 366 dias terminando agora, em UTC e em segundos inteiros), `targetsOf` e `nearestRankP95`;
- o tempo de cada leitura medido no cliente, só depois do aquecimento;
- `linesNext` encadeado no `nextCursor` da pessoa;
- `u2Failures` (limites sem mudança), `u3Failures` e `pnlByModeGroups` (regra R-04);
- `pnlViewsMarkdown`.

**`scripts/load/load-test.mjs`:**

- antes do aquecimento, escolhe o bot de C5 e o par de C6 por `listBots` com `market`, e falha na hora se faltar;
- o relatório ganha `reads`, `compareUnavailable` e `groups`;
- grava o `docs/perf/u2-real-bot-pnl-<data>.md` com o resultado do grupo U2 e o novo `docs/perf/u3-pnl-views-<data>.md`.

O `LoadtestPnlSeed.java` e o `scripts/load-test.sh` não mudaram.

### Alinhamentos de texto (Etapa 27)

| Alinhamento | Onde | O que mudou |
|---|---|---|
| A1 | `nfr-design-questions.md` (nota na Q2) e `security-requirements.md` NFR3.17 | a verificação roda na thread do pedido nos dois 404 |
| A2 | `contract-summary.md` | regra geral, C4 (`market`, `required: [period]`), C5 (503), C8 (`ownedByAnotherTenant`), C10 (`botLines` implementado), PQ-6 e PQ-8 fechadas |
| A4 | Q3 de `nfr-design-questions.md` | nota da premissa errada e da reescrita aceita |
| A5 | NFR1.6 de `performance-requirements.md` | "Onde muda" |
| A9 | `functional-spec.md` | transições `cursorStale` |
| Conflito 1 | BR6.3 de `rules.md` e NFR4.4 de `security-requirements.md` | ordem da API |
| Conflito 2 | `performance-design.md` (2 frases) e `logical-components.md` (A4) | testes da U1 de `/pnl` ajustados, nenhum apagado |

**A3 não foi escrito:** ver "Pendente".

## Vermelho registrado (Fase A, testes antes do código)

Comando: `./apps/api/mvnw -q -f apps/api/pom.xml test-compile`, antes de qualquer código de produção da U3. Resultado: **falha de compilação**.

- **Não compilaram:** `BotLinesCursorTest`, `RealBotPnlBotLinesTest`, `BotPnlLinesCursorStabilityIT` e `PnlNoThirdNumberTest`. Os tipos e métodos de C5 e C6 ainda não existiam.
- **Compilaram, mas falharam:** `PnlRangeBoundaryIT` e `PnlCompareEqualsByModeIT`. A U1 recusava `RANGE` com 400, e C6 não existia.

Depois do código, os quatro ITs da Fase A ficaram verdes.

## Resultados

| Parte | Comando | Resultado |
|---|---|---|
| `apps/api`, unidade da U3 (sessão anterior) | `mvnw test -Dtest=<lista da U3>` | 86 testes, 0 falhas |
| `apps/api`, arquitetura e contrato (sessão anterior) | `ArchitectureTest`, `AuditArchitectureTest`, `BotsArchitectureTest`, `ModeBoundaryArchitectureTest`, `NeverSumModesArchitectureTest`, `PnlViewsArchitectureTest`, `GuestOpenApiContractTest`, `U5OpenApiContractTest` | 65 testes, 0 falhas |
| `apps/api`, integração (sessão anterior) | `mvnw verify -Dit.test=<lista da U3>` | 27 classes verdes |
| `apps/api` (esta sessão, achados da revisão) | `mvnw test -Dtest=PnlViewsArchitectureTest`; `mvnw verify -Dit.test=PnlCompareRouteIT` | 3 testes verdes; 6 testes verdes (só testes mudaram no servidor) |
| `apps/web` | `eslint .` | verde (0 problemas) |
| `apps/web` | `tsc --noEmit` | verde |
| `apps/web` | `check:api` | `schema.d.ts matches openapi-guest.yaml` |
| `apps/web` | `jest --coverage` | **85 suítes, 993 testes, 0 falhas**. Linhas **94,43%**, comandos 93,93%, ramos 87,15%, funções 91,31% (piso de 80% de linhas) |
| `apps/web` | `prettier --check .` | só acusa `apps/web/aidlc/.aidlc-sessions/*.binding.json`, arquivo do AI-DLC fora do código |
| `apps/web` | `playwright test --list e2e/zz-paper-real-unico` | o spec compila e lista 18 casos (8 antigos e 10 novos); não executado (ver "Pendente") |
| Carga | `node --test "scripts/load/*.test.mjs"` | **104 testes, 104 verdes** (eram 95; 0 falhas, 0 pulados) |

Latência medida localmente na sessão anterior (`PnlViewsLatencyIT`, p95, meta 1.000 ms):

| Caso | p95 |
|---|---|
| C4 PAPER `ALL` | 182 ms |
| C4 REAL `ALL` | 492 ms |
| C4 PAPER `RANGE` de 30 dias | 110 ms |
| C4 REAL `RANGE` de 30 dias | 380 ms |
| `listBots` | 16 a 22 ms |
| C5, 1ª página | 359 ms |
| C5, página seguinte | 363 ms |

Paridade de tempo dos 404 (`BotNotFoundTimingParityIT`), diferença de p95: 0,1 ms em C5, 1,0 ms no lado PAPER de C6 e 0,2 ms no lado REAL.

**Cobertura do `apps/api` (JaCoCo):** não foi medida localmente; ver "Pendente".

## Desvios e decisões

### Sessão anterior (servidor)

1. **Ordem das linhas de C5:**
   - a chave de ordem de rede da linha principal do evento, depois a ordem em que a passada aplicou o evento e depois o enum `origin`;
   - a tentativa falha do modo Assinada usa a chave do evento anterior, com identidade `attempt:<referência>`.
2. **Retorno de `botLines`:** devolve `Optional<RealPnlLinePage>` (vazio = 404). Se a âncora não for encontrada, lança `RealPnlLineCursorRejected` (400). O C10 do `contract-summary.md` foi alinhado (A2).
3. **`tenantId` em C4 agora dá 400** (lista fechada, NFR3.1). `ModePnlFailureIT.anotherPersonsBotsNeverAppear` mudou para conferir o 400; `onlyThePeriodAllIsAcceptedInTheU1` só mudou de nome.
4. **`ModePnlStatement.period`** passou a ser `PnlWindow`.
5. **`PnlReadsDoNotBlockCycleIT`:** o ciclo do bot não roda no contexto da API do IT. A trava `FOR UPDATE` da carteira do bot faz o papel dele.
6. **`PnlViewsLogCaptureTest`** é de integração, com nome de unidade.
7. **Cobertura parcial da auditoria:**
   - o `ReadRefusalAuditNonBlockingTest` mede a entrega à auditoria;
   - o `ReadRefusalAuditLimitIT` não cobre "auditoria falhando com 10 respostas iguais" nem "20 pessoas e o `ReconciliationAuditGate`", porque falta um ponto de falha injetável no escritor real.
8. **Correções de teste:**
   - `BotListMarketIT` não pausa mais um bot por `update`;
   - `BotNotFoundTimingParityIT` alterna a ordem dos dois casos a cada rodada.
9. **`ModePnlLatencyIT` (U1):** falhou uma vez com a máquina carregada e passou sozinho. Fica para acompanhar na CI.
10. **Edição pelo shell:** dois arquivos foram editados pelo shell antes de a regra do brief ser notada.

### Esta sessão (telas, ponta a ponta, carga e textos)

11. **A fatia da comparação fica fora do reducer da tela:**
    - a `compareReducer` mora em `pnl-screen-state.ts`, mas a `ComparisonView` a usa num `useReducer` próprio;
    - a `PnlScreen` passa a seleção e o período;
    - assim a comparação é testada sozinha e a tela não depende do estado dela;
    - a `modeCardsReducer` mora em `dashboard-reducer.ts`, mas o `DashboardModeCards` a usa num `useReducer` próprio, pelo mesmo motivo.
12. **Par único só pela pessoa:** a `ComparisonView` escolhe o par único só quando a pessoa abre a região. Com `?comparar=` no endereço, vale o que o endereço diz, e um endereço inválido abre vazio sem pedido (BR5.6).
13. **`DashboardRealPnl.tsx` removido:**
    - nenhuma outra tela o usava, e o plano prevê que saia nesse caso;
    - o `DashboardRealPnl.test.tsx` (U6) **não foi apagado**: os três casos testam agora os cartões por modo, com a mesma intenção (REAL ao lado do PAPER, nunca somados, falha só no próprio cartão);
    - o `RealPnlCard` continua na tela `/real`.
14. **`PnlCard.tsx` removido:**
    - a linha da conferência foi para `ReconciliationLine.tsx`, sem mudança de texto nem de `data-testid`;
    - a fatia `pnl` e as ações `PNL_*` saíram do reducer do painel;
    - `loadPnl` e `refreshPnl` saíram do loader;
    - a releitura às 00:00 UTC saiu do `DashboardProvider`, porque os cartões são "desde o início" (BR2.8).
15. **Testes do painel ajustados, nenhum apagado:**
    - os casos do `DashboardScreen.test.tsx` que esperavam o `PnlCard` agora esperam os dois cartões;
    - o caso "18 casas decimais" passou a conferir o corte em 2 casas do US$ (BR7.2);
    - o caso "às 00:00 UTC relê" passou a conferir que nada é relido;
    - no `dashboard-state.test.ts`, os casos do loader de P&L passaram a usar a conferência, e entrou um bloco novo para `modeCardsReducer`.
16. **Smokes de ponta a ponta de outras Units ajustados às telas novas:**
    - `u5-bots-decision` espera o selo SIMULADO e "Modo: Simulado" no lugar de "MODO PAPER (simulado)";
    - `u7-ledger-pnl` espera "Ver por token" e escolhe "Últimos 7 dias" antes de esperar o vazio da tabela (BR2.9);
    - `u9-dashboard` espera `dashboard-mode-cards` no lugar de `dashboard-pnl-card`;
    - o `scripts/verify.sh` não mudou.
17. **Detalhe de um bot REAL:** não mostra mais as seções simuladas (uso do dia PAPER, operações simuladas, recusas da C21, dado velho), que leem rotas do PAPER. Ficam os comandos, os limites, o P&L do bot, as linhas de C5 e as decisões.
18. **Rótulo do campo "Até":** passou a ser "Até (inclusive)", o rótulo que a U1 já usava (os testes da U1 procuram por ele).
19. **Defeito de desempenho corrigido no `formatDayTime`:**
    - ele criava um `Intl.DateTimeFormat` por linha, até 100 por renderização de C5;
    - agora guarda um formatador por fuso;
    - o `BotPnlLines.test.tsx` falhou uma vez com a suíte inteira em paralelo e ganhou prazo próprio (`asyncUtilTimeout` de 5 s e `jest.setTimeout` de 20 s, como o fluxo do assistente em `BotScreens.test.tsx`);
    - teste instável é defeito (`team.md`): a causa foi corrigida no código, e o prazo cobre a carga da máquina.
20. **Contagem de `UNAVAILABLE` na carga:**
    - o contador `pnl_mode_block_unavailable_total` conta toda leitura de C4, com `ALL` ou `RANGE`;
    - o grupo U2 compara o contador com os blocos REAL `UNAVAILABLE` de todas as respostas de C4;
    - a parcela de `TIMEOUT` é medida sobre todas as leituras de C4 (`readsC4`), sem mudar o limite de 0,5%;
    - o grupo U3 falha também com qualquer `UNAVAILABLE` nas próprias leituras, atribuído pelo rótulo;
    - um `UNAVAILABLE` de C4 `RANGE` aparece também no contador do grupo U2: é a mesma passada REAL, o que a regra R-04 já prevê (U2 vermelho deixa as duas Units pendentes).
22. **Achados da revisão (READY, 4 menores), corrigidos:**
    - **Regra ArchUnit:** a `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS` agora pega a chamada feita por qualquer tipo atribuível a `BotDirectory`, inclusive o `BotDirectoryImpl` concreto. A amostra nova `AskingThroughTheImplementationSample` prova que a regra falha. `PnlViewsArchitectureTest`: 3 testes verdes.
    - **C6 sem repetição automática:** o `comparePnl` usa `retry: false`, porque C6 roda duas passadas REAL e repetir dobraria a carga num momento de falha. C4 continua repetindo, porque é a leitura de um bloco só e esse é o comportamento que já existia. O `pnl-routes.test.ts` confere exatamente 1 `fetch` num 503 de C6, com o `correlationId`, e 3 em C4. `PnlCompareRouteIT`: 6 testes verdes.
    - **Aviso do simulado no spec:** o `zz-paper-real-unico` já confere o aviso no bloco Simulado (`pnl-block-PAPER-disclaimer`, com o texto exato, no caso "/pnl shows each bot…"), e os casos U3 1 e U3 7 também. Não foi preciso mudar nada.
    - **`ModePnlLatencyIT`:** a falha única é item de acompanhamento na CI (Pendente, item 7). Não houve mudança de código.
23. **Passo D-INF4 ainda não aplicado no `ci.yml`:** ele roda os testes de `scripts/load` no job `frontend`. Até o responsável aplicá-lo, vale a saída manual registrada abaixo (104 verdes).

## Pendente (com o responsável ou o coordenador)

1. **A3, para o ritual de aprendizado:** a exceção F-2 não foi escrita em dois lugares, como manda o plano:
   - no `team.md`, Code Style, "Tenant no código": a única consulta fora do tenant é a booleana `BotDirectory.ownedByAnotherTenant`, só no caminho dos 404;
   - no NFR3.1 de `security-requirements.md`.
2. **`./apps/api/mvnw -f apps/api/pom.xml verify` completo com JaCoCo ≥ 80% e Spotless:** não rodou localmente (memória curta; só execuções direcionadas). A prova é o `ci` do commit.
3. **Spec de ponta a ponta:** os 10 casos novos e os smokes ajustados da U5, da U7 e da U9 não foram executados nesta máquina. Eles precisam do `verify.sh` com o validador local e rodam nos jobs `smoke` e `e2e` da CI.
4. **Ponto de controle:** `bash scripts/verify-checkpoint.sh` no commit pedido ao responsável. Se o `verify.sh` local morrer por memória, a prova é o run verde do `ci` do mesmo commit (`team.md`).
5. **Carga (NFR1.6):**
   - o responsável aplica os dois textos prontos abaixo;
   - depois dispara `gh workflow run load-test -f scenario=pnl-by-mode` no SHA candidato;
   - o Build and Test da U3 só fecha com 1 execução verde do grupo U3;
   - uma execução serve às duas Units (R-04).
6. **`apps/web/aidlc/.aidlc-sessions/*.binding.json`:** arquivo do AI-DLC, não rastreado, que o `format:check` acusa. Não deve entrar no commit; o coordenador decide se o remove.
7. **`ModePnlLatencyIT` (U1), acompanhamento na CI:** houve uma falha local, com um bloco REAL `UNAVAILABLE`, quando o teste rodou logo depois do `PnlViewsLatencyIT` com a máquina carregada. Rodado sozinho, passou (desvio 9). Nos próximos runs do `ci`, conferir se ele falha de novo. Se repetir, é defeito a investigar e nunca teste a ignorar (`team.md`). Nenhuma mudança de código por enquanto.

## Texto pronto para o responsável: `.github/workflows/load-test.yml`

**Este texto substitui o texto da U2** que está no `code-summary.md` da Code Generation da U2. Aquele arquivo não foi editado (R-03 do Infrastructure Design; alinhamento A5).

O arquivo inteiro é substituído:

- as opções `real` e `pnl-by-mode` continuam (a `real` é a pendência do intent `261113-trading-real-bot`, R-07);
- os SHAs das ações e o `timeout-minutes` (75 para `real`, 90 para `pnl-by-mode`, 360 para os demais) ficam iguais;
- a única diferença para o texto da U2 é a frase nova no parágrafo do `pnl-by-mode` e a contagem de minutos de carga no comentário do `timeout-minutes`.

Comandos de disparo, depois do commit:

```bash
gh workflow run load-test -f scenario=pnl-by-mode
gh workflow run load-test -f scenario=real
```

O primeiro é o portão do Build and Test da U2 e da U3. O segundo é a pendência do 261113.

```yaml
# Manual load test (cicd-pipeline.md Q4): profiles test,loadtest raise the login limit and, since U3, the invitation
# lookup limit ONLY here; 20 simulated users, one action every 2 s. Scenario u1 fails when p95 of challenge, verify or
# list exceeds 300 ms; u4 adds the filtered list and the assessment; u3 adds the verification with an invitation
# (500 ms) and the invitation lookup and Account screen (300 ms); growth only records. U7: u7 runs the load cycle
# driver of the loadtest profile (p95 execute 250 ms, C5 routes 1 s, query plans) and u7-jobs the 30-minute rounds of
# the marking and the pending close (10 s each). U6: u6 runs the load risk driver (p95 evaluate 250 ms in each window,
# C21 1 s, purge batches 100 ms, query plans). U5: u5 runs the worker cycles with the stopwatch of the loadtest profile
# (p95 cycle 2 s, barrier plus execution 500 ms, C4 routes 1 s, S5 screen 2 s, live channel 4 s, no stale-data
# decision, worker pool at most 14 connections).
# 261113-trading-real-bot (operation/performance-validation): real runs the REAL stack of the e2e (TxSigner, kms-sim,
# local Solana validator, recorded Jupiter) with the pilot times 2: 21 bot wallets with 3 REAL bots each, 20 people on
# the screen and an administrator for 15 minutes, "vender tudo e sacar" in 5 wallets for 5 minutes and a burst of 21
# withdrawals in 10 s every minute for 5 minutes. It fails on any missed target of the requirements, any error, any
# duplicate transaction, any growing queue or more than 60 signatures per wallet and minute. CI environment, never
# devnet, mainnet nor a real KMS; every secret is ephemeral (scripts/load-test.sh).
# 261121-paper-real-unico U2 (nfr-requirements NFR1.1 to NFR1.9): pnl-by-mode seeds 20 people with 5 PAPER and 5 REAL
# bots and 2 years of orders each (4 per bot per day, synthetic, test,loadtest profiles only) and runs 20 users reading
# the P&L by mode and the REAL P&L every 2 s for 10 minutes. It fails when p95 of GET /api/v1/pnl/by-mode exceeds 1 s,
# when p95 of the REAL attribution read on the P&L path exceeds 500 ms, on any error, any
# reconciliation mismatch, REAL blocks UNAVAILABLE by timeout above 0.5% of the reads, any other REAL block UNAVAILABLE,
# a pending pool, more than 900 MB in a container or the main REAL ledger read without its index.
# Since 261121-paper-real-unico U3 (NFR1.6): it also reads C4 with RANGE of 30 and 366 days, the first and the next
# page of C5 and C6 with ALL, RANGE of 30 days and RANGE of 366 days, and fails when the p95 of any of these 7 reads,
# timed by the client, exceeds 1 s. The report keeps the gates of the U2 and of the U3 in two groups, each with its
# own result (docs/perf/u2-real-bot-pnl-<date>.md and docs/perf/u3-pnl-views-<date>.md).
name: load-test

on:
  workflow_dispatch:
    inputs:
      scenario:
        description: Load scenario (u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal, reacceptance, data-requests, real or pnl-by-mode)
        type: choice
        options:
          - u1
          - u4
          - u3
          - growth
          - u7
          - u7-jobs
          - u6
          - u5
          - legal
          - reacceptance
          - data-requests
          - real
          - pnl-by-mode
        default: u1

permissions:
  contents: read

jobs:
  load:
    runs-on: ubuntu-24.04
    # real: about 25 minutes of load plus the image builds, the validator, the TxSigner and the setup of the 21 wallets
    # (45 to 55 minutes expected). pnl-by-mode: the seed of 20 people with 2 years of orders plus 11 minutes of load
    # with the 9 reads of the U2 and the U3 (45 to 70 minutes expected). The other scenarios keep the GitHub default
    # of 360 minutes, as before.
    timeout-minutes: ${{ inputs.scenario == 'real' && 75 || inputs.scenario == 'pnl-by-mode' && 90 || 360 }}
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 # v4.7.1
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4.4.0
        with:
          node-version-file: .nvmrc
      - name: Start the environment with test,loadtest and run the load
        env:
          SPRING_PROFILES_ACTIVE: test,loadtest
          LOGIN_RATE_LIMIT_PER_MINUTE: '100000'
          INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE: '100000'
          LOAD_SCENARIO: ${{ inputs.scenario }}
          IMAGE_TAG: ${{ github.sha }}
        run: bash scripts/load-test.sh
      - uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        if: always()
        with:
          name: load-test-report
          path: |
            load-test-report.json
            docs/perf/*.md
          retention-days: 30
```

## Texto pronto para o responsável: passo do `ci.yml` (D-INF4)

No job `frontend`, depois do passo "Jest with the 80% line floor", que é o último do job. O passo herda o `actions/setup-node` já fixado pelo SHA (`node-version-file: .nvmrc`, Node 24). Não entra ação, permissão nem variável nova, então o `WorkflowsTest` não muda.

```yaml
      - name: Gates of the load scenarios (scripts/load)
        run: node --test "scripts/load/*.test.mjs"
```

O padrão fica entre aspas para o Node expandir; a forma com diretório (`node --test scripts/load`) falha no Node 24.

Saída manual desta geração, até o passo ser aplicado:

```text
ℹ tests 104
ℹ suites 0
ℹ pass 104
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
```

## Regra de leitura do relatório (R-04)

O `load-test-report.json` traz `groups.u2`, `groups.u3` e `groups.pending`, e cada grupo tem o próprio resultado. Os dois relatórios de `docs/perf/` mostram os grupos.

| Grupos | Resultado |
|---|---|
| U2 verde e U3 verde | as duas Units fecham o portão de carga |
| Só U3 vermelho | só a U3 fica pendente, até uma nova execução verde do grupo U3 |
| U2 vermelho | a U2 e a U3 ficam pendentes, porque as leituras da U3 rodam sobre a mesma passada REAL |

O que entra em cada grupo:

- **Grupo U2**, com os limites sem mudança: C4 `ALL` pelo histograma do servidor, atribuição `PNL`, divergências, `UNAVAILABLE` por causa, pool, memória e o índice da `0023`.
- **Grupo U3:** o p95 de cada uma das 7 leituras novas, pelo posto mais próximo no cliente, mais as respostas diferentes de 200, o `UNAVAILABLE` das próprias leituras e as páginas seguintes de C5 sem cursor.
- As respostas diferentes de 200 e o `UNAVAILABLE` vão para o grupo da leitura que os recebeu, pelo rótulo.
- O `load-test-pnl-by-mode.test.mjs` cobre os quatro casos: os dois verdes, só U3 vermelho, só U2 vermelho e os dois vermelhos.

## Arquivos

A lista completa dos arquivos da aplicação está em `source-manifest.json`. Os dois arquivos removidos (`apps/web/src/features/dashboard/PnlCard.tsx` e `apps/web/src/features/real/components/DashboardRealPnl.tsx`) estão nos desvios 13 e 14. Os textos alinhados do AI-DLC estão na tabela da Etapa 27.
