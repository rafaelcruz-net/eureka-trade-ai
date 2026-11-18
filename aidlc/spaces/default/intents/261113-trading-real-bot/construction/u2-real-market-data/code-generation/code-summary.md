# Resumo da Code Generation — U2 `u2-real-market-data` (Bolt B2)

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (aprovado), `unit-test-instructions.md`, `functional-design/` (BR1.1 a BR1.10), `nfr-requirements/`, `nfr-design/`, `infrastructure-design/` e `inception/contract-design/contract-summary.md` (M4, X2).

Testing Contract: `custom`, `comprehensive`. A U2 é adaptador e configuração; só o `ValueParser` (regra "dado ausente vira `unknown`, nunca zero") teve os testes escritos antes do código. O teste rodou vermelho (classe inexistente) antes da implementação e verde depois.

## O que foi feito, por etapa

| Etapa | Entregue |
|---|---|
| 1 Estrutura e configuração | Pacote `marketdata.infra.coingecko`; `coingecko` em `KNOWN_SOURCES` (com `SIMULATED_SOURCES`); `CoinGeckoProperties` (valores do desenho, mensagens com o nome da variável, hosts permitidos fora do perfil `test`); `COINGECKO_API_KEY` lida só no `CoinGeckoConfig` (`@ProcessRole(WORKER)` + condição `MARKET_SOURCES` contém `coingecko`); regra ArchUnit de rede ampliada (`java.net`, `java.net.http`, `org.springframework.web.client`, `org.springframework.http.client`) fora de `infra.coingecko`, com teste que comprova a falha (`NetworkUsingFixture`); WireMock `org.wiremock:wiremock-standalone:3.13.1` só em teste |
| 2 Runner | Comando por escopo da Unit confirmado com um teste vazio, removido depois |
| 3 `ValueParser` [testes antes] | Decimal exato, faixas por campo, `NOT_PROVIDED` para ausente (sem contar) e para inválido (contando em `marketdata_invalid_values_total{field}`), `Known 0` para zero informado, CREATED_AT ISO → segundos de época, nunca no futuro |
| 4 `CoinGeckoHttp` | `RestClient` sobre `JdkClientHttpRequestFactory`, redirecionamento desligado (3xx = falha não repetível), `Accept-Encoding: identity` e recusa de resposta comprimida, corte em 2 MB e `StreamReadConstraints`, números como `BigDecimal`, mint validado antes da URL, chave só no cabeçalho, mensagens só com rota e código, 4 s de resposta e 2 s de conexão |
| 5 `CallBudget` | Janela deslizante (250/min, 2/min de detalhes); migração `0017-marketdata-provider-usage.sql`; upsert condicional **antes** da chamada numa transação própria (`REQUIRES_NEW`); prioridades 98/95/90%; modo conservador; `CoinGeckoUsageJob` (ShedLock, na subida + 5 s e de hora em hora) que guarda o maior valor; retenção de 13 meses na limpeza do histórico |
| 6 `CoinGeckoAdapter` | Rodada em série: preços em lotes de 30, descoberta a cada 5 min (com ≥ 8 s restantes), detalhes (≥ 4 s, 2/min, renovação de 60 min); prazo interno de 18 s e nenhuma chamada nova depois de 12 s pelo relógio injetável; uma repetição só em tempo esgotado/rede/5xx; 429 sem repetição e `Retry-After` vale para as rodadas seguintes; circuito Resilience4j (60 s, 5 chamadas, 50%, 60 s aberto); devolve só os campos renovados e o `RoundSummary` |
| 7 Coletor e `SourceCaller` | `SourceCaller` com política por fonte (`coingecko`: 25 s e 1 tentativa; simuladas: 5 s e 3); `MarketCollector` com `tolerantFailure` nos dois caminhos (primeira rodada sem preço DEGRADED, segunda FAILED), escrita só dos campos enviados, categoria `TIMEOUT`/`ERROR_RESPONSE` do corte (parcial sem corte: `INVALID_PAYLOAD`); `JdbcMarketStore` não apaga a criação quando CREATED_AT não vem |
| 8 `Freshness` por campo | 5 min para PRICE/LIQUIDITY/VOLUME_24H, 2 h para HOLDERS/CREATED_AT; `ResolvedField` e `MarketValue` sem mudança |
| 9 Interface M4 | `MarketDataQuery.activeSourceKind()` e `attribution()` (padrões derivados das fontes para os dublês, com fail-closed SIMULATED; produção pela configuração); `SourceKind`, `Attribution`, `WatchedTokens` (vazio, `@ConditionalOnMissingBean` para a U4 substituir) e `ResolvedFields.singleSource` (BR1.7) |
| 10 Trava de subida | `MarketSourceGuard`: REAL ligado + perfil diferente de `test`/`local` + `sim-a`/`sim-b` → não sobe. `CoinGeckoApiStartupIT` confirma que a API sobe com REAL ligado, `MARKET_SOURCES=coingecko` e sem a chave, sem cliente, adaptador ou trabalho da CoinGecko |
| 11 API do scanner e tela | `dataSource { kind, attribution? }` obrigatório em `ScannerPage` e `Assessment`; `openapi-guest.yaml` regenerado (`-Dopenapi.write=true`) e `schema.d.ts` regenerado (`pnpm gen:api`); componente `DataSourceNotice` com "Dados: CoinGecko" e link HTTPS (`scanner-attribution`, `assessment-attribution`) ou aviso "fonte simulada ativa" com ícone e texto (`scanner-simulated-active`) |
| 12 Métricas | `marketdata_provider_calls_total`, `_budget_ratio`, `_conservative_mode`, `marketdata_round_seconds`, `marketdata_source_state{source,state}` (1 no estado atual), `marketdata_invalid_values_total`, `marketdata_observations_total`, `marketdata_active_tokens`, `marketdata_oldest_price_age_seconds`; rótulos sem chave, mint ou URL (testado) |
| 13 Alertas, Terraform, `.env.example` | `infra/observability/marketdata-alerts.yml` (6 alertas + regra de gravação `marketdata_price_fresh_minute`); `infra/terraform/modules/observability/marketdata.tf` (6 alarmes); `infra/terraform/modules/services/coingecko.tf` (segredo `coingecko-api-key` e política de leitura só para o worker); `CoinGeckoAlarmParityTest` compara nome, limite e janela; `.env.example` com `# COINGECKO_API_KEY=` sem valor |
| 14 Documentação | `docs/runbooks/coingecko-api-key.md` (giro de 90 dias, vazamento, alertas, rollback), este resumo, `traceability.json` e `source-manifest.json` |

