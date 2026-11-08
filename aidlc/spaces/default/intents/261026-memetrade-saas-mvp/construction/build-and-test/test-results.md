# Resultados de Build and Test

Conversa: português (Brasil). Quinta execução, de 2026-11-08, depois da volta 3 e da inclusão dos smokes da U3 e da U9 no `scripts/verify.sh`. As anteriores foram em 2026-11-06, duas em 2026-11-07 e uma em 2026-11-08. A matriz final de metas está em `build-and-test-summary.md`, seção `## Target Verification Matrix`: 355 `Met`, 1 `Not Met`, 3 `Unverified`, 18 `N/A`. Todas as metas que ainda não fecham dependem de ação da pessoa (`perf-audit.sh` da U2 e a escala da U4 com a réplica de leitura).

## Build

| Parte | Status | Saída |
|---|---|---|
| `apps/api` (`mvnw verify`) | sucesso | Surefire 909 testes, 0 falhas; Failsafe 763 testes, 0 falhas, 0 ignorados → BUILD SUCCESS (Passo 30 da U6, 2026-11-08, sobre o código final) |
| `apps/api` cobertura | sucesso | JaCoCo mesclado 13339/14204 linhas = 93,91%, "All coverage checks have been met" |
| `apps/web` | sucesso | `tsc`, `eslint`, `prettier` limpos; Jest 33 suítes, 391/391 em 3 execuções seguidas (Passo 32 da U5, 2026-11-08) |
| `apps/admin` | sucesso | `install --frozen-lockfile`, `tsc`, `eslint`, `prettier` limpos; Jest 10 suítes, 85/85 (2026-11-07; código inalterado desde então) |

Nesta execução não houve mudança de código depois dessas medições, só documentos de registro. Por isso os resultados acima, obtidos na volta 3 sobre o código final, valem para esta execução.

## Testes

| Tipo | Total | Passaram | Falharam | Ignorados |
|---|---|---|---|---|
| Unidade do backend (Surefire) | 909 | 909 | 0 | 0 |
| Integração do backend (Failsafe) | 763 | 763 | 0 | 0 |
| Unidade e componente `apps/web` | 391 | 391 | 0 | 0 |
| Unidade e componente `apps/admin` | 85 | 85 | 0 | 0 |
| Ponta a ponta (`scripts/verify.sh`) | 10 | 10 | 0 | 0 (com os smokes da U3 e da U9, 2026-11-08) |
| Script de carga (`node --test scripts/load/*.test.mjs`) | 22 | 22 | 0 | 0 |

- **Suíte do backend:** os comandos por Unit dos `unit-test-instructions.md` são subconjuntos (`-Dgroups=<unit>`) da suíte completa, que rodou uma vez, sem contar teste em dobro.
- **`scripts/verify.sh`:** saída 0 nos pontos de controle da U5 (2026-11-08 01:57–02:00 UTC), do esqueleto (03:52–03:56 UTC) e da U6 (04:55–04:59 UTC), este sobre o código final.

## Cobertura

| Parte | Linhas | Instruções/statements | Funções | Ramos |
|---|---|---|---|---|
| Backend | 93,91% | — | — | — |
| `apps/web` | 96,03% | — | — | — |
| `apps/admin` | 91,34% | 90,52% | 85,71% | 83,6% |

## Carga

| Cenário | Resultado | Principais números |
|---|---|---|
| `u5` | aprovado | carga da volta 2 (2026-11-07), sobre o código final da U5: semente 400 000 em 285 s; ciclo p95 112 ms; barreira mais execução p95 22 ms; 5 de 5 fatias completas; 0 `STALE_DATA`; BUY 108 e SELL 108; tela S5 27 ms; canal ao vivo 1,9 s |
| `u6` (3 cargas, volta 3) | **aprovado nas 3** | lote 1 com 41, 51 e 39 ms; maior lote 58, 57 e 57 ms (limite 100 ms); aquecimento com `DELETE` desfeito de 68, 74 e 82 ms, fora do tempo dos lotes; p95 da avaliação (amostras, microssegundos) sem / com expurgo 21,30/16,90, 16,64/16,61 e 21,93/16,09 ms; planos sem `Seq Scan`; 0 falhas |

