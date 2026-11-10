# Decisões de tecnologia — U5 `u5-anonymization`

Conversa: português (Brasil). A U5 não traz tecnologia nova; usa a pilha de `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`.

## Decisões

| Área | Escolha | Justificativa |
|---|---|---|
| Transação | `@Transactional` aberta pelo Privacy; as interfaces J1 a J3 usam `Propagation.MANDATORY` | transação única (FR6.2) |
| Travas | `SELECT ... FOR UPDATE` na ordem bots (`LockOrder`), sessões e conta, com o `lock_timeout` de 2 s de `LockOrder` | concorrência (BR5.6) |
| Feedback | função PL/pgSQL `SECURITY DEFINER` com `search_path` fixo | o gatilho de retenção fica intacto |
| Migração | Liquibase `0015-anonymization.sql`, só aditiva ou relaxando, com o índice `login_challenge_wallet_idx` | expand-contract; NFR3.2 |
| Admin | diálogo acessível em React, sem biblioteca nova | regra do time |
| Testes | JUnit 5, Testcontainers e Jest | postura do time |

## Alternativas rejeitadas

- **Configuração de sessão (GUC) para liberar o apagar do feedback:** qualquer conexão poderia ligá-la.
- **Apagar a linha da carteira:** ela é referenciada pela conta e simplifica menos do que parece.
