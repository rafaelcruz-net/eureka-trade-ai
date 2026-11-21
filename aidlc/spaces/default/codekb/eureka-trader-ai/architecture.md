# Arquitetura — eureka-trader-ai

## Architecture Analysis

### System Overview

Monorepo com três aplicações e um banco, tudo local:

- `apps/api` — API Spring Boot 3.5 (Java 21), monólito modular por pacote. A mesma imagem roda em três papéis,
  escolhidos por `MEMETRADE_PROCESS` e perfil Spring:
  - `api` (padrão): rotas REST, canal ao vivo, coleta de mercado, recálculo de risco, marcação de patrimônio,
    reconciliação, kill switch, expurgos e jobs de privacidade (21 jobs agendados, `RuntimeSettingsGuard`);
  - `worker` (perfil `worker`): ciclo dos bots a cada slot de 5 min, meia-noite UTC, verificação de fechamento e
    expurgo de decisões (4 jobs); não tem controlador nem porta publicada;
  - `db-migrate` (perfil `migrate`, `ops/MigrationRunner`): aplica o Liquibase com o login dono e termina.
- `apps/web` — app do convidado, Next.js 15 / React 19 (porta 3000).
- `apps/admin` — app do administrador, Next.js 15 / React 19 (porta 3001).
- PostgreSQL 17 com três logins (dono, aplicação, retenção) e papéis de grupo fixos de retenção.

`docker-compose.yml` sobe `postgres`, `db-bootstrap`, `db-migrate`, `api`, `worker`, `web` e `admin`, todos ligados só
em `127.0.0.1`. Não há nuvem, fila, cache, gerenciador de segredos, KMS, RPC Solana nem fornecedor externo: as fontes de
mercado são adaptadores simulados dentro do processo.

**Atualização de 2026-11-21 (varredura focada)**: a descrição acima é a de 2026-11-13. O intent
`261113-trading-real-bot` acrescentou depois os módulos `botwallet`, `riskcontrol/real`, `realexecution` e `realexits`,
o app `apps/txsigner` (assinatura isolada) e o cliente da Jupiter. Esta execução releu a fundo só `bots`, `pnl`,
`ledger`, `realexecution` e `realexits` no backend e as features `bots`, `real` e `dashboard` do app web; `botwallet`,
`riskcontrol/real`, `apps/txsigner`, a infraestrutura e a nuvem não foram relidos e continuam descritos pelo estado
anterior ou não descritos.

### Architectural Style

**Monólito modular** com fronteiras verificadas por ArchUnit (`apps/api/src/test/java/com/memetrade/architecture/`,
9 classes de teste). Cada módulo tem:

- `api`: interfaces e tipos públicos (os únicos que outros módulos podem importar);
- `domain`: regras puras, sem Spring, JDBC nem web (regra testada em `ledger`, `riskcontrol`, `bots` e `decision`);
- `infra`: JDBC (`JdbcClient`, sem ORM), jobs, configuração;
- `web`: controladores e DTOs;
- às vezes `application`, `worker` ou `loadtest` (este só existe com o perfil `loadtest`).

A integridade crítica fica também no banco: `CHECK (mode = 'PAPER')`, gatilhos append-only sem `SECURITY DEFINER`,
matriz de privilégios por coluna e papéis de grupo para os expurgos. Os eventos entre módulos são síncronos, publicados
pelo `ApplicationEventPublisher` do Spring dentro da transação de quem publica.

### Component Relationships

```mermaid
flowchart LR
  subgraph Navegador
    WEB["apps/web convidado"]
    ADM["apps/admin administrador"]
  end
  subgraph API["processo api"]
    IDN[identity]
    PRV[privacy]
    AUD[audit]
    SCN[scanner]
    MKT[marketdata]
    RSA[riskassessment]
    BOTW["bots web e comandos"]
    LED[ledger]
    PNL[pnl]
    RSC[riskcontrol]
    ADMN[administration]
    FBK[feedback]
  end
  subgraph WRK["processo worker"]
    CYC["bots worker"]
    DEC[decision]
  end
  DB[("PostgreSQL 17")]
  WEB -->|"REST e canal ao vivo"| API
  ADM -->|"REST admin"| API
  SCN --> MKT
  SCN --> RSA
  RSA --> MKT
  BOTW --> LED
  BOTW --> PNL
  CYC --> DEC
  CYC --> RSC
  CYC --> LED
  CYC --> MKT
  CYC --> RSA
  RSC --> MKT
  RSC --> PNL
  PNL --> LED
  PNL --> MKT
  LED --> MKT
  ADMN --> BOTW
  PRV --> IDN
  PRV --> BOTW
  PRV --> LED
  PRV --> FBK
  IDN --> AUD
  LED --> AUD
  RSC --> AUD
  BOTW --> AUD
  API --> DB
  WRK --> DB
```

Texto alternativo: o navegador usa `apps/web` (convidado) e `apps/admin` (administrador), que chamam a API por REST
(e o canal ao vivo no painel). No processo `api`, `scanner` lê `marketdata` e `riskassessment`; os comandos de `bots`
usam `ledger` (financiamento e fechamento) e `pnl` (visão de risco); `administration` comanda `bots` pelo kill switch;
`privacy` lê e aciona `identity`, `bots`, `ledger` e `feedback` por portas públicas; `identity`, `ledger`,
`riskcontrol` e `bots` publicam eventos em `audit`. No processo `worker`, o ciclo dos bots lê mercado e risco, decide
com `decision`, passa pela barreira `riskcontrol` (que lê `marketdata` e `pnl`) e executa em `ledger`. Os dois
processos usam o mesmo PostgreSQL. A lista completa de dependências está em [dependencies.md](dependencies.md).

