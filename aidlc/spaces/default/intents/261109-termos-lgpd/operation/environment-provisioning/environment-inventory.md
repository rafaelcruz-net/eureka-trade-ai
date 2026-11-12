# Inventário de ambientes

Conversa: português (Brasil). Respostas 1A a 4A de `environment-provisioning-questions.md`. Nenhum recurso de nuvem foi criado. O inventário base (serviços, portas, memória, rede e o desenho da AWS) é o do intent anterior (`261026-memetrade-saas-mvp/operation/environment-provisioning/environment-inventory.md`); aqui ficam só as mudanças das Units U1 a U5 deste intent.

Fontes: `construction/u1-legal-documents/infrastructure-design/infrastructure-specification.md` até `construction/u5-anonymization/infrastructure-design/infrastructure-specification.md` e `operation/deployment-pipeline/cd-config.md`.

## Ambientes

| Ambiente | Situação | Mudança neste intent |
|---|---|---|
| Local (Docker Compose) | existe | `api` e `worker` exigem `MEMETRADE_AUDIT_WALLET_HMAC_KEY`; papel novo da U5 no `db-bootstrap`; migrações `0012` a `0015`; pool fixo de 20 conexões na `api`; catálogo de termos na imagem |
| CI (efêmero) | existe | chave HMAC efêmera no `verify.sh`; valor fictício no passo "Compose config" |
| Staging (AWS) | **pendente** (C-10, C-12) | ver "Pendências" |
| Produção do beta PAPER (AWS) | **pendente** | ver "Pendências" |

## Banco de dados

| Item | Valor |
|---|---|
| Migrações aplicadas | `0001` a `0015` (este intent: `0012-retention`, `0013-reacceptance`, `0014-data-requests`, `0015-anonymization`) |
| Papéis | os do intent anterior, mais o papel da função `SECURITY DEFINER` que apaga feedback (U5), criado por `infra/postgres/bootstrap/10-roles.sql` |
| Conexões | orçamento em `docker-compose.yml`: `api` 20 (pool fixo) + retenções e travas 4 + `worker` 14 + bootstrap e migração 2 + 3 reservadas = 43 de 100 (`ComposeFilesTest`) |

## Segredos e parâmetros

| Item | Tipo | Hoje | AWS (desenho) |
|---|---|---|---|
| `MEMETRADE_AUDIT_WALLET_HMAC_KEY` (U2) | segredo, 32 bytes ou mais | efêmera no `verify.sh`, no `load-test.sh` e na CI; no `.env` local para o ambiente de desenvolvimento | Secrets Manager, lida só pela `api` e pelo `worker`; sem rotação no beta, troca só por incidente |
| Campos de implantação dos documentos legais (U1) | parâmetro | ausentes no ambiente de teste (as páginas mostram "a definir") | Parameter Store, preenchidos antes de publicar a versão do beta |
| `DB_POOL_MAX_SIZE` | parâmetro | padrão 20 (aceito de 10 a 50) | Parameter Store; conferir com o `max_connections` do banco gerenciado |
| Os do intent anterior | — | sem mudança | sem mudança |

O `.env` está ignorado pelo git; o `.env.example` traz só o nome `MEMETRADE_AUDIT_WALLET_HMAC_KEY`, sem valor (`ComposeFilesTest`).

## Ação do responsável no ambiente local

Hoje não existe `.env` local. O `verify.sh` e o `load-test.sh` funcionam sem ele (geram segredos temporários por execução), mas `docker compose up` para desenvolvimento precisa de um `.env` copiado de `.env.example`, com `MEMETRADE_AUDIT_WALLET_HMAC_KEY` igual a 64 caracteres hexadecimais aleatórios (por exemplo, `openssl rand -hex 32`). A chave nunca entra no repositório.

## Pendências para quando houver AWS

As do intent anterior, mais:
1. Secrets Manager com a chave HMAC da trilha.
2. Parameter Store com os campos de implantação dos documentos legais.
3. Orçamento de conexões do banco gerenciado: `DB_POOL_MAX_SIZE` × réplicas da `api` + `worker` + tarefas.
4. Alarmes `PURGE_STALE` (U2) e `DATA_REQUEST_OVERDUE` (U4), com a Observability Setup.
