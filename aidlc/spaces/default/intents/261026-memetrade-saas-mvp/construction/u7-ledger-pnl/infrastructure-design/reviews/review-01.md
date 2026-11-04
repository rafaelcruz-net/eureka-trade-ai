## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-04T14:18:14Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/infrastructure-design/infrastructure-specification.md > Configuração da API (`SCHEDULER_POOL_SIZE`) e Infrastructure Services (ShedLock, Agendador) | O pool do agendador sobe de 7 para 10 "para três tarefas da U7", mas a própria tabela de ShedLock lista quatro bloqueios, e o quarto (`pnl-reconciliation-run-limiter-eviction`) é o `ReconciliationRunLimiterEvictionJob` do security-design.md, um trabalho agendado próprio. A conta do `RuntimeSettingsGuard` atual é 3 (U1/U2) + 4 (U4) = 7, logo a U7 precisa de 7 + 4 = 11. Com 10 linhas, uma tarefa fica sem thread quando as outras estão ocupadas, e isso pesa justamente no limite de 10 s por rodada do NFR3.5 medido em `u7-jobs`. | Recontar as tarefas agendadas da U7 (marcação, fechamento pendente, reconciliação e limpeza do limitador) e fixar o novo mínimo e o padrão em 11, ou justificar por escrito por que a limpeza não ocupa uma linha própria. Atualizar o mínimo do `RuntimeSettingsGuard`, o padrão do `application.yml` e do `docker-compose.yml`, e o `ComposeFilesTest`. | New |
| R-02 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/infrastructure-design/cicd-pipeline.md > Cenários de carga (`LoadtestCycleDriver`) | O gerador de ciclos "existe só com os perfis `test` e `loadtest`". O overlay `infra/docker-compose.test.yml` ativa `SPRING_PROFILES_ACTIVE=test` por padrão (smoke diário e `verify.sh`), então a leitura literal liga o gerador, que cria 60 contas PAPER com `FUNDING` e executa ordens sintéticas, também no smoke. O `LoadtestProfileGuard` só impede `loadtest` sem `test`, não o contrário. Também não está dito se esse código, e o semeador de 200 000 preenchimentos, fica no jar de produção (fora do perfil) ou em outro módulo. | Dizer que o gerador e o semeador só são ativados quando `loadtest` está ativo (e, por consequência do guarda, `test`), nunca só com `test`. Acrescentar um teste de que, sem `loadtest`, os dois beans não existem, e declarar onde o código fica no repositório. | New |
| R-03 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/infrastructure-design/cicd-pipeline.md > Estágios e portões, linha Smoke | A tabela marca o smoke Playwright da U7 como portão "Falha bloqueia", mas o `scripts/verify.sh` (linha 116) só roda `e2e/u1-walking-skeleton e2e/u2-audit-trail e2e/u4-discovery`, e o job `smoke` do `ci.yml` só executa `verify.sh`. Até a ação manual do responsável do projeto, nada da U7 bloqueia o merge nesse estágio. Além disso, o texto diz que "os convites" continuam passando, mas nenhum e2e da U3 aparece nessa linha. | Reescrever a linha para dizer que o portão da U7 no smoke só passa a valer depois da edição do `verify.sh`, e que a conclusão da U7 (ponto de controle) depende dessa edição, com a verificação manual do `verify.sh` como evidência até lá. Corrigir ou remover a menção aos convites. | New |
| R-04 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/infrastructure-design/cicd-pipeline.md > Implantação e rollback | "O pool do agendador volta a 7 pelo padrão da imagem anterior" não vale: o `docker-compose.yml` passa `SCHEDULER_POOL_SIZE: ${SCHEDULER_POOL_SIZE:-7}` à `api` e, depois desta Unit, o padrão do Compose será o novo. A imagem anterior sobre o Compose novo ficaria com o valor novo. O guarda antigo (7 a 32) aceita o valor, então o rollback funciona, mas a descrição está errada. | Corrigir a frase: o rollback usa o Compose da versão anterior ou o valor novo, que a imagem anterior aceita. Conferir isso no teste manual de rollback. | New |
| R-05 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/infrastructure-design/traceability.json > NFR3.4 (`Deferred`) | O NFR3.4 (tempo da tela de P&L) foi adiado para o smoke da U5 (Q1, opção B) sem registrar na U5 uma obrigação de medi-lo, e o nfr-design marcava o NFR3.4 como atendido. A decisão é da pessoa, mas o adiamento fica sem dono. | Registrar o NFR3.4 como pendência de entrada da U5 (por exemplo no ponto de controle da U7 ou no plano da U5) para ele não se perder. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Nenhuma ferramenta declarada | `traceability.json` é JSON válido; os 29 `upstream_ids` são `NFRx.y` do `nfr-design/traceability.json` da U7 | Sem referência quebrada. Verificado também que a migração 0008 não colide (existem 0001 a 0007) e que `load-test.yml`, `load-test.sh` e `RuntimeSettingsGuard` ainda estão no estado anterior à U7, como a mudança planejada pressupõe |

### Summary

Sem falha crítica: o desenho acrescenta só a migração 0008, usa o Compose e os workflows existentes, e cada NFR atribuído à infraestrutura tem um alvo verificável. Os pontos a decidir antes da aprovação são o tamanho do pool do agendador (quatro tarefas, não três), a ativação do gerador de ciclos de carga só com `loadtest`, e o fato de o portão de smoke da U7 só valer depois da edição manual do `verify.sh`.
