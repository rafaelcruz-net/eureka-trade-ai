# Desenho de segurança — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` e das decisões do fundador de 2026-11-23 (F-1 a F-3, abaixo).

A superfície nova da U3 é só de leitura: C4 com `market` e `RANGE`, `listBots` com `market` e contagens, C5 e C6. Nenhuma rota movimenta fundos, assina ou envia transação. O modelo de ameaças não muda. Este arquivo diz como o tenant é aplicado, como os 404 ficam iguais em corpo e em tempo, como as recusas são auditadas sem atrasar a resposta [Q2] [fundador D-NFR1] e como os modos continuam separados.

## Decisões do fundador (2026-11-23, depois da revisão 01)

Tomadas depois de `reviews/review-01.md`. Não podem ser mudadas na Code Generation.

- **F-1. Fila da auditoria (R-01).** Nos dois 404 (uuid inexistente e bot de outra pessoa), a consulta `BotDirectory.ownedByAnotherTenant` roda na thread do pedido, antes da resposta. É a mesma consulta nos dois casos, o que mantém a paridade de tempo. Só um resultado positivo entrega a tarefa ao executor `ReadRefusalAudit`. O log `ERROR` de uma auditoria descartada ou que falhou sai no máximo 1 vez por minuto por pessoa e por ação. Com isso, `pnl_read_audit_failures_total{action="TENANT_MISMATCH"}` só conta casos reais de outro tenant. **Isso muda o que a Q2 e o NFR3.17 descreviam** (a classificação depois da resposta, dentro da tarefa): a classificação passa para antes da resposta, e só o envio ao executor fica depois. A regra de NFR3.17 (os dois 404 seguem o mesmo caminho e não se distinguem pelo tempo) continua e é medida pelo mesmo teste.
- **F-2. Exceção ao filtro de tenant (R-05).** `ownedByAnotherTenant` é a única consulta autorizada a olhar fora do tenant de quem pede. Ela devolve só um booleano, nunca um dado do bot, e uma regra ArchUnit deixa só o caminho dos 404 da U3 chamá-la. A exceção fica escrita aqui e na nota de alinhamento do contrato C8 (`logical-components.md`). A regra do `team.md` ("toda consulta de dados de cliente filtra pelo tenant") e o NFR3.1 recebem a exceção anotada depois, pelo ritual de aprendizado; este estágio não edita o `team.md`.
- **F-3. Reducer novo de `/pnl` (R-06).** Ver `performance-design.md`.

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Tenant e pessoa | Só do `Principal` do token; toda consulta filtra por tenant e pessoa, com uma única exceção registrada (F-2) | NFR3.1, NFR3.5 |
| 404 de C5 e C6 | Um caminho de código só até a resposta: a mesma consulta filtrada pelo tenant, a mesma consulta `ownedByAnotherTenant`, o mesmo corpo | NFR3.2, NFR3.3, NFR3.17 |
| Ordem das verificações | 400 (sem banco) → posse dos dois bots → 404 → estratégia → 422 | NFR3.3, NFR3.6 |
| Auditoria das recusas | Só um 404 confirmado de outro tenant ou um 422 vão ao executor `pnl-read-audit` (1 thread, fila de 16, recusa na hora com a fila cheia), depois de um filtro em memória por pessoa e ação; limite de 1 evento por hora pelo `RateLimitedAuditPublisher` da U2 | NFR3.13, NFR3.14 |
| Fila cheia ou falha | A resposta não muda; log `ERROR` no máximo 1 vez por minuto por pessoa e ação; `pnl_read_audit_failures_total{action}` em toda falha | NFR3.14, NFR3.16 |
| Dados pessoais | Cursor de C5 sem assinatura nem endereço; eventos e logs só com ids e enums | NFR3.7 |
| Modos | Nenhum total geral; chave do bloco não pedido ausente; cada modo na própria moeda | NFR4.1 a NFR4.5 |
| Assinatura | 0 chamadas novas ao TxSigner ou ao KMS | NFR3.8 |

