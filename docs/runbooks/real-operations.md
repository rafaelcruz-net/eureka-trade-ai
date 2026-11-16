# Runbook — operações do REAL: liberação, divergência, kill switch, restauração e rollback

Intent `261113-trading-real-bot`, Unit U5 (`u5-real-operations`). Fontes: `functional-design/business-rules.md` (BR1 a BR7), `nfr-design/reliability-design.md`, `nfr-design/security-design.md`, `infrastructure-design/infrastructure-specification.md` e `infrastructure-design/monitoring-design.md` (alertas de `infra/observability/real-ops-alerts.yml`).

Quem executa: o fundador, com o papel ADMIN, pela entrada administrativa. Nenhum passo deste runbook pede chave, segredo, frase de recuperação ou transação assinada. As rotas abaixo ficam sob `/api/v1/admin/real` e toda mudança exige o cabeçalho `Idempotency-Key` (um UUID novo por ação; repetir a mesma chave com o mesmo corpo devolve a mesma resposta) e um motivo de 1 a 500 caracteres, que vai para a trilha de auditoria.

Regra de ouro: **o saque para a carteira principal registrada nunca é bloqueado** — nem pelo kill switch, nem por divergência (só limitado ao que a rede confirma), nem pela restauração, nem sem liberação. Nenhum passo deste runbook desliga o saque.

## 1. Liberação e portões

**Como funciona.** `POST /users/{userId}/release` com `{ "network": "<LOCALNET|DEVNET|MAINNET>", "reason": "..." }` registra a liberação em `QUARANTINE`. Ela só vale depois de 24 horas e com todos os portões verdes. O trabalho `gates` do worker (a cada 5 min) confere os portões das liberações que passaram da quarentena e das que estão `BLOCKED`:

| Motivo de bloqueio | O que significa | O que fazer |
|---|---|---|
| `CI_RED` | o workflow `ci.yml` do commit da imagem (`BUILD_SHA`) no `main` não terminou verde | corrigir a CI; o portão volta sozinho na próxima rodada |
| `CI_UNREACHABLE` | a leitura do GitHub falhou (rede, token vencido, 5 s de prazo) | ver a seção 5 (token); conferir `MEMETRADE_GITHUB_REPOSITORY` |
| `DIVERGENCE_OPEN` | existe divergência aberta | resolver a divergência (seção 2) |
| `KILL_SWITCH_ON` | o kill switch REAL está ligado | desligar quando o incidente acabar (seção 3) |
| `NETWORK_MISMATCH` | a liberação é de outra rede que não a do ambiente | revogar e registrar de novo com a rede certa |

**Regras de entrada (recusas com código estável):** `NO_BOT_WALLET` (a pessoa não tem carteira do bot ativa), `TERMS_V3_MISSING` (falta o aceite dos termos vigentes), `RELEASE_EXISTS` (já existe liberação não revogada nessa rede), `FOUNDER_FIRST` (na MAINNET, a carteira do fundador — `MEMETRADE_REAL_FOUNDER_USER_ID` — é liberada antes de qualquer convidado), `PILOT_FULL` (o piloto da MAINNET já tem 20 convidados).

**Revogar.** `DELETE /users/{userId}/release` com `{ "reason": "..." }`. Vale na hora, sob o lock da pessoa: as ordens não enviadas dela são canceladas (`REAL_DISABLED`); as enviadas seguem até o fim; o saque continua.

**Alertas.** `RealOpsReleaseBlocked` (liberação `BLOCKED` há mais de 1 h) e `RealOpsJobStalledGates`.

## 2. Divergência e compensação

**O que significa.** A reconciliação (trabalho `reconciliation`, a cada 15 min por carteira, e depois de cada ordem confirmada) compara, no slot finalizado, o saldo de SOL e de cada token da carteira do bot na rede com o ledger REAL. Se não bate, abre uma divergência, pausa a carteira com `DIVERGENCE` e limita o saque ao que a rede confirma. Ela fica `DEFERRED` enquanto houver ordem `SENT` ou `AMBIGUOUS` ou depósito ainda não lido, e `UNAVAILABLE` quando o RPC não responde.

**Passos.**
1. `GET /divergences` mostra a divergência com a diferença medida (rede menos ledger) em lamports e em unidades base de cada mint. `GET /users/{userId}` mostra a pessoa, o saldo e as 20 ordens mais recentes.
2. Descubra a causa antes de compensar: depósito de token sem ordem (airdrop, spam), taxa não lançada, defeito. Consulte a carteira do bot (endereço público, mostrado por extenso na visão) num explorador.
3. Se a diferença é legítima, compense: `POST /divergences/{divergenceId}/compensation` com `{ "items": [{ "asset": "SOL" ou "<mint>", "amount": "<diferença com sinal>" }], "reason": "..." }`. Cada item tem de ser **exatamente** a diferença medida do ativo; outro valor é `422 COMPENSATION_MISMATCH`, e uma divergência que já não está aberta é `409 DIVERGENCE_NOT_OPEN`. A compensação é um lançamento novo no ledger (o ledger é só de inserção) e deixa a divergência `COMPENSATED`.
4. O worker roda a reconciliação `AFTER_COMPENSATION`. Se vier `CLEAN`, a divergência fica `RESOLVED` e a pausa `DIVERGENCE` sai. Se continuar diferente, a divergência volta a valer com a nova medida: repita a análise.
5. Se a diferença **não** é legítima (indício de ataque ou defeito), não compense: ligue o kill switch (seção 3) e abra incidente.

