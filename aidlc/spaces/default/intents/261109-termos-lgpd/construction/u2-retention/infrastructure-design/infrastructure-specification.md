# Especificação de infraestrutura — U2 `u2-retention`

Conversa: português (Brasil). Implementa o desenho de `construction/u2-retention/nfr-design/` (segurança, desempenho, escala, confiabilidade, observabilidade e componentes lógicos) no ambiente local existente, com o desenho para a nuvem. Todas as escolhas seguem as recomendações (A), pelo pedido do responsável do projeto.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Compute | Contêineres existentes (`api` e `worker`) no Docker Compose; nenhum serviço novo. Os expurgos rodam no processo `api` | a U2 é código no monólito (NFR7.1) |
| Rede | Sem mudança | os expurgos e o HMAC não abrem porta nem chamam fora |
| Armazenamento | PostgreSQL existente. A migração `0012-retention.sql` só acrescenta: a coluna `audit_event.wallet_hmac` com índice parcial, a tabela `identity_job_run` e o índice de expressão `user_session_end_idx` | expand-contract (NFR5.1) |
| Ambientes | Local (Compose e overlay de teste); nuvem só desenhada | sem conta de nuvem |
| IaC | Compose versionado; a IaC da nuvem continua pendente no produto | igual ao restante |
| Tamanho | Sem mudança de memória. O pool do agendador da API passa de 16 para 18 threads | dois `@Scheduled` novos |

## Configuração nova

| Variável | Serviços | Local | Nuvem (desenho) |
|---|---|---|---|
| `MEMETRADE_AUDIT_WALLET_HMAC_KEY` (segredo) | `api` e `worker` | `.env`, sem valor no `.env.example`; o Compose repassa como `${MEMETRADE_AUDIT_WALLET_HMAC_KEY:?}` (obrigatória) | gerenciador de segredos (Secrets Manager), lido na subida |

Os horários (`memetrade.identity.purge.challenges-cron` e `sessions-cron`) usam o padrão do `application.yml` e não viram variável de ambiente.

**Onde a chave precisa existir.** A variável é obrigatória no `docker-compose.yml` inteiro, inclusive no fluxo de desenvolvimento (`up postgres db-bootstrap db-migrate`). Por isso todo script que sobe o Compose precisa exportá-la:

| Arquivo | Quem edita | Mudança | Quando |
|---|---|---|---|
| `scripts/verify.sh` | responsável do projeto | gerar a chave efêmera **fora** do bloco `if [ ! -f .env ]`, com `export MEMETRADE_AUDIT_WALLET_HMAC_KEY="${MEMETRADE_AUDIT_WALLET_HMAC_KEY:-$(openssl rand -hex 32)}"`, para valer também quando `.env` existe sem a chave | antes do ponto de controle da U2 |
| `.github/workflows/ci.yml` | responsável do projeto | `MEMETRADE_AUDIT_WALLET_HMAC_KEY: 0000000000000000000000000000000000000000000000000000000000000000` no `env` do passo `Compose config` | antes do primeiro commit com a U2 |
| `scripts/load-test.sh` e `scripts/perf-audit.sh` | geração de código (não são exclusivos do responsável) | a mesma linha de geração efêmera, junto dos outros segredos efêmeros | na geração de código da U2 |
| `.env` local | responsável do projeto, uma vez | acrescentar `MEMETRADE_AUDIT_WALLET_HMAC_KEY=` com 64 caracteres hexadecimais aleatórios | antes de subir o Compose com a U2 |

O `.github/workflows/load-test.yml` chama o `scripts/load-test.sh` e não precisa de mudança além dessa.

**Pool do agendador:** na geração de código, o `docker-compose.yml` passa a repassar `SCHEDULER_POOL_SIZE:-18`. O `.env.example` documenta o intervalo "18 to 32 [18]", com a contagem de jobs atualizada, e o `ComposeFilesTest` confere o novo padrão.

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL | database | migração `0012` aditiva; índice parcial `audit_event_wallet_hmac_idx`; índice `user_session_end_idx` sobre `coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at))`; tabela `identity_job_run` (uma linha por expurgo) | rollback de cada changeset declarado |
| ShedLock (tabela existente) | database | travas `loginChallengePurge` e `sessionPurge`, 35 e 5 minutos | mesmo padrão dos jobs da auditoria |
| Gerenciador de segredos (nuvem) | secrets | `MEMETRADE_AUDIT_WALLET_HMAC_KEY` | desenho; sem rotação no beta |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| Coluna `audit_event.wallet_hmac` | U2 (Audit) | U4 (pedido de Acesso) e U5 (anonimização) | só pelo Audit; a leitura pela carteira entra pela interface do Audit quando U4 ou U5 precisarem |
| Pacote `com.memetrade.shared.retention` e catálogo do Privacy | U2 | U4 (retira `DATA_SUBJECT_REQUESTS` das pendências e declara o expurgo dos pedidos) | interface só de declaração |
| Chave `MEMETRADE_AUDIT_WALLET_HMAC_KEY` | U2 | U4 e U5 (mesmo HMAC) | só o `WalletHasher` do Audit lê |
