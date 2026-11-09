# Log da implantação

Conversa: português (Brasil). Release local `v2026.11.08-1`, seguindo `operation/deployment-pipeline/cd-config.md` ("Release de hoje (local)") e a estratégia recreate de `operation/deployment-pipeline/deployment-strategy.md`. O alvo é o único ambiente existente em `operation/environment-provisioning/environment-inventory.md`: Docker Compose local. Staging e produção na AWS continuam pendentes. Respostas 1A a 5A de `deployment-execution-questions.md`.

## Resumo

| Item | Valor |
|---|---|
| Release | tag local anotada `v2026.11.08-1`, **não enviada** ao GitHub |
| Commit candidato | `8fd7a7b` "chore: postcss override, CI, deployment and environment records" |
| Versão anterior do ensaio | `0cd9ccf` (último commit aprovado no Build and Test) |
| Janela | 2026-11-09 (UTC), terminada às 01:01 UTC, nesta máquina, sem usuários |
| Resultado | **implantada e verificada localmente** |
| CI no GitHub | pendente até o push; as mudanças do `ci.yml` em `ci-config.md` ainda dependem do responsável do projeto |

## Linha do tempo

| # | Passo | Resultado | Evidência |
|---|---|---|---|
| 1 | Pré-checagens: Docker 29.9.2 (Compose 5.6.1), Node 24.22.1, pnpm 10.18.3 via corepack, JDK local 25.0.1 (o build do backend roda na imagem com Java 21) | ok | saída das versões |
| 2 | Commit das mudanças pendentes em `main` (pedido pela pessoa ao escolher 1A); `load-test-report.json` fora | `8fd7a7b`; gitleaks: "no leaks found" | `git log` |
| 3 | Portões locais no lugar da CI: testes do web e do admin com cobertura | web 391/391 (33 suítes); admin 85/85 (10 suítes); os limites de cobertura do Jest passaram | ver `smoke-test-results.md` |
| 4 | Backend | sem mudança em `apps/api`, `scripts`, `infra` e `docker-compose.yml` desde `0cd9ccf` (`git diff --stat` vazio); vale o resultado do Build and Test (`construction/build-and-test/test-results.md`) | `git diff` |
| 5 | Ensaio de atualização e rollback sobre um único volume do banco (projeto Compose `memetrade-release`, segredos aleatórios só no processo): anterior → candidata → rollback para a anterior → candidata | os quatro passos subiram saudáveis; no último: `db-bootstrap` e `db-migrate` com saída 0, 64 changesets no `databasechangelog`, API `UP`, web 200, admin 200; o ambiente foi removido (saída 0) | ver `health-check-report.md` |
| 6 | `verify.sh` na versão anterior (`0cd9ccf`), rodado pelo responsável do projeto | 10/10 em 2,9 min; varredura de logs ok | ver `smoke-test-results.md` |
| 7 | `verify.sh` na candidata, duas vezes, rodado pelo responsável do projeto | 10/10 e 10/10 (2,9 min cada); varredura de logs ok; terminou às 01:01 UTC | ver `smoke-test-results.md` |
| 8 | Tag `v2026.11.08-1` em `8fd7a7b`, com o resultado do ensaio na mensagem da tag | criada | `git tag -n1` |

## Migrações

Nenhuma migração nova entre `0cd9ccf` e `8fd7a7b`. Os dois caminhos foram exercitados: atualização sobre o volume existente (passo 5, `db-migrate` sem pendências) e banco novo do zero (cada `verify.sh`). O rollback para `0cd9ccf` sobre o banco migrado subiu saudável, como o expand-contract promete (`deployment-strategy.md`). A delegação ao desenvolvedor não foi necessária, porque não havia migração a executar.

## Desvios e incidentes

- Rodar `bash scripts/verify.sh` a partir do agente é bloqueado. Os três smokes foram rodados pelo responsável do projeto no terminal da sessão.
- A primeira tentativa dos smokes rodou só a versão anterior: o terminal estava em `apps/admin`, e o `!` seguido de espaço inverteu o resultado do primeiro comando. Os dois smokes da candidata foram repetidos a partir da raiz. Nenhum ambiente ficou para trás.
- O log dos passos 1 a 3 do ensaio se perdeu no filtro de saída. Eles passaram porque o script para no primeiro erro (`set -e`) e chegou ao passo 4 e a "rehearsal passed".
- A worktree temporária de `0cd9ccf` foi removida do git. Os arquivos que sobraram ficam na pasta temporária da sessão, fora do repositório.

## Rollback

Caminho testado no passo 5: subir `0cd9ccf` com `IMAGE_TAG=0cd9ccf` sobre o banco atual (`rollback-runbook.md`). Não foi preciso usar.
