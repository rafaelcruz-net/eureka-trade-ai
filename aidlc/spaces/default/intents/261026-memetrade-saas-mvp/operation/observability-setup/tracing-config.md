# Configuração de rastreamento

Conversa: português (Brasil). Resposta 6A. Os desenhos de monitoramento das Units (`construction/<unit>/infrastructure-design/monitoring-design.md`) não usam rastreamento distribuído no beta local. A infraestrutura de destino está em `infrastructure-specification.md`.

## Hoje (local)

- Sem rastreamento distribuído.
- O `CorrelationIdFilter` da U1 gera ou propaga o `correlationId` por requisição. Ele aparece em cada linha de log, na resposta `ProblemDetail` e nos eventos de auditoria. É o fio que liga requisição, eventos e logs.
- As tarefas do worker registram início, fim e falha de cada rodada com o próprio identificador de rodada.

## AWS (desenho, sem recurso criado)

| Item | Configuração |
|---|---|
| Instrumentação | agente Java do AWS Distro for OpenTelemetry (ADOT), anexado por `JAVA_TOOL_OPTIONS` na imagem da API e do worker; sem mudança no código de domínio |
| Coletor | ADOT Collector como sidecar ou serviço da tarefa, exportando para o X-Ray |
| Serviços | `memetrade-api`, `memetrade-worker`; web e admin (Next.js) ficam fora no início |
| Amostragem | 5% das requisições e 100% das que terminam em 5xx ou exceção (regra de amostragem do X-Ray com reservatório de 1 por segundo) |
| Spans | HTTP do servidor, JDBC (só o nome da operação, sem parâmetros), tarefas agendadas e chamadas a adaptadores externos (quando houver fornecedor real) |
| Atributos | `correlationId`, rota, status, papel do processo (`api`/`worker`), ambiente |
| Proibido nos spans | corpo de requisição e de resposta, parâmetros SQL, cabeçalho `Authorization`, token, nonce, assinatura, endereço completo de carteira, texto de feedback |
| Propagação | W3C Trace Context; o `correlationId` continua sendo o identificador visível nos logs e no `ProblemDetail` |
| Retenção | padrão do X-Ray (30 dias) |
| Custo | revisado no primeiro mês; a amostragem pode baixar, mas nunca deixar de capturar os erros |

## Verificação quando a conta existir

1. Subir o staging com o agente ligado.
2. Fazer uma requisição com erro conhecido e achar o trace pelo `correlationId`.
3. Conferir que nenhum atributo proibido aparece (varredura dos segmentos exportados).
