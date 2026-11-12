# Carga local — U1 Documentos legais (2026-11-11)

Gerado por `scripts/load-test.sh` com `LOAD_SCENARIO=legal` (perfis `test,loadtest`).

- Usuários: 20 (metade nas páginas, metade na API) · duração: 300 s
- Resultado: **aprovado**

| Medida | Valor | Limite |
|---|---|---|
| p95 das páginas `/termos` e `/privacidade` | 146 ms | 2000 ms |
| p95 de `/api/v1/legal/**` | 14 ms | 300 ms |
| Requisições (páginas / API) | 1500 / 1500 | — |
| Respostas 5xx | 0 | 0 |
| Outros status ou falhas de rede | 0 | 0 |
