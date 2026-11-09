# Perguntas do Deployment Execution

Conversa: português (Brasil). Alvo: só o ambiente local (Docker Compose), conforme `cd-config.md` ("Release de hoje (local)") e `environment-inventory.md`. Staging e produção na AWS estão pendentes. Por isso não há checagem de identidade AWS: nenhum comando desta etapa usa a AWS CLI, o CDK ou o SAM.

O que existe hoje (lido do repositório):
- Último commit em `main`: `0cd9ccf`. Ainda não há tag de release.
- Fora do commit: o override do `postcss` 8.5.28 nos dois apps (`package.json` e lockfiles) e os registros das etapas CI Pipeline, Deployment Pipeline e Environment Provisioning. `load-test-report.json` nunca entra no commit.
- A CI do GitHub não roda sem push. As mudanças no `ci.yml` descritas em `ci-config.md` (cobertura completa do web, job `admin`) ainda dependem de você.
- Migrações: nenhuma migração nova desde `0cd9ccf`. O `db-migrate` (Liquibase, só expand) roda a cada subida.

## Pergunta 1
Qual commit vira a release local?

A) Você pede o commit das mudanças pendentes (postcss e registros) em `main`. A release usa esse commit, com os portões locais no lugar da CI: `verify.sh` 10/10 e os testes do backend, do web e do admin. A CI no GitHub fica registrada como pendente até o push (recomendado)
B) Usar `0cd9ccf` como está, sem o override do `postcss` (esse commit ainda tem o achado Alto do `pnpm audit`)
C) Não fazer release agora: só ensaiar a subida e o rollback, sem criar tag
X) Other (please specify)

[Answer]: A

## Pergunta 2
Como validar as migrações?

A) Subir a release sobre o volume local que já existe, testando o caminho de atualização, e depois rodar o `verify.sh`, que sobe um banco novo do zero. Assim os dois caminhos ficam cobertos (recomendado)
B) Só o `verify.sh` (banco novo)
X) Other (please specify)

[Answer]: A

## Pergunta 3
Os serviços dependentes estão disponíveis?

A) Sim: só existem os adaptadores simulados (sem fornecedor real, C-06, e sem carteira real). Docker Desktop, Java 21 e pnpm são conferidos antes da subida (recomendado)
B) Conferir algo a mais antes (diga o quê)
X) Other (please specify)

[Answer]: A

## Pergunta 4
Qual a janela de implantação?

A) Agora, nesta máquina. Não há usuários nem tráfego a proteger, e o recreate local é aceitável (recomendado)
B) Marcar outro horário
X) Other (please specify)

[Answer]: A

## Pergunta 5
Como fazer o ensaio de rollback, já que ainda não existe release anterior?

A) Usar `0cd9ccf` como "versão anterior": candidata com `verify.sh`, rollback para `0cd9ccf` sobre o banco já migrado com `verify.sh`, volta para a candidata com `verify.sh`. A tag local `v2026.11.08-1` só é criada se os três passarem, e não é enviada ao GitHub sem seu pedido (recomendado)
B) Pular o ensaio nesta primeira release e registrar o motivo
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Commit das mudanças pendentes (postcss e registros) em `main`, a pedido seu ao escolher 1A; `load-test-report.json` fica fora. A release usa esse commit, validada pelos portões locais (testes do backend, do web e do admin, e `verify.sh`); a CI do GitHub fica pendente até o push.
- Migrações validadas nos dois caminhos: atualização sobre o volume local existente e banco novo no `verify.sh`.
- Dependências: só adaptadores simulados; Docker, Java 21 e pnpm conferidos antes.
- Janela: agora, nesta máquina.
- Ensaio de rollback: candidata → `0cd9ccf` → candidata, com `verify.sh` em cada passo; a tag local `v2026.11.08-1` só é criada se os três passarem, e não é enviada ao GitHub.

- Looks correct
- Request changes

[Answer]: Looks correct
