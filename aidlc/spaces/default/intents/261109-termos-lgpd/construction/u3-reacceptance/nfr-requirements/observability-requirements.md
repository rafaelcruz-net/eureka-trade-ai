# Requisitos de observabilidade — U3 `u3-reacceptance`

Conversa: português (Brasil). Fontes: `construction/u3-reacceptance/functional-design/` (BR3.1, BR3.4), `inception/requirements-analysis/requirements.md` (NFR1, NFR8) e `nfr-requirements-questions.md` (Q2).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR8.1 | `memetrade_terms_reacceptance_blocked_total` (contador sem rótulo de pessoa) e `memetrade_terms_acceptances_total{source}` no `/actuator/prometheus` | teste de integração das métricas |
| NFR8.2 | Cada aceite gera `TERMS_ACCEPTED` na auditoria, pela pessoa, com `details` `{version, source}` | teste de integração |
| NFR8.3 | A trava não registra log por bloqueio (só a métrica); o aceite registra `INFO` só com a versão e o `source` | teste de log |