## Autenticação e autorização (NFR3.1, NFR3.4, NFR3.5)

- **Origem do tenant.** O controlador lê o `Principal` de `AuthContext.current()` e passa `tenantId` e `userId` como parâmetros. As tarefas do pool `pnl-mode-block` e do executor `pnl-read-audit` recebem esses valores capturados na thread do pedido; nenhuma delas lê o contexto de autenticação da própria thread.
- **Parâmetros aceitos.** Cada rota usa `QueryParameters.of(parameters, <lista fechada>)`, como hoje: C4 `{period, from, to, market}`, `listBots` `{state, market, cursor, limit}`, C5 `{cursor, limit}`, C6 `{paperBotId, realBotId, period, from, to}`. Um `tenantId` ou `accountId` na consulta é recusado com 400 pelo mesmo mecanismo; o teste de NFR3.1 aceita esse 400.
- **Filtros.**

| Leitura | Filtro |
|---|---|
| `BotDirectory.botOf` e `botsOf` (C8) | `tenant_id` e `owner_user_id` |
| página e contagens de `listBots` | `tenant_id` e `owner_user_id` em cada uma das três consultas |
| passada REAL de C5 e do lado REAL de C6 | os filtros da U2 (C8, C12, C13), sem mudança |
| lado PAPER de C6 e bloco PAPER com `RANGE` | o filtro de `PnlQueryService`, sem mudança |
| `BotDirectory.ownedByAnotherTenant` (C8) | **exceção única (F-2):** `select exists (select 1 from bot where bot_id = :id and tenant_id <> :tenant)`; devolve só `boolean` |

- **Login e termos.** As 4 rotas ficam fora da lista pública e fora da exceção do novo aceite (leitura da carteira do bot e saque): sem token, 401; com termos desatualizados, 403 `TERMS_REACCEPTANCE_REQUIRED`. Nenhuma configuração do filtro de termos muda; o teste confere as 4 rotas.
- **Contagens.** `matchingCount` e `totalCount` contam só os bots da pessoa do token; `PnlBotCountsTenantIT` cria bots na pessoa B e confere que as contagens da pessoa A não mudam e que `matchingCount ≤ totalCount`.

## Exceção ao filtro de tenant (F-2)

| Item | Regra |
|---|---|
| Método | `boolean ownedByAnotherTenant(UUID tenantId, UUID botId)` em `pnl.api.BotDirectory` (C8), implementado no `bots` |
| O que devolve | só `true` ou `false`: o id que a própria pessoa enviou existe em outro tenant. Nunca o tenant, o dono, o nome, o modo, o `templateId` ou outro campo |
| Quem chama | só o caminho dos 404 de C5 e C6 no `PnlController` (método privado `refuseNotFound`). Regra ArchUnit nova `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS`: nenhuma outra classe depende desse método |
| Consulta | pela chave primária de `bot`, na transação só de leitura do pedido, no pool principal |
| Pendência | o NFR3.1 e a regra do `team.md` (Code Style, "Tenant no código") ganham a exceção anotada pelo ritual de aprendizado; este estágio não edita o `team.md` |

## Ordem das verificações e corpo dos erros (NFR3.2, NFR3.3, NFR3.6)

