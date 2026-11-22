# Desenho de observabilidade — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

A U1 mede duas coisas novas, sem dado pessoal: as recusas REAL na criação, por motivo, e os blocos `UNAVAILABLE` da visão por modo, por modo. Os logs dela levam o `correlationId` que já está no MDC. Não há painel, alarme nem rastreamento novos, e o p95 fica só no log do teste de integração [Q5].

## Métricas

### Convenção

As métricas do código usam Micrometer e são exportadas em `/actuator/prometheus` (porta de gestão 8081).

- O nome no Micrometer leva o prefixo do módulo dono.
- O exportador troca `.` por `_` e acrescenta `_total` aos contadores.
- As etiquetas levam só valores de enum.
- Todo contador é registrado com zero na subida para cada valor da etiqueta, como no `RealOpsMetrics`.

### Contador 1: recusas REAL na criação (NFR3.10)

| Item | Valor |
|---|---|
| Nome no Micrometer | `bots.real.create.refusals` |
| Nome exportado | `bots_real_create_refusals_total` |
| Etiqueta | `reason` ∈ {`KILL_SWITCH`, `REAL_DISABLED`, `WALLET_INACTIVE`, `NOT_RELEASED`, `QUARANTINE`} (5 séries) |
| Descrição | `REAL bot creations refused with 409 REAL_NOT_ELIGIBLE, by strongest reason (NFR3.10)` |
| Dono e classe | módulo `bots`; classe nova `BotCreationMetrics` (nome proposto) em `com.memetrade.bots.infra`, `@Component` **sem** `@ProcessRole`. O `BotCommandService`, que a chama, é um `@Service` sem `@ProcessRole` e existe nos dois processos; com a métrica só na API, o contexto do worker não subiria. É o mesmo padrão do `RealOpsMetrics` e do `BotAudit`, que existem nos dois processos (ver "Processos" abaixo) |
| Registro | no construtor, um `Counter` por valor do enum do motivo (nome proposto `RealIneligibilityReason`), guardado num `EnumMap` |
| Incremento | em `BotCommandService.create`, uma vez por resposta 409 `REAL_NOT_ELIGIBLE`, **depois** que a auditoria em `REQUIRES_NEW` foi gravada. Uma repetição com a mesma chave que reavalia e recusa de novo conta de novo. Um 503 não conta. |

Registro exato:

```java
public BotCreationMetrics(MeterRegistry meters) {
    for (RealIneligibilityReason reason : RealIneligibilityReason.values()) {
        refusals.put(reason, Counter.builder("bots.real.create.refusals")
                .description("REAL bot creations refused with 409 REAL_NOT_ELIGIBLE, by strongest reason (NFR3.10)")
                .tag("reason", reason.name())
                .register(meters));
    }
}

public void refused(RealIneligibilityReason reason) {
    refusals.get(reason).increment();
}
```

### Contador 2: blocos indisponíveis da visão por modo (NFR4.6)

| Item | Valor |
|---|---|
| Nome no Micrometer | `pnl.mode_block.unavailable` |
| Nome exportado | `pnl_mode_block_unavailable_total` |
| Etiqueta | `market` ∈ {`PAPER`, `REAL`} (2 séries); a etiqueta usa `market`, o campo da API e do domínio, e não `mode`, que está obsoleto |
| Descrição | `Blocks of GET /api/v1/pnl/by-mode answered UNAVAILABLE, by market (NFR4.6)` |
| Dono e classe | módulo `pnl`; classe nova `PnlModeMetrics` (nome proposto) em `com.memetrade.pnl.modeview`, `@Component` com `@ProcessRole(ProcessRole.Kind.API)`. Aqui o papel só da API é seguro: toda a cadeia de C4 (controlador, `ModePnlView`, `ModeBlockExecutor`, `PnlModeMetrics`) é só da API, e nenhum bean dos dois processos depende dela |
| Registro | no construtor, um `Counter` por valor de `PnlMarket` (C8), num `EnumMap` |
| Incremento | no `ModePnlView`, na thread do pedido, uma vez por bloco devolvido com `status: UNAVAILABLE` (prazo, exceção ou recusa da fila). Com os dois modos em falha, as duas séries sobem 1. |

Registro exato:

```java
public PnlModeMetrics(MeterRegistry meters) {
    for (PnlMarket market : PnlMarket.values()) {
        unavailable.put(market, Counter.builder("pnl.mode_block.unavailable")
                .description("Blocks of GET /api/v1/pnl/by-mode answered UNAVAILABLE, by market (NFR4.6)")
                .tag("market", market.name())
                .register(meters));
    }
}
```

`Counter.increment()` é chamada do Micrometer, não aritmética de valores PAPER ou REAL. A regra `NEVER_SUM_MODES` proíbe as somas dos tipos de resultado e os acessores de valor, não a contagem de eventos.

### Restrições das duas métricas

