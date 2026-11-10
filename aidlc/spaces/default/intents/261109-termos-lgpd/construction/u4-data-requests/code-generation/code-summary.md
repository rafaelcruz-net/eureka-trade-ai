# Resumo do código — U4 `u4-data-requests`

Conversa: português (Brasil). Código, nomes e logs em inglês. Os passos 1 a 12 de `code-generation-plan.md` estão concluídos, e o Passo 13 está parcial (ver "Testes executados"). A lista de arquivos está em `source-manifest.json`, e a ligação com os requisitos, em `traceability.json`.

## Arquivos criados e alterados

- **Migração `0014-data-requests.sql`:** cria as tabelas `data_subject_request`, `data_subject_verification` e `privacy_job_run`, com os índices, entre eles o único parcial dos pedidos abertos por tipo. Ela só acrescenta, tem rollback e está registrada no changelog mestre.
- **Interfaces públicas:**
  - J1 `PersonDataPort`: `personSummary`, que devolve `Optional`; `findAccount`; `verifyAccountSignature`, que só aceita a mensagem de verificação e confere com Ed25519 nativo;
  - J2 `BotOwnerDataPort.countOwnerBots`;
  - J3 `FeedbackOwnerDataPort.countByUser`;
  - J4 `PaperOrderCountPort.countPaperOrders`.

  Os blocos J1 a J4, P1 e P2 do resumo de contratos foram atualizados.
- **Módulo Privacy:**
  - domínio dos pedidos e da verificação, com `DataRequestService`, `IdentityVerificationService`, `DataRequestTimers` e `VerificationMessage`;
  - infraestrutura: stores JDBC filtrados por tenant e pessoa, limite por hora com trava de transação, `AccessSummaryAssembler`, `PrivacyJobRunner` (com `privacy_job_run`, recuperação na subida e o evento `DATA_REQUESTS_PURGED`), `DataRequestTimersJob`, `DataSubjectRequestPurgeJob`, o refresher de métricas de 60 s, a saúde `dataRequests` e a saúde do expurgo;
  - rotas P1 (convidado) e P2 (administrador, sem a anonimização).

  O catálogo de retenção passou a `PURGE dataSubjectRequestPurge`, e a lista de pendências ficou vazia.
- **Configuração:**
  - prefixo `/api/v1/privacy/` liberado na trava da U3;
  - pool do agendador em 21 (`application.yml`, `RuntimeSettingsGuard`, Compose, `.env.example` e os testes);
  - 6 códigos de erro, 6 ações de auditoria e 6 operações idempotentes novas.
- **Apps:**
  - web: a seção "Meus dados (LGPD)" na Conta (`features/data-requests/`);
  - admin: `/pedidos`, `/pedidos/[id]` e `/pedidos/novo`, com lista, detalhe, registro por e-mail, painel de verificação e resposta ao contato, e o item "Pedidos LGPD" no menu;
  - tipos regenerados nos dois apps.
- **Ponta a ponta e carga:**
  - spec `e2e/data-requests/smoke.spec.ts`;
  - caso `data-requests` em `scripts/load-test.sh`;
  - `scripts/load/data-requests.mjs`, com teste;
  - semeadura `privacy/loadtest/DataRequestLoadSeed`, só no perfil `loadtest`.
- **Documentação:** `docs/api/u4-data-requests.md`.

## Testes executados

