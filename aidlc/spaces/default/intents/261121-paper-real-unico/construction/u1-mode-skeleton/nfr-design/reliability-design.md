# Desenho de confiabilidade — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

Este arquivo reúne quatro assuntos, na mesma divisão do NFR Requirements:

- o comportamento da U1 sob falha;
- a exatidão dos valores;
- os estados da interface;
- a verificação, inclusive a proteção da mudança do BR1.9.

As fontes de dados da U1 são o próprio banco e a configuração. Por isso não há circuit breaker novo nem tentativa automática no servidor [Q2] [Q3] [Q6].

## Modos de falha

| Falha | Onde | Comportamento | Raio de impacto | NFR |
|---|---|---|---|---|
| Bloco PAPER lança exceção ou passa do prazo | C4 | `paper` vem `UNAVAILABLE` com `code` e `correlationId`; `real` vem completo; 200 | só o bloco PAPER desse pedido | NFR4.4 |
| Bloco REAL lança exceção ou passa do prazo | C4 | `real` vem `UNAVAILABLE`; `paper` completo; 200 | só o bloco REAL desse pedido | NFR4.4 |
| Os dois blocos falham | C4 | os dois `UNAVAILABLE`; 200 | o pedido | NFR4.4 |
| Diretório C8 falha | C4 | o diretório é lido dentro da tarefa de cada bloco, então cada bloco falha por conta própria | igual às linhas acima | NFR4.4 |
| Pool `pnl-mode-block` cheio | C4 | o `submit` captura `RejectedExecutionException` e o bloco vem `UNAVAILABLE`; 200 | o bloco desse pedido | NFR4.4 |
| Mais ordens REAL que `real-max-orders` | C4 | o bloco REAL vem `UNAVAILABLE` (`cause=TOO_MANY_ORDERS`), sem número parcial | o bloco REAL | NFR4.4 |
| Preço ausente ou velho | C4 | **não** é falha: `unrealized` e o total ficam `UNKNOWN`; realizado e taxas exatos | a linha ou o total | NFR2.3 |
| Leitura da elegibilidade falha ou passa do prazo, antes de decidir um motivo | C1 REAL, C2 | 503 `SERVICE_UNAVAILABLE`; nada gravado; a criação PAPER segue | só o REAL | NFR4.5 |
| Leitura falha depois de um motivo decidido | C1 REAL, C2 | vale o motivo (409 em C1, 200 com `available = false` em C2), porque as leituras param no primeiro motivo | — | NFR4.5 |
| `RealAccountLimits` lança `RealLimitsUnavailable` | C3 | 503 `SERVICE_UNAVAILABLE` | só C3 | NFR4.5 |
| Gravação da auditoria da recusa falha | C1 REAL | 503; nada gravado; a repetição com a mesma chave reavalia | o pedido | NFR3.5 |
| Banco fora | todas | C4: os dois blocos `UNAVAILABLE`; C1, C2 e C3: 503 pelo `ProblemDetailsHandler`; prontidão cai pelo indicador `db` | o processo, como hoje | NFR4.7 |

## Isolamento entre os blocos de C4 (NFR4.4, NFR1.9)

- Cada bloco roda numa tarefa própria do pool `pnl-mode-block`, com transação própria, `statement_timeout` próprio e prazo de `memetrade.pnl.by-mode.block-timeout` (800 ms). O desenho de execução está em `performance-design.md`.
- O `ModePnlView` transforma o resultado de cada tarefa num destes dois casos:
  - **pronto:** o bloco calculado (`PaperModeBlock` ou `RealModeBlock`);
  - **falha** (prazo, exceção ou recusa da fila): `ModeBlockUnavailable` com `status = UNAVAILABLE`, `market`, `currency`, `code = SERVICE_UNAVAILABLE` e o `correlationId` do pedido, lido na thread do pedido.
