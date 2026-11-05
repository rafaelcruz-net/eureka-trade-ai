# Requisitos de escala — U5 Bots e decisão

Requisitos de carga e volume da Unit `u5-bots-decision`. As fontes são o NFR2 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e a resposta Q3 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Projeção

| Item | Valor no beta |
|---|---|
| Bots ativos | 60 (20 pessoas × 3) |
| Fatias por bot por dia | 288 |
| Decisões por dia | até 17.280 |
| Decisões guardadas | BUY e SELL do beta inteiro mais HOLD e NO_TRADE de 30 dias: até cerca de 520.000 |
| Canais ao vivo abertos | até 20 |

## Requisitos

| ID | Requisito | Meta | Origem |
|---|---|---|---|
| NFR2.4 | Paralelismo do worker | Até 4 ciclos ao mesmo tempo. O pool de banco do worker tem 10 conexões: 4 para os ciclos, 4 para a gravação separada de FAILED, que abre uma segunda conexão enquanto a do ciclo ainda está presa (NFR10.2), e 2 para as tarefas (meia-noite, conferência e expurgo). Teste: 4 ciclos com falha injetada ao mesmo tempo terminam sem esperar conexão | Q3, NFR10.2 |
| NFR2.5 | Volume das decisões | NFR3.1 mantido com 20.000 decisões por bot e 520.000 no total; leitura por índice (bot, criação decrescente) | Q12 do Functional Design |
| NFR2.6 | Canais ao vivo | 20 canais abertos sem aumentar o p95 das rotas C4 acima de 1 s; cada leitura de marca é uma consulta por índice | C7 |
