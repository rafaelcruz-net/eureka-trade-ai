# Perguntas do Environment Provisioning

Conversa: português (Brasil). Decisão de escopo da pessoa (2026-11-08): inventariar e validar o ambiente local e registrar a AWS como pendente. Nenhum recurso de nuvem é criado nesta etapa.

O que existe hoje (lido do repositório):
- `docker-compose.yml`: `postgres` (17-alpine, fixado por digest, 1 GB), `db-bootstrap` e `db-migrate` (rodam e terminam), `api` (1 GB), `worker` (1 GB, sem porta), `web` (512 MB) e `admin` (512 MB); imagens marcadas com `IMAGE_TAG`.
- Portas publicadas só em `127.0.0.1`: 5432 (postgres), 8080 e 8081 (api e gestão), 3000 (web), 3001 (admin). O overlay de teste publica também `127.0.0.1:8082` (gestão do worker).
- Segredos em `.env` local (ignorado pelo git), com `.env.example` sem valores: senhas do banco (`POSTGRES_PASSWORD`, `DB_OWNER_PASSWORD`, `DB_PASSWORD`, `DB_RETENTION_PASSWORD`) e a chave HMAC do token de acesso (`ACCESS_TOKEN_SIGNING_KEY`). O `verify.sh` gera segredos efêmeros por execução quando não há `.env`.

## Pergunta 1
Quais ambientes ficam no inventário desta etapa?

A) O ambiente local (Docker Compose com o overlay de teste) como único ambiente existente; staging e produção do beta PAPER na AWS listados como pendentes, com o que falta para criá-los (recomendado)
B) Só o ambiente local, sem listar os ambientes futuros
X) Other (please specify)

[Answer]: A

## Pergunta 2
A rede e a exposição do ambiente local estão corretas?

A) Sim: todas as portas publicadas só em `127.0.0.1`, o `worker` sem porta no Compose principal, comunicação entre serviços pela rede interna do Compose. Validar isso no relatório e manter como regra para a AWS (sub-redes privadas para api, worker e banco; só o balanceador exposto) (recomendado)
B) Abrir alguma porta para a rede local (por exemplo, para testar em outro aparelho)
X) Other (please specify)

[Answer]: A

## Pergunta 3
Os segredos estão sendo injetados do jeito certo?

A) Sim: `.env` local ignorado pelo git, `.env.example` sem valores, segredos efêmeros no `verify.sh` e na CI, nenhum segredo em `NEXT_PUBLIC_*` nem em log. Na AWS, os mesmos segredos vão para o Secrets Manager, lidos por papel de execução, sem segredo de longa duração na CI (OIDC) (recomendado)
B) Mudar a forma de injetar segredos já no ambiente local
X) Other (please specify)

[Answer]: A

## Pergunta 4
E a conectividade entre contas ou VPCs?

A) Não se aplica hoje: um só ambiente local, sem fornecedor real (C-06) e sem carteira real. Na AWS, uma conta por ambiente (staging e produção) é o desenho a validar quando a conta existir (recomendado)
B) Planejar já uma conta única para staging e produção
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
