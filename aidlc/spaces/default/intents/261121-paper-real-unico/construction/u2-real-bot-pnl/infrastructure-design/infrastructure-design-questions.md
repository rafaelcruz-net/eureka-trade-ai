# Perguntas do Infrastructure Design — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

## Fontes

- `construction/u2-real-bot-pnl/nfr-design/`: índice e migração `0023`; a tabela `real_risk_day_deferred` na `0024`; o pool `pnl-mismatch-audit`; o alarme `RealOpsPnlReconciliationMismatch`; os prazos.
- `construction/u2-real-bot-pnl/nfr-requirements/`: NFR1.9 (texto pronto do `load-test.yml`), NFR8.1 e D-NFR3.
- `construction/u1-mode-skeleton/infrastructure-design/`: chaves no `api_environment`, `@ConfigurationProperties` e o teste de ligação.
- `team.md`, seção Deployment, e `project.md` (CloudWatch: 120 s / 240 s, US$ 350 por conta).

## Q1. Migrações

Como as migrações novas entram?

A. As duas são aditivas e não removem nada:
   - **`0023`:** `real_entry_slot` (só de inserção, com o gatilho `ledger_reject_change` e o papel do app só com SELECT e INSERT) e o índice `real_ledger_entry_wallet_created_idx`;
   - **`0024`:** `real_risk_day_deferred`, com as permissões mínimas.

   O índice é criado com `CREATE INDEX CONCURRENTLY` num changeset próprio, sem transação, para não travar a tabela do ledger na implantação. Em produção, nenhuma migração é revertida; o rollback devolve só a imagem, e a imagem antiga ignora as tabelas novas. (recomendado)
B. Índice dentro da mesma transação da migração.
X. Other (please specify)

[Answer]: A

## Q2. Configuração nova

Que configuração nova a U2 traz, e onde ela fica?

A. As chaves novas (prazos de atribuição de 250 ms e 500 ms, prazo e tamanho do pool `pnl-mismatch-audit` e `work_mem` dos caminhos escolhidos) ficam só no `application.yml`, com `@ConfigurationProperties` e faixa validada. Elas não entram como variáveis da definição de tarefa, porque os valores são iguais em todo ambiente. Assim, a definição de tarefa não muda (NFR8.1). O pool novo, de 2 conexões, cabe no limite de conexões do banco que já existe, e o dimensionamento é conferido no `RuntimeSettingsGuard`. (recomendado)
B. Variáveis de ambiente para todas as chaves.
X. Other (please specify)

[Answer]: A

## Q3. Alarme e monitoramento

Como o alarme novo e as métricas entram no monitoramento?

A. Assim:
   - **Alarme:** `RealOpsPnlReconciliationMismatch` entra em `infra/observability/real-ops-alerts.yml` e em `real-ops.tf`, com 240 s, 1 período e `notBreaching`.
   - **Métrica:** `pnl_real_reconciliation_mismatches_total` entra na lista de permissão do CloudWatch (`app-metrics.tf`), sem dimensões. O custo é de cerca de US$ 0,30 por mês por conta.
   - **Testes:** o `RealOpsAlarmParityTest` passa a esperar 14 regras. O runbook `real-operations.md` ganha a seção do alarme.
   - **O que fica fora do CloudWatch:** as outras métricas novas ficam só no `/actuator/prometheus`.

   (recomendado)
B. Todas as métricas novas no CloudWatch.
X. Other (please specify)

[Answer]: A

## Q4. CI e teste de carga

Como a CI e o teste de carga ficam?

A. Os jobs da CI não mudam. Os testes novos (paridade, propriedade, plano de consulta e IT da trava) rodam no job `backend`.

O texto pronto do `load-test.yml` (NFR1.9) é entregue ao responsável na Code Generation. O Build and Test da U2 só termina com uma execução verde do cenário `pnl-by-mode`, disparada pelo responsável (D-NFR3). Nada muda no `verify.sh`. (recomendado)

B. Job novo na CI.
X. Other (please specify)

[Answer]: A

## Q5. Segredos e escala

O que muda em segredos e em escala?

A. Nada muda:
   - nenhum segredo novo; o HMAC usa a chave de auditoria que já existe;
   - nenhum serviço AWS novo, só uma métrica a mais na lista de permissão, dentro de US$ 350;
   - a mesma regra de escala.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Migrações:** duas, aditivas: `0023` (tabela de slots só de inserção e o índice) e `0024` (marca da fotografia adiada). O índice é criado `CONCURRENTLY`, sem travar o ledger. O rollback devolve só a imagem [Q1].
- **Configuração:** as chaves novas ficam no `application.yml`, com faixa validada. A definição de tarefa não muda. O pool novo, de 2 conexões, cabe no limite de conexões [Q2].
- **Monitoramento:** o alarme de divergência entra nos arquivos de operação REAL, e a métrica dele vai para o CloudWatch (cerca de US$ 0,30 por mês por conta). O teste de paridade passa a esperar 14 regras. O runbook é atualizado [Q3].
- **CI:** os jobs não mudam. O responsável recebe o texto pronto do `load-test.yml`, e o Build and Test precisa de uma execução verde do `pnl-by-mode` [Q4].
- **Segredos e escala:** nada muda [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
