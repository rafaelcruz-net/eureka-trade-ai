# Especificação de infraestrutura — U1 `u1-legal-documents`

Conversa: português (Brasil). Implementa o design de `construction/u1-legal-documents/nfr-design/` (desempenho, segurança, escala, confiabilidade, observabilidade e componentes lógicos) no ambiente local existente, com o desenho para a nuvem. Respostas em `infrastructure-design-questions.md`.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Compute | Contêineres existentes (`api`, `web`, `admin`) no Docker Compose; nenhum serviço novo | a U1 é código no monólito e nos apps |
| Rede | A rede do Compose ganha uma faixa fixa e parametrizada: `${COMPOSE_SUBNET:-172.28.0.0/16}` (`ipam`), com gateway explícito em `.1`. `web` e `admin` recebem IPs fixos (`ipv4_address`) numa sub-faixa estreita, `${COMPOSE_APPS_SUBNET:-172.28.1.0/24}`. A lista de rede interna contém **só essa sub-faixa**, deixando de fora o gateway da ponte, de onde a API vê o tráfego das portas publicadas em `127.0.0.1` (navegador, Playwright, scripts de carga). As portas continuam só em `127.0.0.1` | isenção do limitador pelo par direto (Q1), sem isentar o tráfego externo |
| Armazenamento | Nenhum: o catálogo v2 vai dentro da imagem da API (`classpath:terms/`), como a v1 | catálogo imutável por implantação |
| Ambientes | Local (Compose e overlay de teste); nuvem só desenhada (Q3) | sem conta de nuvem |
| IaC | Compose versionado; a IaC da nuvem continua pendente no produto | igual ao restante |
| Tamanho | Sem mudança de memória (API 1 GB, web e admin 512 MB) | catálogo de poucos MB |

## Configuração nova da `api`

| Variável | Local | Nuvem (desenho) |
|---|---|---|
| `MEMETRADE_LEGAL_STORAGE_LOCATION` | vazia ("a definir") | Parameter Store |
| `MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER` | vazia | Parameter Store |
| `MEMETRADE_LEGAL_DPO_EMAIL` | vazia | Parameter Store |
| `MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE` | `false` | `true` |
| `MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS` | `${COMPOSE_APPS_SUBNET:-172.28.1.0/24}` (só a sub-faixa dos apps) | faixas das sub-redes privadas dos apps web e admin; nunca a do balanceador |
| `MEMETRADE_LEGAL_RATE_LIMIT_PER_MINUTE` | `60` (perfil `loadtest`: alto) | `60` |

Cada variável é repassada ao serviço `api` no `docker-compose.yml` com `${VAR:-}` (ou o padrão acima) e listada no `.env.example`, sem valor. A lista CIDR separada por vírgula é lida como lista, pelo binding relaxado do Spring, em `memetrade.legal.rate-limit.internal-networks`. Um teste de configuração confirma esse binding.

O limite alto do perfil `loadtest` vem de `application-loadtest.yml`, não de variável, porque o overlay de teste não repassa as variáveis `MEMETRADE_LEGAL_*`.

Nenhuma dessas variáveis é segredo, e nenhuma vai para `NEXT_PUBLIC_*`.

**Tarefas da geração de código:**
- atualizar o `ComposeFilesTest`, que valida o Compose mesclado com o bloco de `networks` e os IPs fixos;
- conferir que `infra/docker-compose.test.yml` continua compatível;
- um teste ponta a ponta: uma requisição pela porta publicada que passa do limite recebe `429` (o gateway não fica isento).

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| Cache HTTP | cdn/cache do navegador e de intermediários | `Cache-Control: public, max-age=300` e `ETag` por hash do conteúdo, só em 200 e 304 | sem serviço novo |
| Cache de dados do app | cache | 300 s por URL no servidor dos apps | erro não é guardado em cache |
| Balanceador (nuvem) | load-balancer | não entra na lista de rede interna | desenho |

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| Rede do Compose com faixa fixa e sub-faixa dos apps | U1 (introduz as faixas) | todas as Units seguintes | só a lista de rede interna do limitador de documentos usa a sub-faixa dos apps |
| `SiteFooter` e `LegalDocument` | U1 | U3, U4, U5 (telas que exibem o rodapé) | componentes de cada app |
