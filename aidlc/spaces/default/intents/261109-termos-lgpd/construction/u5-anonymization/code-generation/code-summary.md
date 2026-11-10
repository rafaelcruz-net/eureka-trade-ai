# Resumo do código — U5 `u5-anonymization`

Conversa: português (Brasil). Código, nomes e logs em inglês. Os passos 1 a 12 de `code-generation-plan.md` estão concluídos; o `bash scripts/verify.sh` e o spec `e2e/anonymization` rodam no ponto de controle (ver "Pendências"). A lista de arquivos está em `source-manifest.json`, e a ligação com os requisitos, em `traceability.json`.

## Arquivos criados e alterados

- **Migração `0015-anonymization.sql`:** status `ANONYMIZED`; carteira com endereço e provedor anuláveis e `anonymized_at`; `wallet_anonymized_ck` e Base58 só com endereço; índice `login_challenge_wallet_idx`; função `erase_feedback_of_user` (`SECURITY DEFINER`, `search_path` fixo, `EXECUTE` só para o login da aplicação). Um changeset por mudança, cada um com rollback.
- **Preparo de papéis:** `infra/postgres/bootstrap/10-roles.sql` concede `feedback_retention` ao dono do esquema e revoga só do login da aplicação. O gatilho do feedback não mudou.
- **Interfaces públicas:**
  - J1 `AccountAnonymizationPort` (nova): `target`, `anonymizeAccount(tenant, user, now)` e `anonymizedAmong`. A `PersonDataPort` não mudou;
  - J2 `BotOwnerDataPort.anonymizeOwnerBots`, com `LockOrder.lockOwnerBots` (vários bots por `bot_id`) e a regra `BotStateMachine.anonymizeStop`;
  - J3 `FeedbackOwnerDataPort.deleteByUser`, pela função.

  Todas usam `Propagation.MANDATORY`. Os blocos P2, J1 (`J1-Identity-U5`), J2, J3 e J6 e a tabela de códigos de erro do resumo de contratos foram atualizados.
- **Identity:** `lockActiveForSession` (`FOR SHARE`) no login; `findAuthViewSharingAccount` (`for share of u`) na renovação, que recusa com o `INACTIVE_ACCOUNT` existente (`401`); leitores de carteira (`JdbcAccountDetailsRepository`, `JdbcIdentityDirectory.walletOf`) tratam carteira nula; `SmokeInviteSeeder` semeia o segundo convite quando `SMOKE_ANONYMIZATION_INVITE_CODE` existe.
- **Privacy:** `AnonymizationService` (validações, trava do pedido, `lock_timeout`, transação única, conclusão com a resposta modelo de exclusão), `AnonymizationMetrics` (sucessos, falhas pré-registradas por `reason`, timer), `AuditAnonymizationEvents`, `AdminAnonymizationController` (P2) e o campo aditivo `accountAnonymized` nos DTOs dos pedidos.
- **Configuração:** 5 códigos de erro novos (`CONFIRMATION_REQUIRED`, `REQUEST_NOT_ELIGIBLE`, `NO_LINKED_ACCOUNT`, `ADMIN_ACCOUNT_NOT_ANONYMIZABLE`, `LOCK_TIMEOUT`), a ação `ACCOUNT_ANONYMIZED`, a operação idempotente `DATA_REQUEST_ANONYMIZE` e a rota no `route-registry.yml`. A origem `EMAIL` sem identidade confirmada usa o código existente `IDENTITY_NOT_VERIFIED`, como pede AC6.1.2.
- **Admin:** `AnonymizeAccountButton`, `AnonymizeAccountDialog` (T8) e `AccountState` ("Conta anonimizada"); tipos regenerados.
- **Ponta a ponta:** `apps/web/e2e/anonymization/smoke.spec.ts`, com conta descartável e carteira efêmera de teste.
- **Documentação:** `docs/api/u5-anonymization.md`.

## Testes executados

| Comando | Resultado |
|---|---|
| Testes do Passo 2 antes do código (Red) | 38 de 39 testes de unidade falhando (o que passava confere só a serialização do resultado); os 30 ITs da anonimização falhando; `FeedbackEraseFunctionIT` já verde (8/8), porque a função vem do Passo 1 |
| Mesmos testes depois dos Passos 3 a 5 (Green) | 142 testes de unidade e 38 ITs verdes |
| `mvnw clean verify` completo, sem `MEMETRADE_AUDIT_WALLET_HMAC_KEY` | BUILD SUCCESS: 1315 testes de unidade e 944 ITs, 0 falhas; JaCoCo 93,96% de linhas (`privacy` 94,73%). `DashboardOpenLatencyIT` passou nesta execução |
| Admin: lint, typecheck, format:check, check:api, test:coverage | 164/164; 91,27% de linhas |
| Web: lint, typecheck, format:check, check:api, test:coverage | 531/531; 95,57% de linhas |
| Depois da troca para `IDENTITY_NOT_VERIFIED` | `AnonymizationServiceTest` 17/17, `AdminOpenApiContractTest` 7/7, `AdminAnonymizationIT` 10/10, `check:api` do admin, Jest de `data-requests` 46/46, Prettier |
| Playwright `e2e/anonymization` | não executado: precisa do ambiente do `verify.sh` e do pré-requisito abaixo |

