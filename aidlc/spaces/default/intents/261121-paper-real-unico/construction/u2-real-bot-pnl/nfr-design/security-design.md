# Desenho de segurança — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

A U2 não cria rota, tela nem caminho de assinatura. Ela muda a leitura do P&L REAL, cria a tabela `real_entry_slot` e grava o slot em três confirmações. Este arquivo diz como o isolamento entre contas, a proteção do ledger, a auditoria sem dado pessoal e a separação dos modos continuam valendo [Q3] [Q5], e resolve a revisão R-09 (limite da auditoria com várias instâncias).

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Tenant e pessoa | Vêm do token, viram parâmetros das leituras; toda consulta filtra por tenant e pessoa ou por carteiras já filtradas. A leitura de custo em segundo plano usa o tenant da ordem. | NFR3.1, NFR3.2, NFR3.3 |
| `real_entry_slot` | Só inserção, com os gatilhos e as permissões da C14; sem `tenant_id`; lida só pela junção com `real_ledger_entry`. | NFR3.4, NFR3.5, NFR3.6 |
| `REAL_SLOT_CONFLICT` | Assinatura abreviada (4 … 4) e HMAC dela, calculado pelo `WalletHasher` com a chave `memetrade.audit.wallet-hmac-key` e a etiqueta de domínio `"real-tx-signature:"`. A coluna `audit_event.tx_signature` fica vazia nesse evento. | NFR3.9, D-NFR2 |
| Limite do evento de divergência | Consulta à própria auditoria (outbox pendente e `audit_event`) dentro de uma transação própria, num pool de 2 conexões com espera limitada a 250 ms, com trava consultiva por pessoa e impressão digital. Sem estado em memória, sem tabela nova. | NFR3.15, NFR3.16 |
| Respostas | Bloco `UNAVAILABLE` com `status`, `code` e `correlationId` só; as diferenças ficam no evento. | NFR3.8 |
| Atribuição falhando | Falha fechada para a compra; nunca bloqueia venda, liquidação nem saque. | NFR3.11 |
| Modos | Tipos novos na lista fechada de NEVER_SUM_MODES no mesmo commit; nenhuma leitura do ledger PAPER. | NFR4.1 a NFR4.3 |

## Isolamento entre contas (NFR3.1 a NFR3.3)

- **Origem do tenant.** O `Principal` do pedido dá o tenant e a pessoa ao bloco REAL, como na U1 (o bloco recebe o `Principal` como parâmetro e nunca lê o contexto de autenticação da thread do pool). Nenhum corpo de requisição traz tenant.
- **Filtros por leitura.**

| Leitura | Filtro |
|---|---|
| `botsOf` (C8) | `tenant_id` e `owner_user_id` |
| carteiras do bot (`botwallet.api`) | tenant e pessoa |
| `streamSettledOrders` (C13) | `tenant_id` e `user_id` |
| `streamInNetworkOrder` (C12) | `tenant_id` **e** as carteiras do passo anterior; o filtro de tenant fica no SQL mesmo com as carteiras já filtradas (defesa em profundidade) |
| `real_entry_slot` | nunca lida sozinha; só pela junção com as linhas de `real_ledger_entry` já filtradas |
| leitura de custo da barreira e do ciclo | o tenant e a pessoa da ordem (`OrderIntent`) ou do bot (`bot.scope()`), nunca de outro lugar |

- **Bot de outra pessoa.** `botPnl` confere primeiro que o bot é bot REAL da pessoa no diretório (C8); se não é, devolve vazio e a rota responde 404 sem campo do bot. Em C4, só os bots do diretório da pessoa viram linhas.
- **`UNKNOWN_BOT` (BR3.4).** Uma ordem da pessoa com um `bot_id` fora do diretório dela é falha de integridade: o bloco REAL sai `UNAVAILABLE` com `RECONCILIATION_MISMATCH`, o evento leva só a verificação `UNKNOWN_BOT` e o id da ordem (uuid da própria pessoa), nunca o id nem o nome do bot alheio. A venda, a liquidação e o saque usam o caminho só de quantidade e não são afetados.
- **Testes.** `RealPnlTenantIsolationIT`: duas pessoas de tenants diferentes operam o mesmo mint no mesmo slot; o P&L, a conferência e o evento de cada uma não contêm nada da outra. `AttributedCostTenantIT`: a leitura de custo chamada pela barreira e pelo `RealCycle` usa o tenant da ordem e não lê nada de outro tenant. As regras ArchUnit de tenant do módulo continuam verdes.