- Cada falha gera o log `WARN` e o contador `pnl.mode_block.unavailable{market}` (ver `observability-design.md`).
- Nenhuma exceção de um bloco sai do `ModePnlView`. A rota nunca responde 500 inteira por causa de um modo.
- **Sem circuit breaker.** As duas fontes são o próprio banco. Um disjuntor só trocaria uma leitura que talvez funcione por uma falha certa, e o prazo já limita o custo de cada tentativa [Q2].
- **Teste (NFR4.4).** Um teste de integração (nome proposto `ModePnlFailureIT`) troca, com um bean de teste, o leitor do bloco PAPER e a implementação de C10 por versões que lançam exceção ou dormem além do prazo. Os 4 cenários:
  1. só o PAPER falha;
  2. só o REAL falha;
  3. os dois falham;
  4. pool e fila saturados: com `pool-size = 1` e `queue-capacity = 0` no teste, e a única thread presa por uma tarefa de teste, o envio é recusado (`RejectedExecutionException` capturada no `submit`).

  Em cada um, o teste confere:
  - 200, nunca 500;
  - o bloco que falhou `UNAVAILABLE`, com o `correlationId` da resposta;
  - o outro bloco completo (nos cenários 1 e 2);
  - o tempo do pedido abaixo de 1.000 ms no cenário do sono;
  - o contador da série certa.
- **Testes de unidade do `ModeBlockExecutor`:**
  - com MDC vazio (`getCopyOfContextMap()` nulo), a tarefa roda sem erro e a thread termina com o MDC limpo;
  - uma tarefa cuja transação abre depois do prazo (conexão de teste que demora) desiste sem nenhuma leitura.

## Elegibilidade na criação REAL: leitura com prazo dentro do comando idempotente (NFR4.5, NFR4.9)

### Sequência

1. O controlador valida o corpo: `market` (422), cabeçalho `Idempotency-Key` (400).
2. `BotCommandService.create` abre a transação do comando (READ COMMITTED) e aplica o `lock_timeout` de 2 s dos comandos (`LockOrder.applyCommandTimeouts`).
3. O `IdempotencyService` reivindica a chave. Se a chave já foi concluída com o mesmo corpo, ele devolve a resposta guardada **sem reavaliar a elegibilidade** (BR1.4).
4. Dentro da ação: validação do modelo e do nome (422) e, com `market = REAL`, a elegibilidade.
5. A elegibilidade lê as fontes num **ponto de salvamento** da transação do comando. Ela usa um `TransactionTemplate` com `PROPAGATION_NESTED`, que o `DataSourceTransactionManager` executa como `SAVEPOINT`. A leitura segue a ordem de BR2.1 e para no primeiro motivo. Antes de cada leitura, aplica `set local statement_timeout` com o tempo que resta de `memetrade.bots.real-eligibility-timeout` (500 ms); sem tempo restante, para com falha.
6. Sem motivo: `set local statement_timeout to default` (restaura o padrão para o resto do comando), inserção do bot, `BOT_CREATED` e a resposta 201 guardada pelo mecanismo comum.
7. Com motivo: exceção de domínio. A chave é liberada, a transação do comando é desfeita e, depois dela, vêm a auditoria em `REQUIRES_NEW`, o contador e o 409 (ver `security-design.md`).
8. Com falha de leitura (exceção de acesso a dados, `statement_timeout` com SQLSTATE `57014`, ou prazo esgotado):
   - o ponto de salvamento é desfeito, e a transação do comando volta a ser utilizável;
   - a elegibilidade registra `WARN` e lança uma exceção técnica (nome proposto `RealEligibilityUnavailable`), convertida em 503 `SERVICE_UNAVAILABLE`;
   - a chave é liberada e a transação é desfeita;
   - nada fica gravado e nenhuma resposta fica guardada.

### Por que o ponto de salvamento

No PostgreSQL, um erro dentro de uma transação a deixa abortada: qualquer comando seguinte falha com SQLSTATE `25P02`. O `IdempotencyService.runAndStore` chama `store.release(scope, key)` quando a ação falha. Sem o ponto de salvamento, essa liberação rodaria numa transação abortada e trocaria a exceção da elegibilidade por um erro `25P02`, que vira 500 em vez de 503.

O código de hoje evita o mesmo problema nas travas: o `BotCommandService.command` pega as travas **antes** da reivindicação, para que um `lock_timeout` falhe limpo com 503. A elegibilidade não pode ir para antes da reivindicação, porque a repetição de uma chave concluída precisa devolver a resposta guardada sem reavaliar (BR1.4). Daí o ponto de salvamento.

Um `ROLLBACK TO SAVEPOINT` também desfaz o `SET LOCAL` feito depois dele, então o comando volta ao `statement_timeout` padrão sozinho na falha.

