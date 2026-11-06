# Requisitos de observabilidade — U8 Administração e feedback

Base: resposta Q5 e NFR9 de `requirements.md`.

## Requisitos

| ID | Requisito | Meta e verificação | Origem |
|---|---|---|---|
| NFR9.1 | Métricas do kill switch | Expostas pelo actuator do processo de API: `admin.killswitch.state` (0/1), `admin.killswitch.pending_bots`, `admin.killswitch.reconcile_divergences_total`, `admin.killswitch.apply_seconds` e `admin.killswitch.lift_seconds` (histogramas). Teste que lê as métricas depois de um ON e de um OFF | Q5, NFR9 |
| NFR9.2 | Métricas de feedback | `feedback.received_total` e `feedback.refused_total` com o rótulo `reason` (`VALIDATION`, `RATE_LIMITED`, `IDEMPOTENCY_CONFLICT`); sem tenant nem pessoa nos rótulos | Q5, NFR9 |
| NFR9.3 | Alertas | Log de nível ERROR com código estável quando `reconcileNeeded` dura mais de 60 s, quando `pendingBots > 0` dura mais de 2 min ou quando a reconciliação encontra divergência. Teste com relógio controlado | Q5 |
| NFR9.4 | Saúde | O indicador `adminKillSwitch` fica `DOWN` nos casos do NFR9.3 e `UP` nos outros. O indicador do expurgo do feedback mostra a data da última execução bem-sucedida | Q5, NFR9 |
| NFR9.5 | Erros e correlação | Toda resposta da U8 tem `correlationId`, e os erros seguem o RFC 7807. Métrica de erro por rota administrativa (`http.server.requests` com status) | NFR9, team.md |
| NFR9.6 | Logs sem dado sensível | Nenhum log com texto de feedback, motivo do kill switch, nonce, assinatura, token ou código de convite. Carteira só mascarada. Coberto pela varredura do NFR6.7 | NFR6, NFR7 |
