# Instruções de teste — U5 `u5-anonymization`

Conversa: português (Brasil). Estratégia `Comprehensive`: 10 a 15 testes por componente, de unidade, de integração e de ponta a ponta. O piso é de 80% de linhas no backend e no admin. Pela ordem do time, os testes da anonimização (lógica de dados pessoais) vêm antes do código (Passo 2 do plano); nas demais camadas, cada camada é implementada e depois testada.

## Ferramentas e configuração

| Parte | Ferramenta | Configuração existente |
|---|---|---|
| Backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), JaCoCo e ArchUnit | `apps/api/pom.xml` (regra `check` em 80%) |
| Admin | Jest e Testing Library | `apps/admin/jest.config.*` (limite de 80%) |
| Ponta a ponta | Playwright, pelo `scripts/verify.sh` | `apps/web/playwright.config.ts` |

## Comandos da Unit (só os testes desta Unit)

Rodar da raiz do repositório, sem exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` (a variável substitui a chave de teste).

```bash
# Backend: anonimização, interfaces J1 a J3, login e renovação, leitores de carteira, migração e papéis
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml verify -Dtest='Anonymiz*Test,BotAnonymizeStop*Test,*WalletReader*Test,AccountDetails*Test,IdentityDirectory*Test' -Dit.test='Anonymiz*IT,AdminAnonymization*IT,FeedbackEraseFunction*IT,MigrationsBackwardCompatIT,U8PrivilegesIT,RolesBootstrapIT,LogScanIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true

# Admin: diálogo T8 e listagem de contas
corepack pnpm@10.18.3 --dir apps/admin exec jest src/features/data-requests src/features/accounts --coverage=false

# Ponta a ponta da Unit (com o ambiente do verify.sh já de pé e SMOKE_ANONYMIZATION_INVITE_CODE exportado)
corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/anonymization
```

Antes do Passo 2, os comandos já rodam e devolvem "nenhum teste encontrado" sem falhar.

## Metas de cobertura

80% de linhas no backend e no admin, pela configuração existente. As exclusões ficam só na configuração da ferramenta.

## Mocks e dados de teste

- **Relógio:** `Clock` fixo no serviço e nas interfaces.
- **Carteiras:** Ed25519 gerado em código de teste, nunca uma carteira real; o teste obrigatório procura exatamente esse endereço.
- **Banco:** Testcontainers com as migrações da aplicação e o preparo de papéis, sem estado compartilhado.
- **Falhas injetadas:** um bean de teste que lança exceção em cada um dos cinco pontos da transação.
- **Concorrência:** pontos de pausa de teste (`CountDownLatch`) no login e na renovação, nunca `sleep` como sincronização.
- **Apps:** API simulada por `fetch` mockado.
- **Feedback e bots:** textos e nomes sintéticos, sem dado pessoal real.