## Proteção do ledger e da tabela lateral (NFR3.4 a NFR3.6)

A migração `db/changelog/changes/0023-real-entry-slot.sql`, incluída em `db.changelog-master.yaml`, tem quatro changesets, todos só de criação:

| Changeset | Conteúdo |
|---|---|
| `0023-real-entry-slot` | `CREATE TABLE real_entry_slot` como na C14 (`tx_signature varchar(90)` chave primária, `slot bigint not null` com `CHECK (slot >= 0)`, `recorded_at timestamptz not null`). Rollback: `DROP TABLE`, só com a tabela vazia, nos ambientes locais. |
| `0023-real-entry-slot-append-only` | `real_entry_slot_no_update_delete` (`BEFORE UPDATE OR DELETE`, por linha) e `real_entry_slot_no_truncate` (`BEFORE TRUNCATE`), com a função `ledger_reject_change` da `0008` e da `0016`. |
| `0023-real-entry-slot-grants` | Para o papel da aplicação: `REVOKE ALL ON real_entry_slot`, depois `GRANT SELECT, INSERT`. `REVOKE TRUNCATE` do dono da migração, como na `0019`. |
| `0023-real-ledger-entry-wallet-created-idx` | `DROP INDEX CONCURRENTLY IF EXISTS` e `CREATE INDEX CONCURRENTLY real_ledger_entry_wallet_created_idx ON real_ledger_entry (bot_wallet_id, created_at)`, com `runInTransaction:false` (alinhamento A15; ver `performance-design.md` e a seção 9 do runbook). |

Nenhum `ALTER TABLE`, `UPDATE`, `DELETE` ou `INSERT` em tabela existente. O gatilho de `real_ledger_entry` não muda (C14). Testes: `RealEntrySlotAppendOnlyIT` (UPDATE, DELETE e TRUNCATE falham em 3 de 3, a linha continua igual), `RealEntrySlotGrantsIT` (com o login da aplicação, SELECT e INSERT funcionam; UPDATE, DELETE e TRUNCATE são recusados) e `Migration0023AdditiveIT` (contagem de linhas de `real_ledger_entry` e `real_order` antes e depois: diferença 0; o texto da migração não tem `ALTER`, `UPDATE`, `DELETE` nem `INSERT`).

**`recordSlot` (C13).** `INSERT ... ON CONFLICT (tx_signature) DO NOTHING` na transação do gravador. Sem linha inserida, um `SELECT` pela chave primária lê o slot gravado e decide entre `ALREADY_RECORDED` e `CONFLICT_REFUSED`. Nenhum caminho faz `UPDATE`. Só o `ledger` grava a tabela; os três gravadores chamam a porta `ledger.api.RealLedger.recordSlot`.

## Auditoria

### `REAL_SLOT_CONFLICT` com assinatura abreviada e HMAC (NFR3.9, D-NFR2)

O mecanismo de hoje grava a assinatura completa em `audit_event.tx_signature` quando o evento usa `RealReferences.transaction(...)`. O evento de conflito **não** usa esse campo. O desenho:

- `audit.api.RealReferences` ganha um campo novo, só em memória, para uma assinatura que deve sair abreviada e com HMAC (nome proposto: `hashedTxSignature`, com a fábrica `RealReferences.hashedTransaction(String)`). Ele segue o mesmo padrão do campo `wallet` do `AuditEventCommand`: nunca é serializado nem impresso.
- O `JdbcAuditEventPublisher` troca esse campo, antes de gravar o outbox, por duas entradas em `details`:
  - `txSignatureAbbrev`: os 4 primeiros caracteres, "…" e os 4 últimos, pela mesma forma de `WalletDisplay.abbreviate`;
  - `txSignatureHmac`: o HMAC-SHA-256 da assinatura em hexadecimal minúsculo de 64 caracteres.
