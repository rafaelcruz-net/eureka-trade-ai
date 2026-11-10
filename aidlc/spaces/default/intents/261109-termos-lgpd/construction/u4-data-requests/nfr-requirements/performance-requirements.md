# Requisitos de desempenho — U4 `u4-data-requests`

Conversa: português (Brasil). Fontes: `construction/u4-data-requests/functional-design/` (BR4.8, BR4.14, BR4.19), `inception/requirements-analysis/requirements.md` (NFR3) e `nfr-requirements-questions.md` (Q1).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.1 | Criar pedido, listar os próprios pedidos e abrir um pedido respondem com p95 ≤ 500 ms, com 20 convidados simultâneos | cenário de carga novo `data-requests` (Build and Test) |
| NFR3.2 | As rotas administrativas de lista e detalhe respondem com p95 ≤ 1 s com 1000 pedidos guardados | cenário de carga `data-requests` (Build and Test), que também chama as rotas administrativas com 1000 pedidos semeados |
| NFR3.3 | A prévia do resumo de Acesso fica pronta com p95 ≤ 1 s (quatro leituras pelas interfaces J1 a J4 e os pedidos da pessoa) | o mesmo cenário de carga |
| NFR3.4 | O job de 15 minutos termina em até 30 s com 1000 pedidos abertos | teste de integração com relógio injetável |
| NFR3.5 | As consultas usam índices por `(tenant_id, user_id)` para o convidado, por `(status, due_at)` para o job e por `closed_at` para o expurgo | `EXPLAIN` nos testes de integração, com `SET LOCAL enable_seqscan = off` para o plano não depender do volume |
