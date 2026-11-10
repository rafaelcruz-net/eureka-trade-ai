# U5 — Anonimização da conta (`u5-anonymization`)

Intent `261109-termos-lgpd`. A anonimização atende um pedido de Exclusão (LGPD) em andamento: a conta deixa de ter
carteira, provedor, sessões, comentários e nomes de bot, e o histórico (PAPER, ledger, aceites, trilha) fica guardado,
ligado só ao identificador interno. Tudo acontece numa única transação aberta pelo módulo Privacy.

## Rota

`POST /api/v1/admin/privacy/requests/{requestId}/anonymization` (P2 `adminAnonymizeAccount`)

- Só o papel `ADMIN` (regra de rota `/api/v1/admin/**` e nova conferência na operação). Registrada no
  `route-registry.yml` com escopo `ADMIN`.
- Cabeçalho `Idempotency-Key` obrigatório (operação `DATA_REQUEST_ANONYMIZE`).
- Corpo: `{ "confirmation": "ANONIMIZAR" }`. O tenant e a pessoa vêm do pedido, nunca do corpo.
- Resposta `200` (`AnonymizationResult`): `requestId`, `userId`, `anonymizedAt`, `sessionsRevoked`, `botsStopped`,
  `feedbackDeleted`, `alreadyAnonymized`. Só identificadores internos, o instante e contagens.

O detalhe e a lista de pedidos (`AdminDataRequest`) ganharam o campo `accountAnonymized`; o app do administrador mostra
"Conta anonimizada", nunca a carteira.

## Recusas (RFC 7807, com `code` e `correlationId`)

Conferidas nesta ordem; nenhuma recusa muda nada nem consome a chave:

| Status | `code` | Quando |
|---|---|---|
| 422 | `CONFIRMATION_REQUIRED` | a confirmação não é exatamente `ANONIMIZAR` (antes de ler o pedido) |
| 404 | `NOT_FOUND` | pedido inexistente |
| 409 | `REQUEST_NOT_ELIGIBLE` | o pedido não é de Exclusão ou não está `IN_PROGRESS` |
| 409 | `NO_LINKED_ACCOUNT` | o pedido não está ligado a uma conta |
| 409 | `ADMIN_ACCOUNT_NOT_ANONYMIZABLE` | a conta ligada é `ADMIN` |
| 409 | `IDENTITY_NOT_VERIFIED` | origem `EMAIL` sem identidade confirmada por assinatura |
| 409 | `IDEMPOTENCY_CONFLICT` | a mesma chave com outro conteúdo |
| 503 | `LOCK_TIMEOUT` | uma trava não foi obtida em 2 s; nada mudou; `Retry-After: 2`; repetir com a mesma chave |

**Repetição.** Com a mesma chave, a resposta guardada volta idêntica. Sem ela, numa conta já `ANONYMIZED`:

- o próprio pedido já concluído (`DONE`) responde `200` com o mesmo pedido, a mesma pessoa e o mesmo instante,
  `alreadyAnonymized: true` e contagens 0; nada é escrito e nenhum evento sai;
- outro pedido de Exclusão `IN_PROGRESS` da mesma pessoa responde do mesmo jeito e é concluído com a resposta modelo
  de exclusão e `DATA_REQUEST_TRANSITIONED`, sem tocar a conta nem publicar `ACCOUNT_ANONYMIZED`;
- um pedido `RECEIVED` ou `REJECTED` continua recebendo `409 REQUEST_NOT_ELIGIBLE`.

## O que sai e o que fica

| Sai | Fica (sem ligação com a carteira) |
|---|---|
| endereço e provedor da carteira (`wallet.address`, `wallet.provider` nulos, `wallet.anonymized_at`) | linha da conta (`app_user`, estado `ANONYMIZED`) e linha da carteira sem endereço |
| todas as sessões (revogadas) | ledger, ordens e fills PAPER, recusas da barreira, posições abertas (sem ordem de fechamento) |
| desafios de login do endereço e as respostas idempotentes guardadas da emissão desses desafios | aceites dos termos |
| comentários da pessoa (função `erase_feedback_of_user`) | trilha de auditoria (eventos antigos guardam só máscara e HMAC da carteira até o expurgo de 12 meses) |
| nomes dos bots ("Bot anonimizado"); bots `ACTIVE`, `PAUSED` e `CLOSING_PENDING` vão direto a `STOPPED` | bots `DRAFT` e `VALIDATED` no mesmo estado |

O pedido vai para `DONE` com a resposta modelo de exclusão (a mesma do app do administrador). Os eventos
`ACCOUNT_ANONYMIZED` (ator o administrador, alvo `user:<id>`, `details` só com `botsStopped`, `sessionsRevoked` e
`feedbackDeleted`; o validador recusa outra chave) e `DATA_REQUEST_TRANSITIONED` saem na mesma transação.

## Transação e ordem de travas

