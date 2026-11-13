# Matriz de validação dos NFRs de desempenho e escala

Conversa: português (Brasil). Metas dos `performance-requirements.md` e `scalability-requirements.md` das Units U1 a U5; valores de `test-results.md`. "Local" = Docker Compose, perfil do beta; "AWS" = pendente.

| NFR | Meta | Medido | Onde | Situação |
|---|---|---|---|---|
| U1 NFR3.1 | páginas p95 ≤ 2 s | 146 ms | carga `legal` | atendida (local) |
| U1 NFR3.2 | API legal p95 ≤ 300 ms | 14 ms | carga `legal` | atendida (local) |
| U1 NFR3.3 | 0 respostas 5xx | 0 | carga `legal` | atendida (local) |
| U1 NFR3.7 | 20 acessos simultâneos | 20 usuários | carga `legal` | atendida (local) |
| U2 NFR3.1 | lote do expurgo ≤ 500 ms p95 | dentro da meta | `SessionPurgePerformanceIT` | atendida (suíte) |
| U2 NFR3.2 | 100 mil linhas ≤ 5 min | dentro da meta | `SessionPurgePerformanceIT` | atendida (suíte); volume real pendente (`perf-audit.sh`) |
| U2 NFR3.3 | HMAC ≤ 1 ms p95 | dentro da meta | `WalletHmacOverheadTest` | atendida (suíte) |
| U2 NFR3.4 | login ≤ 300 ms; conta com convite ≤ 500 ms | 134 ms; 224 ms | cargas `u1` e `u3` | atendida (local) |
| U3 NFR3.1 | aceite p95 ≤ 500 ms | 45 ms | carga `reacceptance` | atendida (local) |
| U3 NFR3.2 | trava p95 ≤ 5 ms | 4,2 ms | carga `reacceptance` (4ª rodada) | atendida (local), com pouca folga |
| U3 NFR3.3 | `/novo-aceite` p95 ≤ 2 s | 146 ms | carga `legal` | atendida (local) |
| U3 NFR3.6 | 20 usuários | 20 | carga `reacceptance` | atendida (local) |
| U4 NFR3.1 | criar, listar, abrir ≤ 500 ms | 23 ms | carga `data-requests` | atendida (local) |
| U4 NFR3.2 | administrativas ≤ 1 s com 1000 pedidos | 16 ms | carga `data-requests` | atendida (local) |
| U4 NFR3.3 | prévia ≤ 1 s | dentro dos 16 ms | carga `data-requests` | atendida (local) |
| U4 NFR3.4 | job de 15 min ≤ 30 s com 1000 pedidos | dentro da meta | `DataRequestTimersIT` | atendida (suíte) |
| U4 NFR3.6 | 3.2 e 3.4 com 1000 pedidos | ambas | carga e suíte | atendida |
| U5 NFR3.1 | anonimização p95 ≤ 2 s | dentro da meta | `AnonymizationLatencyIT` | atendida (suíte) |
| U5 NFR3.2 | consultas indexadas | sim | `AnonymizationIndexIT` | atendida (suíte) |
| Escala com várias réplicas | um expurgo e um job por ciclo; sem estado em memória | testado | ITs de concorrência das U2 a U5 | atendida (suíte); validação com réplicas pendente (AWS) |

## Leitura

Todas as metas de desempenho e escala das Units U1 a U5 estão atendidas no perfil do beta, localmente ou na suíte. A mais apertada é a trava de novo aceite (4,2 ms de 5 ms), acompanhada pelo alarme `TERMS_CHECK_SLOW` (`operation/observability-setup/alarms.md`). Nada foi medido num ambiente parecido com produção, porque ele ainda não existe.

## Pendências

- Os cinco cenários num ambiente parecido com produção (AWS, staging).
- `scripts/perf-audit.sh` da U2 com volume real.
- A trava de novo aceite (84% da meta) medida de novo na AWS antes do G1.