## Testes e resultados

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.marketdata.infra.coingecko.**.*Test,com.memetrade.marketdata.**.CoinGecko*IT,com.memetrade.marketdata.**.SourceStateContractTest,com.memetrade.marketdata.**.FieldFreshnessTest,com.memetrade.marketdata.**.MarketSourceGuardTest,com.memetrade.architecture.ArchitectureTest'` | 110 testes, 0 falhas; depois das correções da revisão, 115 testes, 0 falhas |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` (uma vez, no fim) | BUILD SUCCESS: 1522 testes de unidade e 981 de integração, 0 falhas; "All coverage checks have been met" |
| `pnpm --dir apps/web exec jest src/features/scanner` | 4 suítes, 48 testes, 0 falhas |
| `pnpm --dir apps/web test:coverage` | 45 suítes, 550 testes, 0 falhas |
| `pnpm --dir apps/web lint`, `typecheck`, `prettier --check src`, `check:api` | sem erro; `schema.d.ts` igual ao `openapi-guest.yaml` |
| `promtool check rules` (imagem `prom/prometheus:v3.5.0`) | `marketdata-alerts.yml`: 7 regras; `real-alerts.yml`: 12 regras |
| `terraform fmt -check` e `terraform validate` (imagem `hashicorp/terraform:1.9.8`, cópia dentro do contêiner) | sem diferença; configuração válida |

