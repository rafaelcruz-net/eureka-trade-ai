# Perguntas do Deployment Pipeline

Conversa: português (Brasil). Contexto que vale para as perguntas:

- Hoje não há conta de nuvem, região nem orçamento (C-10, C-12, `team.md` → Deployment). O único ambiente é o local, no Windows, com Docker Compose (`docker-compose.yml` mais o overlay de teste).
- A CI (`ci.yml`, `security.yml`) termina no merge em `main` com tudo verde; as imagens são construídas e marcadas com o SHA do commit (`IMAGE_TAG`), mas descartadas no fim do job.
- O `team.md` já fixa: staging recebe a cada merge em `main` quando existir; a produção do beta PAPER exige aprovação manual separada do responsável do projeto; REAL (G2/G3) exige um revisor independente ainda a nomear (C-14); toda implantação tem rollback documentado e testado; artefatos marcados com o SHA; migrações em expand-contract; a implantação só termina quando o smoke passa; destino futuro AWS com IaC e CD na Sprint 7.
- O rollback de hoje, nos `cicd-pipeline.md` das Units, é manual: checkout do commit anterior, `docker compose up -d --build --remove-orphans` e `bash scripts/verify.sh` com saída 0.

## Pergunta 1
Para onde o Deployment Pipeline implanta agora?

A) Só o ambiente local com Docker Compose: a "implantação" é uma release marcada por tag em `main`, com as imagens construídas do SHA e o `verify.sh` como smoke. Staging e produção na AWS ficam desenhados como alvo futuro, para quando houver conta (Environment Provisioning, Sprint 7) (recomendado: é o que existe hoje)
B) Criar já um staging num serviço externo (precisa de conta e orçamento)
X) Other (please specify)

[Answer]: A

## Pergunta 2
Qual estratégia de implantação?

A) Hoje, recreate local (para os contêineres da versão antiga e sobe os da nova, com o banco migrado por expand-contract). No futuro na AWS, blue/green para a API, o worker e os dois apps web, com troca de tráfego só depois do smoke (recomendado: simples agora, seguro depois)
B) Rolling no futuro, sem blue/green
C) Canary no futuro, com uma fração do tráfego
X) Other (please specify)

[Answer]: A

## Pergunta 3
Quais portões de promoção entre ambientes?

A) Merge em `main` só com a CI verde (portões do `quality-gates.md`) → staging automático a cada merge, quando existir, com o smoke no staging → produção do beta PAPER só com aprovação manual do responsável do projeto e smoke verde na produção (recomendado: é o que o `team.md` define)
B) Promoção manual também para o staging
X) Other (please specify)

[Answer]: A

## Pergunta 4
Como fica a aprovação para produção?

A) Um ambiente `production` no GitHub (Environments) com o responsável do projeto como revisor obrigatório; qualquer função REAL fica bloqueada até os gates G2/G3, que exigem um revisor independente (C-14); a autoaprovação não vale para G2/G3 (recomendado)
B) Aprovação fora do GitHub (por exemplo, registro manual)
X) Other (please specify)

[Answer]: A

## Pergunta 5
Qual procedimento de rollback?

A) Voltar para as imagens do SHA anterior (no futuro, do registro; hoje, checkout do commit anterior e build), sem desfazer migração (expand-contract garante que a versão anterior roda sobre o banco migrado), e confirmar com o smoke (`verify.sh` local; smoke do ambiente no futuro). O procedimento é testado: um ensaio de rollback local antes de cada release (recomendado)
B) Rollback com restauração do banco a partir de backup
X) Other (please specify)

[Answer]: A

## Pergunta 6
Qual estratégia de feature flags?

A) Nenhum serviço de flags no beta: REAL continua desligado por configuração padrão (sem caminho de assinatura), o kill switch da U8 é a chave operacional para pausar os bots, e funções novas entram desligadas por configuração do Spring. Um serviço de flags (por exemplo, AppConfig) fica para quando houver AWS (recomendado)
B) Adotar já um serviço de flags gerenciado
X) Other (please specify)

[Answer]: A

## Pergunta 7
Onde ficam as imagens das releases?

A) Por enquanto, em nenhum registro: a release é a tag em `main`, e as imagens são reconstruídas do SHA. O registro (por exemplo, ECR, ou o GitHub Container Registry) entra no Environment Provisioning, junto com a conta de nuvem (recomendado: segue a resposta 6A do CI Pipeline)
B) Publicar já as imagens no GitHub Container Registry a cada tag de release
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