- O `WalletHasher` ganha o método `hmacOfTxSignature(String)`, com a **mesma chave** e o mesmo algoritmo de `hmac`. O `hmac` de hoje recusa qualquer texto que não seja um endereço de carteira (BR2.15 do u2-retention), então ele não serve para a assinatura sem mudança. O método novo aceita só uma assinatura Base58 de 64 bytes (a mesma conferência que o `AuditEventValidator` faz em `real.txSignature`) e recusa qualquer outro texto sem ecoá-lo.
- **Etiqueta de domínio (revisão R-06).** A mensagem do HMAC é a constante `"real-tx-signature:"` seguida da assinatura, em UTF-8: `HMAC-SHA-256(chave, "real-tx-signature:" + assinatura)`. O `hmac` das carteiras continua sem prefixo, para os HMAC já gravados continuarem válidos. Com a etiqueta, as duas famílias de HMAC ficam separadas mesmo que um formato futuro de entrada se pareça com o outro. Uma rotação da chave continua mudando as duas famílias juntas. O `WalletHasherTest` confere que o HMAC de uma assinatura é o da mensagem com a etiqueta e difere do HMAC do mesmo texto sem ela.
- O `AuditEventValidator` continua recusando a chave `signature` em `details`; as chaves novas não colidem com a lista `FORBIDDEN_KEYS`. O valor do HMAC (64 caracteres hexadecimais) não é candidato a carteira, e a forma abreviada também não.
- O evento leva: tenant, `actor = system`, `action = REAL_SLOT_CONFLICT`, `correlationId` da confirmação, e em `details` o slot gravado, o slot recusado, o gravador (`ORDER_CONFIRMATION`, `CONDITIONAL_FILL` ou `WALLET_CONFIRMATION`), `txSignatureAbbrev` e `txSignatureHmac`. `audit_event.tx_signature`, `bot_wallet_address` e `wallet_hmac` ficam nulos.
- O evento é gravado na mesma transação da confirmação, pelo outbox, como todo evento (C13: a confirmação nunca falha por causa do slot).

Retenção e anonimização: as que já existem para `audit_event`. A anonimização procura os eventos pelo `wallet_hmac` e pela pessoa; o HMAC da assinatura não é endereço de carteira e sem a chave não identifica ninguém. O fundador acha a linha do ledger calculando o HMAC da assinatura que ele já tem.

Teste: `SlotConflictAuditIT` confere que o evento guarda a forma abreviada e um HMAC igual ao que o `WalletHasher.hmacOfTxSignature` calcula para a assinatura semeada, e que a assinatura completa não aparece em nenhuma coluna de `audit_outbox` nem de `audit_event`.

### `REAL_PNL_RECONCILIATION_MISMATCH` com limite de 1 por hora (NFR3.15, revisão R-09) [Q3]

**Impressão digital.** SHA-256, em hexadecimal, do texto canônico `<userId>|<verificações que falharam em ordem alfabética>`: a pessoa e os tipos de verificação, sem nenhum valor e sem a janela (decisão do fundador de 2026-11-23, revisão R-05 da Code Generation). Ela vai em `details.fingerprint`. Uma divergência cujo valor muda a cada leitura tem a mesma impressão digital e continua sendo 1 evento por hora; só outro tipo de verificação grava outro evento na mesma hora. A janela e as diferenças em lamports ficam nos detalhes do evento.

**Log da divergência.** O contador `pnl_real_reconciliation_mismatches_total{check}` sobe em toda leitura (o alarme não muda). O log `ERROR` `REAL_PNL_RECONCILIATION_MISMATCH` sai no máximo 1 vez por minuto por pessoa e tipo de verificação, com um mapa em memória de no máximo 10.000 chaves, das quais as vencidas saem primeiro.