Esboço ilustrativo das leituras com prazo:

```java
Instant deadline = clock.instant().plus(eligibilityTimeout);
return nested.execute(status -> {
    if (killSwitch.within(deadline).current().active()) return Eligibility.refused(KILL_SWITCH);
    if (!realEnabled) return Eligibility.refused(REAL_DISABLED);
    if (wallet.within(deadline).readRealState(tenant, user).isEmpty()) return Eligibility.refused(WALLET_INACTIVE);
    RealReleaseInfo release = state.within(deadline).readRelease(tenant, user);
    return RealEligibilityRule.fromRelease(release);   // NOT_RELEASED, QUARANTINE ou disponível
});
// within(deadline): calcula o tempo restante, aplica "set local statement_timeout" e falha se ele acabou
```

A regra de precedência (BR2.1) continua uma função pura no domínio do `bots`, testada por unidade para os 5 motivos e os 10 pares. O esboço só mostra a ordem das leituras e o prazo.

### C2 (leitura)

C2 usa o mesmo componente, numa transação só de leitura própria com o mesmo prazo, sem ponto de salvamento. A falha responde 503 com `WARN`; o motivo responde 200 com `available = false`.

### Testes de integração (nome proposto `RealBotCreationEligibilityIT`)

| Caso | Como provoca | Confere |
|---|---|---|
| Os 5 motivos | estado semeado (kill switch, `memetrade.real.enabled=false`, sem carteira, liberação NONE/BLOCKED/REVOKED, liberação QUARANTINE) | 409 `REAL_NOT_ELIGIBLE` com `reason` certo; 1 auditoria; 0 bots; 0 registros de idempotência da chave; o contador do motivo vale 1 |
| Regressão `WALLET_INACTIVE` | sem carteira | 409, nunca mais 422 |
| Prazo real do banco | fonte de teste que roda `select pg_sleep(1)` na transação do comando, com o prazo de 500 ms | 503 `SERVICE_UNAVAILABLE` (e não 500); 0 bots; 0 registros de idempotência; log `WARN` com o `correlationId` |
| Falha de leitura | fonte de teste que lança `DataAccessResourceFailureException` | 503; nada gravado |
| Motivo antes da falha | kill switch ligado e fonte da carteira que falharia | 409 `KILL_SWITCH`, porque a carteira nunca é lida |
| Repetição depois da recusa | mesma chave, estado corrigido para elegível | 201 e 1 bot: a recusa não ficou guardada |
| Repetição depois do 503 | mesma chave, fonte restaurada | 201 e 1 bot |
| Repetição de chave concluída | mesma chave e corpo depois de um 201, com o kill switch ligado em seguida | o mesmo 201 guardado, sem reavaliar |
| PAPER com a fonte quebrada | `market = PAPER`, fonte que falharia | 201 e 1 bot PAPER; a fonte não é lida |

## Idempotência e repetição (NFR4.9, NFR4.8)

- **C1.**
  - `Idempotency-Key` obrigatória (uuid); sem ela, 400.
  - Mesma chave e mesmo corpo: a mesma resposta e 1 bot.
  - Mesma chave com outro corpo: 409 `IDEMPOTENCY_CONFLICT`.
  - Recusa e 503 nunca guardados.
- **Do lado do cliente.** Num 5xx, tempo esgotado ou sem conexão, o diálogo reenvia com a **mesma** chave (BR2.8). Num 409 `REAL_NOT_ELIGIBLE`, a tela relê C2 e volta para "Simulado", sem repetir.
- **C2, C3 e C4** são leituras sem efeito. O teste do NFR4.8 conta as linhas das tabelas tocadas antes e depois de 3 leituras seguidas e espera 0 gravações. "Tentar de novo" relê C4 inteira.
- **O servidor não repete nada sozinho.** Pelo `team.md`, um erro técnico só é repetido quando a operação é idempotente, e quem decide a repetição é a pessoa na tela.

## Disponibilidade (NFR4.7)

- 0 SLOs novos.
- O grupo de prontidão (`readinessState`, `db`, `migrations`) não muda, e nenhuma rota da U1 entra na verificação de saúde. Uma falha de C4 é parcial e já aparece no contador; ela não deve tirar a tarefa do balanceador.
- Implantação e volta atrás: a U1 não tem migração (NFR1.13), então voltar é trocar a imagem. A U1 só vai para a mainnet com a U2 (NFR8.4).

