# Componentes lógicos — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/functional-design/` (BR1.1 a BR10.2, F1 a F10), `construction/u4-real-execution/nfr-requirements/`, `nfr-design-questions.md` (Q1 a Q7) e o código atual:
- `docker-compose.yml`, onde o contexto do build é a pasta de cada app;
- `scripts/verify.sh`;
- `.github/workflows/ci.yml`;
- `DashboardMarkPoller`;
- `RuntimeSettingsGuard`;
- `apps/txsigner`, com o leitor de mensagem, o `ProductionChecks` e o `JupiterTxProofTest`.

## Desvio da resposta Q2 (registrado na revisão da iteração 1)

O módulo compartilhado `libs/solana-tx` não cabe no projeto atual:
- o contexto do Docker é a pasta de cada app;
- o `verify.sh`, que só o responsável do projeto altera, faz `docker build apps/txsigner`;
- não há POM raiz para Spotless, JaCoCo e ArchUnit.

Por isso o código Solana fica em cada app, com papéis diferentes:
- **API (`realexecution.domain.solana`):** **monta** a mensagem v0 só com chaves estáticas. O `Base58Codec` da U1 passa de `botwallet.domain` para `shared.solana`, para os dois módulos o usarem sem ferir a fronteira entre módulos; o `botwallet` passa a importar dali.
- **TxSigner:** **lê e confere** a mensagem, com o leitor que já existe, ampliado para o swap.

Um teste de conformidade nos dois apps lê o mesmo conjunto de vetores, gerado pelo SDK Solana só no escopo de teste e versionado igual nos dois (`src/test/resources/solana-vectors/`). Um teste a mais compara o SHA-256 dos dois arquivos de vetores para garantir que são iguais.

A decisão de segurança da Q2 continua: sem SDK na produção, Ed25519 nativo, nenhuma dependência de terceiros. O leitor do TxSigner continua aceitando tabela de endereços no saque e no `JupiterTxProofTest`; só a política de swap a recusa (`LOOKUP_TABLE_NOT_ALLOWED`).

## Componentes

| Componente | Onde | Processo | Papel |
|---|---|---|---|
| `MessageBuilder`, `MessagePlan` | `realexecution.domain.solana` | worker e API | monta a mensagem v0 com chaves estáticas, mede o tamanho e calcula o resumo SHA-256; o `MessageBuilder` grava a mensagem não assinada antes de pedir a assinatura |
| `RealExecutionPort` (M6) | `realexecution.api` | os dois | `recordDecision` (`MANDATORY`), `hasOrderInProgress`, `cancelUnsent`, `recentOrders` |
| `RealOrderStateMachine`, `QuoteMath` | `realexecution.domain` | os dois | transições e códigos; `minOut` e frescor; sem I/O |
| `RealPnlCalculator` | `pnl.real` (domínio puro) | API | custo médio por mint, taxas, `unknown` (Q6) |
| `DecisionConsumer` | `realexecution.infra` | worker | F2 a cada 200 ms, `SKIP LOCKED`, com a trava da pessoa (M5 `withPersonLock`). Se não obtiver a trava, deixa a decisão `PENDING` para a próxima rodada |
| `OrderDriver` | `realexecution.infra` | worker | `PROPOSED` → `QUOTED` → `SIGNED` → `SENT`, `SKIP LOCKED` com posse renovada a cada etapa |
| `ConfirmationJob` | `realexecution.infra` | worker | F5 a cada 2 s, `SKIP LOCKED` com posse |
| `AmbiguousJob`, `SigningDeadlineJob`, `UpstreamHealthJob`, `LiquidationJob` | `realexecution.infra` | worker | varreduras com ShedLock: 15 s, 1 s, 30 s, 1 s |
| `JupiterClient` | `realexecution.infra.jupiter` | worker | cotação e instruções de swap, 2 s, circuito (Q3) |
| `SigningSessionService` | `realexecution.infra` | API | `GET` e `POST` da sessão; confere o resumo, o Ed25519 e o prazo, e reivindica a ordem (Q4) |
| `RealOrderController`, `RealModeController`, `LiquidationController`, `RealPnlController` | `realexecution.web`, `pnl.web` | API | rotas R3 |
| `RealExecutionEvents` | `realexecution.infra` | os dois | auditoria `MANDATORY` e marca do canal na mesma transação (ver `observability-design.md`) |
| `RealExecutionMetrics` | `realexecution.infra` | os dois | métricas, inclusive `real_execution_job_runs_total{job}` publicado por todo trabalho ao terminar bem |
| `SwapPolicy` | `apps/txsigner` | TxSigner | BR4.3 e U4-NFR1.5 sobre o leitor do TxSigner |
| `BotMarket` | `bots.domain` e migração | os dois | campo `market` (BR1.1) |
| Guardas de rede | `RealProperties` (API e worker, `MEMETRADE_REAL_NETWORK`) e `ProductionChecks` (TxSigner, `TXSIGNER_NETWORK`), que já existem | os três | ampliados: mainnet só no perfil de produção, com configuração explícita (U4-NFR8.1) |