- A carga 3 da `u6` foi repetida sozinha em 2026-11-08, depois que a primeira tentativa foi interrompida pelo sistema por falta de memória no computador.
- As mudanças da volta 3 na U5 foram só num teste do frontend, e as da U6 só no expurgo, no perfil `loadtest` da U6 e no ramo `u6` do script. Nenhuma toca o que o cenário `u5` mede.
- Os demais cenários (`u1`, `u3`, `u4`, `growth`, `u7`, `u7-jobs`) não foram repetidos. As voltas não mudaram código que eles medem, e os resultados de 2026-11-06 continuam valendo (`performance-test-instructions.md`).

## Detalhe das falhas

### F11 e F12 (volta 3): corrigidos

- **F11 (U5):** o teste do assistente S4 passou a usar `userEvent.setup({ delay: null })` e um tempo próprio de 15 s no `it`, sem tirar asserção (54 `expect` antes e depois). A suíte completa do `apps/web` passou 3 vezes seguidas, 391/391.
- **F12 e F10 (U6):** o p95 da avaliação passou a sair das amostras de cada chamada, e o aquecimento executa o `DELETE` do lote 1 e o desfaz. As 3 cargas aprovaram, com o lote 1 bem abaixo de 100 ms. Limites de 10%, 250 ms e 100 ms inalterados.
- **Achados Minor da revisão 07 da U6 (abertos):** commit possível do `DELETE` desfeito se o próprio `rollback` falhar com a conexão viva; regra de +10% sem mínimo de amostras e com aquecimento da JVM na janela de base; teto de 200 000 amostras sem aviso.

### F7 a F10 (volta 2): situação

- **F7, F8 e F9 (U5): corrigidos.** A carga `u5` da volta 2 aprovou: 5 de 5 fatias completas, 0 `STALE_DATA`, barreira mais execução p95 22 ms com 216 ordens.
- **F10 (U6): corrigido na volta 3** (ver acima).

### F6. Não executados (continua da primeira execução)

- `scripts/perf-audit.sh` (U2 NFR3.1, 3.3 e 4.2): a proteção do ambiente recusa a execução pelo agente; a pessoa roda localmente.
- Smokes `e2e/u3-identity-access` e `e2e/u9-dashboard`: fora de `scripts/verify.sh`, que é protegido e só a pessoa altera.

### Observações

- **`DashboardOpenLatencyIT` (U9 NFR3.1):** passou nas suítes completas da volta 3. Na volta 2 da U6, reprovou uma vez numa suíte completa (2,03 s; sozinho, 2,16 s) e passou na seguinte (1,81 s). Rodando sozinho, já ficou entre 2,02 e 2,16 s. A decisão Q1 = B (medição informativa) não foi aplicada, porque a pessoa aprovou o plano da U9 sem esse passo. O teste continua sujeito a instabilidade, e teste instável é defeito (`team.md`).
- **Dependências do frontend:** `pnpm audit --audit-level high` acusa 2 avisos High no `postcss` que vem pelo `next` (GHSA-6g55-p6wh-862q e GHSA-r28c-9q8g-f849; correção no `postcss` 8.5.18 ou superior), em `apps/web` e `apps/admin`. O portão é do CI Pipeline (U1-NFR6.8).

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

### Loop-back 2 — 2026-11-07T15:47:00Z

- **Resultado da volta 1:**
  - **Corrigidos:** F1 (U2, verde na suíte), F3 (semente da U5 com 400 000 decisões sem erro), F4 (planos da U6 sem `Seq Scan`) e T1–T3 (o leitor aceita todas as Units).
  - **Não corrigido:** F2 (U9). A pessoa aprovou o plano da U9 sem o passo da medição informativa. O teste passou nesta execução, e o assunto fica como observação.
- **Diagnosis:**
  - **F7 (U5):** o limite de fatias do script de carga não desconta a fatia em andamento no início da janela.
  - **F8 e F9 (U5):** sob o cenário `FREQUENT_SIGNALS`, 200 decisões saíram `STALE_DATA` e nenhuma ordem chegou à barreira, então o NFR2.3 da U5 e o NFR2.2 da U6 ficaram sem medida.
  - **F10 (U6):** o primeiro lote do expurgo passou do limite logo depois da semente, provavelmente por estatísticas e cache frios.
  - O rung 1 não se aplica: as causas estão no script de carga e nos perfis `loadtest` gerados na geração de código, não na estrutura deste estágio.
