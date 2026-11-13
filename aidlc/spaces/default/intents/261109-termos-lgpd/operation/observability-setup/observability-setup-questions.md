# Perguntas do Observability Setup

Conversa: português (Brasil). As respostas seguem a opção recomendada, como o responsável do projeto pediu para todas as perguntas do AI-DLC (2026-11-10). Base: o Observability Setup do intent `261026-memetrade-saas-mvp` (sinais de ouro, SLOs, roteamento local e desenho do CloudWatch), que continua valendo.

## Pergunta 1
Quais sinais acompanhar para as Units deste intent?

A) Os sinais de ouro do intent anterior mais as métricas que os `monitoring-design.md` das Units U1 a U5 definem (expurgos, trava de novo aceite, pedidos do titular, anonimização, documentos legais) (recomendado)
B) Só os sinais de ouro, sem as métricas novas
X) Other (please specify)

[Answer]: A

## Pergunta 2
SLOs?

A) Manter os SLOs do intent anterior e acrescentar um SLO de prazo para os pedidos do titular (nenhum pedido vencido além de 15 dias) e as metas de latência dos `performance-design.md` como indicadores, sem orçamento de erro próprio (recomendado: o volume do beta é pequeno para um orçamento por rota)
B) Um SLO com orçamento de erro para cada rota nova
X) Other (please specify)

[Answer]: A

## Pergunta 3
Painéis?

A) Um painel novo "Privacidade e termos" ao lado dos painéis do intent anterior (recomendado)
B) Espalhar as métricas nos painéis existentes
X) Other (please specify)

[Answer]: A

## Pergunta 4
Retenção e agregação de logs?

A) Sem mudança: logs JSON por contêiner localmente; na AWS, CloudWatch Logs com a retenção do intent anterior. Nenhum log carrega endereço completo de carteira, texto de pedido, contato ou assinatura (`LogScanIT`) (recomendado)
B) Outra retenção
X) Other (please specify)

[Answer]: A

## Pergunta 5
Rastreamento distribuído?

A) Sem mudança: o `correlationId` liga requisição, log e evento da trilha; X-Ray fica para a AWS, como no intent anterior (recomendado)
B) Instrumentar agora com OpenTelemetry
X) Other (please specify)

[Answer]: A
