# Runbook — execução REAL: ordem ambígua, pausa UPSTREAM, recusa da política e rollback

Intent `261113-trading-real-bot`, Unit U4 (`u4-real-execution`). Fontes: `functional-design/business-rules.md` (BR1 a BR10), `nfr-design/reliability-design.md`, `infrastructure-design/infrastructure-specification.md` ("Implantação e rollback") e `infrastructure-design/monitoring-design.md` (alertas de `infra/observability/real-execution-alerts.yml`).

Quem executa: o fundador. Nenhum passo deste runbook pede chave, segredo, transação assinada ou endereço de carteira. Os logs e as métricas da execução nunca trazem a transação assinada nem o endereço; a assinatura e o mint aparecem só mascarados (`LogMasking`).

## O que a execução faz

A decisão de um bot REAL aprovada pela barreira (U3) vira um pedido em `real_decision_request`, na transação do ciclo. O worker leva o pedido até o fim:

1. **fila** (`queue`): confere de novo a posse do bot, cria a ordem `PROPOSED` e grava a reserva `RESERVE` (compras);
2. **condutor** (`driver`): pede a cotação à Jupiter, monta a mensagem e grava a mensagem, a saída mínima e a taxa **antes** de pedir qualquer assinatura (`QUOTED`); em Automática total, pede a assinatura ao TxSigner com `signingRequestId = realOrderId` (`SIGNED`); grava `SENT` e a tentativa **antes** de enviar;
3. **confirmação** (`confirmation`): lê o estado da assinatura na rede até `CONFIRMED` ou `FAILED`, ou `AMBIGUOUS` depois de 60 s sem resposta;
4. **ambíguas** (`ambiguous`), **prazo de assinatura** (`signing-deadline`), **saúde dos fornecedores** (`health`) e **liquidação** (`liquidation`).

Regra de ouro: **uma única transação por ordem**. Nenhum passo deste runbook monta, assina ou reenvia uma transação nova à mão.

## 1. Ordem ambígua (alerta `RealExecutionAmbiguousOld`)

**O que significa.** A ordem foi enviada, mas a rede não respondeu se ela entrou. O dinheiro continua reservado (`RESERVE` sem `RESERVE_RELEASE`). A pessoa não consegue subir de modo enquanto houver uma ambígua (`409 ORDER_AMBIGUOUS`).

**O que o sistema já faz.** O trabalho `ambiguous` (a cada 15 s) pergunta pela assinatura gravada até a altura de bloco em que o blockhash da mensagem expira (`last_valid_block_height`):
- se a assinatura aparece finalizada, a ordem vira `CONFIRMED` e o efeito lido da rede vai para o ledger;
- se ela aparece com erro, `FAILED` (`CHAIN_REJECTED` ou `SLIPPAGE_EXCEEDED`), e a reserva é liberada;
- se a altura passou da validade e a assinatura não existe, ela nunca vai entrar: `FAILED` (`BLOCKHASH_EXPIRED`), e a reserva é liberada.

**Passos.**
1. Abra o painel da execução e confira `real_orders_ambiguous_open` e `real_orders_ambiguous_oldest_seconds`.
2. Confira o alerta `RealExecutionJobStalledAmbiguous`. Se ele também disparou, o trabalho parou: veja o worker (`WorkerDown` no local; `metrics-absent-worker` na nuvem) e os logs `REAL order step stopped`.
3. Confira os provedores de RPC (`real_upstream_failures_total{provider="RPC"}`). Com os dois provedores fora, nada se resolve até um voltar; a ordem continua `AMBIGUOUS`, e isso é o correto.
4. Para uma ordem específica, leia no banco (somente leitura): `select state, failure_code, sent_at, last_valid_block_height from real_order where real_order_id = '<id>'` e as tentativas em `real_order_attempt`. Procure a assinatura num explorador público pela assinatura mascarada do log só se o RPC estiver fora por muito tempo.
5. **Nunca** reenvie a transação nem crie uma ordem nova para "compensar". Se a ambígua passar de 1 hora com os provedores saudáveis, abra incidente: é um defeito do trabalho, não da rede.

## 2. Pausa UPSTREAM (alerta `RealExecutionUpstreamPause`)

**O que significa.** Um fornecedor (Jupiter, RPC ou TxSigner) falhou 3 vezes seguidas para a mesma pessoa. A carteira dela foi pausada com o motivo `UPSTREAM` (evento `REAL_PAUSED`). As compras e as vendas do bot param; **o saque continua disponível**.

**O que o sistema já faz.** O trabalho `health` (a cada 30 s) testa o fornecedor (para a Jupiter, uma cotação de SOL para USDC; para o TxSigner, a rota de leitura interna). Depois de 5 minutos seguidos saudável, a pausa `UPSTREAM` é retirada sozinha (`REAL_RESUMED`).

