# Perguntas do CI Pipeline

Conversa: português (Brasil). O repositório já tem CI no GitHub Actions, criada na U1 e ampliada nas Units seguintes:

- `.github/workflows/ci.yml`: roda em todo pull request e em push para `main`. Tem os jobs `backend` (`mvnw verify` com cobertura), `frontend` (só `apps/web`: lint, formatação, tipos e Jest de parte das pastas), `contract` (contratos OpenAPI e tipos gerados), `smoke` (`scripts/verify.sh`) e `e2e` (só no push para `main`).
- `.github/workflows/security.yml`: Gitleaks, CodeQL (SAST), OWASP Dependency-Check e `pnpm audit` do `apps/web` e do `apps/admin`, com exceções versionadas.
- `.github/workflows/load-test.yml`: carga manual (`workflow_dispatch`), por cenário.

As ações de terceiros estão fixadas por SHA e as permissões são só de leitura, como pede o `team.md`.

Lacunas que o Build and Test mostrou:
- o job `frontend` não roda o `apps/admin` (lint, formatação, tipos e Jest com o piso de 80%);
- o job `frontend` roda o Jest só em `src/features/auth`, `src/features/scanner`, `src/lib` e `src/middleware.test.ts`, e não na suíte inteira do `apps/web` (391 testes) com o piso de cobertura;
- o `pnpm audit` acusa 2 avisos High no `postcss` (via `next`), que pela regra da CI bloqueiam o merge.

## Pergunta 1
Qual ferramenta de CI usamos?

A) GitHub Actions, como já está no repositório (recomendado: é o que o `team.md` define)
B) Outra ferramenta (CodePipeline, CodeBuild, Jenkins)
X) Other (please specify)

[Answer]: A

## Pergunta 2
Qual é a estratégia de branches que a CI deve seguir?

A) Desenvolvimento baseado em tronco: `main` é o tronco, CI em todo pull request e em push para `main`, integração por squash-merge, nenhum merge com a CI vermelha (recomendado: é o que o `team.md` define e o que o `ci.yml` já faz)
B) Outra estratégia (por exemplo, branches de release)
X) Other (please specify)

[Answer]: A

## Pergunta 3
Quais portões de qualidade devem bloquear o merge?

A) Os de hoje mais as lacunas: build e testes do backend com JaCoCo ≥ 80%; `apps/web` com a suíte Jest inteira e piso de 80%; um job novo para o `apps/admin` (lint, formatação, tipos, Jest com 80%); contratos; smoke `scripts/verify.sh` (agora com U3 e U9); segurança (segredos, SAST, dependências Crítico/Alto) (recomendado)
B) Só os de hoje, sem o `apps/admin` nem a suíte inteira do `apps/web`
C) A, e também a carga (`load-test.yml`) a cada merge
X) Other (please specify)

[Answer]: A

## Pergunta 4
O `.github/workflows/ci.yml` está entre os arquivos que você pediu para eu não editar à mão. Fechar as lacunas da Pergunta 3 muda esse arquivo. Como fazemos?

A) Eu preparo a mudança exata em `ci-config.md` e você aplica no `ci.yml` (recomendado: mantém a sua regra)
B) Você me autoriza a editar o `ci.yml` só nesta etapa, para as mudanças da Pergunta 3
X) Other (please specify)

[Answer]: A

## Pergunta 5
O `pnpm audit` acusa 2 avisos High no `postcss` (GHSA-6g55-p6wh-862q e GHSA-r28c-9q8g-f849; correção no `postcss` 8.5.18 ou superior), vindo pelo `next`, no `apps/web` e no `apps/admin`. Pela regra da CI, isso bloqueia o merge. O que fazemos?

A) Forçar o `postcss` corrigido com `pnpm.overrides` nos dois apps, atualizar os lockfiles e rodar de novo build, testes e smoke (recomendado: corrige a causa)
B) Registrar uma exceção versionada, com justificativa, validade de até 90 dias e a sua aprovação, até o `next` trazer a versão corrigida
C) Esperar uma versão nova do `next` e deixar o job de dependências vermelho até lá
X) Other (please specify)

[Answer]: A

## Pergunta 6
Onde ficam os artefatos gerados pela CI?

A) Só como artefatos do GitHub Actions (relatórios de testes, de cobertura e da carga), com retenção curta. Imagens de contêiner e registro ficam para os estágios de implantação, quando houver nuvem (recomendado: hoje não há conta de nuvem, C-10 e C-12)
B) Já publicar imagens num registro (por exemplo, GitHub Container Registry) a cada merge para `main`
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
