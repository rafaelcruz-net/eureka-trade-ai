# Requisitos de escalabilidade — U4 `u4-data-requests`

Conversa: português (Brasil). Fontes: `construction/u4-data-requests/functional-design/` (BR4.2, BR4.13 a BR4.15) e `inception/requirements-analysis/requirements.md` (NFR3).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.6 | Volume do beta: até 20 convidados, poucos pedidos por mês; os testes usam 1000 pedidos para ter margem | passa quando NFR3.2 e NFR3.4 passam com 1000 pedidos |
| NFR3.7 | Com várias instâncias, o job e o expurgo rodam uma só vez por ShedLock, e o aviso de vencimento sai uma só vez por pedido (atualização condicional de `overdueNotifiedAt`) | teste de integração com duas execuções concorrentes |
| NFR3.8 | Duas criações concorrentes do mesmo tipo pela mesma pessoa resultam em um pedido e um `409`, pelo índice único parcial | teste de integração concorrente |
