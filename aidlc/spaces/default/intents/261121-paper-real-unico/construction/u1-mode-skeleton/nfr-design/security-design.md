# Desenho de segurança — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

A U1 não cria mecanismo de segurança novo. Ela usa o que já existe: token de acesso, filtro do novo aceite dos termos, tenant do contexto de autenticação, 404 para recurso alheio, validação na fronteira e máscara de endereço no log [Q4]. O que este estágio acrescenta são três decisões de desenho:

- a auditoria da recusa REAL numa transação separada (`REQUIRES_NEW`), depois do rollback do comando;
- a passagem explícita do `Principal` às threads dos blocos de C4;
- a guarda da semente de teste.

## Superfície nova

| Rota | Tipo | Autenticação | Novo aceite dos termos | Dado sensível |
|---|---|---|---|---|
| `POST /api/v1/bots` com `market` (C1) | escrita idempotente | token de acesso | exigido | nenhum; a recusa REAL não expõe estado da carteira além do motivo |
| `GET /api/v1/bots/real-eligibility` (C2) | leitura | token de acesso | exigido | `effectiveAt` da quarentena, da própria pessoa |
| `GET /api/v1/real/account-limits` (C3) | leitura | token de acesso | exigido | limites em lamports da própria pessoa; nenhum endereço |
| `GET /api/v1/pnl/by-mode` (C4) | leitura | token de acesso | exigido | P&L da própria pessoa; nenhum endereço |

Nenhuma rota da U1 assina, envia transação ou chama o TxSigner (NFR3.9). O modelo de ameaças do ADR-005 não muda.

## Autenticação e autorização

- **Login (NFR3.3).** As quatro rotas ficam sob `/api/v1/` e passam pela mesma cadeia de filtros de hoje (`CorrelationIdFilter`, autenticação, regra de rota administrativa, `TermsReacceptanceFilter`). Sem token, a resposta é 401.
- **Novo aceite dos termos (NFR3.3).** O `TermsReacceptanceFilter` deixa passar só os caminhos de `memetrade.terms.allowed-prefixes` (`/api/v1/legal/`, `/api/v1/terms/acceptances`, `/api/v1/account`, `/api/v1/auth/logout`, `/api/v1/auth/refresh`, `/api/v1/privacy/`). Um prefixo vale para o caminho inteiro ou para um caminho abaixo dele, nunca para um caminho que só começa com o mesmo texto. Por isso `/api/v1/real/account-limits` não entra no prefixo `/api/v1/account`. Nenhuma rota da U1 é acrescentada à lista, e as quatro respondem 403 `TERMS_REACCEPTANCE_REQUIRED` com termos desatualizados.
- **Teste (NFR3.3).** Um teste de integração parametrizado cobre as 4 rotas × (sem token → 401; termos desatualizados → 403 `TERMS_REACCEPTANCE_REQUIRED`). Mais um teste de unidade confere que nenhum dos quatro caminhos casa com um prefixo de `allowed-prefixes`, pelo mesmo `RequestPaths.normalized` do filtro.
- **Tenant e pessoa (NFR3.1).** O `Principal` vem do contexto de autenticação na thread do pedido. Toda porta da U1 recebe tenant e pessoa como parâmetros:
  - C8 `botsOf` e `botOf`;
  - C9 `read`;
  - C10 `modePnl`;
  - a elegibilidade;
  - `RealExecutionPort.settledOrders`.

  Nenhuma lê tenant do corpo, da consulta ou de cabeçalho próprio. O corpo de C1 recusa campo não declarado com 400 (`spring.jackson.deserialization.fail-on-unknown-properties: true`). Um `tenantId` na consulta de C2, C3 ou C4 é ignorado: o controlador não tem esse parâmetro, e o tenant usado continua sendo o do token.
- **Threads dos blocos de C4.** As tarefas do pool `pnl-mode-block` não têm contexto de autenticação próprio. O `ModePnlView` passa o `Principal` como argumento, e nenhum código dos blocos chama o `AuthContext`. Assim, uma thread reaproveitada nunca carrega a identidade de outro pedido. O MDC copiado leva só o `correlationId` e é limpo no fim de cada tarefa.
- **404 para o bot de outra pessoa (NFR3.2).** C4 lista só os bots do diretório filtrado pela pessoa; um bot alheio nunca aparece. As rotas que recebem um id de bot respondem 404 `NOT_FOUND` sem nenhum campo do bot:
  - `botOf` de C8;
  - `botPnl` de C10;
  - `GET /api/v1/pnl?botId=`.

  O teste com o id de outra conta confere o 404 e que o corpo não traz nenhum campo do bot alheio.
