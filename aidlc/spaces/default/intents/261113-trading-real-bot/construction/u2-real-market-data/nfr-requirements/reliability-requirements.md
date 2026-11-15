# Requisitos de confiabilidade — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/functional-design/rules.md` (BR1.3, BR1.9), `inception/requirements-analysis/requirements.md` (NFR4), `inception/contract-design/contract-summary.md` (X2) e `nfr-requirements-questions.md` (Q4).

| ID | Requisito |
|---|---|
| NFR4.1 | Meta de frescor do preço: em 99% dos minutos do mês, o preço mais velho entre os tokens ativos tem até 60 s (`marketdata_oldest_price_age_seconds` ≤ 60). Assim a meta acompanha o que o REAL precisa (FR1.4): uma rodada DEGRADED sem preço conta como minuto ruim, mesmo que a fonte ainda não esteja FAILED [Q4] |
| NFR4.6 | Cada rodada tem prazo total de 20 s. A espera de um 429 só acontece se couber no prazo; senão, a rodada termina DEGRADED. A ordem dos protetores é repetição por fora, depois limite de tempo, depois circuito: uma chamada com o circuito aberto não é repetida |
| NFR4.2 | Um circuito do Resilience4j para a CoinGecko conta só falhas técnicas: tempo esgotado, conexão, 5xx e 429 com espera acima de 10 s. Abre com 50% de falha em 60 s, com no mínimo 5 chamadas, e fica aberto por 60 s. Com o circuito aberto, a rodada termina sem chamar o fornecedor [Q4] |
| NFR4.3 | A primeira rodada sem preço deixa a fonte DEGRADED; a segunda seguida, FAILED. Em FAILED, o dado vira `unknown` e nunca há volta para a fonte simulada (BR1.9) |
| NFR4.4 | Depois de um reinício do worker, o conjunto ativo é refeito na primeira rodada. As observações gravadas continuam valendo, com o horário delas |
| NFR4.5 | Uma resposta malformada ou fora da faixa nunca grava valor errado: vira `unknown` e conta na métrica de valores inválidos |
