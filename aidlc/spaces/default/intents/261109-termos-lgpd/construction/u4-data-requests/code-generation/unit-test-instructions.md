# Instruções de teste — U4 `u4-data-requests`

Conversa: português (Brasil). Estratégia `Comprehensive`: 10 a 15 testes por componente, de unidade, de integração e de ponta a ponta. O piso é de 80% de linhas no backend, no web e no admin. Pela ordem do time, os testes das regras de dados pessoais vêm antes do código (Passo 3 do plano); nas demais camadas, cada camada é implementada e depois testada.

## Ferramentas e configuração

| Parte | Ferramenta | Configuração existente |
|---|---|---|
| Backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), JaCoCo e ArchUnit | `apps/api/pom.xml` (regra `check` em 80%) |
| Web e admin | Jest e Testing Library | `jest.config.*` dos dois apps (limite de 80%) |
| Ponta a ponta | Playwright, pelo `scripts/verify.sh` | `apps/web/playwright.config.ts` |
| Carga | Node, com `node --test` para o próprio script | `scripts/load/` |

## Comandos da Unit (só os testes desta Unit)

Rodar da raiz do repositório, sem exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` (a variável substitui a chave de teste).

```bash
# Backend: interfaces J1 a J4, pedidos, verificação, jobs, rotas, migração e retenção
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml verify -Dtest='DataRequest*Test,IdentityVerification*Test,DataSubjectRequestPurge*Test,PersonDataPort*Test,*OwnerDataPort*Test,PaperOrderCount*Test,AccessSummary*Test,PrivacyJob*Test,DataRequestsMigration*Test,RetentionConformance*Test' -Dit.test='DataRequest*IT,AdminDataRequest*IT,IdentityVerification*IT,DataSubjectRequestPurge*IT,DataRequestTimers*IT,LogScanIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true

# Web: seção "Meus dados (LGPD)" da Conta
corepack pnpm@10.18.3 --dir apps/web exec jest src/features/data-requests src/features/account --coverage=false

# Admin: telas de pedidos
corepack pnpm@10.18.3 --dir apps/admin exec jest src/features/data-requests --coverage=false

# Script de carga (só o teste do cenário)
node --test scripts/load/load-test-data-requests.test.mjs

# Ponta a ponta da Unit (com o ambiente do verify.sh já de pé)
corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/data-requests
```

Antes do Passo 3, os comandos já rodam e devolvem "nenhum teste encontrado" sem falhar.

## Metas de cobertura

80% de linhas no backend, no web e no admin, pela configuração existente. As exclusões ficam só na configuração da ferramenta.

## Mocks e dados de teste

- **Relógio:** `Clock` fixo nos serviços e nos jobs (15 dias, 24 h, 12 meses, 1 hora do limite).
- **Carteiras:** Ed25519 gerado em código de teste para assinar a mensagem de verificação, nunca uma carteira real.
- **Banco:** Testcontainers com as migrações da aplicação, sem estado compartilhado.
- **Contagens:** J2 a J4 com valores conhecidos em contas de teste.
- **Apps:** API simulada por `fetch` mockado.
- **Textos de pedido:** sintéticos, sem dado pessoal real.