**Conexão com espera limitada (revisão R-07).** A gravação não usa o pool principal da API, que pode estar cheio justamente quando há divergência e carga. Ela usa um pool próprio e pequeno (nome proposto `pnl-mismatch-audit`): HikariCP com 2 conexões, `minimum-idle` 2 (sempre abertas) e `connection-timeout` de 250 ms, o mínimo que o HikariCP aceita. A espera pela conexão fica limitada a 250 ms nesse pool, e o pool principal (20 conexões) não muda. O PostgreSQL recebe 2 conexões a mais da API.

**Fiação do pool (revisão 02, R-10).** O pool fica inteiro dentro de `audit.infra`, no padrão do `AuditRetentionDataSource`:

- `MismatchAuditDataSource` (nome proposto) embrulha o `HikariDataSource` do pool e **não** é um bean do tipo `DataSource` nem `PlatformTransactionManager`, para o Spring Boot continuar configurando o pool principal e o gerenciador principal sem ambiguidade. Ele cria, ligados ao mesmo `HikariDataSource`, um `DataSourceTransactionManager` próprio, um `TransactionTemplate` sobre ele e um `JdbcClient` próprio (`JdbcClient.create(pool)`). Uma regra ArchUnit permite só o `MismatchAuditWriter` depender dele, como já faz com o pool da retenção.
- `MismatchAuditWriter` (nome proposto, `audit.infra`) é um publicador **dedicado** a este evento, exposto ao `pnl` pela interface `audit.api.RateLimitedAuditPublisher.publishOncePerHour(command, fingerprint, deadline)`, que devolve `WRITTEN`, `DEDUPLICATED`, `LOCK_BUSY` ou uma falha. Ele roda, **numa só transação do `TransactionTemplate` do pool próprio**, a trava consultiva, a consulta de recência e o `insert` no `audit_outbox`, todos pelo `JdbcClient` desse pool. Como o `JdbcClient` e o gerenciador usam o mesmo `HikariDataSource`, o Spring liga a conexão da transação a esse `DataSource` e os três comandos rodam nela: a trava só sai no commit, depois do `insert`.
- Ele reusa o `AuditEventValidator`, o `AuditPayloadCodec` e o contador `audit_events_published` (registrado no `afterCommit` da transação própria), e **não** chama o `JdbcAuditEventPublisher`. O `JdbcAuditEventPublisher` não muda e continua ligado ao pool principal; nada desta gravação cai no pool principal nem em autocommit. A conferência `isActualTransactionActive()` do publicador comum não é usada aqui; o escritor dedicado confere que a conexão da transação ativa é a do próprio pool (`TransactionSynchronizationManager.hasResource(pool)`) e falha antes de escrever se não for.
- A interface de recência `AuditRecency` deixa de ser pública: a consulta é parte do escritor dedicado.

Para a gravação nunca passar do tempo, ela roda numa tarefa de um executor próprio (2 threads, fila de 8, recusa imediata com a fila cheia), e quem a dispara espera o resultado por no máximo 200 ms (`write-timeout`). Desde a revisão R-01 (decisão do fundador de 2026-11-23), quem dispara é a thread do bloco depois que a transação de leitura terminou e a resposta do bloco já foi dada (`AfterBlockRelease` no `ModeBlockExecutor`), ou o próprio `RealBotPnl` quando a leitura foi dona da transação e ela já fechou; a espera não atrasa a resposta e não segura conexão do pool principal. Se o tempo acaba antes, a thread do bloco marca a tarefa como vencida e segue para o passo 7 como falha; a tarefa, ao obter a conexão e de novo antes de publicar, confere a marca e desiste sem gravar. Uma tarefa que já gravou quando a marca chega deixa o evento gravado (o limite de 1 por hora continua valendo) e a leitura conta uma falha a mais, o lado conservador.

**Sequência.** O passo 7 (contador, log limitado e `RealPnlUnavailable(RECONCILIATION_MISMATCH)`) acontece na leitura; os passos 1 a 6 rodam depois que a transação de leitura do bloco REAL fechou e a conexão voltou ao pool principal (revisão R-01):

