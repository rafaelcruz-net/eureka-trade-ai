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
