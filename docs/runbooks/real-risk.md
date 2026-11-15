# Runbook — barreira de risco REAL: recusas, pausa por perda, reservas, lista de bloqueio e rollback

Intent `261113-trading-real-bot`, Unit U3 (`u3-real-risk`). Fontes: `functional-design/rules.md` (BR1.1 a BR7.3), `nfr-design/reliability-design.md`, `infrastructure-design/infrastructure-specification.md` ("Implantação e rollback") e `infrastructure-design/monitoring-design.md` (alertas).

Quem executa: o fundador. Nenhum passo deste runbook pede chave, segredo ou endereço de carteira. Os logs e as métricas da barreira nunca trazem endereço, e o mint aparece só mascarado (`LogMasking`).

## O que a barreira faz

A barreira REAL (M5, `RealRiskGate`) decide se uma ordem REAL pode seguir para a execução (U4). Ela não monta, não assina e não envia transação. A ordem fixa das conferências é:

1. estado da carteira (`REAL_PAUSED`);
2. tipo de fonte (`SIMULATED_SOURCE_ACTIVE`);
3. token (`TOKEN_NOT_ELIGIBLE`);
4. frescor (`STALE_DATA`);
5. limites (`REAL_LIMIT_EXCEEDED`), inclusive a perda do dia;
6. teto e saldo livre (`REAL_CAP_EXCEEDED`).

A venda passa pelo estado e pela fonte e por nada mais. O saque da U1 nunca passa pela barreira.

Erro técnico em qualquer leitura (carteira, ledger, mercado, avaliação) ou trava não obtida em 1 s: a barreira **falha fechada**. Nenhuma ordem é aprovada, o erro conta em `real_risk_errors_total{dependency}` e a decisão do bot fica para o próximo ciclo. A liquidação pedida pela pessoa responde 503.

## 1. Entender uma recusa

1. Cada recusa vira uma linha em `real_risk_evaluation` (`outcome = 'REJECTED'`, `code`, `detail`, `figures`) e um evento de auditoria `REAL_ORDER_REJECTED`, com o mint só no campo tipado.
2. O log `REAL order refused: code=…, detail=…` (INFO) traz o lado, a origem e o mint mascarado.
3. Os números da conferência estão em `figures`, sempre da própria carteira:
   - `ORDER_SIZE`: `inputLamports`, `limitLamports` e `capLamports`;
   - `DAILY_ORDER_COUNT`: `approvedBuysToday` e `dailyBuysMax`;
   - `DAILY_LOSS`: `lossLamports`, `baseLamports` e `dailyLossMaxBps`;
   - `CAP`: `managedLamports`, `inputLamports` e `capLamports`;
   - `FREE_BALANCE`: `inputLamports` e `availableLamports`;
   - `SIGNAL_STALE`: `ageSeconds` e `maxAgeSeconds`.
4. Para `POSITION_PRICE_UNKNOWN`, o mint da posição sem preço está em `detail_mint`. O caso mais comum é faltar a cotação do SOL embrulhado (`So11111111111111111111111111111111111111112`): sem ela, o preço de todas as posições fica `unknown` e nenhuma compra passa. Confira se o mint está em `WatchedTokens` (U4) e se a fonte `coingecko` está OK.
5. Uma decisão recusada **não é reavaliada**, mesmo que as condições mudem. O ciclo seguinte do bot gera uma decisão nova.

## 2. Pausa por perda do dia (`DAILY_LOSS`)

- **Quando acontece:** a carteira pausa quando a perda do dia chega a 20% da base, que é o menor valor entre o teto do início do dia e o teto atual. Quem pausa é a compra recusada (na transação do ciclo) ou o trabalho `daily-loss`, a cada minuto. A pausa é gravada pela M1 em `real_preferences`, com o evento `REAL_PAUSED` (`reason = DAILY_LOSS`), e conta em `real_risk_daily_loss_pauses_total`.
- **O que para:** as compras e as vendas do bot. A liquidação "vender tudo e sacar" da pessoa continua, e o saque também.
- **Quando volta:** o trabalho `day` retoma a pausa a partir de 00:00 UTC do dia seguinte. Nenhuma ação manual é necessária.
- **Se o ciclo foi desfeito:** a pausa some junto com a decisão, mas o trabalho `daily-loss` recalcula e pausa em até 1 minuto.
- **Retomar antes da hora:** não há rota para isso nesta Unit. A retomada manual de uma pausa `DAILY_LOSS` exige decisão do fundador e fica para a área do administrador (U5).

## 3. Reserva vencida (alerta `RealRiskReservationExpired`)