- **Root-cause stage:** code-generation das Units `u5-bots-decision` e `u6-risk-control`. As demais Units mantêm o trabalho e as aprovações. Se a investigação da F8 mostrar que a causa está na fonte simulada, a U4 entra numa volta seguinte.
- **Planned fix:**
  - **U5, F7:** o limite de fatias completas passa a contar só as fatias que cabem inteiras na janela, ou seja, as que começam depois do início da carga e terminam até `fim da carga - 60 s`. Ganha um teste `node --test` com os horários de borda. A regra "toda fatia da janela completa" não muda.
  - **U5, F8 e F9:**
    - investigar, com a base da carga ainda de pé, a idade do último bucket e do preço de referência dos tokens selecionados no momento das decisões `STALE_DATA`;
    - corrigir a causa no perfil `loadtest` (cenário de sinais frequentes, seleção dos tokens ou cadência da coleta simulada), para que os bots recebam dados frescos e gerem ordens;
    - o script passa a reprovar com mensagem clara ("nenhuma ordem chegou à barreira") quando o timer da barreira ficar vazio;
    - os limites de 0 decisões `STALE_DATA` e 500 ms não mudam;
    - depois, rodar de novo o cenário `u5`.
  - **U6, F10:**
    - `LoadtestRiskSeed` passa a rodar `ANALYZE` nas tabelas semeadas antes de liberar o expurgo, como a U2 fez na volta 1;
    - confirmar se a seleção do expurgo de produção (`RiskEvaluationPurgeJob`) tem o mesmo custo no primeiro lote; se tiver, a correção vai para o expurgo e não só para a semente;
    - o limite de 100 ms por lote não muda;
    - depois, rodar de novo o cenário `u6`.
- **Estimated impact:**
  - **Esforço:**
    - U5 F7: cerca de 30 min, 1 função do script e 1 teste;
    - U5 F8 e F9: cerca de 2–4 h de investigação e correção no perfil `loadtest`, mais cerca de 40 min de nova execução;
    - U6 F10: cerca de 30 min a 1 h, 1 classe do perfil `loadtest` e talvez a seleção do expurgo, mais cerca de 15 min de nova execução.
  - **Custo financeiro:** nenhum (tudo local).
  - **Risco:**
    - baixo para F7 e F10, que ficam no script e no perfil `loadtest`;
    - médio para F8 e F9, que podem levar à fonte simulada da U4.
    - Nenhuma meta é reduzida.
  - **Aprovação:** cada Unit reaberta exige nova aprovação do plano pela pessoa antes da geração.
- **Fora desta volta (precisam da pessoa):**
  - os mesmos da volta 1 (smokes U3 e U9 em `scripts/verify.sh`, `perf-audit.sh`, decisão de escala da U4);
  - os 2 avisos High do `postcss` no frontend (CI Pipeline, U1-NFR6.8);
  - a decisão Q1 = B da U9, se a pessoa ainda quiser a medição informativa.

### Loop-back 3 — 2026-11-08T00:55:00Z

- **Resultado da volta 2:**
  - **Corrigidos:** F7, F8 e F9 (U5; carga `u5` aprovada); alvos únicos do NFR2.5 e NFR2.6 da U6; critério de estatísticas da semente da U6.
  - **Cumprido no limite:** F10 (U6), com o lote 1 em 100, 95 e 94 ms nas 3 cargas.
- **Diagnosis:**
  - **F11 (U5):** o teste do assistente S4 em `apps/web/src/features/bots/BotScreens.test.tsx` estoura o tempo padrão de 5 s do Jest na suíte completa (3 de 3) e passa sozinho. São cerca de 30 interações de `userEvent` com digitação tecla a tecla.
  - **F12 (U6):** a regra de no máximo 10% acima no p95 da avaliação com expurgo compara valores lidos das faixas do histograma (22,4 e 33,6 ms). A troca de uma faixa reprovou 1 de 3 cargas.
  - **F10 (U6), folga:** o aquecimento por leitura não executa o `DELETE` (gatilho e chave estrangeira continuam frios no lote 1); a duração do lote é truncada em milissegundos; o Javadoc de `warmUp()` afirma o que a medição desmente.
  - O rung 1 não se aplica: as causas estão em teste, no script de carga e no expurgo gerados na geração de código, não na estrutura deste estágio.