#### Módulos REAL na área analisada (2026-11-21)

```mermaid
flowchart LR
  subgraph NAV["apps/web"]
    WBOT["features/bots"]
    WREAL["features/real"]
    WDASH["features/dashboard"]
  end
  BOTS["bots: ciclo e comandos"]
  RE["realexecution M6"]
  RX["realexits"]
  LEDR["ledger: RealLedger M2"]
  LEDP["ledger: PaperExecution C15"]
  PNL["pnl"]
  BW["botwallet"]
  RG["riskcontrol: RealRiskGate"]
  JUP["Jupiter API"]
  WBOT -->|"REST /bots e /pnl"| BOTS
  WREAL -->|"REST /real/*"| RE
  WREAL -->|"REST /real/conditional-orders"| RX
  WREAL -->|"GET /real/pnl"| PNL
  WDASH -->|"canal ao vivo e /pnl"| BOTS
  BOTS -->|"bot PAPER"| LEDP
  BOTS -->|"bot REAL: recordDecision"| RE
  BOTS --> RG
  BOTS -->|"saldo e posicoes da carteira"| LEDR
  BOTS --> BW
  RE --> RG
  RE --> BW
  RE -->|"lancamentos order:id"| LEDR
  RE --> JUP
  RE -->|"RealBotOwnership"| BOTS
  RX -->|"implementa PositionExitGuard e RealExitChanges"| RE
  RX -->|"lancamentos conditional:id"| LEDR
  RX --> BW
  PNL -->|"PAPER: LedgerReadModel"| LEDP
  PNL -->|"REAL: settledOrders"| RE
```

Texto alternativo: a feature `bots` do app web fala com o módulo `bots` (rotas de bots) e com o `pnl` (P&L PAPER); a
feature `real` fala com `realexecution`, `realexits` e com o `pnl` (P&L REAL); o painel usa o canal ao vivo do módulo
`bots`. No backend, o ciclo de um bot PAPER executa pelo `PaperExecution` do ledger; o de um bot REAL lê saldo e
posições da carteira do bot pelo `RealLedger`, passa pela barreira REAL e grava a decisão no `realexecution`, que cota e
envia pela Jupiter e lança no ledger REAL com a referência `order:<id>`. O `realexits` implementa as interfaces de
extensão do `realexecution` e lança as vendas condicionais no ledger REAL com a referência `conditional:<id>`. O `pnl`
calcula o P&L PAPER pelo `LedgerReadModel` e o P&L REAL pelas ordens assentadas do `realexecution` (não pelo ledger
REAL).

### Data Flow

1. **Entrada**: o app pede um desafio (`POST /api/v1/auth/challenge`), a carteira assina a mensagem (Wallet Standard,
   `solana:signMessage`), o app envia a assinatura (`POST /api/v1/auth/verify`). A API confere Ed25519 com o JDK (sem
   SDK Solana), consome o nonce, cria a conta quando há convite e abre a sessão: token de acesso HMAC de 15 min (só na
   memória do navegador) e cookie de renovação HttpOnly, SameSite=Strict.
2. **Mercado**: `MarketCollectorJob` despacha a cada 1 s; cada fonte coleta no seu intervalo (padrão 30 s) com limite
   de 5 s e até 3 tentativas (Resilience4j), fora da transação; a validação e a gravação acontecem numa transação; ao
   final publica `MarketCollectionCompleted`, que dispara o recálculo do risco, a marcação de patrimônio e os
   fechamentos pendentes.
3. **Bots**: no worker, `SlotDispatcher` abre o slot 60 s depois de cada múltiplo de 5 min (ShedLock
   `bots-slot-dispatch`) e roda os ciclos em 4 threads; cada ciclo é **uma transação** (veja o diagrama abaixo).
4. **Contabilidade**: o preenchimento grava ordem, fill, lançamentos, posição, resumo diário e evento de auditoria na
   mesma transação e publica `FillRecorded`, que atualiza o pico de patrimônio no `pnl`. A reconciliação roda a cada
   5 min por pessoa e sob demanda.
5. **Auditoria**: toda ação auditada grava em `audit_outbox` na transação da ação; `AuditRelayJob` (1 s) copia para
   `audit_event` (append-only), com a carteira trocada por HMAC (`wallet_hmac`).
6. **Retenção e privacidade**: jobs diários com ShedLock apagam o que tem prazo, cada um com seu pool e papel de
   retenção; pedidos de titular têm prazos e expurgo próprios; a anonimização é uma ação do administrador.

### Estado atual do modo REAL

Nas áreas analisadas em 2026-11-21 (`bots`, `pnl`, `ledger`, `realexecution`, `realexits` e o app web), o REAL existe
e funciona atrás de flags e da liberação da pessoa:

| Camada | Onde | Comportamento hoje |
|---|---|---|
| Bot | `bot.market` (0019, `CHECK (market IN ('PAPER','REAL'))`), `bots/domain/BotMarket` | Escolhido na criação, nunca alterado; `bot.mode` continua `CHECK (mode = 'PAPER')` (0010) e o `BotSummary` devolve sempre `mode = "PAPER"` |
| Criação | `bots/application/BotCommandService.create` | `market = REAL` exige carteira do bot ATIVA (`RealStateQuery`), senão `422 VALIDATION_FAILED` em `market`; a chave de idempotência ganha o sufixo `:REAL` |
| Início e parada | `BotCommandService.start` e `stop` | Bot REAL não financia conta PAPER e não fecha posições ao parar: as posições ficam na carteira do bot e saem por "vender tudo e sacar" |
| Ciclo | `bots/worker/CycleRunner`, `RealCycle`, `application/RealDecisionStep` | Bot REAL decide sobre o saldo operável e as posições da carteira do bot inteira; a decisão é gravada com `decision.mode = 'REAL'`; aprovada pela barreira REAL vira `RealDecisionRequest` PENDING na mesma transação; sem preço do SOL o slot é `REAL_UNAVAILABLE` |
| Ledger | `real_ledger_entry` (0016), `ledger/api/RealLedger` | Tabela própria, append-only, `CHECK (mode = 'REAL')`, por `bot_wallet_id` (sem `bot_id`); `ledger_entry` continua só PAPER |
| Ordens | `real_order`, `real_decision_request`, `real_liquidation` (0019) | `origin IN ('BOT','USER_LIQUIDATION')`; `BOT` exige `bot_id`, `USER_LIQUIDATION` exige `liquidation_id`, sem `bot_id`, só `SELL`; uma ordem em andamento por bot |
| Saídas | `real_conditional_order` (0022), `realexits` | Uma condicional não final por posição (mint) da carteira do bot; sem `bot_id` |
| P&L | `pnl/application/RealPnlService`, `GET /api/v1/real/pnl` | Por pessoa e por mint, sem período e sem bot; não lê PAPER |
| Interface | `features/real/*`, `DashboardRealPnl` | Área `/real` (ativação, depósito e saque, modo, ordens) e cartão REAL no painel, só para quem tem carteira ativa; a criação de bot (`features/bots/BotCreate`) não oferece REAL |

As linhas abaixo são o estado de 2026-11-13. As camadas HTTP (`ModeGuardFilter`), conta, termos, build e arquitetura
não foram relidas nesta execução e podem ter mudado com o intent `261113-trading-real-bot` (por exemplo, os termos v3
com o modo REAL e o `ModeGuardFilter`).

| Camada | Onde | Comportamento em 2026-11-13 |
|---|---|---|
| Banco | `0008-ledger-pnl.sql`, `0009-risk-control.sql`, `0010-bots-decision.sql` | `mode varchar(8) NOT NULL DEFAULT 'PAPER'` com `CHECK (mode = 'PAPER')` em 13 tabelas: `paper_account`, `paper_order`, `fill`, `ledger_entry`, `position`, `pnl_daily`, `equity_peak`, `reconciliation_check`, `order_evaluation`, `order_rejection`, `rejection_reason_detail`, `bot`, `decision` |
| HTTP | `shared/web/ModeGuardFilter` | Qualquer `mode` diferente de `PAPER` na query ou no corpo JSON de `/api/**` → `403 REAL_MODE_UNAVAILABLE`, auditado como `REQUEST_FORBIDDEN` |
| Domínio | `ledger/api/PaperEnums.MODE = "PAPER"`, `PaperExecution` | "PAPER never builds, signs nor sends a transaction" |
| Conta | `identity/domain/account/AccountQueryService` | REAL sempre `UNAVAILABLE` na resposta de `GET /api/v1/account` |
| Interface | `apps/web/src/lib/i18n/messages.ts` (`realMode`) e `AccountScreen` | Linha de texto "Modo REAL: indisponível no beta", nunca um controle |
| Build | `apps/api/pom.xml` (`maven-enforcer-plugin`) | `solanaj`, `bitcoinj`, `sol4k` e `sava` banidos nos escopos de produção; `solanaj` só em `test` |
| Arquitetura | `ArchitectureTest`, `BotsArchitectureTest`, `LedgerArchitectureTest`, `RiskControlArchitectureTest` | Proíbem em produção `KeyPairGenerator`, `PrivateKey`, `PKCS8EncodedKeySpec`, `*Mnemonic*` e os pacotes dos SDKs; proíbem rede e tipos `Signature`/`Keypair`/`Signer` em `bots`, `ledger` e `riskcontrol`; proíbem `java.net` em `marketdata` |
| Termos | `apps/api/src/main/resources/terms/v1` e `v2` | Textos dizem que o modo REAL está indisponível no beta |

### Inventário de dados

Inventário dono deste tema (outros artefatos apontam para cá). Tabelas por migração, com o que as protege.

