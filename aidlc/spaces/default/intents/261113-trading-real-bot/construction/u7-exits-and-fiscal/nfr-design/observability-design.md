# Projeto de observabilidade e testes — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `construction/u7-exits-and-fiscal/nfr-requirements/observability-requirements.md` (U7-NFR5.x) e `tech-stack-decisions.md` (U7-NFR8.x, U7-NFR10.1); `nfr-design-questions.md` (Q7).

| Requisito | Como o projeto atende |
|---|---|
| U7-NFR5.1 a U7-NFR5.6b | `RealExitsMetrics`, com rótulos vindos de enums e contadores registrados com zero. O `RealExitsGaugesJob` (30 s) lê do banco a contagem por estado, o `CANCELLING` mais antigo e a leitura mais antiga. As recusas da `TriggerPolicy` são contadas no TxSigner. |
| U7-NFR5.7 a U7-NFR5.10 | Regras em `infra/observability/real-exits-alerts.yml`. Alarmes em `infra/terraform/modules/observability/real-exits.tf` (`Eureka/RealExits`). O teste `RealExitsAlarmParityTest` confere que os dois lados batem. A linha do `promtool` no `ci.yml` vai pronta para o responsável. |
| U7-NFR5.11 | `LogMasking` nos endereços. O cabeçalho da chave é mascarado no `RestClient`, e a exportação só registra o período e a contagem. |
| U7-NFR8.1, U7-NFR8.2 | Sem dependência nova. O `record.sh` é estendido para a Trigger API, e o SDK Solana fica só no escopo de teste. |
| U7-NFR8.3, U7-NFR8.4 | Testes escritos antes do código: `TriggerPolicy`, `ConditionalStateMachine`, `FillPosting`, `PositionExitGuard`, `FiscalCsvWriter` com a conferência de BR7.6, e os acréscimos na `ReconciliationPolicy` e no `closeForAnonymization`. Depois do código: rotas, telas, métricas e adaptadores. |
| U7-NFR8.5 | E2E `apps/web/e2e/real-exports/` (exportação depois do `real-swap`) e `apps/web/e2e/real-exits/` (condicionais pela tela com o adaptador simulado ligado só no perfil de teste da rede local). |
| U7-NFR8.6 | O piso de 80% de linhas que já existe na API, no web e no TxSigner. |
| U7-NFR10.1 | O `FiscalCsvWriter` recebe o fuso da pessoa do perfil e escreve `data_utc` e `data_local`. |