**Evidência do Red do Passo 2:** a contagem acima vem do relatório do desenvolvedor; não há log nem commit separado dos testes antes do código, então a ordem é declarada e não verificável depois do fato. A correção da repetição (desvio 5) teve o Red observado nesta sessão.

## Desvios do plano

1. **Ordem de travas da J1:** sessões, depois apagar os desafios do endereço, depois a conta. Na ordem do desenho, o login (que segura o desafio consumido e depois pede `FOR SHARE` na conta) formaria um impasse com a anonimização. A revogação das sessões continua depois da trava da conta.
2. **Respostas idempotentes dos desafios:** a J1 também apaga de `idempotency_record` as respostas guardadas da emissão de desafios (escopos `wallet:<endereço>:auth.challenge` e `admin.auth.challenge`), porque guardam a mensagem com o endereço. O apagamento usa a chave primária, e o `AnonymizationIndexIT` cobre esse sexto comando.
3. **Contrato da J1:** interface nova `AccountAnonymizationPort`, que devolve `AccountAnonymization`; o Privacy monta o `AnonymizationResult`.
4. **Resposta de P2:** é o `AnonymizationResult`, como no NFR design.
5. **Repetição (BR5.5, depois da revisão do código):** a elegibilidade agora vale também para conta já anonimizada. O próprio pedido já concluído (`DONE`) devolve o mesmo pedido, pessoa e instante, com `alreadyAnonymized: true` e contagens 0, porque as contagens originais não são gravadas. Outro pedido de Exclusão `IN_PROGRESS` da mesma pessoa é concluído com a resposta modelo e `DATA_REQUEST_TRANSITIONED`, sem tocar a conta nem publicar `ACCOUNT_ANONYMIZED`. Um pedido `RECEIVED` ou `REJECTED` continua recebendo `409 REQUEST_NOT_ELIGIBLE` (AC6.1.4). Com a mesma chave, a resposta guardada volta idêntica. Os testes novos de `AnonymizationServiceTest` falharam antes da correção (2 de 18) e passaram depois (18/18), com `AdminAnonymizationIT`, `AnonymizationConcurrencyIT`, `AnonymizationTransactionIT` e `AnonymizedAccessIT` verdes.
6. **"Listagem de contas":** o admin não tem lista de contas; "Conta anonimizada" aparece na lista e no detalhe dos pedidos.
7. **Renovação:** o `FOR SHARE` fica em `SessionRepository.findAuthViewSharingAccount`, e a recusa usa o `INACTIVE_ACCOUNT` existente (`401`).
8. **`EXECUTE` da função:** concedido por um bloco `DO` ao papel que tem `INSERT` em `feedback_entry` e não é o dono, porque o nome do login vem do `.env` (mesmo padrão da `0005`).
9. **Captura de log:** `AnonymizationNoWalletLeftIT` e `AnonymizationObservabilityIT` usam um `ListAppender` do logback, que lê a mensagem antes do mascaramento; o `CapturedOutput` vinha vazio nesse contexto.
10. **Concorrência do login:** com o desvio 1, o desafio consumido já serializa o login com a anonimização, então o caso de login do `AnonymizationConcurrencyIT` não falha sem o `FOR SHARE`; ele fica como defesa em profundidade.
11. **Resposta modelo de exclusão:** o texto está em `AnonymizationService.DELETION_RESPONSE` e em `messages.dataRequests.templateText.DELETION`.
12. **Código de erro da identidade:** o NFR design previa um código novo; o código usa o existente `IDENTITY_NOT_VERIFIED`, que é o que AC6.1.2 pede.

## Pendências

- **`LogScanIT` rodado sozinho:** 5 testes falham em cascata a partir da falha já conhecida do teste da U3, e o MockMvc imprime cabeçalhos com o token na saída capturada. No `verify` completo a classe passa, e o teste novo da U5 passa isolado. Fica para o Build and Test.
- **Pré-requisitos do responsável do projeto:**
  1. `scripts/verify.sh`: gerar e exportar `SMOKE_ANONYMIZATION_INVITE_CODE` (como `SMOKE_INVITE_CODE`) e passá-lo ao Playwright; terminar a lista de specs em `... e2e/legal-documents e2e/reacceptance e2e/data-requests e2e/anonymization`; exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` fora do bloco do `.env` (U2).
  2. `.github/workflows/ci.yml`: `MEMETRADE_AUDIT_WALLET_HMAC_KEY` no passo `Compose config`.
  3. `.github/workflows/load-test.yml`: opções `reacceptance` e `data-requests`.
- **Rollback:** depois da primeira anonimização real, só correção para frente (ver `infrastructure-design/cicd-pipeline.md`).