## Exatidão (NFR2.1, NFR2.2, NFR2.3)

- **Tipos.**
  - REAL em `long` (lamports e unidades base) com `Math.*Exact`; as frações do `RealPnlCalculator` em `BigInteger`; os preços em `BigDecimal`.
  - PAPER em `BigDecimal`.
  - No JSON, os lamports saem como texto inteiro com sinal.
  - No `apps/web`, `bigint` só em `features/real/money.ts`, e a proibição de lint do dinheiro passa a valer em `features/pnl` (BR7.5).
  - A regra ArchUnit de ponto flutuante continua verde.
- **PAPER igual ao de hoje (NFR2.2).**
  - As linhas do bloco PAPER usam o mesmo cálculo de `GET /api/v1/pnl?botId=`, sem cópia.
  - Linhas e total saem da mesma transação REPEATABLE READ.
  - Teste com cenário de resultado conhecido: a soma das linhas PAPER mais a linha "Sem bot" (US$ 0,00 na U1) é igual ao total do modo calculado do ledger PAPER (`compareTo == 0`).
  - Outro teste compara cada linha com o resultado do cálculo de hoje para o mesmo bot e a mesma janela.
- **REAL na U1 (NFR2.3).**
  - Cada linha é o `RealPnlCalculator` sobre as ordens do próprio bot.
  - O total é o mesmo cálculo sobre todas as ordens da pessoa.
  - `realizedLamports` é líquido de taxas e o total é `realized + unrealized` (BR5.5).
  - Com preço ausente, `unrealized.status = UNKNOWN` e o total do bloco `UNKNOWN`, nunca zero.
  - Os cenários de resultado conhecido em lamports são escritos **antes** do código (núcleo financeiro, NFR7.6).

## Proteção do BR1.9 (raio de impacto e testes)

**A mudança.** Um bot REAL não tem limites próprios. Quatro caminhos que hoje leem a versão de limites de **todo** bot passam a ler só no ramo PAPER:

| Caminho | Código de hoje | Mudança |
|---|---|---|
| Validação do rascunho | `BotCommandService.validate` exige `virtualBalance` e `draftLimits` e grava `bot_limits_version` | no REAL: sem essas exigências e sem gravação; o rascunho REAL valida parâmetros e seleção |
| Ciclo | `CycleRunner`: `bot.currentLimitsVersion().orElseThrow()` e `bots.limits(...)` **antes** do desvio `if (bot.real())` | a leitura vai para o ramo PAPER, depois do desvio |
| Parar | `BotCommandService.stop`: `orElseThrow` da versão antes do `if (!bot.real())` | a exigência da versão vai para dentro do ramo PAPER |
| Retomar | `BotCommandService.resume`: `currentLimits(bot)` para todo bot | só o PAPER lê os limites atuais; o REAL retoma sem a checagem de perda do dia do PAPER |

**Raio de impacto além dos quatro caminhos**, achado na leitura do código:

- `DecisionRequest` exige `limitsVersion` (`long`) e `maxOrderSize` não nulo, e o `CycleRunner` monta o pedido com `version` e `limits.maxOrderSize()` também no ramo REAL. A tabela `decision` tem `limits_version integer NOT NULL`, sem checagem de mínimo. No ramo REAL, o pedido recebe os valores da decisão do fundador abaixo.
- `changeLimits` também grava uma versão de limites, para qualquer bot. Num bot REAL, ele passa a responder 422 (ver "Editar limites de um bot REAL").

### Decisão do fundador (2026-11-21): o `DecisionRequest` no ramo REAL

Decisão do fundador sobre o achado R-02 da revisão deste estágio. Ela vale para a Code Generation da U1.

- **`maxOrderSize`** é o limite de ordem da conta, convertido para US$:
  - em lamports, `floor(cap × orderMaxBps / 10000)`, a mesma conta da barreira REAL (`RealRiskPolicy`) e de C3 (BR3.1);
  - em US$, `RealSizing.dollarsOfLamports(limite, solUsd)`, com o `solUsd` do próprio ciclo (`RealCycle.Inputs.solUsd`) e arredondamento para baixo (`RoundingMode.DOWN`, 6 casas, como já faz `dollarsOfLamports`).
