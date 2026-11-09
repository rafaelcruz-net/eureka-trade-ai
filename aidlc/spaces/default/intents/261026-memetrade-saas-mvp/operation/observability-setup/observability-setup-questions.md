# Perguntas do Observability Setup

Conversa: português (Brasil). Há um alvo implantado: a release local `v2026.11.08-1` (`operation/deployment-execution/deployment-log.md`). Não há conta na AWS. Por isso, o CloudWatch, o SNS e o X-Ray só podem ser desenhados, não criados.

O que existe hoje (lido dos desenhos de monitoramento das Units U1 a U8 e do código):
- Métricas Micrometer em formato Prometheus na porta de gestão 8081 (API) e 8082 (worker, só no overlay de teste): `http_server_requests_seconds` por rota, pool HikariCP, JVM e as métricas de domínio de cada Unit (auditoria, fonte, login, limite de tentativas, bots, risco, ledger, kill switch, feedback).
- Indicadores de saúde no `/actuator/health`: `auditOutboxCleanup`, `adminKillSwitch`, `ledgerJobs`, `riskRecalculation`, `feedbackPurge` e outros, com `DEGRADED` ou `DOWN` quando há atraso ou falha.
- Logs JSON no stdout com `timestamp` UTC, `level`, `component`, `message` e `correlationId`, mascarados na origem. Retenção pelo `json-file` do Docker (10 MB × 3 por serviço).
- Não há coletor, painel, notificação nem rastreamento distribuído. Os "alertas" locais são saúde `DOWN` ou `DEGRADED` mais logs `ERROR` com código estável.
- A U9 não tem desenho de infraestrutura próprio. O painel dela usa as métricas das U5 e U7.

## Pergunta 1
Qual o alcance desta etapa?

A) Documentar a observabilidade local que já existe, com consultas prontas para métricas, saúde e logs, e desenhar a configuração da AWS (painéis e alarmes do CloudWatch, SNS, Logs Insights, X-Ray) para aplicar quando a conta existir, sem criar recurso e sem mudar código (recomendado)
B) Além disso, acrescentar Prometheus e Grafana ao Docker Compose agora. Isso muda `infra/` e reabre a revisão da U1
X) Other (please specify)

[Answer]: A

## Pergunta 2
Quais sinais acompanhar?

A) Os quatro sinais de ouro por rota (latência p95 e p99, tráfego, taxa de 5xx e saturação: pool HikariCP, heap da JVM, memória e CPU dos contêineres), mais os sinais de domínio de cada Unit: atraso da auditoria, frescor da fonte, recálculo de risco, ciclo dos bots, avaliação de risco, rodadas do ledger com divergência, kill switch e expurgos (recomendado)
B) Só os quatro sinais de ouro
X) Other (please specify)

[Answer]: A

## Pergunta 3
Quais SLOs registrar?

A) Dois níveis. (1) As metas das Units continuam como SLIs medidos nos testes de carga e de integração (por exemplo: lista p95 ≤ 1 s, `evaluate` p95 ≤ 250 ms, auditoria entregue em até 5 s em 99%). (2) Uma proposta de SLO para a produção do beta PAPER, a confirmar quando houver hospedagem: disponibilidade de 99,5% em janela móvel de 30 dias; 95% das requisições de leitura abaixo de 1 s em janela de 28 dias; frescor da fonte ≤ 60 s em 99% dos minutos; orçamento de erro com alerta de consumo rápido (recomendado)
B) Só as metas das Units; nenhum SLO de produção até haver hospedagem
X) Other (please specify)

[Answer]: A

## Pergunta 4
Quais painéis?

A) Três painéis: "Visão geral", com os sinais de ouro por serviço e rota; "Domínio", com auditoria, fonte, bots, risco, ledger, kill switch e expurgos; e "Segurança", com logins recusados, limite de tentativas, reuso de token de renovação, 403 e 404 por tenant e entradas de administrador. Localmente, eles são consultas de referência ao `/actuator/prometheus` e ao `/actuator/health`; na AWS, painéis do CloudWatch (recomendado)
B) Um painel único
X) Other (please specify)

[Answer]: A

## Pergunta 5
Quais regras de retenção e agregação de logs?

A) Local: como está (`json-file` 10 MB × 3). Na AWS: CloudWatch Logs com um grupo por serviço e 7 dias de retenção, conforme os requisitos de observabilidade. A trilha de auditoria segue no banco, com a retenção dela. Ficam salvas consultas do Logs Insights por `correlationId`, por código de erro estável, por `ERROR` de cada componente e por rodadas de tarefas. Nenhum campo novo de dado pessoal; a varredura de logs continua (recomendado)
B) Retenção maior na AWS (diga quanto)
X) Other (please specify)

[Answer]: A

## Pergunta 6
E o rastreamento distribuído?

A) Nenhum no local; o `correlationId` continua ligando os logs. Na AWS: agente OpenTelemetry Java (ADOT) na API e no worker, enviando ao X-Ray com amostragem de 5% e 100% dos erros. O `correlationId` vira atributo do trace. Sem corpo, token, endereço ou assinatura nos spans (recomendado)
B) Nenhum rastreamento, nem na AWS
X) Other (please specify)

[Answer]: A

## Pergunta 7
Como rotear os alarmes?

A) Local: saúde `DOWN` ou `DEGRADED` e logs `ERROR`, vistos pelo responsável do projeto. Na AWS: dois tópicos SNS. "Alta" vai por e-mail e notificação no celular ao responsável do projeto, com reconhecimento esperado em 15 minutos. "Média" vai por e-mail, para tratar no mesmo dia. O escalonamento é só o responsável do projeto enquanto o time for de uma pessoa; o kill switch da U8 é a ação de contenção (recomendado)
B) Só e-mail para tudo
X) Other (please specify)

[Answer]: A

## Pergunta 8
E a detecção de anomalias?

A) Desenhar a detecção de anomalias do CloudWatch para tráfego, taxa de 5xx, logins recusados e recusas de risco por bot, ligada só depois de duas semanas de dados reais. Até lá, valem os limites fixos (recomendado)
B) Sem detecção de anomalias
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Alcance: documentar a observabilidade local que já existe e desenhar a da AWS, sem criar recurso e sem mudar código.
- Sinais: os quatro sinais de ouro por rota e os sinais de domínio de cada Unit.
- SLOs: metas das Units como SLIs; proposta para o beta PAPER a confirmar com a hospedagem (99,5% de disponibilidade em 30 dias, 95% das leituras < 1 s em 28 dias, fonte ≤ 60 s em 99% dos minutos, orçamento de erro com alerta de consumo rápido).
- Painéis: Visão geral, Domínio e Segurança.
- Logs: local como está; na AWS, CloudWatch Logs com 7 dias e consultas salvas.
- Rastreamento: nenhum local; na AWS, ADOT para o X-Ray com 5% de amostragem e 100% dos erros, sem dados sensíveis.
- Alarmes: Alta por e-mail e celular com reconhecimento em 15 min; Média por e-mail no mesmo dia; escalonamento só para o responsável do projeto; kill switch como contenção.
- Anomalias: desenhadas e ligadas depois de duas semanas de dados reais.

- Looks correct
- Request changes

[Answer]: Looks correct
