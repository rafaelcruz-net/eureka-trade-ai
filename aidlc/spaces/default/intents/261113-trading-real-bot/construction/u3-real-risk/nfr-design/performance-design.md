# Projeto de desempenho — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/nfr-requirements/performance-requirements.md` (U3-NFR3.1 a U3-NFR3.4) e `nfr-design-questions.md` (Q2 a Q4).

| Requisito | Como o projeto atende |
|---|---|
| U3-NFR3.1 (compra p95 ≤ 150 ms) | Depois da trava, a compra faz um número fixo de consultas: estado (M1), saldo e reservas por tipo (M2), posições (M2), preços em lote para posições e token (M4 `referencePrices`, uma consulta), avaliação, bloqueio, reservas `OPEN`, contagem do dia, foto do dia e fluxos. São cerca de 10 consultas indexadas, sem laço por posição. A política pura roda em memória. |
| U3-NFR3.2 (venda p95 ≤ 30 ms) | A venda lê só M1 e M4 (`activeSourceKind`), sem trava, e grava a avaliação. |
| U3-NFR3.3 (trava ≤ 1 s) | `WalletLock` tenta `pg_try_advisory_xact_lock` a cada 20 ms por até 1 s. O tempo de espera vai para `real_risk_lock_wait_seconds`, e o histograma da avaliação só começa depois da trava. |
| U3-NFR3.4 (trabalhos ≤ 5 s) | F4 calcula a perda das 21 carteiras numa passada: posições e preços em lote para todas as carteiras e uma consulta de fluxos por carteira. F3 e F5 são uma consulta e uma atualização em massa cada. |

**Medição:** um teste de integração com PostgreSQL em contêiner, 21 carteiras, 10 posições cada e 4 avaliações por segundo, mede os histogramas e registra o resultado como informação, sem portão (`team.md`).
