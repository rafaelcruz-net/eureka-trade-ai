# Desenho de desempenho — U8 Administração e feedback

Base: [requisitos de desempenho](../nfr-requirements/performance-requirements.md) e [respostas](nfr-design-questions.md) Q2 e Q5.

| ID | Desenho | Verificação |
|---|---|---|
| NFR2.1 | O `PUT ON` confirma o estado, chama o `KillSwitchApplier` (a U5 pausa em lotes, cada bot na sua transação) e responde. Sem leitura da lista de bots pela U8 | `KillSwitchPerformanceIT`: 60 bots ativos (20 pessoas × 3), 20 execuções, p95 ≤ 5 s |
| NFR2.2 | Reconciliação a cada 30 s e ao subir o processo. Pior caso: 45 s (Q2) | `KillSwitchCrashIT` com relógio controlado: queda simulada (o applier lança exceção antes de chamar a U5) e conferência de `systemPaused()` e do motivo SYSTEM nos 60 bots dentro da janela |
| NFR2.3 | `ApplyLock` tenta a cada 200 ms por até 10 s | `ApplyLockIT`: trava presa por outra conexão; a requisição responde em ≤ 11 s com `reconcileNeeded = true` |
| NFR3.1 | Leituras administrativas com índice e paginação por cursor; sem chamadas a outras Units, exceto no estado do sistema | `AdminReadLatencyIT`: 20 leituras simultâneas de cada rota (`system-status`, `invites`, `kill-switch`, `feedback`), p95 ≤ 2 s; o smoke mede cada tela em ≤ 2 s (Q5) |
| NFR3.2 | `SystemStatusAssembler` com as seis partes em paralelo (pool de 6) e `orTimeout(500 ms)` por parte | `SystemStatusIT`: uma parte com atraso de 2 s responde `UNKNOWN/TIMEOUT` e a rota inteira sai em ≤ 1 s |
| NFR3.3 | O feedback grava uma linha, depois de travar a linha da pessoa e fazer uma contagem indexada por (`user_id`, `created_at`) | `FeedbackConcurrencyIT`: 20 pessoas enviando ao mesmo tempo, p95 ≤ 500 ms |

## Pools e conexões

| Recurso | Tamanho | Motivo |
|---|---|---|
| Pool de conexão da trava de aplicação | `DataSource` Hikari dedicado, `maximumPoolSize = 1`, `minimumIdle = 1`, `connectionTimeout` igual ao tempo que sobra do orçamento de 10 s; antes dele, um `ReentrantLock` justo da JVM | Mantém a trava consultiva sem disputar conexões das requisições e sem reentrância na mesma sessão |
| Pool de threads do estado do sistema | 6 threads | Uma por parte |
| Agendador | +2 trabalhos no processo de API (reconciliação e expurgo do feedback) | O guarda de configuração do processo de API sobe o mínimo de threads do agendador em 2 |

A conexão dedicada entra na conta de conexões do NFR Design da U5 (41 de 100), que passa a 42.
