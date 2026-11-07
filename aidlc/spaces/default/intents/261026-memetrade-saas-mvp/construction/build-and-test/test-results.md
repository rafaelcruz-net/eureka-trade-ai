# Resultados de Build and Test

Conversa: português (Brasil). Execução de 2026-11-06. A matriz final de metas está em `build-and-test-summary.md`, seção `## Target Verification Matrix`: 340 `Met`, 4 `Not Met`, 14 `Unverified`, 18 `N/A`.

## Build

| Parte | Status | Saída |
|---|---|---|
| `apps/api` (`mvnw verify`) | **falha** | Surefire 890 testes, 0 falhas; Failsafe 748 testes, 2 falhas, 0 ignorados → BUILD FAILURE |
| `apps/api` cobertura | sucesso | JaCoCo mesclado 12331/13139 linhas = 93,85%, `check` com saída 0 |
| `apps/web` | sucesso | `tsc`, `eslint`, `prettier` limpos; Jest 33 suítes, 391/391 |
| `apps/admin` | sucesso | `tsc`, `eslint`, `prettier` limpos; Jest 10 suítes, 85/85 |

## Testes

| Tipo | Total | Passaram | Falharam | Ignorados |
|---|---|---|---|---|
| Unidade do backend (Surefire) | 890 | 890 | 0 | 0 |
| Integração do backend (Failsafe) | 748 | 746 | 2 | 0 |
| Unidade e componente `apps/web` | 391 | 391 | 0 | 0 |
| Unidade e componente `apps/admin` | 85 | 85 | 0 | 0 |
| Ponta a ponta (`scripts/verify.sh`) | 8 | 8 | 0 | 0 (U3 e U9 fora do comando) |

Os comandos por Unit dos `unit-test-instructions.md` são subconjuntos (`-Dgroups=<unit>`) da suíte completa acima. Por isso a suíte rodou uma vez, sem contar teste em dobro.

## Cobertura

| Parte | Linhas | Instruções/statements | Funções | Ramos |
|---|---|---|---|---|
| Backend | 93,85% | — | — | — |
| `apps/web` | 96,03% | 95,65% | 94,27% | 87,41% |
| `apps/admin` | 91,34% | 90,52% | 85,71% | 83,6% |

## Detalhe das falhas

### F1. `JdbcAuditEventReaderIT.bothPreparedFormsReadFromTheIndexWithoutASeparateSort` (U2)

- **Asserção:** o plano deveria conter `audit_event_action_time_id_idx`; o planner escolheu `audit_event_time_id_idx` com `Filter`.
- **Rodando sozinho:** passa (6 de 6, conforme o resumo da U9).
- **Causa:** o teste lê o plano sobre o banco compartilhado do Failsafe. O volume semeado por outras classes muda as estatísticas do planner. Isso viola a regra "não dependem de estado compartilhado nem da ordem de execução".

### F2. `DashboardOpenLatencyIT` (U9 NFR3.1)

- **Asserção:** p95 ≤ 2 s por quadro; o grupo BOTS mediu 2,357 s.
- **Histórico:** isolado, ficou entre 2,02 e 2,08 s; na suíte completa já tinha passado com 1,92 s.
- **Decisão da pessoa:** Q1 = B, medição informativa fora do portão de CI. O teste ainda reprova, e o requisito ainda diz portão.

### F3. Cenário de carga `u5`: semente de decisões

- **Erro:** `Error: the load bot seed failed: DataIntegrityViolationException: PreparedStatementCallback; SQL [insert into decision (decision_id, bot_id, cycle_id, ...` (a saída corta a mensagem).
- **Causa provável**, pela leitura do código:
  - `LoadtestBotSeed.seedDecisions` grava um ciclo por minuto passado a partir de `now - 1 min`;
  - quando o worker real já rodou ciclos desses bots ACTIVE nesses minutos, `JdbcCycleRepository.tryStart` não insere (`on conflict (bot_id, slot_start) do nothing`), e a semente ignora o retorno `false`;
  - a decisão é gravada com um `cycle_id` que não existe, e `decision.cycle_id REFERENCES bot_cycle` falha.
- **Metas afetadas:** U5 NFR2.1, 2.2, 2.3, 2.5, 2.6, 3.1 e 3.2 ficam `Unverified`.

### F4. Cenário de carga `u6`: conferência de plano

- **Falha:** `query plan` (`page`, `decision`). Todas as medidas passaram:
  - avaliação: p95 22 ms;
  - C21: p95 13 ms;
  - expurgo: maior lote 90 ms;
  - pool: 0 pendentes;
  - memória: api 347 MB, postgres 582 MB.
- **Causa:** em `scripts/load/load-test.mjs`, `u6Plans()` coloca a subconsulta de amostra `(select ... from order_rejection r limit 1)` dentro do `EXPLAIN`. Essa subconsulta gera um `Seq Scan on order_rejection` por si só, e a expressão regular o conta como falha. Por isso o plano das consultas reais não foi conferido (U6 NFR2.6 `Unverified`).

### F5. Cenário de carga `growth` (U4 NFR3.5)

