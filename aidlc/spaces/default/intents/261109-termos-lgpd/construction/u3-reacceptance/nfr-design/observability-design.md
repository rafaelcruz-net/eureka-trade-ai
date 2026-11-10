# Desenho de observabilidade — U3 `u3-reacceptance`

Conversa: português (Brasil). Implementa `construction/u3-reacceptance/nfr-requirements/observability-requirements.md` (NFR8.1 a NFR8.3) e o temporizador do NFR3.2.

| Requisito | Desenho |
|---|---|
| NFR8.1 | Métricas do Micrometer, sem tag de pessoa ou tenant:<ul><li>`memetrade.terms.reacceptance.blocked` (contador);</li><li>`memetrade.terms.acceptances` (contador, tag `source`);</li><li>`memetrade.terms.reacceptance.check` (timer).</li></ul> |
| NFR8.2 | `AuditActions.TERMS_ACCEPTED` é publicado pelo `TermsAcceptanceService` só quando houve inserção |
| NFR8.3 | Nenhum log no bloqueio. No aceite, um `INFO` `Terms accepted: version=..., source=...`, sem pessoa, tenant ou carteira |
