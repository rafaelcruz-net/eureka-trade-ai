# Runbooks

Conversa: português (Brasil). Respostas 1A a 5A de `incident-response-questions.md`. Um runbook por alarme Alto de `operation/observability-setup/alarms.md` e pelos cenários sem alarme próprio. Os painéis citados estão em `operation/observability-setup/dashboards.md`. Os comportamentos de recuperação vêm dos `reliability-design.md` e as ameaças dos `security-design.md` (`construction/<unit>/nfr-design/`). A topologia vem dos `infrastructure-specification.md` (`construction/<unit>/infrastructure-design/`).

## Convenções

- **Local** usa `docker compose` na raiz do repositório. **AWS** é desenho: os documentos SSM Automation (`MemeTrade-RestartService`, `MemeTrade-RollbackImage`, `MemeTrade-KillSwitchOn`) só rodam com a aprovação do responsável do projeto.
- **Contenção padrão para qualquer dúvida sobre os bots PAPER:** ligar o kill switch na tela do administrador (app `admin`, entrada de administrador → "Kill switch" → Ligar, com motivo). A chamada é `PUT /api/v1/admin/kill-switch` com `Idempotency-Key`, e repetir é seguro. Conferir em `/actuator/health` → `adminKillSwitch` e em `admin_killswitch_pending_bots` = 0.
- Nada nestes runbooks edita ou apaga lançamento do ledger. Toda correção do ledger é feita com lançamento compensatório, por mudança de código revisada.
- Registre o incidente em `docs/incidents/AAAA-MM-DD-<slug>.md` desde o primeiro minuto (`incident-plan.md`).

## RB-01 `API_DOWN` / `MIGRATIONS_DOWN`: API fora do ar ou migração falhou

1. Confirmar: `docker compose ps` (api `unhealthy`?) e `curl -s http://127.0.0.1:8081/actuator/health`.
2. Ver o motivo: `docker compose logs --tail 200 db-migrate api`. Saúde `migrations` `DOWN` significa migração não aplicada; `db` `DOWN` leva ao RB-02.
3. Migração falhou depois de uma release: rollback da imagem (RB-12). As migrações são só expand, e a versão anterior funciona sobre o banco.
4. Processo travado sem causa no log: `docker compose restart api` (na AWS, `MemeTrade-RestartService`).
5. Encerrar quando a saúde estiver `UP` e o smoke (`verify.sh` local, ou smoke de ambiente) passar.

## RB-02 Banco indisponível ou disco cheio

1. `docker compose ps postgres`; `docker compose logs --tail 100 postgres`; `docker system df` para espaço.
2. Disco cheio: liberar imagens e cache antigos (`docker image prune`, `docker builder prune`). **Nunca** apagar o volume `pgdata` nem rodar `down -v` no ambiente com dados.
3. Banco reiniciou: a API volta sozinha (o pool reconecta); as tarefas atrasadas rodam ao subir (NFR4.5).
4. Corrupção ou perda do volume: recuperação conforme `incident-plan.md` → "Recuperação de desastre". Localmente não há backup, então o ambiente é recriado e os dados locais se perdem. Na AWS, restauração a um ponto no tempo.

## RB-03 `AUDIT_OUTBOX_STALE`: auditoria atrasada

1. Painel Domínio: `audit_outbox_pending` e `audit_outbox_oldest_age_seconds`; saúde `auditRelay`.
2. Logs: `docker compose logs api worker | grep -i audit | grep ERROR`.
3. Os eventos confirmados ficam no outbox e não se perdem (NFR4.1). Corrigir a causa (banco, permissão do papel de retenção, erro do relay) e deixar o relay drenar.
4. Relay travado: reiniciar o processo dono do relay. Encerrar quando a idade estiver < 60 s.

## RB-04 `MARKET_SOURCE_STALE`: fonte de mercado parada

1. `market_source_status` e `market_source_last_collection_age_seconds`; saúde `marketSources`.
2. A interface já mostra `unknown` e a idade dos dados. Nunca preencher com zero nem com valor antigo como se fosse novo.
3. Fonte simulada local parada: ver os logs da coleta e reiniciar o worker. Fornecedor real (futuro): ver o status do fornecedor e esperar o retry.
4. Mais de 10 minutos parada com bots ativos: ligar o kill switch e avisar os convidados (`incident-plan.md`).

## RB-05 `RISK_EVALUATION_ERRORS`: erro na avaliação de risco

1. **Conter primeiro:** ligar o kill switch. Nenhuma ordem PAPER pode passar sem checagem de risco.
2. `riskcontrol_evaluation_errors_total`, saúde `riskcontrol`, logs `ERROR` do `riskcontrol`.
3. Causa em dado (limite inválido, autorização revogada): corrigir o dado pela tela da conta. Causa em código: rollback (RB-12) ou correção com teste de regressão.
4. Desligar o kill switch só com as avaliações voltando sem erro.

## RB-06 `LEDGER_DIVERGENCE`: divergência na reconciliação do ledger

