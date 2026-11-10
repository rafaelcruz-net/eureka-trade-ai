# Especificação de infraestrutura — U4 `u4-data-requests`

Conversa: português (Brasil). Implementa o desenho de `construction/u4-data-requests/nfr-design/` no ambiente local existente, com o desenho para a nuvem. As escolhas seguem as recomendações (A), pelo pedido do responsável do projeto.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Compute | Contêineres existentes (`api`, `web`, `admin`); nenhum serviço novo. Os jobs do Privacy rodam só no processo `api` | código no monólito e nos apps |
| Rede | Sem mudança | rotas novas atrás da API existente |
| Armazenamento | PostgreSQL existente. A migração `0014-data-requests.sql` fica registrada no `db.changelog-master.yaml` e só acrescenta:<ul><li>as tabelas `data_subject_request`, `data_subject_verification` e `privacy_job_run`;</li><li>os índices;</li><li>as concessões: `SELECT`, `INSERT`, `UPDATE` e `DELETE` para o papel da aplicação, no mesmo padrão das tabelas do Identity. As tabelas não são append-only, e o expurgo usa a conexão principal, como na U2.</li></ul> | expand-contract (NFR5.1) |
| Ambientes | Local (Compose e overlay de teste); nuvem só desenhada | sem conta de nuvem |
| IaC | Compose versionado; muda só o padrão do pool | — |
| Tamanho | O pool do agendador da API vai de 18 a 21. Mudam juntos:<ul><li>`application.yml`;</li><li>`RuntimeSettingsGuard.MIN_SCHEDULER_POOL`;</li><li>`SCHEDULER_POOL_SIZE:-21` no Compose;</li><li>"21 to 32 [21]" no `.env.example`;</li><li>os testes que fixam o valor: `U4ContextIT`, `U4SettingsTest` a `U8SettingsTest`, `U6ContextIT`, `U7ContextIT`, `AuditPropertiesTest` e `ComposeFilesTest`.</li></ul> | três `@Scheduled` novos |

## Configuração nova

| Propriedade | Padrão | Observação |
|---|---|---|
| `memetrade.privacy.timers-cron` | a cada 15 minutos | `application.yml` |
| `memetrade.privacy.purge-cron` | `0 0 4 * * *` UTC | `application.yml` |
| `memetrade.privacy.requests-per-hour` | `10` | `application.yml`; o perfil `loadtest` eleva o valor |
| `memetrade.terms.reacceptance.allowed-prefixes` | acrescenta `/api/v1/privacy/` | lista da U3 |

Nenhuma é segredo e nenhuma vira variável de ambiente obrigatória.

## Dados de teste

| Ambiente | Mudança |
|---|---|
| `verify.sh` (perfil `test`) | O spec novo `e2e/data-requests` usa a conta do smoke, que já tem aceite (U3), para criar um pedido na Conta e vê-lo na lista. Com a carteira de administrador do smoke (`SMOKE_ADMIN_WALLET_FILE`, existente), ele também abre o pedido no app do administrador, conclui com resposta e confere a resposta na Conta. Não precisa de variável nova |
| Cenário de carga `data-requests` | Mudanças na geração de código.<ul><li>**`scripts/load-test.sh`:** ganha o caso `data-requests`, com a mensagem de erro atualizada. Ele:<ul><li>gera os próprios `SMOKE_INVITE_CODES` (20) e define `USERS=20` e a duração de 5 minutos, como o ramo `u3`;</li><li>gera uma carteira de administrador efêmera, no mesmo formato do `verify.sh`, e exporta `ADMIN_WALLET_ADDRESSES` e `SMOKE_ADMIN_WALLET_FILE`;</li><li>liga `LOADTEST_DATA_REQUESTS_SEED=1000`, que vale `0` em todos os outros cenários.</li></ul></li><li>**`load-test.mjs`:** despacha para `scripts/load/data-requests.mjs`, que vem com teste `node --test`. O cenário:<ul><li>cria cada pessoa pelo convite;</li><li>faz cada pessoa criar um pedido e listar os seus;</li><li>entra como administrador pela rota de entrada administrativa existente, com a carteira efêmera, e mede a lista, o detalhe e a prévia do resumo;</li><li>reprova com p95 acima de 500 ms no convidado ou de 1 s no administrador.</li></ul></li><li>**Semeadura:** a classe nova `privacy/loadtest/DataRequestLoadSeeder` existe só com o perfil `loadtest` e com `memetrade.loadtest.data-requests.seed` maior que zero (ligado a `LOADTEST_DATA_REQUESTS_SEED`, padrão `0` em `application-loadtest.yml`). Ela grava 1000 pedidos:<ul><li>de 250 contas sintéticas de um tenant de carga;</li><li>quatro tipos por conta;</li><li>metade `DONE` (fechados, fora do índice parcial de abertos) e metade `RECEIVED` (um aberto por tipo e conta, então sem conflito no índice);</li><li>com textos sintéticos, sem dado pessoal.</li></ul></li></ul> |

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL | database | migração `0014`; índices `(tenant_id, user_id, received_at)`, `(status, due_at)`, `closed_at` parcial e único parcial dos abertos por tipo | rollback declarado em cada changeset |
| ShedLock (tabela existente) | database | `dataRequestTimers` (5/1 min) e `dataSubjectRequestPurge` (35/5 min) | — |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| `data_subject_request` | U4 (Privacy) | U5 (Exclusão e anonimização) | só pelo Privacy |
| Pool do agendador da API | todas | U5 pode acrescentar jobs | a contagem fica no comentário do `application.yml` |
