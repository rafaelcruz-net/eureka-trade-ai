# Componentes lógicos — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes:
- `construction/u5-real-operations/functional-design/` (BR1.1 a BR7.2, F1 a F10);
- `construction/u5-real-operations/nfr-requirements/`;
- `nfr-design-questions.md` (Q1 a Q6);
- o código atual, conferido na revisão da iteração 1:
  - `RealExecutionWalletService.recheckBeforeSend`, `RealRiskPolicy.SEVERITY` e `RealRejectionDetail`;
  - `RealReleases.isEffective`, `ChainPort`, `DepositScanner` e `RpcBudget`;
  - a trilha: o gatilho `audit_event_reject_change` da 0005 e `AuditOutboxRelay`;
  - os Dockerfiles e o `ci.yml`.

## Ajustes da revisão (iteração 1)

1. **As pausas globais exigem mudanças explícitas na U1, na U3 e na U4.** A frase "sem mudança na U3 e na U4" da Q2 estava errada. As mudanças estão na tabela "Mudanças em outras Units".
2. **O kill switch REAL fica em `botwallet`.** `botwallet` é dona das pausas REAL. A M7 passa a viver em `botwallet.api`, e `administration` só chama a M1 e a M7. Assim não há ciclo entre módulos. Não existe pacote `administration.application`; a lógica de serviço fica em `botwallet.application`.
3. **A redação da trilha muda o gatilho da 0005 de forma controlada** (ver `security-design.md`). O endereço fica em `payload.real.botWalletAddress`, não em `details`.

## Componentes

| Componente | Onde | Processo | Papel |
|---|---|---|---|
| `ReleaseService` | `botwallet.application` | API | liberação e revogação (BR1.2, BR1.3, BR1.6); trava da mainnet (Q6); a trava da pessoa na revogação é pega pelo `RealAdminController` (ver ajustes da iteração 2) |
| `RealReleases.isEffective` (U1, mudado) | `botwallet.domain` | os dois | efetiva só com estado `ACTIVE`; `QUARANTINE`, `BLOCKED` e `REVOKED` nunca são efetivas |
| `GateEvaluator` | `botwallet.domain` | worker | portões puros (BR1.4) |
| `GatesJob` | `botwallet.infra` | worker | a cada 5 min; uma leitura da CI por SHA |
| `CiStatus` (porta) com `GitHubCiStatus` e `ConfiguredCiStatus` | `botwallet.domain` e `botwallet.infra.github` | worker | Q5 e `security-design.md` |
| `ReconciliationPolicy` | `botwallet.domain` | worker | regra pura (Q1) |
| `ReconciliationReader` e `ReconciliationJob` | `botwallet.infra` | worker | leitura pela `ChainPort` ampliada; executor de até 4 |
| `DivergenceService` | `botwallet.application` | API e worker | BR2.2, BR2.3 |
| `WithdrawalLimit` | `botwallet.domain` | API | BR2.4, chamado pelo saque |
| `RestoreDetector` | `botwallet.infra` | worker | Q3 e `reliability-design.md` |
| `GlobalPauses` e M7 `RealKillSwitchState` | `botwallet.infra` e `botwallet.api` | os dois | leitura ao vivo de `real_kill_switch` e do `RestoreMarker` (tabelas de `botwallet`) |
| `RealKillSwitchService` | `botwallet.application` | API | ligar e desligar (BR3.1, BR3.2) |
| `KillSwitchCancelJob`, `TxSignerCommandJob`, `RealOpsGaugesJob` | `botwallet.infra` | worker | 1 min, 1 min, 30 s |
| `RealOpsPurgeJob` | `botwallet.infra` | API | expurgo de retenção |
| `RealAdminController` | `administration.web` | API | rotas R4; chama a M1 e a M7 |
| Migração 0021 | Liquibase | — | ver `scalability-design.md` e `security-design.md` |
| Anonimização | `privacy`, chamando a M1 `closeForAnonymization` | API | BR6.1, BR6.2 |

## Mudanças em outras Units

