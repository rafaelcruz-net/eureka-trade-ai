# Componentes lógicos — U5 `u5-anonymization`

Conversa: português (Brasil). Mostra onde cada padrão de NFR da U5 se aplica.

| Componente | Módulo | Papel | Falha e alcance |
|---|---|---|---|
| `AnonymizationService` | Privacy | validação, trava do pedido, transação única, `lock_timeout`, conclusão do pedido | desfaz tudo; o pedido fica `IN_PROGRESS` |
| `AdminAnonymizationController` (P2) | Privacy (web) | rota `POST .../anonymization` com idempotência (`DATA_REQUEST_ANONYMIZE`) | padrão RFC 7807 |
| `AnonymizationMetrics` | Privacy | contador de sucessos e de falhas | fora de liveness e readiness |
| `AccountAnonymizationPort.anonymizeAccount` (J1) | Identity | sessões, conta, carteira, desafios | `MANDATORY`; só dentro da transação do Privacy |
| `BotOwnerDataPort.anonymizeOwnerBots` (J2), `LockOrder` com vários bots | Bots | parar e renomear | `MANDATORY` |
| `FeedbackOwnerDataPort.deleteByUser` (J3), função `erase_feedback_of_user` | Feedback | apagar o feedback da pessoa | `MANDATORY`; o gatilho continua barrando o resto |
| Leitores de `wallet` (`JdbcAccountDetailsRepository`, `JdbcIdentityDirectory.walletOf`, listagem administrativa, J1 `personSummary`, `JdbcPersonDataRepository`) | Identity, Admin | tratam carteira nula: `walletOf` devolve `Optional.empty()` para a conta anonimizada, e `JdbcPersonDataRepository` já usa `left join`; cada leitor ganha teste com carteira nula | "Conta anonimizada" no admin |
| `LoginVerificationService`, renovação (`SessionService`), `JdbcAccountRepository.lockActiveForSession` | Identity | reconferir `ACTIVE` sob `FOR SHARE` antes de abrir ou renovar sessão | recusa como hoje |
| `infra/postgres/bootstrap/10-roles.sql` | banco (preparo de papéis) | dono membro de `feedback_retention`; aplicação fora | idempotente; roda a cada `docker compose up` |
| `AnonymizeAccountButton`, `AnonymizeAccountDialog` (T8) | apps/admin | diálogo acessível | estados em texto |
| `0015-anonymization.sql` | banco | `CHECK`, colunas, índice, função | rollback só sem conta anonimizada |

## Recursos compartilhados

| Recurso | Dono | Quem usa | Fronteira |
|---|---|---|---|
| `data_subject_request` | U4 (Privacy) | U5 conclui o pedido de Exclusão | só pelo Privacy |
| `LockOrder` | Bots | U5 ganha o passo de vários bots | mesma ordem para todos os caminhos |
| Papel `feedback_retention` | Feedback (0011), pertença no preparo de papéis | login de retenção (expurgo diário) e o dono (função da U5) | só membros apagam; a aplicação nunca é membro |
| Respostas modelo do admin | U4 | resposta modelo de exclusão | catálogo de mensagens |
