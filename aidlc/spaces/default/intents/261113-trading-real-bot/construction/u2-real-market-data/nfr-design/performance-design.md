# Desenho de desempenho — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/nfr-requirements/performance-requirements.md` (NFR3.1 a NFR3.4), `construction/u2-real-market-data/functional-design/functional-spec.md` (F1) e `reliability-design.md` deste estágio.

| Meta | Como |
|---|---|
| NFR3.1 rodada de preços até 10 s | Chamadas em série, sem paralelismo: o coletor chama o adaptador uma vez por fonte. São 2 lotes com 50 tokens e 4 com até 100, cada um com 4 s de limite. Com a latência típica de 300 ms, a rodada leva cerca de 1 s; no pior caso sem repetição, 8 s. O prazo total da rodada é de 20 s, no `SourceCaller` |
| NFR3.2 descoberta até 5 s | 2 chamadas, só com pelo menos 8 s de prazo restante |
| NFR3.3 detalhes até 5 s | No máximo 1 chamada por rodada com o limite de 2 por minuto e rodadas de 30 s; só com pelo menos 4 s de prazo restante |
| NFR3.4 scanner sem piora | O scanner lê só do banco. A gravação é uma transação curta depois das chamadas, sem trava aberta durante a rede |

A medição antes da produção é sintética, com WireMock e 300 ms de latência por chamada.
