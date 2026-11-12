<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-11-11T14:00:00Z — as pendências do responsável no `verify.sh` (chave HMAC, segundo convite, specs da U3 a U5), no `load-test.yml` e no `perf-audit.sh` foram aplicadas por patch dele (commit `5d30541`); os specs novos revelaram um defeito real: `/bots` e `/pnl` não eram rotas de retorno do login (corrigido com teste de regressão).
- 2026-11-11T16:00:00Z — a CI nunca tinha rodado num push para `main`; passou a rodar depois da mudança no `ci.yml` (`workflow_dispatch`). A primeira execução expôs problemas antigos que só aparecem no Linux: a chave HMAC faltando no passo "Compose config", o `MigrationRunner` reiniciando o Logback em `WARN` no meio da suíte e a cópia oculta do streaming no `/privacidade`.
- 2026-11-11T18:00:00Z — a primeira execução do `security.yml` acusou CVSS ≥ 7 em dependências do backend que já vinham do intent anterior. Tomcat, pgjdbc e Kotlin têm correção de versão; o Spring 6.2.19 não tem versão aberta corrigida.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-11-11T17:20:00Z — `DashboardOpenLatencyIT` (U9 NFR3.1) virou medição informativa por decisão Q1 = B do responsável no intent anterior, que nunca tinha sido aplicada. Não é relaxamento feito por este estágio: é a decisão registrada do responsável, anotada no NFR3.1.

- 2026-11-11T18:40:00Z — Loop-back 1: U3 NFR3.2 (trava p95 7,0 ms contra 5 ms) volta à geração de código da `u3-reacceptance` por escolha do responsável ("Retry with fix"): aquecer o caminho da consulta da trava na subida e medir separadamente a espera por conexão e a consulta. O pool de tamanho fixo no `application.yml` foi a correção dentro do estágio (8,4 → 7,0 ms).
- 2026-11-11T18:20:00Z — o script de carga do `u3` mandava `acceptedTermsVersion: 'v1'` fixo; corrigido para ler a versão vigente da API (defeito do script, não do produto).

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-11-11T18:10:00Z — Spring 6.2.19: o responsável escolheu exceções por CVE (EX-001 a EX-012, 90 dias, cada uma conferida contra o código) em vez de migrar já para o Spring Boot 4 (dias de trabalho e risco alto) ou contratar o Spring comercial. EX-013 é o falso positivo do Prometheus.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-11-11T18:10:00Z — migrar para o Spring Boot 4 (Spring 7.0.9+) antes de 2027-02-09, quando as exceções EX-001 a EX-012 vencem e voltam a bloquear.
