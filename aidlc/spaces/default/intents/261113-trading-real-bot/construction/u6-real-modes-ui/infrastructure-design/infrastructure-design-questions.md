# Perguntas de Infrastructure Design — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Base:
- `construction/u6-real-modes-ui/nfr-design/`: componentes, canal no layout raiz, adendo do servidor;
- `construction/u5-real-operations/infrastructure-design/`;
- `docker-compose.yml`, `infra/docker-compose.real.yml`, `scripts/verify.sh` e `.github/workflows/ci.yml` atuais;
- `team.md` (Deployment).

## Q1. [Nuvem e local] O que muda?

A. Nenhum serviço, segredo, variável ou porta nova. Também não muda o Compose nem o Terraform.

O web e o admin continuam com as imagens atuais. A API ganha só a rota de leitura, o `serverTime` e o `PATCH` no CORS do convidado. A origem do web, que já está na lista do CORS, continua a mesma.
B. Um serviço de front separado para o REAL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Ponta a ponta] Como entra o e2e novo?

A. O spec `apps/web/e2e/real-ui/` roda no `verify.sh`, depois de `e2e/real-ops`, no mesmo validador local e com o mesmo Compose do REAL. Ele cobre:
- a ativação pela tela;
- a troca de modo;
- a assinatura de uma ordem pela faixa;
- as telas REAL do administrador.

A carteira de teste ganha `signTransaction` só no código de teste. A linha do `verify.sh` vai pronta para você.
B. Rodar o spec fora do `verify.sh`
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [CI] O que muda?

A. Nada no `ci.yml`. Os jobs `frontend`, `admin` e `contract` já rodam lint, tipos, Jest com o piso e `check:api`, e o `smoke` roda o `verify.sh`.

A única mudança é a linha do spec no `verify.sh`, com o texto pronto para você.
B. Um job novo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Implantação e rollback] Em que ordem?

A. A ordem:
1. API, com as mudanças só de acréscimo no contrato;
2. web e admin.

Não há migração. O rollback volta as imagens na ordem inversa:
1. web e admin;
2. API.

O saque continua disponível o tempo todo, inclusive na tela mínima antiga.
B. Tudo ao mesmo tempo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Nenhum serviço, segredo, variável ou mudança de Compose e Terraform; a API ganha só a leitura, o `serverTime` e o `PATCH` [Q1].
- Spec novo `e2e/real-ui` no `verify.sh`, depois de `e2e/real-ops`, com a carteira de teste assinando transações [Q2].
- `ci.yml` sem mudança; só a linha do spec no `verify.sh`, com texto pronto para você [Q3].
- Implantação: API primeiro, depois web e admin; rollback na ordem inversa; o saque sempre disponível [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
