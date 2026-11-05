# Pipeline de CI — U6 Barreira de limites

A U6 usa os workflows existentes (`.github/workflows/ci.yml`, `security.yml` e `load-test.yml`) e não cria workflow novo. As regras de sempre continuam:
- ações de terceiros fixadas por SHA;
- `permissions` mínimas;
- lockfiles versionados;
- integração por squash-merge em `main`, nunca com a CI vermelha.

Os IDs `NFRx.y` são os da U6.

## Estágios e portões

| Estágio | Job | O que a U6 acrescenta | Portão |
|---|---|---|---|
| Lint e formato | `backend` (Spotless) | Módulo `riskcontrol` | Falha bloqueia o merge |
| Testes do backend | `backend` (`mvnw verify`) | Marcador `u6`. Mais abaixo, a lista de testes | Falha ou cobertura < 80% bloqueia |
| Contrato | `contract` | OpenAPI do convidado com `GET /api/v1/bots/{botId}/rejections` e os campos aditivos; tipos do frontend regenerados; teste que proíbe outro método nessa rota | Diferença entre gerado e versionado bloqueia |
| Smoke | `smoke` (`bash scripts/verify.sh`) | Sem teste novo (Q1, opção B). O esqueleto e os smokes da U2, U4 e U7 continuam passando com a `0009` e o papel novo | Falha bloqueia |
| Segurança | `security` | Nenhuma dependência nova | Crítico ou Alto bloqueia |
| Carga | `load-test.yml` (manual) e `scripts/load-test.sh` local | Cenário `u6`, mais abaixo | Não bloqueia o merge; é evidência do ponto de controle da U6 |

### Testes do backend

**Escritos antes do código (TDD, núcleo financeiro):**
- catálogo E1 a E21 (`RiskGateCatalogTest`);
- propriedade com seed fixa (`RiskGatePropertyTest`);
- conferência do slippage e da queda de patrimônio com a política vigente da U7 (`SlippageConformanceIT`);
- reprodução de recusa (`RejectionReplayTest`).

**Integração com Testcontainers:**
- duplicidade em paralelo e depois de rollback em `READ COMMITTED` (`EvaluationDuplicateIT`);
- falha injetada em cada leitura e gravação, sem nada gravado e sem chamada à U7 (`RiskGateFailClosedIT`);
- privilégios e gatilhos por papel nas quatro tabelas (`RiskPrivilegesIT`);
- preparo dos papéis, com a aplicação fora de `risk_retention` (`RolesBootstrapIT`);
- expurgo (`RiskPurgeIT`):
  - linhas gravadas com `evaluated_at` explícito de 29 dias, 30,5 dias e 32 dias: só a de 32 é apagada;
  - aprovada citada por duplicidade nunca é apagada;
  - `DELETE` direto da linha de 30,5 dias pelo papel de retenção passa no gatilho, o que prova a folga entre seleção e gatilho;
  - corte pelo `now()` do banco;
- duas rodadas seguidas do expurgo, e uma rodada com a purga da trilha da U2 em andamento no mesmo login, cada uma com o seu pool (`RiskRetentionPoolIT`);
- guarda do agendador recusa pool menor que 12 (`RuntimeSettingsGuardTest`) e o Compose traz o padrão 12 (`ComposeFilesTest`);
- decisão antiga reavaliada depois do expurgo, contida pela U7 (`PurgedDecisionReplayIT`);
- rota C21 com os cinco casos de 404 e paginação (`RejectionRoutesIT`);
- eventos `ORDER_REJECTED` por caso do catálogo.

**ArchUnit:**
- fronteira só com `pnl.api` e `marketdata.api`;
- sem rede e sem ponto flutuante;
- sem `BigDecimal.ZERO` no lugar de `Unknown`.

## Cenário de carga `u6` (`scripts/load-test.sh` e `scripts/load/load-test.mjs`)

Sem a U5, os ciclos vêm de componentes que só existem com os perfis `test` e `loadtest` (o `LoadtestProfileGuard` já impede `loadtest` sem `test`). Eles seguem o padrão dos componentes `Loadtest*` da U7:
- **`LoadtestRiskCycleDriver`:** abre uma transação por ciclo, liga o contexto do dono e chama `OrderGate.evaluate`, medindo o tempo da chamada. Reaproveita as contas PAPER da U7 com `FUNDING`, criadas pelo `LoadtestLedgerSeed`.
- **`LoadtestRiskSeed`:** grava o volume de preparação em lotes pelos repositórios da U6, no mesmo formato que o `OrderGate` grava. Esse volume não é medido; só os ciclos passam pelo `OrderGate`.
- **`LoadtestRiskPurgeTrigger`:** chama o `RiskEvaluationPurgeJob` diretamente, uma vez, no instante configurado, sem esperar o cron e passando pelo mesmo ShedLock.

### Variáveis novas no `infra/docker-compose.test.yml` (repassadas à `api`)

| Variável | Padrão | Uso |
|---|---|---|
| `LOADTEST_U6_ENABLED` | `false` | Liga os três componentes acima |
| `LOADTEST_U6_PEOPLE` | `20` | Pessoas, cada uma com 3 bots (60 no total) |
| `LOADTEST_U6_REJECTIONS_PER_BOT` | `10000` | Recusas semeadas num bot de cada pessoa |
| `LOADTEST_U6_HEAVY_BOT_REJECTIONS` | `80000` | Recusas semeadas num único bot |
| `LOADTEST_U6_OLD_APPROVED` | `200000` | Avaliações aprovadas semeadas com 32 a 40 dias, para o expurgo |
| `LOADTEST_U6_PURGE_AT` | `300s` | Instante do disparo do expurgo, contado do início dos ciclos |

