# Carga local — U4 Pedidos de titular (2026-11-11)

Gerado por `scripts/load-test.sh` com `LOAD_SCENARIO=data-requests` (perfis `test,loadtest`, 1000 pedidos semeados).

- Convidados: 20, por 300 s; um administrador
- Resultado: **aprovado**

| Medida | Valor | Limite |
|---|---|---|
| p95 de criar e listar (convidado, 2980 chamadas) | 23 ms | 500 ms |
| p95 da lista e do detalhe com prévia (administrador, 298 chamadas) | 16 ms | 1000 ms |
| Respostas inesperadas | 0 | 0 |