| Comando | Resultado |
|---|---|
| Testes do Passo 3 antes do Passo 4 (Red) | 75 testes, todos falhando (`UnsupportedOperationException("Passo 4")` nos esqueletos) |
| Os mesmos depois do Passo 4 | 75 de 75 |
| Comando backend da Unit | 120 unitários verdes. Os ITs da Unit (`DataRequestIT`, `AdminDataRequestIT`, `IdentityVerificationIT`, `DataRequestTimersIT` e `DataSubjectRequestPurgeIT`) também ficaram verdes |
| `clean verify` completo, sem `DashboardOpenLatencyIT` | BUILD SUCCESS: 1272 unitários e 2142 ITs, JaCoCo com **93,87% de linhas** (`privacy`: 94,26%) |
| `clean verify` completo com todos os ITs | 1 falha: `DashboardOpenLatencyIT`, grupo BOTS com p95 de 2,08 s (limite de 2 s) |
| Web: lint, typecheck, format:check, check:api e test:coverage | 531 de 531 testes; **95,57% de linhas** |
| Admin: lint, typecheck, format:check, check:api e test:coverage | 141 de 141 testes; **91,17% de linhas** |
| `node --test scripts/load/load-test-data-requests.test.mjs` | 9 de 9 |

**Falha conhecida.** `DashboardOpenLatencyIT` é da U9 do intent anterior e já falhava nesta máquina na U3, também com a trava desligada. O limite não foi alterado, e o teste volta a ser conferido no Build and Test.

**`LogScanIT` só falha numa execução filtrada.** O teste da U3 dele depende de registros de idempotência deixados por outras classes. No `verify` completo, ele passa.

## Testes escritos e não executados nesta etapa

- **`e2e/data-requests/smoke.spec.ts`:** precisa do ambiente do `verify.sh` e de que o responsável acrescente o spec ao script.
- **Cenário de carga `data-requests` contra o ambiente:** é manual e fica para o Build and Test. Cobre AC5.1.5, NFR3.1 a NFR3.3 e BR4.19.

## Desvios do plano

1. **Arquitetura do Privacy.** O `PrivacyArchitectureTest` passou a aceitar as bibliotecas de que a infraestrutura e a web do Privacy precisam: ShedLock, Micrometer, Jackson, Servlet e Swagger. Continua proibido depender de partes internas de outro módulo.
2. **Privilégios.** A migração não tem `GRANT`, porque os privilégios padrão do banco já dão acesso ao papel da aplicação, como em `identity_job_run`.
3. **Domínio da mensagem de verificação.** Ele vem de `memetrade.auth.guest-app-origin`, como no login; `memetrade.auth.domain` não existe.
4. **Contrato P2.** Mudanças registradas no bloco P2:
   - a lista passou a ser paginada (`{items, nextCursor}`);
   - o pedido ganhou o campo `dueSoon`;
   - na lista, `verification` e a prévia vêm nulas;
   - a conferência ganhou `400` sem assinatura e `409` (`DATA_REQUEST_CLOSED` ou `INVALID_TRANSITION`);
   - a transição ganhou `422` ao liberar o resumo fora de um pedido de Acesso.
5. **Bloco J6.** Ele não recebeu a ação nova `DATA_REQUESTS_PURGED`; fica para o próximo ajuste do resumo de contratos.
6. **Semeadura de carga.** Grava 1000 pedidos por e-mail sem conta ligada, para não escrever em tabelas do Identity. A classe se chama `DataRequestLoadSeed`, por causa da regra de arquitetura de seeders.
7. **Volume de testes.** Alguns componentes pequenos dos apps têm menos de 10 testes cada. Os pisos de 80% foram atendidos.
8. **Nota do app do administrador.** O texto "Sem acesso a ledger, operações ou carteiras dos convidados" continua verdadeiro, mas agora o administrador vê o texto e o contato dos pedidos. Vale revisar a nota.
9. **Formatação.** O `spotless:apply` rodou no projeto inteiro e pode ter reformatado arquivos das Units anteriores ainda não commitados (só formatação).

## Pendências do responsável do projeto

1. **`scripts/verify.sh`:** a lista de specs deve terminar em `... e2e/legal-documents e2e/reacceptance e2e/data-requests`, antes do ponto de controle da U4.
2. **`.github/workflows/load-test.yml`:** acrescentar as opções `reacceptance` e `data-requests`, antes do Build and Test.
3. **Itens da U2 ainda pendentes:**
   - a chave HMAC no `verify.sh`, no `ci.yml`, no `perf-audit.sh` e no `.env`;
   - o link do runbook.
