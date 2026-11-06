# Pipeline de CI — U8 Administração e feedback

A U8 acrescenta um job ao `ci.yml` e muda dois jobs existentes. As regras de sempre continuam:
- ações fixadas por SHA;
- `permissions` mínimas (`contents: read`);
- instalação a partir dos lockfiles;
- nenhum segredo de produção nos workflows;
- squash-merge em `main` só com a CI verde.

## Estágios e portões

| Estágio | Job | O que a U8 acrescenta | Portão |
|---|---|---|---|
| Backend | `backend` | Módulos `administration` e `feedback`, marcador `u8`. Testes de integração: `KillSwitchPerformanceIT`, `KillSwitchCrashIT` (3 quedas), `KillSwitchRaceIT` (ON/OFF em paralelo, 50 repetições), `ApplyLockIT` (disputa na JVM e trava presa por outra conexão), `SystemStatusIT` (cada parte lenta ou fora vira `UNKNOWN`), `AdminReadLatencyIT`, `FeedbackConcurrencyIT` (20 envios da mesma pessoa e de pessoas diferentes), `AdminAuthIT` (nonce, assinatura, carteira fora da lista, carteira de convidado, limites), `AdminGuestRoutesIT`, `AdminCorsIT`, `FeedbackPurgeIT` e `MigrationsIT` até a `0011`. ArchUnit dos dois módulos | Falha ou cobertura < 80% bloqueia |
| Frontend do convidado | `frontend` | Diálogo de feedback (estados, contador, texto mantido em erro, foco). `GuestBundleTest` depois do build: nenhuma ocorrência de `/api/v1/admin` nem `openapi-admin` em `apps/web/.next` | Falha ou cobertura < 80% bloqueia |
| App do administrador | `admin` (novo) | Instala do lockfile; roda lint, Prettier, `tsc --noEmit`, Jest com piso de 80% e `next build`; teste dos cabeçalhos de segurança (CSP com nonce) e do contraste das cores | Falha ou cobertura < 80% bloqueia |
| Contrato | `contract` | Instala o `apps/admin` a partir do lockfile (`pnpm --dir apps/admin install --frozen-lockfile`, R-06), compara o `openapi-admin.yaml` gerado com o versionado e regenera os tipos do `apps/admin`, falhando se houver diferença; inclui as rotas novas (`kill-switch`, `system-status`, `tenants`, `feedback`, `admin/auth/*`) e o `POST /api/v1/feedback` no `openapi-guest.yaml` | Diferença bloqueia |
| Smoke | `smoke` (`bash scripts/verify.sh`) | O Compose sobe o `admin`. O smoke `e2e/u8-admin-feedback` cobre: entrada do administrador; estado do sistema em até 2 s; emitir e revogar convite; ligar e desligar o kill switch, conferindo a faixa; ler um feedback enviado pelo convidado do smoke; e 403 com token de convidado. Entra no `verify.sh` por ação do responsável do projeto | Falha bloqueia |
| Segurança | `security.yml` e `.github/dependabot.yml` | O `security.yml` ganha o passo `pnpm --dir apps/admin audit --audit-level high`, ao lado do passo do `apps/web`, e a varredura SAST passa a incluir `apps/admin`. O `dependabot.yml` ganha a entrada `npm` com `directory: /apps/admin`, com a mesma espera para versões novas do `apps/web` (R-03). Sem dependência nova no backend | Crítico ou Alto bloqueia |
| End-to-end | `e2e` | O `needs` do `e2e` passa a incluir o job `admin` (R-06) | Falha bloqueia |
| Carga | `load-test.yml` | Sem cenário novo (Q6 dos requisitos) | — |

## Ação pendente do responsável do projeto: `scripts/verify.sh`

O responsável do projeto acrescenta ao `verify.sh` (Q3 e R-02):
1. **Carteira.** Gerar a carteira descartável do administrador logo depois da carteira do convidado, com a mesma ferramenta de teste, num segundo arquivo temporário fora do repositório (`ADMIN_KEY_FILE="$KEY_DIR/smoke-admin-wallet.json"`).
2. **Endereço para a `api`.** Exportar o endereço público dela como `ADMIN_WALLET_ADDRESSES` antes do `docker compose up`. O overlay de teste o repassa à `api` só nessa execução.
3. **Espera.** Incluir o `admin` na espera de serviços saudáveis, ao lado de `api` e `web`.
4. **Variáveis do Playwright.** Passar ao Playwright o caminho do arquivo como `SMOKE_ADMIN_WALLET_FILE` e a URL base do app do administrador como `ADMIN_BASE_URL=http://localhost:3001`.
5. **Varredura de logs.**
   - Estender a varredura existente, que hoje só lê os logs do `api`, aos logs do `admin` e do `worker`.
   - Procurar também o endereço completo da carteira do administrador, além do endereço do convidado.
   - Procurar o marcador de texto do feedback que o smoke envia.
6. **Limpeza.** Apagar o arquivo da chave no fim, junto com o da chave do convidado.
7. **Smoke.** Acrescentar `e2e/u8-admin-feedback` ao fim da linha do Playwright.

Até essas mudanças entrarem, a varredura de logs do `admin` não existe. As afirmações do monitoramento e do desenho de segurança sobre ela dependem desses passos.

As linhas exatas saem na geração de código, em `docs/api/u8-admin-feedback.md`. O overlay de teste passa `ADMIN_WALLET_ADDRESSES: ${ADMIN_WALLET_ADDRESSES:-}`, vazio por padrão.

## Implantação e rollback

- **Implantação:** só local, com `docker compose up -d --build`.
- **Rollback:** manual, em três passos (ver [especificação](infrastructure-specification.md#rollback-q4)).
  1. Checkout do commit anterior.
  2. `docker compose up -d --build --remove-orphans`.
  3. `bash scripts/verify.sh`.
- **Risco aceito do rollback:** um kill switch ligado continua na U5.
- **Teste automático:** `MigrationsIT` até a `0011` e o teste de que a versão anterior sobe sobre o banco migrado.

## Segredos

Nenhum segredo novo. `ADMIN_APP_ORIGIN` é configuração pública, e `ADMIN_WALLET_ADDRESSES` tem só endereços públicos. A chave da carteira de administrador do smoke vive só num arquivo temporário apagado no fim da execução, nunca no repositório nem nos logs.