## Mudanças em interfaces e configuração de outras Units

| Item | Mudança |
|---|---|
| M5 (U3) | `withPersonLock(tenantId, userId, ação)`; a trava interna continua oculta (Q7) |
| M1 (U1) | `signSwap` passa a chamar o R1 com `operationKind: SWAP` |
| R1 (TxSigner) | `/signatures` aceita `SWAP` (adendo da U4 no contrato) |
| `DashboardMarkPoller`, `DashboardEvents` e OpenAPI do canal (Bots) | tipos de evento novos `RealOrderStateChanged` e `RealSigningSessionOpened` |
| `RuntimeSettingsGuard` | conta abaixo |

## Pool do worker (corrige U4-NFR3.7)

O worker já tem 12 métodos `@Scheduled`:
- `BotSchedules`, 3;
- `BotWalletJobs`, 3;
- CoinGecko, 2;
- `RealRiskSchedule`, 3;
- o medidor de reservas, 1.

Com os 7 da U4 (`DecisionConsumer`, `OrderDriver`, `ConfirmationJob`, `AmbiguousJob`, `SigningDeadlineJob`, `UpstreamHealthJob`, `LiquidationJob`), são 19.

| Ajuste | Valor |
|---|---|
| `SCHEDULER_POOL_SIZE` padrão do worker | 19 (hoje 4) |
| Faixa do guard para o scheduler | 19 a 26 |
| Pool de conexões | 4 ciclos + 4 de reserva + 19 = 27 no mínimo |
| Faixa do guard para o pool de conexões | 27 a 40 |

O teste de contagem conta os métodos `@Scheduled` com papel de worker e falha se o mínimo do guard ficar abaixo deles.

**Conexões no PostgreSQL:** pelo máximo das faixas, cada processo abre:

| Processo | Conexões |
|---|---|
| API | até 50 |
| worker | até 40 |
| pool `risk_retention` | 1 |
| TxSigner | até 10, no próprio banco |
| migração | 2 |

O total dá até 103. O `max_connections` do PostgreSQL local e do RDS passa a 150, com folga para manutenção. Isso fica no Infrastructure Design.

**Redes divergentes:** se a rede da API (ou do worker) e a do TxSigner divergirem na conferência de subida, o processo não sobe e registra o erro com as duas redes. Em execução, a rota de saúde do TxSigner devolve a rede, e uma divergência deixa o REAL de todos pausado (`UPSTREAM`) até ser corrigida. O saque continua.

## Testes do TxSigner na CI

O `ci.yml` não roda os testes do `apps/txsigner`; só o `docker build` com `-DskipTests`. Até isso mudar, os testes de ataque da `SwapPolicy` e a mudança no leitor ficam sem a CI.
- **Texto pronto:** o job `txsigner` com `mvnw verify` e o piso de 80% já está no code-summary da U1. A U4 repete esse texto no seu code-summary.
- **Quem aplica:** o responsável do projeto.
- **Na Code Generation:** os testes do TxSigner rodam localmente e o resultado fica registrado.

## Falhas e raio de impacto

| Falha | Impacto | Contenção |
|---|---|---|
| Queda do worker | ordens param no estado gravado | a posse vence em 10 s e outra instância retoma com a mesma mensagem gravada; nenhuma reassinatura |
| Jupiter fora | ordens falham antes de assinar | circuito; pausa `UPSTREAM` na terceira falha seguida |
| RPC fora | envio e confirmação param; ambíguas ficam | troca de provedor; pausa `UPSTREAM`; alerta |
| TxSigner fora | assinatura falha | `FAILED` com a reserva liberada; alerta crítico |
| Mensagem adulterada ou com tabela | nada é assinado | conferência na API e de novo no TxSigner |
