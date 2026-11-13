# Runbooks

Conversa: português (Brasil). Respostas 1A a 5A de `incident-response-questions.md`. Os runbooks RB-01 a RB-12 do intent anterior continuam valendo (`261026-memetrade-saas-mvp/operation/incident-response/runbooks.md`), com as mesmas convenções: **local** com `docker compose` na raiz; **AWS** só desenho; registro do incidente em `docs/incidents/AAAA-MM-DD-<slug>.md` desde o primeiro minuto. Aqui ficam o acréscimo ao RB-09 e os runbooks dos alarmes Altos novos de `operation/observability-setup/alarms.md`, com os painéis de `operation/observability-setup/dashboards.md`. Os comportamentos de recuperação vêm dos `reliability-design.md` e as ameaças dos `security-design.md` (`construction/<unit>/nfr-design/`); a topologia, dos `infrastructure-specification.md` (`construction/<unit>/infrastructure-design/`).

Nenhum runbook edita ou apaga a trilha de auditoria, o ledger, os aceites dos termos ou os pedidos do titular: são tabelas só de inserção.

## RB-09 (acréscimo) Vazamento da chave HMAC da trilha

Quando o segredo suspeito é `MEMETRADE_AUDIT_WALLET_HMAC_KEY`, siga o RB-09 e o procedimento de `docs/privacy/u2-retencao-e-auditoria.md`, seção "Runbook: troca da chave HMAC da auditoria":

1. Registrar no incidente o instante UTC da troca: as buscas por carteira na trilha passam a precisar das duas chaves, separadas por esse instante.
2. Gerar a chave nova (`openssl rand -hex 32`) e gravar no `.env` local ou no gerenciador de segredos; nunca em chat, ticket ou log.
3. Reiniciar `api` e `worker` juntos; conferir `auditRelay` `UP` e `audit_publish_rejected_total` parado.
4. Guardar a chave antiga só pelo tempo que o incidente exigir e depois apagá-la.

Efeito: a correlação por HMAC entre eventos de antes e de depois da troca se perde; os eventos antigos não são reescritos (U2 NFR6.3).

## RB-13 `DATA_REQUEST_OVERDUE`: pedido do titular vencido

1. No app do administrador, abrir "Pedidos do titular" e filtrar os vencidos; a saúde `dataRequests` mostra quantos.
2. Para cada um: verificar a identidade (se ainda faltar) e responder. Acesso e correção pelo próprio fluxo; Exclusão pela anonimização (RB-14 se falhar).
3. Se o pedido não puder ser atendido no prazo por motivo legal, registrar a resposta com o motivo, que fica na trilha.
4. Registrar no incidente quantos dias passou do prazo de 15 dias. Pós-incidente obrigatório: é descumprimento de prazo da LGPD.

## RB-14 `ANONYMIZATION_FAILING_REPEATEDLY`: anonimização falhando

1. Parar novas tentativas de anonimização para o mesmo pedido: a falha é sempre total (a transação volta inteira, U5 NFR5.2), então nada fica pela metade.
2. Ler o motivo em `memetrade_account_anonymization_failures_total{reason}` e no log `ERROR` (consulta `anonymization` de `log-queries.md`):
   - `LOCK_TIMEOUT`: outra operação segurava a conta (login, renovação, ciclo do bot). Tentar de novo fora do horário de uso; se persistir, pausar os bots da conta.
   - `ROLLED_BACK`: erro numa das cinco escritas. Abrir o defeito com o `correlationId` e corrigir por código revisado.
3. Enquanto isso, o pedido de Exclusão conta para o prazo (RB-13 se vencer).
4. Nunca apagar linhas à mão para "completar" uma anonimização.

## RB-15 `AUDIT_FULL_WALLET_REJECTED`: tentativa de gravar carteira completa na trilha

1. O validador da trilha recusou o evento (U2 NFR1.2): nenhuma carteira completa foi gravada. O alarme aponta um defeito num chamador.
2. Achar o chamador pela consulta `audit-full-wallet-rejected` (`correlationId`, componente).
3. Corrigir o chamador por código revisado, com teste de regressão; conferir com o `LogScanIT`.
4. Se o mesmo chamador escreveu a carteira em outro lugar (log, outra tabela), tratar como incidente de dado pessoal (`incident-plan.md`).

## RB-16 Trava de novo aceite barrando quem já aceitou

Sem alarme próprio; sinais: `memetrade_terms_reacceptance_blocked_total` subindo sem versão nova publicada, ou convidados relatando a tela de novo aceite repetida.

1. Conferir a versão vigente na saúde `legalDocuments` e o aceite da pessoa na tela Conta (situação dos termos).
2. Se o aceite existe e a trava continua: rollback da imagem (RB-12; o rollback não desfaz a migração `0013`).
3. Se a trava responde `503`: o banco está indisponível e a trava falha fechada de propósito (U3 NFR2.5); seguir o RB-02.
4. Os bots PAPER ativos continuam rodando durante a trava; não é preciso ligar o kill switch.

## Triagem dos alarmes Médios novos

| Alarme | Primeira ação |
|---|---|
| `LEGAL_NOT_PUBLISHABLE` | preencher os campos de implantação dos documentos legais e publicar numa release (`docs/api/u1-legal-documents.md`) |
| `LEGAL_VERSION_IGNORED` | ler o motivo no log (sem revisão, vigência futura, não publicável) e corrigir o catálogo na release seguinte |
| `PURGE_STALE`, `PURGE_FAILED` | ver o log do expurgo; a execução seguinte recupera o atraso (U2 NFR7.3); se repetir, abrir defeito |
| `TERMS_CHECK_SLOW` | ver `hikaricp_connections_pending` e o p95 da trava no painel; com espera de conexão, seguir o `DB_POOL_PENDING` do intent anterior |
| `DATA_REQUEST_DUE_SOON` | atender o pedido antes do prazo (RB-13) |
| `ANONYMIZATION_FAILED` | uma falha isolada: tentar de novo; se repetir, RB-14 |
