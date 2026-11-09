# Configuração de entrega contínua (CD)

Conversa: português (Brasil). Respostas confirmadas em `deployment-pipeline-questions.md` (todas A). Entradas: `construction/ci-pipeline/ci-config.md`, `quality-gates.md`, os `infrastructure-specification.md` e `cicd-pipeline.md` das Units U1 a U8, e o `team.md` (Deployment).

## Situação

- Não há conta de nuvem, região nem orçamento (C-10, C-12). O único ambiente é o local, no Windows, com Docker Compose.
- A CI termina no merge em `main` com todos os portões verdes. As imagens são construídas com a tag do SHA do commit (`IMAGE_TAG`) e descartadas no fim do job.
- Esta etapa define a entrega de hoje (release local) e desenha a de amanhã (staging e produção na AWS), sem criar recurso de nuvem.

## Matriz de ambientes e promoção

| Ambiente | Existe hoje | Como recebe uma versão | Portão de entrada | Smoke | Aprovação |
|---|---|---|---|---|---|
| Local (Docker Compose) | sim | `git checkout <tag>` e `docker compose up -d --build --remove-orphans` com `IMAGE_TAG=<sha>` | merge em `main` com a CI verde (`quality-gates.md`) | `bash scripts/verify.sh` (saída 0, 10 testes Playwright) | nenhuma |
| Staging (AWS) | não; Environment Provisioning | automático a cada merge em `main` | CI verde; imagens do SHA publicadas no registro | smoke de ambiente (os mesmos smokes Playwright apontados para o staging) | nenhuma |
| Produção do beta PAPER (AWS) | não | workflow `deploy` com o ambiente `production` do GitHub | staging verde com o mesmo SHA; tag de release em `main` | smoke de ambiente em produção | responsável do projeto (revisor obrigatório) |
| Funções REAL | não existem no código (FR15.2) | — | gates G2 e G3 do PRD e aprovações jurídicas | — | revisor independente, ainda a nomear (C-14); autoaprovação não vale |

## Release de hoje (local)

1. Merge em `main` com todos os portões verdes.
2. Criar a tag de release em `main` (`vAAAA.MM.DD-N`, por exemplo `v2026.11.08-1`), apontando para o commit verde.
3. Ensaio de rollback local antes de anunciar a release (ver `rollback-runbook.md`, seção "Ensaio").
4. Subir a release: `git checkout v…`, `IMAGE_TAG=$(git rev-parse --short HEAD) docker compose up -d --build --remove-orphans`.
5. Smoke: `bash scripts/verify.sh` com saída 0. A release só termina quando o smoke passa (`team.md`).
6. Registrar no histórico da tag: SHA, data, resultado do smoke e do ensaio de rollback.

## Pipeline futuro na AWS (desenho, sem recurso criado)

Workflow `deploy.yml`, para quando o Environment Provisioning criar a conta, o registro (por exemplo, ECR) e os ambientes:

| Job | Gatilho | Passos | Falha |
|---|---|---|---|
| `publish` | push em `main` depois do `ci` verde | construir as imagens `api`, `worker`, `web` e `admin` com a tag do SHA; publicar no registro; gerar SBOM e varrer as imagens (candidatos do G1, `team.md`) | não publica; nada é promovido |
| `staging` | depois do `publish` | aplicar migrações (expand); implantar blue/green no ambiente verde; smoke de ambiente; trocar o tráfego | não troca o tráfego; o azul continua; alerta |
| `production` | tag de release `v*` em `main`, com ambiente `production` do GitHub (revisor obrigatório: o responsável do projeto) | mesmo SHA do staging; migrações (expand); blue/green; smoke; troca de tráfego; contract só numa release seguinte | não troca o tráfego; rollback automático para o azul |

Regras do workflow futuro, iguais às da CI:
- ações de terceiros fixadas por SHA; `permissions` mínimas por job; credenciais da nuvem por OIDC, sem segredo de longa duração;
- segredos de produção só no ambiente `production`, nunca em workflow disparado por branch ou fork não confiável;
- `concurrency` por ambiente, para nunca haver duas implantações ao mesmo tempo no mesmo ambiente.

## Feature flags

- Nenhum serviço de flags no beta.
- REAL continua desligado por configuração padrão: nenhum caminho assina ou envia transação com a configuração padrão (`project.md`, testes obrigatórios por risco).
- O kill switch da U8 é a chave operacional: pausa todos os bots sem nova implantação.
- Funções novas entram desligadas por propriedade do Spring (`memetrade.<função>.enabled=false`) e são ligadas por configuração do ambiente.
- Um serviço de flags gerenciado (por exemplo, AppConfig) fica para quando houver AWS.

## Artefatos e rastreio

| Item | Hoje | Futuro |
|---|---|---|
| Imagens | reconstruídas do SHA da tag | publicadas no registro com a tag do SHA e a tag de release |
| Rastreio | tag de release → SHA → resultado do `verify.sh` | registro → SHA → implantação → smoke do ambiente |
| Retenção | artefatos do GitHub Actions por 7 dias | imagens das últimas releases mantidas para rollback |

## Pendências para o Environment Provisioning

- Conta de nuvem, região, orçamento e o registro de imagens (C-10, C-12).
- Réplica de leitura para a escala da U4 (decisão da pessoa, U4-NFR3.5) e nova medição antes do G1.
- Execução do `perf-audit.sh` da U2 num ambiente com memória suficiente.
- Ambiente `production` no GitHub com o responsável do projeto como revisor obrigatório.
