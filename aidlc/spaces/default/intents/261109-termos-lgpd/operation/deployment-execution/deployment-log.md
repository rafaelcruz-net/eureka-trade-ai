# Log da implantação

Conversa: português (Brasil). Release local `v2026.11.12-1`, seguindo `operation/deployment-pipeline/cd-config.md` ("Release de hoje (local)") e `rollback-runbook.md`. O alvo é o único ambiente existente em `operation/environment-provisioning/environment-inventory.md`: Docker Compose local. Staging e produção na AWS continuam pendentes. A candidata partiu do Build and Test aprovado (`construction/build-and-test/test-results.md`: backend 1326 de unidade e 949 de integração, Jest com piso, `verify.sh` 19 de 19, carga e segurança dentro das metas). Respostas em `deployment-execution-questions.md`.

## Resumo

| Item | Valor |
|---|---|
| Release | tag local anotada `v2026.11.12-1`, **não enviada** ao GitHub (como a `v2026.11.08-1`) |
| Commit candidato | `9b2695c` "test: make four audit and invite integration tests independent of the shared database order" |
| Versão anterior do ensaio | `v2026.11.08-1` (`8fd7a7b`) |
| Janela | 2026-11-12 (UTC), nesta máquina, sem usuários |
| CI no GitHub | `ci` `43214445497` e `security` `43214445251` verdes em `9b2695c` |
| Piso de rollback | antes da primeira anonimização real: qualquer imagem a partir da `v2026.11.08-1` |
| Resultado | **liberada e verificada pela CI**; o smoke local não foi rodado, por decisão do responsável (pergunta 3) |

## Linha do tempo

| # | Passo | Resultado | Evidência |
|---|---|---|---|
| 1 | Commit de tudo desde o Build and Test (`4c530da`) e push | Gitleaks: "no leaks found" | `git log` |
| 2 | CI de `4c530da` | `ci` verde em todos os jobs; `security` vermelho só por `401 Unauthorized` do OSS Index (nenhuma dependência acima de CVSS 7; as exceções foram lidas) | execuções `43148760824` e `43148760804` |
| 3 | Decisão do responsável: desligar o OSS Index; patch aplicado por ele; commit `e6c634a` | `security` verde em todos os jobs | execução `43208832966` |
| 4 | `ci` de `e6c634a` | 3 testes de integração instáveis no backend (`AdminInviteRouteIT`, dois do `AuthSessionIT`), por ordem de execução no banco de teste compartilhado; o código de produção era o mesmo de `4c530da`, que passou | execução `43208833026` |
| 5 | Correção dos testes (convite achado pelo sufixo; o relé esvazia todos os lotes pendentes, também em `AuthVerifyIT` e `AdminAuditRouteIT`); local 47 de 47; commit `9b2695c` | `ci` e `security` verdes em todos os jobs | execuções `43214445497` e `43214445251` |
| 6 | Ensaio de atualização e rollback num único volume (projeto Compose `memetrade-release`, segredos aleatórios só no processo): `8fd7a7b` → `e6c634a` → `8fd7a7b` → `e6c634a` | os quatro passos saudáveis; ver `health-check-report.md` | script em `apps/api/target/release-rehearsal.sh` (fora do git) |
| 7 | Tag `v2026.11.12-1` em `9b2695c`, com o ensaio e o piso de rollback na mensagem | criada | `git tag -n1` |
| 8 | Smoke da release | `bash scripts/verify.sh` pela CI no commit da tag: `smoke` 19 de 19 e `e2e` 19 de 19, varredura de logs ok | ver `smoke-test-results.md` |

## Migrações

Quatro migrações novas desde a release anterior: `0012-retention`, `0013-reacceptance`, `0014-data-requests` e `0015-anonymization` (de 64 para 83 changesets). Os dois caminhos foram exercitados: atualização sobre o volume existente (passo 6) e banco novo do zero (cada `verify.sh` da CI). O rollback para `8fd7a7b` sobre o banco migrado subiu saudável. A delegação ao desenvolvedor não foi necessária: as migrações rodam pelo serviço `db-migrate` do próprio Compose.

O ensaio usou `e6c634a`; entre ele e `9b2695c` só mudaram testes, então as imagens de produção são as mesmas.

## Desvios e incidentes

- O smoke local final não foi rodado: o agente não pode chamar o `verify.sh` diretamente, e o responsável decidiu seguir sem ele. Vale o mesmo script rodado duas vezes pela CI no commit da tag.
- O analisador OSS Index foi desligado por decisão do responsável (registrado em `construction/ci-pipeline/ci-config.md`).
- Três testes instáveis apareceram e foram corrigidos na causa (passo 5); nenhum teste foi desligado nem repetido até passar.

## Rollback

Caminho testado no passo 6: subir `8fd7a7b` sobre o banco atual (`rollback-runbook.md`). Não foi preciso usar. Depois da primeira anonimização real, o piso passa a ser a imagem da U5.