- **De onde vêm `cap` e `orderMaxBps` no worker:** da interface C9 `riskcontrol.api.RealAccountLimits.read(tenant, pessoa)`, a mesma fonte da rota C3. Ela lê o teto da carteira do bot ativa e a configuração da barreira (`memetrade.riskcontrol.real.*`, `RealRiskProperties`), dentro da transação do ciclo (com o `statement_timeout` de 5 s que já existe) e sem chamada de rede. A dependência `bots → riskcontrol.api` já está prevista no NFR Requirements (NFR7.10) e entra na regra ArchUnit do módulo.
- **`maxOrderSize = 0`** quando o teto não está definido (`orderMax` `NOT_DEFINED`, teto igual a zero), quando não há carteira ativa (C9 vazio), quando o preço do SOL é desconhecido, ou quando C9 lança `RealLimitsUnavailable`. Com 0, o dimensionador da decisão (`OrderSizer`) dá `QUANTITY_BELOW_MINIMUM`: **nenhuma compra** naquele ciclo. As vendas não usam esse teto e continuam. A falha de C9 gera log `WARN` com `cause`; o ciclo não falha por ela.
- **`limitsVersion = 0`** é um valor sentinela documentado: "o bot REAL não tem versão de limites própria; valem os limites REAL da conta". Ele fica gravado em `decision.limits_version`, sem migração (não há checagem de mínimo). Documentar:
  - no DTO da decisão em `BotDtos` (campo `limitsVersion`), no Javadoc: `0` só em decisão REAL e significa "limites da conta";
  - na descrição do campo no `openapi-guest.yaml`, com o mesmo texto;
  - na tela, a decisão de um bot REAL mostra "Limites da conta" no lugar do número da versão.
- **Por que essa opção:** com `maxOrderSize = availableBalance`, o teto nunca cortaria, e o bot REAL proporia todo ciclo uma compra acima do limite da conta. A barreira recusaria com `REAL_LIMIT_EXCEEDED/ORDER_SIZE`, gerando ruído de recusas e talvez pausas. Com o limite da conta, a decisão já nasce dentro do que a barreira aceita, e a barreira continua sendo o portão duro.
- **O que falta no functional design.** O BR1.9 do `functional-design/rules.md` não tem esse detalhe. Ele precisa ser acrescentado ao BR1.9, e a Code Generation lê as duas fontes (o BR1.9 e esta seção) até lá.

### Editar limites de um bot REAL

- `BotCommandService.changeLimits` num bot REAL responde **422 `BOT_VALIDATION_FAILED`** no campo `limits` ("Um bot REAL usa os limites REAL da conta."), sem gravar versão. É um erro de regra de negócio, na convenção 422 do contrato.
- **Contrato:** acrescentar o 422 com esse motivo à descrição da rota existente `POST /api/v1/bots/{botId}/limits` (`changeBotLimits`) no `openapi-guest.yaml`, no mesmo commit (regra de posse 1). A forma da resposta não muda: o `Problem` de 422 já está declarado na rota.
- **Bots REAL antigos (intent `261113-trading-real-bot`).** Bots REAL criados antes da U1 podem ter versões de limites gravadas em `bot_limits_version`. Elas ficam no banco (a tabela é imutável e não se apaga histórico), mas o ramo REAL do ciclo, `stop` e `resume` as **ignoram**: um bot REAL antigo passa a usar só os limites da conta, como um bot REAL novo. O `BotModePathIT` tem um caso com um bot REAL que já tem uma versão de limites gravada e confere que o ciclo usa o teto da conta.
- Outros leitores: `BotQueries.detail` e `BotSummaryView` já toleram a ausência da versão (`Optional`, `orElse(null)`). `MidnightJob` e `ClosingCheckJob` não leem a versão de limites.
- O caminho REAL do ciclo (`RealCycle.order`, `RealSizing`, barreira REAL) não usa `BotLimits`. O tamanho e os limites vêm da carteira e da configuração da barreira (BR3.1).

**Testes que protegem a mudança:**