**Passos.**
1. Veja qual fornecedor: `real_upstream_failures_total{provider}`.
2. **Jupiter:** confira o status público da Jupiter e o `MEMETRADE_JUPITER_BASE_URL` (só `lite-api.jup.ag` ou `api.jup.ag` fora dos perfis de teste). Só 5xx, 429 e tempo esgotado contam como indisponível; um outro 4xx falha só aquela ordem com `NO_ROUTE` (a Jupiter recusou a rota) e nunca conta para a pausa.
3. **RPC:** confira os dois provedores configurados (`MEMETRADE_RPC_PRIMARY_URL` e `MEMETRADE_RPC_SECONDARY_URL`).
4. **TxSigner:** siga `docs/runbooks/txsigner-recovery.md`. Com o TxSigner fora, as ordens em Automática total falham `UPSTREAM_UNAVAILABLE`; as do modo Assinada ficam `QUOTED` com a sessão aberta, e o trabalho de prazo repete o mesmo pedido.
5. Não há rota para retirar a pausa `UPSTREAM` antes da hora; a área do administrador da U5 também não criou uma. Ela sai sozinha depois de 5 minutos com o fornecedor saudável. Se for preciso parar tudo antes disso, o fundador liga o kill switch REAL.

## 3. Recusa da política do TxSigner (alerta `RealExecutionPolicyRefused`, crítico)

**O que significa.** O TxSigner recusou assinar um swap porque a transação saiu da política de conteúdo (ADR-004): programa fora da lista, assinante ou pagador diferente da carteira do bot, destino fora das carteiras da pessoa, saída mínima abaixo da cotação menos a derrapagem, troca de autoridade, fechamento de conta para terceiros, taxa de prioridade acima do teto ou valor acima do teto autorizado. A ordem fica `FAILED` (`SIGNING_REFUSED`) e a reserva é liberada. **Nada foi assinado.**

**Isto pode ser um ataque ou um erro de montagem.** Trate como incidente.

**Passos.**
1. Veja o motivo no rótulo `reason` de `txsigner_swap_decisions_total{outcome="REFUSED"}` e no log de auditoria do TxSigner (`txsigner event` com `eventType=SIGNING_REFUSED`, `operation=SWAP` e `reason`).
2. Se várias pessoas recebem a mesma recusa ao mesmo tempo: pode ser mudança da Jupiter (programa novo, conta nova na rota). Acione o kill switch REAL (`memetrade.real.enabled=false` ou o kill switch do administrador) até entender. Não amplie a lista de programas sem revisão do ADR-004.
3. Se só uma pessoa recebe: confira se a cotação e a mensagem gravadas na ordem (`message_digest`, `expected_out`, `min_out`) batem com o que o TxSigner leu. Uma diferença é defeito da montagem na API (`MessageBuilder`), não da pessoa.
4. Nunca desligue a política, nunca aumente o teto de taxa nem a derrapagem máxima para "fazer passar". O teto da taxa de prioridade é o mesmo nos dois lados (`memetrade.real.execution.max-priority-fee-lamports` e `txsigner.max-swap-priority-fee-lamports`, no máximo 1.000.000 lamports).

## 4. Rollback (com espera de 15 minutos)

A ordem de implantação é: migração 0019, TxSigner, worker, API. O rollback desfaz no sentido inverso **sem nunca deixar uma ordem enviada sem acompanhamento**.

1. **Desligue o REAL primeiro:** `memetrade.real.enabled=false` na API e no worker e `txsigner.real.enabled=false` no TxSigner, ou acione o kill switch REAL. A partir daí:
   - a fila descarta as decisões novas (`REAL_DISABLED`);
   - as ordens ainda não enviadas (`PROPOSED`, `QUOTED`) são canceladas;
   - nenhuma assinatura nova é pedida;
   - **confirmação, ambíguas e liquidação continuam** terminando o que já foi enviado;
   - **o saque continua disponível** o tempo todo.
2. **Espere 15 minutos** com o worker no ar. Nesse tempo toda ordem `SENT` chega a `CONFIRMED`, `FAILED` ou `AMBIGUOUS`, e toda `AMBIGUOUS` passa da validade do blockhash (cerca de 60 a 90 s) e é resolvida pelo trabalho `ambiguous`.
3. Confira antes de seguir: `select state, count(*) from real_order where state in ('SIGNED','SENT','AMBIGUOUS') group by state` deve voltar vazio, e `real_orders_ambiguous_open` deve estar em zero. Se não estiver, espere mais; não siga com ordem em voo.
4. Só então volte o worker e a API para a versão anterior (artefatos marcados pelo SHA do commit).
5. A migração 0019 segue o padrão expand-contract: ela só acrescenta tabelas e a coluna `bot.market`. **Não desfaça a migração** no rollback da aplicação; as tabelas ficam, sem uso, até a próxima implantação. O `rollback` do Liquibase existe para o ambiente local e de teste.
6. Conclua com o smoke da versão anterior (`bash scripts/verify.sh` no ambiente local) e registre o rollback.

## 5. Trabalho parado (alertas `RealExecutionJobStalled*`)

Cada trabalho conta `real_execution_job_runs_total{job}` quando uma rodada termina sem falha. Fila, condutor e confirmação têm janela de 1 minuto; ambíguas, prazo, saúde e liquidação, de 5 minutos. Essas são as janelas do Prometheus local; na nuvem, com o agente lendo a cada 120 s, os alarmes `real-execution-job-stalled-*` usam 4 e 8 minutos (`operation/observability-setup/alarms.md`, seção 1). Um alerta indica worker fora, pool de agendamento cheio (o mínimo é conferido na subida pelo `RuntimeSettingsGuard`; desde a U7, 28 threads e 36 conexões no worker) ou falha repetida na rodada (log `REAL order step stopped` com a classe do erro). O expurgo diário da API (`purge`, 04:30 UTC) não tem alerta; o registro de cada rodada fica em `real_execution_purge_run`.
