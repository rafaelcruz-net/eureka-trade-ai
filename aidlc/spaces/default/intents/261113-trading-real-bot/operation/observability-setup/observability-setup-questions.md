# Perguntas da Observability Setup — intent 261113-trading-real-bot

Conversa: português (Brasil). As respostas seguem a instrução do responsável "siga sem me perguntar": em cada pergunta foi escolhida a opção recomendada.

Fontes:
- `construction/u*/infrastructure-design/monitoring-design.md`;
- `construction/u*/nfr-requirements/observability-requirements.md`;
- `construction/u*/nfr-design/`;
- `infra/observability/*.yml`, as regras do Prometheus validadas no `promtool`;
- `infra/terraform/modules/observability/`;
- `operation/environment-provisioning/security-posture-review.md`, item SP-12;
- `validation-report.md`, seção 11;
- `construction/build-and-test/build-and-test-summary.md`, com as metas U3-NFR5.11 e U4-NFR5.13 desta etapa.

Situação atual:
- não existe ambiente implantado;
- o código publica métricas Micrometer em `/actuator/prometheus`;
- os alarmes do Terraform leem métricas do CloudWatch que ninguém publica (SP-12).

## Pergunta 1 — Caminho das métricas até o CloudWatch

Como as métricas da API, do worker e do TxSigner chegam ao CloudWatch?

A. Um contêiner auxiliar do CloudWatch Agent em cada tarefa ECS. Ele raspa `/actuator/prometheus` pela rede local da tarefa e publica no namespace de cada Unit, com um filtro que deixa passar só as métricas usadas pelos alarmes e painéis. O código não muda, as regras do Prometheus continuam valendo no local e na CI, e o teste de paridade compara os dois lados. (recomendado: fecha o SP-12 sem mexer no código e mantém uma fonte única de métricas)
B. Trocar o registro do Micrometer para o CloudWatch, mudando o código
C. Amazon Managed Prometheus com Grafana
X. Other (please specify)

[Answer]: A

## Pergunta 2 — Alarmes sem dado

Hoje os alarmes usam `treat_missing_data = notBreaching` e ficam verdes para sempre sem métrica. Como tratar a falta de dado?

A. Cada alarme de erro mantém `notBreaching`. Entra também um alarme de "métrica ausente" por serviço, com `treat_missing_data = breaching`, sobre uma métrica que existe sempre (por exemplo `jvm_info` ou o heartbeat dos trabalhos). Assim, um caminho de métricas quebrado dispara um alarme em vez de calar todos. (recomendado)
B. Trocar todos para `breaching`
X. Other (please specify)

[Answer]: A

## Pergunta 3 — SLOs e sinais

Quais SLOs e sinais acompanhar?

A. Os do NFR Design de cada Unit, como já estão nos requisitos:
   - disponibilidade de 99,5% em 30 dias na API e no TxSigner (U1:NFR4.1);
   - frescor dos dados de mercado (U2:NFR4.1);
   - os p95 de cada Unit: barreira, execução, confirmação, preenchimento e exportação.

   Cada SLO tem um alarme de taxa de consumo que dispara antes de o SLO estourar: 2% do orçamento em 1 h, e 5% em 6 h. Os sinais de ouro são latência, tráfego, erros e saturação por serviço. (recomendado)
B. Só os sinais de ouro, sem SLO
X. Other (please specify)

[Answer]: A

## Pergunta 4 — Painéis

Quais painéis montar?

A. Três painéis do CloudWatch, no Terraform:
   - **Visão REAL:** ordens, recusas da barreira, carteiras ativas, saques, divergências, kill switch e saídas condicionais;
   - **Serviços:** sinais de ouro, ECS, RDS e o balanceador;
   - **Segurança:** KMS, recusas da política do TxSigner, GuardDuty e os alarmes `-security-*`.

   (recomendado)
B. Um painel único
X. Other (please specify)

[Answer]: A

## Pergunta 5 — Logs

Que retenção e consultas de log usar?

A. Retenção de 90 dias, já no Terraform por minimização da LGPD. Os logs são JSON com `correlationId`, e nunca levam segredo, endereço completo nem assinatura de login. Entram consultas salvas do Logs Insights para:
   - seguir um `correlationId` entre os serviços;
   - recusas do TxSigner por motivo;
   - erros 5xx por rota;
   - ordens REAL ambíguas;
   - falhas dos trabalhos.

   (recomendado)
B. Retenção de 1 ano
X. Other (please specify)

[Answer]: A

## Pergunta 6 — Rastreamento distribuído

Que rastreamento usar?

A. Nenhum X-Ray no piloto. O `correlationId` já atravessa a API, o worker e o TxSigner nos logs e nas respostas. O X-Ray (ou OpenTelemetry) fica registrado como candidato quando houver mais de uma instância. (recomendado: menos custo e peças, mesma capacidade de seguir um pedido)
B. Ligar o X-Ray com OpenTelemetry agora
X. Other (please specify)

[Answer]: A

## Pergunta 7 — Destino dos alarmes e escalonamento

Para onde vão os alarmes, e como escalam?

A. O tópico SNS com a chave própria, já no Terraform, com o e-mail do fundador como assinante. A severidade é alta para dinheiro e segurança e média para desempenho. O fundador é o único plantonista, e os contatos ficam "a definir" até a Incident Response. A detecção de anomalia do CloudWatch entra nos volumes de ordens e de recusas, só como aviso. (recomendado)
B. Integrar com um serviço de plantão (PagerDuty ou Opsgenie)
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- As métricas chegam ao CloudWatch por um contêiner auxiliar do CloudWatch Agent em cada tarefa. Ele raspa o `/actuator/prometheus` e filtra só as métricas usadas. O código não muda, e a paridade entre as regras do Prometheus e os alarmes continua. Isso fecha o SP-12 [Q1].
- Entra um alarme de "métrica ausente" por serviço, que dispara quando o caminho das métricas quebra. Os alarmes de erro continuam `notBreaching` [Q2].
- Os SLOs são os do NFR Design: 99,5% em 30 dias, o frescor dos dados e os p95 de cada Unit. Cada um tem alarme de taxa de consumo, 2% em 1 h e 5% em 6 h. Os alarmes de p95 adiados (U3-NFR5.11, U4-NFR5.13) entram aqui [Q3].
- Três painéis no Terraform: Visão REAL, Serviços e Segurança [Q4].
- Logs com 90 dias de retenção, em JSON com `correlationId` e sem dado sensível, e com consultas salvas do Logs Insights [Q5].
- Nenhum X-Ray no piloto. O `correlationId` cobre o rastreio [Q6].
- Os alarmes vão para o tópico SNS, com o e-mail do fundador. A severidade é alta para dinheiro e segurança. A detecção de anomalia fica nos volumes de ordens e de recusas, e os contatos ficam "a definir" [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
