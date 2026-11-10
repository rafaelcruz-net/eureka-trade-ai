# Especificação de infraestrutura — U5 `u5-anonymization`

Conversa: português (Brasil). Implementa o desenho de `construction/u5-anonymization/nfr-design/` no ambiente local existente, com o desenho para a nuvem. As escolhas seguem as recomendações (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Compute | Contêineres existentes (`api`, `admin`); nenhum serviço novo. A anonimização é síncrona, no processo `api`, sem job | NFR3.3 |
| Rede | Sem mudança | a rota nova fica atrás da API existente, sob `/api/v1/admin/` |
| Armazenamento | PostgreSQL existente. A migração `0015-anonymization.sql` fica registrada no `db.changelog-master.yaml`, um changeset por mudança, cada um com `--rollback`:<ul><li>`app_user_status_ck` com `ANONYMIZED`;</li><li>`wallet.address` e `wallet.provider` sem `NOT NULL`, mais `wallet.anonymized_at`;</li><li>`wallet_anonymized_ck` e `wallet_address_base58_ck` só com endereço presente;</li><li>`login_challenge_wallet_idx`;</li><li>a função `erase_feedback_of_user` com `SECURITY DEFINER`, `EXECUTE` só para o papel da aplicação.</li></ul> O rollback que volta o `NOT NULL` só vale antes da primeira anonimização | expand-contract (NFR5.3) |
| Papéis do banco | `infra/postgres/bootstrap/10-roles.sql` (serviço `db-bootstrap`, superusuário, a cada `docker compose up` e na base dos testes de integração) passa a conceder `feedback_retention` ao dono do esquema e a revogá-lo só do login da aplicação. O comentário do bloco U8 é reescrito. A função da `0015` é `SECURITY DEFINER` com `SET search_path = public, pg_temp`, `REVOKE ALL ... FROM PUBLIC` e `GRANT EXECUTE` só ao login da aplicação. **Risco aceito:** com isso, uma migração futura (que roda como dono) pode apagar feedback sem ser barrada pelo gatilho. A ampliação é pequena, porque o dono já é dono de `feedback_entry` e do gatilho e poderia desligá-lo; a mitigação é que toda migração é revisada pelo responsável do projeto nos gates do AI-DLC, e que o `U8PrivilegesIT` e o `RolesBootstrapIT` provam que o login da aplicação continua fora do papel | NFR2.3 |
| Ambientes | Local (Compose e overlay de teste); nuvem só desenhada. Na nuvem, o mesmo script de papéis roda no passo de preparo do banco gerenciado, antes das migrações | sem conta de nuvem |
| IaC | Compose versionado sem mudança | — |
| Tamanho | Sem mudança: pool do agendador continua em 21; pool de conexões igual | a anonimização é rara e curta (NFR3.1) |

## Configuração nova

Nenhuma propriedade nova. O `lock_timeout` da anonimização reaproveita `memetrade.bots.lock-timeout` (2 s). O prefixo `/api/v1/admin/` já fica fora da trava de novo aceite da U3.

## Dados de teste

| Ambiente | Mudança |
|---|---|
| `verify.sh` (perfil `test`) | Spec novo `e2e/anonymization` (`apps/web/e2e/anonymization/`), que nunca toca a conta do smoke. Ele usa uma conta descartável:<ul><li>o `SmokeInviteSeeder` (perfil `test`) passa a semear um segundo convite de uso único, com o código lido de `SMOKE_ANONYMIZATION_INVITE_CODE`;</li><li>o spec gera uma carteira efêmera no código de teste (`e2e/support/test-wallet.ts`), entra pelo convite, aceita os termos e cria um pedido de Exclusão na Conta (origem `APP`, sem verificação por e-mail);</li><li>com a carteira de administrador do smoke (`SMOKE_ADMIN_WALLET_FILE`, existente), abre o pedido no app do administrador, passa para `IN_PROGRESS`, abre o diálogo T8, digita `ANONIMIZAR` e confirma;</li><li>confere o pedido `DONE`, a listagem de contas com "Conta anonimizada", a recusa do token antigo na requisição seguinte e a recusa de um novo login com a mesma carteira.</li></ul> Assim o caminho navegador, app do administrador, rota nova, banco e as travas novas do login e da renovação passam pela API real. O `verify.sh` precisa gerar e exportar `SMOKE_ANONYMIZATION_INVITE_CODE` e acrescentar `e2e/anonymization` ao fim da lista de specs, o que fica como pré-requisito do responsável do projeto em `cicd-pipeline.md`. Os demais specs continuam verdes |
| Cenário de carga | Nenhum cenário novo: as anonimizações são raras (NFR3.3) e a meta de 2 s é medida no `AnonymizationLatencyIT` |

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL | database | migração `0015`; índice `login_challenge_wallet_idx`; função `erase_feedback_of_user` | rollback declarado em cada changeset |
| `db-bootstrap` | database | `10-roles.sql` com o dono membro de `feedback_retention` | idempotente |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| `data_subject_request` | U4 (Privacy) | U5 conclui o pedido de Exclusão | só pelo Privacy |
| Papel `feedback_retention` | Feedback (0011) e `db-bootstrap` | login de retenção e o dono (função da U5) | a aplicação nunca é membro |
| `LockOrder` | Bots | U5 | mesma ordem em todos os caminhos |
