## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T10:15:30Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/code-generation/code-summary.md > "Falha de DashboardOpenLatencyIT" | O `verify` completo termina com 1 falha: `DashboardOpenLatencyIT` (grupo BOTS, p95 acima do limite de 2 s). Reexecutei o teste isolado e ele falhou de novo (p95 de 2,31 s, 76 s de execução). O resumo atribui a falha à carga da máquina e afirma que a trava não é a causa, mas a evidência é uma única medição sem a trava, também acima do limite, e não há medição da base anterior à U3. Hoje a abertura do painel faz uma consulta extra por requisição de convidado, e o limite de 2 s é uma meta mensurável que não pode ser relaxada. | Antes do ponto de controle, medir o mesmo teste na base sem a U3 (ou com a trava desligada) na mesma máquina e registrar os dois p95. Se a base passar e a U3 não, tratar como defeito da trava. Se ambas falharem, registrar o motivo ambiental com a evidência e confirmar o teste verde no Build and Test. | New |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/identity/web/TermsReacceptanceFilter.java > doFilterInternal (bloco `catch`) | O filtro só converte `DataAccessException` em 503 pelo `ProblemWriter`. Qualquer outra exceção de runtime (por exemplo, falha em `terms.current()`) sai do filtro sem passar pelo `ProblemWriter`: a requisição não passa sem a checagem (falha fechada), mas a resposta é o 500 padrão do contêiner, sem corpo RFC 7807 e sem `correlationId`. | Cobrir também falhas inesperadas de `terms.current()`/`lookup` com 503 pelo `ProblemWriter` (relançando com contexto e sem dado pessoal no log), ou registrar por escrito que o 500 padrão é aceito. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/functional-design/rules.md > BR3.3 e apps/api/src/main/java/com/memetrade/bots/web/BotController.java > `POST /api/v1/bots/{botId}/commands/{command}` | Toda rota de bot do convidado fica bloqueada durante a trava (decisão do desenho), inclusive o comando de pausa, enquanto os bots ativos seguem operando no worker. Com a trava ativa, a pessoa não consegue pausar o próprio bot pelo app. Não é desvio do desenho, mas é uma consequência de segurança que o desenho não discute. | Confirmar com o responsável do projeto que bloquear a pausa durante a trava é intencional, ou liberar o comando de pausa no desenho. Registrar a decisão. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `mvnw verify` com os filtros da Unit (`TermsReacceptance*Test`, `TermsAcceptance*Test`, `ReacceptanceMigration*Test`, `AccountQueryService*Test`, `SmokeAccountSeeder*Test`, `ProblemResponse*Test`; ITs `TermsAcceptance*IT`, `TermsReacceptance*IT`, `ReacceptanceWorker*IT`, `AccountRoute*IT`, `ProcessRoleContextIT`) | Os relatórios gerados nesta execução (07:16 a 07:18) não têm falha nem erro (ex.: `TermsReacceptanceRoutesIT` 3/3, `ReacceptanceWorkerIT` 2/2). A saída em tela foi cortada, e os totais de 1114/827 do diretório de relatórios incluem arquivos antigos. | Os testes da Unit passam. A única falha no diretório é `DashboardOpenLatencyIT`, de uma execução anterior, e reproduzida por mim (ver Major). |
| `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/reacceptance src/features/auth src/features/account src/lib/api --coverage=false` | 15 suítes e 173 testes, todos passaram (há só avisos `act()` do `SessionProvider`) | OK |
| `node --test scripts/load/load-test-reacceptance.test.mjs` | 10 de 10 passaram | OK |
| `mvnw verify -Dit.test=DashboardOpenLatencyIT` (verificação extra) | FALHA: BOTS p95 = 2,3058 s, limite 2 s | Confirma o Major |

### Summary

A trava está correta no que pude verificar: caminho normalizado, prefixo casado pelo caminho inteiro, só `BETA_USER` (administrador e requisição sem principal passam, o worker não tem camada web), pessoa e tenant só do token, consulta filtrada por tenant, 503 pelo `ProblemWriter` se o banco cair, aceite idempotente em uma transação com `ON CONFLICT DO NOTHING`, 409 com `currentVersion`, migração só expansiva (os dados existentes satisfazem as novas restrições), redirecionamento no app com `safeReturnPath` sem redirecionamento aberto, sem dado pessoal em log ou rótulo de métrica e sem `catch` vazio. A pendência que pesa é a falha reproduzida de `DashboardOpenLatencyIT`, cuja causa ainda não foi separada do efeito da consulta da trava.