Testes novos do backend por componente (com as correções da revisão): `ValueParserTest` 15, `CoinGeckoHttpTest` 14, `CallBudgetTest` 10 + `CoinGeckoBudgetIT` 5, `CoinGeckoAdapterTest` 16, `CoinGeckoWorkerIT` 2, `SourceStateContractTest` 8 + `CoinGeckoSourceCallerTest` 4 + `CoinGeckoCollectionIT` 2, `FieldFreshnessTest` 5, `MarketSourceGuardTest` 9 + `CoinGeckoConfigTest` 5 + `CoinGeckoApiStartupIT` 2, `DataSourceDtoTest` 3 + 1 no `GuestOpenApiContractTest`, `CoinGeckoMetricsTest` 3, `CoinGeckoAlarmParityTest` 4, `ArchitectureTest` +2. Web: `DataSourceNotice.test.tsx` 8. Nenhum teste chama a CoinGecko real: todas as respostas são sintéticas (`CoinGeckoFixtures`, mints gerados por SHA-256 + Base58).

**Cobertura de linhas:** backend 93,60% no relatório JaCoCo combinado (módulo `marketdata` 94,41%); web 95,17%. Nenhuma exclusão nova e nenhum piso alterado.

## Correções da revisão (READY com 1 Major e Minors)

| Achado | Correção | Teste |
|---|---|---|
| Major: faltava um teste do contexto Spring do worker com a fonte real | `CoinGeckoWorkerIT` (perfis `test` e `worker`, `MARKET_SOURCES=coingecko`, chave de teste, WireMock iniciado antes do contexto, PostgreSQL do Testcontainers): confere que `CoinGeckoCollectorJob`, `CoinGeckoUsageJob`, `CoinGeckoAdapter` e `CoinGeckoHttp` existem e que `collectNow()` grava as observações no PostgreSQL e conta as chamadas em `marketdata_provider_usage` | 2 testes |
| Minor: circuito aberto consumia o orçamento | `CoinGeckoAdapter.gate`: o circuito é consultado **antes** do orçamento; uma chamada recusada pelo orçamento devolve a permissão do circuito (`releasePermission`); circuito aberto nos detalhes ou na descoberta para a rodada sem cortar o estado (só os preços cortam) | `CoinGeckoAdapterTest.anOpenCircuitMakesNoCallAndCountsNothingInTheBudget` |
| Minor: detalhe marcado como renovado antes do sucesso | `infoAt`/`poolsAt` só mudam depois de uma chamada bem-sucedida; a tentativa fica em `infoTriedAt`/`poolsTriedAt` e só reordena a fila (o detalhe que falhou vai para o fim e volta numa rodada seguinte, nunca na mesma) | `CoinGeckoAdapterTest.aDetailThatFailedIsNotRenewedAndIsTriedAgainInTheNextRound` |
| Minor: os 4 s por chamada não valiam para a leitura do corpo | `CoinGeckoHttp.get` roda a chamada numa thread própria (daemon) e espera no máximo `callTimeout` sobre a chamada inteira (cabeçalhos e corpo); passado o prazo, a chamada é cancelada com interrupção e vira `TIMEOUT` repetível | `CoinGeckoHttpTest.aBodyThatTricklesInPastTheDeadlineIsATimeout` (WireMock com corpo em gotas por 8 s, corte em 2,5 s) |

Comandos depois das correções (um Maven por vez, sem `verify` completo):

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -B -ntp -q -f apps/api/pom.xml spotless:apply` | formatação aplicada |
| Comando da Unit (o mesmo da tabela abaixo) | 115 testes, 0 falhas |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.marketdata.CoinGeckoWorkerIT,com.memetrade.config.WorkerProcessRoleContextIT,com.memetrade.config.ProcessRoleContextIT,com.memetrade.marketdata.MarketStoreIT,com.memetrade.scanner.ScannerIT,com.memetrade.marketdata.MarketCollectorIT'` | 33 testes, 0 falhas (contextos vizinhos que dividem o banco) |

A cobertura não foi medida de novo: o comando por escopo roda sem o agente do JaCoCo. A última medição é a do `verify` completo abaixo; as correções só acrescentaram testes ao código alterado.

## Testes existentes ajustados

- `ScannerControllerTest`: o controller recebe o `MarketDataQuery` (dublê SIMULATED).
- `GuestOpenApiSlice`: `@MockitoBean MarketDataQuery`.
- `BotWalletRoutesIT` e `WorkerStoresIT` (U1): ligam o REAL com as fontes simuladas, o que a trava BR1.6 só aceita nos perfis `test`/`local`; passaram a usar `@ActiveProfiles("local")` (perfil sem nenhum outro efeito no código).
- Fixtures do web (`fixtures.ts`, `test-support.tsx`, dois testes do painel) ganharam `dataSource`, porque o contrato agora o exige.

