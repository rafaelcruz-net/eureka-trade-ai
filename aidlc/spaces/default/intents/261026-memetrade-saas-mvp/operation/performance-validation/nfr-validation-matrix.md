# Matriz de validação dos NFRs de desempenho e escala

Conversa: português (Brasil). Respostas 1A a 5A de `performance-validation-questions.md`. Fontes:
- requisitos: `performance-requirements.md` e `scalability-requirements.md` de cada Unit (`construction/<unit>/nfr-requirements/`);
- desenho: `performance-design.md` e `scalability-design.md` (`construction/<unit>/nfr-design/`);
- evidência: `construction/build-and-test/test-results.md` e `performance-test-instructions.md` (cargas de 2026-11-06 a 2026-11-08) e o smoke da release `v2026.11.08-1` (`operation/deployment-execution/smoke-test-results.md`);
- métrica de acompanhamento: `operation/observability-setup/dashboards.md`.

Estados: **Met** (atendido com evidência), **Not Met** (medido e fora da meta), **Unverified** (sem medição). A release `8fd7a7b` não muda código medido em relação a `0cd9ccf`, por isso as medições valem para ela.

## Desempenho (NFR3)

| Unit | NFR | Meta | Medido | Estado | Painel |
|---|---|---|---|---|---|
| U1 | NFR3.1 | p95 ≤ 300 ms (desafio, verificação, lista) | 45 / 224 / 45 ms (`u1`) | Met | Visão geral → latência por rota |
| U1 | NFR3.2 | tela da lista p95 ≤ 2 s | smoke S3 "em até 2 s", ok nas 3 execuções da release | Met | — |
| U2 | NFR3.1 | consulta da trilha p95 ≤ 2 s | `perf-audit.sh` não terminou (memória) | **Unverified** | Domínio → auditoria |
| U2 | NFR3.2 | publicação não piora as rotas | cargas das Units publicadoras dentro das metas | Met | Visão geral |
| U2 | NFR3.3 | expurgo e limpeza ≤ 30 min | `perf-audit.sh` não terminou | **Unverified** | Domínio → rodadas da auditoria |
| U2 | NFR4.2 | 99% das entregas em até 5 s | `perf-audit.sh` não terminou | **Unverified** | Domínio → atraso da auditoria |
| U3 | NFR3.1 | verificação com convite p95 ≤ 500 ms | 259 ms (`u3`) | Met | Visão geral |
| U3 | NFR3.2 | convite e Conta p95 ≤ 300 ms | 4 / 7 ms | Met | Visão geral |
| U3 | NFR3.3 | rotas administrativas de convite p95 ≤ 300 ms | Build and Test | Met | Visão geral |
| U3 | NFR3.4 | telas de convite e Conta p95 ≤ 2 s | smoke U3 ok nas 3 execuções | Met | — |
| U4 | NFR3.1 | lista p95 ≤ 1 s com 2 000 tokens | 500 ms (`u4`) | Met | Visão geral; Domínio → scanner |
| U4 | NFR3.2 | detalhe p95 ≤ 500 ms | 11 ms | Met | Visão geral |
| U4 | NFR3.3 | tela do scanner p95 ≤ 2 s | smoke U4 ok | Met | — |
| U4 | NFR3.4 | contadores sem custo por página | Build and Test | Met | — |
| U4 | NFR3.5 | crescimento para 10 000 tokens avaliado antes do G1 | lista 10,02 s (`growth`) | **Not Met** | Visão geral; pool do banco |
| U5 | NFR3.1 | rotas C4 p95 ≤ 1 s | `u5` aprovado | Met | Visão geral |
| U5 | NFR3.2 | tela S5 p95 ≤ 2 s | 27 ms | Met | — |
| U5 | NFR3.3 | canal ao vivo ≤ 4 s | 1,9 s | Met | Domínio → bots |
| U6 | NFR3.1 | rota C21 p95 ≤ 1 s | `u6` aprovado nas 3 cargas | Met | Visão geral |
| U6 | NFR3.2 | tela com recusas p95 ≤ 2 s | smoke U5 ok | Met | — |
| U6 | (ciclo) | `evaluate` p95 ≤ 250 ms; lote do expurgo ≤ 100 ms | ≤ 21,93 ms; lote máximo 58 ms | Met | Domínio → controle de risco |
| U7 | NFR3.1 | `execute`/`close` p95 ≤ 250 ms | 34 / 62 ms | Met | Domínio → ledger |
| U7 | NFR3.2 | P&L e ledger p95 ≤ 1 s | ledger 201 ms | Met | Visão geral |
| U7 | NFR3.3 | demais rotas C5 p95 ≤ 1 s | `u7` aprovado | Met | Visão geral |
| U7 | NFR3.4 | tela de P&L p95 ≤ 2 s | smoke U7 ok | Met | — |
| U7 | NFR3.5 | rodadas p95 ≤ 10 s | marcação até 317 ms, fechamento até 4 ms (`u7-jobs`) | Met | Domínio → ledger |
| U7 | NFR3.6 | reconciliação ≤ 60 s (20 pessoas × 10 000) | 25,4 s (`u7`); rodada curta até 236 ms (`u7-jobs`) | Met | Domínio → ledger |
| U8 | NFR3.1 | telas do administrador p95 ≤ 2 s | `AdminReadLatencyIT` | Met | — |
| U8 | NFR3.2 | `system-status` p95 ≤ 1 s | integração e smoke U8 | Met | Visão geral |
| U8 | NFR3.3 | feedback p95 ≤ 500 ms | `FeedbackConcurrencyIT` | Met | Domínio → feedback |
| U9 | NFR3.1 | cada quadro p95 ≤ 2 s | `DashboardOpenLatencyIT` registra o p95 sem reprovar (decisão da pessoa); passou nas suítes da volta 3, e uma vez ficou em 2,03 s | Met (registrado) | — |
| U9 | NFR3.2 a 3.4 | carregando ≤ 200 ms; sem rajada; buscas sem acúmulo | testes do frontend | Met | — |

## Escala (NFR2 e NFR3 de escala)

| Unit | NFRs | Estado | Evidência |
|---|---|---|---|
| U1 | NFR3.3 a 3.5 (uma instância, volume simulado, contadores do limite) | Met | `u1` e testes de integração |
| U2 | NFR2.1 a 2.4 (volume, outbox limitado, picos, tarefas) | Met nos testes de integração; a carga contínua de 1 h está pendente no `perf-audit` | integração; `perf-audit` pendente |
| U3 | NFR2.1 a 2.4 | Met | `u3`, integração |
| U4 | NFR2.4 a 2.8 | Met | `u4`, planos sem varredura cara |
| U5 | NFR2.4 a 2.6 | Met | `u5` (semente de 400 000 decisões) |
| U6 | NFR2.3 a 2.6 | Met | `u6` (10 000 recusas por bot; planos sem `Seq Scan`) |
| U7 | NFR2.1 a 2.5 | Met | `u7`, `u7-jobs` |
| U8 | NFR2.4 a 2.7 | Met | integração |

## Resumo

- **Met:** todos os NFRs de desempenho e escala, menos os quatro abaixo.
- **Not Met (1):** U4-NFR3.5. Decisão: réplica de leitura com a hospedagem; nova medição do `growth` no staging antes do G1. A meta de 1 s não muda.
- **Unverified (3):** U2-NFR3.1, NFR3.3 e NFR4.2. O `perf-audit.sh` precisa de uma máquina com mais memória ou do staging; o responsável do projeto roda antes do G1.
- Nenhuma meta foi afrouxada.
