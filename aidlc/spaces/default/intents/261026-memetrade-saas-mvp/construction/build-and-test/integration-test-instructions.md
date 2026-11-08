# Instruções de testes de integração

Conversa: português (Brasil). A Test Strategy é Comprehensive.

## O que cobre

- **Backend:** testes `*IT` no Failsafe, contra o PostgreSQL 16 real em contêiner (Testcontainers), com as mesmas migrações Liquibase da aplicação. Eles cobrem as interações entre módulos por interface pública e por evento:
  - identidade → auditoria (U1/U3 → U2);
  - descoberta → bots e decisão (U4 → U5);
  - decisão → barreira (U5 → U6);
  - barreira → execução PAPER e ledger (U6 → U7);
  - ledger → painel (U7 → U9);
  - administração → identidade e auditoria (U8 → U3/U2).
- **Contratos:** testes de contrato OpenAPI (Guest, Admin, U5, U9) e AsyncAPI dos eventos; os tipos do frontend são gerados do OpenAPI (`schema.d.ts`), e uma divergência falha o build.
- **Ponta a ponta:** smokes Playwright em `apps/web/e2e/<unit>` sobre o ambiente Compose completo, rodados por `scripts/verify.sh`.
- **Testes obrigatórios por risco** (`team.md`): isolamento entre tenants (403/404, nunca o dado), separação REAL/PAPER, deduplicação por `Idempotency-Key`, violação de limites, `unknown` nunca zero, pausa do bot, ledger append-only (UPDATE/DELETE falham), P&L determinístico em decimal, REAL desligado por padrão, nonce reutilizado e assinatura expirada rejeitados.

## Como rodar

```bash
# suíte inteira (unidade + integração)
apps/api/mvnw -f apps/api/pom.xml verify
# só uma Unit (tags JUnit por Unit)
apps/api/mvnw -f apps/api/pom.xml verify -Dgroups=u9
# um teste
apps/api/mvnw -f apps/api/pom.xml verify -Dit.test=JdbcAuditEventReaderIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
# ponta a ponta (comando autorizado)
bash scripts/verify.sh
```

Os comandos por Unit estão em `construction/<unit>/code-generation/unit-test-instructions.md`.

## Dados e ambiente

- Relógio injetável (`MutableClock`), seeds fixas e nenhuma rede real. Os adaptadores de mercado usam o simulador e fixtures gravadas.
- As carteiras de teste são geradas no código de teste. Nenhuma fixture usa carteira, mint ou dado pessoal real.
- Os testes não podem depender de estado compartilhado nem da ordem de execução (`team.md`). As medições da U9 rodam num banco `u9_measure_*` próprio (`AbstractU9MeasurementIT`).

## Metas

- Cobertura de linhas ≥ 80% no backend (JaCoCo mesclado) e em cada app do frontend (Jest).
- Nenhuma falha e nenhum teste instável ignorado: teste instável é defeito.

## Resultado desta execução (2026-11-07)

Failsafe com 754 testes, 0 falhas e 0 ignorados.

- `JdbcAuditEventReaderIT` (U2) agora roda em banco próprio (volta 1) e passa.
- `DashboardOpenLatencyIT` (U9) passou na suíte completa. Rodando sozinho, já ficou acima de 2 s, e isso fica como observação em `test-results.md`.
- `scripts/verify.sh` teve saída 0 com 8 testes Playwright.

Na primeira execução (2026-11-06), esses dois testes falharam. Os detalhes estão em `test-results.md`.