- 0 etiquetas com tenant, pessoa, bot, carteira, endereço ou `correlationId`. São 7 séries novas no processo da API (5 + 2) e 5 no worker (as de `bots.real.create.refusals`, sempre em zero, porque o worker não recebe pedidos de criação).

### Processos (achado R-01 da revisão)

Das três opções da revisão (tirar o `@ProcessRole(API)`, injetar por `ObjectProvider` ou mover a criação para um bean só da API), a escolhida é **tirar o `@ProcessRole(API)`** de `BotCreationMetrics` e de `RealCreationRefusalAudit`, e não pôr `@ProcessRole` na `RealCreationEligibility`. Motivos:

- é o padrão do código: `RealOpsMetrics` (registra as séries com zero nos dois processos) e `BotAudit` são `@Component` sem papel e servem aos dois processos;
- `ObjectProvider` deixaria uma dependência opcional que pode faltar em silêncio, e mover a criação para outro bean mexe no `BotCommandService` além do necessário;
- as três classes não têm dependência só da API: usam `MeterRegistry`, `AuditEventPublisher`, `RealStateQuery`, `RealKillSwitchState` e a configuração, que existem nos dois processos.

**Testes:** o `WorkerProcessRoleContextIT` e o `RealWorkerWiringIT` (contexto do worker) e o `ProcessRoleContextIT` (contexto da API) continuam verdes, e o contexto do worker passa a conferir que `BotCreationMetrics` existe e que nenhum bean de `pnl.modeview` existe nele.
- Os 503 de C1, C2 e C3 não ganham métrica nova. Eles já aparecem em `http_server_requests_seconds_count{uri=...,status="503"}`, com histograma ligado.

### Testes das métricas

- **Unidade com `SimpleMeterRegistry`:**
  - depois de 1 recusa de cada motivo, cada série de `reason` vale 1;
  - depois de uma falha injetada no REAL, `pnl.mode_block.unavailable{market="REAL"}` vale 1 e a de `PAPER` vale 0;
  - com os dois em falha, as duas valem 1.
- **Integração:** `/actuator/prometheus` contém `bots_real_create_refusals_total` e `pnl_mode_block_unavailable_total` com as séries acima, e nenhuma etiqueta fora das listas.

## Logs estruturados (NFR3.11)

Os logs saem em JSON pelo `LogstashEncoder` do `logback-spring.xml`, com `timestamp` (UTC), `level`, `component`, `message` e `correlationId` (lido do MDC). Todo valor de texto passa pela máscara Base58. As mensagens são em inglês, e os valores variáveis vão como argumentos do SLF4J, nunca concatenados.

| Evento | Classe que registra | Nível | Mensagem (modelo) | Campos variáveis |
|---|---|---|---|---|
| Recusa REAL na criação | `BotCommandService` (depois da auditoria) | `INFO` | `REAL bot creation refused: reason={}` | o motivo (enum) |
| Bloco `UNAVAILABLE` | `ModePnlView` (thread do pedido) | `WARN` | `P&L block unavailable: market={}, cause={}` | `PAPER` ou `REAL`; `TIMEOUT`, `REJECTED` (fila cheia) ou o nome simples da classe da exceção |
| Falha da fonte da elegibilidade | `RealCreationEligibility` (leitor) | `WARN` | `REAL eligibility unavailable: operation={}, cause={}` | `CREATE` ou `READ`; `TIMEOUT` ou o nome simples da classe da exceção |
| Falha da gravação da auditoria da recusa | `BotCommandService` | `WARN` | `REAL refusal audit failed: reason={}, cause={}` | o motivo; a classe da exceção |

- **Nunca nos logs da U1:** endereço de carteira, mint da carteira, token de sessão, cabeçalho `Authorization`, nonce, assinatura, corpo do pedido, valores de P&L e mensagem de exceção. A mensagem pode conter SQL ou dados; só o nome da classe vai para o log, como já faz o `ProblemDetailsHandler`.
- **Sem falha silenciosa.** Nenhum `catch` vazio. Toda exceção capturada pela U1 vira resultado explícito (`ModeBlockUnavailable`, 503) **e** é registrada.
- **Teste de captura de log**, com um appender de teste do Logback, para os 3 eventos principais:
  - 1 registro por evento, com o `correlationId` igual ao da resposta;
  - o nível da tabela;
  - 0 registros com o endereço semeado, o token do pedido ou o cabeçalho `Authorization`.

## Correlação (NFR3.12)