| Unit e código | Mudança |
|---|---|
| U1 `botwallet.api` M1 `readRealState` | junta as pausas globais (`KILL_SWITCH`, `RESTORE`, `RESTORE_CHECK_PENDING`) às do usuário |
| U1 `RealReleases.isEffective` | exige o estado `ACTIVE` |
| U1 `ChainPort` e `RpcChainWatcher` | ganham as leituras de `performance-design.md` |
| U1 `RpcBudget` | ganha as finalidades `RECONCILIATION` e `RESTORE_CHECK`, com cota própria |
| U1 `DepositScanner` | não roda enquanto o marcador `RESTORE_CHECK_PENDING` estiver aberto |
| U1 saque | consulta o `WithdrawalLimit` |
| U3 `RealRiskPolicy.SEVERITY` e `RealRejectionDetail` | ganham `RESTORE` e `RESTORE_CHECK_PENDING` (detalhes `PAUSED_RESTORE` e `PAUSED_RESTORE_CHECK_PENDING`), que recusam como `REAL_PAUSED`; os CHECKs das tabelas de 0018 e 0019 são ampliados na 0021; teste da barreira com cada motivo novo |
| U4 `RealExecutionWalletService.recheckBeforeSend` | passa a ler o estado pela M1 `readRealState` (com as pausas globais), em vez de `repository.preferences` direto; teste antes de assinar e antes de enviar com o kill switch e com a restauração |
| U4 ciclo REAL | grava `decision.mode = REAL` (BR7.1) |
| `RuntimeSettingsGuard` | worker com scheduler de 25 a 32 e conexões de 33 a 48; API com mínimo de 22 |
| Dockerfiles da API e do TxSigner, `ci.yml` | `ARG BUILD_SHA` e a variável `MEMETRADE_BUILD_SHA` na imagem; a CI passa `--build-arg BUILD_SHA=${{ github.sha }}` (texto para o responsável) |

## Ajustes da revisão (iteração 2)

- **Nomes dos detalhes.** Os detalhes novos da barreira são `PAUSED_RESTORE` e `PAUSED_RESTORE_CHECK_PENDING`, porque o código monta o detalhe com `"PAUSED_" + motivo`.
- **Liquidação.** A liquidação pedida pelo usuário é recusada com `RESTORE` e `RESTORE_CHECK_PENDING`, como já é com `KILL_SWITCH` e `DIVERGENCE`. Nas outras pausas ela continua passando.
- **`pausedSince` das pausas globais.** Vem de `real_kill_switch.activated_at` no kill switch e de `RestoreMarker.detectedAt` na restauração.
- **Liberações que já existem.** A 0021 preenche o estado delas:
  - com `revoked_at`, `REVOKED`;
  - com `effective_at` já passado, `ACTIVE`;
  - nas demais, `QUARANTINE`.

  O `RealTestSeeder` grava `ACTIVE` direto, só no perfil de teste. Depois das 24 horas, a passagem a `ACTIVE` pode levar até 5 minutos, pela cadência do `GatesJob`; esse atraso é aceito.
- **Porta pública da administração.** As ações de administração entram em `botwallet.api`: a M1 já tem `adminRelease`, `adminRevoke`, `adminLowerCap`, `listDivergences` e `compensate`, e a M7 ganha `activate` e `deactivate`. O `RealAdminController` só usa essas interfaces.
- **Sem ciclo entre `botwallet` e `riskcontrol`.** `riskcontrol` já depende de `botwallet.api` (a U3 lê a M1). Por isso a trava da pessoa na revogação é pega por quem chama: o `RealAdminController` chama a M5 `withPersonLock` e, dentro dela, a M1 `adminRevoke`, que faz o `cancelUnsent`. `botwallet` não depende de `riskcontrol`.

## Falhas e raio de impacto

| Falha | Impacto | Contenção |
|---|---|---|
| GitHub fora | liberações `BLOCKED CI_UNREACHABLE` | reconferência a cada 5 min; alerta |
| RPC fora | reconciliação `UNAVAILABLE`; restauração pendente | ordens novas recusadas só com a restauração pendente; saque continua |
| TxSigner fora | comando pendente | reenvio; alerta em 15 min |
| Worker parado | trabalhos atrasam | alertas; o kill switch vale pela leitura ao vivo |
