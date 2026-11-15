# Requisitos de escala — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/functional-design/rules.md` (BR1.1, BR1.3), `inception/requirements-analysis/requirements.md` (NFR3, NFR9), `inception/contract-design/contract-summary.md` (X2) e `nfr-requirements-questions.md` (Q6).

| ID | Requisito |
|---|---|
| NFR3.5 | O piloto trabalha com até 50 tokens ativos, mais os observados. Acima de 100 tokens, a escala e o plano são revistos [Q6] |
| NFR3.6 | Com 50 tokens: cerca de 6,1 chamadas por minuto e 264 mil por mês sem repetições; com até 10% de repetições (X2), até cerca de 290 mil por mês. Toda tentativa conta no limitador e no orçamento. Os limites de 250 por minuto e de 450 mil por mês são configuráveis e conferidos com o contrato antes da produção (BR1.3) |
| NFR3.7 | O contador do orçamento mensal não depende da memória do worker. A fonte da verdade é o uso informado pela própria CoinGecko na rota de uso da chave, lida na subida e a cada hora. Entre as leituras, o worker soma as chamadas que fez. Um reinício recomeça do valor informado, nunca de zero. O limite por minuto vale por processo, e o piloto tem um worker só (ShedLock); com mais de um worker, o limite é dividido pelo número de workers |
| NFR9.1 | O custo do plano da CoinGecko entra no teto de US$ 100 por mês do NFR9, junto com o KMS (cerca de US$ 23, Infrastructure Design da U1) e o RPC. O preço do plano ainda não foi confirmado. Se plano, KMS e RPC passarem de US$ 100, o fundador decide antes da produção entre subir o teto, trocar de plano ou reduzir os tokens [Q6] |

O volume de observações gravadas segue a limpeza do histórico de mercado que já existe.