1. **Teste do percurso (NFR7.4)**, de integração, nome proposto `BotModePathIT`.
   - Bot REAL sem saldo virtual nem limites próprios: validar, ativar, rodar um ciclo (`CycleRunner` com o REAL ligado no teste e as fontes simuladas), pausar, retomar e parar. Confere 0 exceções, 0 linhas em `bot_limits_version` para o bot e um resultado de ciclo diferente de `FAILED`.
   - Casos do `DecisionRequest` REAL (decisão do fundador):
     - com teto definido, a compra proposta fica dentro de `floor(cap × orderMaxBps / 10000)` convertido para US$, e a barreira REAL não recusa por `ORDER_SIZE`; a decisão gravada tem `limits_version = 0`;
     - com teto zero (não definido), a decisão é `NO_TRADE` com `QUANTITY_BELOW_MINIMUM` e nenhuma ordem REAL é criada;
     - um bot REAL antigo, com uma versão de limites já gravada, usa o teto da conta e não a versão.
   - `changeLimits` num bot REAL responde 422 e não grava versão.
   - O mesmo percurso para um bot PAPER, conferindo o comportamento de hoje: 1 versão de limites gravada na validação, a perda do dia lida na retomada e as posições fechadas na parada.
   - Escrito antes do código, porque toca a lógica da carteira do bot e dos estados REAL (`team.md`, Ordering).
2. **Suítes atuais verdes como regressão do PAPER:** `CycleIT`, `BotCommandsIT`, `MidnightIT`, `ClosingCheckIT`, `LockOrderIT`, `RealBotDecisionIT`, `RecordedSwapCycleIT` e `LockClauseSourceTest`. Os testes REAL que hoje criam um bot REAL **com** limites são ajustados para o bot REAL sem limites, nunca apagados.
3. **Testes de unidade** de `validate` para o rascunho REAL:
   - aceito sem saldo nem limites;
   - recusado com 422 se receber saldo virtual ou limites próprios (BR1.8).
4. O spec de ponta a ponta da fatia (NFR7.7) cria e inicia um bot REAL pela tela e faz uma compra. É a prova integrada de que o ciclo REAL sem limites próprios funciona no validador local.

## Interface (NFR5.1 a NFR5.3, NFR6.1, NFR6.2)

- **`PnlModeBlock`.** Máquina com 5 estados (Carregando, Pronto, Vazio, Desconhecido, Erro), cada um com texto próprio. O Erro corresponde a um bloco `UNAVAILABLE` ou a uma falha da rota. "Tentar de novo" relê C4 e atualiza só aquele bloco. Teste de componente para cada estado.
- **Teclado e foco.** `ModeStep` com rádios nativos e "Real" indisponível alcançável; `RealConfirmDialog` e `RealBotLimitsStep` acionáveis por Tab, Enter e Espaço, com foco visível. Teste de componente com Testing Library por controle.
- **Contraste e cor.** Tokens de cor com contraste de pelo menos 4,5:1 no tema escuro; nenhum estado indicado só por cor.
- **Leitor de tela.** O total de cada bloco tem nome acessível com o modo e a moeda; a tela tem um `h1` e um `h2` por modo.
- **360 px.** Cartões empilhados. O spec de ponta a ponta confere `scrollWidth <= clientWidth` em `/pnl` e `/bots`.

## Verificação (NFR7.1 a NFR7.8)

| ID | Desenho |
|---|---|
| NFR7.1 | Test Strategy `Standard`: unidade e integração de cada BR1.1 a BR10.5, com o caminho feliz e pelo menos 2 casos de erro ou borda por componente |
| NFR7.2 | Os 7 testes obrigatórios por risco: isolamento e 404; separação e nunca somar; REAL desligado; novo aceite nas 4 rotas; idempotência da criação (inclusive a variante REAL); `unknown`; P&L determinístico |
| NFR7.3 | JaCoCo (unidade e integração mesclados) de pelo menos 80% no backend; Jest com `coverageThreshold.global.lines = 80` no `apps/web`; o piso nunca é reduzido; as classes novas não entram nas exclusões |
| NFR7.4 | `BotModePathIT` (acima) |
| NFR7.5 | `RealBotCreationEligibilityIT` (os 5 motivos, a regressão `WALLET_INACTIVE`) e o teste de unidade da função pura de BR2.1 (5 motivos e 10 pares) |
| NFR7.6 | Antes do código: P&L por modo (NFR2.2, NFR2.3), deduplicação da criação (NFR4.9), percurso do BR1.9. Depois de cada camada: telas, rotas de leitura, configuração, métricas |
| NFR7.7 | Spec novo da fatia no validador local, acrescentado ao `scripts/verify.sh` pelo responsável; os jobs `smoke` e `e2e` devolvem 0 |
| NFR7.8 | O alargamento de `mode` para REAL, o leitor do resumo do bot no painel, as fixtures e o teste de contrato do `apps/web` entram no mesmo commit; os jobs `frontend` e `contract` verdes |

