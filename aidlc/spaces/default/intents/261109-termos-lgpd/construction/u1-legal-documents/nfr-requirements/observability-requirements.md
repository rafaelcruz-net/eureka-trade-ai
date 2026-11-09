# Requisitos de observabilidade — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes: `construction/u1-legal-documents/functional-design/` (BR1.1), `inception/requirements-analysis/requirements.md` (NFR8), `inception/contract-design/contract-summary.md` (L1) e `nfr-requirements-questions.md` (Q4).

## Requisitos

| ID | Sinal | Detalhe |
|---|---|---|
| NFR8.1 | Log na subida | Um `INFO` com a versão vigente e o total de versões; um `WARN` por versão ignorada, com a versão e o motivo (sem revisão, vigência futura, não publicável). Nunca o texto |
| NFR8.2 | Saúde `legalDocuments` | Detalhes: versão vigente e se é publicável. `UP` quando publicável; `DEGRADED` quando não, com HTTP 200. Fica fora dos grupos de liveness e readiness |
| NFR8.3 | Métrica | Requisições por rota de documentos e status, na métrica HTTP existente, sem rótulo de dado pessoal |

## SLI

O p95 da rota de documentos, a partir da métrica HTTP, e a taxa de 5xx dessas rotas. Metas em `performance-requirements.md`. Sem SLO de disponibilidade no beta local, como no restante do produto.
