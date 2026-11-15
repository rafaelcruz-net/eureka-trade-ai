# Desenho de escala — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/nfr-requirements/scalability-requirements.md` (NFR3.5 a NFR3.7, NFR9.1) e `nfr-design-questions.md` (Q2, Q3).

- **Conjunto ativo:** até 50 tokens vindos da descoberta, mais os de `WatchedTokens`. Acima de 100, a escala é revista (NFR3.5).
- **Volume esperado:** cerca de 6 chamadas por minuto e 264 mil por mês. Com a reserva de 10% para repetições, chega a cerca de 290 mil (NFR3.6).
- **Workers:** um só no piloto, garantido pelo ShedLock do coletor que já existe. Com mais de um, o limite por minuto do `CallBudget` é dividido pelo número de workers, configurado. O orçamento mensal já é comum a todos, porque fica na tabela `marketdata_provider_usage` (NFR3.7).
- **Custo:** o plano da CoinGecko, o KMS e o RPC somados dentro do teto de US$ 100. O preço do plano é decidido pelo fundador antes da produção (NFR9.1).