O `ComposeFilesTest` confere que as variáveis só existem no overlay de teste.

### Volume e memória

| Tabela | Linhas | Estimativa em disco (dados e índices) |
|---|---|---|
| `order_rejection` | 19 × 10.000 + 80.000 = 270.000, mais as dos ciclos (cerca de 600) | cerca de 150 MB |
| `rejection_reason_detail` | cerca de 1,5 por recusa, 405.000 | cerca de 60 MB |
| `order_evaluation` | 270.000 recusadas + 200.000 aprovadas antigas + as dos ciclos | cerca de 110 MB |
| Total da U6 | | cerca de 320 MB |

A U7 passou de 900 MB com 200.000 preenchimentos por causa do ledger, das posições e das consultas de P&L sobre esse volume. O `u6` reaproveita só as 60 contas PAPER, sem preenchimentos semeados (`FILLS_PER_PERSON=0`), e soma cerca de 320 MB em disco.

O limite de memória do PostgreSQL fica no padrão de 900 MB. Se o guarda de memória do `load-test.mjs` reprovar o cenário, ele fica reprovado com o motivo, como no `u7`, e a exceção de limite só entra com decisão do responsável do projeto.

### O cenário

| Item | `u6` |
|---|---|
| Duração | 10 minutos de ciclos, depois da preparação |
| Preparação | `LoadtestLedgerSeed` (60 contas, sem preenchimentos) e `LoadtestRiskSeed` com as variáveis acima |
| Janela sem expurgo | Minutos 0 a 5: uma avaliação por bot a cada 30 s, metade recusada por limites apertados; 20 usuários lendo a C21, uma leitura a cada 2 s, inclusive o bot com 80.000 recusas |
| Janela com expurgo | Minutos 5 a 10: a mesma carga, com o expurgo disparado em `LOADTEST_U6_PURGE_AT`. O tempo de cada lote vem do log da rodada |
| Limites | p95 de `evaluate` ≤ 250 ms em cada janela; p95 da C21 ≤ 1 s; todo lote ≤ 100 ms; p95 de `evaluate` da segunda janela no máximo 10% acima da primeira |
| Plano das consultas | `EXPLAIN` da página da C21, da procura da decisão e da seleção do expurgo; reprova `Seq Scan` em `order_evaluation` ou `order_rejection` |
| Limpeza | O ambiente de teste é recriado do zero a cada execução do `load-test.sh`, como nos outros cenários. As duas janelas são da mesma execução e não precisam de limpeza entre si |
| Relatório | `docs/perf/u6-risk-control-<data>.md`, com uma seção por janela, o tamanho de lote escolhido, o total apagado e o tamanho das tabelas |

### Mudanças no ferramental de carga

- **`scripts/load-test.sh`:** aceita `LOAD_SCENARIO=u6` e exporta `LOADTEST_U6_ENABLED=true` com o perfil `test,loadtest`.
- **`scripts/load/load-test.mjs`:** ganha o ramo `u6`, que:
  - espera a preparação pelo indicador de saúde do perfil `loadtest`;
  - mede as duas janelas separadamente e lê o log da rodada do expurgo;
  - roda os `EXPLAIN`;
  - escreve o relatório.
- **`load-test.yml`:** passa a aceitar `u6` na entrada de cenário, e o `WorkflowsTest` confere.

## Verificações de NFR na CI

| NFR | Onde roda | Como |
|---|---|---|
| NFR4.1 nenhuma ordem acima dos limites | job `backend` | Catálogo E1 a E21 e teste de propriedade |
| NFR4.3 imutabilidade | job `backend` | `RiskPrivilegesIT`, uma linha da matriz por teste |
| NFR4.4 uma avaliação por decisão | job `backend` | `EvaluationDuplicateIT`, 50 repetições em paralelo |
| NFR5.1 isolamento | job `backend` | `RejectionRoutesIT` com duas pessoas e dois tenants |
| NFR10.2 falha fechada | job `backend` | `RiskGateFailClosedIT` |
| NFR10.7 duplicidade depois do expurgo | job `backend` | `PurgedDecisionReplayIT` |
| NFR13.1 portabilidade | job `backend` (Linux) e local (Windows) | Mesma suíte |

## Implantação e rollback

- **Implantação:** só local. O `docker compose up` roda o preparo dos papéis e aplica a `0009`.
- **Migração aditiva:** só acrescenta tabelas, índices, gatilhos e privilégios.
- **Rollback para a U7:** a imagem anterior roda sobre o banco migrado, com as tabelas novas sem uso. O papel `risk_retention` sobra, inofensivo.
- **Teste do rollback:**
  - automático: o `MigrationsIT` aplica até a `0009` e roda as consultas das Units anteriores;
  - manual, no ponto de controle da U6: subir a imagem anterior sobre o volume migrado e rodar `bash scripts/verify.sh` até sair com código 0.

## Segredos na CI

Nenhum segredo novo. O login de retenção já existe.
