# Requisitos de desempenho — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/functional-design/functional-spec.md` (F1), `inception/requirements-analysis/requirements.md` (NFR3), `inception/contract-design/contract-summary.md` (X2) e `nfr-requirements-questions.md` (Q2).

| ID | Operação | Meta (p95) | Como medir |
|---|---|---|---|
| NFR3.1 | Rodada de preços com 50 tokens | até 10 s, com prazo total de 20 s (NFR4.6) | métrica da duração da rodada. Antes da produção, a medição é sintética, com WireMock e latência simulada de 300 ms por chamada; com a API real, só depois da produção, porque a CoinGecko não tem devnet e os testes não usam rede (NFR8.2) |
| NFR3.2 | Descoberta (2 chamadas) | até 5 s | métrica |
| NFR3.3 | Fatia de detalhes | até 5 s | métrica |
| NFR3.4 | Leitura do scanner e do detalhe | a mesma meta do scanner da intent anterior, sem piora medida pelo teste de carga atual | o teste de carga que já existe |

**Regras:**
- O tempo limite de cada chamada é de 5 s, com uma repetição (X2).
- A rodada nunca segura uma trava do banco enquanto espera a rede: busca primeiro e grava depois, numa transação curta.
- O scanner lê só do banco.
- As metas são medidas de forma informativa, sem portão na CI (`team.md`).