- **A elegibilidade nunca autoriza operar (NFR3.4).** `RealCreationEligibility` fica em `com.memetrade.bots` e é usada só pelo comando de criação e por C2. Uma regra ArchUnit nova em `BotsArchitectureTest` diz: "nenhuma classe de `..realexecution..`, `..riskcontrol.real..` ou `..bots.worker..` depende de `RealCreationEligibility` nem do resultado dela". O teste da regra prova a falha com uma classe de amostra. A barreira REAL, a liberação com quarentena, o kill switch e o TxSigner continuam conferindo cada ordem.

## Validação na fronteira

| Entrada | Regra | Resposta na violação |
|---|---|---|
| `market` no corpo de C1 | só `PAPER` ou `REAL`; ausente vira `PAPER` (BR1.1) | 422 `VALIDATION_FAILED` no campo `market` |
| `market` num pedido de edição | campo não declarado (BR1.2) | 400 `VALIDATION_FAILED` |
| `Idempotency-Key` em C1 | obrigatório, uuid canônico | 400 `VALIDATION_FAILED` |
| `period` em C4 | ausente ou `ALL`; `from` e `to` recusados na U1 (BR5.6) | 400 `VALIDATION_FAILED` |
| Campo desconhecido no corpo | recusado | 400 `VALIDATION_FAILED` |
| `POST /api/v1/bots/{botId}/limits` (`changeBotLimits`) num bot REAL | um bot REAL usa os limites da conta (BR1.9) | 422 `BOT_VALIDATION_FAILED`, documentado na rota do `openapi-guest.yaml` (ver `reliability-design.md`) |

A defesa em profundidade do modo imutável é a restrição `bot_market_immutable` da migração `0019-real-execution.sql`, que continua (NFR4.3).

## Auditoria da recusa REAL (NFR3.5)

Toda recusa de domínio na criação REAL (409 `REAL_NOT_ELIGIBLE`, cinco motivos) é auditada, e o registro sobrevive ao rollback do comando.

1. Dentro do comando idempotente, depois da reivindicação da chave, a elegibilidade decide o motivo (BR2.1).
2. Com motivo, a ação lança uma exceção de domínio com o motivo (nome proposto `RealNotEligibleException`). O `IdempotencyService` libera a chave e relança, e a transação do comando é desfeita: nenhum bot, nenhum `BOT_CREATED` e nenhuma resposta guardada.
3. **Depois** que a transação do comando terminou, o `BotCommandService.create` captura a exceção e chama a auditoria da recusa. O nome proposto é `RealCreationRefusalAudit`, no `bots.infra`, ao lado de `BotAudit`. Ela grava com um `TransactionTemplate` em `PROPAGATION_REQUIRES_NEW`, no mesmo padrão de `PnlAudit` e `CycleFailureRecorder`.
4. Como roda depois do rollback, a gravação nunca segura duas conexões ao mesmo tempo e nunca espera uma trava do comando. O `REQUIRES_NEW` garante a transação própria mesmo se um dia ela for chamada dentro de outra transação.
5. O evento:
   - ação nova `BOT_REAL_CREATION_REFUSED` (nome proposto, em `AuditActions`);
   - ator: a pessoa (tenant e pessoa do `Principal`);
   - alvo: `bot-creation`, porque não existe bot;
   - detalhes: `reason` (um dos cinco valores) e `market = REAL`;
   - `correlationId`: `CorrelationId.current()`, o do pedido;
   - nenhum endereço, valor ou chave.
6. Só depois da gravação: o contador `bots.real.create.refusals{reason}` sobe 1, o log `INFO` é escrito e a resposta 409 sai com `reason` e `correlationId` (ver `observability-design.md`).
7. **Se a gravação da auditoria falhar**, a resposta é 503 `SERVICE_UNAVAILABLE` com log `WARN` da causa técnica, e o contador não sobe. Uma recusa de domínio nunca sai sem o seu registro. Como nada foi gravado, a repetição com a mesma chave reavalia tudo.

