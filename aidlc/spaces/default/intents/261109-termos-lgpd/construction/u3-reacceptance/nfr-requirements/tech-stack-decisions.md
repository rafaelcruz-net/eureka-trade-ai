# Decisões de tecnologia — U3 `u3-reacceptance`

Conversa: português (Brasil). A U3 não traz tecnologia nova; usa a pilha de `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`.

## Decisões

| Área | Escolha | Justificativa |
|---|---|---|
| Trava | Filtro de servlet no módulo Identity, com a constante nova `FilterOrder.TERMS_REACCEPTANCE` = +16: depois de `AUTHENTICATION` (+10) e de `ADMIN_ROUTE` (+15), antes de `RATE_LIMIT` (+17). Precedência das respostas ao convidado: 401 sem sessão, depois 403 de papel numa rota de administrador, depois 403 `TERMS_REACCEPTANCE_REQUIRED` e por fim 429 | um convidado desatualizado não consome o limite das rotas bloqueadas |
| Idempotência | Mecanismo comum do Identity | correção registrada no projeto |
| Persistência | PostgreSQL, migração Liquibase `0013` só aditiva (ou relaxando) | expand-contract |
| App | Next.js e TypeScript estrito; o cliente da API gerado do contrato trata o `403` num lugar só | tipos gerados, nunca escritos à mão |
| Testes | JUnit 5, Testcontainers, Jest e Playwright | postura do time |

## Alternativas rejeitadas

- **Versão aceita no token:** exigiria renovar a sessão depois do aceite (L3).
- **Cache da versão aceita em memória:** daria decisões diferentes entre instâncias logo depois do aceite.
