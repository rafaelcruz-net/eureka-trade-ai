# Especificação de infraestrutura — U1 Esqueleto de ponta a ponta

Infraestrutura da Unit `u1-walking-skeleton`, a partir de [logical-components.md](../nfr-design/logical-components.md), [security-design.md](../nfr-design/security-design.md), [reliability-design.md](../nfr-design/reliability-design.md), [scalability-design.md](../nfr-design/scalability-design.md), [performance-design.md](../nfr-design/performance-design.md) e [observability-design.md](../nfr-design/observability-design.md), dos componentes de [components.md](../../../inception/domain-design/components.md), do [functional-spec.md](../functional-design/functional-spec.md) e dos contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md). Decisões em [infrastructure-design-questions.md](infrastructure-design-questions.md) (Q1, Q2, Q6, Q8).

Não há conta de nuvem, região nem orçamento (C-10, C-12): o único ambiente é o local, no Windows, com Docker Compose. A AWS fica para a Sprint 7 e terá seu próprio Infrastructure Design.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Modelo de execução | Contêineres no Docker Compose: `postgres`, `api` (Spring Boot), `web` (Next.js) | Ambiente único local (C-10); mesmo arranjo no Windows e na CI em Linux (NFR13.2) |
| Imagens | Dockerfiles próprios em vários estágios: `api` com só o runtime Java 21 (JRE) e o JAR; `web` no modo `standalone` do Next.js sobre Node LTS enxuto; usuário sem privilégios; imagens base fixadas por digest (Q1) | Imagens pequenas, sem ferramentas de build em execução, sem root; digest impede troca silenciosa da base |
| Arquivos do Compose | `docker-compose.yml` com as imagens de produção e o perfil padrão; `docker-compose.test.yml` como sobreposição usada só pelo `scripts/verify.sh`, pelo smoke, pelo e2e e pelo teste de carga; perfil `dev` do Compose sobe só o `postgres` para a API e o app rodarem fora do contêiner com recarga rápida (Q2) | O smoke testa as mesmas imagens que seriam implantadas; a configuração de teste nunca entra no arquivo padrão |
| Rede | Rede interna do Compose; portas publicadas só em `127.0.0.1`: `web` 3000, `api` 8080 (porta da API) e 8081 (porta de gestão), `postgres` 5432 | Nada exposto na rede da máquina; `localhost` é contexto seguro para o cookie `Secure` (NFR7.2) |
| Entrada e saída | O navegador chama `web` e `api` direto, sem proxy; a API usa o endereço da conexão direta no limitador; lista de proxies confiáveis vazia | Desenho de [security-design.md](../nfr-design/security-design.md) (Q1 do NFR Design) |
| Armazenamento | Volume nomeado `pgdata` para o PostgreSQL; nenhum outro estado persistente | RPO local: o volume persiste entre reinícios (NFR10.1) |
| Ambientes | Só o local. Staging e produção do beta PAPER ficam a definir, com aprovação manual para produção (`team.md`) | C-10, C-12 |
| IaC | Docker Compose versionado no repositório; IaC de nuvem fica para a hospedagem | Não há nuvem a descrever |
| Dimensionamento | `api`: limite de 1 GB de memória e heap máximo de 512 MB; `web`: 512 MB; `postgres`: 1 GB; pool de 10 conexões | Cabe num computador de desenvolvimento com Docker Desktop; folga para 20 usuários (NFR3.3) |
| Publicação de imagens | A CI constrói, marca com o SHA do commit, usa no smoke e descarta; nada é publicado até existir um ambiente (Q6) | Sem destino de implantação ainda |

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| PostgreSQL 17 | database | Imagem oficial fixada por digest; volume `pgdata`; porta só em `127.0.0.1`; mesma versão no Testcontainers (Q8) | Migrações Liquibase aplicadas pela API na subida; usuário da aplicação sem permissão de criar banco |
| API Spring Boot | aplicação (processo de API) | Portas 8080 (API) e 8081 (gestão); `depends_on` do `postgres` saudável | Agendadores de coleta (15 s), relay (1 s) e limpezas no mesmo processo |
| App do convidado Next.js | aplicação web | Porta 3000; rota de saúde; CSP com nonce por requisição; `depends_on` da `api` saudável | Só a origem da API em `connect-src` |
| Limitador | memória do processo de API | Janela de 1 minuto, padrão 10 por IP | Redis só antes da segunda instância (NFR3.5) |
| Adaptador de mercado | componente do MarketData | Implementação simulada com semente fixa, única existente na U1 | Fontes reais entram na U4 atrás da mesma interface |
| cache, fila, busca, CDN, DNS, balanceador | — | Não usados na U1 | Sem necessidade com uma instância local |

## Perfis de execução

A mesma imagem roda em três perfis. O perfil é escolhido por variável de ambiente, nunca embutido na imagem.

| Perfil | Quem ativa | `SPRING_PROFILES_ACTIVE` | O que muda |
|---|---|---|---|
| Padrão | `docker compose up` com `docker-compose.yml` | vazio | Nenhuma conta semeada; limite de tentativas 10 por minuto |
| Teste | `scripts/verify.sh`, smoke e e2e, com `docker-compose.test.yml` | `test` | Um componente que só existe com o perfil `test` cria a conta BETA_USER do smoke na subida, a partir de `SMOKE_BETA_WALLET_ADDRESS` (endereço público da carteira de teste); limite 10 por minuto |
| Carga | Workflow `load-test`, com `docker-compose.test.yml` | `test,loadtest` | Tudo do perfil teste mais o limite de tentativas elevado (`LOGIN_RATE_LIMIT_PER_MINUTE`) e as contas dos usuários simulados |