## Decisões de implementação a conhecer

1. **Coleta da CoinGecko no worker.** O `MarketCollectorJob` existente é `@ProcessRole(API)` e continua coletando `sim-a`/`sim-b` na API. A fonte real tem um agendamento próprio no worker (`CoinGeckoCollectorJob`), que reaproveita o mesmo `MarketCollectorJob` (trava ShedLock `market-collect-coingecko`, métricas, evento). A API registra a fonte `coingecko` (PENDING) mas não tem o adaptador.
2. **Evento entre processos.** O `MarketCollectionCompleted` da coleta real é publicado no worker. O `RiskRecalculator` existe nos dois processos e reage; o `EquityMarkingJob` e o `PendingCloseJob` são da API e passam a depender do seu ciclo de reserva de 60 s para a fonte real (antes reagiam a cada coleta simulada).
3. **Valores ausentes x inválidos.** Ausente vira `NOT_PROVIDED` sem contar na métrica; presente e inválido vira `NOT_PROVIDED` e conta, como na especificação funcional (o plano resumia os dois juntos). Assim o alerta de 5% mede só valores realmente inválidos.
4. **Mais de 18 casas decimais.** O preço é arredondado meio-par para 18 casas (precisão guardada); um valor positivo que viraria zero é inválido, nunca mostrado como zero.
5. **Formato das rotas da CoinGecko.** O adaptador lê as rotas on-chain (`/onchain/simple/networks/{rede}/token_price/...`, `new_pools`, `trending_pools` com `include=base_token`, `tokens/{mint}/info` e `tokens/{mint}/pools`, `/key`) e o cabeçalho `x-cg-pro-api-key`. As respostas dos testes seguem esse formato, que precisa ser conferido na devnet junto com a compressão.
6. **Token observado sem identidade conhecida.** Um token de `WatchedTokens` que a descoberta nunca trouxe só entra nos preços depois que a chamada de detalhe `info` traz símbolo, nome e casas decimais (prioridade na fila de detalhes).
7. **`activeTokens` do `RoundSummary`** conta os tokens ativos com identidade conhecida (os que podem ser enviados ao coletor).

## Pendências

- **NFR9.1 (Deferred):** preço do plano da CoinGecko não confirmado; o fundador decide antes da produção.
- **Conferência na devnet:** resposta sem compressão e formato real das rotas (item 5). Se a CDN ignorar `Accept-Encoding`, o gzip passa a ser aceito com o corte de 2 MB sobre o fluxo descomprimido (cicd-pipeline.md).
- **Worker sem tarefa própria no Terraform:** a política `worker-reads-coingecko-key` precisa ser anexada ao papel de execução do worker quando ele tiver tarefa própria; a injeção de `COINGECKO_API_KEY` e `MARKET_SOURCES=coingecko` também.
- **Métricas na nuvem:** os alarmes leem o namespace `Eureka/MarketData`; o caminho das métricas do Micrometer até o CloudWatch (registry ou agente) ainda não existe.
- **Arquivo de sessão fora do lugar:** `apps/web/aidlc/.aidlc-sessions/<id>.binding.json` foi criado pelo harness do AI-DLC quando comandos rodaram dentro de `apps/web`. Não é desta Unit e eu não mexo em estado do AI-DLC; ele faz o `pnpm format:check` do `apps/web` reclamar e não deve ser commitado. O responsável do projeto decide removê-lo.

## Texto para o responsável do projeto aplicar (CI)

O `cicd-pipeline.md` diz que o `promtool` já valida as regras de `infra/observability/` na etapa da U1, mas não há essa etapa no `.github/workflows/`. Sugestão de passo para o job `backend` (troque a tag pelo digest SHA da imagem, como pede o `team.md`):

```yaml
      - name: Validate alert rules (promtool)
        run: >
          docker run --rm -v "$PWD/infra/observability:/rules:ro" --entrypoint promtool
          prom/prometheus:v3.5.0@sha256:<digest> check rules
          /rules/real-alerts.yml /rules/marketdata-alerts.yml
```

Nenhuma outra mudança de CI é necessária: os testes da U2 rodam no `mvnw verify` existente e o `scripts/verify.sh` continua com `sim-a,sim-b`.

