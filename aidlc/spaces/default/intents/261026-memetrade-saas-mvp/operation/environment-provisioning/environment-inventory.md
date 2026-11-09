# Inventário de ambientes

Conversa: português (Brasil). Decisão da pessoa (2026-11-08): inventariar e validar o ambiente local e registrar a AWS como pendente. Respostas 1A a 4A de `environment-provisioning-questions.md`. Nenhum recurso de nuvem foi criado.

Fontes: a especificação de infraestrutura de cada Unit (`construction/u1-walking-skeleton/infrastructure-design/infrastructure-specification.md` até `construction/u9-dashboard/infrastructure-design/infrastructure-specification.md`), que define os serviços, as portas, os limites de memória e o desenho da AWS listados abaixo, e `operation/deployment-pipeline/cd-config.md` para o fluxo de implantação.

## Ambientes

| Ambiente | Situação | Onde | Como sobe | Smoke |
|---|---|---|---|---|
| Local | existe | Windows do responsável do projeto, Docker Desktop com Compose v2 | `docker compose up -d --build` (dev) ou `bash scripts/verify.sh` (com o overlay `infra/docker-compose.test.yml`) | `bash scripts/verify.sh`: 10 testes Playwright |
| CI (efêmero) | existe | GitHub Actions, `ubuntu-24.04` | jobs `smoke` e `e2e` do `ci.yml` sobem o mesmo Compose com `IMAGE_TAG=${{ github.sha }}` | o mesmo `verify.sh` |
| Staging (AWS) | **pendente** | conta e região a definir (C-10, C-12) | workflow `deploy` desenhado em `operation/deployment-pipeline/cd-config.md` | smokes Playwright apontados para o staging |
| Produção do beta PAPER (AWS) | **pendente** | conta própria, separada do staging | workflow `deploy` com o ambiente `production` do GitHub | smokes em produção |

## Ambiente local: serviços

| Serviço | Imagem | Memória | Porta publicada | Papel |
|---|---|---|---|---|
| `postgres` | `postgres:17-alpine` fixada por digest | 1 GB | `127.0.0.1:5432` | banco, volume `pgdata` |
| `db-bootstrap` | `postgres:17-alpine` | — | — | cria papéis e privilégios (`infra/postgres/bootstrap`) e termina |
| `db-migrate` | `memetrade-api:${IMAGE_TAG}` | — | — | aplica as migrações Liquibase (só expand) e termina |
| `api` | `memetrade-api:${IMAGE_TAG}` | 1 GB | `127.0.0.1:8080` (API), `127.0.0.1:8081` (gestão) | API REST e canal ao vivo |
| `worker` | `memetrade-api:${IMAGE_TAG}` | 1 GB | nenhuma no Compose principal; `127.0.0.1:8082` só no overlay de teste | ciclo dos bots, tarefas agendadas por papel do processo |
| `web` | `memetrade-web:${IMAGE_TAG}` | 512 MB | `127.0.0.1:3000` | app do convidado (Next.js) |
| `admin` | `memetrade-admin:${IMAGE_TAG}` | 512 MB | `127.0.0.1:3001` | app do administrador (Next.js) |

Rede: a rede padrão do Compose (`memetrade_default`); os serviços conversam pelo nome do serviço. Nenhuma porta é publicada fora de `127.0.0.1`.

## Segredos e configuração

| Item | Onde fica hoje | Onde fica na AWS (desenho) |
|---|---|---|
| `POSTGRES_PASSWORD`, `DB_OWNER_PASSWORD`, `DB_PASSWORD`, `DB_RETENTION_PASSWORD` | `.env` local, ignorado pelo git (`.gitignore:30`); gerados por execução no `verify.sh` e na CI | Secrets Manager, lidos pelo papel de execução de cada serviço; rotação |
| `ACCESS_TOKEN_SIGNING_KEY` (HMAC do token de 15 min) | `.env` local; gerada por execução no `verify.sh` | Secrets Manager |
| Origens, carteiras públicas de administradores, parâmetros da U4 e da U8 | `.env` local e `.env.example` (não são segredos) | Parameter Store |
| Credenciais da CI | nenhuma (CI não usa segredo) | OIDC do GitHub para um papel de implantação; nenhum segredo de longa duração |

Regras que continuam valendo: nenhum segredo em `NEXT_PUBLIC_*` nem em código entregue ao navegador (os apps leem a origem da API no servidor, em tempo de execução); nenhum segredo, token, nonce ou assinatura em log (`project.md`).

## O que falta para staging e produção

| Item | Origem | Estágio dono |
|---|---|---|
| Conta de nuvem (uma por ambiente), região e orçamento | C-10, C-12, `team.md` → Deployment | decisão da pessoa; depois Environment Provisioning de novo |
| Ferramenta de IaC e as pilhas (rede, dados, computação) | `team.md` (Sprint 7) | Infrastructure Design / Environment Provisioning |
| Registro de imagens (por exemplo, ECR) | `cd-config.md`; resposta 7A do Deployment Pipeline | Environment Provisioning |
| Banco gerenciado com réplica de leitura | decisão da pessoa para a escala da U4 (U4-NFR3.5) | Environment Provisioning; nova medição da carga `growth` antes do G1 |
| Ambiente `production` no GitHub com o responsável do projeto como revisor obrigatório | resposta 4A do Deployment Pipeline | pessoa (configuração do GitHub) |
| Execução do `perf-audit.sh` da U2 com memória suficiente | pendência aceita no Build and Test | pessoa / ambiente na nuvem |
| DAST, varredura de imagens e SBOM por release | `team.md` (controles antes do G1) | Deployment Pipeline futuro |
