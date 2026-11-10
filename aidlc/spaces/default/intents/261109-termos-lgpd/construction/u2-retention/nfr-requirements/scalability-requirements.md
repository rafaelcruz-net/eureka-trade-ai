# Requisitos de escalabilidade — U2 `u2-retention`

Conversa: português (Brasil). Fontes: `construction/u2-retention/functional-design/` (BR2.7, BR2.16) e `nfr-requirements-questions.md` (Q4).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.6 | Volume do beta: até 20 convidados simultâneos e cerca de 100 logins por dia. São poucas centenas de desafios e sessões por dia, e o lote de 1000 dá folga | passa quando o teste com 100 mil linhas (1000 vezes o volume diário) cumpre NFR3.1 e NFR3.2 |
| NFR3.7 | Com várias instâncias da API, só uma executa cada expurgo e todas mostram a mesma saúde, lida da tabela de execuções (BR2.16). A trava ShedLock segue o padrão do `AuditJobRunner`: `lockAtMostFor` de 35 minutos e `lockAtLeastFor` de 5 minutos | teste de integração com duas chamadas concorrentes: só uma apaga |
| NFR3.8 | A coluna `wallet_hmac` tem índice, então a busca de eventos pela mesma carteira não percorre a trilha inteira | plano de consulta |

## Crescimento

Os dados expurgados não crescem além de um dia (desafios) ou de 30 dias (sessões) de uso. A trilha cresce como hoje e é expurgada em 12 meses pelo expurgo existente.
