# Perguntas de NFR Requirements — U3 `u3-reacceptance`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Desempenho

A. Metas:
- a rota de aceite responde com p95 ≤ 500 ms, com 20 convidados simultâneos (AC4.2.4);
- a trava acrescenta no máximo 5 ms (p95) a cada requisição do convidado;
- a tela de novo aceite carrega com p95 ≤ 2 s, como as páginas legais.

O cenário de carga `u3` ganha o aceite. (recomendado)
B. Sem meta para a trava.
X. Other (please specify)

[Answer]: A

## Q2 — Observabilidade

A. Duas métricas:
- `memetrade_terms_reacceptance_blocked_total`: contador sem rótulo de pessoa;
- `memetrade_terms_acceptances_total{source}`: contador com o rótulo `source`.

A trava não registra log por requisição bloqueada, só a métrica. O aceite gera o evento `TERMS_ACCEPTED` na auditoria. (recomendado)
B. Log de cada bloqueio.
X. Other (please specify)

[Answer]: A
