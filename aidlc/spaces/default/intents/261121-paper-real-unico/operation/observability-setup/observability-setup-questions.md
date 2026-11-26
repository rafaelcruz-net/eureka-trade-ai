# Perguntas do Observability Setup — intent `261121-paper-real-unico`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `261113-trading-real-bot/operation/observability-setup/` (o precedente, que foi executado sem ambiente na nuvem, com a observabilidade como código).
- `project.md` (Corrections): o CloudWatch Agent lê a cada 120 s, alarmes que disparam com dado ausente usam janelas de 240 s e os demais usam pelo menos 180 s; orçamento de US$ 350 por conta.
- `nfr-design/` e `infrastructure-design/` da U1 à U4 (métricas, alarmes, custos).
- `operation/environment-provisioning/` (inventário: 6 séries novas na lista e 3 alarmes; cerca de US$ 2,30 por conta; IR-G3 e D-INF2).
- `infra/observability/real-ops-alerts.yml` (16 regras), `infra/terraform/modules/observability/*`.

## Q1. Executar sem nuvem?

Não existe ambiente na nuvem. Esta etapa roda mesmo assim?

A. Sim, como o 261113. A observabilidade deste intent já está no repositório como código:
   - as regras do Prometheus, que rodam no ambiente local e são testadas na CI;
   - os alarmes e a lista do CloudWatch no Terraform.

   A etapa documenta o que este intent acrescenta (métricas, alarmes, painéis, SLOs, consultas de log) para a primeira implantação. Nada é aplicado na AWS. (recomendado)
B. Pular a etapa
X. Other (please specify)

[Answer]: A

## Q2. Sinais e SLOs

Que sinais e que SLOs valem para este intent?

A. Valem os sinais e as metas já aprovados nas Units, sem meta nova:
   - **U1 e U3:** leitura do P&L por modo; `pnl_mode_block_unavailable_total`, `pnl.compare.*`, `pnl.read_audit.failures`; tempo de C4, C5 e C6 com meta de 1.000 ms.
   - **U2:** a reconciliação `pnl_real_reconciliation_mismatches_total` e o alarme; o teto da barreira de compra de 500 ms.
   - **U4:**
     - leituras da quantidade própria, com o orçamento de 500 ms (F6);
     - o alarme de razão de 5% e o alarme do bot preso.

     O SLI da U4 fica marcado como **`Not Met` na semente B** até a decisão F7, com a flag desligada.

   Os SLOs são escritos com percentual e janela de tempo, e os limites dos alarmes ficam abaixo do ponto em que o SLO é rompido. (recomendado)
B. Definir SLOs novos
X. Other (please specify)

[Answer]: A

## Q3. Painéis, logs e rastreamento

O que muda nos painéis, nos logs e no rastreamento?

A. Fica assim:
   - **Painéis:** o painel REAL do 261113 ganha uma linha "P&L por modo e quantidade própria", com os gráficos das métricas novas. O gráfico de conexões do RDS continua lá, com a nota do D-INF2.
   - **Logs:** consultas salvas do Logs Insights para os eventos deste intent: `PNL_*`, `REAL_OWN_QUANTITY_*`, `STALE_SNAPSHOT`, as recusas e a auditoria. Nenhum dado pessoal, endereço ou segredo. A retenção segue o 261113.
   - **Rastreamento:** nada novo. Continua o `correlationId` nos logs e nas respostas; não entra X-Ray.
   - **Anomalias:** nenhuma detecção nova. Os alarmes fixos bastam, e isso fica com motivo e custo registrados.

   (recomendado)
B. X-Ray e detecção de anomalias
X. Other (please specify)

[Answer]: A

## Q4. Lacunas que vêm de antes

Como ficam as lacunas que vêm do 261113?

A. Ficam registradas com dono e sem mudança de código:
   - **D-INF2:** não há alarme de conexões do RDS. A proposta é o alarme `DatabaseConnections`, por cerca de US$ 0,10, com decisão do fundador junto com a classe do banco.
   - **OBS-G1 e OBS-G2:** continuam como estão no 261113.
   - **TGT-E2E-FLAKY:** o log do validador e do worker passa a ser guardado quando o `e2e` falha. Esta é uma proposta para o `ci.yml`, e só você altera esse arquivo.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Execução:** a etapa roda como no 261113 e documenta a observabilidade que já está no repositório como código. Nada é aplicado na AWS [Q1].
- **Sinais e SLOs:** valem as metas já aprovadas da U1 à U4, escritas com percentual e janela, e os alarmes disparam antes de o SLO ser rompido. A U4 fica `Not Met` na carteira que nunca zerou até a decisão F7 [Q2].
- **Painéis, logs e rastreamento:** uma linha nova no painel REAL, consultas salvas sem dados pessoais e nada novo em rastreamento ou anomalias [Q3].
- **Lacunas:** o alarme de conexões do RDS fica como decisão sua (cerca de US$ 0,10); OBS-G1 e OBS-G2 continuam; e há uma proposta de guardar os logs quando o `e2e` falhar [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
