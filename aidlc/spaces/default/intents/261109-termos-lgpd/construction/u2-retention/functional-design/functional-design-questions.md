# Perguntas do Functional Design — U2 `u2-retention`

Conversa: português (Brasil). A Unit cobre US1.4, US3.1 e US3.2 (FR3.1 a FR3.4, NFR6, NFR7) e os contratos J5 e J6.

O que o código tem hoje:
- **Carteira completa na auditoria:** `LoginVerificationService` e `AdminLoginService` gravam o endereço completo em `details.walletAddress` no `LOGIN_REJECTED`. O `actor` já vai mascarado.
- **Expurgos que faltam:** não há expurgo de `login_challenge` nem de `user_session` e `session_used_refresh_digest`.
- **Padrão dos expurgos existentes:** `FeedbackPurgeJob` e a retenção da auditoria rodam uma vez por dia com ShedLock, em lotes de 1000, publicam um evento com a contagem e têm um indicador de saúde de 48 h.

## Q1 — Onde fica o código HMAC da carteira

A. Numa coluna nova `wallet_hmac` (anulável, com índice) em `audit_event` e num campo `walletHmac` no payload do `audit_outbox`, como o atributo `walletHmac` do `AuditEvent` em `components.md`. A migração só acrescenta (expand) e não altera nenhuma linha existente. (recomendado)
B. Numa chave `walletHmac` dentro de `details`, sem mudar o esquema.
X. Other (please specify)

[Answer]: A

## Q2 — Como a carteira chega à auditoria

A. O `AuditEventCommand` ganha um campo próprio para a carteira. O publicador calcula o HMAC-SHA-256 e a máscara e nunca grava o endereço completo. Valem para todo evento ligado a uma carteira: login aceito e recusado (convidado e administrador), criação de conta e aceite de convite. O validador recusa qualquer valor de `details` com cara de endereço Base58 completo (32 a 44 caracteres), para nenhum chamador escapar. (recomendado)
B. Cada chamador calcula o HMAC e a máscara antes de publicar.
X. Other (please specify)

[Answer]: A

## Q3 — Chave do HMAC ausente

A. A API não sobe sem a chave, que precisa ter pelo menos 32 bytes, em qualquer perfil. Os testes usam uma chave de teste injetada. O nome entra no `.env.example`, sem valor. O responsável do projeto acrescenta a variável ao `scripts/verify.sh` (segredo efêmero por execução) e ao passo `Compose config` do `ci.yml`, com valor de exemplo. (recomendado)
B. Usar uma chave padrão fixa fora de produção.
X. Other (please specify)

[Answer]: A

## Q4 — Corte do expurgo de desafios (FR3.1)

A. Apagar todo desafio com `expires_at` 24 h ou mais antes do relógio, em qualquer estado (`ISSUED`, `CONSUMED` ou `EXPIRED`). Um desafio mais recente fica. (recomendado)
B. Apagar só os desafios em `EXPIRED` ou `CONSUMED`.
X. Other (please specify)

[Answer]: A

## Q5 — Fim de uma sessão (FR3.2)

A. O fim é `revoked_at` numa sessão revogada. Nas outras, é o menor entre `refresh_expires_at` e `absolute_expires_at`, quando a sessão já não pode ser renovada. A sessão sai quando o fim tem 30 dias ou mais, e os digests dela saem antes, no mesmo lote. Uma sessão com fim no futuro nunca sai, qualquer que seja o estado gravado. Vale para convidados e administradores. (recomendado)
B. Só sessões com estado `REVOKED` ou `EXPIRED`, contando 30 dias de `revoked_at` ou de `absolute_expires_at`.
X. Other (please specify)

[Answer]: A

## Q6 — Execução e saúde dos expurgos novos (NFR7)

