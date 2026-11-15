# Decisões de tecnologia — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`, o módulo `riskcontrol` atual, `team.md` (Testing Posture, Code Style) e `nfr-requirements-questions.md` (Q6 a Q8).

| ID | Decisão | Motivo |
|---|---|---|
| U3-NFR8.4 | Pacote `riskcontrol.real` (`domain`, `infra`); a M5 em `riskcontrol.api` | a barreira REAL é parte da RiskControl, separada da PAPER por pacote e por tabelas |
| U3-NFR8.5 | Trava com `pg_try_advisory_xact_lock` pelo `JdbcTemplate`, repetida a cada 20 ms por até 1 s; sem `SET lock_timeout` | o projeto já usa JDBC no PostgreSQL; a trava acaba com a transação; a sessão do ciclo não muda de configuração |
| U3-NFR8.6 | Tabelas `real_risk_evaluation`, `real_risk_reservation`, `real_risk_day` e `real_blocked_token` por migração Liquibase, na sequência do `db.changelog-master.yaml` | padrão do projeto |
| U3-NFR8.7 | Trabalhos com o ShedLock que já existe; a auditoria pela saída que já existe, com propagação MANDATORY, como a barreira PAPER | padrão do projeto |
| U3-NFR8.8 | Testes antes do código a partir dos cenários S1 a S17; JUnit 5, AssertJ, Testcontainers (PostgreSQL) e relógio injetável; piso de 80% | núcleo financeiro (`team.md`) |
| U3-NFR8.9 | Testes obrigatórios por risco tocados pela U3: limites, pausa, `unknown` nunca zero, separação REAL/PAPER, isolamento entre tenants e deduplicação | `team.md` |
| U3-NFR10.1 | Lamports e unidades base em `long`; preços em `BigDecimal`; percentuais em pontos-base; arredondamento para baixo | NFR10, `project.md` |

**Nenhuma biblioteca nova.**