O teste de integração do NFR3.5 cobre os 5 motivos. Para cada um, confere 1 registro de auditoria com o motivo e o `correlationId` da resposta, 0 bots, 0 eventos `BOT_CREATED` e 0 registros de idempotência da chave. Um sexto caso força a falha da gravação da auditoria e confere o 503.

## Proteção de dados

- **Nenhum endereço de carteira (NFR3.6).**
  - O esquema de C4 não tem campo de endereço (teste de contrato).
  - Os blocos montam as linhas só com `botId`, nome, estado e valores; o `RealOrderView` nunca é serializado.
  - O teste de integração com uma carteira do bot ativa confere que o corpo de C4 não contém o endereço semeado.
  - As mensagens de log da U1 levam só `reason`, `market`, a categoria do erro e o `correlationId`. O `Base58AddressValueMasker` do `logback-spring.xml` continua como segunda barreira.
  - As etiquetas das duas métricas novas têm só valores de enum.
- **Nenhum segredo no log nem no navegador (NFR3.7).** A U1 não registra corpo de pedido, cabeçalho `Authorization`, token, nonce nem assinatura. O teste de captura de log da recusa REAL procura o token usado no pedido e não pode encontrá-lo. O `apps/web` não ganha variável `NEXT_PUBLIC_*`. O Gitleaks continua no pré-commit e no workflow `security`.
- **Cripto em trânsito e em repouso.** Sem mudança: nenhum dado novo é gravado (NFR1.13). O tráfego segue o desenho de hoje: a API e os dois apps web no mesmo site, com o token de acesso na memória e a renovação por cookie `SameSite=Strict`, o que mantém a proteção de CSRF de hoje.

## Semente da liberação de teste (NFR3.8)

O spec de ponta a ponta precisa de uma liberação REAL vigente sem esperar 24 horas. A semente que já existe (`RealTestSeeder`) faz isso só com o perfil `test` e com `memetrade.real.seed-test-release=true` (BR10.2). A quarentena de 24 horas de produção não muda.

**Teste de guarda novo**, nome proposto `RealSeedProfileGuardTest`, em `apps/api/src/test/java/com/memetrade/botwallet/infra/`, teste de unidade sem Spring, lendo os arquivos versionados. Ele falha se qualquer uma destas condições deixar de valer:

1. `RealTestSeeder` tem exatamente `@Profile("test")`: sem negação, sem outro perfil e sem expressão. A verificação é por reflexão.
2. Em `apps/api/src/main/resources/application.yml`, `memetrade.real.seed-test-release` é o literal `false`, sem `${...}` que o ligue por variável.
3. Nenhum `application-*.yml` diferente de `application-test.yml` declara `memetrade.real.seed-test-release`, `spring.profiles.active`, `spring.profiles.include` ou `spring.profiles.group` com o valor `test`.
4. Em `infra/terraform/**/*.tf`, nenhum `SPRING_PROFILES_ACTIVE` contém `test`, e nenhum arquivo declara `MEMETRADE_REAL_SEED_TEST_RELEASE` (hoje os valores são `production`, `worker,production` e `migrate`).
5. O `docker-compose.yml` da raiz não declara `MEMETRADE_REAL_SEED_TEST_RELEASE`. A parte do perfil `test` já está coberta pelo `ComposeFilesTest`.
6. Os Dockerfiles (`apps/api/Dockerfile`, `apps/web/Dockerfile`, `apps/admin/Dockerfile`, `apps/txsigner/Dockerfile` e `infra/kms-sim/Dockerfile`) não declaram `SPRING_PROFILES_ACTIVE` com `test` (em `ENV`, `ARG` ou na linha de execução, inclusive `-Dspring.profiles.active`) nem `MEMETRADE_REAL_SEED_TEST_RELEASE`.
7. Os workflows de `.github/workflows/` (hoje `ci.yml`, `load-test.yml` e `security.yml`) não declaram `MEMETRADE_REAL_SEED_TEST_RELEASE`. O perfil `test` aparece só onde roda o ambiente de teste da CI (`SPRING_PROFILES_ACTIVE: test,loadtest` no `load-test.yml`, já conferido pelo `WorkflowsTest`), nunca num workflow de implantação.
8. O `.env.example` não traz valor para `MEMETRADE_REAL_SEED_TEST_RELEASE` nem para `SPRING_PROFILES_ACTIVE` (as duas linhas ficam comentadas e vazias, como hoje).
9. O único arquivo versionado que liga a semente é `infra/docker-compose.real.yml`, a sobreposição REAL do e2e local.

