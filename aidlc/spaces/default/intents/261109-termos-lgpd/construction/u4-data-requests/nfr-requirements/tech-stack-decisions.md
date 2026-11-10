# Decisões de tecnologia — U4 `u4-data-requests`

Conversa: português (Brasil). A U4 não traz tecnologia nova; usa a pilha de `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`.

## Decisões

| Área | Escolha | Justificativa |
|---|---|---|
| Módulo | `com.memetrade.privacy` (domínio, infra, web), criado na U2 | dono dos pedidos (components.md) |
| Persistência | PostgreSQL, migração Liquibase `0014` só aditiva, com as tabelas `data_subject_request`, `data_subject_verification` e `privacy_job_run` e os índices | expand-contract |
| Assinatura | Ed25519 nativo do Java pela J1 do Identity, sem SDK Solana em produção | regra do projeto |
| Jobs | `@Scheduled` com ShedLock só no processo da API, no padrão dos jobs da U2 | consistência |
| Apps | Next.js e TypeScript estrito, tipos gerados dos contratos P1 e P2 | regra do time |
| Testes | JUnit 5, Testcontainers, Jest e Playwright | postura do time |

## Alternativas rejeitadas

- **Fila para o aviso de vencimento:** o job de 15 minutos basta para um prazo de dias.
- **Cifrar o texto na aplicação:** o banco já guarda os dados da conta com a mesma proteção; a cifra complicaria o expurgo sem ganho real no beta (Q1 do Functional Design).
