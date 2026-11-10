# Perguntas de NFR Requirements — U4 `u4-data-requests`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Desempenho

A. Metas:
- criar pedido, listar os próprios pedidos e abrir um pedido respondem com p95 ≤ 500 ms, com 20 convidados simultâneos (AC5.1.5, NFR3);
- as rotas administrativas respondem com p95 ≤ 1 s;
- montar a prévia do resumo de Acesso leva até 1 s (p95);
- o job de 15 minutos termina em até 30 s com 1000 pedidos abertos.

O cenário de carga novo `data-requests` mede os números. (recomendado)
B. Só a meta da criação.
X. Other (please specify)

[Answer]: A

## Q2 — Observabilidade

A. Itens:
- `memetrade_data_requests_open{type}`, um gauge sem rótulo de pessoa;
- `memetrade_data_requests_overdue`, um gauge;
- `memetrade_data_requests_created_total{type,origin}`;
- a saúde `dataRequests` (`DEGRADED` com vencido aberto) e o alerta `DATA_REQUEST_OVERDUE` (Alta), porque o prazo é legal;
- o expurgo com as métricas comuns de expurgo da U2.

(recomendado)
B. Só a saúde.
X. Other (please specify)

[Answer]: A

## Q3 — Proteção contra abuso

A. A criação de pedido pelo convidado usa o limite por pessoa e rota existente (`PersonRouteRateLimiter`), com 10 criações por hora. O texto é sempre tratado como texto simples nas telas, nunca como marcação. (recomendado)
B. Sem limite.
X. Other (please specify)

[Answer]: A