**Teste de contexto**, de integração, complementar: o contexto da API com o perfil padrão e `memetrade.real.seed-test-release=true` sobe **sem** nenhum bean `RealTestSeeder`.

**Checagem de rede no `RealTestSeeder` (requisito, achado R-04):** o `RealTestSeeder` só age quando `memetrade.real.network` é `LOCALNET`. Com `DEVNET` ou `MAINNET`, e com a semente ligada, ele registra `ERROR` e lança uma exceção que impede o processo de subir. É a única barreira de execução quando uma variável de ambiente erra fora dos arquivos que o teste de guarda varre. **Teste de unidade** dos três valores de rede: `LOCALNET` grava a liberação; `DEVNET` e `MAINNET` lançam a exceção e não gravam nada.

Nenhum caminho de produção encurta ou ignora a quarentena. A semente é o único escritor direto de uma liberação ACTIVE fora do job dos gates, e só no local.

## REAL desligado por padrão (NFR3.9)

- `memetrade.real.enabled: false` continua fixo no `application.yml`. Com ele desligado, a elegibilidade devolve `REAL_DISABLED` (motivo 2 de BR2.1), sem tocar na carteira nem no TxSigner.
- A U1 não acrescenta nenhuma dependência de `botwallet`, `realexecution` ou do cliente do TxSigner que assine, envie ou peça assinatura. As leituras são `RealStateQuery`, `RealKillSwitchState.current` e `RealExecutionPort.settledOrders`, todas só de leitura.
- O teste obrigatório "REAL desligado por padrão" continua verde: 0 carteiras criadas, 0 assinaturas pedidas ao KMS e 0 transações enviadas.

## Separação dos modos (NFR4.1, NFR4.2, NFR4.3)

- **Regra ArchUnit `NEVER_SUM_MODES`**, nova na U1, porque é a U1 que cria `pnl.modeview`. Ela usa duas listas fechadas de tipos: os de resultado PAPER e os de resultado REAL, de C10. As quatro partes do ADR-003:
  1. nenhuma classe fora de `pnl.modeview` depende de um tipo de cada lista;
  2. as classes de `pnl.modeview` não chamam aritmética;
  3. elas também não chamam os acessores de valor dos tipos das duas listas;
  4. cada parte tem um teste que prova a falha com uma classe de amostra.

  O pool dos blocos e o `PnlByModeProperties` ficam fora das listas e não leem valor.
- **Contrato e propriedade.**
  - O teste de contrato confere que `PnlByMode` tem só `period`, `paper` e `real`.
  - Um teste de propriedade com valores conhecidos confere que a resposta traz exatamente o valor PAPER e o valor REAL nos seus blocos e nenhum terceiro número igual à soma.
  - O teste de componente do `apps/web` confere que a tela de P&L não mostra total geral.
- **Um bloco, um modo (NFR4.2).** O bloco PAPER lê só o ledger PAPER, em US$ (`BigDecimal`). O bloco REAL lê só as ordens e o ledger REAL, em lamports (`long`), como texto inteiro no JSON. O teste com ordens nos dois modos confere que nenhuma ordem de um modo aparece no bloco do outro.
- **Modo imutável (NFR4.3).**
  - Os DTOs de edição não têm `market`.
  - O banco recusa a mudança (`bot_market_immutable`).
  - O teste de contrato do backend confere `mode == market` em `BotSummary` e `BotDetail`.

## Controles de conformidade que continuam

- **Mandated do `project.md`:**
  - tenant validado no servidor;
  - REAL e PAPER separados;
  - `unknown` explícito;
  - UTC no tráfego;
  - REAL só com liberação e quarentena.