1. Confirmação; reivindicação da chave; `set local lock_timeout` com `memetrade.bots.lock-timeout` (2 s).
2. Pedido `FOR UPDATE` (duas anonimizações do mesmo pedido são serializadas; a segunda responde como repetição).
3. Conferências de BR5.1 (sem trava nova).
4. **J2 (Bots)**, pela `LockOrder`: pausa do sistema compartilhada, `bot_owner`, todos os bots da pessoa numa só consulta
   ordenada por `bot_id`; parar e renomear.
5. **J3 (Feedback)**: `erase_feedback_of_user(tenant, pessoa)`.
6. **J1 (Identity)**: sessões `FOR UPDATE` por `session_id`; endereço lido sem trava e desafios do endereço apagados;
   conta `FOR UPDATE`; revogação das sessões depois da trava da conta; carteira sem endereço; estado `ANONYMIZED`.
7. **J6**: `ACCOUNT_ANONYMIZED`; conclusão do pedido e `DATA_REQUEST_TRANSITIONED`.

Os caminhos concorrentes seguem a mesma ordem: o login trava a conta com `FOR SHARE` antes de abrir a sessão e recusa uma
conta que não esteja `ACTIVE`; a renovação trava a sessão e depois a conta com `FOR SHARE`; o ciclo do bot relê o estado
sob a trava do bot. Os desafios do endereço são apagados **antes** da trava da conta: um login em andamento segura o seu
desafio consumido, termina primeiro, e a sessão nova é revogada em seguida (sem ciclo de espera). O token de acesso já
emitido deixa de valer na requisição seguinte, porque a leitura por requisição exige conta `ACTIVE`.

Qualquer exceção desfaz tudo e o pedido continua `IN_PROGRESS`.

## Observabilidade

- `memetrade_accounts_anonymized_total`: depois do commit.
- `memetrade_account_anonymization_failures_total{reason}`: `LOCK_TIMEOUT` ou `ROLLED_BACK`, ambos registrados em zero na
  partida; cresce depois do rollback. Recusas e repetições não contam.
- `memetrade_account_anonymization_seconds`: duração da transação, com a espera de trava.
- Logs: `INFO "Account anonymized"` e `ERROR "Account anonymization failed"`, só com `requestId`, contagens e a classe da
  exceção; nunca o endereço.

## Migração `0015-anonymization.sql` e papéis

Só acrescenta ou relaxa: `app_user_status_ck` com `ANONYMIZED`; `wallet.address` e `wallet.provider` anuláveis e
`wallet.anonymized_at`; `wallet_anonymized_ck`; `wallet_address_base58_ck` só com endereço presente;
`login_challenge_wallet_idx`; a função `erase_feedback_of_user` (`SECURITY DEFINER`, `search_path` fixo, `EXECUTE` só do
login da aplicação). O gatilho `feedback_entry_guard` não muda.

`infra/postgres/bootstrap/10-roles.sql` concede `feedback_retention` ao dono do esquema (idempotente) e revoga o papel só do
login da aplicação.

**Risco aceito:** com o dono membro de `feedback_retention`, uma migração futura (que roda como dono) pode apagar feedback
sem ser barrada pelo gatilho. A ampliação é pequena (o dono já é dono da tabela e do gatilho) e é mitigada pela revisão de
toda migração nos gates do AI-DLC; `U8PrivilegesIT`, `RolesBootstrapIT` e `FeedbackEraseFunctionIT` provam que a aplicação
continua fora do papel e que o `DELETE` direto dela continua recusado.

## Rollback

- **Antes da primeira anonimização:** a imagem anterior funciona sobre o esquema `0015` (todas as carteiras têm endereço e
  provedor; ela não lê `anonymized_at`), e o rollback de cada changeset do Liquibase é válido (`AnonymizationMigrationIT`).
- **Depois da primeira anonimização:** não há rollback de imagem nem de Liquibase (o `NOT NULL` da carteira não volta e
  nenhuma imagem anterior trata carteira nula). O caminho é **correção para frente**; o piso de rollback passa a ser a
  própria imagem da U5.

## Ponta a ponta

`apps/web/e2e/anonymization/smoke.spec.ts` usa uma conta descartável: uma carteira gerada no teste entra pelo segundo
convite do perfil `test` (`SMOKE_ANONYMIZATION_INVITE_CODE`), pede Exclusão na Conta; o administrador do smoke inicia o
pedido e anonimiza pelo diálogo T8; o spec confere o pedido `DONE`, "Conta anonimizada" no detalhe e na lista, o `401` do
token antigo e a recusa de um novo login com a mesma carteira.

Pré-requisitos do responsável do projeto em `scripts/verify.sh`: gerar e exportar `SMOKE_ANONYMIZATION_INVITE_CODE` (como
`SMOKE_INVITE_CODE`, também passando-o ao Playwright) e terminar a lista de specs em
`... e2e/legal-documents e2e/reacceptance e2e/data-requests e2e/anonymization`.