| Tabela (migração) | Dono | Conteúdo | Proteção e retenção |
|---|---|---|---|
| `tenant`, `app_user`, `wallet` (0001, 0007, 0015) | `identity` | Conta, papel, status (`ACTIVE`, `DISABLED`, `ANONYMIZED`), carteira com endereço único | `uq_wallet_address`; endereço e provedor ficam nulos juntos só com `anonymized_at` |
| `login_challenge`, `user_session`, `session_used_refresh_digest` (0001, 0012) | `identity` | Desafios de login, sessões, digests de renovação usados | Expurgos diários `LoginChallengePurgeJob` e `SessionPurgeJob`, estado em `identity_job_run` |
| `invite`, `terms_acceptance` (0007, 0013) | `identity` | Convites (digest do código) e aceites dos termos (convite ou reaceite) | Aceites sem UPDATE/DELETE/TRUNCATE para a aplicação |
| `idempotency_record` (0002) | `identity` | Chave, escopo, digest e resposta guardada | Limpeza de hora em hora (24 h padrão) |
| `data_source`, `token`, `market_observation`, `observation_sample`, `source_status_mark` (0003, 0006) | `marketdata` | Dados de mercado globais (sem cliente) | Limpeza de histórico de hora em hora |
| `risk_weight_set`, `risk_assessment` (0006) | `riskassessment` | Pesos versionados (um ACTIVE) e última avaliação por token | Globais |
| `audit_outbox`, `audit_event`, `audit_job_run`, `shedlock` (0004, 0005, 0012) | `audit` | Saída e trilha de eventos; `wallet_hmac` | Trilha append-only para todos; DELETE só pelo papel `audit_retention` após 12 meses; saída limpa 7 dias após a entrega |
| `simulation_policy`, `paper_account`, `paper_order`, `fill`, `ledger_entry`, `position` (0008) | `ledger` | Política v1, conta PAPER por bot, ordens, preenchimentos, lançamentos, posições | `mode = 'PAPER'`; `fill` e `ledger_entry` append-only; `paper_order`/`paper_account` sem DELETE; `uq_paper_order_key (tenant_id, mode, idempotency_key)`; um `FUNDING` por bot |
| `pnl_daily`, `equity_peak`, `reconciliation_check` (0008) | `pnl` (`pnl_daily` gravado pelo `ledger`) | Resumo diário, pico de patrimônio, resultados da reconciliação | `mode = 'PAPER'`; pico só sobe; mantém as últimas 100 reconciliações por pessoa |
| `order_evaluation`, `order_rejection`, `rejection_reason_detail`, `risk_purge_run` (0009) | `riskcontrol` | Avaliações (uma por decisão), recusas e motivos | `mode = 'PAPER'`; recusas append-only; avaliações aprovadas com mais de 30 dias apagadas pelo papel `risk_retention` |
| `strategy_template`, `bot_owner`, `bot`, `bot_limits_version`, `bot_cycle`, `bot_holding`, `system_pause_state` (0010) | `bots` | Modelos, bots, limites imutáveis por versão, ciclos por slot, posse dos tokens, pausa do sistema | `bot.mode = 'PAPER'`; `bot_limits_version` imutável; um ciclo por bot e slot |
| `decision` (0010) | `decision` | Decisão explicada com entradas e contexto (JSONB) | `mode = 'PAPER'`; sem UPDATE; HOLD/NO_TRADE apagadas após 30 dias |
| `kill_switch_state`, `kill_switch_change` (0011) | `administration` | Estado único ON/OFF com versão e histórico | Linha única; histórico append-only |
| `feedback_entry` (0011, 0015) | `feedback` | Comentário, tela, pessoa | Apagado após 180 dias pelo papel `feedback_retention`; função `erase_feedback_of_user` para a anonimização |
| `data_subject_request`, `data_subject_verification`, `privacy_job_run` (0014) | `privacy` | Pedidos de titular e verificação por assinatura | Um pedido aberto por tipo e pessoa; expurgo 12 meses após o fechamento |
| `bot.market` (0019) | `bots` | Coluna nova do bot: `PAPER` (padrão) ou `REAL` | Fixa desde a criação |
| `real_ledger_entry` (0016) | `ledger` (M2) | Lançamentos REAL por carteira do bot: `DEPOSIT`, `WITHDRAWAL`, `NETWORK_FEE`, `RENT`, `SWAP_FILL_IN`, `SWAP_FILL_OUT`, `COMPENSATION`, `RESERVE`, `RESERVE_RELEASE`; lamports `bigint`, quantidade `numeric(30,0)`, `reference` (`order:<id>`, `conditional:<id>`, `wd:<id>`, `dep:<assinatura>`) | `mode = 'REAL'`; append-only pela mesma função do ledger PAPER; chave de idempotência única; sinal conferido por `CHECK` e por `RealEntryRules` |
| `real_decision_request`, `real_order`, `real_liquidation` (0019) | `realexecution` (M6) | Fila de decisões dos bots, ordens com estado, cotação, assinatura, saída lida da rede, taxa, quem pagou e aluguel; liquidações da pessoa | Origem `BOT` com `bot_id` ou `USER_LIQUIDATION` sem `bot_id`; índice único de ordem em andamento por bot |
| `real_conditional_order` (0022) | `realexits` | Condicionais por posição (mint) da carteira do bot, com estado, lado executado e assinaturas | Uma não final por posição; sem `bot_id` |
| `decision.mode` (0021) | `decision` | Passa a aceitar `REAL` | As linhas PAPER não mudam |

Logs: JSON no stdout, com qualquer palavra Base58 de 32 a 44 caracteres mascarada (`Base58AddressValueMasker`) e
`WalletAddress.toString()` sempre mascarado. Navegador: token de acesso só em memória.

### Como o P&L é calculado hoje

