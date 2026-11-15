## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-14
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | apps/api/src/main/java/com/memetrade/marketdata/infra/coingecko/CoinGeckoConfig.java (classe inteira) e apps/api/src/test/java/com/memetrade/marketdata/infra/coingecko/CoinGeckoConfigTest.java:107-125 | O grafo de beans do worker com `MARKET_SOURCES=coingecko` (CoinGeckoConfig, CoinGeckoCollectorJob, CoinGeckoUsageJob, CallBudget com `refresh()` no banco, CoinGeckoProperties por `@ConfigurationPropertiesScan`) nunca sobe num contexto Spring completo. O `CoinGeckoApiStartupIT` cobre só o processo API sem chave; o teste de "worker only" confere anotações e uma classe `Probe`; os ITs de worker (`AbstractU5WorkerIT`) usam as fontes simuladas. A mudança de comportamento mais importante da Unit (coleta real só no worker) fica sem prova de que o worker sobe e agenda a coleta. | Antes do checkpoint da Unit, acrescentar um IT de contexto do worker (`memetrade.process=worker`, `memetrade.market.sources=coingecko`, chave de teste, `COINGECKO_BASE_URL` apontando para o WireMock, no perfil de teste) que confirme a criação dos beans `CoinGeckoCollectorJob` e `CoinGeckoUsageJob` e uma `collectNow()` gravando no PostgreSQL de contêiner. Se preferir adiar, registrar a lacuna como pendência explícita do B2 no code-summary. | New |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/marketdata/infra/coingecko/CoinGeckoAdapter.java:433-447 (`call`) e 451-460 (`counted`/`attempt`) | O orçamento é consumido (`budget.acquire`) antes de `circuit.tryAcquirePermission()`. Com o circuito aberto, cada lote de preço da rodada grava +1 no contador mensal sem chamada real. É conservador, mas distorce o `marketdata_provider_budget_ratio` e o alerta de 80% durante a janela aberta. | Verificar a permissão do circuito antes de contar no orçamento, ou documentar o sobrecusto como aceito no `reliability-design.md`. | New |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/marketdata/infra/coingecko/CoinGeckoAdapter.java:`details()` (`(next.info ? infoAt : poolsAt).put(next.token, round.start)` antes da chamada) | A marca de "renovado" é gravada antes do resultado da chamada de detalhe. Uma falha transitória (5xx, timeout) adia a nova tentativa em 60 min. Para um token observado sem identidade (`WatchedTokens`, U4) isso mantém o token fora dos preços por até uma hora após uma única falha do `info`. Na U2 `WatchedTokens` é vazio, então o efeito hoje é só HOLDERS/CREATED_AT desatualizados. | Gravar a marca só em caso de sucesso (ou usar um recuo curto após falha), pelo menos para tokens sem identidade, antes de a U4 passar a injetar tokens observados. | New |
| R-04 | Minor | apps/api/src/main/java/com/memetrade/marketdata/infra/coingecko/CoinGeckoHttp.java:`get`/`read` (e construtor, `setReadTimeout`) | O limite de 4 s do `JdkClientHttpRequestFactory` cobre a espera pelos cabeçalhos; a leitura do corpo em fluxo não tem prazo próprio. Um servidor que goteja bytes após os cabeçalhos ultrapassa os 4 s por chamada. O prazo interno de 18 s e o corte de 25 s do SourceCaller (com `cancelRunningFuture`) ainda protegem a rodada, mas a regra "4 s por chamada" não é garantida só no HTTP. | Impor um prazo total por chamada (cabeçalho + corpo), por exemplo checando o relógio no `LimitedInputStream`, ou registrar a limitação como aceita e coberta pelo prazo de 25 s. Acrescentar um teste com WireMock com `withChunkedDribbleDelay`. | New |
| R-05 | Minor | apps/web/aidlc/ (não rastreado, `.aidlc-sessions/<id>.binding.json`) | Arquivo de sessão do AI-DLC criado dentro de `apps/web` (já declarado no code-summary) faz o `pnpm format:check` falhar e não deve entrar em commit. | O responsável do projeto remove a pasta antes do commit da Unit; confirmar que `.gitignore` cobre `**/aidlc/.aidlc-sessions/`. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Maven (`mvnw verify`) | Não executado por mim, por instrução do pedido (memória baixa). O desenvolvedor reporta BUILD SUCCESS, 1522 testes de unidade e 981 ITs, cobertura de linhas 93,6% (piso 80%); 110 testes da Unit passam. | Resultado aceito como relatado, sem verificação independente; não há razão para bloquear por isso. |
| Leitura estática dos pontos duros | Sem `double`/`float` em valores de preço ou quantidade (só `budgetRatio`/idade em métricas); números JSON como `BigDecimal` exato; chave só no cabeçalho, nunca em log, rótulo de métrica ou `NEXT_PUBLIC_*`; `Redirect.NEVER`, `Accept-Encoding: identity`, corte de 2 MB e `StreamReadConstraints`; regra ArchUnit confina rede a `infra.coingecko`; upsert do contador antes de cada chamada, em transação própria; mapa de estados tolerante (DEGRADED depois FAILED); prazos 18 s / 12 s / 4 s e SourceCaller 25 s com 1 tentativa implementados. | Conforme o desenho. |
| Migração 0017 | Tabela nova, expand-only, com `--rollback` e chave primária (source, month). | Sólida. |

### Julgamento das mudanças de comportamento sinalizadas

- **Coleta só no worker (`CoinGeckoCollectorJob`)**: coerente com NFR1.6 e com o `infrastructure-specification.md`; a API não constrói cliente nem lê a chave. O custo (eventos `MarketCollectionCompleted` publicados no worker; `EquityMarkingJob` e `PendingCloseJob` passam a depender do ciclo de 60 s) está documentado e aceitável. Falta a prova de contexto (achado Major acima).
- **`@ActiveProfiles("local")` em `BotWalletRoutesIT` e `WorkerStoresIT`**: coerente com a regra BR1.6, que isenta explicitamente `test` e `local`; o perfil não tem outro efeito no código. Sólido.
- **Ausente x inválido na métrica**: coerente com a especificação funcional (BR1.2) e melhora o alerta de 5%, que passa a medir só valores realmente inválidos. Em ambos os casos o valor vira `NOT_PROVIDED`, nunca zero. Sólido.
- **Arredondamento acima de 18 casas**: meio-par para 18 casas, com valor positivo que viraria zero tratado como inválido (nunca mostrado como zero). Consistente com a regra "nunca zero"; perda de precisão em preços abaixo de 1e-18 é aceitável para o beta.

### Summary

A implementação segue o desenho e as regras duras do projeto (decimal exato, `unknown` em vez de zero, chave isolada, rede confinada, orçamento antes da chamada, prazos e estados tolerantes), com testes determinísticos sem rede real. A única lacuna relevante é a falta de um teste de contexto completo do worker com a CoinGecko ligada (Major, dentro do limite de dois); os demais pontos são Minor. READY, recomendando fechar o IT do worker antes do checkpoint.