```text
C5: parse(botId, limit, cursor) -> 400
    bot = directory.botOf(tenant, pessoa, botId) filtrado por REAL
    se vazio: refuseNotFound(pessoa, BOT_PNL_LINES, side vazio, botId)              // 404
    passada no pool -> 200 | 503

C6: parse(paperBotId, realBotId, período) -> 400
    paper = directory.botOf(tenant, pessoa, paperBotId) filtrado por PAPER             // sempre
    real  = directory.botOf(tenant, pessoa, realBotId)  filtrado por REAL              // sempre, sem atalho
    se paper vazio: refuseNotFound(pessoa, PNL_COMPARE, PAPER_BOT, paperBotId)       // 404 PAPER_BOT
    se real vazio:  refuseNotFound(pessoa, PNL_COMPARE, REAL_BOT, realBotId)         // 404 REAL_BOT
    se templateId difere: compareMetrics.templateMismatch(); readAudit.templateMismatch(pessoa, ids); 422
    lados no pool -> 200

refuseNotFound(pessoa, rota, lado, botId):
    outro = directory.ownedByAnotherTenant(tenant, botId)   // sempre, nos dois casos
    log INFO PNL_READ_NOT_FOUND reason=(outro ? TENANT_MISMATCH : NOT_FOUND)
    se outro: readAudit.tenantMismatch(pessoa, rota, lado, botId)   // só envia, nunca espera
    throw NotFound(lado)
```

- **Um 404 só.** O corpo é o `ProblemDetail` de `ErrorCode.NOT_FOUND`, igual nos 3 casos de C5 (outro tenant, uuid inexistente, bot PAPER da pessoa) e nos casos de C6, com `reason` em C6. Nenhum nome, modo, `templateId`, linha ou valor. Os bytes diferem só no `correlationId`.
- **404 antes de 422.** A estratégia só é comparada quando os dois bots são da pessoa; um 422 nunca confirma a existência nem a estratégia de um bot de outra pessoa. O corpo do 422 não traz valor de P&L.
- **400 sem banco.** `market` fora de `PAPER` e `REAL`, `cursor` malformado ou de outro bot, `limit` fora de 1 a 100, período incoerente ou acima de 366 dias, id que não é uuid: tudo é recusado antes de qualquer consulta.

## Caminho único dos dois 404 e paridade de tempo (NFR3.17, F-1)

1. Os dois casos (bot de outro tenant e uuid inexistente; em C5 também o bot PAPER da pessoa; em C6 também o bot da pessoa no modo errado) seguem o mesmo código até a resposta: a mesma consulta `botOf` filtrada pelo tenant, que devolve vazio, e **a mesma** consulta `ownedByAnotherTenant` pela chave primária, que devolve `true` ou `false`.
2. Em C6, a consulta roda uma vez, para o lado que decide o `reason`; o número de consultas é o mesmo em todos os 404.
3. Só um `true` chama `readAudit.tenantMismatch(...)`, e essa chamada só faz o filtro em memória e `executor.execute(tarefa)`: nenhuma consulta e nenhuma espera. Com a fila cheia, `execute` recusa na hora; a recusa é contada e registrada sem mudar a resposta.

O custo que diferencia os casos fica fora do tempo da resposta: um `execute` não bloqueante, ou nada. Antes da F-1, a Q2 e o NFR3.17 descreviam a verificação de existência depois da resposta, dentro da tarefa; a revisão 01 (R-01) mostrou que assim uma rajada de 404 com uuid aleatório enchia a fila e apagava o evento real. A paridade é a mesma de antes; muda só o lugar da verificação.

### Testes

- **`BotNotFoundTimingParityIT`** (Failsafe, Testcontainers, método de NFR1.5): duas pessoas em tenants diferentes; a pessoa B tem um bot REAL e um PAPER. A pessoa A pede, intercalando, o bot de B e um uuid aleatório novo a cada pedido: 10 de aquecimento e 100 medidos por caso, em C5 e em C6 (lado PAPER e lado REAL). p95 pelo posto mais próximo. Falha se `|p95(outro tenant) − p95(inexistente)| > max(5 ms, 10% do maior p95)`. Os dois p95 e a diferença vão para o log.
- **`ReadRefusalAuditNonBlockingTest`** (unidade): o controlador de C5 e o de C6 com um `RateLimitedAuditPublisher` duplo que bloqueia 1 s; a resposta 404 de outro tenant (e a 422 em C6) sai em menos de 100 ms, e o duplo é chamado depois.
- **`ReadRefusalAuditTest`** (unidade): as constantes valem 1 thread e fila de 16; um 404 de uuid inexistente não chama o executor (0 tarefas); com o publicador bloqueado e pessoas diferentes, 1 tarefa em execução e 16 na fila são aceitas, e a 18ª é recusada, conta 1 em `pnl_read_audit_failures_total{action="TENANT_MISMATCH"}` e gera 1 log `ERROR`; 10 recusas da mesma pessoa e ação dentro de 1 minuto geram 1 log `ERROR` e 10 contagens.
- **`ReadRefusalAuditFilterTest`** (unidade): a mesma pessoa e ação enviadas 2 vezes dentro de 60 minutos dão 1 tarefa; outra ação ou outra pessoa dão 1 tarefa a mais.

