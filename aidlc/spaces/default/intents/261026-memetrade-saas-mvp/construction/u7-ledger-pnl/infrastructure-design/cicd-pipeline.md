# Pipeline de CI — U7 Ledger e P&L

A U7 usa os workflows existentes (`.github/workflows/ci.yml`, `security.yml`, `load-test.yml`) sem criar workflow novo. Ações de terceiros fixadas por SHA, `permissions` mínimas e lockfiles versionados. Integração por squash-merge em `main`, sem merge com CI vermelha. Os IDs `NFRx.y` são os da U7.

## Estágios e portões

| Estágio | Job | O que a U7 acrescenta | Portão |
|---|---|---|---|
| Lint, formato e tipos | `backend` (Spotless), `frontend` (ESLint, Prettier, `tsc`) | Módulos `ledger` e `pnl`; telas em `src/features/pnl`; regra de lint que proíbe converter `DecimalString` para `number` nas telas da U7 | Falha bloqueia o merge |
| Testes do backend | `backend` (`mvnw verify`) | Marcador `u7`. Escritos antes do código (TDD, núcleo financeiro): catálogo C1 a C15 (`LedgerScenarioCatalogTest`), cálculo e contabilidade da posição, propriedade com semente fixa (`LedgerPropertyTest`), reprodução do `Fill` (`FillReplayTest`). Integração com Testcontainers: privilégios e gatilhos com o papel da aplicação (`LedgerPrivilegesIT`), atomicidade, idempotência em paralelo, ordem das travas (inclusive fechamento pendente com `pnl_daily`), tempo de espera de trava, pico concorrente, pontas do período com compensação, reconciliação com cada tipo de divergência, fechamento pendente com reinício, isolamento entre pessoas e tenants (`LedgerRoutesIT`), só PAPER. ArchUnit: fronteira `pnl`→`ledger.api`, sem rede, sem ponto flutuante, compensação só no domínio | Falha ou cobertura < 80% bloqueia |
| Testes do frontend | `frontend` (Jest com 80%) | Telas de P&L, ledger, operações e reconciliação: `unknown` e "parcial", "sem liquidez", estados vazio, carregando e erro, aviso de divergência e "Reconciliado", rótulo "MODO PAPER (simulado)", decimais como texto, catálogo de termos proibidos | Falha ou cobertura < 80% bloqueia |
| Contrato | `contract` | OpenAPI do convidado com as rotas C5 e os acréscimos (inclusive `SUMMARY_MISMATCH`, `FUNDING`, `POST /reconciliation/run`); tipos do frontend regenerados; teste que proíbe verbos de escrita sob `/ledger`, `/pnl`, `/trades` | Diferença entre gerado e versionado bloqueia |
| Smoke | `smoke` (`bash scripts/verify.sh`) | O esqueleto, a trilha, a descoberta e os convites continuam passando com a `0008` e o pool do agendador em 10. Um smoke Playwright da U7 (`e2e/u7-ledger-pnl`) entra com a conta de smoke da U1 e confere os estados vazios: P&L com "Nenhuma operação neste período", ledger vazio, "Reconciliado" e o rótulo "MODO PAPER (simulado)" (Q1, opção B). O fluxo com operações entra no smoke da U5. Ele entra no `verify.sh` por ação do responsável do projeto | Falha bloqueia |
| Segurança | `security` | Nenhuma dependência nova | Crítico ou Alto bloqueia |
| Carga | `load-test.yml` (manual) e `scripts/load-test.sh` local | Cenários `u7` e `u7-jobs` abaixo | Não bloqueia o merge; é evidência do ponto de controle da U7 |

## Cenários de carga (`scripts/load-test.sh` e `scripts/load/load-test.mjs`)

Sem a U5, os ciclos vêm de um gerador de ciclos de teste, `LoadtestCycleDriver`, que existe só com os perfis `test` e `loadtest` (o `LoadtestProfileGuard` já impede `loadtest` sem `test`): ele cria 60 contas PAPER com `FUNDING`, abre uma transação por ciclo a cada 30 s por bot e chama `PaperExecution.execute` com ordens sintéticas sobre os tokens simulados da U4, medindo o tempo da chamada.

| Item | `u7` | `u7-jobs` |
|---|---|---|
| Duração | 10 minutos | 30 minutos |
| Preparação | 20 pessoas com 10 000 preenchimentos cada, gerados antes por um semeador de carga do perfil `loadtest` com a própria `PaperExecution`; 60 bots ativos no gerador de ciclos | 60 bots com 5 posições cada; gerador de ciclos ativo |
| Ações | Ciclos do gerador; 20 usuários consultando P&L (30 e 366 dias), ledger, operações, uso do dia e reconciliação, uma ação a cada 2 s | Ciclos do gerador; marcação e fechamento pendente depois de cada coleta (30 s) |
| Limites | p95 `execute` ≤ 250 ms; p95 das rotas C5 ≤ 1 s | Nenhuma rodada acima de 10 s em 60 rodadas |
| Plano das consultas | `EXPLAIN` de P&L, ledger e saldo; reprova `Seq Scan` em `ledger_entry` ou `fill` | — |
| Relatório | `docs/perf/u7-ledger-pnl-<data>.md` | Mesmo arquivo, seção própria |

O `load-test.yml` passa a aceitar `u7` e `u7-jobs` na entrada de cenário; o `WorkflowsTest` confere.

## Verificações de NFR na CI

| NFR | Onde roda | Como |
|---|---|---|
| NFR4.1 resultados exatos | job `backend` | Catálogo C1 a C15 com igualdade exata |
| NFR6.2 imutabilidade | job `backend` | `LedgerPrivilegesIT` com o papel da aplicação, uma linha da matriz por teste |
| NFR6.8 idempotência | job `backend` | Mesma chave em sequência, em paralelo e depois de falha |
| NFR10.5 sem deadlock | job `backend` | `LedgerLockOrderIT`, 50 repetições, inclusive fechamento pendente com `pnl_daily` |
| NFR5.1 isolamento | job `backend` | `LedgerRoutesIT` com duas pessoas e dois tenants |
| NFR13.1 portabilidade | job `backend` (Linux) e local (Windows) | Mesma suíte; fixtures em LF |

## Implantação e rollback

- **Implantação:** só local; `docker compose up` aplica a `0008`.
- **Migração aditiva:** só acrescenta tabelas, índices, gatilhos, privilégios e a política v1.
- **Rollback para a U3:** a imagem anterior roda sobre o banco migrado (tabelas novas sem uso). O pool do agendador volta a 7 pelo padrão da imagem anterior.
- **Teste do rollback:** automático, `MigrationsIT` aplica até a `0008` e roda as consultas das Units anteriores; manual, no ponto de controle da U7, subir a imagem anterior sobre o volume migrado e rodar `bash scripts/verify.sh` até sair 0.

## Segredos na CI

Nenhum segredo novo.

## Ação pendente do responsável do projeto

Acrescentar `e2e/u7-ledger-pnl` à linha do Playwright em `scripts/verify.sh`, como nas Units anteriores. O texto é preparado na geração de código.
