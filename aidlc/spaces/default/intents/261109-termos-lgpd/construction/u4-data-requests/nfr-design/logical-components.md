# Componentes lógicos — U4 `u4-data-requests`

Conversa: português (Brasil). Mostra onde cada padrão de NFR da U4 se aplica.

| Componente | Módulo | Papel | Falha e alcance |
|---|---|---|---|
| `DataRequestService`, `DataRequestRepository`, `DataRequestRateLimit` | Privacy | criação, transições, isolamento e limite | só os pedidos |
| `IdentityVerificationService`, `VerificationMessage` | Privacy | mensagem e confirmação (usa J1) | só a verificação |
| `AccessSummaryAssembler` | Privacy | resumo pelas J1 a J4 | só a prévia ou a liberação |
| `DataRequestController` (P1), `AdminDataRequestController` (P2 sem anonimização) | Privacy (web) | rotas | padrão RFC 7807 |
| `PrivacyJobRunner`, `DataRequestTimersJob`, `DataSubjectRequestPurgeJob`, `PrivacyJobRecovery`, `privacy_job_run` | Privacy | jobs | só os jobs; a API segue |
| `DataRequestsHealthIndicator`, indicador `dataSubjectRequestPurge`, `DataRequestMetrics` | Privacy | saúde e métricas | fora de liveness e readiness |
| `PersonDataPort` (J1: `personSummary`, `findAccount`, `verifyAccountSignature`) | Identity | leitura e conferência | só o Privacy usa |
| `BotOwnerDataPort.countOwnerBots` (J2), `FeedbackOwnerDataPort.countByUser` (J3), `PaperOrderCountPort` (J4) | Bots, Feedback, Ledger | contagens | leitura só |
| T5 `DataRequestsSection` (web), T6 a T6c (admin) | apps | telas | estados em texto |

## Recursos compartilhados

| Recurso | Dono | Quem usa | Fronteira |
|---|---|---|---|
| `data_subject_request`, `data_subject_verification` | U4 (Privacy) | U5 (pedido de Exclusão e anonimização) | só pelo Privacy |
| Prefixo `/api/v1/privacy/` liberado na trava | U3 (configuração) | U4 | lista de prefixos |
| Catálogo de retenção | U2 | U4 tira `DATA_SUBJECT_REQUESTS` das pendências | declaração do expurgo |