1. O tempo da gravação é `write-timeout` (200 ms). Com a fila do executor cheia, conta como falha.
2. A tarefa obtém a conexão do pool `pnl-mismatch-audit` (espera de no máximo 250 ms), **confere o instante limite de novo** logo depois de abrir a transação nova de escrita (READ COMMITTED) e desiste, devolvendo a conexão, se ele passou ou se a tarefa foi marcada como vencida. Com tempo, aplica `set local statement_timeout` igual ao que resta.
3. Tenta a trava consultiva da transação: `pg_try_advisory_xact_lock(<espaço PNLM>, hashtext(<userId> || ':' || <fingerprint>))`. O espaço é uma constante nova do `pnl` (`0x504E4C4D`, "PNLM" em ASCII), diferente do da `WalletLock` (`0x55335253`). É uma tentativa, nunca uma espera: se outra instância está gravando o mesmo evento agora, esta não grava e vai ao passo 6.
4. Com a trava, o escritor dedicado pergunta à auditoria, na mesma transação e pelo `JdbcClient` do pool próprio, se já existe um evento igual na última hora. É **um** comando SQL, então vê uma fotografia só:
   - em `audit_outbox`, as linhas ainda não entregues (`delivered_at is null`), pelo índice parcial `audit_outbox_pending_idx (created_at)`, com ação, ator, tenant e impressão digital lidos do `payload`;
   - em `audit_event`, pelo índice `audit_event_action_time_id_idx (action, occurred_at DESC, event_id DESC)`, com `occurred_at` na última hora e filtro residual por `tenant_id`, `actor` (o uuid da pessoa) e `details->>'fingerprint'`.

   As duas partes são necessárias porque o evento passa pelo outbox antes de chegar a `audit_event`, e o relay entrega as duas mudanças numa transação só: num comando único, o evento está em uma das duas.
5. Se não existe, insere o evento no `audit_outbox` pela mesma conexão e confirma a transação. A trava sai com ela, depois do `insert`.
6. Se existe (ou a trava estava com outra instância), confirma a transação vazia.
7. Em qualquer caso, na própria leitura, incrementa `pnl_real_reconciliation_mismatches_total{check}` uma vez por verificação que falhou, registra o log limitado e lança `RealPnlUnavailable(RECONCILIATION_MISMATCH)` (a `ReconciliationMismatch`, que leva a gravação dos passos 1 a 6 para depois da transação).

**Falha da gravação.** Erro, espera pela conexão esgotada (250 ms), prazo de 200 ms, falta de tempo do bloco, fila do executor cheia ou tarefa vencida: nada é gravado (a transação é desfeita ou nem abre), o log `ERROR` leva o `correlationId` e a categoria (`CONNECTION_TIMEOUT`, `DEADLINE`, `REJECTED` ou `ERROR`), `pnl_real_reconciliation_audit_failures_total` sobe 1 e o bloco sai `UNAVAILABLE` com `RECONCILIATION_MISMATCH` do mesmo jeito. A resposta nunca muda por causa da auditoria.

**Por que não há índice novo.** A resposta Q3 citou "o índice que já existe na auditoria (pessoa e ação)". Não existe índice por pessoa em `audit_event`; o que serve é `audit_event_action_time_id_idx`, pela ação e pelo instante. Como o próprio limite deixa no máximo um evento por pessoa, impressão digital e hora, a faixa da última hora dessa ação tem poucas linhas, e o filtro por pessoa e impressão digital é residual e barato. No outbox, as linhas pendentes são poucas (o relay entrega em segundos).

**Por que a trava.** Duas instâncias da API que leem a mesma divergência ao mesmo tempo passariam as duas pela consulta e gravariam dois eventos. A trava por pessoa e impressão digital serializa só esse caso. Uma colisão de `hashtext` entre duas impressões digitais diferentes faz no máximo uma delas pular a gravação naquela leitura; a próxima leitura grava.

**Por que não é estado entre leituras.** O limite lê o que a auditoria já tem. Nada é guardado na memória nem numa tabela de P&L (ADR-004). Uma API reiniciada não perde o limite.