## Auditoria das recusas (NFR3.13, NFR3.14)

### Componente

`pnl.application.ReadRefusalAudit` (nome proposto), bean só do processo da API (`@ProcessRole(API)`), encerrado com o contexto.

| Item | Valor | Onde fica |
|---|---|---|
| Threads | 1, daemon, nome `pnl-read-audit-1` | constante `THREADS = 1` |
| Fila | `ArrayBlockingQueue` de 16 | constante `QUEUE = 16` |
| Recusa | `AbortPolicy`: fila cheia recusa na hora, na thread do pedido | — |
| Prazo da tarefa | 200 ms, contados do envio, pelo `Clock` injetado | constante `BUDGET = Duration.ofMillis(200)` |
| Filtro em memória | pessoa e ação enviadas nos últimos 60 minutos não são enviadas de novo; no máximo 10.000 chaves, as mais velhas que 60 minutos saem primeiro | constantes `SUBMITTED_WINDOW = Duration.ofMinutes(60)` e `SUBMITTED_KEYS = 10_000` |
| Limite do log `ERROR` | 1 por minuto por pessoa e ação | constante `LOG_EVERY = Duration.ofMinutes(1)`, como o `ReconciliationAuditGate` |
| MDC | copiado na thread do pedido e aplicado na tarefa (o `correlationId`), limpo no fim | como o `ModeBlockExecutor` |

As constantes são validadas por teste e não são configuração (NFR8.1: 0 chaves novas).

### Filtro em memória na frente do executor (R-03)

Uma pessoa que repete a mesma recusa só gera 1 tarefa por hora e por ação neste processo, então ela não ocupa a thread de auditoria nem as conexões do `pnl-mismatch-audit`, que também gravam o evento de divergência do P&L REAL. O filtro só evita trabalho repetido: a fonte da verdade do limite de 1 por hora continua sendo a trilha (`MismatchAuditWriter`), que vale entre instâncias e depois de reiniciar. Uma chave só entra no filtro quando a tarefa foi aceita pela fila; uma recusa por fila cheia não marca a chave, então a próxima recusa tenta de novo. Uma falha da gravação remove a chave, pelo mesmo motivo.

### Tarefa `tenantMismatch`

1. Confere o prazo; passou → falha `DEADLINE`.
2. Monta o `AuditEventCommand.person(tenant, pessoa, TENANT_MISMATCH, "pnl-read", correlationId capturado, instante, details)` e chama `RateLimitedAuditPublisher.publishOncePerHour(comando, impressão, prazo)`.

### Tarefa `templateMismatch`

Os dois bots já são da pessoa. Monta o comando com a ação nova `AuditActions.PNL_COMPARE_TEMPLATE_MISMATCH` e chama o mesmo publicador.

### Conteúdo do evento

| Campo | `TENANT_MISMATCH` | `PNL_COMPARE_TEMPLATE_MISMATCH` |
|---|---|---|
| tenant e ator | de quem pediu | de quem pediu |
| `target` | `pnl-read` | `pnl-read` |
| `correlationId` | o do pedido recusado | o do pedido recusado |
| `details.route` | `BOT_PNL_LINES` ou `PNL_COMPARE` | `PNL_COMPARE` |
| `details.side` | `PAPER_BOT` ou `REAL_BOT`, só em C6 | ausente |
| `details.botIds` | o id que a pessoa enviou e que existe em outro tenant | os dois ids enviados |
| `details.fingerprint` | SHA-256 em hexadecimal de `pnl-read-audit|<pessoa>|TENANT_MISMATCH` | SHA-256 de `pnl-read-audit|<pessoa>|PNL_COMPARE_TEMPLATE_MISMATCH` |