**Alertas.** `RealOpsDivergenceOpen` (crítico), `RealOpsReconciliationDeferred` (carteira sem medida há mais de 2 h), `RealOpsJobStalledReconciliation`.

## 3. Kill switch REAL

**Ligar.** `PUT /kill-switch` com `{ "reason": "..." }`. Na hora: nenhuma ordem nova REAL de ninguém é aceita, as ordens não enviadas de todos são canceladas (`KILL_SWITCH`) logo depois do registro e a cada minuto enquanto ficar ligado; as enviadas seguem; posições ficam como estão; o saque continua. A visão de cada pessoa mostra a pausa `KILL_SWITCH`.

**Desligar.** `DELETE /kill-switch` com `{ "reason": "..." }`. Nenhum bot é religado; as outras pausas continuam. Ligar quando já está ligado (ou desligar quando já está desligado) não muda nada (`changed: false`).

**Alertas.** `RealOpsKillSwitchOn` (ligado há 30 min, para não esquecer) e `RealOpsJobStalledKillSwitchCancel`.

## 4. Restauração do banco

**O que significa.** Depois de restaurar um backup, o banco pode não conhecer transações que já estão na rede. Ao subir, o worker abre a pausa global `RESTORE_CHECK_PENDING` e confere, para cada carteira, as assinaturas da rede mais novas que o cursor de depósitos. Se todas são conhecidas, a pausa sai sozinha. Se alguma é desconhecida, a pausa vira `RESTORE` (todos os bots REAL parados; o saque continua) e os depósitos voltam a ser lidos.

**Passos.**
1. Acompanhe `real_restore_pause_active{phase}` e o alerta `RealOpsRestorePause`.
2. Com `RESTORE`, espere a reconciliação `AFTER_RESTORE` de todas as carteiras. Divergências abertas seguem a seção 2.
3. Quando todas as carteiras vierem `CLEAN` depois da restauração, confirme: `POST /restore/confirmation` com `{ "reason": "..." }`. Se alguma ainda não estiver limpa, a resposta é `409 RESTORE_NOT_CLEAN`; sem restauração aberta, `404`.

**Alertas.** `RealOpsRestorePause` e `RealOpsJobStalledRestoreCheck` (só enquanto a conferência está pendente).

## 5. Troca do token do GitHub

O portão de CI lê o workflow `ci.yml` do commit da imagem no `main` com um token fine-grained **só de leitura de Actions** deste repositório, guardado no segredo `github-actions-read-token` (Secrets Manager) e entregue só ao worker como `MEMETRADE_GITHUB_ACTIONS_TOKEN`. Troca a cada 90 dias, ou na hora em caso de vazamento:

1. No GitHub, crie um token novo com "Actions: read" só neste repositório e validade de 90 dias.
2. Grave o valor novo no segredo `github-actions-read-token` (à mão; o Terraform nunca escreve o valor).
3. Force uma nova implantação do worker e confira, no log, que o portão leu a CI (nenhum `CI_UNREACHABLE` novo).
4. Revogue o token antigo no GitHub.

Em vazamento: revogue primeiro, depois troque. O token nunca aparece em log (o `toString` do adaptador não o mostra).

## 6. Duplicatas na migração 0021

A migração 0021 começa por uma pré-condição: no máximo uma liberação não revogada por pessoa e rede. Se houver duplicata, a migração inteira para **antes de qualquer mudança** (`SQL Precondition failed`) e a API não sobe com a imagem nova.

1. Com a imagem anterior ainda no ar, liste as duplicatas: `select user_id, network, count(*) from real_release where revoked_at is null group by user_id, network having count(*) > 1`.
2. Revogue a mais antiga de cada par pela rota do administrador da versão anterior (ou, na falta dela, registrando `revoked_at = now()` e o motivo no incidente).
3. Implante de novo; a pré-condição passa.

## 7. Rollback

A 0021 é só de expansão: as imagens da U4 funcionam sobre o esquema novo. Por isso o rollback normal é **das imagens**, não do banco.

1. Primeiro desligue o REAL: `memetrade.real.enabled=false` e `txsigner.real.enabled=false`, ou ligue o kill switch REAL. O saque continua disponível durante todo o rollback.
2. Volte as imagens da API, do worker e do TxSigner para a tag anterior (marcadas pelo SHA do commit, `BUILD_SHA`).
3. Só se for indispensável voltar o banco, rode o rollback dos changesets da 0021 na ordem inversa. **O CHECK do saque é a exceção:** se algum saque já foi redigido por uma anonimização (`withdrawal.redacted_at` preenchido), o rollback de `0021-withdrawal-redaction` para com a mensagem `withdrawal rows were redacted` — mantenha o CHECK da 0021 (as imagens anteriores funcionam com ele) e volte só as imagens.
4. Confira o smoke e, com o REAL de volta, a reconciliação `CLEAN` das carteiras.