Seção dona deste tema (2026-11-21). Os diagramas estão em [Interaction Diagrams](#interaction-diagrams).

| Aspecto | P&L PAPER | P&L REAL |
|---|---|---|
| Rota | `GET /api/v1/pnl?from&to&botId` (`pnl/web/PnlController`) | `GET /api/v1/real/pnl` (`pnl/web/RealPnlController`), sem parâmetros |
| Serviço | `PnlQueryService.report` em `REPEATABLE READ`, só leitura | `RealPnlService.pnl(tenant, user)` |
| Fonte | Ledger PAPER pelo `LedgerReadModel`: `pnl_daily` para os dias UTC inteiros, `fill` para os dias parciais, `position` para o não realizado | Ordens assentadas do M6 (`RealExecutionPort.settledOrders`: `real_order` com assinatura e estado `CONFIRMED` ou `FAILED`, ordenadas por `updated_at, real_order_id`) e preços de `MarketDataQuery` |
| Granularidade | Por pessoa, com filtro opcional por bot (cada bot tem a sua conta PAPER); linhas por token | Por pessoa; posições por mint da carteira do bot; nenhum filtro por bot |
| Período | `[from, to)`, até 366 dias | Todo o histórico |
| Custo médio | No momento do fill (`ledger/domain/PositionAccounting`): custo total por bot e token, taxa fora do custo, parte vendida `HALF_EVEN` em 6 casas; o realizado fica gravado em cada `fill` | Na leitura (`pnl/real/RealPnlCalculator`): por mint, custo = lamports gastos + taxa (inclusive a paga pela carteira principal no modo Assinada) + aluguel; parte vendida arredondada para CIMA |
| Não realizado | Valor de saída agora (`ExitValuation`, preço de referência) menos o custo; `UNKNOWN` com motivo, nunca zero | Preço em lamports por unidade base = `tokenUsd × 10^9 / (solUsd × 10^decimais)`, 18 casas, para baixo; qualquer peça ausente deixa a posição e o total `UNKNOWN` |
| Unidade | USDC, texto decimal com 6 casas | Lamports, texto inteiro com sinal |
| Ordem FAILED na rede | Não existe (simulado) | Conta só a taxa |
| Venda da liquidação | Não existe | Entra (é um `real_order` com `origin = 'USER_LIQUIDATION'`, sem bot) |
| Venda de saída condicional | Não existe | **Não entra**: `FillPosting` grava `SWAP_FILL_OUT` no ledger REAL sem criar `real_order`, e o P&L não lê o ledger |
| Reconciliação | `ReconciliationService` compara ordens, lançamentos, posições e resumo PAPER | Reconciliação da carteira com a rede no `botwallet`/U5 (fora desta execução); o P&L REAL não é conferido com o ledger REAL por nenhum teste encontrado |

Consequências para o P&L REAL por bot:

- a origem por bot existe em `real_order.bot_id` para as ordens `BOT`; a liquidação e a condicional não têm bot;
- o ledger REAL e as posições (`RealLedger.positions`, `RealPositions`) são da carteira do bot, compartilhada por todos
  os bots REAL da pessoa;
- o ciclo de um bot REAL vende a posição inteira do mint na carteira (`RealCycle.order`, ramo SELL), inclusive o que
  outro bot comprou, e o `PositionExitGuard` cancela a condicional da posição antes de qualquer venda a mercado;
- dois custos médios REAL convivem: `RealPositions` (só a perna em SOL, parte vendida para baixo, usado pela barreira e
  pelo ciclo) e `RealPnlCalculator` (SOL + taxa + aluguel, parte vendida para cima, usado pelo P&L).

### Key Design Decisions

- **Migrações fora da API** (ADR-0002): o login de aplicação não tem DDL; só `db-migrate`, com o dono, aplica o
  Liquibase. Migrações seguem expand-contract, com rollback declarado e `MigrationsBackwardCompatIT`.
- **Proteção no banco além do código**: gatilhos append-only, `CHECK` de modo, matriz de colunas atualizáveis por
  login, papéis de grupo para os expurgos e um pool de 1 conexão por expurgo, entregue por ArchUnit só à classe dele.
- **Ciclo do bot numa transação**: decisão, barreira e execução PAPER são atômicas (`Propagation.MANDATORY` em
  `OrderGate` e `PaperExecution`); uma falha técnica desfaz tudo e o slot é gravado como `FAILED` em outra transação,
  sem repetição.
- **Barreira que falha fechada** (ADR de U6 no código): toda exceção de mercado, risco ou banco é relançada, nunca vira
  aprovação.
- **Duas fontes simuladas e o valor conhecido mais recente** (ADR-0004); **indicador de risco em validação** com pesos
  versionados (ADR-0005).
- **Sem agregação de recusas** (ADR-0003): todo 403 grava um `REQUEST_FORBIDDEN`.
- **Registro de rotas por escopo de dados** (`tenancy/route-registry.yml`, 72 entradas): rotas `CLIENT` e `ADMIN`
  exigem teste negativo nomeado.
- **Contrato gerado**: OpenAPI gerado do código e versionado; tipos dos frontends gerados do OpenAPI; a CI compara.
- **Processos separados por anotação** (`@ProcessRole`): um job só existe no seu processo, verificado por
  `ProcessRoleContextIT`.

### Improvement Opportunities

- A política de simulação v1 existe duas vezes (`ledger` no banco e `riskcontrol/domain/SlippageEstimate.V1` no código),
  mantidas iguais por teste.
- O frescor do preço tem dois limites: 60 s na barreira e 5 min no preenchimento.
- As fronteiras de módulo são verificadas por ArchUnit módulo a módulo, sem uma regra única que cubra os módulos novos
  automaticamente.
- Não há fila nem worker de execução assíncrona: tudo o que o PRD §12 prevê para execução e reconciliação on-chain
  ainda não tem lugar na arquitetura. (Estado de 2026-11-13; o `realexecution` passou a ter fila de decisões e jobs de
  execução no worker.)
- (2026-11-21) O P&L REAL não lê o ledger REAL: as vendas condicionais ficam de fora e nenhum teste confere o P&L REAL
  contra o ledger REAL.
- (2026-11-21) O custo médio REAL existe em duas versões com regras de custo e de arredondamento diferentes
  (`RealPositions` e `RealPnlCalculator`).
- (2026-11-21) O ledger, as posições e as condicionais REAL são por carteira do bot, sem `bot_id`; atribuir resultado a
  um bot exige partir de `real_order.bot_id` e ratear o que não tem bot.
- (2026-11-21) O bot guarda o modo em `market` e mantém `mode` fixo em `PAPER`; o contrato `BotSummary` expõe os dois.

### Pontos de extensão e bloqueios para o Trading REAL

Registro histórico de 2026-11-13, antes da construção do intent `261113-trading-real-bot`; o estado atual das áreas
analisadas está em [Estado atual do modo REAL](#estado-atual-do-modo-real).

| Peça existente | Reaproveitável | Limite atual |
|---|---|---|
| `MarketSourceAdapter` (C19) | Porta pronta para um adaptador real (preço, liquidez, decimais) | Só `sim-a`/`sim-b` aceitos (`MarketDataProperties.KNOWN_SOURCES`); `java.net` proibido em `marketdata` |
| `OrderGate` (C14) e `LimitChecks` | Limite por ordem, exposição, slippage, liquidez, frescor, intervalo, duplicidade, perda diária, drawdown | Saldo e exposição lidos do ledger PAPER; slippage estimado pela política simulada; motivo `INSUFFICIENT_PAPER_BALANCE` |
| `PaperExecution` (C15) | Forma do contrato (resultado selado, idempotência por chave, recusa como resultado) | Exige a transação do ciclo; nunca envia nada |
| Ledger append-only, compensação, reconciliação | Modelo de lançamentos, chave única com `mode`, gatilhos | `CHECK (mode = 'PAPER')`; reconciliação só com fatos internos |
| Kill switch | Estado único, histórico, aplicação com trava e reconciliação a cada 30 s | Só pausa bots; não cancela transações enviadas nem ordens fora da plataforma |
| Idempotência comum | `IdempotencyGuard` com operações fechadas no enum `IdempotentOperation` | Operações REAL não existem |
| Auditoria | Outbox na transação, trilha de 12 meses | `AuditEventValidator` recusa a chave `signature` e sequências Base58 de 80 a 90 caracteres (assinatura de transação) e trata palavras Base58 de 32 bytes como carteira |
| Processo `worker` | Lugar natural para jobs de execução e reconciliação | Pool de 14 conexões e 4 threads de agendamento dimensionados para o ciclo PAPER |
| Login por assinatura | Ed25519 nativo, nonce de uso único | `chain: solana:mainnet` fixo na configuração |

## Interaction Diagrams

### Ciclo de um bot PAPER (worker)

```mermaid
sequenceDiagram
  participant SD as SlotDispatcher
  participant CR as CycleRunner
  participant DB as PostgreSQL
  participant MK as CycleMarketReader e MarketDataQuery
  participant DE as DecisionEngine
  participant OG as OrderGate riskcontrol
  participant PR as PortfolioRiskView pnl
  participant PE as PaperExecution ledger
  participant AU as AuditEventPublisher
  SD->>CR: run bot e slot, uma thread por bot
  CR->>DB: abre transacao READ COMMITTED, lock_timeout 2 s
  CR->>DB: bot_cycle RUNNING, unico por bot e slot
  CR->>DB: trava a linha do bot e confere ACTIVE e kill switch
  CR->>PE: posicoes e saldo via LedgerReadModel
  CR->>MK: janela, preco, decimais e avaliacao dos tokens
  CR->>DE: decide, funcao pura
  CR->>DB: grava decision com entradas e contexto
  CR->>OG: avalia ordem candidata BUY ou SELL
  OG->>MK: preco e liquidez
  OG->>PR: exposicao, perda do dia, patrimonio, saldo virtual
  OG->>DB: order_evaluation, e se recusada order_rejection
  OG->>AU: ORDER_REJECTED quando recusada
  OG-->>CR: Approved ou Rejected com gatilhos de pausa
  CR->>PE: execute com chave order:decisionId
  PE->>DB: trava a conta, confere a chave, grava paper_order, fill, ledger_entry, position, pnl_daily
  PE->>AU: PAPER_ORDER_FILLED ou NOT_FILLED
  CR->>DB: bot_cycle COMPLETED
  CR->>DB: commit unico
```

Texto alternativo: o despachante roda cada bot ativo numa thread; o ciclo abre UMA transação, reivindica o slot, trava
o bot e confere o estado e o kill switch, lê posições, saldo e mercado, decide (função pura) e grava a decisão. Uma
ordem BUY ou SELL passa pela barreira, que lê mercado e a visão de risco, grava a avaliação e, se recusar, a recusa e o
evento `ORDER_REJECTED` e devolve os gatilhos de pausa. Aprovada, a execução PAPER trava a conta, confere a chave de
idempotência e grava ordem, fill, lançamentos, posição e resumo diário. Tudo termina num único commit; uma falha
técnica desfaz tudo e o slot vira `FAILED` em outra transação.

### Kill switch

```mermaid
sequenceDiagram
  participant A as apps/admin
  participant C as AdminKillSwitchController
  participant S as KillSwitchService
  participant DB as PostgreSQL
  participant AP as KillSwitchApplier
  participant B as BotAdministration bots
  participant R as KillSwitchReconciler
  participant W as CycleRunner worker
  A->>C: PUT /api/v1/admin/kill-switch com Idempotency-Key, estado e motivo
  C->>S: valida corpo e reivindica a chave
  S->>DB: trava kill_switch_state, grava versao nova, kill_switch_change e KILL_SWITCH_ON ou OFF
  C->>AP: applyCurrent depois do commit
  AP->>DB: ApplyLock e leitura da cerca
  AP->>B: applySystemPause ou liftSystemPause em lotes
  B->>DB: motivo SYSTEM em cada bot ativo, system_pause_state
  AP->>DB: pendingBots e limpa reconcileNeeded pela cerca
  R->>AP: a cada 30 s e na subida, quando reconcileNeeded
  W->>DB: no inicio de cada ciclo confere systemPaused e pula o slot
```

Texto alternativo: o administrador envia o novo estado com motivo e chave de idempotência; o serviço grava o estado,
o histórico e o evento numa transação; depois do commit, o aplicador, sob uma trava própria, lê o estado vigente e
pede ao módulo `bots` que pause (motivo `SYSTEM`) ou libere os bots em lotes. Se algo falhar, a marca
`reconcileNeeded` fica e o reconciliador repete a cada 30 s e na subida. O worker, no início de cada ciclo, confere se
o sistema está pausado e pula o slot.

### Coleta de mercado e efeitos

```mermaid
flowchart TD
  D["MarketCollectorJob despacho a cada 1 s"] --> F["SourceCaller fora da transacao: 5 s, ate 3 tentativas"]
  F -->|falha| X["source FAILED, valores anteriores mantidos"]
  F -->|sucesso| V["ObservationValidator descarta invalidos"]
  V --> T["Uma transacao: tokens, valores atuais, amostra de 5 min, estado da fonte, marca global"]
  T --> E["evento MarketCollectionCompleted"]
  E --> R1["RiskRecalculator recalcula avaliacoes"]
  E --> R2["EquityMarkingJob marca patrimonio e pico"]
  E --> R3["PendingCloseJob conclui fechamentos pendentes"]
```

Texto alternativo: o despacho roda a cada segundo e coleta cada fonte no seu intervalo; a chamada à fonte acontece
fora da transação, com limite de 5 s e até 3 tentativas; uma falha marca a fonte como `FAILED` e mantém os valores
anteriores; um sucesso passa pela validação e grava tudo numa transação; o evento de fim de coleta dispara o recálculo
do risco, a marcação de patrimônio e a conclusão dos fechamentos pendentes.

### Entrada por convite

```mermaid
sequenceDiagram
  participant B as apps/web
  participant AC as AuthController e InviteLookupController
  participant LV as LoginVerificationService
  participant IC as InviteAccountCreation
  participant DB as PostgreSQL
  participant AP as AuditEventPublisher
  B->>AC: GET /api/v1/invites/codigo
  AC-->>B: estado, validade e textos vigentes
  B->>AC: POST /api/v1/auth/challenge com Idempotency-Key
  AC-->>B: mensagem com dominio, nonce, chain e expiracao
  B->>AC: POST /api/v1/auth/verify com assinatura, inviteCode e versao aceita
  AC->>LV: verify Ed25519 nativo
  LV->>DB: consome o desafio
  LV->>IC: cria a conta
  IC->>DB: app_user, wallet, convite USED, terms_acceptance
  IC->>AP: ACCOUNT_CREATED na saida da auditoria
  IC->>DB: user_session
  AC-->>B: token de acesso e cookie de renovacao
```

Texto alternativo: o app consulta o convite e recebe os textos vigentes; pede um desafio; a carteira assina a
mensagem; o app envia assinatura, código e versão aceita; a API confere a assinatura com Ed25519 nativo, consome o
desafio e, numa transação, cria pessoa, carteira, uso do convite, aceite, evento de auditoria e sessão.

### P&L REAL hoje (leitura, 2026-11-21)

```mermaid
sequenceDiagram
  participant W as apps/web RealPnlCard
  participant C as RealPnlController
  participant S as RealPnlService
  participant P as RealExecutionPort settledOrders
  participant DB as PostgreSQL real_order
  participant M as MarketDataQuery
  participant K as RealPnlCalculator
  W->>C: GET /api/v1/real/pnl com token de acesso
  C->>S: pnl tenant e pessoa do token
  S->>P: settledOrders tenant e pessoa
  P->>DB: real_order com tx_signature, CONFIRMED ou FAILED, por updated_at
  DB-->>S: ordens BOT e USER_LIQUIDATION, sem as vendas condicionais
  S->>S: FAILED vira fill so com a taxa, BUY com lamports, taxa e aluguel, SELL com lamports recebidos
  S->>M: precos em dolar dos mints e do SOL embrulhado, decimais dos mints
  S->>K: of fills e precos em lamports por unidade base
  K-->>S: realizado, nao realizado ou vazio, taxas, aluguel, posicoes por mint
  C-->>W: texto inteiro em lamports, UNKNOWN quando falta preco, Cache-Control no-store
```

Texto alternativo: o cartão REAL do app pede `GET /api/v1/real/pnl`. O controlador pega tenant e pessoa do token e chama
o serviço, que lê pelo M6 todas as ordens da pessoa que chegaram à rede (confirmadas ou falhas com assinatura), em
ordem de atualização. São ordens dos bots e da liquidação; as vendas das saídas condicionais não estão ali. Uma ordem
falha vira só taxa; uma compra soma lamports gastos, taxa e aluguel ao custo; uma venda realiza os lamports recebidos
menos a taxa e o custo médio da parte vendida. O serviço converte o preço em dólar de cada mint em lamports por unidade
base com o preço do SOL embrulhado e os decimais; qualquer peça ausente deixa o preço desconhecido. O cálculo devolve o
realizado, o não realizado (ou desconhecido), as taxas e as posições por mint, sem divisão por bot.

### P&L PAPER por bot (leitura)

```mermaid
sequenceDiagram
  participant W as apps/web BotDetail ou PnlScreen
  participant C as PnlController
  participant Q as PnlQueryService
  participant L as LedgerReadModel
  participant PM as PortfolioMarks e ExitValuation
  participant K as PnlCalculator
  W->>C: GET /api/v1/pnl com from, to e botId
  C->>Q: report pessoa, periodo e bot
  Q->>L: account botId, confere tenant e dono
  alt bot de outra pessoa
    Q-->>C: 404 NOT_FOUND
  else bot sem conta PAPER
    Q-->>C: relatorio vazio
  end
  Q->>L: dailySummary dos dias UTC inteiros do bot
  Q->>L: fills dos dias parciais do bot
  Q->>L: openPositions do bot
  Q->>PM: valor de saida agora de cada posicao aberta
  Q->>K: report linhas por token com realizado, taxas e marca
  K-->>Q: totais, UNKNOWN quando uma linha nao tem preco
  C-->>W: realizado, nao realizado, taxas e linhas por token em USDC
```

Texto alternativo: o detalhe do bot (ou a tela de P&L) pede `GET /api/v1/pnl` com o período e o `botId`. O serviço, numa
leitura `REPEATABLE READ`, confere que a conta PAPER do bot é da pessoa (outro dono dá 404; sem conta, relatório
vazio), soma o realizado e as taxas do resumo diário nos dias UTC inteiros e dos fills nos dias parciais, lê as posições
abertas do bot e as avalia pelo valor de saída agora. O cálculo monta as linhas por token e os totais; uma linha sem
preço deixa o não realizado total desconhecido. O realizado já vem gravado em cada fill, porque o custo médio por bot é
aplicado no momento do preenchimento.

### Venda de uma saída condicional no ledger REAL

```mermaid
sequenceDiagram
  participant J as ConditionalReadJob
  participant T as TriggerPort Jupiter
  participant F as FillPosting
  participant DB as PostgreSQL
  participant L as RealLedger M2
  J->>T: le a condicional aberta da posicao
  T-->>J: executada, com a assinatura da execucao
  J->>F: post condicional, lado e efeito lido na rede
  F->>DB: compare-and-set OPEN ou CANCELLING para FILLED
  F->>L: SWAP_FILL_OUT lamports recebidos e tokens vendidos, referencia conditional:id
  F->>L: NETWORK_FEE quando a carteira do bot pagou
  F->>DB: evento real.conditional.state
  Note over F,L: nenhum real_order e criado, entao o P&L REAL nao ve esta venda
```

Texto alternativo: o job de leitura consulta a condicional na Jupiter; quando ela foi executada, `FillPosting` muda o
estado para `FILLED` por compare-and-set e, na mesma transação, grava no ledger REAL a venda (`SWAP_FILL_OUT`) e,
quando for o caso, a taxa, com a referência `conditional:<id>`, e anuncia a mudança no canal ao vivo. Nenhuma ordem
`real_order` é criada: as posições do ledger REAL e a exportação fiscal veem a venda, o P&L REAL não.

### Ciclo de um bot REAL (worker)

```mermaid
sequenceDiagram
  participant CR as CycleRunner
  participant RC as RealCycle
  participant BW as RealStateQuery botwallet
  participant L as RealLedger view
  participant DE as DecisionEngine
  participant RS as RealDecisionStep
  participant G as RealRiskGate
  participant RE as RealExecutionPort
  CR->>RC: inputs do bot REAL
  RC->>BW: estado REAL da pessoa e carteira do bot
  RC->>L: saldo e posicoes da carteira inteira
  RC-->>CR: saldo operavel e posicoes em dolar com o preco do SOL
  CR->>DE: decide
  CR->>CR: grava decision com mode REAL
  CR->>RC: order BUY em lamports ou SELL da posicao inteira do mint
  RC->>RS: submit candidato
  RS->>RE: ordem em andamento do bot ou liquidacao ativa descartam
  RS->>G: evaluate na transacao do ciclo
  G-->>RS: Approved com reserva ou Rejected
  RS->>RE: recordDecision PENDING
  CR->>CR: BUY gravado abre bot_holding, commit unico
```

Texto alternativo: no worker, o ciclo de um bot REAL lê o estado REAL da pessoa e, pelo `RealLedger`, o saldo e as
posições da carteira do bot inteira (de todos os bots REAL da pessoa), convertidos em dólar com o preço do SOL. O motor
decide; a decisão é gravada com `mode = 'REAL'`. Uma compra vira lamports; uma venda usa a quantidade inteira do mint
na carteira. O passo REAL descarta a decisão quando o bot já tem ordem em andamento ou a pessoa tem uma liquidação
ativa, confere a posse do bot, passa pela barreira REAL e grava a decisão PENDING no M6, tudo na transação do ciclo.
A execução (cotação, assinatura, envio e confirmação) acontece depois, fora do ciclo, no `realexecution`.
