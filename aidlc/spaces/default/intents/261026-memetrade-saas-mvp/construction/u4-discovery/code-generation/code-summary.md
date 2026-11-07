# Resumo do código — U4 Descoberta e avaliação

Código gerado para a Unit `u4-discovery` a partir do [plano aprovado](code-generation-plan.md), com os testes descritos em [unit-test-instructions.md](unit-test-instructions.md). Os 21 passos foram executados e marcados. O Passo 20 (smoke da U4) foi concluído depois que o responsável do projeto acrescentou `e2e/u4-discovery` à linha do Playwright em `scripts/verify.sh`. Rastreabilidade em [traceability.json](traceability.json) e caminhos em `source-manifest.json`.

## O que a U4 entrega

- **MarketData:** duas fontes simuladas (`sim-a`, `sim-b`, 50 tokens em comum, semente 42); validação na porta; chamada com tempo limite e novas tentativas (Resilience4j 2.3.0, espera por `Sleeper` injetável); coleta numa transação com estado `PENDING`/`OK`/`DEGRADED`/`FAILED`, histórico com resolução de 5 minutos e marca global (C18); leitura de campos com valor mais recente, desatualizado na leitura e divergência por comparação cruzada; variação de preço de 1 hora; limpeza horária; `MarketDataQuery` (C11); saúde `marketSources` (nunca `DOWN`, com `lastUpdatedAt`).
- **RiskAssessment (módulo novo):** curvas, cobertura, faixas, `unknown` com risco 100 e nunca `LOW`; versões de pesos com validação, ativação auditada (`RISK_WEIGHTS_ACTIVATED`) e versão 1 semeada; recálculo após cada coleta e ativação, um por vez, com guarda de versão; `RiskAssessmentQuery` (C12); `RiskWeightAdministration` para a U8; saúde `riskRecalculation`.
- **Scanner:** consulta única com filtros inclusivos, `excludedByUnknown`, foto da lista e `pendingNewTokens`, ordem com `unknown` no fim; limitador de 60 por pessoa e rota; rotas da lista e do detalhe com os acréscimos do C2 (`PENDING`, `computedAt`, `bandRaisedByUnknown`, `sources` no `503`, `RATE_LIMITED`).
- **Frontend:** tela S3 com filtros na URL, estados distintos, marcas de desatualizado e divergência, coluna Risco com `--`; painel lateral com indicador, fatores e retorno de foco; catálogo de termos proibidos com teste.
- **Ambiente:** variáveis da U4 no `docker-compose.yml` e no `.env.example`; pool de 20 e agendador de 7; script de carga com cenários U1, U4 e crescimento.

## Resultados

| Comando | Resultado |
|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u4 test-compile surefire:test failsafe:integration-test failsafe:verify` | BUILD SUCCESS: 139 unitários e 81 de integração |
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` | Verde (259 e 249), cobertura atendida |
| Comando da U2 | Verde (144 e 102) |
| `apps/api/mvnw -f apps/api/pom.xml verify` | BUILD SUCCESS: 509 unitários e 249 de integração; **95,72%** de linhas (antes: 95,61%) |
| `pnpm --dir apps/web exec jest src/features/scanner src/content` | 44 testes verdes |
| Jest com cobertura (comando da CI) | 105 testes; **94,36%** de linhas |
| `spotless:apply`, `tsc`, lint | Limpos |
| `bash scripts/verify.sh` (verificação do ponto de controle da U1, 04/11/2026) | Exit 0; 5 testes do Playwright verdes, inclusive o smoke da U4 |

**Smoke da U4 e limite de login:** na primeira execução, o smoke da U4 falhou. A suíte faz cinco logins seguidos a partir do mesmo IP e chegou ao limite real de 10 tentativas por minuto (desafio e verificação contam cada um). Por decisão do responsável do projeto, o limite não mudou. O helper `apps/web/e2e/u4-discovery/sign-in.ts` (`connectAndReachScanner`) espera uma janela de 65 s quando aparece "Muitas tentativas" e tenta entrar uma única vez mais. Qualquer outra recusa continua derrubando o teste.

## Testes escritos e não executados

- **Script de carga** (cenários U1, U4 e crescimento): escrito, conferido com `bash -n` e `node --check`, não executado; é evidência do ponto de controle da U4.
- **Rollback manual para a U2** (cicd-pipeline.md): a executar no ponto de controle.

## Decisões de implementação

