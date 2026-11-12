# Instruções de build

Conversa: português (Brasil). Cobre o monorepo inteiro depois das Units U1 a U5 da intent `261109-termos-lgpd` (termos v2, retenção, novo aceite, pedidos do titular e anonimização), sobre a base da intent `261026-memetrade-saas-mvp`.

## Pré-requisitos

| Item | Versão ou valor | Onde está fixado |
|---|---|---|
| Java | 21 (Temurin) | `apps/api/pom.xml`, `.github/workflows/ci.yml` |
| Maven | wrapper `apps/api/mvnw` | `apps/api/.mvn/wrapper/` |
| Node | LTS fixado no projeto | `.nvmrc` |
| pnpm | 10.18.3 por `corepack` | campo `packageManager` |
| Docker | Docker Desktop com Compose v2 | `docker-compose.yml`, `infra/docker-compose.test.yml` |
| Variáveis | `.env` local copiado de `.env.example`, sem valores reais no repositório | `.env.example` |
| Chave HMAC da trilha (U2) | `MEMETRADE_AUDIT_WALLET_HMAC_KEY`, 32 bytes ou mais, só no `.env` local ou no ambiente do comando | `docker-compose.yml` exige a variável (`:?`) |

No Windows, os scripts rodam no Git Bash.

**Atenção:** não exporte `MEMETRADE_AUDIT_WALLET_HMAC_KEY` ao rodar o Maven. A variável substitui a chave de teste do perfil `test` e quebra os testes de integração. Ela é necessária só para subir o Compose (`verify.sh`, `load-test.sh`).

## Backend (`apps/api`)

```bash
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml clean verify
```

- `verify` roda `spotless:check`, os testes de unidade (Surefire), os de integração (Failsafe, PostgreSQL real por Testcontainers, com as migrações `0001` a `0015` e o preparo de papéis `infra/postgres/bootstrap/10-roles.sql`), as regras ArchUnit e a regra `check` do JaCoCo (80% de linhas no relatório mesclado).
- O contrato OpenAPI é conferido por `GuestOpenApiContractTest`, `AdminOpenApiContractTest` e `U8OpenApiContractTest`.

## Frontend (`apps/web` e `apps/admin`)

```bash
corepack pnpm@10.18.3 --dir apps/web install --frozen-lockfile
corepack pnpm@10.18.3 --dir apps/web run lint
corepack pnpm@10.18.3 --dir apps/web run format:check
corepack pnpm@10.18.3 --dir apps/web run typecheck
corepack pnpm@10.18.3 --dir apps/web run check:api
corepack pnpm@10.18.3 --dir apps/web run test:coverage
```

Os mesmos comandos valem para `apps/admin`, trocando `--dir`. O limite de cobertura do Jest falha o comando abaixo de 80%.

## Ambiente completo e verificação de ponta a ponta

`bash scripts/verify.sh` é o comando de verificação autorizado. Ele sobe `docker-compose.yml` com `infra/docker-compose.test.yml`, espera os serviços ficarem saudáveis, roda os smokes Playwright, varre os logs atrás de endereço completo de carteira e devolve código diferente de zero em qualquer falha. O arquivo é protegido: só o responsável do projeto o altera, e só ele o roda diretamente (`! bash scripts/verify.sh`). O AI-DLC o roda pelo ponto de controle de cada Unit.

### Pré-requisitos do responsável no `verify.sh` (aplicados em 2026-11-11, commit `5d30541`)

1. `MEMETRADE_AUDIT_WALLET_HMAC_KEY` efêmera exportada com ou sem `.env` (U2).
2. `SMOKE_ANONYMIZATION_INVITE_CODE` gerado, exportado e passado ao Playwright, e incluído na varredura de logs (U5).
3. A lista de specs termina em `... e2e/legal-documents e2e/reacceptance e2e/data-requests e2e/anonymization`: os 19 specs rodam.

## Troubleshooting

- **ITs falhando com erro de assinatura HMAC:** a variável `MEMETRADE_AUDIT_WALLET_HMAC_KEY` está exportada no shell. Rode com `env -u MEMETRADE_AUDIT_WALLET_HMAC_KEY`.
- **`docker compose` recusa subir com "MEMETRADE_AUDIT_WALLET_HMAC_KEY is required":** gere uma chave efêmera no ambiente do comando: `export MEMETRADE_AUDIT_WALLET_HMAC_KEY=$(openssl rand -hex 32)`.
- **`LogScanIT` rodado sozinho falha em cascata:** é a falha conhecida do teste da U3 desta intent (o MockMvc imprime cabeçalhos com token na saída capturada). Na suíte completa a classe passa.
- **`DashboardOpenLatencyIT` com p95 entre 2,0 s e 2,3 s:** instabilidade ambiental conhecida da U9 do intent anterior, também com a trava da U3 desligada. O limite não é alterado; ver `test-results.md`.
