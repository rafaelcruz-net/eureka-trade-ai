# Instruções de teste — U2 `u2-retention`

Conversa: português (Brasil). Estratégia `Comprehensive`: 10 a 15 testes por componente, de unidade e de integração, e mais o teste de desempenho do expurgo. O piso é de 80% de linhas no backend. Pela ordem do time, os testes dos expurgos e da minimização da carteira vêm antes do código (Passos 2 e 5 do plano). Nas demais camadas, cada camada é implementada e depois testada.

## Ferramentas e configuração

| Parte | Ferramenta | Configuração existente |
|---|---|---|
| Backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), JaCoCo e ArchUnit | `apps/api/pom.xml` (regra `check` em 80%) |
| Ponta a ponta | Playwright pelo `scripts/verify.sh` | os specs existentes continuam; a U2 não tem tela |

## Comandos da Unit (só os testes desta Unit)

Rodar da raiz do repositório.

```bash
# Backend: expurgos, saúde, recuperação, HMAC, validador, relay, catálogo, conformidade, Compose e migração
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml verify -Dtest='LoginChallengePurge*Test,SessionPurge*Test,PurgeHealthIndicator*Test,IdentityJob*Test,WalletHasher*Test,AuditEventValidator*Test,AuditPayloadCodec*Test,RetentionCatalog*Test,RetentionTableSource*Test,RetentionConformance*Test,RetentionEnforcer*Test,RetentionMigration*Test,ComposeFilesTest' -Dit.test='LoginChallengePurge*IT,SessionPurge*IT,IdentityJob*IT,WalletAudit*IT,AuditOutboxRelay*IT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

Antes do Passo 2, o comando já roda (o Maven e os filtros existem) e devolve "nenhum teste encontrado" sem falhar.

## Metas de cobertura

80% de linhas no backend, medido pelo `mvnw verify` completo (JaCoCo). As exclusões ficam só na configuração da ferramenta. Os arquivos novos devem passar de 80%.

## Mocks e dados de teste

- **Relógio:** `Clock` fixo e injetável nos expurgos e nos indicadores (24 h, 30 dias, 48 h).
- **Chave HMAC:** uma chave fixa de teste em `application-test.yml`, nunca uma chave real.
- **Carteiras:** endereços gerados no código de teste (Ed25519 de teste), nunca uma carteira real.
- **Banco:** Testcontainers com as migrações da aplicação, sem estado compartilhado entre testes.
- **Desempenho:** 100 mil sessões e desafios gerados por SQL no teste, com `EXPLAIN` da consulta do expurgo.