- **Fora do desenho:** coluna `data_source.enabled`, para que a fonte `simulated` da U1, que não está mais configurada, não conte na lista, na saúde nem na avaliação.
- **Consulta da lista:** o valor de cada campo é buscado por token pelo índice `market_observation_pick_idx`. O `DISTINCT ON` sobre a tabela inteira gerava planos ruins com estatísticas defasadas: 2,5 s antes, cerca de 0,1 s depois, com mais de 2 000 tokens. O filtro de fonte reduz os candidatos logo no início.
- **Leitura de `risk_assessment` pela lista:** é a junção da NFR Design (Q3) e a única leitura dessa tabela fora do módulo.
- **Token de administrador em rota do convidado:** `403 FORBIDDEN_ROLE` auditado no `AdminRouteFilter`. É a regra comum do contrato, que a U1 ainda não aplicava.
- **`RiskWeightAdministration`:** recebe o `HttpServletRequest`, porque o `AdminAccess` exige a requisição, e não o `Principal` previsto no security-design.md. Ativações concorrentes são serializadas por advisory lock.
- **Coleta e recálculo:** a coleta publica `MarketCollectionCompleted` e o RiskAssessment escuta, então o MarketData não depende do RiskAssessment. Nos testes, o recálculo em segundo plano só roda com o agendamento ligado.
- **Termos proibidos:** o catálogo tem os 4 termos da BR5.1 mais termos propostos, que ainda precisam da revisão do responsável (`reviewedBy: pendente`).

## Mudanças em testes da U1 e da U2

- Pool de 10 para 20 e agendador de 3 para 7.
- `marketSource` virou `marketSources`; a saúde não degrada mais só pela idade (Q1 da NFR Design).
- As linhas do C2 passaram a trazer `risk`.
- `ScannerIT` filtra pela própria fonte: sem o filtro, o teste de p95 de 150 ms media os milhares de tokens deixados por outras classes no banco compartilhado. O limite não foi reduzido.
- `AbstractPostgresIT` sobe o limite de requisições para 10 000 nos percursos de página e limpa o limitador. `ScannerRateLimitIT` confere o limite real de 60.

## Pontos em aberto

- Quando a nova tentativa de login do smoke da U4 acontece, nada a registra. Uma segunda recusa por limite aparece só como tempo esgotado.
- Revisar os termos propostos do catálogo de termos proibidos.
- Copiar os acréscimos do C2 de `docs/api/c2-market-scanner-assessment.md` para `contract-summary.md`.
- Dois testes põem todas as fontes em `FAILED` e restauram o estado no `finally`. Eles dependem de execução sequencial.
- Na execução completa de integração, os logs capturados podem vir vazios porque outro contexto de teste fecha o Logback. A conferência positiva de log fica em `MarketCollectorJobTest`, e o `LogScanIT` da U4 faz só conferências negativas.
- **Rollback:** uma imagem anterior à U4 não lê uma fonte ainda `PENDING`. O procedimento está documentado na seção de rollback do README.
- O custo da consulta da lista cresce com o total de tokens; o NFR3.1 precisa ser confirmado pelo cenário de carga.

## Volta 1 (Build and Test, 2026-11-06)

O Build and Test reprovou a rastreabilidade entre Units (achado T2): o registro da geração de código da U4 só tinha os critérios de aceite. Esta volta acrescentou ao [traceability.json](traceability.json) os 57 IDs de NFR do `nfr-design/traceability.json`, cada um com uma linha em `coverage` cujo `target` é um único caminho que existe no repositório, de preferência o teste que confere a meta. Explicações e alvos secundários ficam no campo `note`, como no registro da U2. Os 17 critérios de aceite não mudaram. Nenhum código de produção nem teste mudou.

### Contagem por status (NFR)

| Status | Quantidade |
|---|---|
| `OK` | 41 |
| `Deferred` | 15 |
| `N/A` | 1 (NFR10.1: sem meta numérica de disponibilidade enquanto o beta roda só localmente) |
| **Total** | 57 |

Com os 17 critérios de aceite (`OK`), o registro tem 74 linhas.

### Lista dos `Deferred`