A. Seguem o padrão do `FeedbackPurgeJob`:
- uma vez por dia em horário UTC fixo (desafios às 03:20, sessões às 03:40), só no processo da API, com ShedLock;
- lotes de 1000;
- um evento por execução pelo `system` com a contagem (`LOGIN_CHALLENGES_PURGED`, `SESSIONS_PURGED`) e log só com totais;
- indicadores `loginChallengePurge` e `sessionPurge`, que ficam `UP` até 48 h depois da subida e `DEGRADED` quando a última execução bem-sucedida tem mais de 48 h.

(recomendado)
B. Um único expurgo para desafios e sessões, com um indicador só.
X. Other (please specify)

[Answer]: A

## Q7 — Fonte da tabela de retenção e teste de conformidade (FR3.4, US1.4)

A. O `RetentionCatalog` do módulo novo `privacy` é a fonte. Cada linha traz o dado, a finalidade, o prazo, a ação no fim e quem aplica: um expurgo identificado ou uma regra de guarda "enquanto a conta existir".
- O catálogo implementa a porta J5, e a tabela de `/api/v1/legal/**` passa a vir dele.
- O teste de conformidade confere, linha a linha, que o expurgo citado existe com o mesmo prazo configurado.
- O teste também confere que o `retention.tsv` da versão v2 (e de toda versão não legacy) é igual ao catálogo, e falha apontando a linha divergente.

(recomendado)
B. O `retention.tsv` do catálogo de termos é a fonte, e o teste compara só esse arquivo com os expurgos.
X. Other (please specify)

[Answer]: A

## Q8 — Linhas cujo expurgo chega numa Unit posterior

Os pedidos de titular (12 meses) chegam na U4, e a anonimização (sem vínculo depois dela) chega na U5.

A. A linha já entra no catálogo com quem aplica `PLANNED` e a Unit que entrega. O teste aceita `PLANNED` só para as linhas listadas numa lista de pendências versionada, e a U4 e a U5 retiram cada linha dela ao entregar. Assim a política fica completa e nenhuma pendência passa sem registro. (recomendado)
B. Deixar a linha fora do catálogo e da política até a Unit que entrega.
X. Other (please specify)

[Answer]: A

## Q9 — Checklist de abertura do beta (AC3.2.3)

A. Acrescentar ao checklist de `docs/api/u1-legal-documents.md`, ou a um checklist próprio de privacidade em `docs/`:
- banco novo na abertura;
- a chave HMAC definida no gerenciador de segredos;
- duas consultas de conferência que procuram endereços Base58 completos em `audit_event.details` e no payload de `audit_outbox` e precisam voltar vazias.

(recomendado)
B. Só a linha "banco novo".
X. Other (please specify)

[Answer]: A

## Q10 — Dados que já existem

A. Nenhuma migração reescreve eventos antigos: a trilha continua sem edição. Os dados de desenvolvimento e teste são descartados antes da abertura (FR3.3), e o teste de busca só olha eventos gravados depois da mudança. (recomendado)
B. Uma migração que mascara os eventos antigos.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

A U2 grava na auditoria só a carteira mascarada e um HMAC-SHA-256 numa coluna e num campo próprios. O publicador recebe a carteira por um campo dedicado, e o validador recusa endereço completo em `details`. A chave é obrigatória, com 32 bytes ou mais.

Dois expurgos diários novos, no padrão do `FeedbackPurgeJob`, com saúde `DEGRADED` depois de 48 h sem sucesso:
- **desafios:** 24 h depois de vencerem;
- **sessões e digests:** 30 dias depois do fim (revogação ou o menor vencimento).

O `RetentionCatalog` do módulo novo `privacy` é a fonte da tabela pública (J5). Ele é conferido por teste contra os expurgos e contra o `retention.tsv` da v2. Os pedidos de titular e a anonimização entram como `PLANNED` numa lista de pendências.

O checklist de abertura ganha o banco novo, a chave e as consultas de conferência. Nenhum evento antigo é reescrito.

- Looks correct
- Request changes

[Answer]: Looks correct