- **Resultado:** a lista teve p95 de 10,02 s com 10 000 tokens (meta 1 s), e o postgres usou 990 MB. O detalhe ficou em 39 ms.
- **Como o cenário é tratado:** não tem limite de reprovação, e o resultado é anotado.
- **O que falta:** pelo NFR3.5, a decisão de escala precisa ser aberta antes do G1. Essa decisão é da pessoa, não é defeito de código.

### F6. Não executados

- `scripts/perf-audit.sh` (U2 NFR3.1, 3.3, 4.2 e 9.5): a proteção do ambiente recusa a execução pelo agente, porque o script grava num caminho com "audit".
- Smokes `e2e/u3-identity-access` e `e2e/u9-dashboard`: estão fora de `scripts/verify.sh`, que é protegido e só a pessoa altera.

### Observação: cenário `u3`

A primeira execução não subiu a API ("dependency api failed to start"). A segunda passou. Causa provável: a chave aleatória em base64 começando com `/` é convertida pelo MSYS no Git Bash, porque `scripts/load-test.sh` não define `MSYS2_ENV_CONV_EXCL`. É o achado R-06 da U1, ainda em aberto.

## Loop-Back Log

### Loop-back 1 — 2026-11-06T20:30:00Z

- **Diagnosis:**
  - F1 (U2): teste de integração dependente do estado compartilhado;
  - F2 (U9): o teste de latência continua como portão, contra a decisão Q1 = B da pessoa;
  - F3 (U5): a semente de carga ignora o `tryStart` recusado e grava decisão sem ciclo;
  - F4 (U6): o script de carga conta como falha o `Seq Scan` da sua própria subconsulta de amostra.
  - T1–T3 de `cross-unit-traceability.md`: os registros de rastreabilidade da geração de código da U8 e da U9 estão num formato que o leitor recusa; o da U4 não tem as linhas de NFR; o da U2 tem alvos com texto livre e 4 IDs ausentes.
  - O rung 1 não se aplica: todas essas causas estão em código, testes ou registros gerados na geração de código, e não na estrutura deste estágio.
- **Root-cause stage:** code-generation das Units `u2-audit-trail`, `u4-discovery`, `u5-bots-decision`, `u6-risk-control`, `u8-admin-feedback` e `u9-dashboard`. As demais Units (U1, U3, U7) mantêm o trabalho e as aprovações.
- **Planned fix:**
  - **U2:** `JdbcAuditEventReaderIT` passa a rodar num banco próprio, preparado e migrado como o `AbstractU9MeasurementIT`, com `ANALYZE` depois de semear. Assim o plano não depende do que outras classes gravaram. A asserção do índice não muda.
  - **U5:** `LoadtestBotSeed.seedDecisions` passa a semear só minutos anteriores ao ciclo mais antigo de cada bot e a falhar com mensagem clara se `tryStart` devolver `false`. Ganha um teste de regressão que reproduz o conflito com um ciclo já existente. Depois, rodar de novo o cenário `u5`.
  - **U6:** `u6Plans()` lê a amostra (tenant, pessoa, bot) numa consulta separada e põe os valores literais nas três consultas do `EXPLAIN`. Assim só o plano das consultas reais é conferido. A regra "nenhum `Seq Scan` em `order_evaluation`/`order_rejection`" não muda. Depois, rodar de novo o cenário `u6`.
  - **U9:** `DashboardOpenLatencyIT` passa a registrar o p95 de cada quadro no log e no relatório, sem reprovar, conforme a decisão Q1 = B da pessoa. O NFR3.1 em `u9-dashboard/nfr-requirements/performance-requirements.md` passa a citar essa decisão, e a meta de 2 s continua escrita como referência medida. A mudança vem da decisão registrada da pessoa, não de um relaxamento feito pelo agente.
  - **Rastreabilidade (U2, U4, U8, U9):**
    - reescrever `traceability.json` da U8 e da U9 no formato `stage`/`unit`/`upstream_ids`/`coverage`, sem mudar o conteúdo;
    - acrescentar à U4 as linhas de NFR que já estão no `nfr-design/traceability.json`;
    - na U2, deixar só caminhos em `target` e acrescentar NFR2.3, 3.2, 6.8 e 10.1.
    - Nenhum código muda nesse item.
- **Estimated impact:**
  - **Esforço:**
    - U2: cerca de 1 h, 1 classe de teste;
    - U5: cerca de 1–2 h, 1 classe do perfil `loadtest` e 1 teste, mais cerca de 40 min de nova execução do cenário;
    - U6: cerca de 30 min, 1 função do script, mais cerca de 15 min de nova execução;
    - U9: cerca de 30 min, 1 teste e 1 requisito;
    - rastreabilidade: cerca de 1 h, 4 arquivos de registro, sem código.
  - **Custo financeiro:** nenhum (tudo local).
  - **Risco:** baixo. Nenhum código de produção muda: as quatro mudanças ficam em teste, perfil `loadtest` ou script de carga.
  - **Aprovação:** cada Unit reaberta exige nova aprovação do plano pela pessoa antes da geração.
- **Fora desta volta (precisam da pessoa):**
  - incluir os smokes U3 e U9 em `scripts/verify.sh`;
  - rodar `scripts/perf-audit.sh` localmente;
  - abrir a decisão de escala da U4 (NFR3.5) antes do G1.