1. **Conter:** ligar o kill switch.
2. Saúde `ledgerJobs` e log `WARN` da rodada (só contagem e identificadores).
3. Identificar as contas afetadas pelos identificadores do log. **Não** editar nem apagar lançamentos (o banco recusa UPDATE e DELETE).
4. Correção: lançamento compensatório por mudança de código revisada, com teste de regressão que reproduz a divergência.
5. Encerrar quando a reconciliação seguinte mostrar 0 divergências. Pós-incidente obrigatório (P1).

## RB-07 `KILL_SWITCH_RECONCILE_STALE`, `KILL_SWITCH_PENDING_STALE` e `KILL_SWITCH_DIVERGENCE`

1. Saúde `adminKillSwitch`; `admin_killswitch_pending_bots`, `admin_killswitch_reconcile_needed`; logs com `KILL_SWITCH_`.
2. Repetir o `PUT ON` pela tela (idempotente). A reconciliação aplica a pausa aos bots pendentes.
3. Bots continuam pendentes: ver o worker (saúde `botCycles`, `bots_cycle_errors_total`) e reiniciá-lo. A fatia em curso termina antes de parar.
4. Divergência persistente: pausar os bots afetados um a um pela U5 e abrir P1.

## RB-08 `REFRESH_TOKEN_REUSE`: possível roubo de sessão

1. O reuso já revoga a família de tokens (U3). Confirmar a revogação na trilha de auditoria pela pessoa e pelo horário, sem copiar token.
2. Ver na trilha se houve ações depois do reuso. Se houver ação suspeita em bot, pausar o bot.
3. Avisar a pessoa afetada pelo canal do beta para entrar de novo. Se houver suspeita de dado pessoal exposto, seguir o fluxo de LGPD (`incident-plan.md`).

## RB-09 Suspeita de vazamento de segredo

1. Revogar e trocar o segredo **antes** de qualquer limpeza: senhas do banco, `ACCESS_TOKEN_SIGNING_KEY` (trocar invalida as sessões) e credenciais de nuvem.
2. Subir os serviços com os novos valores (`.env` local; Secrets Manager na AWS).
3. Só então limpar o histórico do git, se o segredo entrou num commit, e rodar o gitleaks no repositório inteiro.
4. Ver nos logs e na trilha se o segredo foi usado. Pós-incidente obrigatório.

## RB-10 Acesso entre tenants

1. **Conter:** ligar o kill switch e, se for preciso, tirar a rota afetada do ar com rollback (RB-12).
2. Pela trilha de auditoria, identificar quem acessou o quê. Não copiar dados pessoais para o registro do incidente.
3. Correção com o teste negativo de tenant que reproduz o caso (403 ou 404, nunca o dado).
4. Avaliação de LGPD antes de qualquer comunicação externa. P1.

## RB-11 `HTTP_5XX_RATE` e `SLO_BURN_FAST`

1. Painel Visão geral: quais rotas, desde quando, se coincide com uma release.
2. Coincide com release: rollback (RB-12). Coincide com saturação (pool, heap, memória): reiniciar o serviço e abrir a investigação de capacidade.
3. Achar requisições de exemplo pelo `correlationId` (`log-queries.md`).

## RB-12 Release ruim: rollback

Segue `operation/deployment-pipeline/rollback-runbook.md`, já ensaiado em `v2026.11.08-1`:
- Local: `git checkout <tag anterior>` e `IMAGE_TAG=<sha anterior> docker compose up -d --build --remove-orphans`, depois `verify.sh`.
- AWS: `MemeTrade-RollbackImage`, ou o rollback automático do blue/green quando o smoke falha.
- Nunca reverter uma migração com DROP; o contract só entra numa release seguinte.

## Triagem dos alarmes Médios

| Alarme | Primeira ação |
|---|---|
| `SLO_BURN_SLOW` | Ver a tendência no painel Visão geral; abrir tarefa se não houver causa óbvia |
| `LATENCY_P95` | Comparar com a meta da Unit; ver o pool e o heap; rodar o cenário de carga da Unit no local |
| `DB_POOL_PENDING` | Ver as consultas lentas e o tamanho do pool; reiniciar só se travar |
| `JVM_HEAP_HIGH` / `CONTAINER_MEMORY_HIGH` | Ver se há vazamento (heap crescendo depois do GC); reiniciar e abrir investigação |
| `AUDIT_JOB_STALE` | Ver a saúde `auditOutboxCleanup` e os logs da tarefa; a próxima rodada recupera |
| `RISK_RECALC_STALE` | Saúde `riskRecalculation`; ver a fonte (RB-04) |
| `BOT_CYCLE_ERRORS` | Logs do worker; pausar o bot afetado se o erro se repetir |
| `RISK_PURGE_FAILED` | Logs do `RiskEvaluationPurgeJob`; a próxima rodada diária tenta de novo |
| `FEEDBACK_PURGE_STALE` | Saúde `feedbackPurge`; rodar de novo depois de corrigir |
| `LOGIN_REJECTED_SPIKE` / `ADMIN_LOGIN_RATE_LIMITED` | Ver origem e padrão na trilha; o limite de tentativas já age; RB-08 se houver reuso |
| `MARKET_SOURCE_STALE` (até 10 min) | RB-04 |
