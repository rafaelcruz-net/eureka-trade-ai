# Perguntas do desenho de infraestrutura — U8 Administração e feedback

Unit `u8-admin-feedback`. Base: [NFR Design](../nfr-design/) e a infraestrutura local atual: Docker Compose com `postgres`, `db-bootstrap`, `db-migrate`, `api`, `worker` e `web`, o overlay `infra/docker-compose.test.yml` e os workflows `ci.yml`, `security.yml` e `load-test.yml`.

Já decidido sem pergunta:
- A migração da U8 é a `0011-admin-feedback.sql`, só de acréscimo.
- A conexão dedicada da trava entra na conta, que passa a 42 de 100 conexões.
- A `AdminCorsPolicy` aceita credenciais em `verify`, `refresh` e `logout` (R-08 da revisão do NFR Design).
- `ADMIN_APP_ORIGIN` entra no `.env.example`.
- Nenhum segredo novo.

## Q1. [Implantação] Como o app do administrador entra no Docker Compose?

A. Serviço `admin` com imagem própria (`memetrade-admin:local`), feita por um Dockerfile em `apps/admin` igual ao do `web` (build em etapas, usuário sem privilégio, saída standalone):
   - porta `127.0.0.1:3001:3000`;
   - depende do `api` saudável;
   - healthcheck HTTP;
   - só variáveis públicas (a URL da API);
   - nenhum segredo e nenhum acesso ao banco.
B. Servir o app do administrador pelo mesmo contêiner do `web`, em outra porta.
X. Other (please specify)

[Answer]: A

## Q2. [CI] Como a CI cobre o `apps/admin`?

A. Novo job `admin` no `ci.yml`, igual ao `frontend`:
   - instala do lockfile, lint, formato, tipos, testes com piso de 80% e build;
   - o job `contract` também compara os tipos gerados de `openapi-admin.yaml`;
   - o job `frontend` ganha o `GuestBundleTest` (nenhuma rota `/api/v1/admin` no pacote do convidado);
   - ações fixadas por SHA e `permissions` só de leitura.
B. Acrescentar o `apps/admin` como mais um passo dentro do job `frontend`.
X. Other (please specify)

[Answer]: A

## Q3. [Teste] Carteira do administrador no smoke

O `verify.sh` gera uma carteira descartável para o convidado do smoke, num arquivo temporário fora do repositório, e passa só o endereço público para a API. O smoke da U8 precisa de outra carteira, que esteja em `ADMIN_WALLET_ADDRESSES`.

A. O `verify.sh` gera também uma carteira de administrador descartável, do mesmo jeito. Ela é passada assim:
   - o endereço vai em `ADMIN_WALLET_ADDRESSES` só para essa execução, pelo overlay de teste;
   - o caminho do arquivo da chave vai para o Playwright em `SMOKE_ADMIN_WALLET_FILE`;
   - o arquivo é apagado no fim, como a chave do convidado.

   O `verify.sh` é seu, então eu entrego as linhas exatas para você acrescentar, junto com `e2e/u8-admin-feedback` na linha do Playwright.
B. Uma chave fixa de teste escrita no código de teste, com o endereço fixo no overlay de teste.
X. Other (please specify)

[Answer]: A

## Q4. [Implantação] Rollback da U8

A. Rollback manual no ponto de controle, como nas outras Units:
   1. Checkout do commit anterior.
   2. `docker compose up -d --build --remove-orphans`, que remove o contêiner `admin` órfão.
   3. `bash scripts/verify.sh`.

   As tabelas da `0011` ficam no banco sem uso. Um kill switch ligado fica registrado só na U5 (`SystemPauseState`), e quem desliga é o administrador quando a U8 volta. O desenho deixa esse risco escrito.
B. Igual à A, e o rollback também desliga o kill switch antes de voltar a versão.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

[Answer]: Looks correct
