# Decisões de tecnologia — U2 `u2-retention`

Conversa: português (Brasil). A U2 não traz tecnologia nova: usa a pilha registrada em `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`.

## Decisões

| Área | Escolha | Justificativa |
|---|---|---|
| HMAC | `javax.crypto.Mac` com `HmacSHA256`, do Java 21 | nativo, sem dependência nova |
| Agendamento | `@Scheduled` com cron em UTC e ShedLock, no processo da API | padrão dos expurgos existentes (`FeedbackPurgeJob`, jobs da auditoria) |
| Persistência | PostgreSQL. A migração Liquibase só acrescenta: coluna `wallet_hmac` com índice em `audit_event`, tabela de execuções do Identity e índice de expressão do fim de sessão em `user_session` | padrão expand-contract do time; NFR3.5 |
| Métricas | Micrometer e Prometheus, já configurados | NFR8.1 |
| Testes | JUnit 5, Testcontainers (PostgreSQL), relógio injetável e JaCoCo | postura de testes do time |
| Módulo novo | pacote `com.memetrade.privacy`. A interface de declaração dos expurgos fica em `com.memetrade.shared.retention` | BR2.17, sem ciclo |

## Alternativas rejeitadas

- **Biblioteca de HMAC de terceiros:** o Java já oferece HMAC, e uma biblioteca só aumentaria a cadeia de suprimentos.
- **Papel de banco próprio de retenção para o Identity:** as tabelas do Identity não são append-only, então o gatilho de proteção não acrescenta nada (Q2).
- **Último sucesso em memória:** daria saúde diferente em cada instância (BR2.16).