0 nomes, `templateId`, valores de P&L, endereços de carteira ou campos do bot de outra conta. O evento passa pelo `AuditEventValidator` da trilha (o `MismatchAuditWriter` já o chama) e segue a retenção e a anonimização que já existem.

### Limite de 1 por hora

A impressão digital depende só da pessoa e da ação. O `MismatchAuditWriter` da U2 procura, na transação do pool `pnl-mismatch-audit` e sob a trava consultiva `(PNLM, hashtext(<ator>:<impressão>))`, um evento da mesma ação, tenant, ator e impressão na última hora, no outbox pendente e em `audit_event`. Encontrou → `DEDUPLICATED`, nada gravado. Outra instância gravando → `LOCK_BUSY`, nada gravado. As duas ações têm limites separados; pessoas diferentes também.

### Pool compartilhado

O `pnl-mismatch-audit` (2 conexões, espera de 250 ms) passa a atender o `ReconciliationAuditGate` da U2 (2 threads) e o `ReadRefusalAudit` (1 thread). Com o filtro em memória, a U3 usa esse pool no máximo 1 vez por hora por pessoa e por ação em cada processo, então uma sondagem repetida não toma as conexões da divergência REAL. O `ReadRefusalAuditLimitIT` mede a espera pela conexão do `ReconciliationAuditGate` com 20 pessoas recusando ao mesmo tempo e confere que nenhuma gravação de divergência desiste pelo prazo. O pool não cresce, e o javadoc de `RateLimitedAuditPublisher` ("Used only by the P&L REAL") passa a citar a U3. A regra ArchUnit `PNL_USES_ONLY_THE_AUDIT_API` continua valendo.

### Falha

| Situação | Categoria no log | Contador |
|---|---|---|
| fila cheia (recusa no `execute`) | `QUEUE_FULL` | `pnl_read_audit_failures_total{action}` |
| prazo passou antes de gravar | `DEADLINE` | idem |
| sem conexão a tempo | `CONNECTION_TIMEOUT` | idem |
| qualquer outra exceção | `ERROR` | idem |

O contador sobe em toda falha; como só um 404 confirmado de outro tenant chega ao executor, `{action="TENANT_MISMATCH"}` conta só casos reais. O log é `ERROR` com `event=PNL_READ_AUDIT_FAILED action=<enum> category=<enum> exceptionClass=<nome simples>` e o `correlationId` pelo MDC, no máximo 1 vez por minuto por pessoa e ação (as demais falhas do minuto só contam). Nunca a mensagem da exceção. A fila cheia e a falha de gravação ficam separadas pela `category` do log; o contador mantém só a etiqueta `action` de NFR3.16. A resposta é a mesma, com ou sem falha: o teste de NFR3.14 compara as 10 respostas com a auditoria falhando às 10 sem falha.

## Proteção de dados (NFR3.7, NFR3.8)

- **Cursor de C5.** Versão, `botId` e um resumo de 16 bytes; nenhuma assinatura de transação, slot, mint ou endereço (`performance-design.md`). O `botId` é da própria pessoa e já está no caminho.
- **Esquemas.** C5 e C6 não têm campo de endereço; o teste de contrato confere. O teste de integração confere que o corpo de C5 e de C6 de uma pessoa com carteira do bot ativa não contém o endereço semeado.
- **Logs e métricas.** Só ids, enums, `correlationId` e nomes simples de classe. Etiquetas só de enum (`observability-design.md`). Nenhum segredo, token, cabeçalho `Authorization`, nonce ou assinatura; 0 variáveis `NEXT_PUBLIC_*` novas; Gitleaks verde.
- **REAL desligado.** C5 e C6 só leem. O teste "REAL desligado por padrão" continua verde; 0 chamadas novas ao TxSigner ou ao KMS.