| NFR | Motivo |
|---|---|
| NFR1.3 | Medição de 1 hora com fonte real, prevista para depois do G0 (SP-1). As fontes da U4 são simuladas. |
| NFR2.1 | Lacuna: a coleta numa transação está implementada e a métrica de duração existe, mas nenhum teste nem o script de carga confere a meta de 10 s para 2 000 tokens. |
| NFR2.3 | Lacuna: a limpeza em lotes de 10 000 está implementada e a fronteira de 24 h é testada, mas nada mede a meta de 60 s para uma hora de amostras. |
| NFR2.5 | Lacuna: nada confere o teto de 4,7 milhões de linhas nem os 2 GB em disco do histórico. |
| NFR3.3 | Lacuna parcial: o teste confere que o painel não recarrega a lista, mas nada mede o p95 de 2 s da tela com 20 usuários (o script de carga mede só o servidor). |
| NFR1.2 | Lacuna (revisão da volta 1): o requisito pede um teste com relógio injetável que confira a idade de até 60 s depois de duas coletas seguidas; o teste atual confere só o intervalo de 30 s. |
| NFR5.1 | Lacuna (revisão da volta 1): o teste de registro de rotas globais pedido no requisito não existe; a evidência é a migração `0006` sem tenant, `ScannerIT` e `U4ArchitectureTest`. |
| NFR9.2 | Lacuna parcial (revisão da volta 1): `market_collection_retries_total`, descartes e último sucesso não são conferidos por teste. |
| NFR9.3 | Lacuna parcial (revisão da volta 1): as durações do recálculo e da limpeza, `risk_assessments` e `market_history_samples` não são conferidas por teste. |
| NFR9.4 | Lacuna parcial (revisão da volta 1): `scanner_rate_limited_total` não é conferido por teste. |
| NFR10.5 | Lacuna parcial (revisão da volta 1): a volta de `FAILED` a `OK` e a volta das avaliações a `AVAILABLE` não têm teste próprio. |
| NFR3.5 | **Reprovado, não apenas adiado.** O cenário de crescimento com 10 000 tokens rodou e não cumpriu as metas (p95 da lista em 10,02 s, postgres com 990 MB; `docs/perf/u4-discovery-2026-11-06-growth.md`). A decisão de escala antes do G1 é do responsável do projeto. |
| NFR6.8 | Lacuna: o teste de termos proibidos passa localmente, mas o passo de Jest da CI (`.github/workflows/ci.yml`) não inclui `src/content`, então ele não roda na CI nem bloqueia o merge. |
| NFR9.8 | Lacuna: o evento `RISK_WEIGHTS_ACTIVATED` é testado, mas o log `INFO` da ativação leva o ator como `userId` sem máscara (o desenho pede ator mascarado) e nenhum teste confere esse log. |
| NFR14.1 | Lacuna parcial: as explicações dos termos ficam só no atributo `title` dos cabeçalhos (aparecem com o mouse, não com o foco); a explicação de `stale` existe no catálogo, mas não é usada na tela; nenhum teste confere as dicas. |

### Ressalvas nas linhas `OK`

- NFR1.2, NFR5.1, NFR9.2, NFR9.3, NFR9.4 e NFR10.5 passaram de `OK` para `Deferred` depois da revisão da volta 1, porque as próprias notas admitiam a falta do teste pedido.
- NFR2.8 e NFR13.1: o alvo implementa a regra, mas não a verifica por teste.
- NFR4.1, NFR5.1, NFR6.9, NFR9.7 e NFR11.2 se apoiam em testes fora do marcador `u4` (`ScannerIT`, `CorrelationIdFilterTest`, `U4ArchitectureTest`, `shell.test.tsx`), que rodam só na suíte inteira e não foram reexecutados nesta volta.
- NFR9.1 segue a decisão Q1 da NFR Design (`DEGRADED` com HTTP 200, nunca `DOWN`), e não o texto original do requisito.

As lacunas ficam registradas para decisão do responsável do projeto; esta volta não corrige código.

### Conferência (Passo 23)

| Verificação | Resultado |
|---|---|
| `traceability.json` é JSON válido; 74 IDs em `upstream_ids` e 74 linhas em `coverage`, sem repetição | Conferido |
| Cada `target` existe no repositório e é um único caminho | Conferido (0 ausentes) |
| `pnpm --dir apps/web exec jest src/features/scanner src/content` | 4 suítes, 45 testes verdes |
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u4 test-compile surefire:test failsafe:integration-test failsafe:verify` | BUILD SUCCESS: 139 unitários e 84 de integração, 0 falhas |

O marcador `u4` não seleciona todos os alvos citados. `ScannerIT`, `CorrelationIdFilterTest`, `U4ArchitectureTest` e `apps/web/src/lib/shell.test.tsx` ficam de fora desses dois comandos e rodam na suíte inteira (`verify` e Jest com cobertura), que estava verde na geração e não foi repetida nesta volta.