- O `CorrelationIdFilter` aceita um `X-Correlation-Id` uuid ou gera um, põe no MDC (`correlationId`) e devolve no cabeçalho da resposta.
- O corpo RFC 7807 do 409 e do 503 leva o `correlationId` pelo `ProblemDetailsHandler`, como hoje.
- O `ModeBlockUnavailable` leva `CorrelationId.current()` lido **na thread do pedido**.
- **Propagação para o pool dos blocos.** O MDC é local da thread. O `ModeBlockExecutor` captura `MDC.getCopyOfContextMap()` na thread do pedido, aplica a cópia na thread do pool no começo de cada tarefa e chama `MDC.clear()` no fim. Sem isso, um log dentro do bloco sairia sem o `correlationId`, e um `CorrelationId.current()` lá dentro geraria um id novo, diferente do da resposta.
- **Teste:** o `correlationId` da resposta (cabeçalho e corpo do 409, do 503 e do bloco `UNAVAILABLE`) é igual ao do registro capturado em 100% dos casos testados. Um caso cobre um log escrito dentro da tarefa do bloco.

## Rastreamento distribuído (NFR3.13)

Nenhuma biblioteca de tracing nova. A U1 não chama outro processo (NFR1.8): os blocos e a elegibilidade leem só o banco e a configuração. A correlação entre resposta e log é o `correlationId`.

## Alarmes e painéis (NFR8.5)

- 0 regras novas em `infra/observability/*.yml`. O `promtool check rules` do job `backend` continua com o mesmo número de regras.
- 0 alarmes do CloudWatch novos em `infra/terraform/` e 0 painéis novos.
- **Candidatos para um estágio futuro**, só registrados aqui, sem entrar na U1:
  - `increase(pnl_mode_block_unavailable_total[15m]) > 0` por mais de uma janela;
  - um salto de `bots_real_create_refusals_total{reason="KILL_SWITCH"}` fora de um acionamento conhecido do kill switch.

  Se um dia entrarem, seguem a correção do `project.md` sobre o CloudWatch Agent: leitura a cada 120 s, janelas de 240 s para alarmes com dado ausente e de pelo menos 180 s nos demais.

## SLI e SLO (NFR1.14)

- **SLI de latência das rotas da U1:** o p95 de `http.server.requests` por `uri` (`/api/v1/bots`, `/api/v1/bots/real-eligibility`, `/api/v1/real/account-limits`, `/api/v1/pnl/by-mode`), que já tem histograma.
- **Nenhum SLO operacional novo na U1.** O p95 de C1 a C4 é medido e registrado no log pelo `ModePnlLatencyIT` (NFR1.5). O portão de 1.000 ms de C4 é o cenário de carga da CI da U2 (NFR1.6).
- Num ambiente com usuários, a latência só é observada depois que a U2 for implantada junto (NFR8.4).
- **Indicador auxiliar de qualidade de C4:** a razão entre `pnl_mode_block_unavailable_total` e `http_server_requests_seconds_count{uri="/api/v1/pnl/by-mode"}`. Ela fica disponível para consulta sem meta.

## Sources

- `reviews/review-01.md`: achados R-01 a R-06 da revisão deste estágio; decisão do fundador de 2026-11-21 sobre o `DecisionRequest` do ramo REAL.
- `nfr-design-questions.md`: Q5 e o resumo confirmado [Q5].
- `construction/u1-mode-skeleton/nfr-requirements/observability-requirements.md`: NFR1.14, NFR3.10 a NFR3.13, NFR4.6, NFR8.5.
- `construction/u1-mode-skeleton/nfr-requirements/security-requirements.md`: NFR3.5 a NFR3.7.
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR2.4, BR2.5, BR5.3, BR5.8.
- `inception/contract-design/contract-summary.md`: C1 a C4 (`correlationId` no RFC 7807 e em `ModeBlockUnavailable`).
- Código lido:
  - `bots/worker/CycleFailureRecorder.java` (`Counter.builder("bots.cycle.errors")` com registro por valor de enum);
  - `botwallet/infra/RealOpsMetrics.java` (contadores registrados com zero na subida);
  - `shared/web/CorrelationId.java` e `CorrelationIdFilter.java` (MDC `correlationId`);
  - `logback-spring.xml` (`LogstashEncoder`, `includeMdcKeyName`, máscara Base58);
  - `shared/error/ProblemDetailsHandler.java` (só a classe da exceção no log);
  - `application.yml` (`management`, Prometheus, histograma de `http.server.requests`).
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos, nonces e assinaturas); correção do CloudWatch Agent (120 s, janelas).
- `aidlc/spaces/default/memory/team.md`: Code Style (sem falha silenciosa, idioma dos logs).

## Assumptions & Open Questions

1. [assumption] Os nomes de classe `BotCreationMetrics`, `PnlModeMetrics`, `ModeBlockExecutor` e `RealIneligibilityReason` são propostas; os nomes das métricas e das etiquetas são os do NFR Requirements e só mudam com uma decisão registrada.
2. [assumption] Registrar as séries com zero na subida, como o `RealOpsMetrics`, atende ao critério do NFR4.6 ("a de `PAPER` não existe ou vale 0") e deixa um alarme futuro sem o problema de série ausente.
3. Os alarmes candidatos ficam fora da U1 por decisão da Q5; nenhum entra sem um estágio que o decida.
