# Componentes lógicos — U2 `u2-retention`

Conversa: português (Brasil). Mostra onde cada padrão de NFR da U2 se aplica. Sai deste desenho e de `construction/u2-retention/functional-design/functional-spec.md`.

| Componente | Módulo | Papel | Falha e alcance |
|---|---|---|---|
| `WalletHasher` | Audit | máscara e HMAC da carteira (NFR1.1, NFR6.1) | sem chave, a API não sobe; o alcance é o processo inteiro, de propósito |
| `AuditEventValidator` (ampliado) | Audit | recusa endereço completo em `details` (NFR1.2) | recusa o evento de quem chamou; só em defeito |
| `AuditOutboxRelay` (ampliado) | Audit | copia `walletHmac` para a coluna (BR2.18) | igual ao relay de hoje |
| `LoginChallengePurgeJob`, `SessionPurgeJob` | Identity | expurgos novos (NFR7.1) | só o expurgo; a API segue |
| Executor comum dos jobs e `identity_job_run` | Identity | trava, lotes, execução, métricas e evento (NFR3.7, NFR8.1) | só os jobs do Identity |
| `IdentityJobRecovery` | Identity | recuperação na subida (NFR7.3) | registra a falha; a subida segue |
| Indicadores `loginChallengePurge`, `sessionPurge` | Identity | saúde (NFR7.2) | fora de liveness e readiness |
| `RetentionEnforcerDeclaration` | shared (`com.memetrade.shared.retention`) | declaração de cada expurgo (BR2.17) | nenhum em produção; o teste de conformidade falha |
| Declarações nos módulos | Audit, Feedback, Bots, RiskControl, Identity | um bean de declaração por expurgo, lendo a propriedade do próprio expurgo | nenhum em produção |
| `RetentionCatalog` | Privacy (`com.memetrade.privacy`, módulo novo) | catálogo de retenção; implementa a porta J5 (`RetentionTableSource`) | tabela da política; sem falha em execução |
| Teste de conformidade e lista de pendências | Privacy (teste) | BR2.10 a BR2.12 | falha o build |

## Recursos compartilhados

| Recurso | Dono | Quem usa | Fronteira |
|---|---|---|---|
| Coluna e índice `audit_event.wallet_hmac` | Audit | só o Audit grava | a leitura pela carteira fica para a U4 e a U5, pela interface do Audit |
| `identity_job_run` | Identity | só os jobs e os indicadores do Identity | nenhum outro módulo lê |
| Pacote `shared.retention` | Privacy (contrato J7) | todos os módulos com expurgo | só a interface, sem lógica |