## Separação dos modos (NFR4.1 a NFR4.5)

- **Servidor.** C4 com `market` devolve só o bloco pedido; a outra chave fica fora do JSON (`NON_ABSENT`), nunca `null`. C6 tem só `period`, `paper` e `real`. Os tipos novos de C5 (`RealBotPnlLinePage`, `RealBotPnlLineItem`) e de C6 entram na lista fechada da regra NEVER_SUM_MODES no mesmo commit. O teste de propriedade "nenhum terceiro número" cobre C4 com e sem `market` e C6.
- **Lista.** `/bots` fica na ordem de criação em todos os filtros; C6 sem diferença, percentual, ranking nem vencedor.
- **Tela.** `bigint` só em `features/real/money.ts`; os lints de dinheiro de `features/pnl` e `features/dashboard` continuam; cada valor mostrado é o texto da API formatado por `money-format.ts`. PAPER em US$, REAL em SOL.

## Sources

- `nfr-design-questions.md` desta pasta: Q1, Q2 e o resumo confirmado [Q1] [Q2].
- `reviews/review-01.md` (R-01, R-03, R-05) e as decisões do fundador de 2026-11-23 (F-1, F-2).
- `construction/u3-pnl-views/nfr-requirements/security-requirements.md`: NFR3.1 a NFR3.8, NFR3.13, NFR3.14, NFR3.17, NFR4.1 a NFR4.5, decisão D-NFR1.
- `construction/u3-pnl-views/functional-design/rules.md`: BR5.2, BR5.3, BR8.1, BR8.2, BR6.1 a BR6.3.
- `inception/contract-design/contract-summary.md`: regras comuns (400 contra 422, 404 sem dado), C4, C5, C6, C8.
- `construction/u2-real-bot-pnl/nfr-design/security-design.md`: limite de 1 por hora, pool `pnl-mismatch-audit`, trava consultiva.
- Código lido: `apps/api/.../audit/api/RateLimitedAuditPublisher.java`, `audit/infra/MismatchAuditWriter.java`, `audit/infra/MismatchAuditDataSource.java`, `audit/api/AuditActions.java`, `pnl/application/ReconciliationAuditGate.java` (log limitado a 1 por minuto), `pnl/application/PnlAudit.java`, `pnl/api/BotDirectory.java`, `pnl/modeview/ModeBlockExecutor.java`, `pnl/web/PnlController.java`; `apps/api/src/test/java/com/memetrade/architecture/AuditArchitectureTest.java`.
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos, `NEXT_PUBLIC_*`, soma dos modos) e Mandated (tenant no servidor, separação dos modos); `team.md`: Code Style (erros, tenant no código, sem falha silenciosa), Testing Posture (testes obrigatórios por risco).

## Assumptions & Open Questions

1. [assumption] Em C6, quando os dois lados são recusados, só o lado do `reason` (o PAPER primeiro) passa por `ownedByAnotherTenant`, para que todo 404 faça o mesmo número de consultas. Uma sondagem do lado REAL com um lado PAPER também inválido não gera evento; ela continua no log `INFO` e no histograma de 404.
2. [assumption] `TENANT_MISMATCH` já existe em `AuditActions` e ainda não é gravada por nenhum caminho; a U3 é a primeira a gravá-la. O nome `PNL_COMPARE_TEMPLATE_MISMATCH` segue o prefixo de módulo das ações recentes.
3. O teste de paridade de tempo roda num runner compartilhado. Se ele ficar instável, a instabilidade é um defeito a investigar, nunca um teste a ignorar (`team.md`).
4. A exceção ao filtro de tenant (F-2) ainda não está escrita no NFR3.1 nem no `team.md`; ela entra pelo ritual de aprendizado.