- **Root-cause stage:** code-generation das Units `u5-bots-decision` (só o teste do frontend) e `u6-risk-control`. As demais Units mantêm o trabalho e as aprovações.
- **Planned fix:**
  - **U5, F11:**
    - o teste do assistente S4 passa a rodar sem atraso artificial de digitação (`userEvent.setup({ delay: null })`) e ganha um tempo próprio compatível com o fluxo de 4 etapas;
    - nenhuma asserção é retirada;
    - prova: a suíte completa do `apps/web` verde 3 vezes seguidas.
  - **U6, F12:**
    - medir o p95 da avaliação com resolução melhor que a faixa do histograma (por exemplo, faixas mais finas só no perfil `loadtest` ou percentil calculado das amostras), mantendo a regra de 10% e o limite de 250 ms;
    - a pessoa decide no plano entre medir com mais precisão ou aceitar o risco; o limite não é relaxado.
  - **U6, F10:**
    - registrar a duração do lote com arredondamento para cima (ou em microssegundos), para que um lote de 100,9 ms reprove;
    - aquecer também o gatilho e a chave estrangeira antes do lote 1, por exemplo com um `DELETE` real do primeiro lote desfeito com `rollback`, fora do tempo medido e sem apagar nada; a pessoa escolhe a opção no plano;
    - corrigir o Javadoc de `warmUp()`;
    - o limite de 100 ms por lote não muda.
  - **Prova da U6:** `verify` e 3 cargas `u6` seguidas, todas aprovadas.
- **Estimated impact:**
  - **Esforço:**
    - U5 F11: cerca de 30 min, 1 teste, mais 3 execuções da suíte do `apps/web` (cerca de 4 min);
    - U6 F12 e F10: cerca de 2–3 h, no expurgo, no perfil `loadtest` e no script de carga, mais 3 cargas `u6` (cerca de 1 h) e o `verify`.
  - **Custo financeiro:** nenhum (tudo local).
  - **Risco:**
    - baixo para F11 e F12, que ficam em teste, perfil `loadtest` e script;
    - médio para o aquecimento com `DELETE` desfeito, que muda o expurgo de produção: segura travas de linha por alguns milissegundos e precisa de teste que prove que nada é apagado.
    - Nenhuma meta é reduzida.
  - **Aprovação:** cada Unit reaberta exige nova aprovação do plano pela pessoa antes da geração.
  - **Limite:** esta é a 3ª e última volta automática. Uma nova falha leva à pergunta para a pessoa.
- **Fora desta volta (precisam da pessoa):**
  - smokes U3 e U9 em `scripts/verify.sh`, `perf-audit.sh` e decisão de escala da U4;
  - os 2 avisos High do `postcss` no frontend (CI Pipeline, U1-NFR6.8);
  - `DashboardOpenLatencyIT` da U9, que reprovou uma vez na volta 2 (decisão Q1 = B ainda não aplicada).

## Falha aceita pela pessoa (2026-11-08)

Depois da quarta execução, com as 3 voltas automáticas usadas, a pessoa escolheu **Accept failure**. As 8 metas abertas ficam como pendências dela e seguem para a aprovação deste estágio:
- TP-E2E-U3, TP-E2E-U9, U3-NFR3.4 e U3-NFR9.1: incluir os smokes `e2e/u3-identity-access` (com `SMOKE_INVITE_CODE`) e `e2e/u9-dashboard` em `scripts/verify.sh`, que só a pessoa altera. **Resolvido em 2026-11-08:** a pessoa fez a mudança (com `tr -d '\r\n'` na captura do convite), a U1 foi reaberta só para registrá-la, e o `verify.sh` passou com 10/10 nos pontos de controle do esqueleto e da U3 (quinta execução: as 4 metas ficam `Met`);
- U2-NFR3.1, U2-NFR3.3 e U2-NFR4.2: rodar `bash scripts/perf-audit.sh` localmente. Em 2026-11-08 a pessoa rodou o script, mas o Claude Code o interrompeu por falta de memória no computador durante a semente (7 de 37 milhões de eventos), sem resultado. Na quinta execução, a pessoa escolheu seguir para a aprovação com essas 3 metas pendentes;
- U4-NFR3.5 (`Not Met`): decisão de escala da U4 antes do G1. Em 2026-11-08 a pessoa decidiu pela réplica de leitura junto com a hospedagem na nuvem; a meta de 1 s não muda e volta a ser medida com a réplica antes do G1.
