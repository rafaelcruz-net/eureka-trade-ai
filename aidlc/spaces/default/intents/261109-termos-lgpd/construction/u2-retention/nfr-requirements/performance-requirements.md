# Requisitos de desempenho — U2 `u2-retention`

Conversa: português (Brasil). Fontes: `construction/u2-retention/functional-design/` (BR2.1, BR2.5 a BR2.7), `inception/requirements-analysis/requirements.md` (NFR3, NFR7) e `nfr-requirements-questions.md` (Q1).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.1 | Cada lote de até 1000 linhas de um expurgo novo termina em até 500 ms (p95), no PostgreSQL do ambiente de teste | teste de desempenho do expurgo com 100 mil linhas vencidas (Testcontainers); a duração por lote vai no log |
| NFR3.2 | Uma execução com 100 mil linhas vencidas termina em até 5 minutos | o mesmo teste |
| NFR3.3 | O cálculo do HMAC e a checagem de `details` acrescentam no máximo 1 ms (p95) à publicação de um evento | microteste do publicador com 10 mil eventos |
| NFR3.4 | O login e a criação de conta mantêm as metas atuais (p95 de verificação ≤ 300 ms no cenário `u1`, ≤ 500 ms com convite no `u3`) | cenários de carga existentes no Build and Test |
| NFR3.5 | O expurgo de sessões encontra as sessões vencidas por índice e nunca percorre a tabela inteira a cada lote. A migração da Unit cria em `user_session` um índice de expressão sobre o fim da sessão: `coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at))`. A consulta do expurgo usa exatamente essa expressão. Os desafios já têm índice em `expires_at` | o plano de consulta (`EXPLAIN`) do teste de 100 mil linhas mostra o índice de fim de sessão, e não uma varredura sequencial |

## Orçamento

O expurgo roda fora do horário de uso (03:20 e 03:40 UTC), em lotes curtos, e cada lote é uma transação própria. Assim, um lote não segura bloqueio por mais de 500 ms.