## Sources

- `reviews/review-01.md`: achados R-01 a R-06 da revisão deste estágio; decisão do fundador de 2026-11-21 sobre o `DecisionRequest` do ramo REAL.
- `nfr-design-questions.md`: Q2, Q3, Q6 e o resumo confirmado [Q2] [Q3] [Q6].
- `construction/u1-mode-skeleton/nfr-requirements/reliability-requirements.md`: NFR2.1 a NFR2.3, NFR4.4, NFR4.5, NFR4.7 a NFR4.9, NFR5.1 a NFR5.3, NFR6.1, NFR6.2, NFR7.1 a NFR7.8.
- `construction/u1-mode-skeleton/nfr-requirements/performance-requirements.md`: NFR1.9.
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR1.4, BR1.8, BR1.9, BR2.1, BR2.4, BR2.5, BR2.8, BR5.3 a BR5.5, BR5.9, BR5.10, BR7.5; suposições 3 e 4.
- `construction/u1-mode-skeleton/functional-design/frontend-components.md` e `functional-spec.md` (F2 a F5).
- `inception/contract-design/contract-summary.md`: C1 (repetição segura), C3 e C9 (`RealLimitsUnavailable`), C4 (`ModeBlockUnavailable`), C10.
- Código lido:
  - `identity/domain/idempotency/IdempotencyService.java` (`runAndStore` e `store.release` na falha);
  - `bots/application/BotCommandService.java` (`create`, `validate`, `stop`, `resume`, `command`);
  - `bots/infra/LockOrder.java` (travas antes da reivindicação, `55P03` para 503);
  - `bots/worker/CycleRunner.java` (leitura dos limites antes do desvio REAL, `DecisionRequest`);
  - `decision/domain/DecisionRequest.java`; migração `0010-bots-decision.sql` (`decision.limits_version`, `bot_limits_version`);
  - `shared/error/ProblemDetailsHandler.java` (503 para `QueryTimeoutException` e falhas do banco);
  - `administration/domain/SystemStatusAssembler.java`;
  - `application.yml` (grupo de prontidão).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (Ordering, Volume, Piso, testes obrigatórios), Code Style (erros técnicos só repetidos quando idempotentes, sem falha silenciosa).

## Assumptions & Open Questions

1. [assumption] O `PROPAGATION_NESTED` (ponto de salvamento) é suportado pelo `DataSourceTransactionManager` do app, que usa JDBC sem JPA. A Code Generation confirma isso no teste do prazo real do banco (`pg_sleep`), que precisa dar 503 e não 500.
2. **Decidido (fundador, 2026-11-21, achado R-02):** no ramo REAL, `maxOrderSize` é o limite de ordem da conta em US$ (`floor(cap × orderMaxBps / 10000)` por `RealSizing.dollarsOfLamports`, com o `solUsd` do ciclo, para baixo); 0 sem teto, sem carteira, sem preço ou com C9 indisponível, o que dá `QUANTITY_BELOW_MINIMUM` e nenhuma compra; `limitsVersion = 0` como sentinela documentado no DTO e no OpenAPI. Ver "Decisão do fundador". **Pendência:** o BR1.9 do `functional-design/rules.md` precisa ganhar esse detalhe; a Code Generation lê o BR1.9 e esta seção.
3. **Resolvido no desenho (achado R-05):** `changeLimits` num bot REAL responde 422 `BOT_VALIDATION_FAILED`, documentado na rota `/api/v1/bots/{botId}/limits` do `openapi-guest.yaml`; as versões de limites de bots REAL antigos do `261113-trading-real-bot` ficam no banco e são ignoradas no ramo REAL. Ver "Editar limites de um bot REAL". O BR1.9 lista quatro caminhos; este é o quinto, e também precisa entrar no BR1.9.
4. [assumption] Na retomada de um bot REAL, a checagem de perda do dia do PAPER não se aplica; a pausa por perda do dia do REAL continua com a barreira REAL e as pausas da carteira (261113).