## Volta 1 do Build and Test (2026-11-18)

O Build and Test (Loop-back 1 em `construction/build-and-test/test-results.md`) pediu duas correções nesta Unit. As etapas 1 a 14 não mudaram.

### Step 15 — Rastreabilidade no formato do sensor

- O `traceability.json` foi reescrito com uma linha por critério de aceite, regra e NFR detalhado da Unit: AC6.3.1 a AC6.3.3, BR1.1 a BR1.10 e os 33 NFRx.y (49 IDs). Cada alvo `OK` é um arquivo existente que implementa o ID; quando a implementação fica em dois ou três arquivos, o ID tem uma linha por arquivo.
- A linha da US6.3 saiu: a história é rastreada pelos três critérios de aceite.
- Linhas que não são `OK`, cada uma com o motivo:
  - **AC6.3.3** `N/A`: a recusa da ordem REAL por dado vencido é da U3. A U2 entrega só o horário da busca de cada campo (BR1.4).
  - **NFR2.1** `N/A`: é uma restrição de ausência (a U2 não toca em ledger nem em saldo). A verificação pedida é a revisão de código: nenhuma classe de `com.memetrade.marketdata` referencia `ledger` ou `botwallet`. Nenhum arquivo implementa essa restrição, e nenhuma regra ArchUnit a verifica.
  - **NFR3.4** `Deferred`: a meta "sem piora na leitura do scanner" depende do teste de carga existente, que ainda não rodou. A U2 só acrescenta a atribuição à resposta.
  - **NFR9.1** `Deferred`: o preço do plano da CoinGecko ainda não foi confirmado, e o fundador decide antes da produção.
- O AC6.3.2 aponta para o `MarketSourceGuard`, que impede a API e o worker de subir com fonte simulada quando o REAL está ligado fora de `test` e `local`. A recusa da ordem com fonte simulada continua na U3.
- Três NFRs ficaram `OK` porque a implementação existe, mas a meta numérica ainda não foi medida (no Build and Test eles estão `Unverified`):
  - NFR3.1 a NFR3.3: os prazos estão no `CoinGeckoAdapter`, mas as durações de 10 s e 5 s não foram medidas;
  - NFR4.1: a regra de gravação está em `marketdata-alerts.yml`, mas os 99% de minutos com preço fresco só se medem com a CoinGecko real.
- Sensor: `aidlc engine sensor-traceability --stage code-generation` sobre uma cópia em `construction/u2-real-market-data/code-generation/` → `pass: true`, 0 achados.

### Step 16 — Exceção EX-014 (GHSA-fc5q-39xc-vjpf, `picomatch`)

- A EX-014 entrou em `security/exceptions.yml` com:
  - ferramenta `pnpm-audit`;
  - a justificativa;
  - a aprovação do responsável do projeto em 2026-11-18;
  - a validade até 2027-02-16 (90 dias).
- O mesmo GHSA entrou em `pnpm.auditConfig.ignoreGhsas` de `apps/web/package.json` e `apps/admin/package.json`. É preciso porque o workflow `security` roda `pnpm --dir apps/<app> audit --audit-level high` e só converte em supressão as exceções do `dependency-check`.
- **Só de desenvolvimento:** o `pnpm why picomatch --prod` não mostra nada nos dois apps. O pacote vem do Jest e do ESLint.
- **Aviso retirado:** o GitHub retirou o aviso em 2026-11-18T15:38:56Z, e o registro do npm já não o devolve. Por isso o `pnpm audit` local passava mesmo antes da mudança. A exceção fica como proteção caso o aviso seja publicado de novo. Quando a retirada se confirmar, ou quando sair um `picomatch` corrigido, a exceção e o `ignoreGhsas` saem.
- **Verificação:**
  - `corepack pnpm@10.18.3 audit --audit-level high` passa nos dois apps, só com 3 avisos Moderados;
  - numa cópia descartável, o pnpm mostrou "(1 ignored)", o que confirma que ele lê o `auditConfig` do `package.json`;
  - o `install --frozen-lockfile --lockfile-only` passa nos dois apps, e os lockfiles não mudaram;
  - o `SecurityExceptionsTest` passa (3 testes, 0 falhas).
