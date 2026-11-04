## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-04T19:07:27Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/code-generation/traceability.json > coverage NFR3.6 | A rastreabilidade marca NFR3.6 (rodada de reconciliação para 20 pessoas com 10 000 preenchimentos em até 60 s; "verificar agora" em até 5 s) como `OK`, apontando para `ReconciliationIT`. Esse teste não gera volume (12 testes pequenos, sem `generate_series`, sem laço de 10 000 preenchimentos, sem limite de tempo). O relatório de carga também não registra a duração de nenhuma rodada de reconciliação (só marcação e fechamento pendente), e `ReconciliationJob` não loga `durationMs`. A consulta de órfãos usa `o.order_id::text = e.reference_id` e a reconciliação lê todo o histórico da pessoa a cada 5 minutos. O NFR3.6 não está medido em lugar nenhum. | Acrescentar o teste de integração com volume do NFR2.1 (20 pessoas x 10 000 preenchimentos) afirmando 60 s por rodada e 5 s por "verificar agora", ou medir a duração da rodada no cenário `u7` (log com `durationMs`) e registrá-la em `docs/perf`. Enquanto isso, trocar o status do NFR3.6 para `Deferred` com a razão. | New |
| R-02 | Major | docs/perf/u7-ledger-pnl-2026-11-04.md > Cenário `u7` (Falhas: memory postgres) e .github/workflows/load-test.yml > cenário u7 | O cenário `u7` termina reprovado (código 1): PostgreSQL em 902 MB contra a guarda de 900 MB do roteiro. Qualquer execução do workflow com `u7` fica vermelha, e a causa (cache de páginas no `docker stats` ou consumo real) não foi separada. A evidência de carga da Unit, portanto, não tem um cenário verde para o volume completo. | Decidir e aplicar uma das opções: limitar a memória/`shared_buffers` do contêiner de teste e repetir o `u7` até ficar verde; ou medir o uso sem cache de páginas e ajustar a guarda de forma justificada e registrada; ou registrar exceção formal do responsável do projeto com validade. Não deixar o cenário vermelho sem decisão registrada. | New |
| R-03 | Major | scripts/verify.sh (linha do Playwright) e apps/web/e2e/u7-ledger-pnl/smoke.spec.ts | O smoke Playwright da U7 nunca foi executado e `scripts/verify.sh` (o comando de verificação autorizado) não o inclui; a regra da equipe é rodar o smoke de ponta a ponta em toda mudança. A rota `/pnl`, o link do menu e os estados vazios só têm cobertura de Jest com mocks; nada exercita navegador, API e banco juntos para esta Unit. O resumo reconhece a pendência, mas ela é uma alteração de uma linha no script e fecha o único teste integrado da Unit. | Acrescentar `e2e/u7-ledger-pnl` à linha do Playwright em `scripts/verify.sh`, rodar `bash scripts/verify.sh` até passar e registrar o resultado no code-summary.md antes do ponto de controle. | New |
| R-04 | Minor | docs/perf/u7-ledger-pnl-2026-11-04.md > NFR3.1; traceability.json > NFR3.1 | `closePositions` (laço sobre posições, preenchimento, resumo diário e evento do maior patrimônio por posição) não foi medido sob carga; o NFR3.1 permanece `Deferred` embora `execute` esteja em 39 ms contra 250 ms. | Fazer o gerador de ciclos encerrar alguns bots no `u7-jobs` e registrar o p95 de `closePositions`; só então marcar o NFR3.1 como `OK`. | New |
| R-05 | Minor | apps/api/src/main/java/com/memetrade/ledger/infra/JdbcLedgerRepository.java > pendingCloseOrder | A ordem CLOSE pendente é localizada por `idempotency_key like '%:<positionId>'` sobre uma chave que contém texto fornecido pelo chamador; a chave derivada também soma 37 caracteres a uma chave que pode ter até 200 (coluna `varchar(200)`). | Limitar o tamanho da chave de `closePositions` a 163 caracteres, ou guardar o `position_id` da ordem CLOSE em coluna própria e consultar por igualdade. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/code-generation/code-summary.md > Desvios do plano (BR2.3) | O ajuste aceito da BR2.3 vive só no code-summary.md; `rules.md` e o catálogo C1 a C15 continuam com a fórmula antiga, e a U5, a U6 e os testes futuros leem `rules.md`. | Acrescentar a `rules.md` uma nota de uma linha na BR2.3 apontando para o ajuste vigente (sem reescrever o desenho). | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u7 test-compile surefire:test failsafe:integration-test failsafe:verify` | BUILD SUCCESS; 114 testes de integração, 0 falhas (execução em 2026-11-04) | Domínio, repositório, gatilhos, privilégios, concorrência, rotas e reconciliação passam. |
| `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/pnl src/app/pnl` | 3 suítes, 31 testes passando | Tela de P&L, ledger e período verdes. |
| `bash scripts/verify.sh` | Saída 0: 5 testes Playwright passaram (U1, U2, U4), "walking skeleton verified". Não inclui o smoke da U7. | O esqueleto continua verde, mas o smoke da U7 segue sem execução (terceiro finding Major). |
| Revisão do código (BigDecimal, gatilhos, privilégios, perfil `loadtest`) | Sem `double`/`float`, sem `catch` vazio; `UPDATE`/`DELETE`/`TRUNCATE` recusados por gatilho e privilégio; tenant e pessoa filtrados em todas as consultas; `loadtest` exige o perfil `test` e tem teste de arquitetura | Nenhuma violação das regras do projeto encontrada. |

### Summary

O núcleo financeiro (cálculo, ledger imutável, idempotência, tenant, PAPER/REAL) está sólido e os testes da Unit passam, mas a evidência de não funcionais e de integração tem lacunas: NFR3.6 marcado `OK` sem teste nem medição, cenário de carga `u7` vermelho e smoke da U7 nunca executado nem ligado ao `verify.sh`. São três Major, o que torna o resultado NOT-READY até serem resolvidos.