1. Uma compra aprovada vira reserva `OPEN` por 5 minutos. Ela ocupa o teto e o saldo livre até a U4 chamar `handOver` na mesma transação do `RESERVE` do ledger.
2. O alerta indica que a U4 não consumiu a decisão a tempo. O trabalho `reservation-expiry` passou a reserva a `EXPIRED`, e o teto voltou a ficar livre. Uma reserva vencida nunca vira ordem: `handOver` responde `RESERVATION_EXPIRED`.
3. O que conferir:
   - o consumidor da fila da U4 e os seus logs;
   - o alerta `WorkerDown` da U1;
   - a consulta `select state, count(*) from real_risk_reservation where created_at > now() - interval '1 hour' group by state`.
4. Nada precisa ser corrigido no banco. As tabelas são append-only, e a reserva só muda de `OPEN` para um estado final.

## 4. Lista de bloqueio de tokens

- Só a área do administrador (U5, módulo `administration`) chama `blockToken` e `unblockToken`. Uma regra ArchUnit impede outro módulo de chamá-las.
- Bloquear: o token deixa de ser comprado por qualquer bot REAL, e as vendas continuam. Repetir o bloqueio não muda nada. O motivo é obrigatório e tem até 500 caracteres. Não escreva endereço de carteira, chave ou dado pessoal no motivo, porque ele vai para a trilha de auditoria (que recusa endereço completo).
- Desbloquear: apaga a linha e gera o evento `REAL_TOKEN_UNBLOCKED` com o motivo anterior.
- Até a U5 existir, não há rota. Em emergência, use o kill switch REAL ou desligue o REAL (seção 6), e nunca edite `real_blocked_token` à mão.

## 5. Erros da barreira (alerta `RealRiskErrors`) e lentidão (`RealRiskSlow`)

| Dependência (`real_risk_errors_total`) | O que conferir |
|---|---|
| `lock` | Outra transação segura a trava da carteira por mais de 1 s. Veja ciclos longos no worker; a trava acaba com a transação. |
| `wallet` | Leitura da BotWallet (M1) falhando: banco. |
| `ledger` | Leitura do ledger REAL (M2) falhando: banco. |
| `market` | Leitura do mercado (M4) falhando: banco. |
| `assessment` | Não é usado para contar: a falha da avaliação vira recusa `NO_ASSESSMENT`. |

`RealRiskSlow` é aviso, sem portão: o p95 da compra passou de 150 ms por 10 minutos. Veja `real_risk_lock_wait_seconds` (disputa pela trava) e a carga do banco.

## 6. Trabalhos parados (alertas `RealRiskJobStalled*`) e rollback

Os três trabalhos do worker publicam `real_risk_job_runs_total{job}` só depois de uma execução bem-sucedida:

| Trabalho | Frequência | Janela do alerta |
|---|---|---|
| `day` (fotos do dia e retomada da pausa `DAILY_LOSS`) | a cada 5 min | 15 min |
| `daily-loss` (perda do dia) | a cada minuto | 5 min |
| `reservation-expiry` (vencimento das reservas) | a cada minuto | 15 min |

O expurgo (`purge`) roda às 04:00 UTC na API e grava cada execução em `real_risk_purge_run`.

Quando um alerta de trabalho parado dispara:

1. Confira se o worker está no ar (alerta `WorkerDown` da U1) e os logs `REAL risk job failed`, que trazem o nome do trabalho e a classe do erro.
2. Uma falha numa carteira não para as outras, mas a execução não conta como sucesso e o alerta continua até a falha sumir.

**Rollback da U3** (`infrastructure-specification.md`):

1. Desligue o REAL primeiro (`memetrade.real.enabled=false` na API e no worker, `txsigner.real.enabled=false` no TxSigner) ou acione o kill switch REAL. O saque continua disponível o tempo todo.
2. Volte a imagem: primeiro a API, depois o worker. A migração 0018 fica (expand-contract); as tabelas novas ficam sem uso.
3. **Silencie os três alertas `RealRiskJobStalledDailyLoss`, `RealRiskJobStalledDay` e `RealRiskJobStalledReservationExpiry`** (e os alarmes `*-real-risk-job-stalled-*` no CloudWatch) durante o rollback. A imagem sem a U3 não publica `real_risk_job_runs_total`, e o `absent()` e o `treat_missing_data = breaching` fazem os alertas dispararem.
4. A implantação só termina com o smoke test verde (`team.md`). Até a U4 chamar a barreira, o smoke não a exercita.
5. Ao reimplantar a U3, retire o silêncio só depois de ver `real_risk_job_runs_total` subir para os três trabalhos.

## 7. Limites na configuração

Os limites ficam em `memetrade.real.risk.*` (`application.yml`). Os padrões são `order-max-bps` 1000, `daily-loss-max-bps` 2000, `daily-buys-max` 20, `signal-max-age` 60s e `reservation-ttl` 5m.

Fora dos perfis `test` e `local`, a API e o worker não sobem com um valor mais frouxo que o padrão. A mensagem cita a chave, nunca o valor. Um valor mais rígido é aceito, e a mudança só entra com nova implantação.
