# Perguntas do Infrastructure Design — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes:
- `construction/u4-own-quantity-cycle/nfr-design/`:
  - migrações `0026` (resultados do ciclo) e `0027` (índice da fila);
  - flag em `api_environment` e `worker_environment`;
  - contadores, alarme por razão (F2) e alarme do bot preso (F3);
  - custo de cerca de US$ 1,90 por mês;
  - `hasSize(16)`.
- `construction/u2-real-bot-pnl/infrastructure-design/` (formato e alarme da U2).
- `team.md`, seção Deployment (flags como variáveis da tarefa, implantação separada).

## Q1. Migrações e flag

Como entram as migrações e a flag?

A. As duas migrações são só de expansão:
   - `0026`: dois changesets, com `lock_timeout` de 5 s e um passo de repetição no runbook.
   - `0027`: índice parcial criado com `CONCURRENTLY` e `runInTransaction:false`.

   O rollback devolve só a imagem.

   A flag `MEMETRADE_REAL_OWN_QUANTITY_SELL="false"` entra em `api_environment` e em `worker_environment` no `main.tf`. Isso muda a definição de tarefa só por essa variável. A virada é uma implantação auditada separada: primeiro a API, depois o worker, com as consultas de virada do runbook e só depois da Performance Validation do 261113. (recomendado)
B. Flag no banco
X. Other (please specify)

[Answer]: A

## Q2. Monitoramento

O que muda no monitoramento?

A. Entram dois alarmes novos: `RealOpsOwnHoldingsUnavailable` (razão) e `RealOpsOwnHoldingsStuckBots` (indicador).
   - Ficam em `real-ops-alerts.yml` e `real-ops.tf`.
   - As métricas `real_own_holdings_unavailable_total`, `real_own_holdings_reads_total` e `real_own_holdings_stuck_bots` entram na lista de permissão do CloudWatch.
   - O sidecar muda nas duas famílias de tarefa, e a revisão do TxSigner entra no seu passo com MFA, como na U2.
   - Custo: cerca de US$ 1,90 por mês por conta.
   - O runbook ganha as seções dos dois alarmes e da virada.

   (recomendado)
B. Só Prometheus
X. Other (please specify)

[Answer]: A

## Q3. CI

O que muda na CI?

A. Os jobs não mudam. O teste da regra do alarme roda dentro do `mvnw verify` por Testcontainers, com o digest lido do `ci.yml`. O `verify.sh` não muda: com a flag desligada por padrão, o comportamento de ponta a ponta continua o de hoje. (recomendado)
B. Passo novo no `ci.yml`
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Migrações e flag:**
  - duas migrações só de expansão: a de resultados do ciclo e a do índice criado sem travar;
  - o rollback devolve só a imagem;
  - a flag começa desligada, como variável da API e do worker no Terraform;
  - a virada é feita numa implantação separada, primeiro a API e depois o worker, só depois da validação de desempenho do REAL [Q1].
- **Monitoramento:**
  - dois alarmes novos, um pela proporção de falhas e outro para bot travado;
  - três métricas entram no CloudWatch;
  - a revisão do TxSigner entra no seu passo com MFA;
  - custo de cerca de US$ 1,90 por mês por conta;
  - o runbook é atualizado [Q2].
- **CI:** os jobs não mudam. O teste da regra do alarme roda dentro do Maven, e o `verify.sh` não muda [Q3].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