**Fronteira de módulo.** O `pnl` nunca lê as tabelas da auditoria nem conhece o pool; ele usa `audit.api.RateLimitedAuditPublisher`. A regra ArchUnit do `audit` ganha a interface nova e a regra do pool próprio.

Teste `ReconciliationAuditLimitIT`: 10 leituras seguidas com a mesma divergência gravam 1 evento e sobem o contador 10 vezes; uma divergência diferente grava 1 evento a mais; duas threads com conexões próprias, ao mesmo tempo, gravam 1 evento; com a publicação falhando, as 10 respostas são iguais e `pnl_real_reconciliation_audit_failures_total` sobe a cada tentativa; com as 2 conexões do pool `pnl-mismatch-audit` presas pelo teste, a leitura responde dentro do prazo do bloco, a gravação desiste em até 250 ms, nada é gravado e o contador de falhas sobe 1; com as 20 conexões do **pool principal** presas pelo teste, o evento ainda é gravado pelo pool próprio; com duas instâncias em paralelo, o `pg_backend_pid()` da trava e o do `insert` são o mesmo (um gancho de teste do escritor devolve os dois) e só um evento é gravado.

## Respostas e logs (NFR3.7, NFR3.8)

- **Respostas.** O bloco `UNAVAILABLE` de C4 e o erro do `GET /api/v1/real/pnl` levam só `status`, `code` e `correlationId`. As diferenças em lamports, as verificações e o mint da verificação que falhou ficam só no evento. Os esquemas de C4 e de `GET /api/v1/real/pnl` não têm campo de endereço nem de assinatura (teste de contrato).
- **Logs.** Nenhum log da U2 leva endereço de carteira nem assinatura completa. A assinatura aparece só no log do conflito de slot, abreviada por `WalletDisplay.abbreviate`. Os nomes e níveis dos registros estão em `observability-design.md`. Teste `U2LogCaptureTest` com as assinaturas e endereços semeados.

## Caminhos de risco do REAL (NFR3.10, NFR3.11)

- **Sem assinatura nova.** `recordSlot` roda só nas três confirmações que já existem e não chama o TxSigner nem a rede. A leitura nunca busca o slot na rede (BR1.4, NFR8.13). O teste obrigatório "REAL desligado por padrão" continua verde sem mudança.
- **Falha fechada para a compra.** A leitura de custo com atribuição falhando (erro, `TIMEOUT`, `UNKNOWN_BOT`, `INCONSISTENT_READ`) devolve `RealCost.Unknown`, nunca 0 nem `null` (decisão F-3 do fundador, `performance-design.md`), e a posição sem preço fica sem valor. A compra é recusada (`POSITION_PRICE_UNKNOWN` ou perda diária não avaliada como cumprida) e conta `real_risk_errors_total{dependency="attribution"}`. A venda, a liquidação e o saque nunca leem a atribuição. O mecanismo (ponto de salvamento e transação utilizável) está em `reliability-design.md`. Teste `AttributionFailureGateIT`, com os três casos de falha: compra recusada, venda, liquidação e saque aceitos (3 de 3).

## Separação dos modos (NFR4.1 a NFR4.3)

- Os tipos novos de resultado REAL (`NetworkOrderedEntry`, `NetworkOrderKey`, `SlotStatus`, `RecordSlotResult`, `ReconciliationResult`, `RealCost`, `WalletCost` (o resultado de `RealWalletCost`) e os de `RealCostBasis` e `RealProration`) entram na lista fechada de `NeverSumModesArchitectureTest` no mesmo commit em que são criados.
- Regra ArchUnit nova: as classes do `ledger.infra` e do `pnl` do REAL não dependem de tipos do ledger PAPER (`ledger_entry`, `LedgerReadModel`). `real_entry_slot` só é casada com `real_ledger_entry`.
- `RealNeverPaperIT`: com ordens nos dois modos, `streamInNetworkOrder` entrega 0 lançamentos PAPER e a conferência REAL não muda quando se acrescentam lançamentos PAPER. Os testes da U1 de nunca somar os modos continuam verdes com o bloco REAL calculado pela U2.