- **Forbidden do `project.md`:**
  - somar os modos;
  - endereço, token, nonce ou assinatura no log;
  - segredo em `NEXT_PUBLIC_*`;
  - ponto flutuante em dinheiro;
  - abrir a devnet aos usuários.
- **CI:** o workflow `security` (Gitleaks, CodeQL, OWASP Dependency-Check, `pnpm audit`) roda sem mudança. A U1 não acrescenta dependência.

## Sources

- `reviews/review-01.md`: achados R-01 a R-06 da revisão deste estágio; decisão do fundador de 2026-11-21 sobre o `DecisionRequest` do ramo REAL.
- `nfr-design-questions.md`: Q3, Q4 e o resumo confirmado [Q3] [Q4].
- `construction/u1-mode-skeleton/nfr-requirements/security-requirements.md`: NFR3.1 a NFR3.9, NFR4.1 a NFR4.3; `observability-requirements.md`: NFR3.10 a NFR3.12.
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR1.1, BR1.2, BR1.4, BR2.1, BR2.4 a BR2.6, BR5.1, BR5.2, BR5.6, BR5.8, BR8.1 a BR8.3, BR10.2; "Alinhamento do ADR-005".
- `inception/contract-design/contract-summary.md`: regras de todos os contratos (tenant do token, 404, novo aceite, convenção 400 e 422), C1 a C4, C8 a C10.
- `inception/domain-design/components.md`: ModePnlView (`NEVER_SUM_MODES`), RealCreationEligibility.
- Código lido:
  - `identity/web/TermsReacceptanceFilter.java` e `memetrade.terms.allowed-prefixes` no `application.yml`;
  - `shared/web/CorrelationId.java` e `CorrelationIdFilter.java`;
  - `pnl/application/PnlAudit.java` e `bots/worker/CycleFailureRecorder.java` (`REQUIRES_NEW`), `bots/infra/BotAudit.java` (auditoria na transação);
  - `identity/domain/idempotency/IdempotencyService.java` (liberação da chave na falha);
  - `bots/application/BotCommandService.java` (`create`);
  - `botwallet/infra/RealTestSeeder.java` e `RealProperties`;
  - `infra/ComposeFilesTest.java` e `config/RealApiWiringIT.java`;
  - `infra/terraform/main.tf`, `modules/services/api-worker.tf` e `one-off.tf` (`SPRING_PROFILES_ACTIVE`);
  - `infra/docker-compose.real.yml` (`MEMETRADE_REAL_SEED_TEST_RELEASE`);
  - `logback-spring.xml` (máscara Base58, `correlationId` do MDC);
  - `audit/api/AuditActions.java`.
- `aidlc/spaces/default/memory/project.md`: Forbidden, Mandated e a correção sobre `SameSite=Strict`.
- `aidlc/spaces/default/memory/team.md`: Testing Posture (testes obrigatórios por risco), Code Style (erros RFC 7807, tenant no código, ArchUnit), Way of Working (Gitleaks).

## Assumptions & Open Questions

1. [assumption] Gravar a auditoria da recusa **depois** do rollback do comando, com `REQUIRES_NEW`, atende à Q3 ("transação separada") e evita segurar duas conexões. A Code Generation pode gravar de dentro do comando com `REQUIRES_NEW`, desde que o teste do NFR3.5 continue verde e a pressão de conexões seja aceita.
2. [assumption] Uma falha na gravação da auditoria da recusa responde 503, e não 409, porque o `team.md` exige que erros de domínio sejam auditados. Esse caso não muda nenhum contrato: C1 já declara o 503.
3. [assumption] O nome `BOT_REAL_CREATION_REFUSED` e o alvo `bot-creation` são propostas; o catálogo de `AuditActions` decide o nome final na Code Generation.
4. **Decidido (revisão, achado R-04):** a checagem `LOCALNET` no `RealTestSeeder` é requisito deste desenho, com teste de unidade dos três valores de rede. Se o fundador um dia quiser a semente na devnet dos testes dele, isso é uma decisão registrada que passa a aceitar `DEVNET`, nunca `MAINNET`.
5. **`BotCreationMetrics` e `RealCreationRefusalAudit` ficam sem `@ProcessRole`** (achado R-01), porque o `BotCommandService` existe nos dois processos. Ver `observability-design.md`, "Processos".