Como a carteira de teste chega à conta semeada:

1. O `scripts/verify.sh` roda a ferramenta de teste (código de teste, com o SDK Solana no escopo de teste) que gera a carteira e grava o par de chaves num arquivo temporário fora do repositório.
2. Só o endereço público vai para `SMOKE_BETA_WALLET_ADDRESS` no ambiente da `api`.
3. A chave privada fica só com o executor do smoke e do Playwright e é apagada no fim.

Proteções:

- O arquivo padrão não define `SPRING_PROFILES_ACTIVE`, `SMOKE_BETA_WALLET_ADDRESS` nem o limite elevado.
- Um teste da CI lê o `docker-compose.yml` e falha se encontrar qualquer um deles.
- A API recusa subir com o perfil `loadtest` sem o perfil `test`.
- O componente de semente registra em log `WARN` que está ativo, com o endereço mascarado.

## Configuração e segredos

| Variável | Serviço | Origem | Observação |
|---|---|---|---|
| `POSTGRES_PASSWORD`, `DB_PASSWORD` | `postgres`, `api` | `.env` local (ignorado pelo git) | `.env.example` sem valor |
| `ACCESS_TOKEN_SIGNING_KEY` | `api` | `.env` local | Nunca em log; a CI gera um valor efêmero por execução |
| `GUEST_APP_ORIGIN` | `api` | `.env` | Origem permitida no CORS |
| `API_ORIGIN` | `web` | `.env` | Usada só no servidor do Next.js para montar a CSP; não é segredo |
| `TRUSTED_PROXIES` | `api` | `.env` | Vazia no ambiente local |
| `ADMIN_WALLET_ADDRESSES` | `api` | `.env` | Endereços públicos, não são segredo (BR1.5) |
| `SPRING_PROFILES_ACTIVE` | `api` | Só `docker-compose.test.yml` | `test` ou `test,loadtest`; ausente no arquivo padrão |
| `SMOKE_BETA_WALLET_ADDRESS` | `api` | Gerada pelo `scripts/verify.sh` a cada execução | Endereço público; só com o perfil `test` |
| `LOGIN_RATE_LIMIT_PER_MINUTE` | `api` | Perfil da aplicação; elevado só no perfil `loadtest` | Padrão 10 |

Nenhum segredo vai em variável `NEXT_PUBLIC_*` nem no código entregue ao navegador.

## Verificações de saúde

| Serviço | Comando | Intervalo | Timeout | Tentativas | Início |
|---|---|---|---|---|---|
| `postgres` | `pg_isready -U $POSTGRES_USER` | 5 s | 3 s | 10 | 10 s |
| `api` | Sonda Java pequena empacotada no JAR (`java -cp app.jar ...HealthProbe`), que chama `http://127.0.0.1:8081/actuator/health/readiness` e sai com 0 só em HTTP 200; a imagem enxuta não tem `curl` nem `wget` | 10 s | 5 s | 6 | 60 s |
| `web` | `node -e` com `fetch` em `http://127.0.0.1:3000/api/health`, saindo com 0 só em HTTP 200 | 10 s | 3 s | 6 | 20 s |

A saúde de prontidão da API fica `DOWN` (HTTP 503) só quando o banco está indisponível ou as migrações não foram aplicadas. Relay atrasado ou fonte sem coletar viram um estado degradado que responde HTTP 200, aparece no corpo da saúde e nas métricas e não marca o contêiner como não saudável. O smoke confere esse estado à parte.

## Ordem de subida

1. `postgres` fica saudável.
2. `api` aplica as migrações, conecta ao banco e responde pronta na porta de gestão.
3. `web` sobe depois da `api` saudável.
4. `scripts/verify.sh` gera a carteira de teste, sobe com `docker-compose.test.yml`, espera os três saudáveis e a primeira coleta (até 60 s), roda o smoke e devolve código diferente de zero em qualquer falha (NFR13.1).

## Logs dos contêineres

Driver `json-file` em todos os serviços, com `max-size: 10m` e `max-file: 3`. O driver limita por tamanho e número de arquivos, não por idade.

## Pré-requisitos antes de um ambiente compartilhado (G1)

| Item | Origem |
|---|---|
| TLS 1.2 ou superior em toda conexão externa | NFR7.2 |
| Criptografia em repouso do banco e dos backups, com restauração testada | NFR7.2, [reliability-requirements.md](../nfr-requirements/reliability-requirements.md) |
| Redis para o limitador antes da segunda instância | NFR3.5 |
| Executor único da coleta com mais de uma instância | [scalability-design.md](../nfr-design/scalability-design.md) |
| Lista de proxies confiáveis do balanceador | [security-design.md](../nfr-design/security-design.md) |
| SLO de disponibilidade e alertas com notificação | NFR10.1 |
| Retenção de logs por idade num agregador | NFR9.3 |
| Varredura dinâmica, varredura de imagens e SBOM por release (a definir) | `team.md` |