## Sources

- `nfr-design-questions.md`: Q3 e Q5 e o resumo confirmado [Q3] [Q5].
- `reviews/review-01.md` (R-02, R-06, R-07), `reviews/review-02.md` (R-10) e as decisões do fundador de 2026-11-22 (F-3, em `performance-design.md`).
- Código lido também: `audit/infra/AuditRetentionDataSource.java` e `AuditRetentionDataSourceConfig.java` (pool próprio que não é bean `DataSource`).
- `construction/u2-real-bot-pnl/nfr-requirements/security-requirements.md`: NFR3.1 a NFR3.11, NFR3.15, NFR4.1 a NFR4.3, D-NFR2; `observability-requirements.md`: NFR3.16; `reviews/review-02.md`: R-09.
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR1.4, BR1.5, BR2.7, BR3.4, BR8.6, BR8.7, BR10.2, BR10.5; `entities.md`: ENT-202, ENT-214, ENT-215; `functional-spec.md`: F4, F5, F6.2, F7.
- `inception/contract-design/contract-summary.md`: C8, C10, C12, C13, C14.
- `construction/u1-mode-skeleton/nfr-design/security-design.md` (forma) e `performance-design.md` (o bloco recebe o `Principal`).
- Código lido: `application.yml` (Hikari com `connection-timeout` de 2 s), `audit/infra/WalletHasher.java` (`hmac` aceita só endereço de carteira), `audit/infra/JdbcAuditEventPublisher.java` (outbox, `walletHmac`), `audit/infra/AuditOutboxRelay.java` (colunas de `audit_event`), `audit/api/RealReferences.java`, `audit/api/AuditEventCommand.java`, `audit/domain/AuditEventValidator.java` (`FORBIDDEN_KEYS`, conferência de 64 bytes), `shared/wallet/WalletDisplay.java`, `riskcontrol/real/infra/WalletLock.java` (espaço da trava), `db/changelog/changes/0004-audit.sql`, `0005-audit-trail.sql`, `0012-retention.sql`, `0016-real-bot-wallet.sql`, `0019-real-execution.sql`, `0021-real-operations.sql`.
- `aidlc/spaces/default/memory/project.md`: Forbidden e Mandated; `team.md`: Testing Posture (testes obrigatórios por risco), Code Style (tenant no código, ArchUnit).

## Assumptions & Open Questions

1. [assumption] O HMAC da assinatura usa a mesma chave dos endereços, como a D-NFR2 pede ("pelo mecanismo que a auditoria já usa"). A etiqueta de domínio `"real-tx-signature:"` separa as duas famílias de HMAC; o HMAC das carteiras fica sem etiqueta, para os valores já gravados continuarem válidos. Uma rotação da chave muda as duas famílias juntas, como hoje.
2. [assumption] Os nomes `hashedTxSignature`, `hmacOfTxSignature`, `MismatchAuditDataSource`, `MismatchAuditWriter`, `RateLimitedAuditPublisher` e as chaves `txSignatureAbbrev`, `txSignatureHmac` e `fingerprint` são propostas deste estágio; a Code Generation pode trocá-los mantendo o comportamento.
3. **Conflito com o texto da Q3:** a auditoria não tem índice por pessoa e passa por um outbox. O desenho mantém a escolha da Q3 (consulta à própria auditoria, trava consultiva, nada em memória, nenhuma tabela nova) e corrige só o índice e o alcance da consulta (outbox pendente mais `audit_event`).
4. **Conflito com o `WalletHasher` de hoje:** o NFR3.9 pede "um HMAC igual ao que o `WalletHasher` calcula para a assinatura", mas o `hmac` recusa texto que não seja endereço. O método novo com a mesma chave resolve sem afrouxar a regra BR2.15 do `hmac`.
5. O ENT-215 do Functional Design ainda descreve a assinatura completa; ele é alinhado na Code Generation (lista em `logical-components.md`).
