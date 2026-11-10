## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T13:56:49Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | apps/admin/src/lib/i18n/messages.ts > `noPersonalData` ("Sem acesso a ledger, operações ou carteiras dos convidados.") | O aviso fixo do app administrativo continua na tela, mas agora o administrador lê o texto e o contato dos pedidos e vê a carteira mascarada no resumo de Acesso. O próprio `code-summary.md` (desvio 8) admite que a frase ficou imprecisa e a deixou sem corrigir. | Reescrever a frase para dizer o que o administrador realmente não acessa (ledger, operações e carteira completa), ou mencioná-la no gate como pendência assumida. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/contract-design/contract-summary.md > bloco J6 (desvio 5 do `code-summary.md`) | A ação de auditoria nova `DATA_REQUESTS_PURGED`, publicada pelo expurgo de 12 meses (`PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE`), não está listada no bloco J6 do contrato compartilhado. Quem consome o contrato não vê esta ação. | Acrescentar `DATA_REQUESTS_PURGED` ao bloco J6 antes de a Unit seguinte (U5) consumir o contrato. | New |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/shared/error/ErrorCode.java > `INVALID_TRANSITION` | O título do problema de `INVALID_TRANSITION` mudou de "Invalid bot transition" para "Invalid state transition", e o código passou a ser usado também pelos pedidos de titular. Nenhuma referência ao título antigo restou no repositório (busca feita), então não quebra nada hoje, mas o título de um erro já existente da U5 mudou sem registro como mudança de contrato. | Registrar a mudança do título no contrato (ou no `docs/api/u4-data-requests.md`) para que clientes da U5 que comparem o título saibam dela. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml verify -Dtest=... -Dit.test=... -Djacoco.skip=true` (filtro da Unit) | Saiu com código 0, sem falha de build nem de teste (a saída mostra os logs dos ITs de pedidos e de verificação). A pasta `target/surefire-reports` e `failsafe-reports` também guarda relatórios de execuções anteriores, então não cito contagens delas. | Os testes de unidade e os ITs da Unit (`DataRequest*`, `AdminDataRequest*`, `IdentityVerification*`, `DataSubjectRequestPurge*`, `DataRequestTimers*`, `PersonDataPort*`, `*OwnerDataPort*`, `PaperOrderCount*`, `AccessSummary*`, `PrivacyJob*`, `DataRequestsMigration*`, `RetentionConformance*`) passam. |
| `corepack pnpm@10.18.3 --dir apps/web run test -- src/features/data-requests src/features/account --coverage=false` | 2 suítes e 35 testes passaram | Formulário, lista, detalhe e a seção da Conta do web estão verdes. O `console.warn` impresso vem de `session-account.ts`, que é de teste de outra Unit, e não falha nada. |
| `corepack pnpm@10.18.3 --dir apps/admin run test -- src/features/data-requests --coverage=false` | 1 suíte e 27 testes passaram | Lista, detalhe, painel de verificação, resumo e navegação por teclado do admin estão verdes. |
| `node --test scripts/load/load-test-data-requests.test.mjs` | 9 de 9 passaram | O script de carga está coberto. O cenário contra o ambiente é manual e fica para o Build and Test, como o resumo declara. |
| `pnpm run check:api` no web e no admin (extra) | Os dois imprimem "schema.d.ts matches openapi-*.yaml" | Os tipos gerados estão em dia com os contratos OpenAPI. |
| Buscas estáticas em `apps/api/.../privacy` | Zero `catch` vazio, zero `Instant.now()`/`Clock.systemUTC()`, zero leitura de tabela de outro módulo (`from app_user`, `bot`, `feedback_entry`, etc., só nas portas J1 a J4 de cada dono), zero `dangerouslySetInnerHTML` nas telas de pedidos | Cumpre relógio injetável, fronteira de módulo e texto como texto puro. |

**O que tentei refutar e não consegui (evidência no código):**
- Isolamento: `findOwned` e `listOwned` filtram por tenant e pessoa, e o id malformado ou alheio é o mesmo 404 (`DataRequestController.parse`, `RequestErrors.notFound`). As rotas P2 estão no registro de rotas como ADMIN, e cada operação chama `requireAdministrator`. A rota de pessoa tem `negativeTest` (`anotherPersonsRequestIsNotFoundOnEveryRoute`).
- Texto, contato, mensagem e assinatura: os `toString` dos comandos escondem o texto, o evento de auditoria leva só `requestId`, `type`, `origin`, `from` e `to`, e todos os `log.*` do módulo levam só ids, estados e totais. A assinatura só passa para `PersonDataPort.verifyAccountSignature`; não é coluna, não é parâmetro de SQL e entra na chave de idempotência só por digest.
- Verificação: a mensagem vem do banco (nunca do cliente), começa pela linha fixa de verificação, o J1 recusa qualquer outra primeira linha, confirma só com a carteira da conta ATIVA, e `confirm` é condicional a `PENDING` e `expires_at > now`, com a requisição travada por `FOR UPDATE`. O job de 15 minutos usa `FOR UPDATE ... SKIP LOCKED`, então só um dos dois vence. Existem ITs `theVerificationSignatureSentToTheLoginIsRefused` e `aLoginSignatureNeverConfirmsAVerification`.
- Máquina de estados: `RECEIVED` para `IN_PROGRESS` em EMAIL de ACCESS ou DELETION com conta ativa devolve 409 "verificação de identidade primeiro" (`needsVerificationFirst`). `AWAITING_VERIFICATION` só vai para `REFUSED` pela rota de transição (e expira a verificação na mesma transação). Pedido encerrado devolve `DATA_REQUEST_CLOSED` antes de qualquer erro de verificação.
- Idempotência e limite: o corpo é validado antes de consumir a chave, a chave é reivindicada pelo mecanismo comum do Identity, e o limite por hora roda dentro do fechamento com `pg_advisory_xact_lock` por pessoa. Um 429, 409 ou 422 desfaz a transação e libera a chave. O índice único parcial `uq_open_request_per_type` decide a corrida.
- Aviso de vencido e expurgo: `markOverdue` atualiza só linhas com `overdue_notified_at is null` e devolve só as linhas mudadas, então o evento sai uma vez. O expurgo calcula o corte em meses de calendário UTC com o relógio injetado, apaga a verificação antes do pedido e roda em lotes.
- Migração: `0014-data-requests.sql` só cria três tabelas e índices, com `rollback` em cada changeset e sem tocar objeto existente.

### Summary

Não encontrei falha Critical nem Major. O isolamento por tenant e pessoa, a verificação por assinatura de uso único, a máquina de estados, a idempotência com limite, o aviso único de vencido e o expurgo de 12 meses estão implementados como o design pede, e todos os comandos de validação rodados passam. Ficam três pendências Minor de documentação e texto, nenhuma bloqueia a Unit.
